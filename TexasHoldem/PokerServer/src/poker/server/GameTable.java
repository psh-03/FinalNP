package poker.server;

import poker.common.Cards;
import poker.common.Wire;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** All methods run on the server's ONE game thread. Socket threads only enqueue commands. */
final class GameTable {
    static final int MAX_PLAYERS = 6, BUY_IN = 1000, SMALL_BLIND = 10, BIG_BLIND = 20;
    interface Peer { void send(String frame); }
    static final class Player {
        final int seat;
        final String name;
        final Peer peer;
        boolean connected = true, inHand, folded;
        int chips = BUY_IN, street, contributed, lastActedBet = -1;
        final List<Integer> hole = new ArrayList<Integer>();
        Player(int seat, String name, Peer peer) { this.seat = seat; this.name = name; this.peer = peer; }
        boolean canAct() { return inHand && !folded && chips > 0; }
    }
    final Player[] players = new Player[MAX_PLAYERS];
    final List<Integer> board = new ArrayList<Integer>();
    final Set<Integer> pending = new HashSet<Integer>();
    private final ScheduledExecutorService loop;
    private final int turnSeconds;
    private final Random random;
    private final Deque<Integer> deck = new ArrayDeque<Integer>();
    String phase = "WAITING", result = "두 명 이상 접속하면 방장이 시작할 수 있습니다.";
    int dealer = -1, smallBlind = -1, bigBlind = -1, actor = -1;
    int hand, currentBet, lastFullRaise = BIG_BLIND, lastPot;
    long turnToken, revision, deadline;
    boolean reveal;

