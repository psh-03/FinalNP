package poker.common;

import java.util.ArrayList;
import java.util.List;

/** Card id: suit * 13 + rank - 2. S/H/D/C, ranks 2..A. */
public final class Cards {
    private static final String RANKS = "23456789TJQKA";
    private static final String SUITS = "SHDC";
    private Cards() { }
    public static int rank(int card) { return card % 13 + 2; }
    public static int suit(int card) { return card / 13; }
    public static String code(int card) {
        return "" + RANKS.charAt(card % 13) + SUITS.charAt(card / 13);
    }
    public static int parse(String code) {
        if (code.length() != 2) throw new IllegalArgumentException("invalid card");
        int r = RANKS.indexOf(code.charAt(0));
        int s = SUITS.indexOf(code.charAt(1));
        if (r < 0 || s < 0) throw new IllegalArgumentException("invalid card");
        return s * 13 + r;
    }
    public static String encode(List<Integer> cards) {
        StringBuilder out = new StringBuilder();
        for (int card : cards) {
            if (out.length() > 0) out.append(',');
            out.append(code(card));
        }
        return out.toString();
    }
    public static List<Integer> decode(String value) {
        List<Integer> cards = new ArrayList<Integer>();
        if (value != null && !value.isEmpty())
            for (String code : value.split(",")) cards.add(parse(code));
        return cards;
    }
}
