import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** 접속과 메시지 송수신. 담당: 송준석 */
public class ServerMain {
    private final GameTable table = new GameTable();
    private final Map<Integer, ClientHandler> clients = new LinkedHashMap<>();

    public static void main(String[] args) {
        Console console = System.console();
        System.setOut(new PrintStream(System.out, true,
                console == null ? StandardCharsets.UTF_8 : console.charset()));
        try {
            int port = args.length == 0 ? 5555 : Integer.parseInt(args[0]);
            new ServerMain().run(port);
        } catch (IOException | IllegalArgumentException e) {
            System.out.println("서버를 시작할 수 없습니다: " + e.getMessage());
        }
    }

    private void run(int port) throws IOException {
        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("3주차 서버: 포트 " + port + "에서 접속 대기 중");
            while (true) {
                Socket socket = server.accept();
                // 한 명의 입력을 기다리는 동안 다른 접속도 받을 수 있게 분리한다.
                new Thread(new ClientHandler(socket), "client-handler").start();
            }
        }
    }

    private void broadcast(String message) {
        for (ClientHandler client : clients.values()) {
            client.send(message);
        }
    }

    // 공개 상태는 모두에게, 개인 카드는 해당 소켓에만 보낸다.
    private void sendTableState() {
        String state = table.snapshot().toWire();
        for (ClientHandler client : clients.values()) { sendStateTo(client, state); }
        for (String notice : table.drainNotices()) { broadcast("/NOTICE " + notice); }
    }

    private void sendStateTo(ClientHandler client, String state) {
        client.send(state);
        StringBuilder hand = new StringBuilder("/HAND");
        for (Card card : table.getHand(client.player.id)) { hand.append(' ').append(card.toWire()); }
        client.send(hand.toString());
    }

    private class ClientHandler implements Runnable {
        private final Socket socket;
        private PrintWriter out;
        private GameTable.Player player;

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        void send(String message) {
            out.println(message); // autoFlush=true이므로 줄바꿈 뒤 자동으로 전송한다.
            if (out.checkError()) {
                try { socket.close(); } catch (IOException ignored) { }
            }
        }

        @Override
        public void run() {
            try (Socket connection = socket;
                 BufferedReader in = new BufferedReader(new InputStreamReader(
                         connection.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                         connection.getOutputStream(), StandardCharsets.UTF_8), true)) {
                out = writer;
                connection.setSoTimeout(10000); // 접속 후 닉네임 요청은 10초 안에 보낸다.
                String first = in.readLine();
                if (first == null || !first.startsWith("/JOIN ")) {
                    send("/ERROR 먼저 /JOIN 닉네임을 보내야 합니다.");
                    return;
                }
                synchronized (table) {
                    try {
                        player = table.addPlayer(first.substring(6));
                    } catch (IllegalArgumentException e) {
                        send("/ERROR " + e.getMessage());
                        return;
                    }
                    clients.put(player.id, this);
                    send("/JOIN_OK " + player.id);
                    sendTableState();
                    System.out.println("참가: " + player.nickname + " (" + clients.size() + "명)");
                }
                connection.setSoTimeout(0);
                String line;
                while ((line = in.readLine()) != null) {
                    // 참가자 목록과 카드가 동시에 바뀌지 않도록 같은 잠금 안에서 처리한다.
                    synchronized (table) {
                        handle(line);
                    }
                }
            } catch (SocketTimeoutException e) {
                // 등록되지 않은 접속의 시간 초과. 참가자 목록에는 들어가지 않는다.
            } catch (IOException e) {
                // 연결이 끊기면 finally에서 참가자를 정리한다.
            } finally {
                synchronized (table) {
                    if (player != null) {
                        clients.remove(player.id);
                        table.removePlayer(player.id);
                        sendTableState();
                        System.out.println("퇴장: " + player.nickname + " (" + clients.size() + "명)");
                    }
                }
            }
        }

        private void handle(String line) {
            if (line.startsWith("/CHAT ")) {
                String message = line.substring(6).trim();
                if (message.isEmpty() || message.length() > 200) {
                    send("/ERROR 채팅은 1~200자로 입력하세요.");
                    return;
                }
                // 송신자가 적은 ID 대신 이 연결에 등록된 닉네임을 사용한다.
                broadcast("/CHAT " + player.nickname + " " + message);
            } else {
                try {
                    String[] fields = line.split(" ");
                    if (fields.length < 2) { throw new IllegalArgumentException("명령 형식을 확인하세요."); }
                    long receivedRevision = Long.parseLong(fields[1]);
                    table.requireRevision(receivedRevision);
                    if (fields[0].equals("/START") && fields.length == 2) {
                        table.startGame(player.id);
                    } else if (fields[0].equals("/ACTION") && fields.length >= 3 && fields.length <= 4) {
                        String action = fields[2];
                        boolean needsAmount = action.equals("BET") || action.equals("RAISE");
                        if (fields.length != (needsAmount ? 4 : 3)) {
                            throw new IllegalArgumentException("베트와 레이즈에만 이번 단계 총액을 붙이세요.");
                        }
                        int amount = needsAmount ? Integer.parseInt(fields[3]) : 0;
                        // 요청에 플레이어 ID를 넣지 않는다. 이 소켓의 실제 참가자를 사용한다.
                        table.act(player.id, action, amount);
                    } else { throw new IllegalArgumentException("알 수 없는 명령입니다."); }
                    sendTableState();
                } catch (IllegalArgumentException | IllegalStateException e) {
                    send("/ERROR " + e.getMessage());
                    // 오래된 요청을 거절한 뒤 최신 상태도 돌려주어 재입력할 수 있게 한다.
                    sendStateTo(this, table.snapshot().toWire());
                }
            }
        }
    }
}