    GameTable(ScheduledExecutorService loop, int turnSeconds) {
        this(loop, turnSeconds, new SecureRandom());
    }
    GameTable(ScheduledExecutorService loop, int turnSeconds, Random random) {
        this.loop = loop; this.turnSeconds = turnSeconds; this.random = random;
    }
    boolean running() { return !phase.equals("WAITING") && !phase.equals("FINISHED"); }
    private Player find(Peer peer) {
        for (Player p : players) if (p != null && p.peer == peer) return p;
        return null;
    }
    private int host() {
        for (Player p : players) if (p != null && p.connected) return p.seat;
        return -1;
    }
    private int funded() {
        int n = 0;
        for (Player p : players) if (p != null && p.connected && p.chips > 0) n++;
        return n;
    }
    void handle(Peer peer, Map<String, String> m) {
        try {
            String type = Wire.required(m, "type");
            if (type.equals("PING")) { peer.send(Wire.message("PONG")); return; }
            Player p = find(peer);
            if (type.equals("JOIN")) {
                if (p != null) throw new IllegalArgumentException("이미 접속했습니다.");
                join(peer, Wire.required(m, "name"));
                return;
            }
            if (p == null || !p.connected) throw new IllegalArgumentException("먼저 JOIN이 필요합니다.");
            if (type.equals("CHAT")) {
                String text = Wire.required(m, "text").trim();
                if (text.isEmpty() || text.length() > 200 || hasControl(text))
                    throw new IllegalArgumentException("채팅은 줄바꿈 없이 1~200자로 입력하세요.");
                log(p.name + ": " + text);
            } else if (type.equals("START")) {
                if (p.seat != host()) throw new IllegalArgumentException("방장만 시작할 수 있습니다.");
                start();
            } else if (type.equals("REBUY")) {
                if (running() || p.chips != 0) throw new IllegalArgumentException("라운드가 끝나고 칩이 0일 때만 충전할 수 있습니다.");
                p.chips = BUY_IN;
                log(p.name + " 님이 연습용 칩을 충전했습니다.");
                broadcast();
            } else if (type.equals("ACT")) {
                if (!running() || p.seat != actor) throw new IllegalArgumentException("현재 본인 차례가 아닙니다.");
                if (Wire.number(m, "hand") != hand || Long.parseLong(Wire.required(m, "turn")) != turnToken)
                    throw new IllegalArgumentException("이전 차례의 요청입니다. 현재 화면에서 다시 선택하세요.");
                String action = Wire.required(m, "action");
                int target = action.equals("RAISE") ? Wire.number(m, "amount") : 0;
                act(p, action, target);
            } else throw new IllegalArgumentException("지원하지 않는 요청입니다.");
        } catch (IllegalArgumentException e) {
            peer.send(Wire.message("ERROR", "text", e.getMessage() == null ? "잘못된 요청입니다." : e.getMessage()));
            Player p = find(peer);
            if (p != null) peer.send(snapshot(p));
        }
    }
    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true;
        return false;
    }
    private void join(Peer peer, String input) {
        String name = input.trim();
        if (!name.matches("[\\p{L}\\p{N}_ -]{1,16}"))
            throw new IllegalArgumentException("이름은 한글·영문·숫자·공백·밑줄·하이픈으로 1~16자 입력하세요.");
        for (Player p : players) if (p != null && p.name.equalsIgnoreCase(name))
            throw new IllegalArgumentException("이미 사용 중인 이름입니다.");
        int seat = -1;
        for (int i = 0; i < MAX_PLAYERS; i++) if (players[i] == null) { seat = i; break; }
        if (seat == -1) throw new IllegalArgumentException("테이블이 가득 찼습니다. 최대 6명입니다.");
        players[seat] = new Player(seat, name, peer);
        log(name + " 님이 접속했습니다." + (running() ? " 다음 핸드부터 참가합니다." : ""));
        broadcast();
    }
    void disconnected(Peer peer) {
        Player p = find(peer);
        if (p == null || !p.connected) return;
        p.connected = false;
        log(p.name + " 님의 연결이 종료되었습니다.");
        if (!running() || !p.inHand) players[p.seat] = null;
        else if (actor == p.seat) {
            // Preserve all-in hands; disconnected players check/fold only on their own turn.
            act(p, p.street >= currentBet ? "CHECK" : "FOLD", 0);
            return;
        }
        broadcast();
    }
    private int nextInHand(int after) {
        for (int k = 1; k <= MAX_PLAYERS; k++) {
            int i = (after + k + MAX_PLAYERS) % MAX_PLAYERS;
            if (players[i] != null && players[i].inHand) return i;
        }
        throw new IllegalStateException("no players");
    }
    private void start() {
        if (running()) throw new IllegalArgumentException("진행 중인 핸드가 있습니다.");
        if (funded() < 2) throw new IllegalArgumentException("칩이 있는 접속자 2명 이상이 필요합니다.");
        hand++; reveal = false; board.clear(); result = ""; lastPot = 0;
        actor = -1; currentBet = BIG_BLIND; lastFullRaise = BIG_BLIND;
        pending.clear();
        for (int i = 0; i < MAX_PLAYERS; i++) {
            Player p = players[i];
            if (p == null) continue;
            if (!p.connected) { players[i] = null; continue; }
            p.inHand = p.chips > 0; p.folded = false; p.street = 0; p.contributed = 0;
            p.lastActedBet = -1; p.hole.clear();
        }
        dealer = nextInHand(dealer);
        smallBlind = funded() == 2 ? dealer : nextInHand(dealer);
        bigBlind = nextInHand(smallBlind);
        List<Integer> shuffled = new ArrayList<Integer>();
        for (int card = 0; card < 52; card++) shuffled.add(card);
        Collections.shuffle(shuffled, random); deck.clear(); deck.addAll(shuffled);
        for (int round = 0; round < 2; round++) {
            int seat = dealer;
            do { seat = nextInHand(seat); players[seat].hole.add(deck.removeFirst()); } while (seat != dealer);
        }
        pay(players[smallBlind], Math.min(SMALL_BLIND, players[smallBlind].chips));
        pay(players[bigBlind], Math.min(BIG_BLIND, players[bigBlind].chips));
        phase = "PREFLOP";
        for (Player p : players) if (p != null && p.canAct()) pending.add(p.seat);
        log("핸드 #" + hand + " 시작 · SB " + players[smallBlind].name + " / BB " + players[bigBlind].name);
        progress(bigBlind);
    }
    private static void pay(Player p, int amount) {
        if (amount < 0 || amount > p.chips) throw new IllegalArgumentException("칩 범위를 확인하세요.");
        p.chips -= amount; p.street += amount; p.contributed += amount;
    }
    private boolean mayRaise(Player p) {
        if (!p.canAct() || p.street + p.chips <= currentBet) return false;
        boolean opponentCanAct = false;
        for (Player other : players) if (other != null && other != p && other.canAct()) opponentCanAct = true;
        // A check before an opening bet does not close the player's right to raise.
        return opponentCanAct && (p.lastActedBet <= 0 || currentBet - p.lastActedBet >= lastFullRaise);
    }
    private void act(Player p, String action, int target) {
        int owed = Math.max(0, currentBet - p.street);
        String description;
        if (action.equals("FOLD")) {
            p.folded = true; description = "폴드";
        } else if (action.equals("CHECK")) {
            if (owed != 0) throw new IllegalArgumentException("콜해야 할 금액이 있어 체크할 수 없습니다.");
            description = "체크";
        } else if (action.equals("CALL")) {
            if (owed == 0) throw new IllegalArgumentException("체크를 선택하세요.");
            int amount = Math.min(owed, p.chips); pay(p, amount); description = "콜 " + amount;
        } else if (action.equals("RAISE")) {
            if (!mayRaise(p)) throw new IllegalArgumentException("현재 레이즈할 수 없습니다.");
            if (target <= currentBet || target > p.street + p.chips)
                throw new IllegalArgumentException("레이즈 총액이 허용 범위를 벗어났습니다.");
            int increment = target - currentBet;
            boolean allIn = target == p.street + p.chips;
            if (increment < lastFullRaise && !allIn)
                throw new IllegalArgumentException("최소 레이즈 총액: " + (currentBet + lastFullRaise));
            pay(p, target - p.street);
            if (increment >= lastFullRaise) lastFullRaise = increment;
            currentBet = target;
            for (Player other : players)
                if (other != null && other != p && other.canAct() && other.street < currentBet) pending.add(other.seat);
            description = "베팅/레이즈 총액 " + target;
        } else throw new IllegalArgumentException("행동은 FOLD, CHECK, CALL, RAISE 중 하나여야 합니다.");
        p.lastActedBet = currentBet;
        pending.remove(p.seat); actor = -1;
        log(p.name + " · " + description + (p.chips == 0 && !p.folded ? " (올인)" : ""));
        progress(p.seat);
    }
    private void progress(int after) {
        while (running()) {
            int contenders = 0, actors = 0; Player loneActor = null;
            for (Player p : players) if (p != null && p.inHand && !p.folded) {
                contenders++;
                if (p.canAct()) { actors++; loneActor = p; }
            }
            if (contenders == 1) { finish(false); return; }
            pending.removeIf(i -> players[i] == null || !players[i].canAct());
            if (actors == 1) {
                // No player can contest extra chips: match only actual opposing bets.
                // In particular, a heads-up short all-in BB must not force a full-BB call.
                int opposingBet = 0;
                for (Player p : players) if (p != null && p != loneActor && p.inHand && !p.folded)
                    opposingBet = Math.max(opposingBet, p.street);
                currentBet = Math.min(currentBet, Math.max(loneActor.street, opposingBet));
                if (loneActor.street >= currentBet) pending.clear();
            }
            if (pending.isEmpty()) {
                if (phase.equals("RIVER")) { finish(true); return; }
                deck.removeFirst(); // Burn one before flop/turn/river.
                int count = phase.equals("PREFLOP") ? 3 : 1;
                for (int i = 0; i < count; i++) board.add(deck.removeFirst());
                phase = phase.equals("PREFLOP") ? "FLOP" : phase.equals("FLOP") ? "TURN" : "RIVER";
                currentBet = 0; lastFullRaise = BIG_BLIND; actor = -1;
                for (Player p : players) if (p != null) {
                    p.street = 0; p.lastActedBet = -1;
                    if (p.canAct()) pending.add(p.seat);
                }
                after = dealer;
                log(phase + " · " + Cards.encode(board));
                continue;
            }
            for (int k = 1; k <= MAX_PLAYERS; k++) {
                int i = (after + k + MAX_PLAYERS) % MAX_PLAYERS;
                if (pending.contains(i)) { actor = i; break; }
            }
            Player p = players[actor];
            if (!p.connected) {
                act(p, p.street >= currentBet ? "CHECK" : "FOLD", 0);
                return;
            }
            turnToken++; deadline = System.currentTimeMillis() + turnSeconds * 1000L;
            final long expected = turnToken;
            loop.schedule(() -> {
                if (running() && actor >= 0 && turnToken == expected) {
                    Player timedOut = players[actor];
                    log(timedOut.name + " 님 시간 초과 · 자동 체크/폴드");
                    act(timedOut, timedOut.street >= currentBet ? "CHECK" : "FOLD", 0);
                }
            }, turnSeconds, TimeUnit.SECONDS);
            broadcast(); return;
        }
    }
    private int pot() {
        int amount = 0;
        for (Player p : players) if (p != null) amount += p.contributed;
        return amount;
    }
    private void finish(boolean showdown) {
        actor = -1; deadline = 0; turnToken++; reveal = showdown; lastPot = pot();
        int[] contributions = new int[MAX_PLAYERS];
        boolean[] eligible = new boolean[MAX_PLAYERS];
        long[] strengths = new long[MAX_PLAYERS];
        StringBuilder details = new StringBuilder();
        for (Player p : players) if (p != null && p.inHand) {
            contributions[p.seat] = p.contributed; eligible[p.seat] = !p.folded;
            if (showdown && !p.folded) {
                List<Integer> seven = new ArrayList<Integer>(board); seven.addAll(p.hole);
                HandEvaluator.Rank rank = HandEvaluator.best(seven);
                strengths[p.seat] = rank.score;
                details.append(p.name).append(": ").append(rank.name()).append("  ");
            }
        }
        Pots.Result settlement = Pots.settle(contributions, eligible, strengths, dealer);
        StringBuilder winners = new StringBuilder();
        for (Player p : players) if (p != null) {
            int award = settlement.awards[p.seat], refund = settlement.refunds[p.seat];
            p.chips += award + refund;
            p.street = 0; p.contributed = 0;
            if (award > 0) winners.append(p.name).append(" +").append(award).append("  ");
            if (refund > 0) details.append(p.name).append(" 미콜 금액 반환 ").append(refund).append("  ");
        }
        phase = "FINISHED"; pending.clear(); currentBet = 0;
        result = (showdown ? "쇼다운 · " : "폴드 승리 · ") + winners + "\n" + details;
        log(result.replace('\n', ' '));
        // Release disconnected seats after settling their hand. No account/reconnect in this demo.
        for (int i = 0; i < MAX_PLAYERS; i++)
            if (players[i] != null && !players[i].connected) players[i] = null;
        broadcast();
    }
    private void log(String text) {
        System.out.println("[TABLE] " + text);
        String frame = Wire.message("LOG", "text", text);
        for (Player p : players) if (p != null && p.connected) p.peer.send(frame);
    }
    private void broadcast() {
        revision++;
        for (Player p : players) if (p != null && p.connected) p.peer.send(snapshot(p));
    }
    String snapshot(Player viewer) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("phase", phase); m.put("hand", "" + hand); m.put("revision", "" + revision);
        m.put("you", "" + viewer.seat); m.put("host", "" + host());
        m.put("dealer", "" + dealer); m.put("sb", "" + smallBlind); m.put("bb", "" + bigBlind);
        m.put("actor", "" + actor); m.put("turn", "" + turnToken); m.put("deadline", "" + deadline);
        m.put("pot", "" + pot()); m.put("lastPot", "" + lastPot); m.put("bet", "" + currentBet);
        m.put("board", Cards.encode(board)); m.put("result", result);
        m.put("canStart", "" + (!running() && viewer.seat == host() && funded() >= 2));
        m.put("canRebuy", "" + (!running() && viewer.chips == 0));
        m.put("toCall", "" + Math.min(viewer.chips, Math.max(0, currentBet - viewer.street)));
        m.put("canRaise", "" + (running() && actor == viewer.seat && mayRaise(viewer)));
        m.put("minRaiseTo", "" + (currentBet + lastFullRaise));
        m.put("maxRaiseTo", "" + (viewer.street + viewer.chips));
        for (Player p : players) if (p != null) {
            String prefix = "p" + p.seat + ".";
            m.put(prefix + "name", p.name); m.put(prefix + "chips", "" + p.chips);
            m.put(prefix + "bet", "" + p.street); m.put(prefix + "connected", "" + p.connected);
            m.put(prefix + "inHand", "" + p.inHand);
            String status = !p.inHand ? "대기" : p.folded ? "폴드" : p.chips == 0 ? "올인" : "참가";
            if (!p.connected) status += " / 연결 종료";
            m.put(prefix + "status", status);
            // SECURITY BOUNDARY: never send a different player's private cards before showdown.
            boolean visible = p == viewer || (reveal && p.inHand && !p.folded);
            m.put(prefix + "cards", visible ? Cards.encode(p.hole) : "");
        }
        return Wire.message("STATE", m);
    }
}
