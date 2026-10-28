package poker.client;

import javax.swing.JPanel;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Draws only the redacted state received from the server; contains no game rules. */
public final class TablePanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private Map<String, String> state = Collections.emptyMap();
    private final Color gold = new Color(231, 195, 118);
    public TablePanel() { setPreferredSize(new Dimension(1100, 510)); setBackground(new Color(16, 26, 33)); }
    public void setState(Map<String, String> state) {
        this.state = new HashMap<String, String>(state); repaint();
    }
    private String s(String key, String fallback) { return state.containsKey(key) ? state.get(key) : fallback; }
    private int n(String key, int fallback) {
        try { return Integer.parseInt(state.get(key)); } catch (Exception e) { return fallback; }
    }
    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        g.setPaint(new GradientPaint(0, 0, new Color(19, 36, 44), w, h, new Color(9, 18, 25)));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(76, 68, 49)); g.fillRoundRect(105, 74, w - 210, h - 148, 200, 200);
        g.setPaint(new GradientPaint(0, 70, new Color(29, 95, 76), 0, h, new Color(17, 62, 53)));
        g.fillRoundRect(112, 81, w - 224, h - 162, 190, 190);
        g.setColor(new Color(99, 147, 120)); g.setStroke(new BasicStroke(1));
        g.drawRoundRect(124, 93, w - 248, h - 186, 180, 180);
        g.setFont(new Font("SansSerif", Font.BOLD, 12)); g.setColor(new Color(151, 178, 164));
        center(g, "T E X A S   H O L D ' E M", w / 2, h / 2 - 83);
        g.setFont(new Font("SansSerif", Font.BOLD, 23)); g.setColor(gold);
        String pot = "POT  " + s("pot", "0");
        if (s("phase", "").equals("FINISHED")) pot = "LAST POT  " + s("lastPot", "0");
        center(g, pot, w / 2, h / 2 - 50);
        String[] board = s("board", "").isEmpty() ? new String[0] : s("board", "").split(",");
        for (int i = 0; i < 5; i++) card(g, w / 2 - 147 + i * 60, h / 2 - 31, 52, 72, i < board.length ? board[i] : "", false);
        g.setColor(new Color(202, 217, 207)); g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        String phase = s("phase", "WAITING");
        String label = phase.equals("WAITING") ? "접속 후 2명 이상이면 방장이 시작할 수 있어요"
            : phase.equals("FINISHED") ? "핸드 종료 · 아래 기록에서 결과를 확인하세요"
            : "HAND #" + s("hand", "0") + "  /  " + phase + "  /  BLINDS 10·20";
        center(g, label, w / 2, h / 2 + 68);
        int you = n("you", 0);
        for (int offset = 0; offset < 6; offset++) {
            int seat = (you + offset) % 6;
            double angle = Math.toRadians(90 + offset * 60);
            int cx = w / 2 + (int) (Math.cos(angle) * (w * .36));
            int cy = h / 2 + (int) (Math.sin(angle) * (h * .35));
            player(g, seat, cx - 98, cy - 47, seat == you);
        }
        g.dispose();
    }
    private void player(Graphics2D g, int seat, int x, int y, boolean self) {
        String p = "p" + seat + ".";
        boolean occupied = state.containsKey(p + "name");
        boolean active = occupied && n("actor", -1) == seat;
        g.setColor(new Color(0, 0, 0, 45)); g.fillRoundRect(x + 2, y + 4, 196, 94, 18, 18);
        g.setColor(active ? new Color(49, 66, 62) : new Color(28, 42, 49));
        g.fillRoundRect(x, y, 196, 94, 18, 18);
        g.setStroke(new BasicStroke(active ? 2.5f : 1));
        g.setColor(active ? gold : new Color(69, 89, 92)); g.drawRoundRect(x, y, 196, 94, 18, 18);
        if (!occupied) {
            g.setFont(new Font("SansSerif", Font.PLAIN, 14)); g.setColor(new Color(127, 145, 150));
            center(g, "빈 자리 " + (seat + 1), x + 98, y + 53); return;
        }
        g.setFont(new Font("SansSerif", Font.BOLD, 13)); g.setColor(self ? gold : Color.WHITE);
        String name = s(p + "name", "") + (self ? " (나)" : "");
        g.drawString(ellipsize(g, name, 125), x + 12, y + 23);
        g.setFont(new Font("SansSerif", Font.BOLD, 17)); g.setColor(Color.WHITE);
        g.drawString(s(p + "chips", "0"), x + 12, y + 48);
        g.setFont(new Font("SansSerif", Font.PLAIN, 11)); g.setColor(new Color(169, 191, 189));
        String status = s(p + "status", "대기");
        if (n(p + "bet", 0) > 0) status += " · 베팅 " + s(p + "bet", "0");
        g.drawString(ellipsize(g, status, 172), x + 12, y + 80);
        String hole = s(p + "cards", "");
        boolean inHand = s(p + "inHand", "false").equals("true");
        String[] cards = hole.isEmpty() ? new String[0] : hole.split(",");
        if (inHand) for (int i = 0; i < 2; i++)
            card(g, x + 120 + i * 33, y + 29, 28, 37, i < cards.length ? cards[i] : "", cards.length == 0);
        String badge = seat == n("dealer", -1) ? "D" : "";
        if (seat == n("sb", -1)) badge += " SB";
        if (seat == n("bb", -1)) badge += " BB";
        if (!badge.isEmpty()) {
            g.setFont(new Font("SansSerif", Font.BOLD, 10)); g.setColor(gold);
            g.drawString(badge.trim(), x + 147, y + 19);
        }
    }
    private void card(Graphics2D g, int x, int y, int w, int h, String code, boolean back) {
        Shape shape = new RoundRectangle2D.Float(x, y, w, h, 7, 7);
        if (code.isEmpty()) {
            g.setColor(back ? new Color(58, 89, 117) : new Color(11, 49, 40, 150)); g.fill(shape);
            g.setColor(new Color(100, 137, 128)); g.setStroke(new BasicStroke(1)); g.draw(shape);
            if (back) { g.setFont(new Font("SansSerif", Font.BOLD, 17)); g.setColor(new Color(154, 186, 202)); center(g, "?", x + w / 2, y + h / 2 + 6); }
            return;
        }
        g.setColor(new Color(245, 244, 232)); g.fill(shape);
        char suit = code.charAt(1);
        String symbol = suit == 'S' ? "♠" : suit == 'H' ? "♥" : suit == 'D' ? "♦" : "♣";
        g.setColor(suit == 'H' || suit == 'D' ? new Color(177, 52, 52) : new Color(26, 42, 50));
        g.setFont(new Font("SansSerif", Font.BOLD, w < 40 ? 12 : 19));
        g.drawString(code.charAt(0) == 'T' ? "10" : code.substring(0, 1), x + 4, y + (w < 40 ? 14 : 23));
        g.setFont(new Font("SansSerif", Font.PLAIN, w < 40 ? 18 : 30));
        center(g, symbol, x + w / 2, y + h - (w < 40 ? 4 : 10));
    }
    private static void center(Graphics2D g, String text, int x, int baseline) {
        g.drawString(text, x - g.getFontMetrics().stringWidth(text) / 2, baseline);
    }
    private static String ellipsize(Graphics2D g, String text, int max) {
        if (g.getFontMetrics().stringWidth(text) <= max) return text;
        while (!text.isEmpty() && g.getFontMetrics().stringWidth(text + "…") > max) text = text.substring(0, text.length() - 1);
        return text + "…";
    }
}
