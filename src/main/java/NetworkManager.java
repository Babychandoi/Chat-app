import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;

public class NetworkManager {
    private MainController mainController;
    private String currentUser;
    private int myTcpPort;
    private int myFilePort;

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
    }

    public void startServer() {
        startChatServer();
        startFileServer();
        startDiscoveryServer();
        announcePresence();
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
                discoveryServer = new ServerSocket(8889);
                System.out.println("✓ Discovery server started on port: 8889");

                while (!Thread.currentThread().isInterrupted()) {
                    Socket socket = discoveryServer.accept();
                    handleDiscoveryConnection(socket);
                }
            } catch (IOException e) {
                System.out.println("Discovery server stopped");
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
                    if (parts.length >= 7) {
                        String peerName = parts[1];
                        int peerPort = Integer.parseInt(parts[2]);
                        int peerFilePort = Integer.parseInt(parts[3]);
                        int peerVoicePort = Integer.parseInt(parts[4]);
                        int peerVideoPort = Integer.parseInt(parts[5]);
                        int peerVideoAudioPort = Integer.parseInt(parts[6]);
                        String peerIp = socket.getInetAddress().getHostAddress();

                        if (!peerName.equals(currentUser)) {
                            writer.println("PEER:" + currentUser + ":" + myTcpPort + ":" + myFilePort +
                                    ":" + mainController.getCallManager().getVoiceCallManager().getVoicePort() +
                                    ":" + mainController.getCallManager().getVideoCallManager().getVideoPort() +
                                    ":" + mainController.getCallManager().getVideoCallManager().getAudioPort());
                            addPeer(peerName, peerIp, peerPort, peerFilePort, peerVoicePort, peerVideoPort, peerVideoAudioPort);
                            System.out.println("✓ Discovered peer: " + peerName + " at " + peerIp + ":" + peerPort);
                        }
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
            try {
                InetAddress localhost = InetAddress.getLocalHost();
                String subnet = localhost.getHostAddress().substring(0,
                        localhost.getHostAddress().lastIndexOf('.'));

                System.out.println("🔍 Scanning network: " + subnet + ".x");

                for (int i = 1; i < 255; i++) {
                    final int host = i;
                    new Thread(() -> {
                        try {
                            String ip = subnet + "." + host;
                            Socket socket = new Socket();
                            socket.connect(new InetSocketAddress(ip, 8889), 100);

                            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                            writer.println("ANNOUNCE:" + currentUser + ":" + myTcpPort + ":" + myFilePort +
                                    ":" + mainController.getCallManager().getVoiceCallManager().getVoicePort() +
                                    ":" + mainController.getCallManager().getVideoCallManager().getVideoPort() +
                                    ":" + mainController.getCallManager().getVideoCallManager().getAudioPort());

                            BufferedReader reader = new BufferedReader(
                                    new InputStreamReader(socket.getInputStream()));
                            String response = reader.readLine();

                            if (response != null && response.startsWith("PEER:")) {
                                String[] parts = response.split(":");
                                if (parts.length >= 7) {
                                    String peerName = parts[1];
                                    int peerPort = Integer.parseInt(parts[2]);
                                    int peerFilePort = Integer.parseInt(parts[3]);
                                    int peerVoicePort = Integer.parseInt(parts[4]);
                                    int peerVideoPort = Integer.parseInt(parts[5]);
                                    int peerVideoAudioPort = Integer.parseInt(parts[6]);

                                    if (!peerName.equals(currentUser)) {
                                        addPeer(peerName, ip, peerPort, peerFilePort, peerVoicePort, peerVideoPort, peerVideoAudioPort);
                                    }
                                }
                            }

                            socket.close();
                        } catch (IOException e) {
                            // Host not reachable
                        }
                    }).start();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void addPeer(String username, String ip, int port, int filePort,
                         int voicePort, int videoPort, int videoAudioPort) {
        if (!discoveredPeers.containsKey(username)) {
            PeerInfo peer = new PeerInfo(ip, port, filePort, voicePort, videoPort, videoAudioPort);
            discoveredPeers.put(username, peer);

            mainController.getChatManager().refreshContactList();
            System.out.println("➕ Added peer: " + username);
        }
    }

    public void handlePeerMessage(String from, String message) {
        System.out.println("📩 From " + from + ": " + message);

        String[] parts = message.split(":", 3);
        if (parts.length < 2) return;

        String type = parts[0];

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

        } else if (type.equals("CALL_ACCEPTED")) {
            mainController.getCallManager().handleCallAccepted(from);

        } else if (type.equals("CALL_REJECTED")) {
            mainController.getCallManager().handleCallRejected(from);

        } else if (type.equals("VIDEO_CALL_ACCEPTED")) {
            mainController.getCallManager().handleVideoCallAccepted(from);

        } else if (type.equals("VIDEO_CALL_REJECTED")) {
            mainController.getCallManager().handleVideoCallRejected(from);
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
            try {
                File existingFile = new File("shared_files/" + uniqueFileName);
                if (existingFile.exists()) {
                    System.out.println("✓ File already exists: " + uniqueFileName);
                    if (onComplete != null) {
                        onComplete.run();
                    }
                    return;
                }

                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(senderIp, senderFilePort), 5000);

                DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                DataInputStream dis = new DataInputStream(socket.getInputStream());

                dos.writeUTF("REQUEST_FILE");
                dos.writeUTF(uniqueFileName);
                dos.flush();

                String response = dis.readUTF();
                if (response.equals("OK")) {
                    long fileSize = dis.readLong();
                    String fileName = dis.readUTF();

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

                    System.out.println("✓ Downloaded file: " + displayName + " (" + totalBytesRead + " bytes)");

                    if (onComplete != null) {
                        onComplete.run();
                    }

                } else {
                    String error = dis.readUTF();
                    System.err.println("✗ Cannot download file: " + error);
                    mainController.getChatManager().showAlert("Lỗi", "Không thể tải file: " + error);
                }
            } catch (IOException e) {
                System.err.println("✗ Failed to download file from " + senderIp + ":" + senderFilePort + " - " + e.getMessage());
                mainController.getChatManager().showAlert("Lỗi", "Không thể kết nối để tải file!");
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
            InetAddress localhost = InetAddress.getLocalHost();
            String myIp = localhost.getHostAddress();

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
                InetAddress localhost = InetAddress.getLocalHost();
                String myIp = localhost.getHostAddress();

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