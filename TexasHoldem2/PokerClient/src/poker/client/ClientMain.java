package poker.client;

import poker.common.Wire;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Collections;
import java.util.Map;

/**
 * [클라이언트 / 실행 시작점과 화면 제어] 플레이어마다 이 프로그램을 하나씩 실행한다.
 * JFrame을 상속하므로 창이며, PokerConnection.Listener를 구현하여 네트워크 소식을 받는다.
 * 버튼으로 요청을 보내고 서버가 돌려준 STATE로 화면을 갱신한다. 승패는 여기서 계산하지 않는다.
 * 실제 카드 테이블 그림은 TablePanel, 실제 소켓 입출력은 PokerConnection에 맡긴다.
 * 처음 읽을 순서: main → 생성자(버튼 연결 부분) → toggleConnection → action → message.
 */
public final class ClientMain extends JFrame implements PokerConnection.Listener {
    // JFrame이 직렬화 가능한 클래스여서 붙인 버전 번호다. 이 프로그램의 네트워크 통신은 객체 직렬화를 사용하지 않는다.
    private static final long serialVersionUID = 1L;
    // JTextField는 한 줄 입력 칸이다. 127.0.0.1은 이 클라이언트를 실행한 PC 자신을 뜻한다.
    private final JTextField host = new JTextField("127.0.0.1", 11);
    private final JTextField port = new JTextField("5555", 5);
    private final JTextField name = new JTextField("Player" + (100 + new java.util.Random().nextInt(900)), 10);
    // JButton은 버튼이다. 아래 생성자에서 addActionListener로 눌렀을 때 실행할 코드를 연결한다.
    private final JButton connect = new JButton("접속"), start = new JButton("핸드 시작");
    private final JButton rebuy = new JButton("칩 충전"), fold = new JButton("폴드");
    private final JButton call = new JButton("체크"), raise = new JButton("레이즈"), allIn = new JButton("올인");
    // 숫자 입력 칸의 모델: 초기값 40, UI 최소 1, UI 최대 1000000, 화살표 증감 10. 실제 허용 금액은 서버가 검증한다.
    private final JSpinner amount = new JSpinner(new SpinnerNumberModel(40, 1, 1000000, 10));
    private final JLabel status = new JLabel("서버를 먼저 실행하고 접속하세요.");
    private final JTextArea log = new JTextArea(5, 50);
    private final JTextField chat = new JTextField();
    // TablePanel은 JFrame 창 안에 배치할 카드 그림 패널이다. 이것 자체를 별도 main으로 실행하지 않는다.
    private final TablePanel table = new TablePanel();
    // 현재 연결 객체. null이면 아직 연결되지 않았거나 종료된 상태다.
    private PokerConnection connection;
    // 서버가 마지막으로 보내 준 상태다. 이 Map을 읽어 버튼 활성화와 화면 표시를 결정한다.
    private Map<String, String> state = Collections.emptyMap();
    // awaiting은 행동 요청의 응답 대기, connecting은 접속 시도 중이다. 반복 클릭으로 요청이 겹치는 것을 줄인다.
    private boolean awaiting, connecting;

