import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.image.ImageView;
import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
import javafx.util.Duration;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class P2PChatApp extends Application {

    // Cấu hình
    private static final int BASE_TCP_PORT = 8888;
    private static final int DISCOVERY_PORT = 8889;
    private static final int FILE_TRANSFER_PORT = 8890;
    private static final String USERS_FILE = "users.txt";
    private static final String CHAT_HISTORY_DIR = "chat_history/";
    private static final String GROUPS_DIR = "groups/";
    private static final String SHARED_FILES_DIR = "shared_files/";
    private static final int BUFFER_SIZE = 8192;

    // Dữ liệu ứng dụng
    private String currentUser;
    private int myTcpPort;
    private int myFilePort;
    private ServerSocket serverSocket;
    private ServerSocket discoveryServer;
    private ServerSocket fileServer;

    // Lưu kết nối persistent với từng peer
    private Map<String, PeerConnection> peerConnections = new ConcurrentHashMap<>();
    private Map<String, PeerInfo> discoveredPeers = new ConcurrentHashMap<>();
    private Map<String, ChatGroup> chatGroups = new ConcurrentHashMap<>();

    // Call managers
    private VoiceCallManager voiceCallManager;
    private VideoCallManager videoCallManager;
    private Stage videoCallStage;

    // UI Components
    private Stage primaryStage;
    private VBox contactListContainer;
    private VBox chatMessageContainer;
    private ScrollPane chatScrollPane;
    private TextField messageField;
    private Label chatTitleLabel;
    private String currentChatTarget;
    private boolean isGroupChat = false;
    private Button voiceCallBtn;
    private Button videoCallBtn;

    // Call UI
    private Stage incomingCallStage;
    private String incomingCaller;
    private boolean isVideoCall;
    private Stage voiceCallStage;

    public static void main(String[] args) {
        System.setProperty("file.encoding", "UTF-8");
        try {
            System.setOut(new PrintStream(System.out, true, "UTF-8"));
            System.setErr(new PrintStream(System.err, true, "UTF-8"));
        } catch (Exception e) {
            e.printStackTrace();
        }
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        primaryStage.setTitle("P2P Chat - Real-time");

        new File(CHAT_HISTORY_DIR).mkdirs();
        new File(GROUPS_DIR).mkdirs();
        new File(SHARED_FILES_DIR).mkdirs();
        showLoginScreen();
    }

    // ============== AUTHENTICATION ==============

    private void showLoginScreen() {
        VBox root = new VBox(20);
        root.setPadding(new Insets(40));
        root.setAlignment(Pos.CENTER);
        root.setStyle("-fx-background-color: linear-gradient(to bottom, #0068FF, #0091FF);");

        Label titleLabel = new Label("💬 Chat P2P");
        titleLabel.setFont(Font.font("Arial", FontWeight.BOLD, 36));
        titleLabel.setStyle("-fx-text-fill: white;");

        Label subtitleLabel = new Label("Real-time Event-Driven + Voice/Video Call");
        subtitleLabel.setFont(Font.font("Arial", 16));
        subtitleLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.8);");

        VBox formBox = new VBox(15);
        formBox.setMaxWidth(350);
        formBox.setPadding(new Insets(30));
        formBox.setStyle("-fx-background-color: white; -fx-background-radius: 15;");

        TextField usernameField = new TextField();
        usernameField.setPromptText("Tên đăng nhập");
        usernameField.setStyle("-fx-font-size: 14; -fx-padding: 12;");

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Mật khẩu");
        passwordField.setStyle("-fx-font-size: 14; -fx-padding: 12;");

        Button loginButton = new Button("Đăng nhập");
        loginButton.setStyle("-fx-background-color: #0068FF; -fx-text-fill: white; " +
                "-fx-font-size: 14; -fx-font-weight: bold; -fx-padding: 12; " +
                "-fx-background-radius: 8; -fx-cursor: hand;");
        loginButton.setPrefWidth(290);

        Button registerButton = new Button("Đăng ký tài khoản");
        registerButton.setStyle("-fx-background-color: transparent; -fx-text-fill: #0068FF; " +
                "-fx-font-size: 14; -fx-cursor: hand; -fx-border-color: #0068FF; " +
                "-fx-border-radius: 8; -fx-padding: 12;");
        registerButton.setPrefWidth(290);

        Label messageLabel = new Label();
        messageLabel.setStyle("-fx-text-fill: #FF3B30; -fx-font-size: 12;");
        messageLabel.setWrapText(true);

        loginButton.setOnAction(e -> {
            String username = usernameField.getText().trim();
            String password = passwordField.getText();

            if (username.isEmpty() || password.isEmpty()) {
                messageLabel.setText("Vui lòng điền đầy đủ thông tin!");
                return;
            }

            if (authenticate(username, password)) {
                currentUser = username;
                myTcpPort = BASE_TCP_PORT + Math.abs(username.hashCode() % 1000);
                myFilePort = FILE_TRANSFER_PORT + Math.abs(username.hashCode() % 1000);

                // Initialize call managers
                voiceCallManager = new VoiceCallManager(currentUser);
                videoCallManager = new VideoCallManager(currentUser);

                loadUserGroups();
                startServer();
                showChatScreen();
            } else {
                messageLabel.setText("Sai tên đăng nhập hoặc mật khẩu!");
            }
        });

        registerButton.setOnAction(e -> {
            String username = usernameField.getText().trim();
            String password = passwordField.getText();

            if (username.isEmpty() || password.isEmpty()) {
                messageLabel.setText("Vui lòng điền đầy đủ thông tin!");
                return;
            }

            if (username.length() < 3) {
                messageLabel.setText("Tên đăng nhập phải có ít nhất 3 ký tự!");
                return;
            }

            if (register(username, password)) {
                messageLabel.setStyle("-fx-text-fill: #34C759; -fx-font-size: 12;");
                messageLabel.setText("✓ Đăng ký thành công! Hãy đăng nhập.");
                passwordField.clear();
            } else {
                messageLabel.setText("Tên đăng nhập đã tồn tại!");
            }
        });

        passwordField.setOnAction(e -> loginButton.fire());

        formBox.getChildren().addAll(usernameField, passwordField, loginButton,
                registerButton, messageLabel);

        root.getChildren().addAll(titleLabel, subtitleLabel, formBox);

        Scene scene = new Scene(root, 500, 700);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private boolean authenticate(String username, String password) {
        try (BufferedReader reader = new BufferedReader(new FileReader(USERS_FILE))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(":");
                if (parts.length == 2 && parts[0].equals(username) && parts[1].equals(password)) {
                    return true;
                }
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    private boolean register(String username, String password) {
        try (BufferedReader reader = new BufferedReader(new FileReader(USERS_FILE))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(username + ":")) {
                    return false;
                }
            }
        } catch (IOException e) {
            // File không tồn tại
        }

        try (FileWriter writer = new FileWriter(USERS_FILE, true)) {
            writer.write(username + ":" + password + "\n");
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    // ============== CHAT SCREEN ==============

    private void showChatScreen() {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: white;");

        // Left sidebar
        VBox leftSidebar = new VBox();
        leftSidebar.setPrefWidth(320);
        leftSidebar.setStyle("-fx-background-color: white; -fx-border-color: #E5E5EA; " +
                "-fx-border-width: 0 1 0 0;");

        // User header
        HBox userHeader = new HBox(15);
        userHeader.setPadding(new Insets(15));
        userHeader.setAlignment(Pos.CENTER_LEFT);
        userHeader.setStyle("-fx-background-color: #0068FF;");

        Label userAvatar = new Label(currentUser.substring(0, 1).toUpperCase());
        userAvatar.setStyle("-fx-background-color: white; -fx-text-fill: #0068FF; " +
                "-fx-font-size: 18; -fx-font-weight: bold; " +
                "-fx-min-width: 45; -fx-min-height: 45; " +
                "-fx-max-width: 45; -fx-max-height: 45; " +
                "-fx-background-radius: 50%; -fx-alignment: center;");

        VBox userInfo = new VBox(2);
        Label usernameLabel = new Label(currentUser);
        usernameLabel.setStyle("-fx-text-fill: white; -fx-font-size: 16; -fx-font-weight: bold;");
        Label statusLabel = new Label("🟢 Đang hoạt động");
        statusLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.8); -fx-font-size: 12;");
        userInfo.getChildren().addAll(usernameLabel, statusLabel);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button addGroupBtn = new Button("➕");
        addGroupBtn.setStyle("-fx-background-color: rgba(255,255,255,0.2); " +
                "-fx-text-fill: white; -fx-font-size: 18; " +
                "-fx-min-width: 35; -fx-min-height: 35; " +
                "-fx-background-radius: 50%; -fx-cursor: hand;");
        addGroupBtn.setOnAction(e -> showCreateGroupDialog());

        userHeader.getChildren().addAll(userAvatar, userInfo, spacer, addGroupBtn);

        // Search box
        HBox searchBox = new HBox(10);
        searchBox.setPadding(new Insets(15));
        TextField searchField = new TextField();
        searchField.setPromptText("🔍 Tìm kiếm");
        searchField.setStyle("-fx-background-color: #F2F2F7; -fx-background-radius: 20; " +
                "-fx-padding: 10 15; -fx-font-size: 14;");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchBox.getChildren().add(searchField);

        // Contacts list
        ScrollPane contactsScrollPane = new ScrollPane();
        contactsScrollPane.setFitToWidth(true);
        contactsScrollPane.setStyle("-fx-background-color: white; -fx-background: white;");
        contactsScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        contactListContainer = new VBox();
        contactListContainer.setStyle("-fx-background-color: white;");
        contactsScrollPane.setContent(contactListContainer);
        VBox.setVgrow(contactsScrollPane, Priority.ALWAYS);

        leftSidebar.getChildren().addAll(userHeader, searchBox, contactsScrollPane);

        // Center chat area
        VBox centerPanel = new VBox();
        centerPanel.setStyle("-fx-background-color: #F0F0F0;");

        HBox chatHeader = new HBox(15);
        chatHeader.setPadding(new Insets(15));
        chatHeader.setAlignment(Pos.CENTER_LEFT);
        chatHeader.setStyle("-fx-background-color: white; -fx-border-color: #E5E5EA; " +
                "-fx-border-width: 0 0 1 0;");

        chatTitleLabel = new Label("Chọn một cuộc trò chuyện");
        chatTitleLabel.setStyle("-fx-font-size: 16; -fx-font-weight: bold; -fx-text-fill: #333;");

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        voiceCallBtn = new Button("📞");
        voiceCallBtn.setStyle("-fx-background-color: #34C759; -fx-text-fill: white; " +
                "-fx-font-size: 16; -fx-font-weight: bold; " +
                "-fx-min-width: 40; -fx-min-height: 40; " +
                "-fx-background-radius: 50%; -fx-cursor: hand;");
        voiceCallBtn.setOnAction(e -> startVoiceCall());
        voiceCallBtn.setVisible(false);

        videoCallBtn = new Button("📹");
        videoCallBtn.setStyle("-fx-background-color: #007AFF; -fx-text-fill: white; " +
                "-fx-font-size: 16; -fx-font-weight: bold; " +
                "-fx-min-width: 40; -fx-min-height: 40; " +
                "-fx-background-radius: 50%; -fx-cursor: hand;");
        videoCallBtn.setOnAction(e -> startVideoCall());
        videoCallBtn.setVisible(false);

        chatHeader.getChildren().addAll(chatTitleLabel, headerSpacer, voiceCallBtn, videoCallBtn);

        chatMessageContainer = new VBox();
        chatMessageContainer.setPadding(new Insets(10));
        chatMessageContainer.setSpacing(8);
        chatMessageContainer.setStyle("-fx-background-color: #F0F0F0;");

        chatScrollPane = new ScrollPane(chatMessageContainer);
        chatScrollPane.setFitToWidth(true);
        chatScrollPane.setStyle("-fx-background-color: #F0F0F0; -fx-background: #F0F0F0;");
        chatScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(chatScrollPane, Priority.ALWAYS);

        HBox messageInputBox = new HBox(10);
        messageInputBox.setPadding(new Insets(15));
        messageInputBox.setAlignment(Pos.CENTER);
        messageInputBox.setStyle("-fx-background-color: white;");

        // File attachment button
        Button attachButton = new Button("📎");
        attachButton.setStyle("-fx-background-color: #F2F2F7; -fx-text-fill: #0068FF; " +
                "-fx-font-size: 18; -fx-font-weight: bold; " +
                "-fx-min-width: 45; -fx-min-height: 45; " +
                "-fx-background-radius: 50%; -fx-cursor: hand;");
        attachButton.setOnAction(e -> selectAndSendFile());

        messageField = new TextField();
        messageField.setPromptText("Nhập tin nhắn...");
        messageField.setStyle("-fx-background-color: #F2F2F7; -fx-background-radius: 20; " +
                "-fx-padding: 12 20; -fx-font-size: 14;");
        HBox.setHgrow(messageField, Priority.ALWAYS);

        Button sendButton = new Button("➤");
        sendButton.setStyle("-fx-background-color: #0068FF; -fx-text-fill: white; " +
                "-fx-font-size: 18; -fx-font-weight: bold; " +
                "-fx-min-width: 45; -fx-min-height: 45; " +
                "-fx-background-radius: 50%; -fx-cursor: hand;");
        sendButton.setOnAction(e -> sendMessage());

        messageField.setOnAction(e -> sendMessage());

        messageInputBox.getChildren().addAll(attachButton, messageField, sendButton);

        centerPanel.getChildren().addAll(chatHeader, chatScrollPane, messageInputBox);

        root.setLeft(leftSidebar);
        root.setCenter(centerPanel);

        Scene scene = new Scene(root, 1100, 700);
        primaryStage.setScene(scene);
        primaryStage.setOnCloseRequest(e -> shutdown());

        // Initialize call listeners
        initializeCallListeners();

        refreshContactList();
    }

    private void initializeCallListeners() {
        voiceCallManager.setCallListener(new VoiceCallManager.CallListener() {
            @Override
            public void onIncomingVoiceCall(String caller) {
                showIncomingCallDialog(caller, false);
            }

            @Override
            public void onCallAccepted() {
                Platform.runLater(() -> {
                    if (incomingCallStage != null) {
                        incomingCallStage.close();
                    }
                    showAlert("Thông báo", "Cuộc gọi đã được chấp nhận");
                });
            }

            @Override
            public void onCallRejected() {
                Platform.runLater(() -> {
                    if (incomingCallStage != null) {
                        incomingCallStage.close();
                    }
                    showAlert("Thông báo", "Cuộc gọi bị từ chối");
                });
            }

            @Override
            public void onCallEnded() {
                Platform.runLater(() -> {
                    if (voiceCallStage != null) {
                        voiceCallStage.close();
                    }
                });
            }
        });

        videoCallManager.setCallListener(new VideoCallManager.CallListener() {
            @Override
            public void onIncomingVideoCall(String caller) {
                showIncomingCallDialog(caller, true);
            }

            @Override
            public void onCallAccepted() {
                Platform.runLater(() -> {
                    if (incomingCallStage != null) {
                        incomingCallStage.close();
                    }
                });
            }

            @Override
            public void onCallRejected() {
                Platform.runLater(() -> {
                    if (incomingCallStage != null) {
                        incomingCallStage.close();
                    }
                    showAlert("Thông báo", "Cuộc gọi video bị từ chối");
                });
            }

            @Override
            public void onCallEnded() {
                Platform.runLater(() -> {
                    // Clean up if needed
                });
            }
        });
    }

    // ============== INCOMING CALL DIALOG ==============

    private void showIncomingCallDialog(String caller, boolean isVideoCall) {
        Platform.runLater(() -> {
            this.incomingCaller = caller;
            this.isVideoCall = isVideoCall;

            // Close existing call dialog if any
            if (incomingCallStage != null && incomingCallStage.isShowing()) {
                incomingCallStage.close();
            }

            incomingCallStage = new Stage();
            incomingCallStage.initModality(Modality.APPLICATION_MODAL);
            incomingCallStage.initOwner(primaryStage);
            incomingCallStage.setTitle("Cuộc gọi đến");
            incomingCallStage.setResizable(false);

            VBox dialogBox = new VBox(20);
            dialogBox.setPadding(new Insets(30));
            dialogBox.setAlignment(Pos.CENTER);
            dialogBox.setStyle("-fx-background-color: linear-gradient(to bottom, #0068FF, #0091FF);");

            // Call icon
            Label callIcon = new Label(isVideoCall ? "📹" : "📞");
            callIcon.setStyle("-fx-font-size: 48;");

            // Caller info
            Label callerLabel = new Label(isVideoCall ? "Video call từ" : "Voice call từ");
            callerLabel.setStyle("-fx-text-fill: white; -fx-font-size: 16;");

            Label callerName = new Label(caller);
            callerName.setStyle("-fx-text-fill: white; -fx-font-size: 24; -fx-font-weight: bold;");

            Label statusLabel = new Label("Đang gọi...");
            statusLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.8); -fx-font-size: 14;");

            // Action buttons
            HBox buttonBox = new HBox(20);
            buttonBox.setAlignment(Pos.CENTER);

            Button acceptBtn = new Button(isVideoCall ? "📹 Chấp nhận" : "📞 Nghe máy");
            acceptBtn.setStyle("-fx-background-color: #34C759; -fx-text-fill: white; " +
                    "-fx-font-size: 16; -fx-font-weight: bold; -fx-padding: 12 25; " +
                    "-fx-background-radius: 25; -fx-cursor: hand;");

            Button rejectBtn = new Button("❌ Từ chối");
            rejectBtn.setStyle("-fx-background-color: #FF3B30; -fx-text-fill: white; " +
                    "-fx-font-size: 16; -fx-font-weight: bold; -fx-padding: 12 25; " +
                    "-fx-background-radius: 25; -fx-cursor: hand;");

            buttonBox.getChildren().addAll(acceptBtn, rejectBtn);

            // Button actions
            acceptBtn.setOnAction(e -> {
                if (isVideoCall) {
                    videoCallManager.acceptVideoCall();
                    // Video call UI will be shown from VideoCallManager
                } else {
                    voiceCallManager.acceptCall();
                    showVoiceCallDialog(caller); // Show voice call UI
                }
                incomingCallStage.close();
            });

            rejectBtn.setOnAction(e -> {
                if (isVideoCall) {
                    videoCallManager.rejectVideoCall();
                } else {
                    voiceCallManager.rejectCall();
                }
                incomingCallStage.close();
            });

            // Auto reject after 30 seconds
            Timeline autoReject = new Timeline(new KeyFrame(Duration.seconds(30), ev -> {
                if (incomingCallStage.isShowing()) {
                    if (isVideoCall) {
                        videoCallManager.rejectVideoCall();
                    } else {
                        voiceCallManager.rejectCall();
                    }
                    incomingCallStage.close();
                    showAlert("Thông báo", "Cuộc gọi đã hết thời gian chờ");
                }
            }));
            autoReject.play();

            dialogBox.getChildren().addAll(callIcon, callerLabel, callerName, statusLabel, buttonBox);

            Scene scene = new Scene(dialogBox, 350, 300);
            incomingCallStage.setScene(scene);
            incomingCallStage.show();
        });
    }

    private void showVoiceCallDialog(String peer) {
        Platform.runLater(() -> {
            if (voiceCallStage != null && voiceCallStage.isShowing()) {
                voiceCallStage.close();
            }

            voiceCallStage = new Stage();
            voiceCallStage.initModality(Modality.APPLICATION_MODAL);
            voiceCallStage.setTitle("Voice Call - " + peer);

            VBox callBox = new VBox(20);
            callBox.setPadding(new Insets(30));
            callBox.setAlignment(Pos.CENTER);
            callBox.setStyle("-fx-background-color: linear-gradient(to bottom, #0068FF, #0091FF);");

            Label callIcon = new Label("📞");
            callIcon.setStyle("-fx-font-size: 48;");

            Label peerLabel = new Label(peer);
            peerLabel.setStyle("-fx-text-fill: white; -fx-font-size: 24; -fx-font-weight: bold;");

            Label statusLabel = new Label("Đang kết nối...");
            statusLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.8); -fx-font-size: 16;");

            Label timerLabel = new Label("00:00");
            timerLabel.setStyle("-fx-text-fill: white; -fx-font-size: 18; -fx-font-weight: bold;");

            Button endCallBtn = new Button("Kết thúc");
            endCallBtn.setStyle("-fx-background-color: #FF3B30; -fx-text-fill: white; " +
                    "-fx-font-size: 16; -fx-font-weight: bold; -fx-padding: 12 40; " +
                    "-fx-background-radius: 25; -fx-cursor: hand;");

            // Timer for call duration
            final int[] seconds = {0};
            Timeline timer = new Timeline(new KeyFrame(Duration.seconds(1), ev -> {
                seconds[0]++;
                int minutes = seconds[0] / 60;
                int secs = seconds[0] % 60;
                timerLabel.setText(String.format("%02d:%02d", minutes, secs));
            }));
            timer.setCycleCount(Timeline.INDEFINITE);

            endCallBtn.setOnAction(e -> {
                timer.stop();
                voiceCallManager.endCall();
                voiceCallStage.close();
            });

            callBox.getChildren().addAll(callIcon, peerLabel, statusLabel, timerLabel, endCallBtn);

            Scene scene = new Scene(callBox, 300, 350);
            voiceCallStage.setScene(scene);

            // Update status when call is connected
            new Thread(() -> {
                try {
                    Thread.sleep(2000); // Simulate connection time
                    Platform.runLater(() -> {
                        statusLabel.setText("✅ Đã kết nối");
                        callIcon.setText("🎧");
                        timer.play(); // Start timer when connected
                    });
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
            }).start();

            voiceCallStage.show();
        });
    }

    // ============== NETWORKING (EVENT-DRIVEN) ==============

    private void startServer() {
        // Main TCP server cho chat
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(myTcpPort);
                System.out.println("✓ Chat server started on port: " + myTcpPort);

                while (!Thread.currentThread().isInterrupted()) {
                    Socket clientSocket = serverSocket.accept();
                    handleNewConnection(clientSocket);
                }
            } catch (SocketException e) {
                // Socket closed
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();

        // File transfer server
        new Thread(() -> {
            try {
                fileServer = new ServerSocket(myFilePort);
                System.out.println("✓ File server started on port: " + myFilePort);

                while (!Thread.currentThread().isInterrupted()) {
                    Socket clientSocket = fileServer.accept();
                    handleFileTransfer(clientSocket);
                }
            } catch (SocketException e) {
                // Socket closed
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();

        // Discovery server
        new Thread(() -> {
            try {
                discoveryServer = new ServerSocket(DISCOVERY_PORT);
                System.out.println("✓ Discovery server started on port: " + DISCOVERY_PORT);

                while (!Thread.currentThread().isInterrupted()) {
                    Socket socket = discoveryServer.accept();
                    handleDiscoveryConnection(socket);
                }
            } catch (SocketException e) {
                // Socket closed
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();

        // Voice call server
        voiceCallManager.startVoiceServer();

        // Video call server
        videoCallManager.startVideoServer();

        announcePresence();
    }

    private void announcePresence() {
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
                            socket.connect(new InetSocketAddress(ip, DISCOVERY_PORT), 100);

                            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                            writer.println("ANNOUNCE:" + currentUser + ":" + myTcpPort + ":" + myFilePort +
                                    ":" + voiceCallManager.getVoicePort() + ":" + videoCallManager.getVideoPort() +
                                    ":" + videoCallManager.getAudioPort());

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
                                    ":" + voiceCallManager.getVoicePort() + ":" + videoCallManager.getVideoPort() +
                                    ":" + videoCallManager.getAudioPort());
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

    private void addPeer(String username, String ip, int port, int filePort,
                         int voicePort, int videoPort, int videoAudioPort) {
        if (!discoveredPeers.containsKey(username)) {
            PeerInfo peer = new PeerInfo(ip, port, filePort, voicePort, videoPort, videoAudioPort);
            discoveredPeers.put(username, peer);

            Platform.runLater(() -> {
                refreshContactList();
                System.out.println("➕ Added peer: " + username);
            });
        }
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

    private void handlePeerMessage(String from, String message) {
        System.out.println("📩 From " + from + ": " + message);

        String[] parts = message.split(":", 3);
        if (parts.length < 2) return;

        String type = parts[0];

        if (type.equals("MESSAGE") && parts.length >= 3) {
            String content = parts[2];

            Platform.runLater(() -> {
                if (currentChatTarget != null && currentChatTarget.equals(from) && !isGroupChat) {
                    displayMessage(from, content, false);
                }
            });

            saveChatHistory(from, content, false);

        } else if (type.equals("FILE") && parts.length >= 3) {
            String fileInfo = parts[2];
            String[] fileData = fileInfo.split("\\|");
            if (fileData.length >= 4) {
                String fileName = fileData[0];
                long fileSize = Long.parseLong(fileData[1]);
                String senderIp = fileData[2];
                String uniqueFileName = fileData[3];

                PeerInfo senderPeer = discoveredPeers.get(from);
                if (senderPeer != null) {
                    downloadFileFromPeer(senderIp, senderPeer.filePort, uniqueFileName, fileName, () -> {
                        Platform.runLater(() -> {
                            if (currentChatTarget != null && currentChatTarget.equals(from) && !isGroupChat) {
                                displayFileMessage(from, fileName, fileSize, uniqueFileName, false);
                            }
                        });
                    });
                }

                saveChatHistory(from, "[FILE:" + fileName + "]", false);
            }

        } else if (type.equals("GROUP_FILE") && parts.length >= 3) {
            String groupName = parts[1];
            String fileInfo = parts[2];
            String[] fileData = fileInfo.split("\\|");
            if (fileData.length >= 5) {
                String sender = fileData[0];
                String fileName = fileData[1];
                long fileSize = Long.parseLong(fileData[2]);
                String senderIp = fileData[3];
                String uniqueFileName = fileData[4];

                PeerInfo senderPeer = discoveredPeers.get(sender);
                if (senderPeer != null) {
                    downloadFileFromPeer(senderIp, senderPeer.filePort, uniqueFileName, fileName, () -> {
                        Platform.runLater(() -> {
                            if (currentChatTarget != null && currentChatTarget.equals(groupName) && isGroupChat) {
                                boolean isSentByMe = sender.equals(currentUser);
                                displayFileMessage(sender, fileName, fileSize, uniqueFileName, isSentByMe);
                            }
                        });
                    });
                }

                saveChatHistory(groupName + "_group", sender + ": [FILE:" + fileName + "]", false);
            }

        } else if (type.equals("GROUP_MESSAGE") && parts.length >= 3) {
            String groupName = parts[1];
            String content = parts[2];

            Platform.runLater(() -> {
                if (currentChatTarget != null && currentChatTarget.equals(groupName) && isGroupChat) {
                    String[] messageParts = content.split(":", 2);
                    if (messageParts.length >= 2) {
                        String sender = messageParts[0].trim();
                        String actualMessage = messageParts[1].trim();
                        boolean isSentByMe = sender.equals(currentUser);
                        displayMessage(sender, actualMessage, isSentByMe);
                    }
                }
            });

            saveChatHistory(groupName + "_group", content, false);

        } else if (type.equals("GROUP_SYNC") && parts.length >= 3) {
            String groupName = parts[1];
            String groupInfo = parts[2];
            String[] infoParts = groupInfo.split(":");

            if (infoParts.length >= 2) {
                String creator = infoParts[0];
                String[] membersList = infoParts[1].split(",");

                ChatGroup group = chatGroups.get(groupName);
                if (group == null) {
                    group = new ChatGroup(groupName, creator);
                    chatGroups.put(groupName, group);
                }

                for (String member : membersList) {
                    group.addMember(member.trim());
                }

                saveGroup(group);

                Platform.runLater(() -> {
                    refreshContactList();
                });
            }

        } else if (type.equals("TYPING")) {
            Platform.runLater(() -> {
                if (currentChatTarget != null && currentChatTarget.equals(from)) {
                    chatTitleLabel.setText(from + " đang soạn tin...");

                    new Thread(() -> {
                        try {
                            Thread.sleep(3000);
                            Platform.runLater(() -> {
                                if (isGroupChat) {
                                    ChatGroup group = chatGroups.get(currentChatTarget);
                                    if (group != null) {
                                        chatTitleLabel.setText(currentChatTarget + " (" + group.members.size() + " thành viên)");
                                    }
                                } else {
                                    chatTitleLabel.setText(currentChatTarget);
                                }
                            });
                        } catch (InterruptedException e) {}
                    }).start();
                }
            });
        }
        // Handle call responses
        else if (type.equals("CALL_ACCEPTED")) {
            Platform.runLater(() -> {
                showAlert("Thông báo", from + " đã chấp nhận cuộc gọi");
            });
        }
        else if (type.equals("CALL_REJECTED")) {
            Platform.runLater(() -> {
                showAlert("Thông báo", from + " đã từ chối cuộc gọi");
                if (voiceCallStage != null) {
                    voiceCallStage.close();
                }
            });
        }
        else if (type.equals("VIDEO_CALL_ACCEPTED")) {
            Platform.runLater(() -> {
                System.out.println("Video call accepted by " + from);
            });
        }
        else if (type.equals("VIDEO_CALL_REJECTED")) {
            Platform.runLater(() -> {
                showAlert("Thông báo", from + " đã từ chối cuộc gọi video");
                if (videoCallStage != null) {
                    videoCallStage.close();
                }
            });
        }
    }

    // ============== VOICE & VIDEO CALL ==============

    private void startVoiceCall() {
        if (currentChatTarget == null || isGroupChat) {
            showAlert("Lỗi", "Voice call chỉ hỗ trợ chat 1-1!");
            return;
        }

        if (voiceCallManager.isCallActive()) {
            showAlert("Thông báo", "Đang trong cuộc gọi!");
            return;
        }

        PeerInfo peer = discoveredPeers.get(currentChatTarget);
        if (peer == null || peer.getVoicePort() == -1) {
            showAlert("Lỗi", "Không thể kết nối voice call!");
            return;
        }

        // Show calling dialog immediately
        showVoiceCallDialog(currentChatTarget);

        // Start call in background
        new Thread(() -> {
            boolean success = voiceCallManager.startCall(peer.getIp(), peer.getVoicePort());
            Platform.runLater(() -> {
                if (!success) {
                    showAlert("Lỗi", "Không thể kết nối voice call!");
                    if (voiceCallStage != null) {
                        voiceCallStage.close();
                    }
                }
            });
        }).start();
    }

    private void startVideoCall() {
        if (currentChatTarget == null || isGroupChat) {
            showAlert("Lỗi", "Video call chỉ hỗ trợ chat 1-1!");
            return;
        }

        if (videoCallManager.isVideoCallActive()) {
            showAlert("Thông báo", "Đang trong cuộc gọi video!");
            return;
        }

        PeerInfo peer = discoveredPeers.get(currentChatTarget);
        if (peer == null || peer.getVideoPort() == -1) {
            showAlert("Lỗi", "Không thể kết nối video call!");
            return;
        }

        // Show video call window
        videoCallStage = new Stage();
        videoCallStage.initModality(Modality.NONE);
        videoCallStage.setTitle("Video Call - " + currentChatTarget);

        BorderPane videoPane = new BorderPane();
        videoPane.setStyle("-fx-background-color: #1C1C1E;");

        // Remote video (main)
        ImageView remoteVideoView = new ImageView();
        remoteVideoView.setPreserveRatio(true);
        remoteVideoView.setFitWidth(800);
        remoteVideoView.setFitHeight(600);

        // Local video (small overlay)
        ImageView localVideoView = new ImageView();
        localVideoView.setPreserveRatio(true);
        localVideoView.setFitWidth(200);
        localVideoView.setFitHeight(150);
        localVideoView.setStyle("-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 10, 0, 0, 0);");

        StackPane localVideoPane = new StackPane(localVideoView);
        localVideoPane.setPadding(new Insets(20));
        localVideoPane.setAlignment(Pos.TOP_RIGHT);
        StackPane.setAlignment(localVideoView, Pos.TOP_RIGHT);

        StackPane videoStack = new StackPane(remoteVideoView, localVideoPane);
        videoPane.setCenter(videoStack);

        // Controls
        HBox controls = new HBox(20);
        controls.setPadding(new Insets(20));
        controls.setAlignment(Pos.CENTER);
        controls.setStyle("-fx-background-color: rgba(0,0,0,0.5);");

        Button endCallBtn = new Button("🔴 Kết thúc");
        endCallBtn.setStyle("-fx-background-color: #FF3B30; -fx-text-fill: white; " +
                "-fx-font-size: 16; -fx-font-weight: bold; -fx-padding: 12 30; " +
                "-fx-background-radius: 25; -fx-cursor: hand;");
        endCallBtn.setOnAction(e -> {
            videoCallManager.endVideoCall();
            videoCallStage.close();
        });

        controls.getChildren().add(endCallBtn);
        videoPane.setBottom(controls);

        Scene scene = new Scene(videoPane, 800, 600);
        videoCallStage.setScene(scene);
        videoCallStage.setOnCloseRequest(e -> videoCallManager.endVideoCall());

        // Start video call
        new Thread(() -> {
            boolean success = videoCallManager.startVideoCall(
                    peer.getIp(),
                    peer.getVideoPort(),
                    peer.getVideoAudioPort(),
                    localVideoView,
                    remoteVideoView
            );
            if (!success) {
                Platform.runLater(() -> {
                    showAlert("Lỗi", "Không thể kết nối video call!");
                    videoCallStage.close();
                });
            }
        }).start();

        videoCallStage.show();
    }

    // ============== FILE TRANSFER ==============

    private void selectAndSendFile() {
        if (currentChatTarget == null) {
            showAlert("Lỗi", "Vui lòng chọn một cuộc trò chuyện trước!");
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Chọn file để gửi");
        File selectedFile = fileChooser.showOpenDialog(primaryStage);

        if (selectedFile != null) {
            if (selectedFile.length() > 50 * 1024 * 1024) {
                showAlert("Lỗi", "File quá lớn! Giới hạn 50MB.");
                return;
            }

            new Thread(() -> {
                try {
                    String uniqueFileName = System.currentTimeMillis() + "_" + selectedFile.getName();
                    Path destPath = Paths.get(SHARED_FILES_DIR + uniqueFileName);
                    Files.copy(selectedFile.toPath(), destPath, StandardCopyOption.REPLACE_EXISTING);

                    long fileSize = selectedFile.length();
                    String fileName = selectedFile.getName();

                    Platform.runLater(() -> {
                        displayFileMessage(currentUser, fileName, fileSize, uniqueFileName, true);
                    });

                    if (isGroupChat) {
                        sendGroupFile(fileName, fileSize, uniqueFileName);
                    } else {
                        sendDirectFile(fileName, fileSize, uniqueFileName);
                    }

                    saveChatHistory(currentChatTarget + (isGroupChat ? "_group" : ""),
                            "[FILE:" + fileName + "]", true);

                    System.out.println("✓ File saved and sent: " + uniqueFileName);

                } catch (IOException e) {
                    e.printStackTrace();
                    Platform.runLater(() -> showAlert("Lỗi", "Không thể gửi file!"));
                }
            }).start();
        }
    }

    private void sendDirectFile(String fileName, long fileSize, String uniqueFileName) {
        try {
            InetAddress localhost = InetAddress.getLocalHost();
            String myIp = localhost.getHostAddress();

            PeerConnection connection = peerConnections.get(currentChatTarget);
            if (connection == null || !connection.isAlive()) {
                connection = connectToPeer(currentChatTarget);
            }

            if (connection != null) {
                String message = "FILE::" + fileName + "|" + fileSize + "|" + myIp + "|" + uniqueFileName;
                connection.send(message);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendGroupFile(String fileName, long fileSize, String uniqueFileName) {
        ChatGroup group = chatGroups.get(currentChatTarget);
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
                            String message = "GROUP_FILE:" + currentChatTarget + ":" +
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

    private void handleFileTransfer(Socket socket) {
        new Thread(() -> {
            try {
                DataInputStream dis = new DataInputStream(socket.getInputStream());
                DataOutputStream dos = new DataOutputStream(socket.getOutputStream());

                String command = dis.readUTF();

                if (command.equals("REQUEST_FILE")) {
                    String uniqueFileName = dis.readUTF();
                    File file = new File(SHARED_FILES_DIR + uniqueFileName);

                    if (file.exists()) {
                        dos.writeUTF("OK");
                        dos.writeLong(file.length());
                        dos.writeUTF(file.getName());

                        FileInputStream fis = new FileInputStream(file);
                        byte[] buffer = new byte[BUFFER_SIZE];
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

    private void downloadFileFromPeer(String senderIp, int senderFilePort, String uniqueFileName, String displayName, Runnable onComplete) {
        new Thread(() -> {
            try {
                File existingFile = new File(SHARED_FILES_DIR + uniqueFileName);
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

                    String savePath = SHARED_FILES_DIR + uniqueFileName;
                    FileOutputStream fos = new FileOutputStream(savePath);

                    byte[] buffer = new byte[BUFFER_SIZE];
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
                    Platform.runLater(() -> {
                        showAlert("Lỗi", "Không thể tải file: " + error);
                    });
                }
            } catch (IOException e) {
                System.err.println("✗ Failed to download file from " + senderIp + ":" + senderFilePort + " - " + e.getMessage());
                Platform.runLater(() -> {
                    showAlert("Lỗi", "Không thể kết nối để tải file!");
                });
            }
        }).start();
    }

    private void displayFileMessage(String sender, String fileName, long fileSize, String uniqueFileName, boolean isSent) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));

        HBox messageContainer = new HBox();
        messageContainer.setPadding(new Insets(5, 10, 5, 10));
        messageContainer.setAlignment(isSent ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);

        VBox messageBubble = new VBox(5);
        messageBubble.setMaxWidth(350);
        messageBubble.setPadding(new Insets(12, 15, 12, 15));
        messageBubble.setStyle("-fx-background-color: " +
                (isSent ? "#007AFF" : "#E5E5EA") + ";" +
                "-fx-background-radius: 18;" +
                "-fx-border-radius: 18;");

        if (isGroupChat && !isSent && !sender.equals(currentUser)) {
            Label senderLabel = new Label(sender);
            senderLabel.setStyle("-fx-font-size: 12; -fx-text-fill: " +
                    (isSent ? "rgba(255,255,255,0.8)" : "#666") + "; -fx-font-weight: bold;");
            messageBubble.getChildren().add(senderLabel);
        }

        HBox fileInfo = new HBox(10);
        fileInfo.setAlignment(Pos.CENTER_LEFT);

        Label fileIcon = new Label(getFileIcon(fileName));
        fileIcon.setStyle("-fx-font-size: 32;");

        VBox fileDetails = new VBox(3);
        Label fileNameLabel = new Label(fileName);
        fileNameLabel.setWrapText(true);
        fileNameLabel.setStyle("-fx-font-size: 14; -fx-font-weight: bold; -fx-text-fill: " +
                (isSent ? "white" : "black") + ";");

        Label fileSizeLabel = new Label(formatFileSize(fileSize));
        fileSizeLabel.setStyle("-fx-font-size: 12; -fx-text-fill: " +
                (isSent ? "rgba(255,255,255,0.7)" : "rgba(0,0,0,0.5)") + ";");

        fileDetails.getChildren().addAll(fileNameLabel, fileSizeLabel);
        fileInfo.getChildren().addAll(fileIcon, fileDetails);

        Button downloadBtn = new Button("⬇ Tải về");
        downloadBtn.setStyle("-fx-background-color: " + (isSent ? "rgba(255,255,255,0.2)" : "#0068FF") + ";" +
                "-fx-text-fill: white;" +
                "-fx-font-size: 12; -fx-font-weight: bold; -fx-padding: 6 12;" +
                "-fx-background-radius: 8; -fx-cursor: hand;");

        downloadBtn.setOnAction(e -> {
            File file = new File(SHARED_FILES_DIR + uniqueFileName);

            if (file.exists()) {
                FileChooser fileChooser = new FileChooser();
                fileChooser.setTitle("Lưu file");
                fileChooser.setInitialFileName(fileName);
                File saveFile = fileChooser.showSaveDialog(primaryStage);

                if (saveFile != null) {
                    try {
                        Files.copy(file.toPath(), saveFile.toPath(),
                                StandardCopyOption.REPLACE_EXISTING);
                        showAlert("Thành công", "Đã lưu file: " + fileName);
                    } catch (IOException ex) {
                        showAlert("Lỗi", "Không thể lưu file!");
                    }
                }
            } else {
                showAlert("Thông báo", "File đã được lưu trong thư mục shared_files/");
            }
        });

        Label timeLabel = new Label(timestamp);
        timeLabel.setStyle("-fx-font-size: 10; -fx-text-fill: " +
                (isSent ? "rgba(255,255,255,0.7)" : "rgba(0,0,0,0.5)") + ";");

        messageBubble.getChildren().addAll(fileInfo, downloadBtn, timeLabel);
        messageContainer.getChildren().add(messageBubble);

        chatMessageContainer.getChildren().add(messageContainer);

        chatMessageContainer.layout();
        chatScrollPane.layout();
        Platform.runLater(() -> {
            chatScrollPane.setVvalue(1.0);
        });
    }

    private String getFileIcon(String fileName) {
        String extension = "";
        int i = fileName.lastIndexOf('.');
        if (i > 0) {
            extension = fileName.substring(i + 1).toLowerCase();
        }

        switch (extension) {
            case "jpg":
            case "jpeg":
            case "png":
            case "gif":
            case "bmp":
                return "🖼️";
            case "pdf":
                return "📄";
            case "doc":
            case "docx":
                return "📝";
            case "xls":
            case "xlsx":
                return "📊";
            case "ppt":
            case "pptx":
                return "📽️";
            case "zip":
            case "rar":
            case "7z":
                return "🗜️";
            case "mp3":
            case "wav":
            case "flac":
                return "🎵";
            case "mp4":
            case "avi":
            case "mkv":
                return "🎬";
            case "txt":
                return "📃";
            default:
                return "📎";
        }
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
        return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
    }

    private void showAlert(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    // ============== PEER CONNECTION ==============

    private PeerConnection connectToPeer(String username) {
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

    // ============== CHAT FUNCTIONS ==============

    private void startChatWithUser(String username) {
        currentChatTarget = username;
        isGroupChat = false;
        chatMessageContainer.getChildren().clear();
        chatTitleLabel.setText(username);

        // Show call buttons for 1-1 chat
        voiceCallBtn.setVisible(true);
        videoCallBtn.setVisible(true);

        loadChatHistory(username);

        if (!peerConnections.containsKey(username)) {
            connectToPeer(username);
        }
    }

    private void startChatWithGroup(String groupName) {
        currentChatTarget = groupName;
        isGroupChat = true;
        chatMessageContainer.getChildren().clear();

        // Hide call buttons for group chat
        voiceCallBtn.setVisible(false);
        videoCallBtn.setVisible(false);

        ChatGroup group = chatGroups.get(groupName);
        if (group != null) {
            chatTitleLabel.setText(groupName + " (" + group.members.size() + " thành viên)");
        }

        loadChatHistory(groupName + "_group");
    }

    private void sendMessage() {
        String message = messageField.getText().trim();
        if (message.isEmpty() || currentChatTarget == null) return;

        messageField.clear();

        if (isGroupChat) {
            sendGroupMessage(message);
        } else {
            sendDirectMessage(message);
        }

        displayMessage(currentUser, message, true);
        saveChatHistory(currentChatTarget + (isGroupChat ? "_group" : ""), message, true);
    }

    private void sendDirectMessage(String message) {
        PeerConnection connection = peerConnections.get(currentChatTarget);
        if (connection == null || !connection.isAlive()) {
            connection = connectToPeer(currentChatTarget);
        }

        if (connection != null) {
            connection.send("MESSAGE:" + currentUser + ":" + message);
        }
    }

    private void sendGroupMessage(String message) {
        ChatGroup group = chatGroups.get(currentChatTarget);
        if (group != null) {
            String fullMessage = currentUser + ": " + message;
            for (String member : group.members) {
                if (!member.equals(currentUser)) {
                    PeerConnection connection = peerConnections.get(member);
                    if (connection == null || !connection.isAlive()) {
                        connection = connectToPeer(member);
                    }
                    if (connection != null) {
                        connection.send("GROUP_MESSAGE:" + currentChatTarget + ":" + fullMessage);
                    }
                }
            }
        }
    }

    private void displayMessage(String sender, String message, boolean isSent) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));

        HBox messageContainer = new HBox();
        messageContainer.setPadding(new Insets(5, 10, 5, 10));
        messageContainer.setAlignment(isSent ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);

        VBox messageBubble = new VBox(3);
        messageBubble.setMaxWidth(300);
        messageBubble.setPadding(new Insets(10, 15, 10, 15));
        messageBubble.setStyle("-fx-background-color: " +
                (isSent ? "#007AFF" : "#E5E5EA") + ";" +
                "-fx-background-radius: 18;" +
                "-fx-border-radius: 18;");

        if (isGroupChat && !isSent && !sender.equals(currentUser)) {
            Label senderLabel = new Label(sender);
            senderLabel.setStyle("-fx-font-size: 12; -fx-text-fill: #666; -fx-font-weight: bold;");
            messageBubble.getChildren().add(senderLabel);
        }

        Label messageLabel = new Label(message);
        messageLabel.setWrapText(true);
        messageLabel.setStyle("-fx-font-size: 14; -fx-text-fill: " +
                (isSent ? "white" : "black") + ";");

        Label timeLabel = new Label(timestamp);
        timeLabel.setStyle("-fx-font-size: 10; -fx-text-fill: " +
                (isSent ? "rgba(255,255,255,0.7)" : "rgba(0,0,0,0.5)") + ";");
        timeLabel.setAlignment(Pos.BOTTOM_RIGHT);

        messageBubble.getChildren().addAll(messageLabel, timeLabel);
        messageContainer.getChildren().add(messageBubble);

        chatMessageContainer.getChildren().add(messageContainer);

        chatMessageContainer.layout();
        chatScrollPane.layout();
        Platform.runLater(() -> {
            chatScrollPane.setVvalue(1.0);
        });
    }

    // ============== CONTACT LIST ==============

    private void refreshContactList() {
        Platform.runLater(() -> {
            contactListContainer.getChildren().clear();

            if (!discoveredPeers.isEmpty()) {
                Label usersHeader = new Label("TIN NHẮN");
                usersHeader.setStyle("-fx-padding: 15 15 5 15; -fx-font-size: 12; " +
                        "-fx-font-weight: bold; -fx-text-fill: #8E8E93;");
                contactListContainer.getChildren().add(usersHeader);

                for (String username : discoveredPeers.keySet()) {
                    if (!username.equals(currentUser)) {
                        contactListContainer.getChildren().add(createContactItem(username, false));
                    }
                }
            }

            if (!chatGroups.isEmpty()) {
                Label groupsHeader = new Label("NHÓM");
                groupsHeader.setStyle("-fx-padding: 15 15 5 15; -fx-font-size: 12; " +
                        "-fx-font-weight: bold; -fx-text-fill: #8E8E93;");
                contactListContainer.getChildren().add(groupsHeader);

                for (String groupName : chatGroups.keySet()) {
                    contactListContainer.getChildren().add(createContactItem(groupName, true));
                }
            }
        });
    }

    private HBox createContactItem(String name, boolean isGroup) {
        HBox item = new HBox(12);
        item.setPadding(new Insets(12, 15, 12, 15));
        item.setAlignment(Pos.CENTER_LEFT);
        item.setStyle("-fx-cursor: hand; -fx-background-color: white;");

        Label avatar = new Label(isGroup ? "👥" : name.substring(0, 1).toUpperCase());
        avatar.setStyle("-fx-background-color: " + (isGroup ? "#34C759" : "#0068FF") + "; " +
                "-fx-text-fill: white; -fx-font-size: 16; -fx-font-weight: bold; " +
                "-fx-min-width: 50; -fx-min-height: 50; " +
                "-fx-max-width: 50; -fx-max-height: 50; " +
                "-fx-background-radius: 50%; -fx-alignment: center;");

        VBox info = new VBox(3);
        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-font-size: 15; -fx-font-weight: bold; -fx-text-fill: #000;");

        Label statusLabel = new Label(isGroup ?
                chatGroups.get(name).members.size() + " thành viên" : "🟢 Online");
        statusLabel.setStyle("-fx-font-size: 13; -fx-text-fill: #8E8E93;");

        info.getChildren().addAll(nameLabel, statusLabel);
        HBox.setHgrow(info, Priority.ALWAYS);

        item.getChildren().addAll(avatar, info);

        item.setOnMouseEntered(e ->
                item.setStyle("-fx-background-color: #F2F2F7; -fx-cursor: hand;"));
        item.setOnMouseExited(e ->
                item.setStyle("-fx-background-color: white; -fx-cursor: hand;"));

        item.setOnMouseClicked(e -> {
            if (isGroup) {
                startChatWithGroup(name);
            } else {
                startChatWithUser(name);
            }
        });

        return item;
    }

    // ============== GROUP MANAGEMENT ==============

    private void loadUserGroups() {
        File groupsDir = new File(GROUPS_DIR);
        File[] groupFiles = groupsDir.listFiles((dir, name) ->
                name.startsWith(currentUser + "_group_") && name.endsWith(".txt"));

        if (groupFiles != null) {
            for (File file : groupFiles) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
                    String groupName = reader.readLine();
                    String creator = reader.readLine();
                    String membersLine = reader.readLine();

                    if (groupName != null && creator != null && membersLine != null) {
                        ChatGroup group = new ChatGroup(groupName, creator);
                        String[] members = membersLine.split(",");
                        for (String member : members) {
                            group.addMember(member.trim());
                        }
                        chatGroups.put(groupName, group);
                        System.out.println("✓ Loaded group: " + groupName);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void saveGroup(ChatGroup group) {
        for (String member : group.members) {
            String filename = GROUPS_DIR + member + "_group_" + group.name + ".txt";
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

    private void showCreateGroupDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Tạo nhóm mới");
        dialog.setHeaderText("Tạo nhóm trò chuyện");

        VBox content = new VBox(15);
        content.setPadding(new Insets(20));

        TextField groupNameField = new TextField();
        groupNameField.setPromptText("Tên nhóm");
        groupNameField.setStyle("-fx-font-size: 14; -fx-padding: 10;");

        Label membersLabel = new Label("Chọn thành viên:");
        membersLabel.setStyle("-fx-font-weight: bold;");

        VBox memberCheckboxes = new VBox(8);
        List<CheckBox> checkBoxes = new ArrayList<>();

        for (String username : discoveredPeers.keySet()) {
            if (!username.equals(currentUser)) {
                CheckBox cb = new CheckBox(username);
                cb.setStyle("-fx-font-size: 13;");
                checkBoxes.add(cb);
                memberCheckboxes.getChildren().add(cb);
            }
        }

        ScrollPane scrollPane = new ScrollPane(memberCheckboxes);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(150);

        content.getChildren().addAll(groupNameField, membersLabel, scrollPane);
        dialog.getDialogPane().setContent(content);

        ButtonType createButtonType = new ButtonType("Tạo nhóm", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createButtonType, ButtonType.CANCEL);

        dialog.showAndWait().ifPresent(response -> {
            if (response == createButtonType) {
                String groupName = groupNameField.getText().trim();
                if (!groupName.isEmpty()) {
                    ChatGroup group = new ChatGroup(groupName, currentUser);
                    group.addMember(currentUser);

                    for (CheckBox cb : checkBoxes) {
                        if (cb.isSelected()) {
                            group.addMember(cb.getText());
                        }
                    }

                    chatGroups.put(groupName, group);
                    refreshContactList();

                    saveGroup(group);
                    syncGroupToMembers(group);

                    System.out.println("✓ Created group: " + groupName + " with " + group.members.size() + " members");
                }
            }
        });
    }

    private void syncGroupToMembers(ChatGroup group) {
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

    // ============== CHAT HISTORY ==============

    private void saveChatHistory(String target, String message, boolean isSent) {
        String filename = CHAT_HISTORY_DIR + currentUser + "_" + target + ".txt";
        try (FileWriter writer = new FileWriter(filename, true)) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String sender = isSent ? currentUser : (isGroupChat ? message.split(":")[0] : target);
            String actualMessage = isSent ? message : (isGroupChat && message.contains(":") ?
                    message.substring(message.indexOf(":") + 1).trim() : message);
            writer.write(String.format("[%s] %s: %s\n", timestamp, sender, actualMessage));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadChatHistory(String target) {
        String filename = CHAT_HISTORY_DIR + currentUser + "_" + target + ".txt";
        File file = new File(filename);

        if (file.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.matches("\\[.*\\] .*: .*")) {
                        String timestamp = line.substring(1, line.indexOf("]"));
                        String rest = line.substring(line.indexOf("]") + 2);
                        String sender = rest.substring(0, rest.indexOf(":"));
                        String message = rest.substring(rest.indexOf(":") + 2);

                        boolean isSent = sender.equals(currentUser);

                        if (message.startsWith("[FILE:") && message.endsWith("]")) {
                            String fileName = message.substring(6, message.length() - 1);
                            File sharedDir = new File(SHARED_FILES_DIR);
                            File[] matchingFiles = sharedDir.listFiles((dir, name) ->
                                    name.endsWith("_" + fileName) || name.equals(fileName));

                            if (matchingFiles != null && matchingFiles.length > 0) {
                                File foundFile = matchingFiles[0];
                                displayFileMessage(sender, fileName, foundFile.length(),
                                        foundFile.getName(), isSent);
                            } else {
                                displayMessage(sender, message, isSent);
                            }
                        } else {
                            displayMessage(sender, message, isSent);
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // ============== CLEANUP ==============

    private void shutdown() {
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
            if (voiceCallManager != null) {
                voiceCallManager.shutdown();
            }
            if (videoCallManager != null) {
                videoCallManager.shutdown();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        Platform.exit();
        System.exit(0);
    }
}