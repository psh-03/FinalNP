package poker.client;

import javax.swing.JPanel;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * [클라이언트 / 화면 그리기] 서버에서 전달받은 상태를 카드 테이블 모양으로 그린다.
 * JFrame 창 자체가 아닌 JPanel 부품이며, ClientMain의 중앙에 배치된다.
 * 카드 선택·승패 계산을 하지 않는다. 서버가 숨긴 카드 값은 빈 문자열로 전달된다.
 * 처음에는 setState() → paintComponent() → player()/card() 순서만 이해하면 된다.
 * 색상과 좌표 수치는 화면 디자인 설정이므로 네트워크 구조를 이해한 뒤 읽어도 된다.
 */
public final class TablePanel extends JPanel {
    // JPanel 상속에 따른 직렬화 버전 필드다. 게임 메시지 전송과는 관계없다.
    private static final long serialVersionUID = 1L;
    // 그릴 때 읽을 마지막 상태를 보관한다. 초기에는 빈 Map이라 빈 자리와 대기 안내가 표시된다.
    private Map<String, String> state = Collections.emptyMap();
    private final Color gold = new Color(231, 195, 118);
    // 패널의 선호 크기와 기본 배경색을 정한다. 최종 크기는 바깥 창의 레이아웃에 따라 달라질 수 있다.
    public TablePanel() { setPreferredSize(new Dimension(1100, 510)); setBackground(new Color(16, 26, 33)); }
    // ClientMain.message()가 EDT에서 호출한다. 받은 Map을 복사해 보관한다.
    // repaint()는 즉시 그리는 호출이 아니라 Swing에게 화면 갱신이 필요하다고 알리는 요청이다.
    public void setState(Map<String, String> state) {
        this.state = new HashMap<String, String>(state); repaint();
    }
    // 문자열 상태 읽기 도우미. 키가 없으면 기본값을 써서 첫 화면도 그릴 수 있게 한다.
    private String s(String key, String fallback) { return state.containsKey(key) ? state.get(key) : fallback; }
    // 숫자 상태 읽기 도우미. UI 표시 목적이며 서버의 규칙 검증과는 별개다.
    private int n(String key, int fallback) {
        try { return Integer.parseInt(state.get(key)); } catch (Exception e) { return fallback; }
    }
    // Swing이 다시 그릴 때 호출하는 메서드를 재정의한다. 프로그램이 반복문으로 이 메서드를 직접 부르지 않는다.
    @Override protected void paintComponent(Graphics graphics) {
        // 부모 패널의 기본 그리기를 먼저 수행하여 이전 화면을 정리한다.
        super.paintComponent(graphics);
        // 그리기 도구를 복사해 사용한다. 색·선 굵기 변경이 호출자의 도구에 남지 않게 하고 마지막에 dispose한다.
        Graphics2D g = (Graphics2D) graphics.create();
        // 곡선과 글자 가장자리를 부드럽게 표시하는 설정이다.
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // 현재 패널 크기 기준으로 좌표를 계산해 창 크기가 바뀌어도 가운데 배치가 유지되게 한다.
        int w = getWidth(), h = getHeight();
        // 먼저 배경, 다음 테이블 테두리와 천을 그린다. setColor/setPaint 이후의 draw/fill에 그 색이 적용된다.
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
        // 진행 중에는 현재 팟, 핸드가 끝났으면 직전 정산 전 팟을 표시한다.
        String pot = "POT  " + s("pot", "0");
        if (s("phase", "").equals("FINISHED")) pot = "LAST POT  " + s("lastPot", "0");
        center(g, pot, w / 2, h / 2 - 50);
        // 쉼표로 구분된 공유 카드 코드를 나누어 최대 5장을 그린다. 아직 안 열린 자리는 빈 카드 테두리로 표시한다.
        String[] board = s("board", "").isEmpty() ? new String[0] : s("board", "").split(",");
        for (int i = 0; i < 5; i++) card(g, w / 2 - 147 + i * 60, h / 2 - 31, 52, 72, i < board.length ? board[i] : "", false);
        g.setColor(new Color(202, 217, 207)); g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        String phase = s("phase", "WAITING");
        String label = phase.equals("WAITING") ? "접속 후 2명 이상이면 방장이 시작할 수 있어요"
            : phase.equals("FINISHED") ? "핸드 종료 · 아래 기록에서 결과를 확인하세요"
            : "HAND #" + s("hand", "0") + "  /  " + phase + "  /  BLINDS 10·20";
        center(g, label, w / 2, h / 2 + 68);
        // [자리 배치] 각 클라이언트에서 자기 자리를 항상 아래쪽에 표시하려고 표시 순서만 회전한다.
        // 실제 좌석 번호나 서버의 진행 순서를 바꾸는 것은 아니다.
        int you = n("you", 0);
        for (int offset = 0; offset < 6; offset++) {
            int seat = (you + offset) % 6;
            // 원을 6개로 나누면 60도씩이다. cos/sin과 가로/세로 반지름으로 타원 위의 좌석 중심을 구한다.
            double angle = Math.toRadians(90 + offset * 60);
            int cx = w / 2 + (int) (Math.cos(angle) * (w * .36));
            int cy = h / 2 + (int) (Math.sin(angle) * (h * .35));
            player(g, seat, cx - 98, cy - 47, seat == you);
        }
        // create()로 복사한 그래픽 자원을 사용 후 정리한다.
        g.dispose();
    }
    // 좌석 하나의 이름·칩·현재 베팅·개인 카드·딜러/블라인드 표시를 그린다.
    private void player(Graphics2D g, int seat, int x, int y, boolean self) {
        // 서버 상태에서 p0.name, p0.chips처럼 좌석별 필드를 읽기 위한 접두사다.
        String p = "p" + seat + ".";
        boolean occupied = state.containsKey(p + "name");
        // 현재 차례이면 테두리를 금색으로 강조한다.
        boolean active = occupied && n("actor", -1) == seat;
        g.setColor(new Color(0, 0, 0, 45)); g.fillRoundRect(x + 2, y + 4, 196, 94, 18, 18);
        g.setColor(active ? new Color(49, 66, 62) : new Color(28, 42, 49));
        g.fillRoundRect(x, y, 196, 94, 18, 18);
        g.setStroke(new BasicStroke(active ? 2.5f : 1));
        g.setColor(active ? gold : new Color(69, 89, 92)); g.drawRoundRect(x, y, 196, 94, 18, 18);
        // 이름 정보가 없는 좌석은 빈 자리로 그린 뒤 이 메서드를 끝낸다.
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
        // 카드가 빈 문자열이면 아직 전달되지 않은 비공개 패다. 그 경우 뒷면(?) 그림을 사용한다.
        String hole = s(p + "cards", "");
        boolean inHand = s(p + "inHand", "false").equals("true");
        String[] cards = hole.isEmpty() ? new String[0] : hole.split(",");
        if (inHand) for (int i = 0; i < 2; i++)
            card(g, x + 120 + i * 33, y + 29, 28, 37, i < cards.length ? cards[i] : "", cards.length == 0);
        // D는 딜러, SB/BB는 스몰/빅 블라인드다. 2인전에서는 D와 SB가 같은 좌석일 수 있다.
        String badge = seat == n("dealer", -1) ? "D" : "";
        if (seat == n("sb", -1)) badge += " SB";
        if (seat == n("bb", -1)) badge += " BB";
        if (!badge.isEmpty()) {
            g.setFont(new Font("SansSerif", Font.BOLD, 10)); g.setColor(gold);
            g.drawString(badge.trim(), x + 147, y + 19);
        }
    }
    // 카드 한 장의 배경·숫자·무늬를 그린다. w/h가 작으면 좌석의 개인 카드, 크면 중앙의 공유 카드다.
    private void card(Graphics2D g, int x, int y, int w, int h, String code, boolean back) {
        Shape shape = new RoundRectangle2D.Float(x, y, w, h, 7, 7);
        // 값이 없는 카드는 back=true일 때 뒷면, false일 때 아직 안 열린 빈 공유 카드 칸이다.
        if (code.isEmpty()) {
            g.setColor(back ? new Color(58, 89, 117) : new Color(11, 49, 40, 150)); g.fill(shape);
            g.setColor(new Color(100, 137, 128)); g.setStroke(new BasicStroke(1)); g.draw(shape);
            if (back) { g.setFont(new Font("SansSerif", Font.BOLD, 17)); g.setColor(new Color(154, 186, 202)); center(g, "?", x + w / 2, y + h / 2 + 6); }
            return;
        }
        g.setColor(new Color(245, 244, 232)); g.fill(shape);
        // 코드 첫 글자는 숫자, 둘째 글자는 무늬다. H/D는 빨간색, S/C는 어두운색으로 표시한다.
        char suit = code.charAt(1);
        String symbol = suit == 'S' ? "♠" : suit == 'H' ? "♥" : suit == 'D' ? "♦" : "♣";
        g.setColor(suit == 'H' || suit == 'D' ? new Color(177, 52, 52) : new Color(26, 42, 50));
        g.setFont(new Font("SansSerif", Font.BOLD, w < 40 ? 12 : 19));
        g.drawString(code.charAt(0) == 'T' ? "10" : code.substring(0, 1), x + 4, y + (w < 40 ? 14 : 23));
        g.setFont(new Font("SansSerif", Font.PLAIN, w < 40 ? 18 : 30));
        center(g, symbol, x + w / 2, y + h - (w < 40 ? 4 : 10));
    }
    // 글자의 실제 픽셀 폭 절반을 x에서 빼서 가로 중앙에 놓는다. baseline은 글자 기준선의 y좌표다.
    private static void center(Graphics2D g, String text, int x, int baseline) {
        g.drawString(text, x - g.getFontMetrics().stringWidth(text) / 2, baseline);
    }
    // 이름이 칸보다 길면 끝을 줄이고 …을 붙인다. 문자열 길이보다 실제 그려지는 픽셀 폭으로 판단한다.
    private static String ellipsize(Graphics2D g, String text, int max) {
        if (g.getFontMetrics().stringWidth(text) <= max) return text;
        while (!text.isEmpty() && g.getFontMetrics().stringWidth(text + "…") > max) text = text.substring(0, text.length() - 1);
        return text + "…";
    }
}
