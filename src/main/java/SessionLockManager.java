import java.io.IOException;
import java.net.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Session Lock Manager - Ngăn chặn đăng nhập trùng lặp trên LAN
 * 
 * Chiến lược: Tích hợp với NetworkManager để sử dụng peer discovery
 * - Local lock: Ngăn đăng nhập nhiều lần trên cùng máy
 * - LAN lock: Kiểm tra qua discovery port của NetworkManager
 */
public class SessionLockManager {
    private static final int BASE_PORT = 20000; // Port cho local lock
    private static final int MOD_RANGE = 20000;
    
    private static SessionLockManager instance;
    
    private ServerSocket lockSocket;
    private String lockedUsername;
    private int lockedPort = -1;
    
    // Discovery scan timeout
    private static final int SCAN_TIMEOUT_MS = 3000; // 3 giây
    
    public static synchronized SessionLockManager getInstance() {
        if (instance == null) {
            instance = new SessionLockManager();
        }
        return instance;
    }
    
    private int getPortForUsername(String username) {
        int offset = Math.abs(username.hashCode() % MOD_RANGE);
        return BASE_PORT + offset;
    }
    
    /**
     * CÁCH TIẾP CẬN MỚI: Sử dụng Discovery Port để phát hiện phiên active
     * 
     * NetworkManager đã có discovery port (11000-11999) đang chạy liên tục
     * Nếu user đã đăng nhập → discovery port của họ đang mở
     * Ta chỉ cần scan discovery port của username đó!
     */
    public synchronized boolean acquire(String username) {
        release(); // Dọn lock cũ
        
        int localPort = getPortForUsername(username);
        
        // BƯỚC 1: Thử acquire LOCAL lock (cùng máy)
        if (!acquireLocalLock(username, localPort)) {
            System.out.println("🔒 [SESSION] User " + username + " already logged in on THIS machine");
            return false;
        }
        
        // BƯỚC 2: Quét LAN tìm discovery port của user này
        if (!checkLANSessionFree(username)) {
            // Có phiên đang active trên máy khác → release local lock
            try {
                if (lockSocket != null) {
                    lockSocket.close();
                    lockSocket = null;
                }
            } catch (IOException e) {
                // Ignore
            }
            System.out.println("🔒 [SESSION] User " + username + " already logged in on ANOTHER machine in LAN");
            return false;
        }
        
        // THÀNH CÔNG: Cả local và LAN đều OK
        lockedUsername = username;
        lockedPort = localPort;
        
        System.out.println("🔐 [SESSION] Lock acquired for " + username);
        System.out.println("  - Local lock: 127.0.0.1:" + localPort);
        System.out.println("  - LAN check: PASSED (no active session found)");
        
        return true;
    }
    
