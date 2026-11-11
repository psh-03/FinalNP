package poker.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;

/** 1주차 실습: 클라이언트 1명에게 받은 문장을 그대로 돌려주는 에코 서버. */
public class ServerMain {
    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5555;

        // CMD 출력은 콘솔 설정에 맞추고, Eclipse의 Console 창은 UTF-8로 설정한다.
        Console console = System.console();
        Charset consoleCharset = console == null ? StandardCharsets.UTF_8 : console.charset();
        System.setOut(new PrintStream(System.out, true, consoleCharset));

        // ServerSocket은 접속을 기다린다. 이 버전은 한 번의 접속만 처리한다.
        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("포트 " + port + "에서 접속 대기 중");

            // accept()는 접속 요청이 들어올 때까지 여기에서 기다린다.
            try (Socket client = server.accept();
                 BufferedReader in = new BufferedReader(new InputStreamReader(
                         client.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                         client.getOutputStream(), StandardCharsets.UTF_8))) {

                System.out.println("클라이언트가 연결되었습니다.");

                String message;
                // 한 줄을 메시지 하나로 본다. 상대가 정상 종료하면 null을 반환한다.
                while ((message = in.readLine()) != null) {
                    System.out.println("받은 메시지: " + message);
                    // 접두사나 확인 문구를 붙이지 않고 원문을 그대로 응답한다.
                    out.write(message);
                    out.newLine(); // readLine()이 메시지의 끝을 구분할 수 있도록 한다.
                    out.flush();   // 출력 버퍼에 남은 내용을 실제로 전송한다.
                }
            }
            System.out.println("연결이 종료되었습니다.");
        } catch (BindException e) {
            System.out.println("이미 사용 중인 포트입니다: " + port);
        } catch (IOException e) {
            System.out.println("통신 오류: " + e.getMessage());
        }
    }
}
