package poker.server;

import poker.common.Cards;
import poker.common.Wire;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Dependency-free behavior tests. Run with run-tests.sh or run-tests.bat. */
public final class SmokeTests {
    private static int assertions;
    private static final ScheduledExecutorService LOOP = Executors.newSingleThreadScheduledExecutor();
    private static final class Mock implements GameTable.Peer {
        Map<String, String> state;
        int errors;
        public void send(String line) {
            Map<String, String> m = Wire.parse(line);
            if ("STATE".equals(m.get("type"))) state = m;
            if ("ERROR".equals(m.get("type"))) errors++;
        }
    }
    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
    private static GameTable game(int n, int... stacks) {
        GameTable t = new GameTable(LOOP, 600, new Random(39));
        for (int i = 0; i < n; i++) {
            t.handle(new Mock(), Wire.parse(Wire.message("JOIN", "name", "P" + i)));
            if (stacks.length > i) t.players[i].chips = stacks[i];
        }
        return t;
    }
    private static void start(GameTable t) { t.handle(t.players[0].peer, Wire.parse(Wire.message("START"))); }
    private static void act(GameTable t, String action, int amount) {
        check(t.actor >= 0, "expected an actor");
        Mock p = (Mock) t.players[t.actor].peer;
        int errors = p.errors;
        t.handle(p, Wire.parse(Wire.message("ACT", "hand", "" + t.hand, "turn", "" + t.turnToken,
            "action", action, "amount", "" + amount)));
        check(p.errors == errors, "unexpected rejected action " + action);
    }
    private static void callOrCheck(GameTable t) {
        act(t, t.players[t.actor].street < t.currentBet ? "CALL" : "CHECK", 0);
    }
    private static long score(String cards) { return HandEvaluator.best(Cards.decode(cards)).score; }
    private static void evaluator() {
        String[] hands = {"AS,KD,9C,5H,2S", "AS,AD,9C,5H,2S", "AS,AD,9C,9H,2S",
            "AS,AD,AC,5H,2S", "AS,2D,3C,4H,5S", "AS,JS,9S,5S,2S",
            "AS,AD,AC,5H,5S", "AS,AD,AC,AH,2S", "9S,TS,JS,QS,KS"};
        for (int i = 0; i < hands.length; i++) {
            check(HandEvaluator.best(Cards.decode(hands[i])).category == i, "category " + i);
            if (i > 0) check(score(hands[i]) > score(hands[i - 1]), "category ordering");
        }
        check(score("AS,2D,3C,4H,5S") < score("2S,3D,4C,5H,6S"), "wheel is five high");
        check(score("AS,AD,KC,QH,9S") > score("AH,AC,KD,QS,8S"), "last kicker decides");
        check(score("AS,AD,KC,KH,2S,2D,3C") == score("AH,AC,KD,KS,3S"), "best two pair kicker");
        check(score("AS,AD,AC,KH,KS,KD,2S") == score("AH,AC,AS,KC,KH"), "two triples -> best full house");
        check(score("TS,JS,QS,KS,AS,2H,3C") == score("TS,JS,QS,KS,AS,4H,5C"), "board plays; no suit tiebreak");
        try { score("AS,AS,KC,QH,9S"); check(false, "duplicate accepted"); }
        catch (IllegalArgumentException expected) { check(true, "duplicate rejected"); }
    }
    private static void pots() {
        Pots.Result p = Pots.settle(new int[]{100, 200, 200}, new boolean[]{true, true, true}, new long[]{3, 2, 1}, 0);
        check(Arrays.equals(p.awards, new int[]{300, 200, 0}), "main and side pot");
        p = Pots.settle(new int[]{100, 200, 300}, new boolean[]{true, true, true}, new long[]{3, 2, 1}, 0);
        check(Arrays.equals(p.refunds, new int[]{0, 0, 100}), "uncalled bet refunded");
        check(Arrays.equals(p.awards, new int[]{300, 200, 0}), "only matched chips awarded");
        p = Pots.settle(new int[]{5, 5, 5}, new boolean[]{true, true, false}, new long[]{7, 7, 100}, 0);
        check(Arrays.equals(p.awards, new int[]{7, 8, 0}), "split odd chip left of button; folded hand excluded");
        p = Pots.settle(new int[]{40, 100, 200, 200}, new boolean[]{true, true, true, true}, new long[]{4, 3, 2, 1}, 3);
        check(Arrays.equals(p.awards, new int[]{160, 180, 200, 0}), "three pot tiers");
    }
    private static void protocol() throws IOException {
        String text = "준석 + a=b & 한글\t\n";
        String frame = Wire.message("CHAT", "text", text);
        check(Wire.parse(frame).get("text").equals(text), "UTF-8 escaping round trip");
        StringReader reader = new StringReader(frame + "\nPING\n");
        check(Wire.readLine(reader).equals(frame), "first framed message");
        check(Wire.readLine(reader).equals("PING"), "coalesced second message");
        try { Wire.parse("JOIN\tname=A\tname=B"); check(false, "duplicate key"); }
        catch (IllegalArgumentException expected) { check(true, "duplicate key rejected"); }
        try { Wire.readLine(new StringReader("X".repeat(Wire.MAX_LINE + 1) + "\n")); check(false, "oversize"); }
        catch (IOException expected) { check(true, "oversize rejected"); }
        try { Wire.readLine(new StringReader("JOIN")); check(false, "partial EOF"); }
        catch (IOException expected) { check(true, "partial frame rejected"); }
    }
    private static void betting() {
        GameTable t = game(2); start(t);
        check(t.dealer == 0 && t.smallBlind == 0 && t.bigBlind == 1 && t.actor == 0, "heads-up preflop order");
        long token = t.turnToken;
        callOrCheck(t); check(t.actor == 1, "BB keeps option after limp"); callOrCheck(t);
        check(t.phase.equals("FLOP") && t.actor == 1 && t.board.size() == 3, "heads-up postflop order");
        Mock p = (Mock) t.players[1].peer; int errors = p.errors;
        t.handle(p, Wire.parse(Wire.message("ACT", "hand", "" + t.hand, "turn", "" + token, "action", "CHECK")));
        check(p.errors == errors + 1 && t.actor == 1, "stale turn rejected without mutation");
        while (t.running()) callOrCheck(t);
        check(t.board.size() == 5 && t.reveal, "full showdown");
        check(t.players[0].chips + t.players[1].chips == 2000, "chips conserved after showdown");
        start(t); check(t.dealer == 1 && t.actor == 1, "dealer rotates"); act(t, "FOLD", 0);
        check(!t.reveal && t.board.isEmpty(), "fold win hides hands");

        t = game(2, 1000, 5); start(t);
        check(!t.running() && t.players[0].chips + t.players[1].chips == 1005, "short BB runs out without unnecessary call");

        t = game(3, 100, 200, 500); start(t);
        act(t, "RAISE", 100); act(t, "RAISE", 200); callOrCheck(t);
        check(!t.running() && t.board.size() == 5, "all-in automatic board runout");
        check(t.players[0].chips + t.players[1].chips + t.players[2].chips == 800, "side pot chip conservation");

        t = game(4, 150, 1000, 1000, 1000); start(t);
        act(t, "RAISE", 100); act(t, "RAISE", 150); callOrCheck(t); callOrCheck(t);
        check(t.actor == 3 && !Wire.parse(t.snapshot(t.players[3])).get("canRaise").equals("true"), "short raise doesn't reopen action");
        p = (Mock) t.players[3].peer; errors = p.errors;
        t.handle(p, Wire.parse(Wire.message("ACT", "hand", "" + t.hand, "turn", "" + t.turnToken, "action", "RAISE", "amount", "230")));
        check(p.errors == errors + 1 && t.currentBet == 150, "illegal re-raise rejected");

        t = game(4, 150, 180, 1000, 1000); start(t);
        act(t, "RAISE", 100); act(t, "RAISE", 150); act(t, "RAISE", 180); callOrCheck(t);
        check(t.actor == 3 && Wire.parse(t.snapshot(t.players[3])).get("canRaise").equals("true"), "cumulative short raises reopen action");

        t = game(3, 1000, 1000, 25); start(t);
        callOrCheck(t); callOrCheck(t); callOrCheck(t);
        check(t.phase.equals("FLOP") && t.actor == 1, "3-player flop order");
        callOrCheck(t); act(t, "RAISE", 5); callOrCheck(t);
        check(t.actor == 1 && Wire.parse(t.snapshot(t.players[1])).get("canRaise").equals("true"), "check before short opening bet may raise");
    }
    private static void connectionsAndPrivacy() {
        GameTable t = game(2); start(t);
        Map<String, String> a = Wire.parse(t.snapshot(t.players[0]));
        check(Cards.decode(a.get("p0.cards")).size() == 2 && a.get("p1.cards").isEmpty(), "own cards only on wire");
        Mock newcomer = new Mock(); t.handle(newcomer, Wire.parse(Wire.message("JOIN", "name", "신규")));
        check(!t.players[2].inHand && newcomer.state.get("p0.cards").isEmpty(), "late join waits and sees no hole cards");
        t.disconnected(t.players[0].peer);
        check(!t.running() && t.players[0] == null && t.players[1].chips == 1010, "disconnect on turn folds and pays winner");
        check("1".equals(newcomer.state.get("host")), "host transfers on disconnect");

        t = game(2); start(t); t.disconnected(t.players[1].peer); callOrCheck(t);
        check(t.phase.equals("FLOP") && t.actor == 0, "disconnected opponent automatically checks on turn");
        while (t.running()) callOrCheck(t);
        check(!t.running(), "disconnected check-through hand completes");

        t = game(2); Mock peer = (Mock) t.players[1].peer;
        t.handle(peer, Wire.parse(Wire.message("START")));
        check(peer.errors == 1 && !t.running(), "non-host start rejected");
        for (int i = 2; i < 6; i++) t.handle(new Mock(), Wire.parse(Wire.message("JOIN", "name", "P" + i)));
        Mock seventh = new Mock(); t.handle(seventh, Wire.parse(Wire.message("JOIN", "name", "P6")));
        check(seventh.errors == 1, "seventh player rejected");
    }
    private static void randomHands() {
        Random r = new Random(20261028);
        for (int test = 0; test < 350; test++) {
            int n = 2 + r.nextInt(5), total = 0;
            int[] stacks = new int[n];
            for (int i = 0; i < n; i++) { stacks[i] = 1 + r.nextInt(1000); total += stacks[i]; }
            GameTable t = game(n, stacks); start(t); int actions = 0;
            while (t.running()) {
                check(++actions < 500, "hand must terminate");
                check(t.actor >= 0 && t.players[t.actor].canAct(), "valid actor");
                Map<String, String> state = Wire.parse(t.snapshot(t.players[t.actor]));
                int choice = r.nextInt(10);
                if (choice == 0) act(t, "FOLD", 0);
                else if (choice < 5 && "true".equals(state.get("canRaise"))) {
                    int max = Integer.parseInt(state.get("maxRaiseTo"));
                    int min = Integer.parseInt(state.get("minRaiseTo"));
                    int target = choice == 1 ? max : Math.min(max, min);
                    act(t, "RAISE", target);
                } else callOrCheck(t);
                int conserved = 0;
                for (GameTable.Player p : t.players) if (p != null) {
                    check(p.chips >= 0, "nonnegative stack"); conserved += p.chips + p.contributed;
                    if (t.running()) {
                        Map<String, String> visible = Wire.parse(t.snapshot(p));
                        for (GameTable.Player other : t.players) if (other != null && other != p)
                            check(visible.get("p" + other.seat + ".cards").isEmpty(), "no private card leak");
                    }
                }
                check(conserved == total, "chips conserved at every action");
            }
            Set<Integer> used = new HashSet<Integer>(t.board);
            int dealt = t.board.size();
            for (GameTable.Player p : t.players) if (p != null) { used.addAll(p.hole); dealt += p.hole.size(); }
            check(used.size() == dealt, "no duplicate dealt cards");
        }
    }
    public static void main(String[] args) throws Exception {
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(OutputStream.nullOutputStream()));
            protocol(); evaluator(); pots(); betting(); connectionsAndPrivacy(); randomHands();
        } finally { System.setOut(original); LOOP.shutdownNow(); }
        System.out.println("PASS: " + assertions + " assertions; evaluator, pots, protocol, betting, privacy, disconnects, 350 randomized hands.");
    }
}
