import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 서버에서만 실행하는 게임 규칙. 담당: 표신현
 * ServerMain은 synchronized(table) 안에서 이 객체를 사용한다.
 * 3주차: 일반 베팅과 리버까지 구현. 족보 비교와 올인은 아직 지원하지 않는다.
 */
public final class GameTable {
    public static final int MAX_PLAYERS = 6;
    public static final int SMALL_BLIND = 10;
    public static final int BIG_BLIND = 20;
    public enum Phase { WAITING, PREFLOP, FLOP, TURN, RIVER, SHOWDOWN, FINISHED }

    public static final class Player {
        public final int id;
        public final String nickname;
        public int chips = 1000;
        public int bet;             // 이번 베팅 단계에서 낸 총액
        public int contribution;    // 이번 판 전체에서 낸 총액
        public boolean connected = true;
        public boolean inHand;
        public boolean folded;
        private final List<Card> hand = new ArrayList<>();
        Player(int id, String nickname) { this.id = id; this.nickname = nickname; }
    }

    private final Map<Integer, Player> players = new LinkedHashMap<>();
    // 아직 행동해야 하는 사람. 레이즈가 나오면 상대들이 다시 행동해야 한다.
    private final Set<Integer> pending = new LinkedHashSet<>();
    private final List<Card> board = new ArrayList<>();
    private final List<String> notices = new ArrayList<>();
    private int nextId = 1;
    private long revision;
    private Phase phase = Phase.WAITING;
    private int dealerId, smallBlindId, bigBlindId, turnId;
    private int pot, currentBet, minRaise = BIG_BLIND;
    private Deck deck;

    public Player addPlayer(String nickname) {
        if (!nickname.matches("[\\p{L}\\p{N}_]{1,12}")) {
            throw new IllegalArgumentException("닉네임은 공백 없는 문자·숫자·밑줄 1~12자로 입력하세요.");
        }
        if (players.size() >= MAX_PLAYERS) {
            throw new IllegalArgumentException("테이블이 가득 찼습니다. 진행 중 퇴장한 자리는 판이 끝나면 비워집니다.");
        }
        for (Player p : players.values()) {
            if (p.nickname.equalsIgnoreCase(nickname)) { throw new IllegalArgumentException("이미 사용 중인 닉네임입니다."); }
        }
        Player player = new Player(nextId++, nickname);
        players.put(player.id, player);
        revision++;
        notices.add(nickname + "님이 접속했습니다." + (isRunning() ? " 다음 판부터 참가합니다." : ""));
        return player;
    }

    public void removePlayer(int id) {
        Player p = players.get(id);
        if (p == null) { return; }
        p.connected = false;
        notices.add(p.nickname + "님이 나갔습니다.");
        if (getHostId() == 0) {
            players.clear(); board.clear(); pending.clear();
            phase = Phase.WAITING;
            pot = currentBet = turnId = dealerId = smallBlindId = bigBlindId = 0;
        } else if (isRunning() && p.inHand && !p.folded) {
            // 현재 차례가 아닌 사람이 나가도 폴드한다. 다른 사람의 차례는 유지한다.
            p.folded = true;
            pending.remove(id);
            advanceAfter(id);
        } else if (!p.inHand) {
            players.remove(id);
        }
        // 진행 중에는 기여액을 보관한다. 판이 끝나면 퇴장 자리를 비워 재입장을 허용한다.
        if (!isRunning()) { clearDeparted(); }
        revision++;
    }

    public void requireRevision(long received) {
        if (received != revision) {
            throw new IllegalStateException("화면 상태가 변경되었습니다. 현재 차례를 확인하고 다시 눌러 주세요.");
        }
    }

