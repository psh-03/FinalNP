import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 참가자와 개인 카드 배분을 관리한다. 담당: 표신현
 * 여러 통신 Thread에서 사용할 때는 ServerMain이 synchronized(table)로 보호한다.
 */
public class GameTable {
    public static final int MAX_PLAYERS = 6;

    public static class Player {
        public final int id;
        public final String nickname;
        public final int chips = 1000; // 베팅 전이므로 2주차에는 변하지 않는다.
        private final List<Card> hand = new ArrayList<>();

        private Player(int id, String nickname) {
            this.id = id;
            this.nickname = nickname;
        }
    }

    private final Map<Integer, Player> players = new LinkedHashMap<>();
    private int nextId = 1;
    private boolean dealt = false;

    public Player addPlayer(String nickname) {
        if (!nickname.matches("[\\p{L}\\p{N}_]{1,12}")) {
            throw new IllegalArgumentException("닉네임은 공백 없이 문자·숫자·밑줄 1~12자로 입력하세요.");
        }
        if (players.size() >= MAX_PLAYERS) {
            throw new IllegalArgumentException("테이블 정원은 6명입니다.");
        }
        for (Player player : players.values()) {
            if (player.nickname.equalsIgnoreCase(nickname)) {
                throw new IllegalArgumentException("이미 사용 중인 닉네임입니다.");
            }
        }
        Player player = new Player(nextId++, nickname);
        players.put(player.id, player);
        resetDeal();
        return player;
    }

    public void removePlayer(int id) {
        if (players.remove(id) != null) {
            resetDeal();
        }
    }

    public int getHostId() {
        return players.isEmpty() ? 0 : players.keySet().iterator().next();
    }

    public List<Player> getPlayers() {
        return new ArrayList<>(players.values());
    }

    public String getPhase() {
        return dealt ? "DEALT" : "WAITING";
    }

    public List<Card> getHand(int id) {
        return new ArrayList<>(players.get(id).hand);
    }

    public void startGame(int requesterId) {
        if (requesterId != getHostId()) {
            throw new IllegalStateException("방장만 게임을 시작할 수 있습니다.");
        }
        if (players.size() < 2) {
            throw new IllegalStateException("참가자가 2명 이상이어야 합니다.");
        }
        resetDeal();
        Deck deck = new Deck();
        // 한 장씩 두 바퀴를 돌며 나누므로 모든 참가자가 서로 다른 카드 2장을 받는다.
        for (int round = 0; round < 2; round++) {
            for (Player player : players.values()) {
                player.hand.add(deck.draw());
            }
        }
        dealt = true;
    }

    private void resetDeal() {
        dealt = false;
        for (Player player : players.values()) {
            player.hand.clear();
        }
    }
}
