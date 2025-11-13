import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class NetworkManager {
    private MainController mainController;
    private String currentUser;
    private int myTcpPort;
    private int myFilePort;
    private int myDiscoveryPort;

    private ServerSocket serverSocket;
    private ServerSocket discoveryServer;
    private ServerSocket fileServer;

    private ConcurrentHashMap<String, PeerConnection> peerConnections;
    private ConcurrentHashMap<String, PeerInfo> discoveredPeers;
    private ConcurrentHashMap<String, ChatGroup> chatGroups;

    public NetworkManager(MainController mainController) {
        this.mainController = mainController;
        this.peerConnections = new ConcurrentHashMap<>();
        this.discoveredPeers = new ConcurrentHashMap<>();
        this.chatGroups = new ConcurrentHashMap<>();
    }

    public void initialize(String currentUser, int tcpPort, int filePort) {
        this.currentUser = currentUser;
        this.myTcpPort = tcpPort;
        this.myFilePort = filePort;
        // Discovery port khác range với TẤT CẢ các port khác để tránh xung đột
        // TCP: 8888-9887, File: 8890-9889, Voice: 9xxx, Video: 9xxx, VideoAudio: 10xxx
        // Discovery: 11000-11049 (CHỈ 50 PORTS - NHANH HƠN)
        this.myDiscoveryPort = 11000 + Math.abs(currentUser.hashCode() % 50);
    }

    public void startServer() {
        startChatServer();
        startFileServer();
        startDiscoveryServer();
        announcePresence();
        startHeartbeatChecker();
    }

    private void startChatServer() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(myTcpPort);
                System.out.println("✓ Chat server started on port: " + myTcpPort);

                while (!Thread.currentThread().isInterrupted()) {
                    Socket clientSocket = serverSocket.accept();
                    handleNewConnection(clientSocket);
                }
            } catch (IOException e) {
                System.out.println("Chat server stopped");
            }
        }).start();
    }

    private void startFileServer() {
        new Thread(() -> {
            try {
                fileServer = new ServerSocket(myFilePort);
                System.out.println("✓ File server started on port: " + myFilePort);

                while (!Thread.currentThread().isInterrupted()) {
                    Socket clientSocket = fileServer.accept();
                    handleFileTransfer(clientSocket);
                }
            } catch (IOException e) {
                System.out.println("File server stopped");
            }
        }).start();
    }

    private void startDiscoveryServer() {
        new Thread(() -> {
            try {
                discoveryServer = new ServerSocket(myDiscoveryPort);
                System.out.println("✓ Discovery server started on port: " + myDiscoveryPort);

                while (!Thread.currentThread().isInterrupted()) {
                    Socket socket = discoveryServer.accept();
                    handleDiscoveryConnection(socket);
                }
            } catch (IOException e) {
                System.out.println("Discovery server stopped: " + e.getMessage());
            }
        }).start();
    }

    private void handleNewConnection(Socket socket) {
        new Thread(() -> {
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), "UTF-8"));
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                        socket.getOutputStream(), "UTF-8"), true);

                String hello = reader.readLine();
                if (hello != null && hello.startsWith("HELLO:")) {
                    String peerName = hello.split(":")[1];

                    PeerConnection connection = new PeerConnection(socket, peerName, reader, writer);
                    peerConnections.put(peerName, connection);

                    writer.println("HELLO:" + currentUser);

                    System.out.println("🤝 Established connection with: " + peerName);

                    String message;
                    while ((message = reader.readLine()) != null) {
                        handlePeerMessage(peerName, message);
                    }

                    peerConnections.remove(peerName);
                    System.out.println("❌ Connection closed: " + peerName);
                    
                    // Kiểm tra xem peer còn online không trước khi xóa khỏi danh sách
                    if (!peerConnections.containsKey(peerName)) {
                        checkPeerOffline(peerName);
                    }
                }
            } catch (IOException e) {
                // Connection error
            }
        }).start();
    }

    private void handleDiscoveryConnection(Socket socket) {
        new Thread(() -> {
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream()));
                PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);

                String message = reader.readLine();
                if (message != null && message.startsWith("ANNOUNCE:")) {
                    String[] parts = message.split(":");
                    if (parts.length >= 8) {
                        String peerName = parts[1];
                        int peerPort = Integer.parseInt(parts[2]);
                        int peerFilePort = Integer.parseInt(parts[3]);
                        int peerVoicePort = Integer.parseInt(parts[4]);
                        int peerVideoPort = Integer.parseInt(parts[5]);
                        int peerVideoAudioPort = Integer.parseInt(parts[6]);
                        int peerDiscoveryPort = Integer.parseInt(parts[7]);
                        String peerIp = socket.getInetAddress().getHostAddress();

                        if (!peerName.equals(currentUser)) {
                            writer.println("PEER:" + currentUser + ":" + myTcpPort + ":" + myFilePort +
                                    ":" + mainController.getCallManager().getVoiceCallManager().getVoicePort() +
                                    ":" + mainController.getCallManager().getVideoCallManager().getVideoPort() +
                                    ":" + mainController.getCallManager().getVideoCallManager().getAudioPort() +
                                    ":" + myDiscoveryPort);
                            
                            // TRƯỚC khi thêm peer mới, gửi thông tin các peer hiện có cho peer mới
                            sendExistingPeersToNewPeer(peerName, peerIp, peerDiscoveryPort);
                            
                            // Thêm peer mới vào danh sách
                            addPeer(peerName, peerIp, peerPort, peerFilePort, peerVoicePort, peerVideoPort, peerVideoAudioPort, peerDiscoveryPort);
                            System.out.println("✓ Discovered peer: " + peerName + " at " + peerIp + ":" + peerPort);
                            
                            // SAU khi thêm, thông báo cho tất cả peer hiện có về peer mới
                            notifyExistingPeersAboutNewPeer(peerName, peerIp, peerPort, peerFilePort, peerVoicePort, peerVideoPort, peerVideoAudioPort, peerDiscoveryPort);
                        }
                    }
                } else if (message != null && message.startsWith("PEER_NOTIFY:")) {
                    // Nhận thông báo về peer mới từ peer khác
                    System.out.println("📬 [" + currentUser + "] Received PEER_NOTIFY: " + message);
                    String[] parts = message.split(":");
                    if (parts.length >= 9) {
                        String peerName = parts[1];
                        String peerIp = parts[2];
                        int peerPort = Integer.parseInt(parts[3]);
                        int peerFilePort = Integer.parseInt(parts[4]);
                        int peerVoicePort = Integer.parseInt(parts[5]);
                        int peerVideoPort = Integer.parseInt(parts[6]);
                        int peerVideoAudioPort = Integer.parseInt(parts[7]);
                        int peerDiscoveryPort = Integer.parseInt(parts[8]);

                        if (!peerName.equals(currentUser)) {
                            addPeer(peerName, peerIp, peerPort, peerFilePort, peerVoicePort, peerVideoPort, peerVideoAudioPort, peerDiscoveryPort);
                            System.out.println("✅ [" + currentUser + "] Added peer from notification: " + peerName + " at " + peerIp + ":" + peerPort);
                        } else {
                            System.out.println("⚠️ [" + currentUser + "] Ignored self notification");
                        }
                    } else {
                        System.out.println("⚠️ [" + currentUser + "] Invalid PEER_NOTIFY format, parts.length=" + parts.length);
                    }
                }

                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void handleFileTransfer(Socket socket) {
        new Thread(() -> {
            try {
                DataInputStream dis = new DataInputStream(socket.getInputStream());
                DataOutputStream dos = new DataOutputStream(socket.getOutputStream());

                String command = dis.readUTF();

                if (command.equals("REQUEST_FILE")) {
                    String uniqueFileName = dis.readUTF();
                    File file = new File("shared_files/" + uniqueFileName);

                    if (file.exists()) {
                        dos.writeUTF("OK");
                        dos.writeLong(file.length());
                        dos.writeUTF(file.getName());

                        FileInputStream fis = new FileInputStream(file);
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        long totalSent = 0;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            dos.write(buffer, 0, bytesRead);
                            totalSent += bytesRead;
                        }
                        fis.close();
                        dos.flush();
                        System.out.println("✓ Sent file: " + uniqueFileName + " (" + totalSent + " bytes)");
                    } else {
                        dos.writeUTF("ERROR");
                        dos.writeUTF("File not found");
                        System.err.println("✗ File not found: " + uniqueFileName);
                    }
                }

                socket.close();
            } catch (IOException e) {
                System.err.println("✗ Error in file transfer: " + e.getMessage());
            }
        }).start();
    }

    public void announcePresence() {
        new Thread(() -> {
            ExecutorService executor = Executors.newFixedThreadPool(50); // Giới hạn 50 thread đồng thời
            try {
                // Lấy IP thực của WiFi/Ethernet, không phải WSL
                String myIp = getRealLocalIp();
                String subnet = myIp.substring(0, myIp.lastIndexOf('.'));

                System.out.println("🔍 My IP: " + myIp);
                System.out.println("🔍 My Discovery Port: " + myDiscoveryPort);
                System.out.println("🔍 Scanning network: " + subnet + ".x on discovery ports 11000-11049 (50 ports)");

                // ƯU TIÊN scan localhost trước (cùng máy)
                System.out.println("🔍 Priority: Scanning localhost first...");
                // Scan các port discovery từ 11000-11049 (CHỈ 50 PORTS)
                for (int offset = 0; offset < 50; offset++) {
                    int port = 11000 + offset;
                    if (port == myDiscoveryPort) {
                        continue; // Bỏ qua port của chính mình
                    }
                    
                    final int scanPort = port;
                    executor.submit(() -> {
                        try {
                            Socket socket = new Socket();
                            socket.connect(new InetSocketAddress(myIp, scanPort), 200);

                            System.out.println("🔗 Connected to localhost:" + scanPort);

                            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                            writer.println("ANNOUNCE:" + currentUser + ":" + myTcpPort + ":" + myFilePort +
                                    ":" + mainController.getCallManager().getVoiceCallManager().getVoicePort() +
                                    ":" + mainController.getCallManager().getVideoCallManager().getVideoPort() +
                                    ":" + mainController.getCallManager().getVideoCallManager().getAudioPort() +
                                    ":" + myDiscoveryPort);

                            BufferedReader reader = new BufferedReader(
                                    new InputStreamReader(socket.getInputStream()));
                            String response = reader.readLine();

                            System.out.println("📥 Response from port " + scanPort + ": " + response);

                            if (response != null && response.startsWith("PEER:")) {
                                String[] parts = response.split(":");
                                System.out.println("✅ Valid PEER response, parts: " + parts.length);
                                if (parts.length >= 8) {
                                    String peerName = parts[1];
                                    int peerPort = Integer.parseInt(parts[2]);
                                    int peerFilePort = Integer.parseInt(parts[3]);
                                    int peerVoicePort = Integer.parseInt(parts[4]);
                                    int peerVideoPort = Integer.parseInt(parts[5]);
                                    int peerVideoAudioPort = Integer.parseInt(parts[6]);
                                    int peerDiscoveryPort = Integer.parseInt(parts[7]);

                                    System.out.println("👤 Found peer: " + peerName + " (current: " + currentUser + ")");
                                    
                                    if (!peerName.equals(currentUser)) {
                                        addPeer(peerName, myIp, peerPort, peerFilePort, peerVoicePort, peerVideoPort, peerVideoAudioPort, peerDiscoveryPort);
                                        System.out.println("✅ Successfully added peer: " + peerName);
                                    } else {
                                        System.out.println("⏭️ Skipped self: " + peerName);
                                    }
                                }
                            }

                            socket.close();
                        } catch (IOException e) {
                            // Port not open - this is normal, don't log
                        }
                    });
                }
                
                // SAU ĐÓ scan các IP khác trong subnet (các máy khác)
                System.out.println("🔍 Finding active hosts in subnet...");
                
                // BƯỚC 1: Tìm các IP đang hoạt động (NHANH - chỉ mất vài giây)
                java.util.List<String> activeHosts = new java.util.ArrayList<>();
                java.util.concurrent.CountDownLatch pingLatch = new java.util.concurrent.CountDownLatch(254);
                
                long pingStartTime = System.currentTimeMillis();
                
                for (int i = 1; i < 255; i++) {
                    final String targetIp = subnet + "." + i;
                    if (targetIp.equals(myIp)) {
                        pingLatch.countDown(); // Bỏ qua localhost
                        continue;
                    }
                    
                    executor.submit(() -> {
                        try {
                            InetAddress addr = InetAddress.getByName(targetIp);
                            if (addr.isReachable(300)) { // Tăng timeout lên 300ms
                                synchronized(activeHosts) {
                                    activeHosts.add(targetIp);
                                }
                                System.out.println("✓ Host alive (ping): " + targetIp);
                            } else {
                                // Nếu ping thất bại, thử TCP connect đến port 445 (SMB - thường mở trên Windows)
                                try {
                                    Socket testSocket = new Socket();
                                    testSocket.connect(new InetSocketAddress(targetIp, 445), 200);
                                    testSocket.close();
                                    synchronized(activeHosts) {
                                        activeHosts.add(targetIp);
                                    }
                                    System.out.println("✓ Host alive (TCP 445): " + targetIp);
                                } catch (IOException e) {
                                    // Thử port 135 (RPC - cũng thường mở trên Windows)
                                    try {
                                        Socket testSocket2 = new Socket();
                                        testSocket2.connect(new InetSocketAddress(targetIp, 135), 200);
                                        testSocket2.close();
                                        synchronized(activeHosts) {
                                            activeHosts.add(targetIp);
                                        }
                                        System.out.println("✓ Host alive (TCP 135): " + targetIp);
                                    } catch (IOException e2) {
                                        // Host thực sự không hoạt động
                                    }
                                }
                            }
                        } catch (Exception e) {
                            // Host không hoạt động
                        } finally {
                            pingLatch.countDown();
                        }
                    });
                }
                
                // Đợi ping xong HOẶC timeout 8 giây
                boolean pingFinished = pingLatch.await(8, TimeUnit.SECONDS);
                long pingDuration = System.currentTimeMillis() - pingStartTime;
                System.out.println("🔍 Ping completed in " + pingDuration + "ms: " + pingFinished + ", Found " + activeHosts.size() + " active hosts");
                System.out.println("🔍 Active hosts: " + activeHosts);
                
                // BƯỚC 2: CHỈ scan discovery port trên các IP ĐANG HOẠT ĐỘNG
                long scanStartTime = System.currentTimeMillis();
                
                // QUAN TRỌNG: Nếu ít active hosts (<5), thêm scan một số IP phổ biến
                // Vì một số máy Windows chặn ICMP ping nhưng vẫn mở ports
                if (activeHosts.size() < 5) {
                    System.out.println("⚠️ Only " + activeHosts.size() + " hosts found via ping. Adding common IPs as fallback...");
                    // Thêm các IP phổ biến trong dải 192.168.1.x
                    for (int i = 50; i < 100; i++) { // Scan .50-.99 (thường là DHCP range)
                        String commonIp = subnet + "." + i;
                        if (!commonIp.equals(myIp) && !activeHosts.contains(commonIp)) {
                            activeHosts.add(commonIp);
                        }
                    }
                    System.out.println("🔍 Extended scan list to " + activeHosts.size() + " IPs");
                }
                
                System.out.println("🔍 Starting discovery port scan (50 ports per host) on " + activeHosts.size() + " hosts...");
                
                for (String activeIp : activeHosts) {
                    System.out.println("🔍 Scanning " + activeIp + " for discovery ports 11000-11049 (50 ports)...");
                    for (int offset = 0; offset < 50; offset++) {
                        final int scanPort = 11000 + offset;
                        final String targetIp = activeIp;
                        
                        executor.submit(() -> {
                            try {
                                Socket socket = new Socket();
                                socket.connect(new InetSocketAddress(targetIp, scanPort), 200);

                                System.out.println("🔗 Connected to " + targetIp + ":" + scanPort);

                                PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                                writer.println("ANNOUNCE:" + currentUser + ":" + myTcpPort + ":" + myFilePort +
                                        ":" + mainController.getCallManager().getVoiceCallManager().getVoicePort() +
                                        ":" + mainController.getCallManager().getVideoCallManager().getVideoPort() +
                                        ":" + mainController.getCallManager().getVideoCallManager().getAudioPort() +
                                        ":" + myDiscoveryPort);

                                BufferedReader reader = new BufferedReader(
                                        new InputStreamReader(socket.getInputStream()));
                                String response = reader.readLine();

                                System.out.println("📥 Response from " + targetIp + ":" + scanPort + " = " + response);

                                if (response != null && response.startsWith("PEER:")) {
                                    String[] parts = response.split(":");
                                    if (parts.length >= 8) {
                                        String peerName = parts[1];
                                        int peerPort = Integer.parseInt(parts[2]);
                                        int peerFilePort = Integer.parseInt(parts[3]);
                                        int peerVoicePort = Integer.parseInt(parts[4]);
                                        int peerVideoPort = Integer.parseInt(parts[5]);
                                        int peerVideoAudioPort = Integer.parseInt(parts[6]);
                                        int peerDiscoveryPort = Integer.parseInt(parts[7]);

                                        System.out.println("👤 Found peer on other machine: " + peerName + " at " + targetIp);

                                        if (!peerName.equals(currentUser)) {
                                            addPeer(peerName, targetIp, peerPort, peerFilePort, peerVoicePort, peerVideoPort, peerVideoAudioPort, peerDiscoveryPort);
                                            System.out.println("✅ Successfully added remote peer: " + peerName + " (" + targetIp + ")");
                                        } else {
                                            System.out.println("⏭️ Skipped self: " + peerName);
                                        }
                                    }
                                }

                                socket.close();
                            } catch (IOException e) {
                                // Port not open or connection refused
                            }
                        });
                    }
                }
                
                executor.shutdown();
                boolean finished = executor.awaitTermination(60, TimeUnit.SECONDS); // Giảm xuống 60s vì ít port hơn
                long totalDuration = System.currentTimeMillis() - pingStartTime;
                long scanDuration = System.currentTimeMillis() - scanStartTime;
                System.out.println("✅ Network scan completed (finished=" + finished + ")");
                System.out.println("⏱️ Total time: " + totalDuration + "ms (Ping: " + pingDuration + "ms, Scan: " + scanDuration + "ms)");
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                executor.shutdownNow();
            }
        }).start();
    }

    private String getRealLocalIp() {
        try {
            System.out.println("🔍 Detecting network interfaces...");
            // Thử tìm IP không phải loopback và không phải WSL
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface iface = interfaces.nextElement();
                
                System.out.println("  📡 Interface: " + iface.getDisplayName() + " (up=" + iface.isUp() + ", loopback=" + iface.isLoopback() + ")");
                
                // Bỏ qua interface đã tắt và loopback
                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }
                
                String ifaceName = iface.getDisplayName().toLowerCase();
                // Ưu tiên WiFi và Ethernet, bỏ qua WSL
                if (ifaceName.contains("wsl") || ifaceName.contains("virtual") || 
                    ifaceName.contains("vmware") || ifaceName.contains("vbox")) {
                    System.out.println("  ⏭️  Skipping virtual interface: " + iface.getDisplayName());
                    continue;
                }
                
                java.util.Enumeration<java.net.InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    System.out.println("    🔸 Address: " + addr.getHostAddress() + " (IPv4=" + (addr instanceof java.net.Inet4Address) + ")");
                    
                    // Chỉ lấy IPv4, không phải loopback
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        // Ưu tiên dải 192.168.x.x (WiFi thường dùng)
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            System.out.println("✅ Selected IP: " + ip + " on " + iface.getDisplayName());
                            return ip;
                        }
                    }
                }
            }
            
            System.out.println("⚠️ No suitable IP found, using fallback...");
        } catch (Exception e) {
            System.err.println("❌ Error detecting IP: ");
            e.printStackTrace();
        }
        
        // Fallback về localhost nếu không tìm thấy
        try {
            String fallbackIp = InetAddress.getLocalHost().getHostAddress();
            System.out.println("🔄 Fallback IP: " + fallbackIp);
            return fallbackIp;
        } catch (Exception e) {
            System.out.println("🔄 Ultimate fallback: 127.0.0.1");
            return "127.0.0.1";
        }
    }

    private void addPeer(String username, String ip, int port, int filePort,
                         int voicePort, int videoPort, int videoAudioPort, int discoveryPort) {
        if (!discoveredPeers.containsKey(username)) {
            PeerInfo peer = new PeerInfo(ip, port, filePort, voicePort, videoPort, videoAudioPort, discoveryPort);
            discoveredPeers.put(username, peer);

            mainController.getChatManager().refreshContactList();
            System.out.println("➕ Added peer: " + username);
        }
    }

    private void removePeer(String username) {
        if (discoveredPeers.containsKey(username)) {
            discoveredPeers.remove(username);
            mainController.getChatManager().refreshContactList();
            System.out.println("➖ Removed peer (offline): " + username);
        }
    }

    private void checkPeerOffline(String username) {
        // Kiểm tra xem peer có còn online không bằng cách thử kết nối lại
        PeerInfo peer = discoveredPeers.get(username);
        if (peer == null) {
            return; // Đã bị xóa rồi
        }

        new Thread(() -> {
            try {
                // Thử kết nối đến discovery port để kiểm tra peer còn online không
                Socket testSocket = new Socket();
                testSocket.connect(new InetSocketAddress(peer.ip, peer.discoveryPort), 1000);
                testSocket.close();
                
                // Nếu kết nối được, peer vẫn online, không làm gì
                System.out.println("✓ Peer " + username + " still online");
            } catch (IOException e) {
                // Không kết nối được, peer đã offline
                System.out.println("❌ Peer " + username + " is offline, removing from list");
                removePeer(username);
            }
        }).start();
    }

    private void startHeartbeatChecker() {
        // Kiểm tra định kỳ các peer còn online không (mỗi 10 giây)
        new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(10000); // 10 giây
                    
                    // Kiểm tra các peer không có connection active
                    for (String username : new java.util.ArrayList<>(discoveredPeers.keySet())) {
                        // Nếu không có connection active, kiểm tra peer còn online không
                        if (!peerConnections.containsKey(username) || 
                            (peerConnections.get(username) != null && !peerConnections.get(username).isAlive())) {
                            checkPeerOffline(username);
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    System.err.println("❌ Error in heartbeat checker: " + e.getMessage());
                }
            }
        }).start();
        
        System.out.println("💓 Heartbeat checker started");
    }

    private void sendExistingPeersToNewPeer(String newPeerName, String newPeerIp, int newPeerDiscoveryPort) {
        // Gửi thông tin về TẤT CẢ các peer hiện có cho peer mới
        System.out.println("🔄 [" + currentUser + "] Sending existing peers to new peer: " + newPeerName);
        System.out.println("🔄 Current peers in list: " + discoveredPeers.keySet());
        
        for (String existingPeerName : discoveredPeers.keySet()) {
            if (!existingPeerName.equals(newPeerName)) {
                PeerInfo existingPeer = discoveredPeers.get(existingPeerName);
                
                new Thread(() -> {
                    try {
                        Socket socket = new Socket();
                        socket.connect(new InetSocketAddress(newPeerIp, newPeerDiscoveryPort), 2000);
                        
                        PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                        String notifyMsg = "PEER_NOTIFY:" + existingPeerName + ":" + existingPeer.ip + ":" + 
                                      existingPeer.port + ":" + existingPeer.filePort + ":" + 
                                      existingPeer.voicePort + ":" + existingPeer.videoPort + ":" + 
                                      existingPeer.videoAudioPort + ":" + existingPeer.discoveryPort;
                        writer.println(notifyMsg);
                        
                        socket.close();
                        System.out.println("✅ [" + currentUser + "] Sent " + existingPeerName + " info to " + newPeerName);
                    } catch (IOException e) {
                        System.out.println("❌ [" + currentUser + "] Failed to send " + existingPeerName + " to " + newPeerName + ": " + e.getMessage());
                    }
                }).start();
            }
        }
    }
    
    private void notifyExistingPeersAboutNewPeer(String newPeerName, String newPeerIp, int newPeerPort, 
                                                  int newPeerFilePort, int newPeerVoicePort, 
                                                  int newPeerVideoPort, int newPeerVideoAudioPort, int newPeerDiscoveryPort) {
        // Thông báo cho TẤT CẢ các peer hiện có về peer mới (KHÔNG bao gồm peer mới)
        System.out.println("🔄 [" + currentUser + "] Notifying existing peers about new peer: " + newPeerName);
        System.out.println("🔄 Current peers in list: " + discoveredPeers.keySet());
        
        for (String existingPeerName : discoveredPeers.keySet()) {
            if (!existingPeerName.equals(newPeerName)) {
                PeerInfo existingPeer = discoveredPeers.get(existingPeerName);
                
                new Thread(() -> {
                    try {
                        Socket socket = new Socket();
                        socket.connect(new InetSocketAddress(existingPeer.ip, existingPeer.discoveryPort), 2000);
                        
                        PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                        String notifyMsg = "PEER_NOTIFY:" + newPeerName + ":" + newPeerIp + ":" + 
                                      newPeerPort + ":" + newPeerFilePort + ":" + 
                                      newPeerVoicePort + ":" + newPeerVideoPort + ":" + 
                                      newPeerVideoAudioPort + ":" + newPeerDiscoveryPort;
                        writer.println(notifyMsg);
                        
                        socket.close();
                        System.out.println("✅ [" + currentUser + "] Notified " + existingPeerName + " about " + newPeerName);
                    } catch (IOException e) {
                        System.out.println("❌ [" + currentUser + "] Failed to notify " + existingPeerName + " about " + newPeerName + ": " + e.getMessage());
                    }
                }).start();
            }
        }
    }

    public void handlePeerMessage(String from, String message) {
        System.out.println("📩 From " + from + ": " + message);

        String[] parts = message.split(":", 3);
        String type = parts[0];
        
        // Xử lý các message không có dấu : (như TYPING, STOP_TYPING)
        if (parts.length == 1) {
            if (type.equals("TYPING")) {
                System.out.println("📝 Received TYPING from: " + from);
                System.out.println("📝 Current chat target: " + mainController.getChatManager().getCurrentChatTarget());
                System.out.println("📝 Is group chat: " + mainController.getChatManager().isGroupChat());
                
                if (mainController.getChatManager().getCurrentChatTarget() != null &&
                    mainController.getChatManager().getCurrentChatTarget().equals(from) &&
                    !mainController.getChatManager().isGroupChat()) {
                    System.out.println("✅ Showing typing indicator for: " + from);
                    mainController.getChatManager().showTypingIndicator(from);
                } else {
                    System.out.println("⚠️ Not showing typing indicator - conditions not met");
                }
                return;
            } else if (type.equals("STOP_TYPING")) {
                System.out.println("🛑 Received STOP_TYPING from: " + from);
                if (mainController.getChatManager().getCurrentChatTarget() != null &&
                    mainController.getChatManager().getCurrentChatTarget().equals(from) &&
                    !mainController.getChatManager().isGroupChat()) {
                    System.out.println("✅ Hiding typing indicator for: " + from);
                    mainController.getChatManager().hideTypingIndicator(from);
                }
                return;
            }
        }
        
        if (parts.length < 2) return;

        if (type.equals("MESSAGE") && parts.length >= 3) {
            String content = parts[2];
            mainController.getChatManager().displayMessage(from, content, false);
            saveChatHistory(from, content, false);

        } else if (type.equals("FILE") && parts.length >= 3) {
            handleIncomingFile(from, parts[2]);

        } else if (type.equals("GROUP_MESSAGE") && parts.length >= 3) {
            String groupName = parts[1];
            String content = parts[2];
            String[] messageParts = content.split(":", 2);
            if (messageParts.length >= 2) {
                String sender = messageParts[0].trim();
                String actualMessage = messageParts[1].trim();
                boolean isSentByMe = sender.equals(currentUser);

                if (mainController.getChatManager().getCurrentChatTarget() != null &&
                        mainController.getChatManager().getCurrentChatTarget().equals(groupName) &&
                        mainController.getChatManager().isGroupChat()) {
                    mainController.getChatManager().displayMessage(sender, actualMessage, isSentByMe);
                }
            }
            saveChatHistory(groupName + "_group", content, false);

        } else if (type.equals("GROUP_SYNC") && parts.length >= 3) {
            // XỬ LÝ ĐỒNG BỘ NHÓM
            String groupName = parts[1];
            String restData = parts[2];
            String[] groupData = restData.split(":", 2);

            if (groupData.length >= 2) {
                String creator = groupData[0];
                String membersList = groupData[1];

                // Tạo hoặc cập nhật nhóm
                ChatGroup group = chatGroups.get(groupName);
                java.util.Set<String> oldMembers = new java.util.HashSet<>();
                if (group != null) {
                    oldMembers.addAll(group.members);
                } else {
                    group = new ChatGroup(groupName, creator);
                    chatGroups.put(groupName, group);
                }

                // Cập nhật danh sách thành viên
                group.members.clear();
                String[] members = membersList.split(",");
                for (String member : members) {
                    group.addMember(member.trim());
                }

                // Phát hiện thành viên đã rời nhóm
                final ChatGroup finalGroup = group; // Make effectively final
                java.util.Set<String> leftMembers = new java.util.HashSet<>(oldMembers);
                leftMembers.removeAll(finalGroup.members);
                
                // Gửi thông báo vào chat nếu có thành viên rời
                for (String leftMember : leftMembers) {
                    if (!leftMember.equals(currentUser)) {
                        String notification = "⚠️ " + leftMember + " đã rời nhóm";
                        mainController.getChatManager().displayMessage("System", notification, false);
                        saveChatHistory(groupName + "_group", notification, false);
                        System.out.println("📢 " + leftMember + " left group " + groupName);
                    }
                }

                // Phát hiện thành viên mới
                java.util.Set<String> newMembers = new java.util.HashSet<>(finalGroup.members);
                newMembers.removeAll(oldMembers);
                for (String newMember : newMembers) {
                    if (!newMember.equals(currentUser)) {
                        String notification = "✅ " + newMember + " đã tham gia nhóm";
                        mainController.getChatManager().displayMessage("System", notification, false);
                        saveChatHistory(groupName + "_group", notification, false);
                        System.out.println("📢 " + newMember + " joined group " + groupName);
                    }
                }

                // Lưu nhóm vào file
                saveGroup(finalGroup);

                // Cập nhật giao diện
                mainController.getChatManager().refreshContactList();
                
                // Cập nhật title nếu đang chat với nhóm này
                if (mainController.getChatManager().getCurrentChatTarget() != null &&
                    mainController.getChatManager().getCurrentChatTarget().equals(groupName)) {
                    final int memberCount = finalGroup.members.size();
                    javafx.application.Platform.runLater(() -> {
                        mainController.getChatManager().updateGroupTitle(groupName, memberCount);
                    });
                }

                System.out.println("✓ Received group sync: " + groupName + " with " + group.members.size() + " members");
            }

        } else if (type.equals("GROUP_FILE") && parts.length >= 3) {
            String groupName = parts[1];
            String fileData = parts[2];
            String[] fileInfo = fileData.split("\\|");

            if (fileInfo.length >= 5) {
                String sender = fileInfo[0];
                String fileName = fileInfo[1];
                long fileSize = Long.parseLong(fileInfo[2]);
                String senderIp = fileInfo[3];
                String uniqueFileName = fileInfo[4];

                PeerInfo senderPeer = discoveredPeers.get(sender);
                if (senderPeer != null) {
                    downloadFileFromPeer(senderIp, senderPeer.filePort, uniqueFileName, fileName, () -> {
                        if (mainController.getChatManager().getCurrentChatTarget() != null &&
                                mainController.getChatManager().getCurrentChatTarget().equals(groupName) &&
                                mainController.getChatManager().isGroupChat()) {
                            mainController.getChatManager().displayFileMessage(sender, fileName, fileSize, uniqueFileName, false);
                        }
                    });
                }
                saveChatHistory(groupName + "_group", "[FILE:" + fileName + "]", false);
            }

        } else if (type.equals("CALL_ACCEPTED")) {
            mainController.getCallManager().handleCallAccepted(from);

        } else if (type.equals("CALL_REJECTED")) {
            mainController.getCallManager().handleCallRejected(from);

        } else if (type.equals("VIDEO_CALL_ACCEPTED")) {
            mainController.getCallManager().handleVideoCallAccepted(from);

        } else if (type.equals("VIDEO_CALL_REJECTED")) {
            mainController.getCallManager().handleVideoCallRejected(from);


        } else if (type.equals("GROUP_TYPING") && parts.length >= 3) {
            // Hiển thị typing indicator cho nhóm
            String groupName = parts[1];
            String typingUser = parts[2];
            if (mainController.getChatManager().getCurrentChatTarget() != null &&
                mainController.getChatManager().getCurrentChatTarget().equals(groupName) &&
                mainController.getChatManager().isGroupChat() &&
                !typingUser.equals(currentUser)) {
                mainController.getChatManager().showTypingIndicator(typingUser);
            }

        } else if (type.equals("GROUP_STOP_TYPING") && parts.length >= 3) {
            // Ẩn typing indicator cho nhóm
            String groupName = parts[1];
            String typingUser = parts[2];
            if (mainController.getChatManager().getCurrentChatTarget() != null &&
                mainController.getChatManager().getCurrentChatTarget().equals(groupName) &&
                mainController.getChatManager().isGroupChat()) {
                mainController.getChatManager().hideTypingIndicator(typingUser);
            }
        }
    }

    private void handleIncomingFile(String from, String fileInfo) {
        String[] fileData = fileInfo.split("\\|");
        if (fileData.length >= 4) {
            String fileName = fileData[0];
            long fileSize = Long.parseLong(fileData[1]);
            String senderIp = fileData[2];
            String uniqueFileName = fileData[3];

            PeerInfo senderPeer = discoveredPeers.get(from);
            if (senderPeer != null) {
                downloadFileFromPeer(senderIp, senderPeer.filePort, uniqueFileName, fileName, () -> {
                    if (mainController.getChatManager().getCurrentChatTarget() != null &&
                            mainController.getChatManager().getCurrentChatTarget().equals(from) &&
                            !mainController.getChatManager().isGroupChat()) {
                        mainController.getChatManager().displayFileMessage(from, fileName, fileSize, uniqueFileName, false);
                    }
                });
            }
            saveChatHistory(from, "[FILE:" + fileName + "]", false);
        }
    }

    private void downloadFileFromPeer(String senderIp, int senderFilePort, String uniqueFileName, String displayName, Runnable onComplete) {
        new Thread(() -> {
            System.out.println("📥 [FILE] Starting download:");
            System.out.println("  - Sender IP: " + senderIp);
            System.out.println("  - Sender Port: " + senderFilePort);
            System.out.println("  - Unique Name: " + uniqueFileName);
            System.out.println("  - Display Name: " + displayName);
            
            try {
                File existingFile = new File("shared_files/" + uniqueFileName);
                if (existingFile.exists()) {
                    System.out.println("✓ File already exists: " + uniqueFileName);
                    if (onComplete != null) {
                        onComplete.run();
                    }
                    return;
                }

                System.out.println("🔗 [FILE] Connecting to " + senderIp + ":" + senderFilePort + "...");
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(senderIp, senderFilePort), 10000); // Tăng timeout lên 10s
                System.out.println("✅ [FILE] Connected successfully");

                DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                DataInputStream dis = new DataInputStream(socket.getInputStream());

                System.out.println("📤 [FILE] Sending REQUEST_FILE for: " + uniqueFileName);
                dos.writeUTF("REQUEST_FILE");
                dos.writeUTF(uniqueFileName);
                dos.flush();

                System.out.println("⏳ [FILE] Waiting for response...");
                String response = dis.readUTF();
                System.out.println("📥 [FILE] Response: " + response);
                if (response.equals("OK")) {
                    long fileSize = dis.readLong();
                    String fileName = dis.readUTF();
                    // fileName được gửi từ server (có thể khác displayName)

                    String savePath = "shared_files/" + uniqueFileName;
                    FileOutputStream fos = new FileOutputStream(savePath);

                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalBytesRead = 0;

                    while (totalBytesRead < fileSize && (bytesRead = dis.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                        totalBytesRead += bytesRead;
                    }

                    fos.close();
                    socket.close();

                    System.out.println("✓ Downloaded file: " + displayName + " (server name: " + fileName + ", " + totalBytesRead + " bytes)");

                    if (onComplete != null) {
                        onComplete.run();
                    }

                } else {
                    String error = dis.readUTF();
                    System.err.println("✗ Cannot download file: " + error);
                    mainController.getChatManager().showAlert("Lỗi", "Không thể tải file: " + error);
                }
            } catch (java.net.ConnectException e) {
                System.err.println("❌ [FILE] Connection refused: " + senderIp + ":" + senderFilePort);
                System.err.println("  - Possible causes:");
                System.err.println("    1. Firewall blocking port " + senderFilePort);
                System.err.println("    2. Sender's file server not running");
                System.err.println("    3. Wrong IP address");
                mainController.getChatManager().showAlert("Lỗi", "Không thể kết nối đến máy gửi!\nKiểm tra firewall và IP: " + senderIp);
            } catch (java.net.SocketTimeoutException e) {
                System.err.println("❌ [FILE] Connection timeout: " + senderIp + ":" + senderFilePort);
                mainController.getChatManager().showAlert("Lỗi", "Timeout khi kết nối đến máy gửi!");
            } catch (IOException e) {
                System.err.println("❌ [FILE] Download error: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                e.printStackTrace();
                mainController.getChatManager().showAlert("Lỗi", "Lỗi tải file: " + e.getMessage());
            }
        }).start();
    }

    public void ensureConnection(String username) {
        if (!peerConnections.containsKey(username)) {
            connectToPeer(username);
        }
    }

    public PeerConnection connectToPeer(String username) {
        PeerConnection existing = peerConnections.get(username);
        if (existing != null && existing.isAlive()) {
            return existing;
        }

        PeerInfo peer = discoveredPeers.get(username);
        if (peer == null) return null;

        try {
            Socket socket = new Socket(peer.ip, peer.port);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"));
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                    socket.getOutputStream(), "UTF-8"), true);

            writer.println("HELLO:" + currentUser);

            String response = reader.readLine();
            if (response != null && response.startsWith("HELLO:")) {
                PeerConnection connection = new PeerConnection(socket, username, reader, writer);
                peerConnections.put(username, connection);

                new Thread(() -> {
                    try {
                        String message;
                        while ((message = reader.readLine()) != null) {
                            handlePeerMessage(username, message);
                        }
                    } catch (IOException e) {
                        // Connection closed
                    } finally {
                        peerConnections.remove(username);
                        
                        // Kiểm tra nếu không còn connection nào với peer này, xóa khỏi danh sách
                        // Chỉ xóa nếu không có connection active và không thể reconnect
                        if (!peerConnections.containsKey(username)) {
                            // Thử kiểm tra xem peer còn online không bằng cách ping
                            checkPeerOffline(username);
                        }
                    }
                }).start();

                System.out.println("✓ Connected to: " + username);
                return connection;
            }
        } catch (IOException e) {
            System.err.println("✗ Failed to connect to: " + username);
        }

        return null;
    }

    public void sendDirectMessage(String target, String message) {
        PeerConnection connection = peerConnections.get(target);
        if (connection == null || !connection.isAlive()) {
            connection = connectToPeer(target);
        }

        if (connection != null) {
            connection.send("MESSAGE:" + currentUser + ":" + message);
        }
    }

    public void sendGroupMessage(String groupName, String message) {
        ChatGroup group = chatGroups.get(groupName);
        if (group != null) {
            String fullMessage = currentUser + ": " + message;
            for (String member : group.members) {
                if (!member.equals(currentUser)) {
                    PeerConnection connection = peerConnections.get(member);
                    if (connection == null || !connection.isAlive()) {
                        connection = connectToPeer(member);
                    }
                    if (connection != null) {
                        connection.send("GROUP_MESSAGE:" + groupName + ":" + fullMessage);
                    }
                }
            }
        }
    }

    public void sendTyping(String target) {
        System.out.println("📤 Sending TYPING to: " + target);
        PeerConnection connection = peerConnections.get(target);
        if (connection == null || !connection.isAlive()) {
            connection = connectToPeer(target);
        }
        if (connection != null) {
            connection.send("TYPING");
            System.out.println("✅ TYPING signal sent to: " + target);
        } else {
            System.err.println("❌ Failed to send TYPING - no connection to: " + target);
        }
    }

    public void sendStopTyping(String target) {
        PeerConnection connection = peerConnections.get(target);
        if (connection != null && connection.isAlive()) {
            connection.send("STOP_TYPING");
        }
    }

    public void sendGroupTyping(String groupName) {
        ChatGroup group = chatGroups.get(groupName);
        if (group != null) {
            String typingMessage = "GROUP_TYPING:" + groupName + ":" + currentUser;
            for (String member : group.members) {
                if (!member.equals(currentUser)) {
                    PeerConnection connection = peerConnections.get(member);
                    if (connection == null || !connection.isAlive()) {
                        connection = connectToPeer(member);
                    }
                    if (connection != null) {
                        connection.send(typingMessage);
                    }
                }
            }
        }
    }

    public void sendGroupStopTyping(String groupName) {
        ChatGroup group = chatGroups.get(groupName);
        if (group != null) {
            String stopTypingMessage = "GROUP_STOP_TYPING:" + groupName + ":" + currentUser;
            for (String member : group.members) {
                if (!member.equals(currentUser)) {
                    PeerConnection connection = peerConnections.get(member);
                    if (connection != null && connection.isAlive()) {
                        connection.send(stopTypingMessage);
                    }
                }
            }
        }
    }

    public void sendFile(String target, File selectedFile, boolean isGroup) {
        if (selectedFile.length() > 50 * 1024 * 1024) {
            mainController.getChatManager().showAlert("Lỗi", "File quá lớn! Giới hạn 50MB.");
            return;
        }

        new Thread(() -> {
            try {
                String uniqueFileName = System.currentTimeMillis() + "_" + selectedFile.getName();
                Path destPath = Paths.get("shared_files/" + uniqueFileName);
                Files.copy(selectedFile.toPath(), destPath, StandardCopyOption.REPLACE_EXISTING);

                long fileSize = selectedFile.length();
                String fileName = selectedFile.getName();

                mainController.getChatManager().displayFileMessage(currentUser, fileName, fileSize, uniqueFileName, true);

                if (isGroup) {
                    sendGroupFile(fileName, fileSize, uniqueFileName, target);
                } else {
                    sendDirectFile(fileName, fileSize, uniqueFileName, target);
                }

                saveChatHistory(target + (isGroup ? "_group" : ""), "[FILE:" + fileName + "]", true);
                System.out.println("✓ File saved and sent: " + uniqueFileName);

            } catch (IOException e) {
                e.printStackTrace();
                mainController.getChatManager().showAlert("Lỗi", "Không thể gửi file!");
            }
        }).start();
    }

    private void sendDirectFile(String fileName, long fileSize, String uniqueFileName, String target) {
        try {
            // Sử dụng getRealLocalIp() thay vì getLocalHost() để tránh lấy 127.0.0.1
            String myIp = getRealLocalIp();
            System.out.println("📤 [FILE] Sending file metadata:");
            System.out.println("  - My IP: " + myIp);
            System.out.println("  - My File Port: " + myFilePort);
            System.out.println("  - File: " + fileName + " (" + fileSize + " bytes)");

            PeerConnection connection = peerConnections.get(target);
            if (connection == null || !connection.isAlive()) {
                connection = connectToPeer(target);
            }

            if (connection != null) {
                String message = "FILE::" + fileName + "|" + fileSize + "|" + myIp + "|" + uniqueFileName;
                connection.send(message);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendGroupFile(String fileName, long fileSize, String uniqueFileName, String groupName) {
        ChatGroup group = chatGroups.get(groupName);
        if (group != null) {
            try {
                // Sử dụng getRealLocalIp() thay vì getLocalHost() để tránh lấy 127.0.0.1
                String myIp = getRealLocalIp();
                System.out.println("📤 [FILE] Sending group file metadata:");
                System.out.println("  - My IP: " + myIp);
                System.out.println("  - My File Port: " + myFilePort);
                System.out.println("  - File: " + fileName + " (" + fileSize + " bytes)");

                for (String member : group.members) {
                    if (!member.equals(currentUser)) {
                        PeerConnection connection = peerConnections.get(member);
                        if (connection == null || !connection.isAlive()) {
                            connection = connectToPeer(member);
                        }
                        if (connection != null) {
                            String message = "GROUP_FILE:" + groupName + ":" +
                                    currentUser + "|" + fileName + "|" + fileSize + "|" + myIp + "|" + uniqueFileName;
                            connection.send(message);
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void saveGroup(ChatGroup group) {
        for (String member : group.members) {
            String filename = "groups/" + member + "_group_" + group.name + ".txt";
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(filename), "UTF-8"))) {
                writer.write(group.name + "\n");
                writer.write(group.creator + "\n");
                writer.write(String.join(",", group.members) + "\n");
                System.out.println("💾 Saved group for: " + member);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void syncGroupToMembers(ChatGroup group) {
        String membersList = String.join(",", group.members);
        String syncMessage = "GROUP_SYNC:" + group.name + ":" + group.creator + ":" + membersList;

        for (String member : group.members) {
            if (!member.equals(currentUser)) {
                PeerConnection connection = peerConnections.get(member);
                if (connection == null || !connection.isAlive()) {
                    connection = connectToPeer(member);
                }
                if (connection != null) {
                    connection.send(syncMessage);
                    System.out.println("📤 Synced group " + group.name + " to " + member);
                }
            }
        }
    }

    public void addMembersToGroup(String groupName, List<String> newMembers) {
        ChatGroup group = chatGroups.get(groupName);
        if (group == null) {
            System.err.println("❌ Group not found: " + groupName);
            return;
        }

        boolean hasNewMembers = false;
        for (String member : newMembers) {
            if (!group.isMember(member) && discoveredPeers.containsKey(member)) {
                group.addMember(member);
                hasNewMembers = true;
                System.out.println("➕ Added member " + member + " to group " + groupName);
            }
        }

        if (hasNewMembers) {
            // Gửi thông báo vào chat cho các thành viên mới
            for (String newMember : newMembers) {
                String notification = "✅ " + newMember + " đã tham gia nhóm";
                if (mainController.getChatManager().getCurrentChatTarget() != null &&
                    mainController.getChatManager().getCurrentChatTarget().equals(groupName) &&
                    mainController.getChatManager().isGroupChat()) {
                    mainController.getChatManager().displayMessage("System", notification, false);
                }
                saveChatHistory(groupName + "_group", notification, false);
            }
            
            // Lưu lại file
            saveGroup(group);
            
            // Sync với tất cả thành viên (bao gồm thành viên mới)
            syncGroupToMembers(group);
            
            // Cập nhật UI
            mainController.getChatManager().refreshContactList();
            
            // Cập nhật title nếu đang chat với nhóm này
            if (mainController.getChatManager().getCurrentChatTarget() != null &&
                mainController.getChatManager().getCurrentChatTarget().equals(groupName)) {
                javafx.application.Platform.runLater(() -> {
                    mainController.getChatManager().updateGroupTitle(groupName, group.members.size());
                });
            }
        }
    }

    public void removeMemberFromGroup(String groupName, String memberToRemove) {
        ChatGroup group = chatGroups.get(groupName);
        if (group == null) {
            System.err.println("❌ Group not found: " + groupName);
            return;
        }

        if (!group.isMember(memberToRemove)) {
            System.out.println("⚠️ Member " + memberToRemove + " is not in group " + groupName);
            return;
        }

        // Xóa thành viên khỏi nhóm
        group.removeMember(memberToRemove);
        System.out.println("➖ Removed member " + memberToRemove + " from group " + groupName);

        // Nếu là chính mình rời nhóm, xóa file group của mình
        if (memberToRemove.equals(currentUser)) {
            String filename = "groups/" + currentUser + "_group_" + groupName + ".txt";
            File groupFile = new File(filename);
            if (groupFile.exists()) {
                groupFile.delete();
                System.out.println("🗑️ Deleted group file: " + filename);
            }
            
            // Xóa nhóm khỏi danh sách của mình
            chatGroups.remove(groupName);
        } else {
            // Nếu là thành viên khác, cập nhật file cho tất cả thành viên còn lại
            saveGroup(group);
        }

        // Gửi thông báo vào chat cho các thành viên còn lại
        if (!memberToRemove.equals(currentUser)) {
            // Nếu là thành viên khác rời, gửi thông báo vào chat
            String notification = "⚠️ " + memberToRemove + " đã rời nhóm";
            if (mainController.getChatManager().getCurrentChatTarget() != null &&
                mainController.getChatManager().getCurrentChatTarget().equals(groupName) &&
                mainController.getChatManager().isGroupChat()) {
                mainController.getChatManager().displayMessage("System", notification, false);
            }
            saveChatHistory(groupName + "_group", notification, false);
        }

        // Sync với các thành viên còn lại
        if (!group.members.isEmpty()) {
            syncGroupToMembers(group);
        } else {
            // Nếu nhóm không còn thành viên nào, xóa nhóm
            System.out.println("🗑️ Group " + groupName + " has no members, removing...");
            chatGroups.remove(groupName);
            
            // Xóa tất cả file group
            File groupsDir = new File("groups/");
            File[] groupFiles = groupsDir.listFiles((dir, name) -> name.endsWith("_group_" + groupName + ".txt"));
            if (groupFiles != null) {
                for (File file : groupFiles) {
                    file.delete();
                }
            }
        }

        // Cập nhật UI
        mainController.getChatManager().refreshContactList();
        
        // Cập nhật title nếu đang chat với nhóm này
        if (mainController.getChatManager().getCurrentChatTarget() != null &&
            mainController.getChatManager().getCurrentChatTarget().equals(groupName) &&
            !memberToRemove.equals(currentUser)) {
            javafx.application.Platform.runLater(() -> {
                if (chatGroups.containsKey(groupName)) {
                    mainController.getChatManager().updateGroupTitle(groupName, chatGroups.get(groupName).members.size());
                }
            });
        }
    }

    private void saveChatHistory(String target, String message, boolean isSent) {
        String filename = "chat_history/" + currentUser + "_" + target + ".txt";
        try (FileWriter writer = new FileWriter(filename, true)) {
            String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String sender = isSent ? currentUser : (target.contains("_group") ? message.split(":")[0] : target);
            String actualMessage = isSent ? message : (target.contains("_group") && message.contains(":") ?
                    message.substring(message.indexOf(":") + 1).trim() : message);
            writer.write(String.format("[%s] %s: %s\n", timestamp, sender, actualMessage));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void shutdown() {
        try {
            for (PeerConnection connection : peerConnections.values()) {
                connection.close();
            }

            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            if (discoveryServer != null && !discoveryServer.isClosed()) {
                discoveryServer.close();
            }
            if (fileServer != null && !fileServer.isClosed()) {
                fileServer.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Getters
    public ConcurrentHashMap<String, PeerInfo> getDiscoveredPeers() { return discoveredPeers; }
    public ConcurrentHashMap<String, ChatGroup> getChatGroups() { return chatGroups; }
}