    public void startGame(int requesterId) {
        if (requesterId != getHostId()) { throw new IllegalStateException("방장만 시작할 수 있습니다."); }
        if (isRunning()) { throw new IllegalStateException("진행 중에는 다시 시작할 수 없습니다."); }
        long eligible = players.values().stream().filter(p -> p.connected
                && p.chips + (phase == Phase.SHOWDOWN ? p.contribution : 0) > BIG_BLIND).count();
        if (eligible < 2) { throw new IllegalStateException("칩이 21개 이상인 참가자가 두 명 필요합니다."); }
        if (phase == Phase.SHOWDOWN) {
            // 족보 미구현 상태에서 승자를 임의로 정하지 않는다.
            refundContributions();
            notices.add("다음 연습판을 위해 미정산 베팅액을 각 참가자에게 돌려주었습니다.");
        }
        players.values().removeIf(p -> !p.connected);
        board.clear(); pending.clear();
        pot = currentBet = 0; minRaise = BIG_BLIND;
        deck = new Deck();
        for (Player p : players.values()) {
            p.hand.clear(); p.bet = p.contribution = 0; p.folded = false;
            p.inHand = p.chips > BIG_BLIND;
        }
        List<Player> active = remaining();
        dealerId = nextAfter(dealerId, active);
        smallBlindId = active.size() == 2 ? dealerId : nextAfter(dealerId, active);
        bigBlindId = nextAfter(smallBlindId, active);
        // 딜러 다음 자리부터 한 장씩 두 바퀴 배분한다.
        int seat = dealerId;
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < active.size(); i++) {
                seat = nextAfter(seat, active);
                players.get(seat).hand.add(deck.draw());
            }
        }
        pay(players.get(smallBlindId), SMALL_BLIND);
        pay(players.get(bigBlindId), BIG_BLIND);
        currentBet = BIG_BLIND;
        phase = Phase.PREFLOP;
        for (Player p : active) { pending.add(p.id); }
        // 두 명은 딜러(SB)가 먼저, 세 명 이상이면 BB 다음 사람이 먼저 행동한다.
        turnId = nextAfter(bigBlindId, active);
        notices.add("새 판 시작. 스몰 블라인드 10, 빅 블라인드 20입니다.");
        revision++;
    }

    public void act(int playerId, String action, int amount) {
        if (!isRunning() || playerId != turnId) { throw new IllegalStateException("현재 본인의 차례가 아닙니다."); }
        Player p = players.get(playerId);
        int toCall = currentBet - p.bet;
        switch (action) {
            case "CHECK" -> {
                if (toCall != 0) { throw new IllegalStateException("맞춰야 할 금액이 있어 체크할 수 없습니다."); }
                notices.add(p.nickname + ": 체크");
            }
            case "CALL" -> {
                if (toCall <= 0) { throw new IllegalStateException("콜할 금액이 없습니다. 체크하세요."); }
                requirePayment(p, toCall); pay(p, toCall);
                notices.add(p.nickname + ": 콜 " + toCall);
            }
            case "BET", "RAISE" -> {
                if (action.equals("BET") != (currentBet == 0)) {
                    throw new IllegalStateException(currentBet == 0 ? "첫 베팅은 BET입니다." : "이미 베팅이 있어 RAISE를 사용하세요.");
                }
                // amount는 추가 납부액이 아니라 이번 단계에서 맞출 총액이다.
                int minimum = currentBet == 0 ? BIG_BLIND : currentBet + minRaise;
                if (amount < minimum) { throw new IllegalStateException("이번 단계 총액을 " + minimum + " 이상으로 입력하세요."); }
                requirePayment(p, amount - p.bet);
                int raiseSize = amount - currentBet;
                pay(p, amount - p.bet);
                currentBet = amount; minRaise = raiseSize;
                pending.clear();
                for (Player other : remaining()) { pending.add(other.id); }
                notices.add(p.nickname + (action.equals("BET") ? ": 베트 총액 " : ": 레이즈 총액 ") + amount);
            }
            case "FOLD" -> { p.folded = true; notices.add(p.nickname + ": 폴드"); }
            default -> throw new IllegalArgumentException("지원하지 않는 행동입니다.");
        }
        pending.remove(playerId);
        // 방금 행동한 사람 다음으로 차례를 넘길 수 있도록 먼저 비운다.
        turnId = 0;
        advanceAfter(playerId);
        if (!isRunning()) { clearDeparted(); }
        revision++;
    }

    private void requirePayment(Player p, int amount) {
        if (amount <= 0) { throw new IllegalStateException("베팅 금액을 확인하세요."); }
        if (amount >= p.chips) {
            throw new IllegalStateException("3주차는 올인을 지원하지 않습니다. 보유 칩보다 적은 금액만 낼 수 있습니다.");
        }
    }

    private void pay(Player p, int amount) {
        p.chips -= amount; p.bet += amount; p.contribution += amount; pot += amount;
    }

    private void advanceAfter(int lastId) {
        List<Player> active = remaining();
        if (active.size() == 1) {
            Player winner = active.get(0);
            int won = pot;
            winner.chips += pot;
            pot = 0; turnId = 0; pending.clear();
            for (Player p : players.values()) { p.contribution = 0; }
            phase = Phase.FINISHED;
            notices.add(winner.nickname + "님이 혼자 남아 판돈 " + won + "개를 받았습니다.");
            return;
        }
        if (pending.isEmpty()) { nextStreet(); return; }
        // 다른 사람의 퇴장 때문에 현재 차례까지 건너뛰면 안 된다.
        if (turnId != 0 && pending.contains(turnId)) { return; }
        List<Player> needAction = new ArrayList<>();
        for (Player p : active) { if (pending.contains(p.id)) { needAction.add(p); } }
        turnId = nextAfter(lastId, needAction);
    }

    private void nextStreet() {
        if (phase == Phase.RIVER) {
            phase = Phase.SHOWDOWN; turnId = 0;
            notices.add("리버 베팅 종료. 족보 판정은 4주차 구현 예정입니다. 다음 연습판 시작 시 베팅액을 돌려줍니다.");
            return;
        }
        phase = switch (phase) {
            case PREFLOP -> Phase.FLOP;
            case FLOP -> Phase.TURN;
            case TURN -> Phase.RIVER;
            default -> throw new IllegalStateException("잘못된 게임 단계입니다.");
        };
        deck.draw(); // 공용 카드를 열기 전에 한 장을 버리는 번 카드
        int count = phase == Phase.FLOP ? 3 : 1;
        for (int i = 0; i < count; i++) { board.add(deck.draw()); }
        currentBet = 0; minRaise = BIG_BLIND;
        for (Player p : players.values()) { p.bet = 0; }
        pending.clear();
        List<Player> active = remaining();
        for (Player p : active) { pending.add(p.id); }
        turnId = nextAfter(dealerId, active);
        notices.add(GameState.phaseText(phase.name()) + " 시작. 공용 카드 " + board.size() + "장 공개.");
    }

    private void refundContributions() {
        for (Player p : players.values()) { p.chips += p.contribution; p.contribution = 0; }
        pot = 0;
    }

    private void clearDeparted() {
        for (Player p : players.values()) {
            if (!p.connected && phase == Phase.SHOWDOWN) {
                // 판정 미구현 상태에서 퇴장한 사람의 미정산액을 반환한 것으로 처리한다.
                // 그 금액을 pot에서 빼야 나머지 사람에게 중복 반환되지 않는다.
                pot -= p.contribution;
                p.chips += p.contribution;
                p.contribution = 0;
            }
        }
        players.values().removeIf(p -> !p.connected);
    }

    private List<Player> remaining() {
        List<Player> result = new ArrayList<>();
        for (Player p : players.values()) { if (p.inHand && !p.folded) { result.add(p); } }
        return result;
    }

    // ID는 입장 순서대로 증가한다. 마지막 자리 다음에는 첫 자리로 돌아간다.
    private int nextAfter(int id, List<Player> candidates) {
        for (Player p : candidates) { if (p.id > id) { return p.id; } }
        return candidates.isEmpty() ? 0 : candidates.get(0).id;
    }

    public boolean isRunning() {
        return phase == Phase.PREFLOP || phase == Phase.FLOP || phase == Phase.TURN || phase == Phase.RIVER;
    }
    public int getHostId() {
        for (Player p : players.values()) { if (p.connected) { return p.id; } }
        return 0;
    }
    public List<Card> getHand(int id) {
        Player p = players.get(id);
        return p == null ? List.of() : new ArrayList<>(p.hand);
    }
    public GameState snapshot() {
        List<GameState.Seat> seats = new ArrayList<>();
        for (Player p : players.values()) {
            String status = !p.connected ? "LEFT" : !p.inHand ? "WAIT" : p.folded ? "FOLD" : "PLAY";
            seats.add(new GameState.Seat(p.id, p.nickname, p.chips, p.bet, status));
        }
        return new GameState(revision, getHostId(), dealerId, smallBlindId, bigBlindId,
                phase.name(), turnId, pot, currentBet, minRaise, seats, board);
    }
    public List<String> drainNotices() {
        List<String> result = new ArrayList<>(notices); notices.clear(); return result;
    }
}
