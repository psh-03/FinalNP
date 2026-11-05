package poker.client;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ConnectException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** 1주차 실습: 콘솔에서 입력한 문자열을 서버로 보내는 클라이언트. */
public class ClientMain {
    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5555;

        // 같은 PC에서 실습하므로 기본 주소는 자기 자신인 127.0.0.1이다.
        try (Socket socket = new Socket(host, port);
             BufferedReader serverIn = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter serverOut = new BufferedWriter(new OutputStreamWriter(
                     socket.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader keyboard = new BufferedReader(new InputStreamReader(
                     System.in, StandardCharsets.UTF_8))) {

            System.out.println("[클라이언트] 서버 연결 완료");
            String welcome = serverIn.readLine();
            if (welcome == null) {
                System.out.println("[클라이언트] 서버가 연결을 종료했습니다.");
                return;
            }
            System.out.println("[클라이언트 수신] " + welcome);
            System.out.println("[클라이언트] 메시지를 입력하세요. 종료: QUIT");

            String message;
            while ((message = keyboard.readLine()) != null) {
                serverOut.write(message);
                serverOut.newLine();
                serverOut.flush();
                System.out.println("[클라이언트 송신] " + message);

                // 1주차는 메시지 하나를 보내고 응답 하나를 기다리는 방식이다.
                String reply = serverIn.readLine();
                if (reply == null) {
                    System.out.println("[클라이언트] 서버가 연결을 종료했습니다.");
                    break;
                }
                System.out.println("[클라이언트 수신] " + reply);
                if ("BYE".equals(reply)) {
                    break;
                }
            }
            System.out.println("[클라이언트] 종료");
        } catch (ConnectException e) {
            System.out.println("[클라이언트] 서버에 연결할 수 없습니다.");
            System.out.println("[클라이언트] 서버 실행 여부와 IP/포트를 확인하세요.");
        } catch (IOException e) {
            System.out.println("[클라이언트] 통신 오류: " + e.getMessage());
        }
    }
}
