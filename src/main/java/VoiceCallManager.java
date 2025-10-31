import javax.sound.sampled.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class VoiceCallManager {
    private static final int VOICE_PORT_BASE = 9000;
    private static final AudioFormat AUDIO_FORMAT = new AudioFormat(16000, 16, 1, true, true);
    private static final int BUFFER_SIZE = 1024;

    private ServerSocket voiceServer;
    private Socket voiceSocket;
    private TargetDataLine microphone;
    private SourceDataLine speaker;
    private AtomicBoolean isCallActive = new AtomicBoolean(false);
    private Thread sendThread;
    private Thread receiveThread;
    private int myVoicePort;
    private String currentUser;
    private CallListener callListener;

    public interface CallListener {
        void onIncomingVoiceCall(String caller);
        void onCallAccepted();
        void onCallRejected();
        void onCallEnded();
    }

    public VoiceCallManager(String currentUser) {
        this.currentUser = currentUser;
        this.myVoicePort = VOICE_PORT_BASE + Math.abs(currentUser.hashCode() % 1000);
    }

    public void setCallListener(CallListener listener) {
        this.callListener = listener;
    }

    public void startVoiceServer() {
        new Thread(() -> {
            try {
                voiceServer = new ServerSocket(myVoicePort);
                System.out.println("🎤 Voice server started on port: " + myVoicePort);

                while (!Thread.currentThread().isInterrupted()) {
                    Socket clientSocket = voiceServer.accept();
                    handleIncomingCall(clientSocket);
                }
            } catch (SocketException e) {
                // Server closed
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    public int getVoicePort() {
        return myVoicePort;
    }

    private void handleIncomingCall(Socket socket) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));
            String message = reader.readLine();

            if (message != null && message.startsWith("VOICE_CALL:")) {
                String caller = message.split(":")[1];
                System.out.println("📞 Incoming voice call from: " + caller);

                // Store the socket for later use
                this.voiceSocket = socket;

                // Notify UI about incoming call
                if (callListener != null) {
                    callListener.onIncomingVoiceCall(caller);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void acceptCall() {
        if (voiceSocket != null && !voiceSocket.isClosed()) {
            try {
                PrintWriter writer = new PrintWriter(voiceSocket.getOutputStream(), true);
                writer.println("CALL_ACCEPTED:" + currentUser);
                startVoiceStreaming();

                if (callListener != null) {
                    callListener.onCallAccepted();
                }
                System.out.println("✅ Voice call accepted");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void rejectCall() {
        if (voiceSocket != null && !voiceSocket.isClosed()) {
            try {
                PrintWriter writer = new PrintWriter(voiceSocket.getOutputStream(), true);
                writer.println("CALL_REJECTED:" + currentUser);
                voiceSocket.close();

                if (callListener != null) {
                    callListener.onCallRejected();
                }
                System.out.println("❌ Voice call rejected");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public boolean startCall(String peerIp, int peerVoicePort) {
        if (isCallActive.get()) {
            System.err.println("❌ Call already in progress");
            return false;
        }

        try {
            voiceSocket = new Socket(peerIp, peerVoicePort);
            PrintWriter writer = new PrintWriter(voiceSocket.getOutputStream(), true);
            writer.println("VOICE_CALL:" + currentUser);

            startVoiceStreaming();
            System.out.println("✅ Voice call started with " + peerIp);
            return true;
        } catch (IOException e) {
            System.err.println("❌ Failed to start voice call: " + e.getMessage());
            return false;
        }
    }

    private void startVoiceStreaming() {
        isCallActive.set(true);

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

            System.out.println("🎤 Microphone and speaker initialized");
        } catch (LineUnavailableException e) {
            e.printStackTrace();
            isCallActive.set(false);
            return;
        }

        // Send audio thread
        sendThread = new Thread(() -> {
            try {
                OutputStream out = voiceSocket.getOutputStream();
                byte[] buffer = new byte[BUFFER_SIZE];

                while (isCallActive.get()) {
                    int bytesRead = microphone.read(buffer, 0, buffer.length);
                    if (bytesRead > 0) {
                        out.write(buffer, 0, bytesRead);
                        out.flush();
                    }
                }
            } catch (IOException e) {
                System.err.println("❌ Error sending audio: " + e.getMessage());
                endCall();
            }
        });
        sendThread.start();

        // Receive audio thread
        receiveThread = new Thread(() -> {
            try {
                InputStream in = voiceSocket.getInputStream();
                byte[] buffer = new byte[BUFFER_SIZE];

                while (isCallActive.get()) {
                    int bytesRead = in.read(buffer, 0, buffer.length);
                    if (bytesRead > 0) {
                        speaker.write(buffer, 0, bytesRead);
                    }
                }
            } catch (IOException e) {
                System.err.println("❌ Error receiving audio: " + e.getMessage());
                endCall();
            }
        });
        receiveThread.start();
    }

    public void endCall() {
        if (!isCallActive.get()) {
            return;
        }

        isCallActive.set(false);

        try {
            if (sendThread != null) sendThread.interrupt();
            if (receiveThread != null) receiveThread.interrupt();

            if (microphone != null) {
                microphone.stop();
                microphone.close();
            }
            if (speaker != null) {
                speaker.stop();
                speaker.close();
            }
            if (voiceSocket != null && !voiceSocket.isClosed()) {
                voiceSocket.close();
            }

            if (callListener != null) {
                callListener.onCallEnded();
            }

            System.out.println("📞 Call ended");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public boolean isCallActive() {
        return isCallActive.get();
    }

    public void shutdown() {
        endCall();
        try {
            if (voiceServer != null && !voiceServer.isClosed()) {
                voiceServer.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}