    private boolean acquireLocalLock(String username, int port) {
        try {
            // Bind localhost với SO_REUSEADDR=false để exclusive lock
            lockSocket = new ServerSocket();
            lockSocket.setReuseAddress(false); // QUAN TRỌNG: không cho reuse
            lockSocket.bind(new InetSocketAddress("127.0.0.1", port), 1);
            
            System.out.println("✅ [LOCAL] Acquired local lock for " + username + " on port " + port);
            return true;
        } catch (BindException e) {
            System.out.println("🔒 [LOCAL] Port " + port + " already in use (user logged in locally)");
            return false;
        } catch (IOException e) {
            System.err.println("❌ [LOCAL] Lock error: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Kiểm tra xem có phiên nào của user này đang active trên LAN không
     * 
     * Logic: Discovery port = 11000 + hash(username) % 1000
     * Nếu user đã đăng nhập → NetworkManager của họ đang bind discovery port
     * Ta chỉ cần thử kết nối đến port đó!
     */
    private boolean checkLANSessionFree(String username) {
        try {
            String myIp = getRealLocalIp();
            String subnet = myIp.substring(0, myIp.lastIndexOf('.'));
            
            // Discovery port theo logic của NetworkManager (CHỈ 50 PORTS)
            int discoveryPort = 11000 + Math.abs(username.hashCode() % 50);
            
            System.out.println("🔍 [LAN] Scanning for active session of '" + username + "'");
            System.out.println("  - Target discovery port: " + discoveryPort);
            System.out.println("  - My IP: " + myIp);
            System.out.println("  - Scanning subnet: " + subnet + ".x");
            
            // TỐI ƯU: Quét song song nhiều IP cùng lúc
            ExecutorService executor = Executors.newFixedThreadPool(50);
            AtomicBoolean foundActiveSession = new AtomicBoolean(false);
            
            // 1. Ưu tiên: Scan localhost TRƯỚC (nhanh nhất)
            if (!myIp.equals("127.0.0.1")) {
                if (tryConnectToDiscoveryPort(myIp, discoveryPort, 200)) {
                    System.out.println("🔒 [LAN] Found active session on localhost:" + discoveryPort);
                    executor.shutdownNow();
                    return false;
                }
            }
            
            // 2. Scan các IP khác trong subnet (song song)
            for (int i = 1; i < 255; i++) {
                final String targetIp = subnet + "." + i;
                
                // Bỏ qua chính mình
                if (targetIp.equals(myIp)) {
                    continue;
                }
                
                executor.submit(() -> {
                    if (foundActiveSession.get()) {
                        return; // Đã tìm thấy, skip
                    }
                    
                    if (tryConnectToDiscoveryPort(targetIp, discoveryPort, 200)) {
                        foundActiveSession.set(true);
                        System.out.println("🔒 [LAN] Found active session at " + targetIp + ":" + discoveryPort);
                    }
                });
            }
            
            // Đợi scan xong hoặc timeout
            executor.shutdown();
            boolean finished = executor.awaitTermination(SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            
            if (!finished) {
                System.out.println("⏱️ [LAN] Scan timeout after " + SCAN_TIMEOUT_MS + "ms");
                executor.shutdownNow();
            }
            
            if (foundActiveSession.get()) {
                System.out.println("❌ [LAN] Active session detected - login DENIED");
                return false;
            } else {
                System.out.println("✅ [LAN] No active session found - login ALLOWED");
                return true;
            }
            
        } catch (Exception e) {
            System.err.println("⚠️ [LAN] Scan error: " + e.getMessage());
            // Nếu không scan được, CHO PHÉP đăng nhập (fail-open để không block user)
            return true;
        }
    }
    
    /**
     * Thử kết nối đến discovery port để kiểm tra có đang active không
     */
    private boolean tryConnectToDiscoveryPort(String ip, int port, int timeoutMs) {
        try {
            Socket testSocket = new Socket();
            testSocket.connect(new InetSocketAddress(ip, port), timeoutMs);
            testSocket.close();
            return true; // Kết nối được → port đang mở → có phiên active
        } catch (IOException e) {
            return false; // Không kết nối được → không có phiên active
        }
    }
    
    /**
     * Release lock khi đăng xuất hoặc thoát app
     */
    public synchronized void release() {
        if (lockSocket != null && !lockSocket.isClosed()) {
            try {
                lockSocket.close();
                System.out.println("🔓 [SESSION] Released lock for " + lockedUsername);
            } catch (IOException e) {
                System.err.println("⚠️ [SESSION] Error releasing lock: " + e.getMessage());
            }
        }
        
        lockSocket = null;
        lockedUsername = null;
        lockedPort = -1;
    }
    
    /**
     * Lấy IP thực của máy (ưu tiên WiFi/Ethernet, bỏ qua WSL/Virtual)
     */
    private String getRealLocalIp() {
        try {
            java.util.Enumeration<NetworkInterface> interfaces = 
                NetworkInterface.getNetworkInterfaces();
                
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                
                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }
                
                String ifaceName = iface.getDisplayName().toLowerCase();
                if (ifaceName.contains("wsl") || ifaceName.contains("virtual") || 
                    ifaceName.contains("vmware") || ifaceName.contains("vbox")) {
                    continue;
                }
                
                java.util.Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || 
                            ip.startsWith("172.16.") || ip.startsWith("172.31.")) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("⚠️ Error detecting IP: " + e.getMessage());
        }
        
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }
    
    /**
     * Kiểm tra xem user có đang giữ lock không
     */
    public synchronized boolean isLocked(String username) {
        return lockedUsername != null && lockedUsername.equals(username);
    }
    
    /**
     * Get thông tin lock hiện tại (for debugging)
     */
    public synchronized String getLockedInfo() {
        if (lockedUsername == null) {
            return "No active lock";
        }
        return "Locked: " + lockedUsername + " on port " + lockedPort;
    }
}