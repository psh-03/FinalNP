package poker.server;

import poker.common.Wire;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One reader + one queued writer per socket; slow clients cannot block the table. */
final class ClientSession implements GameTable.Peer {
    private final Socket socket;
    private final GameTable table;
    private final ScheduledExecutorService loop;
    private final Semaphore capacity;
    private final BlockingQueue<String> outgoing = new ArrayBlockingQueue<String>(128);
    private final AtomicBoolean closed = new AtomicBoolean();
    ClientSession(Socket socket, GameTable table, ScheduledExecutorService loop, Semaphore capacity) {
        this.socket = socket; this.table = table; this.loop = loop; this.capacity = capacity;
    }
    void start() {
        Thread writer = new Thread(this::writeLoop, "client-writer-" + socket.getPort());
        Thread reader = new Thread(this::readLoop, "client-reader-" + socket.getPort());
        writer.setDaemon(true); reader.setDaemon(true); writer.start(); reader.start();
    }
    public void send(String frame) {
        if (!closed.get() && !outgoing.offer(frame)) close();
    }
    private void readLoop() {
        try (Reader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            socket.setTcpNoDelay(true); socket.setSoTimeout(45000);
            long window = System.nanoTime(); int messages = 0;
            for (String line; !closed.get() && (line = Wire.readLine(in)) != null;) {
                if (System.nanoTime() - window > 1000000000L) { window = System.nanoTime(); messages = 0; }
                if (++messages > 40) throw new IOException("too many messages");
                try {
                    Map<String, String> command = Wire.parse(line);
                    loop.execute(() -> { if (!closed.get()) table.handle(this, command); });
                } catch (IllegalArgumentException invalid) {
                    send(Wire.message("ERROR", "text", "잘못된 메시지 형식입니다."));
                }
            }
        } catch (IOException expected) {
            if (!closed.get()) System.out.println("[NETWORK] " + expected.getMessage());
        } finally { close(); }
    }
    private void writeLoop() {
        try (Writer out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            while (!closed.get()) {
                String frame = outgoing.poll(1, TimeUnit.SECONDS);
                if (frame != null) { out.write(frame); out.write('\n'); out.flush(); }
            }
        } catch (IOException | InterruptedException expected) {
            if (expected instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally { close(); }
    }
    private void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { socket.close(); } catch (IOException ignored) { }
        capacity.release();
        loop.execute(() -> table.disconnected(this));
    }
}