    // [창 만들기] 컴포넌트를 배치하고 버튼 이벤트를 연결한다.
    // main()이 invokeLater로 생성자를 호출하므로 이 코드는 Swing의 EDT(화면 이벤트 스레드)에서 실행된다.
    // 처음 공부할 때는 색상·여백 수치보다 addActionListener 부분부터 보면 된다.
    private ClientMain(String[] args) {
        super("Texas Hold'em · Network Programming");
        // 실행 인자가 있다면 IP·포트·이름 입력 칸의 기본값을 덮어쓴다. 실제 접속은 접속 버튼을 눌러야 한다.
        if (args.length > 0) host.setText(args[0]);
        if (args.length > 1) port.setText(args[1]);
        if (args.length > 2) name.setText(args[2]);
        // 닫기 버튼으로 창을 해제한다. 아래 windowClosed 이벤트에서 타이머와 소켓도 정리한다.
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        // BorderLayout은 위/아래/왼쪽/오른쪽/중앙으로 나눈다. 여기서는 위에 접속 바, 중앙에 테이블, 아래에 조작부를 둔다.
        JPanel root = new JPanel(new BorderLayout(0, 0)); root.setBackground(new Color(16, 26, 33));
        // FlowLayout은 컴포넌트를 가로로 나란히 배치한다. 뒤의 숫자들은 간격/여백 설정이다.
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 12));
        top.add(new JLabel("서버 IP")); top.add(host); top.add(new JLabel("포트")); top.add(port);
        top.add(new JLabel("이름")); top.add(name); top.add(connect); top.add(start); top.add(rebuy);
        root.add(top, BorderLayout.NORTH); root.add(table, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(0, 6)); south.setBorder(new EmptyBorder(6, 12, 12, 12));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
        actions.add(fold); actions.add(call); actions.add(new JLabel("이번 단계 총액"));
        amount.setPreferredSize(new Dimension(100, 30)); actions.add(amount); actions.add(raise); actions.add(allIn);
        raise.setToolTipText("입력한 금액은 추가로 내는 칩이 아니라 이번 베팅 단계의 누적 총액입니다.");
        JPanel controls = new JPanel(new BorderLayout()); controls.add(actions, BorderLayout.NORTH);
        status.setBorder(new EmptyBorder(5, 6, 5, 6)); controls.add(status, BorderLayout.SOUTH);
        south.add(controls, BorderLayout.NORTH);
        // 기록 영역은 읽기 전용으로 두고 긴 문장은 줄바꿈한다. JScrollPane이 스크롤을 제공한다.
        log.setEditable(false); log.setLineWrap(true); log.setWrapStyleWord(true);
        log.setFont(new Font("SansSerif", Font.PLAIN, 12));
        south.add(new JScrollPane(log), BorderLayout.CENTER);
        JPanel chatBar = new JPanel(new BorderLayout(8, 0)); chatBar.add(new JLabel("채팅"), BorderLayout.WEST);
        chatBar.add(chat, BorderLayout.CENTER); JButton send = new JButton("보내기"); chatBar.add(send, BorderLayout.EAST);
        south.add(chatBar, BorderLayout.SOUTH); root.add(south, BorderLayout.SOUTH); setContentPane(root);
        // [이벤트 연결] e -> ...는 버튼을 눌렀을 때 실행할 ActionListener를 람다식으로 적은 것이다.
        // 등록할 때 바로 toggleConnection을 실행하는 것이 아니라, 클릭 이벤트가 발생하면 실행한다.
        connect.addActionListener(e -> toggleConnection());
        // START/REBUY 버튼은 요청만 보낸다. 실제 시작과 충전 권한은 서버의 GameTable.handle()이 검사한다.
        start.addActionListener(e -> send(Wire.message("START")));
        rebuy.addActionListener(e -> send(Wire.message("REBUY")));
        fold.addActionListener(e -> action("FOLD", 0));
        // 서버가 알려 준 toCall이 0이면 CHECK, 양수이면 CALL 명령을 보낸다.
        call.addActionListener(e -> action(integer("toCall", 0) == 0 ? "CHECK" : "CALL", 0));
        // commitEdit()는 사용자가 입력 중인 글자를 숫자 모델에 반영한다. 숫자가 아니면 예외를 잡아 안내한다.
        raise.addActionListener(e -> {
            try { amount.commitEdit(); action("RAISE", ((Number) amount.getValue()).intValue()); }
            catch (java.text.ParseException invalid) { append("총액에 숫자를 입력하세요."); }
        });
        // 올인 전용 명령을 따로 만들지 않았다. 잔액으로 콜만 가능하면 CALL, 더 올릴 수 있으면 최대 총액의 RAISE다.
        allIn.addActionListener(e -> {
            int max = integer("maxRaiseTo", 0);
            action(max <= integer("bet", 0) ? "CALL" : "RAISE", max);
        });
        // Runnable은 '실행할 작업 하나'를 표현한다. 채팅 Enter키와 보내기 버튼에서 같은 작업을 재사용한다.
        // 아래 sendChat.run()은 현재 EDT에서 작업을 실행한다. Runnable 자체가 새 스레드는 아니다.
        // send()는 소켓에 직접 쓰지 않고 큐에 넣으므로 이 짧은 작업은 EDT에서 실행할 수 있다.
        Runnable sendChat = () -> {
            String text = chat.getText().trim();
            if (connection != null && !text.isEmpty()) {
                if (text.length() > 200) { append("채팅은 200자까지 보낼 수 있습니다."); return; }
                send(Wire.message("CHAT", "text", text)); chat.setText("");
            }
        };
        chat.addActionListener(e -> sendChat.run()); send.addActionListener(e -> sendChat.run());
        // javax.swing.Timer는 500ms마다 EDT에서 남은 시간 표시를 갱신한다. 실제 시간 초과 판정은 서버가 담당한다.
        Timer clock = new Timer(500, e -> updateStatus()); clock.start();
        // WindowAdapter의 필요한 이벤트만 재정의한다. 익명 클래스이며 windowClosed가 호출될 때 정리한다.
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                clock.stop(); if (connection != null) connection.close("창을 닫았습니다.");
            }
        });
        setMinimumSize(new Dimension(1050, 780)); setSize(1180, 850); setLocationByPlatform(true);
        refreshControls();
    }
    // 이미 연결되어 있으면 끊고, 아니면 입력값 검사 후 접속을 시작한다. 접속 시도 중의 중복 클릭은 무시한다.
    private void toggleConnection() {
        if (connection != null) { connection.close("접속을 종료했습니다."); return; }
        if (connecting) return;
        final int targetPort;
        try {
            targetPort = Integer.parseInt(port.getText().trim());
            if (targetPort < 1 || targetPort > 65535) throw new NumberFormatException();
        } catch (NumberFormatException invalid) { append("포트는 1~65535의 숫자여야 합니다."); return; }
        final String targetHost = host.getText().trim(), nickname = name.getText().trim();
        if (targetHost.isEmpty() || nickname.isEmpty()) { append("서버 IP와 이름을 입력하세요."); return; }
        connecting = true; refreshControls(); status.setText("서버에 연결하는 중…");
        // [스레드 분리] Socket.connect는 최대 5초 기다릴 수 있다.
        // 이 대기를 EDT에서 수행하면 창 이동이나 버튼 반응이 멈추므로 별도 connect-worker에서 연결한다.
        Thread connector = new Thread(() -> {
            try {
                // this는 현재 ClientMain이다. Listener 역할로 전달해서 네트워크 결과를 이 창으로 돌려받는다.
                PokerConnection opened = new PokerConnection(targetHost, targetPort, this);
                // 연결 성공 후 입력 칸·버튼을 바꾸는 부분은 다시 EDT에 예약한다. I/O 스레드에서 Swing을 직접 바꾸지 않는다.
                SwingUtilities.invokeLater(() -> {
                    connecting = false;
                    // 연결을 기다리는 동안 사용자가 창을 닫았을 수도 있으므로 그 경우 새 연결도 닫는다.
                    if (!isDisplayable()) { opened.close("창이 닫혔습니다."); return; }
                    connection = opened; opened.start(nickname); refreshControls();
                });
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> {
                    connecting = false; append("접속 실패: " + error.getMessage());
                    status.setText("서버 실행 여부, IP, 포트를 확인하세요."); refreshControls();
                });
            }
        }, "connect-worker");
        connector.setDaemon(true); connector.start();
    }
    // 네트워크 담당 객체의 송신 큐에 문자열을 넣는 짧은 연결 메서드다.
    private void send(String frame) { if (connection != null) connection.send(frame); }
    // [게임 버튼 → 서버] 현재 서버 상태에 있는 hand/turn과 행동을 ACT 메시지에 담는다.
    // 여기서 로컬 칩을 먼저 줄이거나 다음 차례를 결정하지 않는다. 서버의 확정 상태를 기다린다.
    private void action(String action, int target) {
        if (state.isEmpty() || awaiting) return;
        send(Wire.message("ACT", "hand", state.get("hand"), "turn", state.get("turn"),
            "action", action, "amount", "" + target));
        // 응답이 올 때까지 행동 버튼을 잠깐 끈다. 서버에서도 차례 토큰을 검증하므로 UI와 서버에서 각각 보호한다.
        awaiting = true; refreshControls();
    }
    // [서버 응답 처리 / Listener 구현] 수신 스레드가 이 메서드를 호출한다.
    // invokeLater 안의 실제 화면 작업은 EDT에서 나중에 실행된다.
    // STATE는 화면 갱신, LOG는 기록 추가, ERROR는 요청 실패 안내다. PONG은 별도 화면 표시가 필요 없다.
    public void message(PokerConnection source, Map<String, String> message) {
        SwingUtilities.invokeLater(() -> {
            // 재접속한 뒤 옛 연결에서 도착한 늦은 응답이면 무시한다. 객체 참조가 현재 연결과 같은지 확인한다.
            if (connection != source) return;
            String type = message.get("type");
            if ("STATE".equals(type)) {
                // 서버 상태를 최신 값으로 교체하고 응답 대기를 해제한다. TablePanel.setState()가 다시 그리기를 요청한다.
                state = message; awaiting = false; table.setState(state);
                int minimum = integer("minRaiseTo", 20), maximum = integer("maxRaiseTo", 1000);
                // 최소 레이즈 총액과 현재 잔액 한도 중 작은 값을 입력 기본값으로 표시한다. 부족한 잔액의 올인도 고려한다.
                amount.setValue(Math.max(1, Math.min(minimum, maximum)));
                refreshControls(); updateStatus();
            } else if ("LOG".equals(type)) append(message.get("text"));
            else if ("ERROR".equals(type)) {
                append("[요청 거절] " + message.get("text")); awaiting = false; refreshControls();
                if (state.isEmpty()) source.close("접속 정보를 수정한 뒤 다시 접속하세요.");
            }
        });
    }
    // 연결 종료 통지도 EDT에서 처리한다. 카드 표시와 상태를 비우고 다시 접속할 수 있도록 입력 칸을 연다.
    public void closed(PokerConnection source, String reason) {
        SwingUtilities.invokeLater(() -> {
            if (connection != source) return;
            connection = null; state = Collections.emptyMap(); awaiting = false;
            table.setState(state); append(reason); status.setText(reason); refreshControls();
        });
    }
    // 상태 Map의 숫자 문자열을 int로 읽는다. 아직 필드가 없는 초기 화면에서는 fallback을 사용한다.
    private int integer(String key, int fallback) {
        try { return Integer.parseInt(state.get(key)); } catch (Exception e) { return fallback; }
    }
    // 문자열 "true"를 boolean으로 해석하는 작은 도우미다.
    private boolean yes(String key) { return "true".equals(state.get(key)); }
    // 서버가 알려 준 actor(현재 행동 좌석)와 you(내 좌석)가 같은지 확인한다.
    private boolean myTurn() {
        return connection != null && !state.isEmpty() && integer("actor", -1) == integer("you", -2);
    }
    // [버튼 상태] 연결·방장 권한·내 차례·응답 대기 여부에 따라 버튼을 켜거나 끈다.
    // 버튼 비활성화는 사용자 안내이고, 실제 부정한 요청 거절은 서버 검증이 담당한다.
    private void refreshControls() {
        boolean joined = connection != null && !state.isEmpty();
        connect.setEnabled(!connecting); connect.setText(connection == null ? "접속" : "접속 종료");
        host.setEnabled(connection == null && !connecting); port.setEnabled(host.isEnabled()); name.setEnabled(host.isEnabled());
        start.setEnabled(joined && yes("canStart")); rebuy.setEnabled(joined && yes("canRebuy"));
        boolean turn = myTurn() && !awaiting;
        fold.setEnabled(turn); call.setEnabled(turn); raise.setEnabled(turn && yes("canRaise")); amount.setEnabled(raise.isEnabled());
        allIn.setEnabled(turn && (yes("canRaise") || integer("maxRaiseTo", 0) <= integer("bet", 0)));
        call.setText(integer("toCall", 0) == 0 ? "체크" : "콜 " + integer("toCall", 0));
        raise.setText(integer("bet", 0) == 0 ? "베팅" : "레이즈"); chat.setEnabled(joined);
    }
    // 현재 차례와 남은 시간 등을 안내한다. 로컬 PC와 서버 시계 차이 때문에 표시 시간은 근사값일 수 있다.
    private void updateStatus() {
        if (state.isEmpty()) return;
        String phase = state.get("phase");
        if (phase.equals("WAITING") || phase.equals("FINISHED")) {
            status.setText(yes("canStart") ? "방장입니다. '핸드 시작'을 누르세요.  |  칩 0일 때 '칩 충전' 가능"
                : "방장이 다음 핸드를 시작할 때까지 기다리세요. 칩이 있는 참가자 2명 이상이 필요합니다."); return;
        }
        long seconds = 0;
        // 마감 시각 - 현재 시각을 초로 바꾼다. +999는 남은 밀리초를 초 단위로 올림 표시하려는 계산이다.
        try { seconds = Math.max(0, (Long.parseLong(state.get("deadline")) - System.currentTimeMillis() + 999) / 1000); }
        catch (NumberFormatException ignored) { }
        String who = state.get("p" + integer("actor", -1) + ".name");
        status.setText((myTurn() ? "내 차례" : who + " 님 차례") + " · 약 " + seconds + "초  |  콜 "
            + integer("toCall", 0) + "  |  최소 레이즈 총액 " + integer("minRaiseTo", 0)
            + "  |  총액 입력은 현재 단계 기준입니다.");
    }
    // 채팅/이벤트 기록을 추가한다. 내용이 계속 쌓이지 않도록 오래된 앞부분을 잘라내고 스크롤을 마지막으로 옮긴다.
    private void append(String text) {
        log.append(text + "\n");
        if (log.getDocument().getLength() > 30000) log.replaceRange("", 0, 10000);
        log.setCaretPosition(log.getDocument().getLength());
    }
    // 클라이언트 실행 시작점이다. Swing 창 생성부터 EDT에서 수행하도록 invokeLater에 맡긴다.
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            // 운영체제와 비슷한 버튼/입력 칸 외형을 사용한다. 적용에 실패해도 기본 외형으로 실행한다.
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { }
            new ClientMain(args).setVisible(true);
        });
    }
}
