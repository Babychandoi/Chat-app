import javafx.application.Application;
import javafx.stage.Stage;
import java.io.File;

public class P2PChatApp extends Application {

    private MainController mainController;

    public static void main(String[] args) {
        System.setProperty("file.encoding", "UTF-8");
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        // Tạo thư mục cần thiết
        new File("chat_history/").mkdirs();
        new File("groups/").mkdirs();
        new File("shared_files/").mkdirs();

        mainController = new MainController(primaryStage);
        mainController.showLoginScreen();
    }
}