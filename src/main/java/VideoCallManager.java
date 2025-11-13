import com.github.sarxos.webcam.Webcam;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import javax.imageio.ImageIO;
import javax.sound.sampled.*;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class VideoCallManager {
    private static final int VIDEO_PORT_BASE = 9500;
    private static final int VIDEO_AUDIO_PORT_BASE = 9600;
    // Audio format - thử little-endian trước, nếu không được sẽ fallback
    private static AudioFormat getAudioFormat() {
        // Thử little-endian trước (Windows thường hỗ trợ)
        AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
        if (isFormatSupported(format)) {
            return format;
        }
        
        // Fallback sang big-endian
        format = new AudioFormat(16000, 16, 1, true, true);
        if (isFormatSupported(format)) {
            return format;
        }
        
        // Fallback cuối cùng: unsigned little-endian
        return new AudioFormat(16000, 16, 1, false, false);
    }
    
    private static boolean isFormatSupported(AudioFormat format) {
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            return AudioSystem.isLineSupported(info);
        } catch (Exception e) {
            return false;
        }
    }
    private static final int BUFFER_SIZE = 1024;
    private static final Dimension VIDEO_SIZE = new Dimension(640, 480);
    private static final int FPS = 15;
    private static final int FRAME_DELAY = 1000 / FPS;

    private ServerSocket videoServer;
    private ServerSocket audioServer;
    private Socket videoSocket;
    private Socket audioSocket;
    private Webcam webcam;
    private TargetDataLine microphone;
    private SourceDataLine speaker;
    private AtomicBoolean isVideoCallActive = new AtomicBoolean(false);
    private Thread videoSendThread;
    private Thread videoReceiveThread;
    private Thread audioSendThread;
    private Thread audioReceiveThread;
    private int myVideoPort;
    private int myAudioPort;
    private String currentUser;
    private ImageView localVideoView;
    private ImageView remoteVideoView;
    private CallListener callListener;

    public interface CallListener {
        void onIncomingVideoCall(String caller);
        void onCallAccepted();
        void onCallRejected();
        void onCallEnded();
    }

    public VideoCallManager(String currentUser) {
        this.currentUser = currentUser;
        this.myVideoPort = VIDEO_PORT_BASE + Math.abs(currentUser.hashCode() % 1000);
        this.myAudioPort = VIDEO_AUDIO_PORT_BASE + Math.abs(currentUser.hashCode() % 1000);
    }

    public void setCallListener(CallListener listener) {
        this.callListener = listener;
    }

    public void startVideoServer() {
        // Server video
        new Thread(() -> {
            try {
                videoServer = new ServerSocket(myVideoPort);
                System.out.println("📹 Video server started on port: " + myVideoPort);

                while (!Thread.currentThread().isInterrupted()) {
                    Socket clientSocket = videoServer.accept();
                    System.out.println("📹 New video connection from: " + clientSocket.getInetAddress());
                    handleIncomingVideoCall(clientSocket);
                }
            } catch (SocketException e) {
                System.out.println("📹 Video server stopped");
            } catch (IOException e) {
                System.err.println("❌ Video server error: " + e.getMessage());
            }
        }).start();

        // Server audio
        new Thread(() -> {
            try {
                audioServer = new ServerSocket(myAudioPort);
                System.out.println("🎤 Video audio server started on port: " + myAudioPort);

                while (!Thread.currentThread().isInterrupted()) {
                    Socket clientSocket = audioServer.accept();
                    System.out.println("🎤 New audio connection from: " + clientSocket.getInetAddress());
                    
                    // CHỈ set audio socket nếu chưa có hoặc đã đóng
                    if (this.audioSocket == null || this.audioSocket.isClosed()) {
                        this.audioSocket = clientSocket;
                        System.out.println("✅ Audio socket set");
                        
                        if (isVideoCallActive.get()) {
                            System.out.println("🎤 Video call is active, starting audio streaming...");
                            startAudioStreaming();
                        }
                    } else {
                        System.out.println("⚠️ Audio socket already exists, closing new connection");
                        clientSocket.close();
                    }
                }
            } catch (SocketException e) {
                System.out.println("🎤 Audio server stopped");
            } catch (IOException e) {
                System.err.println("❌ Audio server error: " + e.getMessage());
            }
        }).start();
    }

    public int getVideoPort() {
        return myVideoPort;
    }

    public int getAudioPort() {
        return myAudioPort;
    }

    private void handleIncomingVideoCall(Socket socket) {
        new Thread(() -> {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String message = reader.readLine();

                if (message != null && message.startsWith("VIDEO_CALL:")) {
                    String caller = message.split(":")[1];
                    System.out.println("📹 Incoming video call from: " + caller);

                    this.videoSocket = socket;

                    if (callListener != null) {
                        callListener.onIncomingVideoCall(caller);
                    }
                }
            } catch (IOException e) {
                System.err.println("❌ Error handling incoming video call: " + e.getMessage());
            }
        }).start();
    }

    public void acceptVideoCall() {
        if (videoSocket != null && !videoSocket.isClosed()) {
            try {
                // Gửi response trước
                PrintWriter writer = new PrintWriter(videoSocket.getOutputStream(), true);
                writer.println("VIDEO_CALL_ACCEPTED:" + currentUser);
                System.out.println("✅ Sent video call acceptance");

                // Khởi động webcam và streaming
                if (initializeWebcam()) {
                    isVideoCallActive.set(true);

                    // Bắt đầu video streaming
                    startVideoStreaming();
                    
                    // QUAN TRỌNG: Bắt đầu audio streaming nếu audio socket đã sẵn sàng
                    if (audioSocket != null && !audioSocket.isClosed()) {
                        System.out.println("✅ Audio socket ready, starting audio streaming");
                        startAudioStreaming();
                    } else {
                        System.out.println("⚠️ Audio socket not ready yet, waiting for connection...");
                    }

                    if (callListener != null) {
                        callListener.onCallAccepted();
                    }
                    System.out.println("✅ Video call accepted and streaming started");
                } else {
                    System.err.println("❌ Failed to initialize webcam");
                    rejectVideoCall();
                }
            } catch (IOException e) {
                System.err.println("❌ Error accepting video call: " + e.getMessage());
            }
        }
    }

    public void rejectVideoCall() {
        if (videoSocket != null && !videoSocket.isClosed()) {
            try {
                PrintWriter writer = new PrintWriter(videoSocket.getOutputStream(), true);
                writer.println("VIDEO_CALL_REJECTED:" + currentUser);
                videoSocket.close();

                if (callListener != null) {
                    callListener.onCallRejected();
                }
                System.out.println("❌ Video call rejected");
            } catch (IOException e) {
                System.err.println("❌ Error rejecting video call: " + e.getMessage());
            }
        }
        closeWebcam();
    }

    public boolean startVideoCall(String peerIp, int peerVideoPort, int peerAudioPort,
                                  ImageView localView, ImageView remoteView) {
        if (isVideoCallActive.get()) {
            System.err.println("❌ Video call already in progress");
            return false;
        }

        this.localVideoView = localView;
        this.remoteVideoView = remoteView;

        System.out.println("🎯 Starting video call with:");
        System.out.println("  - Peer IP: " + peerIp);
        System.out.println("  - Video Port: " + peerVideoPort);
        System.out.println("  - Audio Port: " + peerAudioPort);
        System.out.println("  - Local View: " + (localView != null ? "✓" : "✗"));
        System.out.println("  - Remote View: " + (remoteView != null ? "✓" : "✗"));

        try {
            // Kết nối video socket
            System.out.println("🔗 Connecting to video port...");
            videoSocket = new Socket(peerIp, peerVideoPort);
            videoSocket.setSoTimeout(30000); // 30s timeout

            PrintWriter writer = new PrintWriter(videoSocket.getOutputStream(), true);
            writer.println("VIDEO_CALL:" + currentUser);
            System.out.println("✅ Video connection established, waiting for acceptance...");

            // ĐỢI RESPONSE TỪ NGƯỜI NGHE
            BufferedReader reader = new BufferedReader(new InputStreamReader(videoSocket.getInputStream()));
            String response = reader.readLine();

            if (response == null || !response.startsWith("VIDEO_CALL_ACCEPTED:")) {
                System.err.println("❌ Video call rejected or timeout");
                videoSocket.close();
                return false;
            }

            System.out.println("✅ Video call accepted: " + response);

            // Kết nối audio socket SAU KHI ĐƯỢC ACCEPT
            System.out.println("🔗 Connecting to audio port...");
            audioSocket = new Socket(peerIp, peerAudioPort);
            audioSocket.setSoTimeout(5000);
            System.out.println("✅ Audio connection established");

            // Khởi động webcam và streaming
            if (initializeWebcam()) {
                isVideoCallActive.set(true);
                startVideoStreaming();
                startAudioStreaming();
                System.out.println("✅ Video call started successfully");
                return true;
            } else {
                System.err.println("❌ Failed to initialize webcam");
                return false;
            }
        } catch (SocketTimeoutException e) {
            System.err.println("❌ Connection timeout: " + e.getMessage());
            return false;
        } catch (IOException e) {
            System.err.println("❌ Failed to start video call: " + e.getMessage());
            return false;
        }
    }

    public void connectAudioSocket(String peerIp, int peerAudioPort) {
        new Thread(() -> {
            try {
                System.out.println("🔗 Receiver connecting to audio port: " + peerAudioPort);
                audioSocket = new Socket(peerIp, peerAudioPort);
                audioSocket.setSoTimeout(5000);

                if (isVideoCallActive.get()) {
                    startAudioStreaming();
                    System.out.println("✅ Receiver audio streaming started");
                }
            } catch (IOException e) {
                System.err.println("❌ Failed to connect audio socket: " + e.getMessage());
            }
        }).start();
    }

    public void setVideoViews(ImageView localView, ImageView remoteView) {
        this.localVideoView = localView;
        this.remoteVideoView = remoteView;
        System.out.println("✅ Video views set - Local: " + (localView != null ? "✓" : "✗") +
                ", Remote: " + (remoteView != null ? "✓" : "✗"));
    }

    private boolean initializeWebcam() {
        try {
            closeWebcam();

            webcam = Webcam.getDefault();
            if (webcam != null) {
                Dimension[] resolutions = webcam.getViewSizes();
                System.out.println("📹 Available webcam resolutions: " + resolutions.length);

                try {
                    webcam.setViewSize(VIDEO_SIZE);
                    System.out.println("📹 Set resolution to: " + VIDEO_SIZE.width + "x" + VIDEO_SIZE.height);
                } catch (Exception e) {
                    System.out.println("⚠️ Could not set resolution: " + e.getMessage());
                }

                webcam.open();
                System.out.println("✅ Webcam opened: " + webcam.getName());
                return true;
            } else {
                System.err.println("❌ No webcam found");
                return false;
            }
        } catch (Exception e) {
            System.err.println("❌ Error initializing webcam: " + e.getMessage());
            return false;
        }
    }

    private void closeWebcam() {
        if (webcam != null) {
            try {
                if (webcam.isOpen()) {
                    webcam.close();
                    System.out.println("📹 Webcam closed");
                }
                webcam = null;
            } catch (Exception e) {
                System.err.println("❌ Error closing webcam: " + e.getMessage());
            }
        }
    }

    private void startVideoStreaming() {
        isVideoCallActive.set(true);

        // Thread gửi video
        videoSendThread = new Thread(() -> {
            System.out.println("📤 Starting video sending thread");
            int frameCount = 0;

            try {
                OutputStream out = videoSocket.getOutputStream();
                DataOutputStream dos = new DataOutputStream(out);

                while (isVideoCallActive.get() && webcam != null && webcam.isOpen()) {
                    try {
                        long frameStartTime = System.currentTimeMillis();

                        BufferedImage image = webcam.getImage();
                        if (image != null) {
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            ImageIO.write(image, "jpg", baos);
                            byte[] imageData = baos.toByteArray();

                            dos.writeInt(imageData.length);
                            dos.write(imageData);
                            dos.flush();

                            frameCount++;
                            if (frameCount % 30 == 0) {
                                System.out.println("📤 Sent " + frameCount + " frames");
                            }
                        }

                        long frameTime = System.currentTimeMillis() - frameStartTime;
                        long sleepTime = FRAME_DELAY - frameTime;
                        if (sleepTime > 0) {
                            Thread.sleep(sleepTime);
                        }
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        System.err.println("❌ Error sending frame: " + e.getMessage());
                        break;
                    }
                }
            } catch (IOException e) {
                System.err.println("❌ Video sending thread error: " + e.getMessage());
            } finally {
                System.out.println("📤 Video sending thread ended. Total frames: " + frameCount);
            }
        });
        videoSendThread.setDaemon(true);
        videoSendThread.start();

        // Thread nhận video
        videoReceiveThread = new Thread(() -> {
            System.out.println("📥 Starting video receiving thread");
            int frameCount = 0;

            try {
                DataInputStream dis = new DataInputStream(videoSocket.getInputStream());

                while (isVideoCallActive.get()) {
                    try {
                        int imageSize = dis.readInt();
                        
                        // Kiểm tra signal END (size = -1)
                        if (imageSize == -1) {
                            System.out.println("📥 Received VIDEO_CALL_ENDED signal from remote");
                            endVideoCall();
                            break;
                        }
                        
                        if (imageSize <= 0 || imageSize > 5000000) {
                            System.err.println("❌ Invalid image size: " + imageSize);
                            break;
                        }

                        byte[] imageData = new byte[imageSize];
                        dis.readFully(imageData);

                        ByteArrayInputStream bais = new ByteArrayInputStream(imageData);
                        BufferedImage image = ImageIO.read(bais);

                        if (image != null && remoteVideoView != null) {
                            Image fxImage = SwingFXUtils.toFXImage(image, null);
                            Platform.runLater(() -> {
                                if (remoteVideoView != null) {
                                    remoteVideoView.setImage(fxImage);
                                }
                            });

                            frameCount++;
                            if (frameCount % 30 == 0) {
                                System.out.println("📥 Received " + frameCount + " frames");
                            }
                        }
                    } catch (EOFException | SocketException e) {
                        System.out.println("📥 Remote closed video connection");
                        endVideoCall();
                        break;
                    } catch (Exception e) {
                        System.err.println("❌ Error receiving frame: " + e.getMessage());
                        break;
                    }
                }
            } catch (IOException e) {
                System.err.println("❌ Video receiving thread error: " + e.getMessage());
            } finally {
                System.out.println("📥 Video receiving thread ended. Total frames: " + frameCount);
            }
        });
        videoReceiveThread.setDaemon(true);
        videoReceiveThread.start();

        // Thread hiển thị local video
        new Thread(() -> {
            System.out.println("📹 Starting local video display thread");

            try {
                while (isVideoCallActive.get() && webcam != null && webcam.isOpen()) {
                    try {
                        BufferedImage image = webcam.getImage();
                        if (image != null && localVideoView != null) {
                            Image fxImage = SwingFXUtils.toFXImage(image, null);
                            Platform.runLater(() -> {
                                if (localVideoView != null) {
                                    localVideoView.setImage(fxImage);
                                }
                            });
                        }
                        Thread.sleep(FRAME_DELAY);
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        System.err.println("❌ Error displaying local video: " + e.getMessage());
                    }
                }
            } finally {
                System.out.println("📹 Local video display thread ended");
            }
        }).start();
    }

    private void startAudioStreaming() {
        System.out.println("🎤 Starting audio streaming...");
        System.out.println("  - Audio socket: " + (audioSocket != null ? "✓" : "✗"));
        System.out.println("  - Audio socket connected: " + (audioSocket != null && audioSocket.isConnected() ? "✓" : "✗"));
        System.out.println("  - Audio socket closed: " + (audioSocket != null && audioSocket.isClosed() ? "✓" : "✗"));
        System.out.println("  - Microphone: " + (microphone != null ? "✓" : "✗"));
        System.out.println("  - Speaker: " + (speaker != null ? "✓" : "✗"));
        
        if (audioSocket == null || audioSocket.isClosed()) {
            System.err.println("❌ Cannot start audio streaming: audio socket is null or closed");
            return;
        }
        
        // KIỂM TRA: Nếu audio devices đã được mở rồi, không mở lại
        if (microphone != null && speaker != null) {
            System.out.println("⚠️ Audio devices already initialized, skipping initialization");
            // Chỉ start threads nếu chưa start
            if (audioSendThread == null || !audioSendThread.isAlive()) {
                startAudioThreads();
            } else {
                System.out.println("⚠️ Audio threads already running");
            }
            return;
        }
        
        try {
            // Tự động tìm format được hỗ trợ
            AudioFormat audioFormat = getAudioFormat();
            System.out.println("🎤 Using audio format: " + audioFormat);
            
            DataLine.Info micInfo = new DataLine.Info(TargetDataLine.class, audioFormat);
            microphone = (TargetDataLine) AudioSystem.getLine(micInfo);
            microphone.open(audioFormat);
            microphone.start();
            System.out.println("✅ Microphone opened");

            DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, audioFormat);
            speaker = (SourceDataLine) AudioSystem.getLine(speakerInfo);
            speaker.open(audioFormat);
            
            // Giảm volume của speaker để tránh echo (vọng)
            if (speaker.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl volumeControl = (FloatControl) speaker.getControl(FloatControl.Type.MASTER_GAIN);
                float currentVolume = volumeControl.getValue();
                float newVolume = Math.max(volumeControl.getMinimum(), currentVolume - 20.0f);
                volumeControl.setValue(newVolume);
                System.out.println("🔊 Video speaker volume reduced to: " + newVolume + " dB (from " + currentVolume + " dB)");
                System.out.println("💡 TIP: Use headphones to avoid echo!");
            } else {
                System.out.println("⚠️ Cannot control speaker volume - echo may occur");
                System.out.println("💡 STRONGLY RECOMMENDED: Use headphones!");
            }
            
            // Đảm bảo microphone được set null khi đóng để tránh giữ lại
            System.out.println("✅ Audio devices initialized with format: " + audioFormat);
            
            speaker.start();
            System.out.println("✅ Speaker opened");

            System.out.println("✅ Audio devices initialized");

            // Start audio threads
            startAudioThreads();

        } catch (LineUnavailableException e) {
            System.err.println("❌ Audio devices unavailable: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("❌ Error in audio streaming: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Start audio send/receive threads (tách riêng để tránh duplicate)
     */
    private void startAudioThreads() {
        if (audioSocket == null || audioSocket.isClosed()) {
            System.err.println("❌ Cannot start audio threads: socket not ready");
            return;
        }
        
        if (microphone == null || speaker == null) {
            System.err.println("❌ Cannot start audio threads: devices not initialized");
            return;
        }
        
        System.out.println("🎤 Starting audio threads...");
        
        // Thread gửi audio
        audioSendThread = new Thread(() -> {
                System.out.println("🎤 Audio sending thread started");
                int bytesSent = 0;
                try {
                    OutputStream out = audioSocket.getOutputStream();
                    byte[] buffer = new byte[BUFFER_SIZE];

                    while (isVideoCallActive.get() && microphone.isOpen()) {
                        int bytesRead = microphone.read(buffer, 0, buffer.length);
                        if (bytesRead > 0) {
                            out.write(buffer, 0, bytesRead);
                            out.flush();
                            bytesSent += bytesRead;
                            
                            if (bytesSent % 10240 == 0) { // Log mỗi 10KB
                                System.out.println("🎤 Sent " + (bytesSent / 1024) + " KB audio data");
                            }
                        }
                    }
                } catch (IOException e) {
                    System.err.println("❌ Audio sending error: " + e.getMessage());
                    e.printStackTrace();
                }
                System.out.println("🎤 Audio sending ended. Total sent: " + (bytesSent / 1024) + " KB");
            });
            audioSendThread.setDaemon(true);
            audioSendThread.start();

            // Thread nhận audio
            audioReceiveThread = new Thread(() -> {
                System.out.println("🎧 Audio receiving thread started");
                int bytesReceived = 0;
                try {
                    InputStream in = audioSocket.getInputStream();
                    byte[] buffer = new byte[BUFFER_SIZE];

                    while (isVideoCallActive.get() && speaker.isOpen()) {
                        int bytesRead = in.read(buffer);
                        if (bytesRead > 0) {
                            speaker.write(buffer, 0, bytesRead);
                            bytesReceived += bytesRead;
                            
                            if (bytesReceived % 10240 == 0) { // Log mỗi 10KB
                                System.out.println("🎧 Received " + (bytesReceived / 1024) + " KB audio data");
                            }
                        }
                    }
                } catch (IOException e) {
                    System.err.println("❌ Audio receiving error: " + e.getMessage());
                    e.printStackTrace();
                }
                System.out.println("🎧 Audio receiving ended. Total received: " + (bytesReceived / 1024) + " KB");
            });
            audioReceiveThread.setDaemon(true);
            audioReceiveThread.start();
    }

    public void endVideoCall() {
        if (!isVideoCallActive.get()) {
            return;
        }

        System.out.println("🛑 Ending video call COMPLETELY...");
        isVideoCallActive.set(false);

        try {
            // Gửi frame đặc biệt với size = -1 để báo hiệu END
            if (videoSocket != null && !videoSocket.isClosed()) {
                try {
                    DataOutputStream dos = new DataOutputStream(videoSocket.getOutputStream());
                    dos.writeInt(-1); // Signal END với size = -1
                    dos.flush();
                    System.out.println("📤 Sent VIDEO_CALL_ENDED signal (size=-1)");
                } catch (IOException e) {
                    // Socket đã đóng, bỏ qua
                }
            }
            
            // Đợi message được gửi
            Thread.sleep(100);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // DỪNG TẤT CẢ THREAD TRƯỚC KHI ĐÓNG RESOURCES
        System.out.println("🛑 Stopping all threads...");
        if (videoSendThread != null && videoSendThread.isAlive()) {
            System.out.println("🛑 Interrupting video send thread...");
            videoSendThread.interrupt();
        }
        if (videoReceiveThread != null && videoReceiveThread.isAlive()) {
            System.out.println("🛑 Interrupting video receive thread...");
            videoReceiveThread.interrupt();
        }
        if (audioSendThread != null && audioSendThread.isAlive()) {
            System.out.println("🛑 Interrupting audio send thread...");
            audioSendThread.interrupt();
        }
        if (audioReceiveThread != null && audioReceiveThread.isAlive()) {
            System.out.println("🛑 Interrupting audio receive thread...");
            audioReceiveThread.interrupt();
        }
        
        // ĐỢI CÁC THREAD DỪNG
        try {
            System.out.println("⏳ Waiting for threads to stop...");
            Thread.sleep(500); // Tăng lên 500ms
            
            // Kiểm tra xem threads đã dừng chưa
            boolean allStopped = true;
            if (audioSendThread != null && audioSendThread.isAlive()) {
                System.err.println("⚠️ Audio send thread still alive!");
                allStopped = false;
            }
            if (audioReceiveThread != null && audioReceiveThread.isAlive()) {
                System.err.println("⚠️ Audio receive thread still alive!");
                allStopped = false;
            }
            
            if (allStopped) {
                System.out.println("✅ All threads stopped");
            } else {
                System.err.println("⚠️ Some threads still running - forcing cleanup anyway");
            }
        } catch (InterruptedException e) {
            System.err.println("⚠️ Interrupted while waiting for threads");
        }
        
        // ĐÓNG AUDIO DEVICES - QUAN TRỌNG: ĐẢM BẢO CẢ 2 BÊN ĐỀU ĐÓNG
        System.out.println("🛑 Force closing ALL audio devices...");
        closeAudioDevicesCompletely();
        
        // ĐÓNG WEBCAM
        closeWebcam();
        
        // ĐÓNG SOCKETS
        System.out.println("🛑 Closing all sockets...");
        closeAllSockets();
        
        // CLEAN UP REFERENCES
        localVideoView = null;
        remoteVideoView = null;

        if (callListener != null) {
            callListener.onCallEnded();
        }

        System.out.println("✅ Video call ended COMPLETELY on both audio and video");
    }

    /**
     * PHƯƠNG THỨC MỚI: Đóng hoàn toàn audio devices
     */
    private void closeAudioDevicesCompletely() {
        System.out.println("🛑 [VIDEO] Starting COMPLETE audio device cleanup...");
        
        // ĐÓNG MICROPHONE
        if (microphone != null) {
            try {
                System.out.println("🎤 [VIDEO] Closing microphone (isOpen=" + microphone.isOpen() + ")...");
                
                // LUÔN LUÔN gọi stop() và close() bất kể trạng thái
                try {
                    if (microphone.isOpen() || microphone.isActive()) {
                        microphone.stop();
                        System.out.println("✅ [VIDEO] Microphone stopped");
                    }
                } catch (Exception e) {
                    System.err.println("⚠️ [VIDEO] Error stopping microphone: " + e.getMessage());
                }
                
                try {
                    microphone.flush(); // Flush buffer
                    System.out.println("✅ [VIDEO] Microphone flushed");
                } catch (Exception e) {
                    System.err.println("⚠️ [VIDEO] Error flushing microphone: " + e.getMessage());
                }
                
                try {
                    microphone.close();
                    System.out.println("✅ [VIDEO] Microphone closed");
                } catch (Exception e) {
                    System.err.println("⚠️ [VIDEO] Error closing microphone: " + e.getMessage());
                }
                
            } catch (Exception e) {
                System.err.println("⚠️ [VIDEO] Error in microphone cleanup: " + e.getMessage());
            } finally {
                microphone = null;
                System.out.println("✅ [VIDEO] Microphone reference released");
            }
        } else {
            System.out.println("🎤 [VIDEO] Microphone already null");
        }
        
        // ĐÓNG SPEAKER
        if (speaker != null) {
            try {
                System.out.println("🔊 [VIDEO] Closing speaker (isOpen=" + speaker.isOpen() + ")...");
                
                // LUÔN LUÔN gọi stop() và close() bất kể trạng thái
                try {
                    if (speaker.isOpen() || speaker.isActive()) {
                        speaker.stop();
                        System.out.println("✅ [VIDEO] Speaker stopped");
                    }
                } catch (Exception e) {
                    System.err.println("⚠️ [VIDEO] Error stopping speaker: " + e.getMessage());
                }
                
                try {
                    speaker.flush();
                    System.out.println("✅ [VIDEO] Speaker flushed");
                } catch (Exception e) {
                    System.err.println("⚠️ [VIDEO] Error flushing speaker: " + e.getMessage());
                }
                
                try {
                    speaker.close();
                    System.out.println("✅ [VIDEO] Speaker closed");
                } catch (Exception e) {
                    System.err.println("⚠️ [VIDEO] Error closing speaker: " + e.getMessage());
                }
                
            } catch (Exception e) {
                System.err.println("⚠️ [VIDEO] Error in speaker cleanup: " + e.getMessage());
            } finally {
                speaker = null;
                System.out.println("✅ [VIDEO] Speaker reference released");
            }
        } else {
            System.out.println("🔊 [VIDEO] Speaker already null");
        }
        
        // QUAN TRỌNG: Force garbage collection và đợi audio system release
        try {
            System.out.println("🔄 [VIDEO] Forcing garbage collection...");
            System.gc(); // Suggest garbage collection
            Thread.sleep(200);
            
            System.out.println("🔄 [VIDEO] Waiting for audio system to release resources...");
            Thread.sleep(500); // Đợi audio system release
            
            System.out.println("✅ [VIDEO] Audio system resources released");
        } catch (InterruptedException e) {
            System.err.println("⚠️ [VIDEO] Interrupted while waiting for audio release");
        }
    }

    /**
     * PHƯƠNG THỨC MỚI: Đóng tất cả sockets
     */
    private void closeAllSockets() {
        if (audioSocket != null && !audioSocket.isClosed()) {
            try {
                audioSocket.close();
                System.out.println("✅ Audio socket closed");
            } catch (IOException e) {
                System.err.println("⚠️ Error closing audio socket: " + e.getMessage());
            } finally {
                audioSocket = null;
            }
        }
        
        if (videoSocket != null && !videoSocket.isClosed()) {
            try {
                videoSocket.close();
                System.out.println("✅ Video socket closed");
            } catch (IOException e) {
                System.err.println("⚠️ Error closing video socket: " + e.getMessage());
            } finally {
                videoSocket = null;
            }
        }
    }

    public boolean isVideoCallActive() {
        return isVideoCallActive.get();
    }

    public void shutdown() {
        endVideoCall();
        try {
            if (videoServer != null && !videoServer.isClosed()) {
                videoServer.close();
            }
            if (audioServer != null && !audioServer.isClosed()) {
                audioServer.close();
            }
        } catch (IOException e) {
            System.err.println("❌ Error shutting down: " + e.getMessage());
        }
    }
}