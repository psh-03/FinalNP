import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** 서버 접속과 프로토콜 해석. 담당: 송준석 */
public final class ClientMain implements GameView.Actions {
    private final GameView view = new GameView(this);
    private volatile Socket socket;
    private volatile PrintWriter out;
    private volatile int session = 0;
    // 송신도 화면 Thread 밖에서 순서대로 처리한다.
    private final ExecutorService sender = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "client-sender");
        thread.setDaemon(true);
        return thread;
    });

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Font font = new Font("Dialog", Font.PLAIN, 14);
            for (String key : new String[] {"Label.font", "Button.font", "TextField.font",
                    "TextArea.font", "Table.font", "TableHeader.font"}) {
                UIManager.put(key, font);
            }
            new ClientMain().showWindow();
        });
    }

    private void showWindow() {
        JFrame frame = new JFrame("텍사스 홀덤 - 2주차");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setContentPane(view);
        frame.pack();
        frame.setMinimumSize(new Dimension(1040, 700));
        frame.setLocationByPlatform(true);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                disconnect();
                sender.shutdownNow();
                frame.dispose();
            }
        });
        frame.setVisible(true);
    }

    @Override
    public void connect(String host, int port, String nickname) {
        if (socket != null) { return; }
        int currentSession = ++session;
        Socket connection = new Socket();
        socket = connection;
        view.setConnecting();
        Thread receiver = new Thread(() -> receive(connection, currentSession, host, port, nickname),
                "client-receiver");
        receiver.setDaemon(true);
        receiver.start();
    }

    private void receive(Socket connection, int token, String host, int port, String nickname) {
        String endMessage = "서버와 연결이 종료되었습니다.";
        try (Socket current = connection) {
            current.connect(new InetSocketAddress(host, port), 3000);
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                    current.getOutputStream(), StandardCharsets.UTF_8), true);
            if (session != token) { return; }
            updateView(token, () -> out = writer);
            writer.println("/JOIN " + nickname);
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    current.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                String message = line;
                // Swing 컴포넌트 변경은 화면 이벤트 Thread에서만 한다.
                updateView(token, () -> handle(message));
            }
        } catch (IOException | IllegalArgumentException e) {
            endMessage = "연결을 확인하세요. 서버 실행 여부와 IP·포트를 확인해 주세요.";
        } finally {
            String message = endMessage;
            updateView(token, () -> {
                out = null;
                socket = null;
                view.setDisconnected(message);
                view.appendChat("[알림] " + message);
            });
        }
    }

    private void updateView(int token, Runnable action) {
        SwingUtilities.invokeLater(() -> {
            // 이전 연결에서 늦게 도착한 메시지가 새 접속 화면을 바꾸지 않게 한다.
            if (token == session) { action.run(); }
        });
    }

    private void handle(String line) {
        String[] parts = line.split(" ", 2);
        String value = parts.length == 2 ? parts[1] : "";
        switch (parts[0]) {
            case "/JOIN_OK" -> view.setConnected(Integer.parseInt(value));
            case "/CHAT" -> {
                String[] chat = value.split(" ", 2);
                view.appendChat(chat[0] + ": " + chat[1]);
            }
            case "/NOTICE" -> view.appendChat("[알림] " + value);
            case "/ERROR" -> view.showError(value);
            case "/TABLE" -> {
                String[] fields = value.split(" ");
                List<String[]> players = new ArrayList<>();
                int count = Integer.parseInt(fields[2]);
                for (int i = 0; i < count; i++) { players.add(fields[i + 3].split(",")); }
                view.setTable(Integer.parseInt(fields[0]), fields[1], players);
            }
            case "/HAND" -> {
                List<Card> hand = new ArrayList<>();
                if (!value.isEmpty()) {
                    for (String card : value.split(" ")) { hand.add(Card.fromWire(card)); }
                }
                view.setHand(hand);
            }
            default -> view.showError("알 수 없는 서버 메시지입니다.");
        }
    }

    private void send(String message) {
        int token = session;
        PrintWriter writer = out;
        if (writer == null) { return; }
        sender.execute(() -> {
            if (session != token) { return; }
            writer.println(message);
            if (writer.checkError()) {
                updateView(token, () -> {
                    disconnect();
                    view.showError("메시지를 보내지 못했습니다. 다시 접속해 주세요.");
                });
            }
        });
    }

    @Override public void sendChat(String text) { send("/CHAT " + text); }
    @Override public void startGame() { send("/START"); }

    @Override
    public void disconnect() {
        ++session;
        Socket previous = socket;
        socket = null;
        out = null;
        if (previous != null) {
            try { previous.close(); } catch (IOException ignored) { }
        }
        view.setDisconnected("접속이 종료되었습니다.");
    }
}
