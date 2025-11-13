import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import java.io.*;

public class AuthManager {
    private MainController mainController;

    public AuthManager(MainController mainController) {
        this.mainController = mainController;
    }

    public void showLoginScreen() {
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
				// Thử chiếm khóa phiên LAN theo cổng cố định của username
				boolean lockOk = SessionLockManager.getInstance().acquire(username);
				if (!lockOk) {
					messageLabel.setText("Tài khoản này đang đăng nhập trên máy khác trong LAN!");
					return;
				}
				mainController.loginSuccess(username);
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
        mainController.getPrimaryStage().setScene(scene);
        mainController.getPrimaryStage().show();
    }

    private boolean authenticate(String username, String password) {
        try (BufferedReader reader = new BufferedReader(new FileReader("users.txt"))) {
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
        try (BufferedReader reader = new BufferedReader(new FileReader("users.txt"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(username + ":")) {
                    return false;
                }
            }
        } catch (IOException e) {
            // File không tồn tại
        }

        try (FileWriter writer = new FileWriter("users.txt", true)) {
            writer.write(username + ":" + password + "\n");
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }
}