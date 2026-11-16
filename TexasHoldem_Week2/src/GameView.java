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
    }

    private final JTextField hostField = new JTextField("127.0.0.1", 11);
    private final JTextField portField = new JTextField("5555", 5);
    private final JTextField nicknameField = new JTextField(9);
    private final JButton connectButton = new JButton("접속");
    private final JButton disconnectButton = new JButton("나가기");
    private final JButton startButton = new JButton("게임 시작");
    private final JButton sendButton = new JButton("전송");
    private final JTextField chatField = new JTextField();
    private final JTextArea chatArea = new JTextArea();
    private final JLabel statusLabel = new JLabel("서버 주소와 닉네임을 입력해 주세요.");
    private final JLabel phaseLabel = new JLabel("게임 대기", SwingConstants.CENTER);
    private final JLabel playersLabel = new JLabel("참가자 0 / 6");
    private final JLabel[] handLabels = {cardLabel("?"), cardLabel("?")};
    private final DefaultTableModel playerModel = new DefaultTableModel(
            new String[] {"닉네임", "보유 칩", "역할"}, 0) {
        private static final long serialVersionUID = 1L;
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private boolean connected;
    private int myId;

    public GameView(Actions actions) {
        setLayout(new BorderLayout(16, 14));
        setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        setBackground(new Color(244, 246, 248));
        setPreferredSize(new Dimension(1100, 720));

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("텍사스 홀덤");
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
        playerScroll.setPreferredSize(new Dimension(600, 150));
        players.add(playersLabel, BorderLayout.NORTH);
        players.add(playerScroll, BorderLayout.CENTER);
        left.add(players, BorderLayout.NORTH);

        JPanel board = new JPanel();
        board.setLayout(new BoxLayout(board, BoxLayout.Y_AXIS));
        board.setBackground(new Color(25, 82, 65));
        board.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        phaseLabel.setForeground(Color.WHITE);
        phaseLabel.setAlignmentX(CENTER_ALIGNMENT);
        phaseLabel.setFont(new Font("Dialog", Font.BOLD, 18));
        board.add(phaseLabel);
        board.add(boardLabel("공용 카드"));
        JPanel community = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 4));
        community.setOpaque(false);
        for (int i = 0; i < 5; i++) { community.add(cardLabel("—")); }
        board.add(community);
        board.add(boardLabel("내 카드"));
        JPanel hand = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 4));
        hand.setOpaque(false);
        for (JLabel label : handLabels) { hand.add(label); }
        board.add(hand);
        board.add(Box.createVerticalStrut(8));
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
        sendButton.addActionListener(event -> submitChat(actions));
        chatField.addActionListener(event -> submitChat(actions));
        setDisconnected("서버 주소와 닉네임을 입력해 주세요.");
    }

    private static JLabel boardLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Color.WHITE);
        label.setAlignmentX(CENTER_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(10, 0, 4, 0));
        return label;
    }

    private static JLabel cardLabel(String text) {
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setPreferredSize(new Dimension(82, 80));
        label.setOpaque(true);
        label.setBackground(Color.WHITE);
        label.setForeground(new Color(35, 44, 55));
        label.setBorder(BorderFactory.createLineBorder(new Color(207, 216, 222)));
        label.setFont(new Font("Dialog", Font.BOLD, 27));
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
        connected = false; myId = 0;
        setFieldsEnabled(true);
        disconnectButton.setEnabled(false); sendButton.setEnabled(false);
        chatField.setEnabled(false); startButton.setEnabled(false);
        startButton.setText("게임 시작");
        playerModel.setRowCount(0);
        playersLabel.setText("참가자 0 / 6");
        phaseLabel.setText("게임 대기");
        setHand(List.of());
        statusLabel.setForeground(Color.DARK_GRAY);
        statusLabel.setText(message);
    }

    public void setTable(int hostId, String phase, List<String[]> players) {
        playerModel.setRowCount(0);
        for (String[] player : players) {
            int id = Integer.parseInt(player[0]);
            String role = id == hostId ? "방장" : "참가자";
            if (id == myId) { role += " · 나"; }
            playerModel.addRow(new Object[] {player[1], player[2], role});
        }
        playersLabel.setText("참가자 " + players.size() + " / 6");
        boolean dealt = phase.equals("DEALT");
        phaseLabel.setText(dealt ? "개인 카드 배분 완료" : "게임 대기");
        statusLabel.setForeground(Color.DARK_GRAY);
        statusLabel.setText(dealt ? "개인 카드가 배분되었습니다. 내 카드를 확인하세요."
                : players.size() < 2 ? "참가자가 2명 이상이면 시작할 수 있습니다."
                : myId == hostId ? "방장입니다. 게임 시작을 눌러 주세요."
                : "방장이 게임을 시작할 때까지 기다려 주세요.");
        startButton.setText(dealt ? "다시 배분" : "게임 시작");
        startButton.setEnabled(connected && myId == hostId && players.size() >= 2);
    }

    public void setHand(List<Card> hand) {
        for (int i = 0; i < handLabels.length; i++) {
            Card card = i < hand.size() ? hand.get(i) : null;
            handLabels[i].setText(card == null ? "?" : "<html><center><span style='font-size:11pt'>"
                    + card.getSuitName() + "</span><br>" + card.getRankText() + "</center></html>");
            handLabels[i].setForeground(card != null && card.isRed()
                    ? new Color(179, 44, 52) : new Color(35, 44, 55));
        }
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
