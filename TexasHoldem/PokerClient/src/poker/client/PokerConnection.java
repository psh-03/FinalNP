package poker.client;

import poker.common.Wire;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Network IO stays off Swing's event dispatch thread. */
final class PokerConnection {
    interface Listener {
        void message(PokerConnection source, Map<String, String> message);
        void closed(PokerConnection source, String reason);
    }
    private final Socket socket = new Socket();
    private final Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final BlockingQueue<String> outgoing = new ArrayBlockingQueue<String>(64);
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "heartbeat"); t.setDaemon(true); return t;
    });
    PokerConnection(String host, int port, Listener listener) throws IOException {
        this.listener = listener;
        try {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setTcpNoDelay(true); socket.setSoTimeout(45000);
        } catch (IOException | RuntimeException e) {
            try { socket.close(); } catch (IOException ignored) { }
            heartbeat.shutdownNow(); throw e;
        }
    }
    void start(String name) {
        Thread reader = new Thread(this::readLoop, "server-reader");
        Thread writer = new Thread(this::writeLoop, "server-writer");
        reader.setDaemon(true); writer.setDaemon(true); writer.start(); reader.start();
        send(Wire.message("JOIN", "name", name));
        heartbeat.scheduleAtFixedRate(() -> send(Wire.message("PING")), 10, 10, TimeUnit.SECONDS);
    }
    void send(String frame) {
        if (!closed.get() && !outgoing.offer(frame)) close("전송 대기열이 가득 찼습니다.");
    }
    private void readLoop() {
        try (Reader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; !closed.get() && (line = Wire.readLine(in)) != null;)
                listener.message(this, Wire.parse(line));
            close("서버 연결이 종료되었습니다.");
        } catch (IOException | IllegalArgumentException e) { close("연결 종료: " + e.getMessage()); }
    }
    private void writeLoop() {
        try (Writer out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            while (!closed.get()) {
                String frame = outgoing.poll(1, TimeUnit.SECONDS);
                if (frame != null) { out.write(frame); out.write('\n'); out.flush(); }
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            close("전송 연결이 종료되었습니다.");
        }
    }
    void close(String reason) {
        if (!closed.compareAndSet(false, true)) return;
        heartbeat.shutdownNow();
        try { socket.close(); } catch (IOException ignored) { }
        listener.closed(this, reason);
    }
}
