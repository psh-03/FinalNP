package poker.client;

import poker.common.Wire;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * [클라이언트 / 네트워크 담당] ClientMain의 화면과 소켓 입출력을 분리한다.
 * 서버와 연결하고, 송신 큐·수신 스레드·송신 스레드·주기적인 연결 확인을 관리한다.
 * 네트워크는 기다리는 시간이 있으므로 Swing 화면을 처리하는 스레드에서 수행하지 않는다.
 */
final class PokerConnection {
    // 콜백 인터페이스다. 데이터를 받거나 연결이 끊기면 화면 쪽에 알려준다.
    // ClientMain이 이 인터페이스를 구현한다. message()의 Map은 해석을 마친 메시지다.
    // Listener 호출은 I/O 스레드에서도 발생하므로 화면 변경은 ClientMain이 EDT에 다시 맡긴다.
    interface Listener {
        void message(PokerConnection source, Map<String, String> message);
        void closed(PokerConnection source, String reason);
    }
    private final Socket socket = new Socket();
    private final Listener listener;
    // 여러 스레드가 동시에 종료를 알리더라도 close() 본문은 한 번만 실행하기 위한 플래그다.
    private final AtomicBoolean closed = new AtomicBoolean();
    // 버튼 이벤트는 여기에 메시지를 넣는다. 실제 Socket 쓰기는 별도 writer 스레드가 담당한다.
    private final BlockingQueue<String> outgoing = new ArrayBlockingQueue<String>(64);
    // 일정 간격으로 PING을 보내는 전용 스케줄러다. 입력이 없어도 연결이 살아 있음을 확인한다.
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "heartbeat"); t.setDaemon(true); return t;
    });
    // 생성자에서 실제 연결을 시도한다. ClientMain은 이 생성자를 별도 connect-worker에서 호출한다.
    PokerConnection(String host, int port, Listener listener) throws IOException {
        this.listener = listener;
        try {
            // host와 port가 서버의 주소다. 연결 시도를 최대 5000ms(5초) 기다린다.
            socket.connect(new InetSocketAddress(host, port), 5000);
            // 연결 이후 읽기 대기는 45초 제한이다. heartbeat에 대한 PONG도 수신 데이터에 해당한다.
            socket.setTcpNoDelay(true); socket.setSoTimeout(45000);
        } catch (IOException | RuntimeException e) {
            try { socket.close(); } catch (IOException ignored) { }
            heartbeat.shutdownNow(); throw e;
        }
    }
    // 연결 성공 후 I/O 스레드를 시작하고, JOIN으로 이름을 등록한 뒤 주기적인 PING을 시작한다.
    void start(String name) {
        // this::readLoop는 메서드 참조다. 새 스레드가 실행할 작업으로 readLoop를 전달한다.
        Thread reader = new Thread(this::readLoop, "server-reader");
        Thread writer = new Thread(this::writeLoop, "server-writer");
        reader.setDaemon(true); writer.setDaemon(true); writer.start(); reader.start();
        send(Wire.message("JOIN", "name", name));
        // 최초 10초 뒤부터 10초마다 PING. 화면에서 버튼을 누르지 않아도 연결 확인 메시지가 오간다.
        heartbeat.scheduleAtFixedRate(() -> send(Wire.message("PING")), 10, 10, TimeUnit.SECONDS);
    }
    // 화면에서 호출해도 소켓 전송을 기다리지 않도록 offer()로 큐에만 넣는다.
    void send(String frame) {
        if (!closed.get() && !outgoing.offer(frame)) close("전송 대기열이 가득 찼습니다.");
    }
    // 서버가 보낸 줄을 받는 반복문이다. UTF-8로 읽어 Wire.parse()로 해석한 뒤 Listener에 전달한다.
    private void readLoop() {
        try (Reader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; !closed.get() && (line = Wire.readLine(in)) != null;)
                // this를 함께 넘겨 재접속 이후 옛 연결에서 온 늦은 이벤트를 ClientMain이 구별하게 한다.
                listener.message(this, Wire.parse(line));
            close("서버 연결이 종료되었습니다.");
        } catch (IOException | IllegalArgumentException e) { close("연결 종료: " + e.getMessage()); }
    }
    // 서버로 보낼 문자열을 큐에서 꺼내 전송한다. 출력을 담당하는 스레드는 이 연결에 하나다.
    private void writeLoop() {
        try (Writer out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            while (!closed.get()) {
                String frame = outgoing.poll(1, TimeUnit.SECONDS);
                // 문자열 + 줄바꿈을 쓰고 flush()한다. 서버의 Wire.readLine()은 이 줄바꿈을 만나야 메시지 하나를 완성한다.
                if (frame != null) { out.write(frame); out.write('\n'); out.flush(); }
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            close("전송 연결이 종료되었습니다.");
        }
    }
    // 연결 종료를 한 번만 처리하고 heartbeat도 중단한다. 이유 문자열은 화면 로그에 표시된다.
    void close(String reason) {
        if (!closed.compareAndSet(false, true)) return;
        heartbeat.shutdownNow();
        try { socket.close(); } catch (IOException ignored) { }
        // 이 통지는 호출한 스레드에서 실행된다. 실제 화면 초기화는 ClientMain.closed()가 EDT에서 수행한다.
        listener.closed(this, reason);
    }
}
