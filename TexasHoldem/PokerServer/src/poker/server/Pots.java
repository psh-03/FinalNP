package poker.server;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Pure settlement function. Folded chips count toward pots, never toward winners. */
public final class Pots {
    private Pots() { }
    public static final class Result {
        public final int[] awards;
        public final int[] refunds;
        Result(int n) { awards = new int[n]; refunds = new int[n]; }
    }
    public static Result settle(int[] contributions, boolean[] eligible, long[] strengths, int dealer) {
        int n = contributions.length;
        Result out = new Result(n);
        int[] paid = contributions.clone();
        int high = -1, max = 0, second = 0;
        for (int i = 0; i < n; i++) {
            if (paid[i] > max) { second = max; max = paid[i]; high = i; }
            else second = Math.max(second, paid[i]);
        }
        if (max > second) {
            out.refunds[high] = max - second;
            paid[high] = second;
        }
        TreeSet<Integer> levels = new TreeSet<Integer>();
        for (int v : paid) if (v > 0) levels.add(v);
        int previous = 0;
        for (int level : levels) {
            int amount = 0;
            long best = Long.MIN_VALUE;
            List<Integer> winners = new ArrayList<Integer>();
            for (int i = 0; i < n; i++) {
                if (paid[i] >= level) amount += level - previous;
                if (paid[i] >= level && eligible[i]) best = Math.max(best, strengths[i]);
            }
            // Valid betting always leaves at least one eligible player in each matched pot.
            if (best == Long.MIN_VALUE) throw new IllegalStateException("pot without eligible player");
            // Odd chips go to the first tied winner clockwise after the dealer.
            for (int offset = 1; offset <= n; offset++) {
                int i = (dealer + offset + n) % n;
                if (paid[i] >= level && eligible[i] && strengths[i] == best) winners.add(i);
            }
            for (int i = 0; i < winners.size(); i++)
                out.awards[winners.get(i)] += amount / winners.size() + (i < amount % winners.size() ? 1 : 0);
            previous = level;
        }
        return out;
    }
}
