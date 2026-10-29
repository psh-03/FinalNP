package poker.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;

/**
 * [서버 / 실행 시작점] 클라이언트보다 먼저 실행하는 프로그램이다.
 * 서버 소켓으로 접속을 기다리고, 연결마다 ClientSession을 하나씩 만든다.
 * 이 클래스는 접속을 받고, 실제 포커 규칙은 GameTable이 처리한다.
 * 실행 인자 예: ServerMain 5555 45 → 포트 5555, 한 차례 45초.
 */
public final class ServerMain {
    // JVM이 프로그램을 실행할 때 시작하는 메서드다. args는 실행 시 뒤에 붙인 문자열 배열이다.
    public static void main(String[] args) {
        try {
            // 조건 ? 참일 때 값 : 거짓일 때 값은 삼항 연산자다. 실행 인자가 없으면 기본값을 쓴다.
            int port = args.length > 0 ? Integer.parseInt(args[0]) : 5555;
            int turnSeconds = args.length > 1 ? Integer.parseInt(args[1]) : 45;
            if (port < 1 || port > 65535 || turnSeconds < 1 || turnSeconds > 600)
                throw new IllegalArgumentException("port 1..65535, turnSeconds 1..600");
            // [심화 / 동시성] 게임 상태를 바꾸는 작업을 한 개의 스레드가 순서대로 처리하게 만든다.
            // execute(...)는 작업을 맡기고, schedule(...)은 일정 시간 후 실행을 예약한다.
            // r -> {...}는 ThreadFactory를 람다식으로 작성한 것: 전달된 작업 r을 실행할 Thread를 만든다.
            // 접속자별 수신 스레드가 GameTable을 동시에 변경하지 않고 이 loop에 작업을 맡기는 구조다.
            ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(r -> {
                // daemon 스레드는 이것만 남았을 때 JVM 종료를 막지 않는다. 게임 순서 보장은 단일 작업 스레드에서 나온다.
                Thread t = new Thread(r, "game-loop"); t.setDaemon(true); return t;
            });
            GameTable table = new GameTable(loop, turnSeconds);
            // Semaphore는 연결 허가증 12장처럼 쓴다. 접속 때 1장 확보하고 연결 종료 때 돌려준다.
            Semaphore capacity = new Semaphore(12); // 실제 게임 정원 6명과 별개로, 아직 입장하지 않은 연결까지 최대 12개 허용.
            // ServerSocket은 접속을 기다리는 소켓이다. 실제 한 명과 통신하는 Socket과 구분한다.
            // try (...)는 try-with-resources 문법으로, 블록을 벗어날 때 자원을 close()한다.
            try (ServerSocket server = new ServerSocket(port)) {
                System.out.println("Texas Hold'em server · TCP " + port + " · turn " + turnSeconds + "s");
                System.out.println("ClientMain을 2개 이상 실행하세요. 같은 PC 주소: 127.0.0.1");
                while (true) {
                    // [수업 핵심] accept()는 클라이언트의 연결 요청이 올 때까지 기다린다.
                    // 연결되면 그 클라이언트와 데이터를 주고받을 새 Socket을 반환한다.
                    // 기존 ServerSocket은 다음 사람의 접속을 계속 받을 수 있다.
                    Socket socket = server.accept();
                    // 허가증이 없으면 기다리지 않고 이 새 연결을 닫는다. 실제 6인 좌석 제한은 GameTable.join()에서 검사한다.
                    if (!capacity.tryAcquire()) { socket.close(); continue; }
                    // 이 클라이언트 전용 수신·송신 스레드를 시작한 뒤 while문의 accept()로 돌아가 다음 접속을 받는다.
                    new ClientSession(socket, table, loop, capacity).start();
                }
            }
        // 포트 사용 중 같은 입출력 오류나 잘못된 인자를 한 곳에서 처리한다. |는 여러 예외 타입을 함께 잡는 문법이다.
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("서버 실행 실패: " + e.getMessage());
            System.err.println("사용법: java poker.server.ServerMain [포트] [제한시간(초)]");
            System.exit(1);
        }
    }
}
