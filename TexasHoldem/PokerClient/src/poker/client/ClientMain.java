package poker.client;

import poker.common.Wire;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Collections;
import java.util.Map;

/** Run once per player. ClientMain is the executable; TablePanel is only the view. */
public final class ClientMain extends JFrame implements PokerConnection.Listener {
    private static final long serialVersionUID = 1L;
    private final JTextField host = new JTextField("127.0.0.1", 11);
    private final JTextField port = new JTextField("5555", 5);
    private final JTextField name = new JTextField("Player" + (100 + new java.util.Random().nextInt(900)), 10);
    private final JButton connect = new JButton("접속"), start = new JButton("핸드 시작");
    private final JButton rebuy = new JButton("칩 충전"), fold = new JButton("폴드");
    private final JButton call = new JButton("체크"), raise = new JButton("레이즈"), allIn = new JButton("올인");
    private final JSpinner amount = new JSpinner(new SpinnerNumberModel(40, 1, 1000000, 10));
    private final JLabel status = new JLabel("서버를 먼저 실행하고 접속하세요.");
    private final JTextArea log = new JTextArea(5, 50);
    private final JTextField chat = new JTextField();
    private final TablePanel table = new TablePanel();
    private PokerConnection connection;
    private Map<String, String> state = Collections.emptyMap();
    private boolean awaiting, connecting;

    private ClientMain(String[] args) {
        super("Texas Hold'em · Network Programming");
        if (args.length > 0) host.setText(args[0]);
        if (args.length > 1) port.setText(args[1]);
        if (args.length > 2) name.setText(args[2]);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel root = new JPanel(new BorderLayout(0, 0)); root.setBackground(new Color(16, 26, 33));
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
        log.setEditable(false); log.setLineWrap(true); log.setWrapStyleWord(true);
        log.setFont(new Font("SansSerif", Font.PLAIN, 12));
        south.add(new JScrollPane(log), BorderLayout.CENTER);
        JPanel chatBar = new JPanel(new BorderLayout(8, 0)); chatBar.add(new JLabel("채팅"), BorderLayout.WEST);
        chatBar.add(chat, BorderLayout.CENTER); JButton send = new JButton("보내기"); chatBar.add(send, BorderLayout.EAST);
        south.add(chatBar, BorderLayout.SOUTH); root.add(south, BorderLayout.SOUTH); setContentPane(root);
        connect.addActionListener(e -> toggleConnection());
        start.addActionListener(e -> send(Wire.message("START")));
        rebuy.addActionListener(e -> send(Wire.message("REBUY")));
        fold.addActionListener(e -> action("FOLD", 0));
        call.addActionListener(e -> action(integer("toCall", 0) == 0 ? "CHECK" : "CALL", 0));
        raise.addActionListener(e -> {
            try { amount.commitEdit(); action("RAISE", ((Number) amount.getValue()).intValue()); }
            catch (java.text.ParseException invalid) { append("총액에 숫자를 입력하세요."); }
        });
        allIn.addActionListener(e -> {
            int max = integer("maxRaiseTo", 0);
            action(max <= integer("bet", 0) ? "CALL" : "RAISE", max);
        });
        Runnable sendChat = () -> {
            String text = chat.getText().trim();
            if (connection != null && !text.isEmpty()) {
                if (text.length() > 200) { append("채팅은 200자까지 보낼 수 있습니다."); return; }
                send(Wire.message("CHAT", "text", text)); chat.setText("");
            }
        };
        chat.addActionListener(e -> sendChat.run()); send.addActionListener(e -> sendChat.run());
        Timer clock = new Timer(500, e -> updateStatus()); clock.start();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                clock.stop(); if (connection != null) connection.close("창을 닫았습니다.");
            }
        });
        setMinimumSize(new Dimension(1050, 780)); setSize(1180, 850); setLocationByPlatform(true);
        refreshControls();
    }
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
        Thread connector = new Thread(() -> {
            try {
                PokerConnection opened = new PokerConnection(targetHost, targetPort, this);
                SwingUtilities.invokeLater(() -> {
                    connecting = false;
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
    private void send(String frame) { if (connection != null) connection.send(frame); }
    private void action(String action, int target) {
        if (state.isEmpty() || awaiting) return;
        send(Wire.message("ACT", "hand", state.get("hand"), "turn", state.get("turn"),
            "action", action, "amount", "" + target));
        awaiting = true; refreshControls();
    }
    public void message(PokerConnection source, Map<String, String> message) {
        SwingUtilities.invokeLater(() -> {
            if (connection != source) return;
            String type = message.get("type");
            if ("STATE".equals(type)) {
                state = message; awaiting = false; table.setState(state);
                int minimum = integer("minRaiseTo", 20), maximum = integer("maxRaiseTo", 1000);
                amount.setValue(Math.max(1, Math.min(minimum, maximum)));
                refreshControls(); updateStatus();
            } else if ("LOG".equals(type)) append(message.get("text"));
            else if ("ERROR".equals(type)) {
                append("[요청 거절] " + message.get("text")); awaiting = false; refreshControls();
                if (state.isEmpty()) source.close("접속 정보를 수정한 뒤 다시 접속하세요.");
            }
        });
    }
    public void closed(PokerConnection source, String reason) {
        SwingUtilities.invokeLater(() -> {
            if (connection != source) return;
            connection = null; state = Collections.emptyMap(); awaiting = false;
            table.setState(state); append(reason); status.setText(reason); refreshControls();
        });
    }
    private int integer(String key, int fallback) {
        try { return Integer.parseInt(state.get(key)); } catch (Exception e) { return fallback; }
    }
    private boolean yes(String key) { return "true".equals(state.get(key)); }
    private boolean myTurn() {
        return connection != null && !state.isEmpty() && integer("actor", -1) == integer("you", -2);
    }
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
    private void updateStatus() {
        if (state.isEmpty()) return;
        String phase = state.get("phase");
        if (phase.equals("WAITING") || phase.equals("FINISHED")) {
            status.setText(yes("canStart") ? "방장입니다. '핸드 시작'을 누르세요.  |  칩 0일 때 '칩 충전' 가능"
                : "방장이 다음 핸드를 시작할 때까지 기다리세요. 칩이 있는 참가자 2명 이상이 필요합니다."); return;
        }
        long seconds = 0;
        try { seconds = Math.max(0, (Long.parseLong(state.get("deadline")) - System.currentTimeMillis() + 999) / 1000); }
        catch (NumberFormatException ignored) { }
        String who = state.get("p" + integer("actor", -1) + ".name");
        status.setText((myTurn() ? "내 차례" : who + " 님 차례") + " · 약 " + seconds + "초  |  콜 "
            + integer("toCall", 0) + "  |  최소 레이즈 총액 " + integer("minRaiseTo", 0)
            + "  |  총액 입력은 현재 단계 기준입니다.");
    }
    private void append(String text) {
        log.append(text + "\n");
        if (log.getDocument().getLength() > 30000) log.replaceRange("", 0, 10000);
        log.setCaretPosition(log.getDocument().getLength());
    }
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { }
            new ClientMain(args).setVisible(true);
        });
    }
}
