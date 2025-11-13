import javafx.application.Platform;
import javafx.stage.Stage;

public class MainController {
    private Stage primaryStage;
    private AuthManager authManager;
    private ChatManager chatManager;
    private NetworkManager networkManager;
    private CallManager callManager;

    private String currentUser;
    private int myTcpPort;
    private int myFilePort;

    public MainController(Stage primaryStage) {
        this.primaryStage = primaryStage;
        this.authManager = new AuthManager(this);
        this.chatManager = new ChatManager(this);
        this.networkManager = new NetworkManager(this);
        this.callManager = new CallManager(this);
    }

    public void showLoginScreen() {
        authManager.showLoginScreen();
    }

    public void loginSuccess(String username) {
        this.currentUser = username;
        this.myTcpPort = 8888 + Math.abs(username.hashCode() % 1000);
        this.myFilePort = 8890 + Math.abs(username.hashCode() % 1000);

        networkManager.initialize(currentUser, myTcpPort, myFilePort);
        callManager.initialize(currentUser);
        chatManager.loadUserGroups();
        networkManager.startServer();
        chatManager.showChatScreen();
    }

    // Getter methods
    public Stage getPrimaryStage() { return primaryStage; }
    public String getCurrentUser() { return currentUser; }
    public int getMyTcpPort() { return myTcpPort; }
    public int getMyFilePort() { return myFilePort; }
    public AuthManager getAuthManager() { return authManager; }
    public ChatManager getChatManager() { return chatManager; }
    public NetworkManager getNetworkManager() { return networkManager; }
    public CallManager getCallManager() { return callManager; }

    public void shutdown() {
        // Xóa session khi đăng xuất
        if (currentUser != null) {
			// Nhả khóa phiên LAN
			SessionLockManager.getInstance().release();
        }
        networkManager.shutdown();
        callManager.shutdown();
        Platform.exit();
        System.exit(0);
    }
}