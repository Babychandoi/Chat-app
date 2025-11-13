import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class ChatManager {
    private MainController mainController;

    // UI Components
    private VBox contactListContainer;
    private VBox chatMessageContainer;
    private ScrollPane chatScrollPane;
    private TextField messageField;
    private Label chatTitleLabel;
    private String currentChatTarget;
    private boolean isGroupChat = false;
    private Button voiceCallBtn;
    private Button videoCallBtn;
    private Button addMemberBtn;
    private Button leaveGroupBtn;
    private Label typingIndicatorLabel;
    private javafx.animation.Timeline typingTimeout;
    private java.util.Set<String> typingUsers = new java.util.HashSet<>();
    private java.util.Map<String, javafx.animation.Timeline> typingTimeouts = new java.util.HashMap<>();

    public ChatManager(MainController mainController) {
        this.mainController = mainController;
    }

    public void showChatScreen() {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: white;");

        // Left sidebar
        VBox leftSidebar = createLeftSidebar();

        // Center chat area
        VBox centerPanel = createCenterPanel();

        root.setLeft(leftSidebar);
        root.setCenter(centerPanel);

        Scene scene = new Scene(root, 1100, 700);
        mainController.getPrimaryStage().setScene(scene);
        mainController.getPrimaryStage().setOnCloseRequest(e -> mainController.shutdown());

        refreshContactList();
    }

    private VBox createLeftSidebar() {
        VBox leftSidebar = new VBox();
        leftSidebar.setPrefWidth(320);
        leftSidebar.setStyle("-fx-background-color: white; -fx-border-color: #E5E5EA; " +
                "-fx-border-width: 0 1 0 0;");

        // User header
        HBox userHeader = createUserHeader();

        // Search box
        HBox searchBox = createSearchBox();

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
        return leftSidebar;
    }

    private HBox createUserHeader() {
        HBox userHeader = new HBox(15);
        userHeader.setPadding(new Insets(15));
        userHeader.setAlignment(Pos.CENTER_LEFT);
        userHeader.setStyle("-fx-background-color: #0068FF;");

        Label userAvatar = new Label(mainController.getCurrentUser().substring(0, 1).toUpperCase());
        userAvatar.setStyle("-fx-background-color: white; -fx-text-fill: #0068FF; " +
                "-fx-font-size: 18; -fx-font-weight: bold; " +
                "-fx-min-width: 45; -fx-min-height: 45; " +
                "-fx-max-width: 45; -fx-max-height: 45; " +
                "-fx-background-radius: 50%; -fx-alignment: center;");

        VBox userInfo = new VBox(2);
        Label usernameLabel = new Label(mainController.getCurrentUser());
        usernameLabel.setStyle("-fx-text-fill: white; -fx-font-size: 16; -fx-font-weight: bold;");
        
        // Status với online indicator
        HBox statusBox = new HBox(5);
        statusBox.setAlignment(Pos.CENTER_LEFT);
        Label onlineIndicator = new Label("●");
        onlineIndicator.setStyle("-fx-text-fill: #4CD964; -fx-font-size: 12;");
        Label statusText = new Label("Đang hoạt động");
        statusText.setStyle("-fx-text-fill: rgba(255,255,255,0.8); -fx-font-size: 12;");
        statusBox.getChildren().addAll(onlineIndicator, statusText);
        
        userInfo.getChildren().addAll(usernameLabel, statusBox);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button addGroupBtn = new Button("➕");
        addGroupBtn.setStyle("-fx-background-color: rgba(255,255,255,0.2); " +
                "-fx-text-fill: white; -fx-font-size: 18; " +
                "-fx-min-width: 35; -fx-min-height: 35; " +
                "-fx-background-radius: 50%; -fx-cursor: hand;");
        addGroupBtn.setOnAction(e -> showCreateGroupDialog());

        userHeader.getChildren().addAll(userAvatar, userInfo, spacer, addGroupBtn);
        return userHeader;
    }

    private HBox createSearchBox() {
        HBox searchBox = new HBox(10);
        searchBox.setPadding(new Insets(15));
        TextField searchField = new TextField();
        searchField.setPromptText("🔍 Tìm kiếm");
        searchField.setStyle("-fx-background-color: #F2F2F7; -fx-background-radius: 20; " +
                "-fx-padding: 10 15; -fx-font-size: 14;");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchBox.getChildren().add(searchField);
        return searchBox;
    }

    private VBox createCenterPanel() {
        VBox centerPanel = new VBox();
        centerPanel.setStyle("-fx-background-color: #F0F0F0;");

        HBox chatHeader = createChatHeader();

        chatMessageContainer = new VBox();
        chatMessageContainer.setPadding(new Insets(10));
        chatMessageContainer.setSpacing(8);
        chatMessageContainer.setStyle("-fx-background-color: #F0F0F0;");

        chatScrollPane = new ScrollPane(chatMessageContainer);
        chatScrollPane.setFitToWidth(true);
        chatScrollPane.setStyle("-fx-background-color: #F0F0F0; -fx-background: #F0F0F0;");
        chatScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(chatScrollPane, Priority.ALWAYS);

        // Typing indicator
        typingIndicatorLabel = new Label();
        typingIndicatorLabel.setStyle("-fx-font-size: 12; -fx-text-fill: #8E8E93; -fx-font-style: italic;");
        typingIndicatorLabel.setPadding(new Insets(5, 15, 5, 15));
        typingIndicatorLabel.setVisible(false);

        HBox messageInputBox = createMessageInputBox();

        centerPanel.getChildren().addAll(chatHeader, chatScrollPane, typingIndicatorLabel, messageInputBox);
        return centerPanel;
    }

    private HBox createChatHeader() {
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
        voiceCallBtn.setOnAction(e -> mainController.getCallManager().startVoiceCall(currentChatTarget));
        voiceCallBtn.setVisible(false);

        videoCallBtn = new Button("📹");
        videoCallBtn.setStyle("-fx-background-color: #007AFF; -fx-text-fill: white; " +
                "-fx-font-size: 16; -fx-font-weight: bold; " +
                "-fx-min-width: 40; -fx-min-height: 40; " +
                "-fx-background-radius: 50%; -fx-cursor: hand;");
        videoCallBtn.setOnAction(e -> mainController.getCallManager().startVideoCall(currentChatTarget));
        videoCallBtn.setVisible(false);

        // Nút thêm thành viên (chỉ hiện khi chat nhóm)
        addMemberBtn = new Button("➕");
        addMemberBtn.setStyle("-fx-background-color: #34C759; -fx-text-fill: white; " +
                "-fx-font-size: 16; -fx-font-weight: bold; " +
                "-fx-min-width: 40; -fx-min-height: 40; " +
                "-fx-background-radius: 50%; -fx-cursor: hand;");
        addMemberBtn.setOnAction(e -> showAddMemberDialog());
        addMemberBtn.setVisible(false);

        // Nút rời nhóm (chỉ hiện khi chat nhóm)
        leaveGroupBtn = new Button("🚪");
        leaveGroupBtn.setStyle("-fx-background-color: #FF3B30; -fx-text-fill: white; " +
                "-fx-font-size: 16; -fx-font-weight: bold; " +
                "-fx-min-width: 40; -fx-min-height: 40; " +
                "-fx-background-radius: 50%; -fx-cursor: hand;");
        leaveGroupBtn.setOnAction(e -> leaveGroup());
        leaveGroupBtn.setVisible(false);

        chatHeader.getChildren().addAll(chatTitleLabel, headerSpacer, addMemberBtn, leaveGroupBtn, voiceCallBtn, videoCallBtn);
        return chatHeader;
    }

    private HBox createMessageInputBox() {
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

        // Typing detection
        messageField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (currentChatTarget != null && !newValue.trim().isEmpty()) {
                handleTyping();
            } else if (newValue.trim().isEmpty()) {
                stopTyping();
            }
        });

        Button sendButton = new Button("➤");
        sendButton.setStyle("-fx-background-color: #0068FF; -fx-text-fill: white; " +
                "-fx-font-size: 18; -fx-font-weight: bold; " +
                "-fx-min-width: 45; -fx-min-height: 45; " +
                "-fx-background-radius: 50%; -fx-cursor: hand;");
        sendButton.setOnAction(e -> sendMessage());

        messageField.setOnAction(e -> sendMessage());

        messageInputBox.getChildren().addAll(attachButton, messageField, sendButton);
        return messageInputBox;
    }

    public void loadUserGroups() {
        File groupsDir = new File("groups/");
        File[] groupFiles = groupsDir.listFiles((dir, name) ->
                name.startsWith(mainController.getCurrentUser() + "_group_") && name.endsWith(".txt"));

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
                        mainController.getNetworkManager().getChatGroups().put(groupName, group);
                        System.out.println("✓ Loaded group: " + groupName);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void refreshContactList() {
        Platform.runLater(() -> {
            contactListContainer.getChildren().clear();

            if (!mainController.getNetworkManager().getDiscoveredPeers().isEmpty()) {
                Label usersHeader = new Label("TIN NHẮN");
                usersHeader.setStyle("-fx-padding: 15 15 5 15; -fx-font-size: 12; " +
                        "-fx-font-weight: bold; -fx-text-fill: #8E8E93;");
                contactListContainer.getChildren().add(usersHeader);

                for (String username : mainController.getNetworkManager().getDiscoveredPeers().keySet()) {
                    if (!username.equals(mainController.getCurrentUser())) {
                        contactListContainer.getChildren().add(createContactItem(username, false));
                    }
                }
            }

            if (!mainController.getNetworkManager().getChatGroups().isEmpty()) {
                Label groupsHeader = new Label("NHÓM");
                groupsHeader.setStyle("-fx-padding: 15 15 5 15; -fx-font-size: 12; " +
                        "-fx-font-weight: bold; -fx-text-fill: #8E8E93;");
                contactListContainer.getChildren().add(groupsHeader);

                for (String groupName : mainController.getNetworkManager().getChatGroups().keySet()) {
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

        // Status với online indicator cho user hoặc số thành viên cho group
        if (isGroup) {
            Label statusLabel = new Label(mainController.getNetworkManager().getChatGroups().get(name).members.size() + " thành viên");
            statusLabel.setStyle("-fx-font-size: 13; -fx-text-fill: #8E8E93;");
            info.getChildren().addAll(nameLabel, statusLabel);
        } else {
            HBox statusBox = new HBox(5);
            statusBox.setAlignment(Pos.CENTER_LEFT);
            Label onlineIndicator = new Label("●");
            onlineIndicator.setStyle("-fx-text-fill: #4CD964; -fx-font-size: 10;");
            Label statusText = new Label("Online");
            statusText.setStyle("-fx-font-size: 13; -fx-text-fill: #8E8E93;");
            statusBox.getChildren().addAll(onlineIndicator, statusText);
            info.getChildren().addAll(nameLabel, statusBox);
        }
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

    public void startChatWithUser(String username) {
        currentChatTarget = username;
        isGroupChat = false;
        chatMessageContainer.getChildren().clear();
        chatTitleLabel.setText(username);

        voiceCallBtn.setVisible(true);
        videoCallBtn.setVisible(true);
        addMemberBtn.setVisible(false);
        leaveGroupBtn.setVisible(false);

        // Clear typing indicators khi chuyển chat
        clearTypingIndicators();

        loadChatHistory(username);
        mainController.getNetworkManager().ensureConnection(username);
    }

    public void startChatWithGroup(String groupName) {
        currentChatTarget = groupName;
        isGroupChat = true;
        chatMessageContainer.getChildren().clear();

        voiceCallBtn.setVisible(false);
        videoCallBtn.setVisible(false);
        addMemberBtn.setVisible(true);
        leaveGroupBtn.setVisible(true);

        // Clear typing indicators khi chuyển chat
        clearTypingIndicators();

        ChatGroup group = mainController.getNetworkManager().getChatGroups().get(groupName);
        if (group != null) {
            chatTitleLabel.setText(groupName + " (" + group.members.size() + " thành viên)");
        }

        loadChatHistory(groupName + "_group");
    }

    private void sendMessage() {
        String message = messageField.getText().trim();
        if (message.isEmpty() || currentChatTarget == null) return;

        // Gửi stop typing signal
        stopTyping();

        messageField.clear();

        if (isGroupChat) {
            mainController.getNetworkManager().sendGroupMessage(currentChatTarget, message);
        } else {
            mainController.getNetworkManager().sendDirectMessage(currentChatTarget, message);
        }

        displayMessage(mainController.getCurrentUser(), message, true);
        saveChatHistory(currentChatTarget + (isGroupChat ? "_group" : ""), message, true);
    }

    public void displayMessage(String sender, String message, boolean isSent) {
        Platform.runLater(() -> {
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

            if (isGroupChat && !isSent && !sender.equals(mainController.getCurrentUser())) {
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

            // Auto-scroll xuống tin nhắn mới nhất với delay nhỏ để UI render xong
            Platform.runLater(() -> {
                PauseTransition pause = new PauseTransition(Duration.millis(50));
                pause.setOnFinished(e -> {
                    chatScrollPane.setVvalue(1.0);
                    // Đảm bảo scroll đến cuối cùng
                    chatScrollPane.layout();
                });
                pause.play();
            });
        });
    }

    public void displayFileMessage(String sender, String fileName, long fileSize, String uniqueFileName, boolean isSent) {
        Platform.runLater(() -> {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));

            HBox messageContainer = new HBox();
            messageContainer.setPadding(new Insets(5, 10, 5, 10));
            messageContainer.setAlignment(isSent ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);

            VBox messageBubble = new VBox(5);
            messageBubble.setMaxWidth(isImageFile(fileName) ? 400 : 350); // Rộng hơn cho ảnh
            messageBubble.setPadding(new Insets(12, 15, 12, 15));
            messageBubble.setStyle("-fx-background-color: " +
                    (isSent ? "#007AFF" : "#E5E5EA") + ";" +
                    "-fx-background-radius: 18;" +
                    "-fx-border-radius: 18;");

            if (isGroupChat && !isSent && !sender.equals(mainController.getCurrentUser())) {
                Label senderLabel = new Label(sender);
                senderLabel.setStyle("-fx-font-size: 12; -fx-text-fill: " +
                        (isSent ? "rgba(255,255,255,0.8)" : "#666") + "; -fx-font-weight: bold;");
                messageBubble.getChildren().add(senderLabel);
            }

            // KIỂM TRA: Nếu là ảnh, hiển thị preview
            File file = new File("shared_files/" + uniqueFileName);
            if (isImageFile(fileName) && file.exists()) {
                try {
                    // Hiển thị ảnh preview
                    Image image = new Image(file.toURI().toString());
                    ImageView imageView = new ImageView(image);
                    
                    // Giới hạn kích thước ảnh
                    imageView.setPreserveRatio(true);
                    imageView.setFitWidth(350);
                    imageView.setFitHeight(350);
                    
                    // Bo góc cho ảnh
                    imageView.setStyle("-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 5, 0, 0, 1);");
                    
                    // Click để xem full size
                    imageView.setOnMouseClicked(e -> {
                        if (e.getClickCount() == 2) { // Double click
                            openImageInViewer(file);
                        }
                    });
                    imageView.setStyle("-fx-cursor: hand;");
                    
                    messageBubble.getChildren().add(imageView);
                    
                    // Thêm tên file, size và nút download
                    HBox fileInfoSmall = new HBox(8);
                    fileInfoSmall.setAlignment(Pos.CENTER_LEFT);
                    
                    VBox fileTextInfo = new VBox(2);
                    Label fileNameLabel = new Label(fileName);
                    fileNameLabel.setWrapText(true);
                    fileNameLabel.setStyle("-fx-font-size: 12; -fx-text-fill: " +
                            (isSent ? "rgba(255,255,255,0.8)" : "#666") + ";");
                    
                    Label fileSizeLabel = new Label(formatFileSize(fileSize));
                    fileSizeLabel.setStyle("-fx-font-size: 10; -fx-text-fill: " +
                            (isSent ? "rgba(255,255,255,0.6)" : "rgba(0,0,0,0.5)") + ";");
                    
                    fileTextInfo.getChildren().addAll(fileNameLabel, fileSizeLabel);
                    
                    Region spacer = new Region();
                    HBox.setHgrow(spacer, Priority.ALWAYS);
                    
                    // Nút download nhỏ cho ảnh
                    Button downloadImgBtn = new Button("⬇");
                    downloadImgBtn.setStyle("-fx-background-color: " + (isSent ? "rgba(255,255,255,0.2)" : "#0068FF") + ";" +
                            "-fx-text-fill: white;" +
                            "-fx-font-size: 14; -fx-font-weight: bold; -fx-padding: 4 8;" +
                            "-fx-background-radius: 50%; -fx-cursor: hand; -fx-min-width: 28; -fx-min-height: 28;");
                    downloadImgBtn.setOnAction(e -> downloadFile(file, fileName));
                    
                    fileInfoSmall.getChildren().addAll(fileTextInfo, spacer, downloadImgBtn);
                    messageBubble.getChildren().add(fileInfoSmall);
                    
                } catch (Exception e) {
                    System.err.println("❌ Error loading image: " + e.getMessage());
                    // Fallback: hiển thị như file thông thường
                    addFileInfoUI(messageBubble, fileName, fileSize, uniqueFileName, isSent, file);
                }
            } else {
                // File thông thường (không phải ảnh hoặc ảnh không tồn tại)
                addFileInfoUI(messageBubble, fileName, fileSize, uniqueFileName, isSent, file);
            }

            Label timeLabel = new Label(timestamp);
            timeLabel.setStyle("-fx-font-size: 10; -fx-text-fill: " +
                    (isSent ? "rgba(255,255,255,0.7)" : "rgba(0,0,0,0.5)") + ";");
            messageBubble.getChildren().add(timeLabel);

            messageContainer.getChildren().add(messageBubble);
            chatMessageContainer.getChildren().add(messageContainer);
            
            // Auto-scroll xuống tin nhắn mới nhất
            Platform.runLater(() -> {
                PauseTransition pause = new PauseTransition(Duration.millis(50));
                pause.setOnFinished(e -> {
                    chatScrollPane.setVvalue(1.0);
                    chatScrollPane.layout();
                });
                pause.play();
            });
        });
    }

    private void selectAndSendFile() {
        if (currentChatTarget == null) {
            showAlert("Lỗi", "Vui lòng chọn một cuộc trò chuyện trước!");
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Chọn file để gửi");
        File selectedFile = fileChooser.showOpenDialog(mainController.getPrimaryStage());

        if (selectedFile != null) {
            mainController.getNetworkManager().sendFile(currentChatTarget, selectedFile, isGroupChat);
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

        for (String username : mainController.getNetworkManager().getDiscoveredPeers().keySet()) {
            if (!username.equals(mainController.getCurrentUser())) {
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
                    ChatGroup group = new ChatGroup(groupName, mainController.getCurrentUser());
                    group.addMember(mainController.getCurrentUser());

                    for (CheckBox cb : checkBoxes) {
                        if (cb.isSelected()) {
                            group.addMember(cb.getText());
                        }
                    }

                    mainController.getNetworkManager().getChatGroups().put(groupName, group);
                    refreshContactList();
                    mainController.getNetworkManager().saveGroup(group);
                    mainController.getNetworkManager().syncGroupToMembers(group);

                    System.out.println("✓ Created group: " + groupName + " with " + group.members.size() + " members");
                }
            }
        });
    }

    private void showAddMemberDialog() {
        if (currentChatTarget == null || !isGroupChat) {
            showAlert("Lỗi", "Vui lòng chọn một nhóm!");
            return;
        }

        ChatGroup group = mainController.getNetworkManager().getChatGroups().get(currentChatTarget);
        if (group == null) {
            showAlert("Lỗi", "Không tìm thấy nhóm!");
            return;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Thêm thành viên");
        dialog.setHeaderText("Thêm thành viên vào nhóm: " + currentChatTarget);

        VBox content = new VBox(15);
        content.setPadding(new Insets(20));

        Label membersLabel = new Label("Chọn thành viên để thêm:");
        membersLabel.setStyle("-fx-font-weight: bold;");

        VBox memberCheckboxes = new VBox(8);
        List<CheckBox> checkBoxes = new ArrayList<>();

        // Chỉ hiển thị các peer chưa có trong nhóm
        for (String username : mainController.getNetworkManager().getDiscoveredPeers().keySet()) {
            if (!username.equals(mainController.getCurrentUser()) && !group.isMember(username)) {
                CheckBox cb = new CheckBox(username);
                cb.setStyle("-fx-font-size: 13;");
                checkBoxes.add(cb);
                memberCheckboxes.getChildren().add(cb);
            }
        }

        if (checkBoxes.isEmpty()) {
            Label noMembersLabel = new Label("Không có thành viên nào để thêm");
            noMembersLabel.setStyle("-fx-text-fill: #8E8E93; -fx-font-size: 13;");
            memberCheckboxes.getChildren().add(noMembersLabel);
        }

        ScrollPane scrollPane = new ScrollPane(memberCheckboxes);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(200);

        content.getChildren().addAll(membersLabel, scrollPane);
        dialog.getDialogPane().setContent(content);

        ButtonType addButtonType = new ButtonType("Thêm", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(addButtonType, ButtonType.CANCEL);

        dialog.showAndWait().ifPresent(response -> {
            if (response == addButtonType) {
                List<String> newMembers = new ArrayList<>();
                for (CheckBox cb : checkBoxes) {
                    if (cb.isSelected()) {
                        newMembers.add(cb.getText());
                    }
                }

                if (!newMembers.isEmpty()) {
                    mainController.getNetworkManager().addMembersToGroup(currentChatTarget, newMembers);
                    showAlert("Thành công", "Đã thêm " + newMembers.size() + " thành viên vào nhóm!");
                } else {
                    showAlert("Thông báo", "Chưa chọn thành viên nào!");
                }
            }
        });
    }

    private void leaveGroup() {
        if (currentChatTarget == null || !isGroupChat) {
            showAlert("Lỗi", "Vui lòng chọn một nhóm!");
            return;
        }

        ChatGroup group = mainController.getNetworkManager().getChatGroups().get(currentChatTarget);
        if (group == null) {
            showAlert("Lỗi", "Không tìm thấy nhóm!");
            return;
        }

        // Xác nhận rời nhóm
        Alert confirmDialog = new Alert(Alert.AlertType.CONFIRMATION);
        confirmDialog.setTitle("Xác nhận");
        confirmDialog.setHeaderText("Rời nhóm");
        confirmDialog.setContentText("Bạn có chắc chắn muốn rời nhóm \"" + currentChatTarget + "\"?");

        confirmDialog.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                mainController.getNetworkManager().removeMemberFromGroup(currentChatTarget, mainController.getCurrentUser());
                
                // Chuyển về màn hình chọn chat
                currentChatTarget = null;
                isGroupChat = false;
                chatMessageContainer.getChildren().clear();
                chatTitleLabel.setText("Chọn một cuộc trò chuyện");
                addMemberBtn.setVisible(false);
                leaveGroupBtn.setVisible(false);
                
                refreshContactList();
                showAlert("Thông báo", "Đã rời nhóm!");
            }
        });
    }

    private void loadChatHistory(String target) {
        String filename = "chat_history/" + mainController.getCurrentUser() + "_" + target + ".txt";
        File file = new File(filename);

        if (file.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
					if (line.matches("\\[.*\\] .*: .*")) {
                        String rest = line.substring(line.indexOf("]") + 2);
                        String sender = rest.substring(0, rest.indexOf(":"));
                        String message = rest.substring(rest.indexOf(":") + 2);

                        boolean isSent = sender.equals(mainController.getCurrentUser());

                        if (message.startsWith("[FILE:") && message.endsWith("]")) {
                            String fileName = message.substring(6, message.length() - 1);
                            File sharedDir = new File("shared_files/");
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

    private void saveChatHistory(String target, String message, boolean isSent) {
        String filename = "chat_history/" + mainController.getCurrentUser() + "_" + target + ".txt";
        try (FileWriter writer = new FileWriter(filename, true)) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String sender = isSent ? mainController.getCurrentUser() : (isGroupChat ? message.split(":")[0] : currentChatTarget);
            String actualMessage = isSent ? message : (isGroupChat && message.contains(":") ?
                    message.substring(message.indexOf(":") + 1).trim() : message);
            writer.write(String.format("[%s] %s: %s\n", timestamp, sender, actualMessage));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String getFileIcon(String fileName) {
        String extension = "";
        int i = fileName.lastIndexOf('.');
        if (i > 0) {
            extension = fileName.substring(i + 1).toLowerCase();
        }

        switch (extension) {
            case "jpg": case "jpeg": case "png": case "gif": case "bmp": return "🖼️";
            case "pdf": return "📄";
            case "doc": case "docx": return "📝";
            case "xls": case "xlsx": return "📊";
            case "ppt": case "pptx": return "📽️";
            case "zip": case "rar": case "7z": return "🗜️";
            case "mp3": case "wav": case "flac": return "🎵";
            case "mp4": case "avi": case "mkv": return "🎬";
            case "txt": return "📃";
            default: return "📎";
        }
    }
    
    private boolean isImageFile(String fileName) {
        String extension = "";
        int i = fileName.lastIndexOf('.');
        if (i > 0) {
            extension = fileName.substring(i + 1).toLowerCase();
        }
        return extension.equals("jpg") || extension.equals("jpeg") || 
               extension.equals("png") || extension.equals("gif") || 
               extension.equals("bmp") || extension.equals("webp");
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
        return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
    }

    public void showAlert(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    public void updateGroupTitle(String groupName, int memberCount) {
        if (chatTitleLabel != null) {
            chatTitleLabel.setText(groupName + " (" + memberCount + " thành viên)");
        }
    }

    private void handleTyping() {
        if (currentChatTarget == null) return;

        // Hủy timeout cũ nếu có
        if (typingTimeout != null) {
            typingTimeout.stop();
        }

        // Gửi typing signal
        if (isGroupChat) {
            mainController.getNetworkManager().sendGroupTyping(currentChatTarget);
        } else {
            mainController.getNetworkManager().sendTyping(currentChatTarget);
        }

        // Tạo timeout mới - sau 3 giây không gõ sẽ gửi stop typing
        typingTimeout = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(javafx.util.Duration.seconds(3), e -> stopTyping())
        );
        typingTimeout.play();
    }

    private void stopTyping() {
        if (currentChatTarget == null) return;

        // Hủy timeout
        if (typingTimeout != null) {
            typingTimeout.stop();
            typingTimeout = null;
        }

        // Gửi stop typing signal
        if (isGroupChat) {
            mainController.getNetworkManager().sendGroupStopTyping(currentChatTarget);
        } else {
            mainController.getNetworkManager().sendStopTyping(currentChatTarget);
        }
    }

    public void showTypingIndicator(String username) {
        Platform.runLater(() -> {
            // Hủy timeout cũ nếu có
            if (typingTimeouts.containsKey(username)) {
                typingTimeouts.get(username).stop();
                typingTimeouts.remove(username);
            }

            if (typingUsers.add(username)) {
                updateTypingIndicator();
            }

            // Tạo timeout để tự động ẩn sau 5 giây nếu không nhận stop typing
            javafx.animation.Timeline timeout = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.seconds(5), e -> {
                    if (typingUsers.remove(username)) {
                        updateTypingIndicator();
                    }
                    typingTimeouts.remove(username);
                })
            );
            timeout.play();
            typingTimeouts.put(username, timeout);
        });
    }

    public void hideTypingIndicator(String username) {
        Platform.runLater(() -> {
            // Hủy timeout
            if (typingTimeouts.containsKey(username)) {
                typingTimeouts.get(username).stop();
                typingTimeouts.remove(username);
            }

            if (typingUsers.remove(username)) {
                updateTypingIndicator();
            }
        });
    }

    private void updateTypingIndicator() {
        Platform.runLater(() -> {
            if (typingUsers.isEmpty()) {
                typingIndicatorLabel.setVisible(false);
                typingIndicatorLabel.setText("");
                System.out.println("🔴 Typing indicator hidden");
            } else {
                typingIndicatorLabel.setVisible(true);
                if (typingUsers.size() == 1) {
                    String username = typingUsers.iterator().next();
                    typingIndicatorLabel.setText(username + " đang gõ...");
                    System.out.println("🟢 Typing indicator shown: " + username + " đang gõ...");
                } else if (typingUsers.size() == 2) {
                    String[] users = typingUsers.toArray(new String[0]);
                    typingIndicatorLabel.setText(users[0] + " và " + users[1] + " đang gõ...");
                    System.out.println("🟢 Typing indicator shown: " + users[0] + " và " + users[1] + " đang gõ...");
                } else {
                    typingIndicatorLabel.setText(typingUsers.size() + " người đang gõ...");
                    System.out.println("🟢 Typing indicator shown: " + typingUsers.size() + " người đang gõ...");
                }
            }
        });
    }

    public void clearTypingIndicators() {
        Platform.runLater(() -> {
            // Hủy tất cả timeouts
            for (javafx.animation.Timeline timeout : typingTimeouts.values()) {
                timeout.stop();
            }
            typingTimeouts.clear();
            typingUsers.clear();
            typingIndicatorLabel.setVisible(false);
            typingIndicatorLabel.setText("");
        });
    }
    
    private void openImageInViewer(File imageFile) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(imageFile);
            }
        } catch (IOException e) {
            showAlert("Lỗi", "Không thể mở ảnh!");
        }
    }
    
    private void downloadFile(File file, String fileName) {
        if (file.exists()) {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Lưu file");
            fileChooser.setInitialFileName(fileName);
            File saveFile = fileChooser.showSaveDialog(mainController.getPrimaryStage());

            if (saveFile != null) {
                try {
                    Files.copy(file.toPath(), saveFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    showAlert("Thành công", "Đã lưu file: " + fileName);
                } catch (IOException ex) {
                    showAlert("Lỗi", "Không thể lưu file!");
                }
            }
        } else {
            showAlert("Thông báo", "File đã được lưu trong thư mục shared_files/");
        }
    }
    
    private void addFileInfoUI(VBox messageBubble, String fileName, long fileSize, String uniqueFileName, boolean isSent, File file) {
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

        downloadBtn.setOnAction(e -> downloadFile(file, fileName));

        messageBubble.getChildren().addAll(fileInfo, downloadBtn);
    }

    // Getters
    public VBox getContactListContainer() { return contactListContainer; }
    public String getCurrentChatTarget() { return currentChatTarget; }
    public boolean isGroupChat() { return isGroupChat; }
}