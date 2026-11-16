/** 카드 한 장의 무늬와 숫자. 담당: 표신현 */
public class Card {
    private final char suit; // S 스페이드, H 하트, D 다이아, C 클로버
    private final int rank;  // 2~10, J=11, Q=12, K=13, A=14

    public Card(char suit, int rank) {
        if ("SHDC".indexOf(suit) < 0 || rank < 2 || rank > 14) {
            throw new IllegalArgumentException("올바르지 않은 카드입니다.");
        }
        this.suit = suit;
        this.rank = rank;
    }

    // 통신에는 기호 대신 간단한 영문 코드를 사용한다. 예: S14
    public String toWire() {
        return "" + suit + rank;
    }

    public static Card fromWire(String code) {
        return new Card(code.charAt(0), Integer.parseInt(code.substring(1)));
    }

    public boolean isRed() {
        return suit == 'H' || suit == 'D';
    }

    public String getSuitName() {
        return switch (suit) {
            case 'S' -> "스페이드";
            case 'H' -> "하트";
            case 'D' -> "다이아";
            default -> "클로버";
        };
    }

    public String getRankText() {
        return switch (rank) {
            case 11 -> "J";
            case 12 -> "Q";
            case 13 -> "K";
            case 14 -> "A";
            default -> String.valueOf(rank);
        };
    }

    @Override
    public String toString() {
        return getSuitName() + " " + getRankText();
    }
}
