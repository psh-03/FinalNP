package poker.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** 1주차 실습: 클라이언트 1명과 문자열을 주고받는 콘솔 서버. */
public class ServerMain {
    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5555;

        // ServerSocket은 접속을 기다린다. 이 버전은 한 번의 접속만 처리한다.
        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("[서버] 포트 " + port + "에서 접속 대기");

            // accept()는 접속 요청이 들어올 때까지 여기에서 기다린다.
            try (Socket client = server.accept();
                 BufferedReader in = new BufferedReader(new InputStreamReader(
                         client.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                         client.getOutputStream(), StandardCharsets.UTF_8))) {

                System.out.println("[서버] 클라이언트 접속 완료");
                send(out, "WELCOME");

                String message;
                // 한 줄을 메시지 하나로 본다. 상대가 정상 종료하면 null을 반환한다.
                while ((message = in.readLine()) != null) {
                    System.out.println("[서버 수신] " + message);
                    if ("QUIT".equalsIgnoreCase(message.trim())) {
                        send(out, "BYE");
                        break;
                    }
                    if (message.isBlank()) {
                        send(out, "ERROR 빈 메시지는 보낼 수 없습니다.");
                    } else {
                        // ACK는 수신 확인 응답이다. 아직 게임 명령은 해석하지 않는다.
                        send(out, "ACK " + message);
                    }
                }
            }
            System.out.println("[서버] 연결 종료");
        } catch (BindException e) {
            System.out.println("[서버] 이미 사용 중인 포트입니다: " + port);
        } catch (IOException e) {
            System.out.println("[서버] 통신 오류: " + e.getMessage());
        }
    }

    private static void send(BufferedWriter out, String message) throws IOException {
        out.write(message);
        out.newLine();  // 수신 측 readLine()이 메시지의 끝을 구분하는 기준이다.
        out.flush();    // 출력 버퍼에 남은 문자를 소켓으로 보낸다.
        System.out.println("[서버 송신] " + message);
    }
}
