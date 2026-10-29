package poker.server;

import poker.common.Wire;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * [서버 / 한 명의 연결 담당] 클라이언트 한 명당 이 객체 하나가 만들어진다.
 * reader: 클라이언트 요청을 받는다. writer: 보낼 큐에서 꺼내 소켓으로 전송한다.
 * GameTable.Peer 구현: 게임 코드는 send()만 호출하면 되며 Socket 세부사항을 알 필요가 없다.
 * 여러 세션이 같은 table과 loop를 공유하지만, table 변경은 loop 스레드에만 맡긴다.
 */
final class ClientSession implements GameTable.Peer {
    // final 필드는 생성 후 다른 객체로 갈아 끼우지 않는다. 소켓 객체 자체의 상태가 고정된다는 뜻은 아니다.
    private final Socket socket;
    private final GameTable table;
    private final ScheduledExecutorService loop;
    private final Semaphore capacity;
    // [심화] 송신할 문장을 넣는 스레드 안전 큐다. 최대 128개까지 대기한다.
    // 게임 스레드의 send()는 큐에 넣고 바로 돌아오며, 실제 느릴 수 있는 소켓 쓰기는 writer가 맡는다.
    private final BlockingQueue<String> outgoing = new ArrayBlockingQueue<String>(128);
    // 수신/송신 스레드가 모두 close()할 수 있으므로 원자적 플래그로 종료 처리를 한 번만 실행한다.
    private final AtomicBoolean closed = new AtomicBoolean();
    // 생성자는 이 연결에서 사용할 소켓과 서버가 공유하는 객체들의 참조를 저장한다.
    ClientSession(Socket socket, GameTable table, ScheduledExecutorService loop, Semaphore capacity) {
        this.socket = socket; this.table = table; this.loop = loop; this.capacity = capacity;
    }
    // [수업 핵심] new Thread(...)는 스레드 객체 생성, start()는 별도 실행 흐름 시작이다.
    // this::writeLoop는 () -> this.writeLoop()와 같은 역할을 하는 메서드 참조다.
    // writeLoop()를 여기서 직접 호출하면 현재 스레드가 그 반복문에서 기다리게 된다.
    void start() {
        Thread writer = new Thread(this::writeLoop, "client-writer-" + socket.getPort());
        Thread reader = new Thread(this::readLoop, "client-reader-" + socket.getPort());
        writer.setDaemon(true); reader.setDaemon(true); writer.start(); reader.start();
    }
    // offer()는 큐가 가득 찼을 때 기다리지 않고 false를 반환한다. 계속 못 받아가는 연결은 종료한다.
    public void send(String frame) {
        if (!closed.get() && !outgoing.offer(frame)) close();
    }
    // [요청 수신] 바이트 입력 스트림 → UTF-8 문자 변환 → 문자 버퍼 순서로 감싼다.
    // 서버 기준 getInputStream()은 클라이언트가 보낸 데이터를 읽는 입구다.
    // try 블록을 끝내면 Reader와 연결된 입력 자원도 닫힌다.
    private void readLoop() {
        try (Reader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            // TcpNoDelay는 작은 메시지를 모아두는 Nagle 알고리즘을 비활성화한다.
            // SoTimeout 45000은 소켓 읽기 대기 제한(45초)이다. 게임의 행동 제한 시간과는 별개다.
            socket.setTcpNoDelay(true); socket.setSoTimeout(45000);
            // 대략 1초 구간에 40개를 넘는 요청을 보내면 연결을 끊는다. 게임 처리 큐에 과도한 요청이 쌓이는 것을 줄인다.
            long window = System.nanoTime(); int messages = 0;
            // 한 줄씩 복원하여 계속 읽는다. 정상 EOF이면 null, 읽기 오류이면 IOException으로 반복을 끝낸다.
            for (String line; !closed.get() && (line = Wire.readLine(in)) != null;) {
                if (System.nanoTime() - window > 1000000000L) { window = System.nanoTime(); messages = 0; }
                if (++messages > 40) throw new IOException("too many messages");
                try {
                    Map<String, String> command = Wire.parse(line);
                    // [핵심 연결 지점] 현재는 소켓 수신 스레드다. table.handle을 여기서 즉시 실행하지 않는다.
                    // 람다식 안의 일을 단일 게임 스레드의 대기열에 등록하여 다른 사람 요청과 순서대로 처리한다.
                    // 여기서 this는 현재 ClientSession이므로 서버가 누가 보낸 요청인지 구분할 수 있다.
                    loop.execute(() -> { if (!closed.get()) table.handle(this, command); });
                } catch (IllegalArgumentException invalid) {
                    send(Wire.message("ERROR", "text", "잘못된 메시지 형식입니다."));
                }
            }
        } catch (IOException expected) {
            if (!closed.get()) System.out.println("[NETWORK] " + expected.getMessage());
        } finally { close(); }
    }
    // [상태 송신] 문자열 → UTF-8 바이트 → 해당 클라이언트 소켓으로 보낸다.
    // 이 스레드만 이 연결의 Writer를 사용하므로 서로 다른 메시지의 글자가 섞이지 않는다.
    private void writeLoop() {
        try (Writer out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            while (!closed.get()) {
                // 보낼 것이 없으면 최대 1초 기다린다. null이면 다시 종료 플래그를 확인하고 다음 반복을 한다.
                String frame = outgoing.poll(1, TimeUnit.SECONDS);
                // [수업 핵심] write(frame)로 내용, write('줄바꿈')으로 메시지 끝을 보낸다.
                // BufferedWriter의 버퍼에 내용이 남아 있을 수 있어 flush()로 바로 내보낸다.
                // flush() 자체가 메시지 구분자는 아니다. 이 프로젝트의 구분자는 줄바꿈이다.
                if (frame != null) { out.write(frame); out.write('\n'); out.flush(); }
            }
        } catch (IOException | InterruptedException expected) {
            if (expected instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally { close(); }
    }
    // 어느 쪽 I/O가 실패해도 연결을 정리한다.
    // compareAndSet(false, true)는 아직 안 닫혔을 때만 true로 바꾸고 성공을 반환한다.
    // 이를 통해 소켓 닫기·허가증 반환·퇴장 통지를 중복 실행하지 않는다.
    private void close() {
        if (!closed.compareAndSet(false, true)) return;
        // 소켓을 닫으면 다른 스레드가 기다리던 read/write도 오류나 종료로 빠져나올 수 있다.
        try { socket.close(); } catch (IOException ignored) { }
        capacity.release();
        // 퇴장도 게임 상태를 바꾸므로 반드시 게임 스레드에 맡긴다.
        loop.execute(() -> table.disconnected(this));
    }
}
