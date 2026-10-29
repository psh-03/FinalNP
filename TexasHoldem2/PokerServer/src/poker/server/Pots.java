package poker.server;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * [서버 / 심화 알고리즘] 서로 다른 금액으로 올인했을 때 팟을 층별로 나누어 정산한다.
 * 이 클래스는 Player 객체를 직접 변경하지 않고, 좌석별 수령액/반환액만 계산해 돌려준다.
 * 폴드한 사람의 칩도 팟에는 포함하지만 그 사람은 승자 후보에서 제외한다.
 *
 * 예: A=100, B=200, C=300 납부 → C의 미콜 100 반환 → 납부액 [100,200,200].
 * 0~100층: 100*3=300 메인팟(A/B/C 경쟁).
 * 100~200층: 100*2=200 사이드팟(B/C 경쟁).
 * A가 가장 강해도 본인이 낸 한도를 넘는 사이드팟 200은 받을 수 없다.
 */
public final class Pots {
    // 상태가 없는 계산 도구이므로 생성자는 외부에 공개하지 않는다.
    private Pots() { }
    // 좌석별 결과를 묶어 반환하는 객체다. awards는 획득한 팟, refunds는 상대가 따라오지 않은 칩의 반환액이다.
    public static final class Result {
        public final int[] awards;
        public final int[] refunds;
        // 참가 좌석 수만큼 배열을 만들면 각 칸은 자동으로 0으로 초기화된다.
        Result(int n) { awards = new int[n]; refunds = new int[n]; }
    }
    // 입력 배열은 모두 같은 좌석 인덱스를 사용한다.
    // contributions[i]: 총 납부액 / eligible[i]: 아직 승리 자격이 있는가 / strengths[i]: 족보 점수.
    // dealer는 동점 때 나머지 칩의 배분 순서를 정하는 데만 사용한다.
    public static Result settle(int[] contributions, boolean[] eligible, long[] strengths, int dealer) {
        int n = contributions.length;
        Result out = new Result(n);
        // 호출한 GameTable이 가진 입력 배열을 바꾸지 않도록 복사본으로 계산한다.
        int[] paid = contributions.clone();
        // [1단계] 가장 많이 낸 좌석과 가장 큰 두 납부액을 구한다. 공동 최대이면 max와 second가 같아진다.
        int high = -1, max = 0, second = 0;
        for (int i = 0; i < n; i++) {
            if (paid[i] > max) { second = max; max = paid[i]; high = i; }
            else second = Math.max(second, paid[i]);
        }
        // 한 명만 더 많이 냈다면 max-second는 아무도 콜하지 않은 금액이다. 승패와 관계없이 그 사람에게 반환한다.
        if (max > second) {
            out.refunds[high] = max - second;
            paid[high] = second;
        }
        // [2단계] 양수 납부액의 중복을 없애고 오름차순으로 정렬한다. TreeSet은 두 작업을 동시에 해 준다.
        TreeSet<Integer> levels = new TreeSet<Integer>();
        for (int v : paid) if (v > 0) levels.add(v);
        int previous = 0;
        // previous~level 사이의 층 하나를 처리한다. 이 높이까지 낸 사람만 이 층의 팟에 참가한다.
        for (int level : levels) {
            int amount = 0;
            // 어떤 유효한 족보보다도 작게 시작한다. 승자 자격이 있는 사람을 한 명도 찾지 못하면 이 값이 그대로 남는다.
            long best = Long.MIN_VALUE;
            List<Integer> winners = new ArrayList<Integer>();
            for (int i = 0; i < n; i++) {
                // 폴드 여부와 관계없이 이 층까지 낸 사람의 칩을 센다. 층 두께 × 해당 인원 수가 팟 금액이다.
                if (paid[i] >= level) amount += level - previous;
                // 반면 최고 족보를 고를 때는 이 층에 충분히 냈고 폴드하지 않은 사람만 후보로 삼는다.
                if (paid[i] >= level && eligible[i]) best = Math.max(best, strengths[i]);
            }
            // 정상적인 게임 진행이라면 각 매칭된 팟에 승리 자격을 가진 사람이 남아 있어야 한다. 없으면 내부 상태 오류다.
            if (best == Long.MIN_VALUE) throw new IllegalStateException("pot without eligible player");
            // [3단계] 딜러 다음 좌석부터 최고 점수와 같은 사람들을 승자 목록에 넣는다. 같은 점수면 공동 승리다.
            for (int offset = 1; offset <= n; offset++) {
                int i = (dealer + offset + n) % n;
                if (paid[i] >= level && eligible[i] && strengths[i] == best) winners.add(i);
            }
            // 몫만큼 똑같이 나누고, 나머지는 위에서 정한 승자 순서대로 1점씩 더 준다.
            // 예: 15점을 2명이 나누면 7점씩 받고 첫 번째 승자가 나머지 1점을 받아 8점이 된다.
            for (int i = 0; i < winners.size(); i++)
                out.awards[winners.get(i)] += amount / winners.size() + (i < amount % winners.size() ? 1 : 0);
            // 현재 층까지 계산했으므로 다음 층의 시작 높이를 갱신한다.
            previous = level;
        }
        return out;
    }
}
