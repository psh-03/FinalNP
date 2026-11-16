import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 조커 없는 52장 덱. 담당: 표신현 */
public class Deck {
    private final List<Card> cards = new ArrayList<>();
    private int nextIndex = 0;

    public Deck() {
        for (char suit : new char[] {'S', 'H', 'D', 'C'}) {
            for (int rank = 2; rank <= 14; rank++) {
                cards.add(new Card(suit, rank));
            }
        }
        Collections.shuffle(cards);
    }

    public Card draw() {
        if (nextIndex >= cards.size()) {
            throw new IllegalStateException("남은 카드가 없습니다.");
        }
        // 같은 위치의 카드를 두 번 꺼내지 않도록 인덱스를 증가시킨다.
        return cards.get(nextIndex++);
    }
}
