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
                    this.audioSocket = clientSocket;
                    if (isVideoCallActive.get()) {
                        startAudioStreaming();
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

                    // Bắt đầu streaming ngay sau khi gửi response
                    startVideoStreaming();

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
                        System.out.println("📥 Remote closed connection");
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

            // Thread gửi audio
            audioSendThread = new Thread(() -> {
                System.out.println("🎤 Starting audio sending");
                try {
                    OutputStream out = audioSocket.getOutputStream();
                    byte[] buffer = new byte[BUFFER_SIZE];

                    while (isVideoCallActive.get() && microphone.isOpen()) {
                        int bytesRead = microphone.read(buffer, 0, buffer.length);
                        if (bytesRead > 0) {
                            out.write(buffer, 0, bytesRead);
                            out.flush();
                        }
                    }
                } catch (IOException e) {
                    System.err.println("❌ Audio sending error: " + e.getMessage());
                }
                System.out.println("🎤 Audio sending ended");
            });
            audioSendThread.setDaemon(true);
            audioSendThread.start();

            // Thread nhận audio
            audioReceiveThread = new Thread(() -> {
                System.out.println("🎧 Starting audio receiving");
                try {
                    InputStream in = audioSocket.getInputStream();
                    byte[] buffer = new byte[BUFFER_SIZE];

                    while (isVideoCallActive.get() && speaker.isOpen()) {
                        int bytesRead = in.read(buffer);
                        if (bytesRead > 0) {
                            speaker.write(buffer, 0, bytesRead);
                        }
                    }
                } catch (IOException e) {
                    System.err.println("❌ Audio receiving error: " + e.getMessage());
                }
                System.out.println("🎧 Audio receiving ended");
            });
            audioReceiveThread.setDaemon(true);
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
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        try {
            if (videoSendThread != null) {
                videoSendThread.interrupt();
            }
            if (videoReceiveThread != null) {
                videoReceiveThread.interrupt();
            }
            if (audioSendThread != null) {
                audioSendThread.interrupt();
            }
            if (audioReceiveThread != null) {
                audioReceiveThread.interrupt();
            }

            closeWebcam();

            if (microphone != null && microphone.isOpen()) {
                microphone.stop();
                microphone.close();
            }
            if (speaker != null && speaker.isOpen()) {
                speaker.stop();
                speaker.close();
            }
            if (videoSocket != null && !videoSocket.isClosed()) {
                videoSocket.close();
            }
            if (audioSocket != null && !audioSocket.isClosed()) {
                audioSocket.close();
            }

            localVideoView = null;
            remoteVideoView = null;

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