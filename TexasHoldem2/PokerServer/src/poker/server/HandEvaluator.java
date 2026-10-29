package poker.server;

import poker.common.Cards;
import java.util.List;

/**
 * [서버 / 알고리즘] 카드 5~7장을 받아 가장 강한 5장 조합을 찾는다.
 * 네트워크·화면·보유 칩과는 관계없는 순수 계산 클래스다.
 * 7장에서 5장을 고르는 경우의 수는 21개라서 전부 비교하는 완전탐색으로 충분하다.
 * 읽는 순서: best()에서 조합 선택 → five()로 한 조합 판정 → Rank.score로 크기 비교.
 */
public final class HandEvaluator {
    // 배열 인덱스가 족보 등급 0~8이다. 로열 플러시는 별도 등급이 아니라 A가 높은 스트레이트 플러시로 비교한다.
    private static final String[] NAMES = {"하이 카드", "원 페어", "투 페어", "트리플",
        "스트레이트", "플러시", "풀 하우스", "포카드", "스트레이트 플러시"};
    // 별도 상태가 없어 new로 만들지 않고 HandEvaluator.best(...)를 호출한다.
    private HandEvaluator() { }
    // 족보 판정 결과 객체다. Comparable은 두 결과의 크기를 compareTo()로 비교할 수 있다는 약속이다.
    public static final class Rank implements Comparable<Rank> {
        // score는 족보와 동점 비교 숫자를 합친 점수, category는 족보 이름을 찾는 등급이다. 생성 후 값이 바뀌지 않는다.
        public final long score;
        public final int category;
        // [핵심 계산] 족보 등급 뒤에 중요한 카드 숫자를 순서대로 붙인다고 생각하면 된다.
        // 카드 숫자는 최대 14이므로 15진수 자리처럼 처리하면 뒷자리들이 앞자리를 뒤집지 못한다.
        // 예: 원페어 A,A,K,Q,9 → category=1, kickers=[14,13,12,9].
        // value = value*15 + 다음값을 총 5번 실행하여 모든 결과의 자리 수를 같게 한다.
        // 남는 자리는 0으로 채운다. 족보가 같을 때만 다음 카드 숫자(키커 등)가 승부를 가른다.
        Rank(int category, int... kickers) {
            this.category = category;
            long value = category;
            for (int i = 0; i < 5; i++) value = value * 15 + (i < kickers.length ? kickers[i] : 0);
            score = value;
        }
        // category를 화면 표시용 한글 족보 이름으로 바꾼다.
        public String name() { return NAMES[category]; }
        // 내 score가 더 크면 양수, 같으면 0, 작으면 음수다. 카드 무늬는 동점 우열 기준으로 쓰지 않는다.
        public int compareTo(Rank other) { return Long.compare(score, other.score); }
    }
    // 주어진 카드 수와 중복 여부를 검사한 뒤 가능한 모든 5장 조합 중 최대 점수를 반환한다.
    public static Rank best(List<Integer> cards) {
        if (cards.size() < 5 || cards.size() > 7) throw new IllegalArgumentException("5..7 cards required");
        // Set은 중복 원소를 제거하므로 원래 목록과 크기가 다르면 같은 카드가 두 번 들어온 것이다.
        if (new java.util.HashSet<Integer>(cards).size() != cards.size())
            throw new IllegalArgumentException("duplicate card");
        Rank best = null;
        int n = cards.size();
        // a < b < c < d < e 인덱스 순서를 강제해서 같은 카드나 같은 조합을 중복 선택하지 않는다.
        for (int a = 0; a < n - 4; a++) for (int b = a + 1; b < n - 3; b++)
            for (int c = b + 1; c < n - 2; c++) for (int d = c + 1; d < n - 1; d++)
                for (int e = d + 1; e < n; e++) {
                    Rank r = five(cards.get(a), cards.get(b), cards.get(c), cards.get(d), cards.get(e));
                    if (best == null || r.compareTo(best) > 0) best = r;
                }
        return best;
    }
    // 정확히 5장인 조합 하나를 판정한다. counts[rank]에 그 숫자의 카드가 몇 장인지 센다.
    private static Rank five(int... cards) {
        // 인덱스 2~14를 카드 숫자 그대로 쓴다. 0과 1은 숫자 개수 저장에 사용하지 않는다.
        int[] counts = new int[15];
        boolean flush = true;
        for (int card : cards) {
            counts[Cards.rank(card)]++;
            // &=는 기존 boolean 값과 오른쪽 조건의 AND를 다시 저장한다. 한 번이라도 무늬가 다르면 끝까지 false다.
            flush &= Cards.suit(card) == Cards.suit(cards[0]);
        }
        int straight = 0;
        // 가장 높은 카드 후보 14부터 5까지 내려가며 연속된 다섯 숫자가 모두 존재하는지 확인한다.
        for (int high = 14; high >= 5; high--) {
            boolean found = true;
            for (int offset = 0; offset < 5; offset++) {
                int rank = high - offset;
                // A,2,3,4,5는 예외적으로 5가 높은 스트레이트다. high=5일 때 필요한 1을 A(14)로 바꾸어 검사한다.
                if (rank == 1) rank = 14;
                found &= counts[rank] > 0;
            }
            if (found) { straight = high; break; }
        }
        // 같은 숫자가 4장/3장/2장인 그룹을 찾는다. 큰 숫자부터 훑으므로 pair1이 더 높은 페어다.
        int four = 0, three = 0, pair1 = 0, pair2 = 0;
        int[] descending = new int[5]; int at = 0;
        for (int rank = 14; rank >= 2; rank--) {
            // 같은 숫자의 장수만큼 내림차순 숫자 배열에 채운다. 플러시와 하이 카드의 순차 비교에 사용한다.
            for (int i = 0; i < counts[rank]; i++) descending[at++] = rank;
            if (counts[rank] == 4) four = rank;
            if (counts[rank] == 3) three = rank;
            if (counts[rank] == 2) { if (pair1 == 0) pair1 = rank; else pair2 = rank; }
        }
        // 높은 족보부터 검사해 첫 결과를 반환한다. 각 Rank의 뒷자리 숫자는 해당 족보의 우열 비교 순서대로 전달한다.
        if (flush && straight > 0) return new Rank(8, straight);
        if (four > 0) return new Rank(7, four, singles(counts)[0]);
        if (three > 0 && pair1 > 0) return new Rank(6, three, pair1);
        if (flush) return new Rank(5, descending);
        if (straight > 0) return new Rank(4, straight);
        int[] s = singles(counts);
        if (three > 0) return new Rank(3, three, s[0], s[1]);
        // 투페어는 높은 페어 → 낮은 페어 → 남은 한 장 순서로 비교한다.
        if (pair2 > 0) return new Rank(2, pair1, pair2, s[0]);
        if (pair1 > 0) return new Rank(1, pair1, s[0], s[1], s[2]);
        return new Rank(0, descending);
    }
    // 한 장씩만 있는 카드 숫자를 높은 순서로 모은다. 페어/트리플/포카드의 키커로 사용한다.
    private static int[] singles(int[] counts) {
        int[] out = new int[5]; int i = 0;
        for (int r = 14; r >= 2; r--) if (counts[r] == 1) out[i++] = r;
        return out;
    }
}
