import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableModel;

/** 화면 배치와 버튼 이벤트. 담당: 표신현
 * 통신은 Actions를 통해 ClientMain에 요청한다. 이 클래스는 Socket을 만들지 않는다.
 */
public final class GameView extends JPanel {
    private static final long serialVersionUID = 1L;

    public interface Actions {
        void connect(String host, int port, String nickname);
        void disconnect();
        void sendChat(String text);
        void startGame();
        void play(String action, int amount);
    }

    private final JTextField hostField = new JTextField("127.0.0.1", 11);
    private final JTextField portField = new JTextField("5555", 5);
    private final JTextField nicknameField = new JTextField(9);
    private final JButton connectButton = new JButton("접속");
    private final JButton disconnectButton = new JButton("나가기");
    private final JButton startButton = new JButton("게임 시작");
    private final JButton checkButton = new JButton("체크");
    private final JButton callButton = new JButton("콜");
    private final JButton foldButton = new JButton("폴드");
    private final JButton betButton = new JButton("베트");
    private final JTextField amountField = new JTextField("20", 6);
    private final JLabel betHelpLabel = new JLabel("이번 단계 총액을 입력하세요.", SwingConstants.CENTER);
    private final JLabel potLabel = new JLabel("판돈 0", SwingConstants.CENTER);
    private final JLabel turnLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel[] boardLabels = {cardLabel("-"), cardLabel("-"), cardLabel("-"), cardLabel("-"), cardLabel("-")};
    private transient GameState state; // Swing 직렬화 대상이 아닌 현재 화면 정보
    private final JButton sendButton = new JButton("전송");
    private final JTextField chatField = new JTextField();
    private final JTextArea chatArea = new JTextArea();
    private final JLabel statusLabel = new JLabel("서버 주소와 닉네임을 입력해 주세요.");
    private final JLabel phaseLabel = new JLabel("게임 대기", SwingConstants.CENTER);
    private final JLabel playersLabel = new JLabel("참가자 0 / 6");
    private final JLabel[] handLabels = {cardLabel("?"), cardLabel("?")};
    private final DefaultTableModel playerModel = new DefaultTableModel(
            new String[] {"닉네임", "보유 칩", "이번 베팅", "상태", "역할"}, 0) {
        private static final long serialVersionUID = 1L;
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private boolean connected;
    private int myId;

    public GameView(Actions actions) {
        setLayout(new BorderLayout(16, 14));
        setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        setBackground(new Color(244, 246, 248));
        setPreferredSize(new Dimension(1180, 870));

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("텍사스 홀덤 · 3주차");
        title.setFont(new Font("Dialog", Font.BOLD, 25));
        header.add(title);
        JPanel connection = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 10));
        connection.setAlignmentX(LEFT_ALIGNMENT);
        connection.setOpaque(false);
        connection.add(new JLabel("서버")); connection.add(hostField);
        connection.add(new JLabel("포트")); connection.add(portField);
        connection.add(new JLabel("닉네임")); connection.add(nicknameField);
        connection.add(connectButton); connection.add(disconnectButton);
        header.add(connection);
        header.add(statusLabel);
        add(header, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(16, 0));
        center.setOpaque(false);
        JPanel left = new JPanel(new BorderLayout(0, 12));
        left.setOpaque(false);
        JPanel players = new JPanel(new BorderLayout(0, 6));
        players.setOpaque(false);
        JTable playerTable = new JTable(playerModel);
        playerTable.setRowHeight(25);
        playerTable.setFocusable(false);
        playerTable.getTableHeader().setReorderingAllowed(false);
        JScrollPane playerScroll = new JScrollPane(playerTable);
        playerScroll.setColumnHeaderView(playerTable.getTableHeader());
        playerScroll.setPreferredSize(new Dimension(600, 180));
        players.add(playersLabel, BorderLayout.NORTH);
        players.add(playerScroll, BorderLayout.CENTER);
        left.add(players, BorderLayout.NORTH);

