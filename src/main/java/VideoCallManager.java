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
    private static final int FPS = 15; // Frames per second

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
        // Video server
        new Thread(() -> {
            try {
                videoServer = new ServerSocket(myVideoPort);
                System.out.println("📹 Video server started on port: " + myVideoPort);

                while (!Thread.currentThread().isInterrupted()) {
                    Socket clientSocket = videoServer.accept();
                    handleIncomingVideoCall(clientSocket);
                }
            } catch (SocketException e) {
                // Server closed
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();

        // Audio server for video calls
        new Thread(() -> {
            try {
                audioServer = new ServerSocket(myAudioPort);
                System.out.println("🎤 Video audio server started on port: " + myAudioPort);

                while (!Thread.currentThread().isInterrupted()) {
                    Socket clientSocket = audioServer.accept();
                    this.audioSocket = clientSocket;
                    if (isVideoCallActive.get()) {
                        startAudioStreaming();
                    }
                }
            } catch (SocketException e) {
                // Server closed
            } catch (IOException e) {
                e.printStackTrace();
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
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));
            String message = reader.readLine();

            if (message != null && message.startsWith("VIDEO_CALL:")) {
                String caller = message.split(":")[1];
                System.out.println("📹 Incoming video call from: " + caller);

                this.videoSocket = socket;

                // Notify UI about incoming call
                if (callListener != null) {
                    callListener.onIncomingVideoCall(caller);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void acceptVideoCall() {
        if (videoSocket != null && !videoSocket.isClosed()) {
            try {
                PrintWriter writer = new PrintWriter(videoSocket.getOutputStream(), true);
                writer.println("VIDEO_CALL_ACCEPTED:" + currentUser);
                initializeWebcam();
                startVideoStreaming();

                if (callListener != null) {
                    callListener.onCallAccepted();
                }
                System.out.println("✅ Video call accepted");
            } catch (IOException e) {
                e.printStackTrace();
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
                e.printStackTrace();
            }
        }
    }

    public boolean startVideoCall(String peerIp, int peerVideoPort, int peerAudioPort,
                                  ImageView localView, ImageView remoteView) {
        if (isVideoCallActive.get()) {
            System.err.println("❌ Video call already in progress");
            return false;
        }

        this.localVideoView = localView;
        this.remoteVideoView = remoteView;

        try {
            // Connect video
            videoSocket = new Socket(peerIp, peerVideoPort);
            PrintWriter writer = new PrintWriter(videoSocket.getOutputStream(), true);
            writer.println("VIDEO_CALL:" + currentUser);

            // Connect audio
            audioSocket = new Socket(peerIp, peerAudioPort);

            initializeWebcam();
            startVideoStreaming();
            startAudioStreaming();

            System.out.println("✅ Video call started with " + peerIp);
            return true;
        } catch (IOException e) {
            System.err.println("❌ Failed to start video call: " + e.getMessage());
            return false;
        }
    }

    private void initializeWebcam() {
        if (webcam == null) {
            webcam = Webcam.getDefault();
            if (webcam != null) {
                webcam.setViewSize(VIDEO_SIZE);
                webcam.open();
                System.out.println("📹 Webcam initialized");
            } else {
                System.err.println("❌ No webcam found");
            }
        }
    }

    private void startVideoStreaming() {
        isVideoCallActive.set(true);

        // Send video thread
        videoSendThread = new Thread(() -> {
            try {
                OutputStream out = videoSocket.getOutputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();

                long frameDelay = 1000 / FPS;

                while (isVideoCallActive.get() && webcam != null) {
                    long startTime = System.currentTimeMillis();

                    BufferedImage image = webcam.getImage();
                    if (image != null) {
                        // Display local video
                        if (localVideoView != null) {
                            Platform.runLater(() -> {
                                Image fxImage = SwingFXUtils.toFXImage(image, null);
                                localVideoView.setImage(fxImage);
                            });
                        }

                        // Send image to peer
                        baos.reset();
                        ImageIO.write(image, "jpg", baos);
                        byte[] imageBytes = baos.toByteArray();

                        // Send image size first
                        DataOutputStream dos = new DataOutputStream(out);
                        dos.writeInt(imageBytes.length);
                        dos.write(imageBytes);
                        dos.flush();
                    }

                    // Frame rate control
                    long elapsedTime = System.currentTimeMillis() - startTime;
                    long sleepTime = frameDelay - elapsedTime;
                    if (sleepTime > 0) {
                        Thread.sleep(sleepTime);
                    }
                }
            } catch (IOException | InterruptedException e) {
                System.err.println("❌ Error sending video: " + e.getMessage());
                endVideoCall();
            }
        });
        videoSendThread.start();

        // Receive video thread
        videoReceiveThread = new Thread(() -> {
            try {
                DataInputStream dis = new DataInputStream(videoSocket.getInputStream());

                while (isVideoCallActive.get()) {
                    int imageSize = dis.readInt();
                    byte[] imageBytes = new byte[imageSize];
                    dis.readFully(imageBytes);

                    ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes);
                    BufferedImage image = ImageIO.read(bais);

                    if (image != null && remoteVideoView != null) {
                        Platform.runLater(() -> {
                            Image fxImage = SwingFXUtils.toFXImage(image, null);
                            remoteVideoView.setImage(fxImage);
                        });
                    }
                }
            } catch (IOException e) {
                System.err.println("❌ Error receiving video: " + e.getMessage());
                endVideoCall();
            }
        });
        videoReceiveThread.start();
    }

    private void startAudioStreaming() {
        // Initialize audio devices
        try {
            DataLine.Info micInfo = new DataLine.Info(TargetDataLine.class, AUDIO_FORMAT);
            microphone = (TargetDataLine) AudioSystem.getLine(micInfo);
            microphone.open(AUDIO_FORMAT);
            microphone.start();

            DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, AUDIO_FORMAT);
            speaker = (SourceDataLine) AudioSystem.getLine(speakerInfo);
            speaker.open(AUDIO_FORMAT);
            speaker.start();

            System.out.println("🎤 Audio devices initialized for video call");
        } catch (LineUnavailableException e) {
            e.printStackTrace();
            return;
        }

        // Send audio thread
        audioSendThread = new Thread(() -> {
            try {
                OutputStream out = audioSocket.getOutputStream();
                byte[] buffer = new byte[BUFFER_SIZE];

                while (isVideoCallActive.get()) {
                    int bytesRead = microphone.read(buffer, 0, buffer.length);
                    if (bytesRead > 0) {
                        out.write(buffer, 0, bytesRead);
                        out.flush();
                    }
                }
            } catch (IOException e) {
                System.err.println("❌ Error sending audio: " + e.getMessage());
            }
        });
        audioSendThread.start();

        // Receive audio thread
        audioReceiveThread = new Thread(() -> {
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
                System.err.println("❌ Error receiving audio: " + e.getMessage());
            }
        });
        audioReceiveThread.start();
    }

    public void endVideoCall() {
        if (!isVideoCallActive.get()) {
            return;
        }

        isVideoCallActive.set(false);

        try {
            if (videoSendThread != null) videoSendThread.interrupt();
            if (videoReceiveThread != null) videoReceiveThread.interrupt();
            if (audioSendThread != null) audioSendThread.interrupt();
            if (audioReceiveThread != null) audioReceiveThread.interrupt();

            if (webcam != null && webcam.isOpen()) {
                webcam.close();
                webcam = null;
            }
            if (microphone != null) {
                microphone.stop();
                microphone.close();
            }
            if (speaker != null) {
                speaker.stop();
                speaker.close();
            }
            if (videoSocket != null && !videoSocket.isClosed()) {
                videoSocket.close();
            }
            if (audioSocket != null && !audioSocket.isClosed()) {
                audioSocket.close();
            }

            if (callListener != null) {
                callListener.onCallEnded();
            }

            System.out.println("📹 Video call ended");
        } catch (IOException e) {
            e.printStackTrace();
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
            e.printStackTrace();
        }
    }
}