import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
import javafx.util.Duration;

public class CallManager {
    private MainController mainController;
    private VoiceCallManager voiceCallManager;
    private VideoCallManager videoCallManager;

    private Stage incomingCallStage;
    private Stage voiceCallStage;
    private Stage videoCallStage;
    private String incomingCaller;
    private boolean isVideoCall;

    public CallManager(MainController mainController) {
        this.mainController = mainController;
    }

    public void initialize(String currentUser) {
        this.voiceCallManager = new VoiceCallManager(currentUser);
        this.videoCallManager = new VideoCallManager(currentUser);

        setupCallListeners();
        voiceCallManager.startVoiceServer();
        videoCallManager.startVideoServer();
    }

    private void setupCallListeners() {
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
                    if (videoCallStage != null) {
                        videoCallStage.close();
                    }
                });
            }
        });
    }

    public void startVoiceCall(String target) {
        if (target == null || mainController.getChatManager().isGroupChat()) {
            showAlert("Lỗi", "Voice call chỉ hỗ trợ chat 1-1!");
            return;
        }

        if (voiceCallManager.isCallActive()) {
            showAlert("Thông báo", "Đang trong cuộc gọi!");
            return;
        }

        PeerInfo peer = mainController.getNetworkManager().getDiscoveredPeers().get(target);
        if (peer == null || peer.getVoicePort() == -1) {
            showAlert("Lỗi", "Không thể kết nối voice call!");
            return;
        }

        // Show calling dialog immediately
        showVoiceCallDialog(target);

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

    public void startVideoCall(String target) {
        if (target == null || mainController.getChatManager().isGroupChat()) {
            showAlert("Lỗi", "Video call chỉ hỗ trợ chat 1-1!");
            return;
        }

        if (videoCallManager.isVideoCallActive()) {
            showAlert("Thông báo", "Đang trong cuộc gọi video!");
            return;
        }

        PeerInfo peer = mainController.getNetworkManager().getDiscoveredPeers().get(target);
        if (peer == null || peer.getVideoPort() == -1) {
            showAlert("Lỗi", "Không thể kết nối video call!");
            return;
        }

        // Show video call window
        videoCallStage = new Stage();
        videoCallStage.initModality(Modality.NONE);
        videoCallStage.setTitle("Video Call - " + target);

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
            incomingCallStage.initOwner(mainController.getPrimaryStage());
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

    public void handleCallAccepted(String from) {
        Platform.runLater(() -> {
            showAlert("Thông báo", from + " đã chấp nhận cuộc gọi");
        });
    }

    public void handleCallRejected(String from) {
        Platform.runLater(() -> {
            showAlert("Thông báo", from + " đã từ chối cuộc gọi");
            if (voiceCallStage != null) {
                voiceCallStage.close();
            }
        });
    }

    public void handleVideoCallAccepted(String from) {
        Platform.runLater(() -> {
            System.out.println("Video call accepted by " + from);
        });
    }

    public void handleVideoCallRejected(String from) {
        Platform.runLater(() -> {
            showAlert("Thông báo", from + " đã từ chối cuộc gọi video");
            if (videoCallStage != null) {
                videoCallStage.close();
            }
        });
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

    public void shutdown() {
        if (voiceCallManager != null) {
            voiceCallManager.shutdown();
        }
        if (videoCallManager != null) {
            videoCallManager.shutdown();
        }
    }

    // Getters
    public VoiceCallManager getVoiceCallManager() { return voiceCallManager; }
    public VideoCallManager getVideoCallManager() { return videoCallManager; }
}