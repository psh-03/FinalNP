import java.util.ArrayList;
import java.util.List;

/** 통신과 화면에서 공유하는 공개 상태. 담당: 송준석, 화면 연결: 표신현
 * 개인 카드는 넣지 않는다. 한 줄에 상태 전체를 보내 중간 상태가 섞이지 않게 한다.
 */
public final class GameState {
    // record는 값을 보관하는 자료형이다. id(), chips() 등 접근자가 자동 생성된다.
    public record Seat(int id, String nickname, int chips, int bet, String status) { }
    public final long revision;
    public final int hostId, dealerId, smallBlindId, bigBlindId, turnId;
    public final String phase;
    public final int pot, currentBet, minRaise;
    public final List<Seat> seats;
    public final List<Card> board;

    public GameState(long revision, int hostId, int dealerId, int smallBlindId, int bigBlindId,
            String phase, int turnId, int pot, int currentBet, int minRaise,
            List<Seat> seats, List<Card> board) {
        this.revision = revision; this.hostId = hostId; this.dealerId = dealerId;
        this.smallBlindId = smallBlindId; this.bigBlindId = bigBlindId;
        this.phase = phase; this.turnId = turnId; this.pot = pot;
        this.currentBet = currentBet; this.minRaise = minRaise;
        this.seats = List.copyOf(seats); this.board = List.copyOf(board);
    }
    public boolean running() {
        return phase.equals("PREFLOP") || phase.equals("FLOP") || phase.equals("TURN") || phase.equals("RIVER");
    }
    public Seat find(int id) {
        for (Seat seat : seats) { if (seat.id() == id) { return seat; } }
        return null;
    }
    public String toWire() {
        StringBuilder s = new StringBuilder("/STATE ");
        s.append(revision).append(' ').append(hostId).append(' ').append(dealerId).append(' ')
                .append(smallBlindId).append(' ').append(bigBlindId).append(' ').append(phase).append(' ')
                .append(turnId).append(' ').append(pot).append(' ').append(currentBet).append(' ')
                .append(minRaise).append(' ').append(seats.size());
        for (Seat p : seats) {
            s.append(' ').append(p.id()).append(',').append(p.nickname()).append(',')
                    .append(p.chips()).append(',').append(p.bet()).append(',').append(p.status());
        }
        s.append(' ');
        if (board.isEmpty()) { s.append('-'); }
        else {
            for (int i = 0; i < board.size(); i++) {
                if (i > 0) { s.append(','); }
                s.append(board.get(i).toWire());
            }
        }
        return s.toString();
    }
    public static GameState parse(String line) {
        String[] f = line.split(" ");
        if (!f[0].equals("/STATE") || f.length < 13) { throw new IllegalArgumentException("상태 형식 오류"); }
        int count = Integer.parseInt(f[11]);
        if (count < 0 || count > 6 || f.length != count + 13) { throw new IllegalArgumentException("인원 형식 오류"); }
        List<Seat> seats = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String[] p = f[i + 12].split(",");
            if (p.length != 5) { throw new IllegalArgumentException("참가자 형식 오류"); }
            seats.add(new Seat(Integer.parseInt(p[0]), p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3]), p[4]));
        }
        List<Card> board = new ArrayList<>();
        if (!f[12 + count].equals("-")) {
            for (String card : f[12 + count].split(",")) { board.add(Card.fromWire(card)); }
        }
        return new GameState(Long.parseLong(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]),
                Integer.parseInt(f[4]), Integer.parseInt(f[5]), f[6], Integer.parseInt(f[7]),
                Integer.parseInt(f[8]), Integer.parseInt(f[9]), Integer.parseInt(f[10]), seats, board);
    }
    public static String phaseText(String phase) {
        return switch (phase) {
            case "PREFLOP" -> "프리플롭";
            case "FLOP" -> "플롭";
            case "TURN" -> "턴";
            case "RIVER" -> "리버";
            case "SHOWDOWN" -> "리버 종료 · 판정 대기";
            case "FINISHED" -> "폴드 승리 · 판 종료";
            default -> "게임 대기";
        };
    }
}
