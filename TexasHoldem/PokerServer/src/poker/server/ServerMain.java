package poker.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;

/** Run this FIRST. Usage: ServerMain [port=5555] [turnSeconds=45]. */
public final class ServerMain {
    public static void main(String[] args) {
        try {
            int port = args.length > 0 ? Integer.parseInt(args[0]) : 5555;
            int turnSeconds = args.length > 1 ? Integer.parseInt(args[1]) : 45;
            if (port < 1 || port > 65535 || turnSeconds < 1 || turnSeconds > 600)
                throw new IllegalArgumentException("port 1..65535, turnSeconds 1..600");
            ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "game-loop"); t.setDaemon(true); return t;
            });
            GameTable table = new GameTable(loop, turnSeconds);
            Semaphore capacity = new Semaphore(12); // 6 players plus a few pending joins.
            try (ServerSocket server = new ServerSocket(port)) {
                System.out.println("Texas Hold'em server · TCP " + port + " · turn " + turnSeconds + "s");
                System.out.println("ClientMain을 2개 이상 실행하세요. 같은 PC 주소: 127.0.0.1");
                while (true) {
                    Socket socket = server.accept();
                    if (!capacity.tryAcquire()) { socket.close(); continue; }
                    new ClientSession(socket, table, loop, capacity).start();
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("서버 실행 실패: " + e.getMessage());
            System.err.println("사용법: java poker.server.ServerMain [포트] [제한시간(초)]");
            System.exit(1);
        }
    }
}
