import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;

public class PeerConnection {
    Socket socket;
    String peerName;
    BufferedReader reader;
    PrintWriter writer;

    public PeerConnection(Socket socket, String peerName,
                          BufferedReader reader, PrintWriter writer) {
        this.socket = socket;
        this.peerName = peerName;
        this.reader = reader;
        this.writer = writer;
    }

    public void send(String message) {
        if (writer != null) {
            writer.println(message);
        }
    }

    public boolean isAlive() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    public void close() {
        try {
            if (socket != null) socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}