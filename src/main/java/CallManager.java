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

    private ImageView localVideoView;
    private ImageView remoteVideoView;

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
                // KIỂM TRA: Nếu video call đang active, từ chối voice call
                if (videoCallManager.isVideoCallActive()) {
                    System.out.println("⚠️ [CALL] Rejecting voice call - video call is active");
                    voiceCallManager.rejectCall();
                    return;
                }
                
                // Đợi một chút để đảm bảo video call đã cleanup xong
                new Thread(() -> {
                    try {
                        Thread.sleep(800); // Đợi 800ms
                        Platform.runLater(() -> showIncomingCallDialog(caller, false));
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }).start();
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
                    showVideoCallWindow(incomingCaller, false);

                    // Đợi UI sẵn sàng rồi mới set video views và kết nối audio
                    new Thread(() -> {
                        try {
                            Thread.sleep(500);
                            if (areVideoViewsReady()) {
                                videoCallManager.setVideoViews(localVideoView, remoteVideoView);

                                // QUAN TRỌNG: Kết nối audio socket cho người nghe
                                PeerInfo peer = mainController.getNetworkManager()
                                        .getDiscoveredPeers().get(incomingCaller);
                                if (peer != null) {
                                    videoCallManager.connectAudioSocket(peer.getIp(), peer.getVideoAudioPort());
                                }

                                System.out.println("✅ Video views set for receiver");
                            }
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }).start();
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
                    System.out.println("📞 Video call ended callback received - FORCE CLEANUP");
                    if (videoCallStage != null) {
                        videoCallStage.close();
                    }
                    // FORCE CLEANUP - QUAN TRỌNG
                    localVideoView = null;
                    remoteVideoView = null;
                    
                    // Đảm bảo audio devices được đóng hoàn toàn
                    if (videoCallManager != null) {
                        videoCallManager.endVideoCall();
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

        showVoiceCallDialog(target);

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

        // Hiển thị giao diện trước
        showVideoCallWindow(target, true);

        // Khởi động streaming sau khi UI được tạo
        new Thread(() -> {
            try {
                Thread.sleep(800);

                Platform.runLater(() -> {
                    debugVideoViews();

                    if (!areVideoViewsReady()) {
                        showAlert("Lỗi", "Video views chưa sẵn sàng!");
                        return;
                    }

                    boolean success = videoCallManager.startVideoCall(
                            peer.getIp(),
                            peer.getVideoPort(),
                            peer.getVideoAudioPort(),
                            localVideoView,
                            remoteVideoView
                    );

                    if (!success) {
                        showAlert("Lỗi", "Không thể kết nối video call!");
                        if (videoCallStage != null) {
                            videoCallStage.close();
                        }
                    } else {
                        System.out.println("✅ Video call connected to: " + target);
                    }
                });
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void showVideoCallWindow(String peer, boolean isCaller) {
        Platform.runLater(() -> {
            if (videoCallStage != null) {
                videoCallStage.close();
            }

            videoCallStage = new Stage();
            videoCallStage.initModality(Modality.NONE);
            videoCallStage.setTitle("Video Call - " + peer + (isCaller ? " (Đang gọi)" : " (Đang nhận)"));
            videoCallStage.setOnCloseRequest(e -> {
                videoCallManager.endVideoCall();
            });

            BorderPane videoPane = new BorderPane();
            videoPane.setStyle("-fx-background-color: #1C1C1E;");

            // Remote video (main)
            remoteVideoView = new ImageView();
            remoteVideoView.setPreserveRatio(true);
            remoteVideoView.setFitWidth(800);
            remoteVideoView.setFitHeight(600);
            remoteVideoView.setStyle("-fx-background-color: #2C2C2E;");

            // Local video (small overlay)
            localVideoView = new ImageView();
            localVideoView.setPreserveRatio(true);
            localVideoView.setFitWidth(200);
            localVideoView.setFitHeight(150);
            localVideoView.setStyle("-fx-background-color: #3C3C3E; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 10, 0, 0, 0);");

            StackPane localVideoPane = new StackPane(localVideoView);
            localVideoPane.setPadding(new Insets(20));
            localVideoPane.setAlignment(Pos.TOP_RIGHT);
            StackPane.setAlignment(localVideoView, Pos.TOP_RIGHT);

            StackPane videoStack = new StackPane(remoteVideoView, localVideoPane);
            videoPane.setCenter(videoStack);

            // Info panel
            VBox infoPanel = new VBox(10);
            infoPanel.setPadding(new Insets(15));
            infoPanel.setAlignment(Pos.CENTER);
            infoPanel.setStyle("-fx-background-color: rgba(0,0,0,0.7);");

            Label callInfo = new Label("Video call với: " + peer);
            callInfo.setStyle("-fx-text-fill: white; -fx-font-size: 16; -fx-font-weight: bold;");

            Label statusLabel = new Label(isCaller ? "Đang kết nối..." : "Đã kết nối");
            statusLabel.setStyle("-fx-text-fill: #34C759; -fx-font-size: 14;");

            infoPanel.getChildren().addAll(callInfo, statusLabel);
            videoPane.setTop(infoPanel);

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

            Button muteBtn = new Button("🎤");
            muteBtn.setStyle("-fx-background-color: #8E8E93; -fx-text-fill: white; " +
                    "-fx-font-size: 16; -fx-font-weight: bold; -fx-padding: 12 20; " +
                    "-fx-background-radius: 25; -fx-cursor: hand;");
            muteBtn.setOnAction(e -> {
                showAlert("Thông báo", "Tính năng tắt microphone đang phát triển");
            });

            Button cameraBtn = new Button("📷");
            cameraBtn.setStyle("-fx-background-color: #8E8E93; -fx-text-fill: white; " +
                    "-fx-font-size: 16; -fx-font-weight: bold; -fx-padding: 12 20; " +
                    "-fx-background-radius: 25; -fx-cursor: hand;");
            cameraBtn.setOnAction(e -> {
                showAlert("Thông báo", "Tính năng tắt camera đang phát triển");
            });

            controls.getChildren().addAll(muteBtn, cameraBtn, endCallBtn);
            videoPane.setBottom(controls);

            Scene scene = new Scene(videoPane, 800, 700);
            videoCallStage.setScene(scene);

            if (isCaller) {
                new Thread(() -> {
                    try {
                        Thread.sleep(2000);
                        Platform.runLater(() -> {
                            statusLabel.setText("✅ Đã kết nối");
                        });
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }).start();
            }

            videoCallStage.show();

            System.out.println("📹 Video call window opened for: " + peer + " (isCaller: " + isCaller + ")");
        });
    }

    private void showIncomingCallDialog(String caller, boolean isVideoCall) {
        Platform.runLater(() -> {
            this.incomingCaller = caller;
            this.isVideoCall = isVideoCall;

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

            Label callIcon = new Label(isVideoCall ? "📹" : "📞");
            callIcon.setStyle("-fx-font-size: 48;");

            Label callerLabel = new Label(isVideoCall ? "Video call từ" : "Voice call từ");
            callerLabel.setStyle("-fx-text-fill: white; -fx-font-size: 16;");

            Label callerName = new Label(caller);
            callerName.setStyle("-fx-text-fill: white; -fx-font-size: 24; -fx-font-weight: bold;");

            Label statusLabel = new Label("Đang gọi...");
            statusLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.8); -fx-font-size: 14;");

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

            acceptBtn.setOnAction(e -> {
                if (isVideoCall) {
                    videoCallManager.acceptVideoCall();
                } else {
                    voiceCallManager.acceptCall();
                    showVoiceCallDialog(caller);
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

            System.out.println("📞 Incoming call dialog shown for: " + caller + " (video: " + isVideoCall + ")");
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
            voiceCallStage.setOnCloseRequest(e -> {
                voiceCallManager.endCall();
            });

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

            new Thread(() -> {
                try {
                    Thread.sleep(2000);
                    Platform.runLater(() -> {
                        statusLabel.setText("✅ Đã kết nối");
                        callIcon.setText("🎧");
                        timer.play();
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
            System.out.println("✅ Video call accepted by " + from);
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

        if (incomingCallStage != null) {
            incomingCallStage.close();
        }
        if (voiceCallStage != null) {
            voiceCallStage.close();
        }
        if (videoCallStage != null) {
            videoCallStage.close();
        }
    }

    public VoiceCallManager getVoiceCallManager() {
        return voiceCallManager;
    }

    public VideoCallManager getVideoCallManager() {
        return videoCallManager;
    }

    public boolean areVideoViewsReady() {
        return localVideoView != null && remoteVideoView != null;
    }

    public void debugVideoViews() {
        System.out.println("🎯 Video Views Debug:");
        System.out.println("  - Local Video View: " + (localVideoView != null ? "✓ Ready" : "✗ Null"));
        System.out.println("  - Remote Video View: " + (remoteVideoView != null ? "✓ Ready" : "✗ Null"));

        if (localVideoView != null) {
            System.out.println("  - Local View Size: " + localVideoView.getFitWidth() + "x" + localVideoView.getFitHeight());
        }
        if (remoteVideoView != null) {
            System.out.println("  - Remote View Size: " + remoteVideoView.getFitWidth() + "x" + remoteVideoView.getFitHeight());
        }
    }
}