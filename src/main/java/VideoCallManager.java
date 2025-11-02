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
    private static final AudioFormat AUDIO_FORMAT = new AudioFormat(16000, 16, 1, true, true);
    private static final int BUFFER_SIZE = 1024;
    private static final Dimension VIDEO_SIZE = new Dimension(640, 480);
    private static final int FPS = 15;

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
        new Thread(() -> {
            try {
                videoServer = new ServerSocket(myVideoPort);
                System.out.println("📹 Video server started on port: " + myVideoPort);

                while (!Thread.currentThread().isInterrupted()) {
                    Socket clientSocket = videoServer.accept();
                    System.out.println("📹 New video connection from: " + clientSocket.getInetAddress());
                    handleIncomingVideoCall(clientSocket);
                }
            } catch (IOException e) {
                System.out.println("📹 Video server stopped: " + e.getMessage());
            }
        }).start();

        new Thread(() -> {
            try {
                audioServer = new ServerSocket(myAudioPort);
                System.out.println("🎤 Video audio server started on port: " + myAudioPort);

                while (!Thread.currentThread().isInterrupted()) {
                    Socket clientSocket = audioServer.accept();
                    System.out.println("🎤 New audio connection from: " + clientSocket.getInetAddress());
                    this.audioSocket = clientSocket;
                    if (isVideoCallActive.get()) {
                        startAudioStreaming();
                    }
                }
            } catch (IOException e) {
                System.out.println("🎤 Audio server stopped: " + e.getMessage());
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
    }

    public void acceptVideoCall() {
        if (videoSocket != null && !videoSocket.isClosed()) {
            try {
                PrintWriter writer = new PrintWriter(videoSocket.getOutputStream(), true);
                writer.println("VIDEO_CALL_ACCEPTED:" + currentUser);

                System.out.println("✅ Sent video call acceptance");

                // Khởi tạo webcam và bắt đầu streaming
                if (initializeWebcam()) {
                    startVideoStreaming();
                    startAudioStreaming();

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
            videoSocket.setSoTimeout(5000); // 5 second timeout

            PrintWriter writer = new PrintWriter(videoSocket.getOutputStream(), true);
            writer.println("VIDEO_CALL:" + currentUser);
            System.out.println("✅ Video connection established");

            // Kết nối audio socket
            System.out.println("🔗 Connecting to audio port...");
            audioSocket = new Socket(peerIp, peerAudioPort);
            audioSocket.setSoTimeout(5000);
            System.out.println("✅ Audio connection established");

            // Khởi tạo webcam
            if (initializeWebcam()) {
                startVideoStreaming();
                startAudioStreaming();
                System.out.println("✅ Video call started successfully with " + peerIp);
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

    private boolean initializeWebcam() {
        try {
            closeWebcam();

            webcam = Webcam.getDefault();
            if (webcam != null) {
                // Kiểm tra các resolutions có sẵn
                Dimension[] resolutions = webcam.getViewSizes();
                System.out.println("📹 Available webcam resolutions: " + resolutions.length);
                for (Dimension res : resolutions) {
                    System.out.println("  - " + res.width + "x" + res.height);
                }

                // Thử đặt resolution
                try {
                    webcam.setViewSize(VIDEO_SIZE);
                    System.out.println("📹 Set resolution to: " + VIDEO_SIZE);
                } catch (Exception e) {
                    System.err.println("❌ Cannot set resolution, using default");
                    // Sử dụng resolution mặc định nếu không được
                }

                webcam.open();
                System.out.println("✅ Webcam opened: " + webcam.getName());
                System.out.println("📹 Current resolution: " + webcam.getViewSize());
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
            long startTime = System.currentTimeMillis();

            try {
                OutputStream out = videoSocket.getOutputStream();
                DataOutputStream dos = new DataOutputStream(out);

                while (isVideoCallActive.get() && webcam != null && webcam.isOpen()) {
                    try {
                        BufferedImage image = webcam.getImage();
                        if (image != null) {
                            frameCount++;

                            // Hiển thị local video
                            if (localVideoView != null) {
                                Platform.runLater(() -> {
                                    try {
                                        Image fxImage = SwingFXUtils.toFXImage(image, null);
                                        localVideoView.setImage(fxImage);
                                    } catch (Exception e) {
                                        System.err.println("❌ Error updating local video: " + e.getMessage());
                                    }
                                });
                            }

                            // Convert image to bytes
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            ImageIO.write(image, "jpg", baos);
                            byte[] imageBytes = baos.toByteArray();

                            // Gửi kích thước trước
                            dos.writeInt(imageBytes.length);
                            // Gửi dữ liệu ảnh
                            dos.write(imageBytes);
                            dos.flush();

                            // Log mỗi 10 frames
                            if (frameCount % 10 == 0) {
                                long elapsed = System.currentTimeMillis() - startTime;
                                double fps = (frameCount * 1000.0) / elapsed;
                                System.out.println("📤 Sent frame " + frameCount + " (" + imageBytes.length + " bytes, FPS: " + String.format("%.1f", fps) + ")");
                            }

                            // Giới hạn FPS
                            Thread.sleep(1000 / FPS);
                        }
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        System.err.println("❌ Error in video sending: " + e.getMessage());
                        break;
                    }
                }
            } catch (IOException e) {
                System.err.println("❌ Video sending thread error: " + e.getMessage());
            } finally {
                System.out.println("📤 Video sending thread ended. Total frames: " + frameCount);
            }
        });
        videoSendThread.start();

        // Thread nhận video
        videoReceiveThread = new Thread(() -> {
            System.out.println("📥 Starting video receiving thread");
            int frameCount = 0;
            long startTime = System.currentTimeMillis();

            try {
                DataInputStream dis = new DataInputStream(videoSocket.getInputStream());

                while (isVideoCallActive.get()) {
                    try {
                        // Đọc kích thước frame
                        int imageSize = dis.readInt();
                        if (imageSize <= 0) {
                            System.err.println("❌ Invalid image size: " + imageSize);
                            break;
                        }

                        // Đọc dữ liệu frame
                        byte[] imageBytes = new byte[imageSize];
                        int totalRead = 0;
                        while (totalRead < imageSize) {
                            int bytesRead = dis.read(imageBytes, totalRead, imageSize - totalRead);
                            if (bytesRead == -1) {
                                throw new EOFException("End of stream");
                            }
                            totalRead += bytesRead;
                        }

                        // Convert bytes thành image
                        ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes);
                        BufferedImage image = ImageIO.read(bais);

                        if (image != null && remoteVideoView != null) {
                            frameCount++;
                            Platform.runLater(() -> {
                                try {
                                    Image fxImage = SwingFXUtils.toFXImage(image, null);
                                    remoteVideoView.setImage(fxImage);
                                } catch (Exception e) {
                                    System.err.println("❌ Error updating remote video: " + e.getMessage());
                                }
                            });

                            // Log mỗi 10 frames
                            if (frameCount % 10 == 0) {
                                long elapsed = System.currentTimeMillis() - startTime;
                                double fps = (frameCount * 1000.0) / elapsed;
                                System.out.println("📥 Received frame " + frameCount + " (" + imageSize + " bytes, FPS: " + String.format("%.1f", fps) + ")");
                            }
                        }
                    } catch (EOFException | SocketException e) {
                        System.err.println("❌ Video stream ended: " + e.getMessage());
                        break;
                    } catch (Exception e) {
                        System.err.println("❌ Error in video receiving: " + e.getMessage());
                        break;
                    }
                }
            } catch (IOException e) {
                System.err.println("❌ Video receiving thread error: " + e.getMessage());
            } finally {
                System.out.println("📥 Video receiving thread ended. Total frames: " + frameCount);
            }
        });
        videoReceiveThread.start();
    }

    private void startAudioStreaming() {
        try {
            DataLine.Info micInfo = new DataLine.Info(TargetDataLine.class, AUDIO_FORMAT);
            microphone = (TargetDataLine) AudioSystem.getLine(micInfo);
            microphone.open(AUDIO_FORMAT);
            microphone.start();

            DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, AUDIO_FORMAT);
            speaker = (SourceDataLine) AudioSystem.getLine(speakerInfo);
            speaker.open(AUDIO_FORMAT);
            speaker.start();

            System.out.println("🎤 Audio devices initialized");

            // Audio sending
            audioSendThread = new Thread(() -> {
                System.out.println("🎤 Starting audio sending");
                try {
                    OutputStream out = audioSocket.getOutputStream();
                    byte[] buffer = new byte[BUFFER_SIZE];

                    while (isVideoCallActive.get()) {
                        int bytesRead = microphone.read(buffer, 0, buffer.length);
                        if (bytesRead > 0) {
                            out.write(buffer, 0, bytesRead);
                        }
                    }
                } catch (IOException e) {
                    System.err.println("❌ Audio sending error: " + e.getMessage());
                }
                System.out.println("🎤 Audio sending ended");
            });
            audioSendThread.start();

            // Audio receiving
            audioReceiveThread = new Thread(() -> {
                System.out.println("🎧 Starting audio receiving");
                try {
                    InputStream in = audioSocket.getInputStream();
                    byte[] buffer = new byte[BUFFER_SIZE];

                    while (isVideoCallActive.get()) {
                        int bytesRead = in.read(buffer, 0, buffer.length);
                        if (bytesRead > 0) {
                            speaker.write(buffer, 0, bytesRead);
                        }
                    }
                } catch (IOException e) {
                    System.err.println("❌ Audio receiving error: " + e.getMessage());
                }
                System.out.println("🎧 Audio receiving ended");
            });
            audioReceiveThread.start();

        } catch (LineUnavailableException e) {
            System.err.println("❌ Audio devices unavailable: " + e.getMessage());
        }
    }

    public void endVideoCall() {
        if (!isVideoCallActive.get()) {
            return;
        }

        System.out.println("🛑 Ending video call...");
        isVideoCallActive.set(false);

        try {
            if (videoSendThread != null) videoSendThread.interrupt();
            if (videoReceiveThread != null) videoReceiveThread.interrupt();
            if (audioSendThread != null) audioSendThread.interrupt();
            if (audioReceiveThread != null) audioReceiveThread.interrupt();

            closeWebcam();

            if (microphone != null) {
                microphone.stop();
                microphone.close();
            }
            if (speaker != null) {
                speaker.stop();
                speaker.close();
            }
            if (videoSocket != null) videoSocket.close();
            if (audioSocket != null) audioSocket.close();

            if (callListener != null) {
                callListener.onCallEnded();
            }

            System.out.println("✅ Video call ended completely");
        } catch (IOException e) {
            System.err.println("❌ Error ending video call: " + e.getMessage());
        }
    }

    public boolean isVideoCallActive() {
        return isVideoCallActive.get();
    }

    public void shutdown() {
        endVideoCall();
        try {
            if (videoServer != null) videoServer.close();
            if (audioServer != null) audioServer.close();
        } catch (IOException e) {
            System.err.println("❌ Error shutting down: " + e.getMessage());
        }
    }
}