import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;

/** 1주차 실습: 입력한 한 줄을 보내고 서버가 돌려준 원문을 확인한다. */
public class ClientMain {
    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5555;

        // CMD에서는 Java가 확인한 콘솔 설정을 사용한다.
        // Eclipse에는 System.console()이 없으므로 Console 창을 UTF-8로 설정한다.
        Console console = System.console();
        Charset consoleCharset = console == null ? StandardCharsets.UTF_8 : console.charset();
        System.setOut(new PrintStream(System.out, true, consoleCharset));

        // 같은 PC에서 실습하므로 기본 주소는 자기 자신인 127.0.0.1이다.
        try (Socket socket = new Socket(host, port);
             BufferedReader serverIn = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter serverOut = new BufferedWriter(new OutputStreamWriter(
                     socket.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader keyboard = new BufferedReader(console != null
                     ? console.reader()
                     : new InputStreamReader(System.in, StandardCharsets.UTF_8))) {

            System.out.println("서버에 연결했습니다. 메시지를 입력하세요. 종료: QUIT");

            while (true) {
                System.out.print("입력> ");
                System.out.flush();
                String message = keyboard.readLine();

                // QUIT은 클라이언트 종료 명령이다. 서버에 보내지 않고 소켓을 닫는다.
                if (message == null || "QUIT".equalsIgnoreCase(message.trim())) {
                    break;
                }

                // 메시지 원문과 줄바꿈만 UTF-8로 보낸다.
                serverOut.write(message);
                serverOut.newLine();
                serverOut.flush();

                // 서버가 그대로 돌려준 문장을 한 번만 표시한다.
                String reply = serverIn.readLine();
                if (reply == null) {
                    System.out.println("서버가 연결을 종료했습니다.");
                    break;
                }
                System.out.println("서버 응답: " + reply);
            }
            // try 블록을 벗어나면 스트림과 소켓이 자동으로 닫힌다.
        } catch (ConnectException e) {
            System.out.println("서버에 연결할 수 없습니다. 서버 실행 여부와 IP/포트를 확인하세요.");
        } catch (IOException e) {
            System.out.println("통신 오류: " + e.getMessage());
        }
    }
}
