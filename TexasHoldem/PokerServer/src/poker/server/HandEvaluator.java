package poker.server;

import poker.common.Cards;
import java.util.List;

/** Compare every five-card combination; score encodes category and all kickers. */
public final class HandEvaluator {
    private static final String[] NAMES = {"하이 카드", "원 페어", "투 페어", "트리플",
        "스트레이트", "플러시", "풀 하우스", "포카드", "스트레이트 플러시"};
    private HandEvaluator() { }
    public static final class Rank implements Comparable<Rank> {
        public final long score;
        public final int category;
        Rank(int category, int... kickers) {
            this.category = category;
            long value = category;
            for (int i = 0; i < 5; i++) value = value * 15 + (i < kickers.length ? kickers[i] : 0);
            score = value;
        }
        public String name() { return NAMES[category]; }
        public int compareTo(Rank other) { return Long.compare(score, other.score); }
    }
    public static Rank best(List<Integer> cards) {
        if (cards.size() < 5 || cards.size() > 7) throw new IllegalArgumentException("5..7 cards required");
        if (new java.util.HashSet<Integer>(cards).size() != cards.size())
            throw new IllegalArgumentException("duplicate card");
        Rank best = null;
        int n = cards.size();
        for (int a = 0; a < n - 4; a++) for (int b = a + 1; b < n - 3; b++)
            for (int c = b + 1; c < n - 2; c++) for (int d = c + 1; d < n - 1; d++)
                for (int e = d + 1; e < n; e++) {
                    Rank r = five(cards.get(a), cards.get(b), cards.get(c), cards.get(d), cards.get(e));
                    if (best == null || r.compareTo(best) > 0) best = r;
                }
        return best;
    }
    private static Rank five(int... cards) {
        int[] counts = new int[15];
        boolean flush = true;
        for (int card : cards) {
            counts[Cards.rank(card)]++;
            flush &= Cards.suit(card) == Cards.suit(cards[0]);
        }
        int straight = 0;
        for (int high = 14; high >= 5; high--) {
            boolean found = true;
            for (int offset = 0; offset < 5; offset++) {
                int rank = high - offset;
                if (rank == 1) rank = 14;
                found &= counts[rank] > 0;
            }
            if (found) { straight = high; break; }
        }
        int four = 0, three = 0, pair1 = 0, pair2 = 0;
        int[] descending = new int[5]; int at = 0;
        for (int rank = 14; rank >= 2; rank--) {
            for (int i = 0; i < counts[rank]; i++) descending[at++] = rank;
            if (counts[rank] == 4) four = rank;
            if (counts[rank] == 3) three = rank;
            if (counts[rank] == 2) { if (pair1 == 0) pair1 = rank; else pair2 = rank; }
        }
        if (flush && straight > 0) return new Rank(8, straight);
        if (four > 0) return new Rank(7, four, singles(counts)[0]);
        if (three > 0 && pair1 > 0) return new Rank(6, three, pair1);
        if (flush) return new Rank(5, descending);
        if (straight > 0) return new Rank(4, straight);
        int[] s = singles(counts);
        if (three > 0) return new Rank(3, three, s[0], s[1]);
        if (pair2 > 0) return new Rank(2, pair1, pair2, s[0]);
        if (pair1 > 0) return new Rank(1, pair1, s[0], s[1], s[2]);
        return new Rank(0, descending);
    }
    private static int[] singles(int[] counts) {
        int[] out = new int[5]; int i = 0;
        for (int r = 14; r >= 2; r--) if (counts[r] == 1) out[i++] = r;
        return out;
    }
}
