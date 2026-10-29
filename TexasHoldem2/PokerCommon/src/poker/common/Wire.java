package poker.common;

import java.io.IOException;
import java.io.Reader;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * [공통 / 먼저 읽기] 서버와 클라이언트가 지킬 메시지 작성 규칙이다.
 * 예: JOIN + 탭 + name=PlayerA. 실제 전송 끝에는 송신 스레드가 줄바꿈을 붙인다.
 * message(): Java 데이터 → 전송 문자열 / parse(): 전송 문자열 → Java 데이터.
 * TCP는 바이트를 순서대로 전달하지만 '한 번 보낸 문장'의 경계를 보존하지 않는다.
 * 그래서 이 프로젝트에서는 줄바꿈을 메시지 하나의 끝으로 약속했다.
 * URL 인코딩은 한글과 구분자를 안전하게 표현하기 위한 것으로, 암호화가 아니다.
 */
public final class Wire {
    // 줄바꿈 없이 계속 들어오는 입력으로 메모리가 커지는 것을 제한한다. 단위는 읽은 문자 수다.
    public static final int MAX_LINE = 16384;
    // 객체 상태가 없는 도구 클래스이므로 new Wire()를 막고 Wire.message(...)처럼 사용한다.
    private Wire() { }

    // [메서드] 키/값을 번갈아 받아 메시지를 만든다.
    // String...은 가변 인자다. 예: message("ACT", "action", "CALL", "hand", "1").
    // pairs는 {"action", "CALL", "hand", "1"} 배열처럼 사용된다.
    // 키 하나마다 값 하나가 필요하므로 인자 수가 홀수이면 잘못된 요청이다.
    public static String message(String type, String... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("key/value pairs required");
        // Map은 키로 값을 찾는 자료구조다. LinkedHashMap은 넣은 순서대로 항목을 순회한다.
        Map<String, String> fields = new LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) fields.put(pairs[i], pairs[i + 1]);
        return message(type, fields);
    }

    // [오버로딩] Map으로 받은 필드를 실제 전송 문자열로 조립한다.
    // 위 메서드와 이름은 같지만 매개변수 타입이 다르다.
    // StringBuilder는 문자열을 여러 번 이어 붙일 때 사용하는 가변 문자열 버퍼다.
    public static String message(String type, Map<String, String> fields) {
        StringBuilder b = new StringBuilder(type);
        try {
            for (Map.Entry<String, String> e : fields.entrySet()) {
                // 탭은 필드 사이, '='는 키와 값 사이의 구분자다.
                // 값에 탭·줄바꿈·'='·한글이 있어도 규칙을 깨뜨리지 않도록 값 부분을 인코딩한다.
                b.append('\t').append(e.getKey()).append('=')
                    .append(URLEncoder.encode(e.getValue(), "UTF-8"));
            }
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
        if (b.length() > MAX_LINE) throw new IllegalArgumentException("message too long");
        return b.toString();
    }

    // [수신 측] 한 줄을 분해하여 type과 각 필드를 Map에 담는다.
    // 예: ACT 탭 action=CALL → m.get("type")은 "ACT", m.get("action")은 "CALL".
    // 형식이 틀리면 IllegalArgumentException을 던지고, 호출한 통신 코드가 오류를 처리한다.
    public static Map<String, String> parse(String line) {
        if (line == null || line.length() > MAX_LINE) throw new IllegalArgumentException("bad frame");
        // split의 -1은 끝의 빈 항목도 보존한다. matches(...)는 정규표현식으로 허용 형식을 검사한다.
        String[] parts = line.split("\t", -1);
        if (!parts[0].matches("[A-Z_]{1,20}") || parts.length > 200)
            throw new IllegalArgumentException("bad message type");
        Map<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("type", parts[0]);
        try {
            for (int i = 1; i < parts.length; i++) {
                // 첫 '='의 위치를 찾는다. 그 앞은 키, 그 뒤는 URL 인코딩된 값이다.
                int eq = parts[i].indexOf('=');
                if (eq <= 0) throw new IllegalArgumentException("bad field");
                String key = parts[i].substring(0, eq);
                // 같은 키를 두 번 넣어 해석이 모호해지는 메시지도 거절한다.
                if (!key.matches("[a-zA-Z0-9_.]{1,32}") || fields.containsKey(key))
                    throw new IllegalArgumentException("bad/duplicate key");
                fields.put(key, URLDecoder.decode(parts[i].substring(eq + 1), "UTF-8"));
            }
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
        return fields;
    }

    /**
     * [핵심] 네트워크에서 읽은 문자를 줄바꿈이 나올 때까지 모은다.
     * BufferedReader.readLine()과 목적은 비슷하지만 최대 길이를 직접 제한한다.
     * read()는 데이터가 없으면 기다리며, 연결의 정상적인 입력 끝에서는 -1을 반환한다.
     * 따라서 이 메서드는 GUI 스레드나 서버 게임 스레드가 아닌 수신 스레드에서 호출한다.
     */
    public static String readLine(Reader in) throws IOException {
        StringBuilder b = new StringBuilder();
        int rawLength = 0;
        for (int c; (c = in.read()) != -1;) {
            if (c == '\n') return b.toString();
            if (++rawLength > MAX_LINE) throw new IOException("frame exceeds limit");
            // Windows 줄바꿈 CR+LF 중 CR은 내용에서 제외한다. 길이 제한에는 이미 포함했다.
            if (c == '\r') continue;
            b.append((char) c);
        }
        // 문장을 읽던 중 연결이 끝나면 불완전한 메시지다. 아무 문자도 없던 EOF는 null로 알린다.
        if (b.length() > 0) throw new IOException("incomplete frame");
        return null;
    }

    // 메시지의 값은 모두 String이다. 계산에 쓸 값은 여기서 int로 바꾸고 필수 값 존재도 검사한다.
    public static int number(Map<String, String> m, String key) {
        String value = m.get(key);
        if (value == null) throw new IllegalArgumentException("missing " + key);
        return Integer.parseInt(value);
    }

    // 필수 문자열을 가져온다. 없으면 null을 뒤에서 쓰게 두지 않고 즉시 오류로 처리한다.
    public static String required(Map<String, String> m, String key) {
        String value = m.get(key);
        if (value == null) throw new IllegalArgumentException("missing " + key);
        return value;
    }
}
