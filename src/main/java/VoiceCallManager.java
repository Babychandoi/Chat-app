import javax.sound.sampled.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class VoiceCallManager {
    private static final int VOICE_PORT_BASE = 9000;
    // Đổi sang little-endian (false) để tương thích với Windows
    private static final AudioFormat AUDIO_FORMAT = new AudioFormat(16000, 16, 1, true, false);
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

        // Initialize audio devices với retry mechanism
        try {
            DataLine.Info micInfo = new DataLine.Info(TargetDataLine.class, AUDIO_FORMAT);
            
            // RETRY: Thử mở microphone tối đa 3 lần (có thể video call chưa release xong)
            int retries = 3;
            boolean micOpened = false;
            for (int i = 0; i < retries; i++) {
                try {
                    System.out.println("🎤 [VOICE] Attempting to open microphone (attempt " + (i+1) + "/" + retries + ")...");
                    microphone = (TargetDataLine) AudioSystem.getLine(micInfo);
                    microphone.open(AUDIO_FORMAT);
                    microphone.start();
                    System.out.println("✅ [VOICE] Microphone opened successfully");
                    micOpened = true;
                    break;
                } catch (LineUnavailableException e) {
                    System.err.println("⚠️ [VOICE] Microphone unavailable (attempt " + (i+1) + "): " + e.getMessage());
                    if (i < retries - 1) {
                        try {
                            System.out.println("⏳ [VOICE] Waiting 1 second before retry...");
                            Thread.sleep(1000); // Đợi 1 giây trước khi retry
                        } catch (InterruptedException ie) {
                            System.err.println("⚠️ [VOICE] Interrupted while waiting for retry");
                            Thread.currentThread().interrupt();
                            throw e; // Throw original exception nếu bị interrupt
                        }
                    } else {
                        throw e; // Throw nếu hết retries
                    }
                }
            }
            
            if (!micOpened) {
                throw new LineUnavailableException("Failed to open microphone after " + retries + " attempts");
            }

            DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, AUDIO_FORMAT);
            speaker = (SourceDataLine) AudioSystem.getLine(speakerInfo);
            speaker.open(AUDIO_FORMAT);
            
            // Giảm volume của speaker để tránh echo (vọng)
            if (speaker.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl volumeControl = (FloatControl) speaker.getControl(FloatControl.Type.MASTER_GAIN);
                // Giảm xuống -20dB để tránh microphone thu lại (tăng từ -10dB)
                float currentVolume = volumeControl.getValue();
                float newVolume = Math.max(volumeControl.getMinimum(), currentVolume - 20.0f);
                volumeControl.setValue(newVolume);
                System.out.println("🔊 Speaker volume reduced to: " + newVolume + " dB (from " + currentVolume + " dB)");
                System.out.println("💡 TIP: Use headphones to avoid echo!");
            } else {
                System.out.println("⚠️ Cannot control speaker volume - echo may occur");
                System.out.println("💡 STRONGLY RECOMMENDED: Use headphones!");
            }
            
            speaker.start();
            System.out.println("✅ Voice speaker opened");

            System.out.println("✅ Voice devices initialized");
        } catch (LineUnavailableException e) {
            System.err.println("❌ Voice audio devices unavailable: " + e.getMessage());
            e.printStackTrace();
            isCallActive.set(false);
            return;
        }

        // Send audio thread
        sendThread = new Thread(() -> {
            System.out.println("🎤 Voice sending thread started");
            int bytesSent = 0;
            int silentPackets = 0;
            final int NOISE_GATE_THRESHOLD = 500; // Ngưỡng để coi là có tiếng nói
            
            try {
                OutputStream out = voiceSocket.getOutputStream();
                byte[] buffer = new byte[BUFFER_SIZE];

                while (isCallActive.get()) {
                    int bytesRead = microphone.read(buffer, 0, buffer.length);
                    if (bytesRead > 0) {
                        // Tính RMS (Root Mean Square) để đo volume
                        int sum = 0;
                        for (int i = 0; i < bytesRead; i += 2) {
                            // Convert 2 bytes to 16-bit sample
                            short sample = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF));
                            sum += sample * sample;
                        }
                        int rms = (int) Math.sqrt(sum / (bytesRead / 2));
                        
                        // Chỉ gửi nếu volume đủ lớn (noise gate)
                        if (rms > NOISE_GATE_THRESHOLD) {
                            out.write(buffer, 0, bytesRead);
                            out.flush();
                            bytesSent += bytesRead;
                            
                            if (bytesSent % 10240 == 0) {
                                System.out.println("🎤 Voice sent: " + (bytesSent / 1024) + " KB");
                            }
                        } else {
                            silentPackets++;
                            // Gửi silence packet thay vì skip (để tránh jitter)
                            byte[] silence = new byte[bytesRead];
                            out.write(silence, 0, bytesRead);
                            out.flush();
                        }
                    }
                }
            } catch (IOException e) {
                if (isCallActive.get()) { // Chỉ log nếu call vẫn active
                    System.err.println("❌ Error sending voice audio: " + e.getMessage());
                }
                // Đặt flag để dừng, không gọi endCall() trực tiếp
                isCallActive.set(false);
            }
            System.out.println("🎤 Voice sending ended. Total: " + (bytesSent / 1024) + " KB (Silent: " + silentPackets + " packets)");
            
            // QUAN TRỌNG: Gọi cleanup khi thread kết thúc (nếu chưa cleanup)
            if (isCallActive.get() || microphone != null || speaker != null) {
                System.out.println("🔄 [VOICE] Send thread spawning cleanup...");
                new Thread(() -> {
                    try {
                        Thread.sleep(200); // Đợi cả 2 threads exit
                        System.out.println("🔄 [VOICE] Cleanup thread calling endCall()...");
                        endCall();
                    } catch (InterruptedException e) {
                        System.err.println("⚠️ [VOICE] Cleanup thread interrupted");
                    }
                }, "VoiceCallCleanup").start();
            }
        });
        sendThread.start();

        // Receive audio thread
        receiveThread = new Thread(() -> {
            System.out.println("🎧 Voice receiving thread started");
            int bytesReceived = 0;
            try {
                InputStream in = voiceSocket.getInputStream();
                byte[] buffer = new byte[BUFFER_SIZE];

                while (isCallActive.get()) {
                    int bytesRead = in.read(buffer, 0, buffer.length);
                    if (bytesRead > 0) {
                        speaker.write(buffer, 0, bytesRead);
                        bytesReceived += bytesRead;
                        
                        if (bytesReceived % 10240 == 0) {
                            System.out.println("🎧 Voice received: " + (bytesReceived / 1024) + " KB");
                        }
                    } else if (bytesRead == -1) {
                        System.out.println("📞 Remote ended voice call");
                        // KHÔNG set flag ở đây, để endCall() làm
                        // Gọi endCall() từ thread khác để tránh self-interrupt
                        System.out.println("🔄 [VOICE] Spawning cleanup thread...");
                        new Thread(() -> {
                            try {
                                Thread.sleep(100); // Đợi thread này exit
                                System.out.println("🔄 [VOICE] Cleanup thread calling endCall()...");
                                endCall();
                            } catch (InterruptedException e) {
                                System.err.println("⚠️ [VOICE] Cleanup thread interrupted");
                            }
                        }, "VoiceCallCleanup").start();
                        break;
                    }
                }
            } catch (IOException e) {
                if (isCallActive.get()) { // Chỉ log nếu call vẫn active
                    System.err.println("❌ Error receiving voice audio: " + e.getMessage());
                    // Gọi cleanup từ thread khác
                    System.out.println("🔄 [VOICE] Spawning cleanup thread (from exception)...");
                    new Thread(() -> {
                        try {
                            Thread.sleep(100);
                            System.out.println("🔄 [VOICE] Cleanup thread calling endCall()...");
                            endCall();
                        } catch (InterruptedException ie) {
                            System.err.println("⚠️ [VOICE] Cleanup thread interrupted");
                        }
                    }, "VoiceCallCleanup").start();
                } else {
                    // Call đã stopped, chỉ set flag
                    isCallActive.set(false);
                }
            }
            System.out.println("🎧 Voice receiving ended. Total: " + (bytesReceived / 1024) + " KB");
            
            // QUAN TRỌNG: Gọi cleanup khi thread kết thúc (nếu chưa cleanup)
            if (isCallActive.get() || microphone != null || speaker != null) {
                System.out.println("🔄 [VOICE] Receive thread spawning cleanup...");
                new Thread(() -> {
                    try {
                        Thread.sleep(200); // Đợi cả 2 threads exit
                        System.out.println("🔄 [VOICE] Cleanup thread calling endCall()...");
                        endCall();
                    } catch (InterruptedException e) {
                        System.err.println("⚠️ [VOICE] Cleanup thread interrupted");
                    }
                }, "VoiceCallCleanup").start();
            }
        });
        receiveThread.start();
    }

    public void endCall() {
        // Sử dụng compareAndSet để đảm bảo chỉ cleanup một lần
        if (!isCallActive.compareAndSet(true, false)) {
            // Nếu đã false rồi, vẫn cần cleanup nếu chưa cleanup
            if (microphone == null && speaker == null && voiceSocket == null) {
                System.out.println("🛑 [VOICE] Already cleaned up, skipping");
                return;
            }
            System.out.println("🛑 [VOICE] Call already stopped, but cleaning up resources...");
        } else {
            System.out.println("🛑 [VOICE] Ending voice call...");
        }

        try {
            // Gửi thông báo END_CALL trước khi đóng socket
            if (voiceSocket != null && !voiceSocket.isClosed()) {
                try {
                    PrintWriter writer = new PrintWriter(voiceSocket.getOutputStream(), true);
                    writer.println("VOICE_CALL_ENDED");
                    System.out.println("📤 [VOICE] Sent VOICE_CALL_ENDED signal");
                } catch (IOException e) {
                    // Socket đã đóng, bỏ qua
                }
            }
            
            // Delay nhỏ để message được gửi đi
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                System.out.println("⚠️ [VOICE] Interrupted while sending end signal");
                Thread.currentThread().interrupt(); // Restore interrupt status
            }
            
            // DỪNG THREADS TRƯỚC (nhưng không interrupt thread hiện tại)
            System.out.println("🛑 [VOICE] Stopping threads...");
            Thread currentThread = Thread.currentThread();
            if (sendThread != null && sendThread != currentThread) {
                sendThread.interrupt();
            }
            if (receiveThread != null && receiveThread != currentThread) {
                receiveThread.interrupt();
            }
            
            // Đợi threads dừng (nếu không phải thread hiện tại)
            if (currentThread != sendThread && currentThread != receiveThread) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    System.out.println("⚠️ [VOICE] Interrupted while waiting for threads");
                    Thread.currentThread().interrupt();
                }
            }

            // ĐÓNG AUDIO DEVICES
            System.out.println("🛑 [VOICE] Closing audio devices...");
            if (microphone != null && microphone.isOpen()) {
                microphone.stop();
                microphone.flush();
                microphone.close();
                System.out.println("✅ [VOICE] Microphone closed");
            }
            microphone = null;
            
            if (speaker != null && speaker.isOpen()) {
                speaker.stop();
                speaker.flush();
                speaker.close();
                System.out.println("✅ [VOICE] Speaker closed");
            }
            speaker = null;
            
            // ĐÓNG SOCKET
            if (voiceSocket != null && !voiceSocket.isClosed()) {
                voiceSocket.close();
                System.out.println("✅ [VOICE] Socket closed");
            }
            voiceSocket = null;

            // Đợi audio system release
            System.out.println("🔄 [VOICE] Waiting for audio system to release...");
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                System.out.println("⚠️ [VOICE] Interrupted while waiting for audio release");
                // Không restore interrupt vì đang cleanup
            }

            if (callListener != null) {
                callListener.onCallEnded();
            }

            System.out.println("✅ [VOICE] Voice call ended completely");
        } catch (Exception e) {
            System.err.println("❌ [VOICE] Error ending call: " + e.getMessage());
            // Không print stack trace cho InterruptedException
            if (!(e instanceof InterruptedException)) {
                e.printStackTrace();
            }
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