        JPanel board = new JPanel();
        board.setLayout(new BoxLayout(board, BoxLayout.Y_AXIS));
        board.setBackground(new Color(25, 82, 65));
        board.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        phaseLabel.setForeground(Color.WHITE);
        phaseLabel.setAlignmentX(CENTER_ALIGNMENT);
        phaseLabel.setFont(new Font("Dialog", Font.BOLD, 18));
        board.add(phaseLabel);
        for (JLabel label : new JLabel[] {potLabel, turnLabel}) {
            label.setForeground(Color.WHITE); label.setAlignmentX(CENTER_ALIGNMENT);
            label.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0)); board.add(label);
        }
        board.add(boardLabel("공용 카드"));
        JPanel community = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 4));
        community.setOpaque(false);
        for (JLabel label : boardLabels) { community.add(label); }
        community.setMaximumSize(new Dimension(2000, 76));
        board.add(community);
        board.add(boardLabel("내 카드"));
        JPanel hand = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 4));
        hand.setOpaque(false);
        for (JLabel label : handLabels) { hand.add(label); }
        hand.setMaximumSize(new Dimension(2000, 76));
        board.add(hand);
        JPanel basicActions = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        basicActions.setOpaque(false);
        basicActions.add(checkButton); basicActions.add(callButton); basicActions.add(foldButton);
        basicActions.setMaximumSize(new Dimension(2000, 40));
        board.add(basicActions);
        JPanel betting = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        betting.setOpaque(false);
        JLabel totalLabel = new JLabel("이번 단계 총액"); totalLabel.setForeground(Color.WHITE);
        betting.add(totalLabel); betting.add(amountField); betting.add(betButton);
        betting.setMaximumSize(new Dimension(2000, 40));
        board.add(betting);
        betHelpLabel.setForeground(Color.WHITE); betHelpLabel.setAlignmentX(CENTER_ALIGNMENT);
        betHelpLabel.setMaximumSize(new Dimension(2000, 24));
        board.add(betHelpLabel);
        board.add(Box.createVerticalStrut(4));
        startButton.setAlignmentX(CENTER_ALIGNMENT);
        board.add(startButton);
        left.add(board, BorderLayout.CENTER);
        center.add(left, BorderLayout.CENTER);

        JPanel chat = new JPanel(new BorderLayout(6, 8));
        chat.setOpaque(false);
        chat.setPreferredSize(new Dimension(300, 500));
        chat.add(new JLabel("채팅 및 게임 안내"), BorderLayout.NORTH);
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        chatArea.setMargin(new java.awt.Insets(10, 10, 10, 10));
        chat.add(new JScrollPane(chatArea), BorderLayout.CENTER);
        JPanel chatInput = new JPanel(new BorderLayout(6, 0));
        chatInput.add(chatField, BorderLayout.CENTER);
        chatInput.add(sendButton, BorderLayout.EAST);
        chat.add(chatInput, BorderLayout.SOUTH);
        center.add(chat, BorderLayout.EAST);
        add(center, BorderLayout.CENTER);

        connectButton.addActionListener(event -> {
            try {
                int port = Integer.parseInt(portField.getText().trim());
                String host = hostField.getText().trim();
                String nickname = nicknameField.getText().trim();
                if (host.isEmpty() || port < 1 || port > 65535 || nickname.isEmpty()) {
                    showError("서버 주소, 포트, 닉네임을 확인하세요.");
                    return;
                }
                actions.connect(host, port, nickname);
            } catch (NumberFormatException e) {
                showError("포트는 숫자로 입력하세요.");
            }
        });
        disconnectButton.addActionListener(event -> actions.disconnect());
        startButton.addActionListener(event -> actions.startGame());
        checkButton.addActionListener(event -> actions.play("CHECK", 0));
        callButton.addActionListener(event -> actions.play("CALL", 0));
        foldButton.addActionListener(event -> actions.play("FOLD", 0));
        betButton.addActionListener(event -> {
            try {
                int amount = Integer.parseInt(amountField.getText().trim());
                if (amount <= 0 || state == null) { showError("양의 정수 금액을 입력하세요."); return; }
                actions.play(state.currentBet == 0 ? "BET" : "RAISE", amount);
            } catch (NumberFormatException e) { showError("베팅 총액은 정수로 입력하세요."); }
        });
        sendButton.addActionListener(event -> submitChat(actions));
        chatField.addActionListener(event -> submitChat(actions));
        setDisconnected("서버 주소와 닉네임을 입력해 주세요.");
    }

    private static JLabel boardLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Color.WHITE);
        label.setAlignmentX(CENTER_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(6, 0, 2, 0));
        return label;
    }

    private static JLabel cardLabel(String text) {
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setPreferredSize(new Dimension(82, 68));
        label.setMinimumSize(new Dimension(82, 68));
        label.setOpaque(true);
        label.setBackground(Color.WHITE);
        label.setForeground(new Color(35, 44, 55));
        label.setBorder(BorderFactory.createLineBorder(new Color(207, 216, 222)));
        label.setFont(new Font("Dialog", Font.BOLD, 22));
        return label;
    }

    private void submitChat(Actions actions) {
        String text = chatField.getText().trim();
        if (!connected || text.isEmpty()) { return; }
        if (text.length() > 200) { showError("채팅은 200자까지 입력할 수 있습니다."); return; }
        actions.sendChat(text);
        chatField.setText("");
    }

    public void setConnecting() {
        setFieldsEnabled(false);
        disconnectButton.setEnabled(true);
        statusLabel.setText("서버에 연결하는 중입니다.");
        statusLabel.setForeground(Color.DARK_GRAY);
    }

    public void setConnected(int id) {
        myId = id;
        connected = true;
        sendButton.setEnabled(true);
        chatField.setEnabled(true);
        statusLabel.setText("접속되었습니다. 다른 참가자를 기다려 주세요.");
    }

    private void setFieldsEnabled(boolean enabled) {
        hostField.setEnabled(enabled); portField.setEnabled(enabled);
        nicknameField.setEnabled(enabled); connectButton.setEnabled(enabled);
    }

    public void setDisconnected(String message) {
        connected = false; myId = 0; state = null;
        setActionPending();
        setFieldsEnabled(true);
        disconnectButton.setEnabled(false); sendButton.setEnabled(false);
        chatField.setEnabled(false); startButton.setEnabled(false);
        startButton.setText("게임 시작");
        playerModel.setRowCount(0);
        playersLabel.setText("참가자 0 / 6");
        phaseLabel.setText("게임 대기");
        potLabel.setText("판돈 0"); turnLabel.setText("");
        betHelpLabel.setText("이번 단계 총액을 입력하세요.");
        for (JLabel label : boardLabels) { displayCard(label, null, "-"); }
        setHand(List.of());
        statusLabel.setForeground(Color.DARK_GRAY);
        statusLabel.setText(message);
    }

    public void setTable(GameState snapshot) {
        state = snapshot;
        playerModel.setRowCount(0);
        int connectedCount = 0;
        for (GameState.Seat player : state.seats) {
            int id = player.id();
            if (!player.status().equals("LEFT")) { connectedCount++; }
            String role = id == state.hostId ? "방장 " : "";
            if (id == state.dealerId) { role += "D "; }
            if (id == state.smallBlindId) { role += "SB "; }
            if (id == state.bigBlindId) { role += "BB "; }
            if (id == myId) { role += "나"; }
            String status = switch (player.status()) {
                case "LEFT" -> "퇴장";
                case "WAIT" -> "다음 판 대기";
                case "FOLD" -> "폴드";
                default -> id == state.turnId ? "현재 차례" : state.running() ? "참여 중" : "판 종료";
            };
            playerModel.addRow(new Object[] {player.nickname(), player.chips(), player.bet(), status, role});
        }
        playersLabel.setText("접속 " + connectedCount + "명 / 자리 " + state.seats.size() + " / 6");
        phaseLabel.setText(GameState.phaseText(state.phase));
        potLabel.setText("판돈 " + state.pot + "   |   현재 베팅 총액 " + state.currentBet);
        GameState.Seat actor = state.find(state.turnId);
        turnLabel.setText(actor == null ? " " : "현재 차례: " + actor.nickname());
        for (int i = 0; i < boardLabels.length; i++) {
            displayCard(boardLabels[i], i < state.board.size() ? state.board.get(i) : null, "-");
        }
        statusLabel.setForeground(Color.DARK_GRAY);
        statusLabel.setText(state.phase.equals("SHOWDOWN") ? "리버 베팅이 끝났습니다. 다음 연습판 시작 시 미정산 베팅액을 돌려줍니다."
                : state.running() ? (state.turnId == myId ? "내 차례입니다. 행동을 선택하세요." : "다른 참가자의 행동을 기다리고 있습니다.")
                : "방장이 시작하면 다음 판을 진행합니다. D는 딜러, SB·BB는 블라인드 자리입니다.");
        startButton.setText(state.phase.equals("SHOWDOWN") ? "베팅액 반환 후 다음 연습판" : "게임 시작 / 다음 판");
        startButton.setEnabled(connected && myId == state.hostId && connectedCount >= 2 && !state.running());
        GameState.Seat me = state.find(myId);
        boolean myTurn = connected && state.running() && state.turnId == myId && me != null;
        int toCall = me == null ? 0 : state.currentBet - me.bet();
        int minimum = state.currentBet == 0 ? 20 : state.currentBet + state.minRaise;
        checkButton.setEnabled(myTurn && toCall == 0);
        callButton.setText("콜 " + toCall);
        callButton.setEnabled(myTurn && toCall > 0 && toCall < me.chips());
        foldButton.setEnabled(myTurn);
        betButton.setText(state.currentBet == 0 ? "베트" : "레이즈");
        betButton.setEnabled(myTurn && minimum - me.bet() < me.chips());
        amountField.setEnabled(betButton.isEnabled());
        amountField.setText(String.valueOf(minimum));
        betHelpLabel.setText(myTurn ? "최소 총액 " + minimum + " / 추가 납부액 = 입력 총액 - 내 이번 베팅"
                : "베팅 총액은 이번 단계에서 낼 누적 금액입니다.");
    }

    public void setActionPending() {
        for (JButton button : new JButton[] {startButton, checkButton, callButton, foldButton, betButton}) {
            button.setEnabled(false);
        }
        amountField.setEnabled(false);
    }

    public void setHand(List<Card> hand) {
        for (int i = 0; i < handLabels.length; i++) {
            Card card = i < hand.size() ? hand.get(i) : null;
            displayCard(handLabels[i], card, "?");
        }
    }

    private static void displayCard(JLabel label, Card card, String empty) {
        label.setText(card == null ? empty : "<html><center><span style='font-size:10pt'>"
                + card.getSuitName() + "</span><br><span style='font-size:18pt'>"
                + card.getRankText() + "</span></center></html>");
        label.setForeground(card != null && card.isRed() ? new Color(179, 44, 52) : new Color(35, 44, 55));
    }

    public void appendChat(String text) {
        chatArea.append(text + "\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

    public void showError(String text) {
        statusLabel.setForeground(new Color(174, 37, 45));
        statusLabel.setText(text);
        appendChat("[안내] " + text);
    }
}
