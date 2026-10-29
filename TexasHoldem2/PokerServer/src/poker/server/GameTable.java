package poker.server;

import poker.common.Cards;
import poker.common.Wire;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * [서버 / 게임 진행의 중심] 접속자, 카드, 칩, 차례와 현재 베팅 단계를 관리한다.
 * 정상 서버 실행에서는 이 객체의 상태를 단일 game-loop 스레드만 변경한다.
 * ClientSession이 읽은 요청은 loop.execute(...)를 통해 handle()로 전달된다.
 *
 * 처음 읽을 순서: handle → join → start → act → progress → finish → snapshot.
 * handle은 요청 종류를 구별하고, act는 행동을 반영하며, progress는 다음 진행을 결정한다.
 * '핸드'는 카드 분배부터 승패 결정까지 한 판, 'street'는 프리플롭/플롭/턴/리버 한 단계다.
 * 순수 족보 계산은 HandEvaluator, 팟 분배 계산은 Pots에 맡긴다.
 */
final class GameTable {
    // 게임 설정값: 최대 6명, 초기 칩 1000, SB 10, BB 20. static final은 클래스가 공유하는 상수다.
    static final int MAX_PLAYERS = 6, BUY_IN = 1000, SMALL_BLIND = 10, BIG_BLIND = 20;
    // Peer는 이 플레이어에게 메시지를 보낼 수 있다는 최소 약속이다.
    // 실제 게임에서는 ClientSession, 테스트에서는 가짜 수신자인 Mock이 구현한다.
    // 게임 규칙을 소켓이나 Swing에 직접 의존시키지 않아 테스트하기 쉬워진다.
    interface Peer { void send(String frame); }
    // 플레이어 한 명의 서버 상태를 묶는 내부 클래스다. static이므로 바깥 GameTable 객체 참조를 자동 보관하지 않는다.
    static final class Player {
        // seat: 0~5 좌석 번호 / name: 표시 이름 / peer: 이 사람의 연결을 통해 보내는 창구.
        final int seat;
        final String name;
        final Peer peer;
        // connected: 아직 연결되어 있는가 / inHand: 이번 핸드에 카드를 받았는가 / folded: 포기했는가.
        // 중도 입장자는 connected만 true이고 inHand는 다음 핸드 시작까지 false다.
        boolean connected = true, inHand, folded;
        // chips: 아직 손에 있는 칩.
        // street: 이번 베팅 단계에 이미 낸 칩. 플롭/턴/리버로 넘어갈 때 0으로 초기화한다.
        // contributed: 이번 핸드 전체에서 낸 칩. 사이드팟을 계산할 때 사용한다.
        // lastActedBet: 마지막 행동 당시의 테이블 베팅 총액. -1은 아직 행동하지 않았다는 뜻이다.
        // 예: 프리플롭 20 + 플롭 40을 냈다면 플롭에서 street=40, contributed=60이다.
        int chips = BUY_IN, street, contributed, lastActedBet = -1;
        // 본인만 볼 수 있는 개인 카드 2장. 각 카드는 Cards가 정의한 정수 ID로 저장한다.
        final List<Integer> hole = new ArrayList<Integer>();
        // 새 참가자의 좌석·이름·통신 창구를 저장한다. 칩은 위 필드의 기본값 BUY_IN으로 시작한다.
        Player(int seat, String name, Peer peer) { this.seat = seat; this.name = name; this.peer = peer; }
        // 이번 핸드 참가자이고 폴드하지 않았고 남은 칩이 있을 때만 행동 가능하다. 올인한 사람도 승리 자격은 남는다.
        boolean canAct() { return inHand && !folded && chips > 0; }
    }
    // 배열 인덱스가 좌석 번호다. null이면 빈 자리이며, final이어도 배열의 각 원소는 바꿀 수 있다.
    final Player[] players = new Player[MAX_PLAYERS];
    // 모두에게 공개되는 공유 카드 목록이다. 크기는 0 → 3 → 4 → 5 순서로 변한다.
    final List<Integer> board = new ArrayList<Integer>();
    // 이번 단계에서 아직 행동해야 하는 좌석들의 집합이다. Set은 중복을 허용하지 않는다.
    // 레이즈가 나오면 이미 행동했더라도 새 금액에 응답해야 하는 사람을 다시 넣는다.
    // HashSet 자체에는 차례 순서가 없다. 실제 순서는 progress()가 좌석 번호를 돌며 정한다.
    final Set<Integer> pending = new HashSet<Integer>();
    // loop는 요청과 시간 초과를 처리하는 공통 실행기, turnSeconds는 행동 제한 시간, random은 셔플용 난수다.
    private final ScheduledExecutorService loop;
    private final int turnSeconds;
    private final Random random;
    // Deque는 양쪽 끝에서 꺼낼 수 있는 큐다. 여기서는 섞은 카드 더미의 앞쪽에서 한 장씩 꺼내는 용도로 쓴다.
    private final Deque<Integer> deck = new ArrayDeque<Integer>();
    // phase는 WAITING/PREFLOP/FLOP/TURN/RIVER/FINISHED 중 현재 단계, result는 마지막 결과 안내다.
    String phase = "WAITING", result = "두 명 이상 접속하면 방장이 시작할 수 있습니다.";
    // dealer·smallBlind·bigBlind·actor는 각각 역할을 가진 좌석 번호다. actor=-1이면 지금 기다리는 행동자가 없다.
    int dealer = -1, smallBlind = -1, bigBlind = -1, actor = -1;
    // hand: 몇 번째 핸드인가 / currentBet: 이번 단계에서 맞춰야 하는 테이블 베팅 총액.
    // lastFullRaise: 마지막 정식 베팅/레이즈의 증액 폭. 다음 최소 레이즈를 정한다.
    // lastPot: 정산 직전 전체 투입 칩. 미콜 반환분도 포함한 표시용 기록이다.
    int hand, currentBet, lastFullRaise = BIG_BLIND, lastPot;
    // turnToken: 차례가 바뀔 때 증가하는 번호. 늦게 도착한 이전 행동을 거절할 때 사용한다.
    // revision: STATE 방송의 갱신 번호. 현재 클라이언트는 이 번호로 중복 상태를 따로 걸러내지는 않는다.
    // deadline: 서버 시계 기준 행동 마감 시각(밀리초). reveal은 쇼다운 카드 공개 여부다.
    long turnToken, revision, deadline;
    boolean reveal;

    // 실제 서버용 생성자는 SecureRandom으로 셔플한다. this(...)는 아래의 다른 생성자를 호출한다.
    GameTable(ScheduledExecutorService loop, int turnSeconds) {
        this(loop, turnSeconds, new SecureRandom());
    }
    // 테스트에서는 고정 seed의 Random을 전달해 매번 같은 카드 순서로 문제를 재현할 수 있게 했다.
    GameTable(ScheduledExecutorService loop, int turnSeconds, Random random) {
        this.loop = loop; this.turnSeconds = turnSeconds; this.random = random;
    }
    // 대기 또는 종료 상태이면 false, 베팅 중인 네 단계이면 true다.
    boolean running() { return !phase.equals("WAITING") && !phase.equals("FINISHED"); }
    // 메시지에 적힌 이름이 아니라 실제 연결 객체로 보낸 사람을 찾는다. ==는 같은 객체인지 비교한다.
    private Player find(Peer peer) {
        for (Player p : players) if (p != null && p.peer == peer) return p;
        return null;
    }
    // 연결 중인 가장 낮은 좌석 번호를 방장으로 정한다. 방장이 나가면 다음 접속 좌석이 방장이 된다.
    private int host() {
        for (Player p : players) if (p != null && p.connected) return p.seat;
        return -1;
    }
    // 연결되어 있고 칩이 남아 있는 인원을 센다. 핸드 시작에 최소 2명이 필요하다.
    private int funded() {
        int n = 0;
        for (Player p : players) if (p != null && p.connected && p.chips > 0) n++;
        return n;
    }
    // [요청 입구] 메시지 종류에 따라 처리할 메서드를 고르는 분기점이다.
    // PING은 연결 확인, JOIN은 입장, CHAT은 채팅, START는 시작, REBUY는 칩 충전, ACT는 게임 행동이다.
    // 잘못된 요청에는 ERROR와 현재 상태를 돌려준다. 정상 요청에서만 상태를 변경한다.
    void handle(Peer peer, Map<String, String> m) {
        try {
            String type = Wire.required(m, "type");
            // heartbeat 확인 요청에 PONG을 응답한다. 포커 차례를 소비하지 않는다.
            if (type.equals("PING")) { peer.send(Wire.message("PONG")); return; }
            Player p = find(peer);
            if (type.equals("JOIN")) {
                if (p != null) throw new IllegalArgumentException("이미 접속했습니다.");
                join(peer, Wire.required(m, "name"));
                return;
            }
            // PING/JOIN 이외의 명령은 정상적으로 입장한 연결만 사용할 수 있다.
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
                // [검증] 현재 차례인 사람도 이전 핸드/차례의 메시지를 다시 보내면 거절한다.
                // 버튼 중복 클릭이나 지연 전송으로 옛 행동이 나중에 적용되는 것을 막는다.
                // hand/turn은 인증 비밀번호가 아니라 현재 상태를 맞추는 번호다.
                if (Wire.number(m, "hand") != hand || Long.parseLong(Wire.required(m, "turn")) != turnToken)
                    throw new IllegalArgumentException("이전 차례의 요청입니다. 현재 화면에서 다시 선택하세요.");
                String action = Wire.required(m, "action");
                int target = action.equals("RAISE") ? Wire.number(m, "amount") : 0;
                act(p, action, target);
            } else throw new IllegalArgumentException("지원하지 않는 요청입니다.");
        } catch (IllegalArgumentException e) {
            peer.send(Wire.message("ERROR", "text", e.getMessage() == null ? "잘못된 요청입니다." : e.getMessage()));
            Player p = find(peer);
            // 거절 후에도 현재 상태를 보내 클라이언트 버튼과 표시가 서버 상태로 다시 맞춰지게 한다.
            if (p != null) peer.send(snapshot(p));
        }
    }
    // 채팅에 줄바꿈 같은 제어 문자가 들어갔는지 검사한다. 화면 로그는 한 문장 단위로 유지한다.
    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true;
        return false;
    }
    // 이름 형식·중복·빈 좌석을 검사한 뒤 새 Player를 등록한다. 진행 중인 핸드의 카드를 주지는 않는다.
    private void join(Peer peer, String input) {
        String name = input.trim();
        if (!name.matches("[\\p{L}\\p{N}_ -]{1,16}"))
            throw new IllegalArgumentException("이름은 한글·영문·숫자·공백·밑줄·하이픈으로 1~16자 입력하세요.");
        for (Player p : players) if (p != null && p.name.equalsIgnoreCase(name))
            throw new IllegalArgumentException("이미 사용 중인 이름입니다.");
        // 처음 발견한 빈 좌석을 고른다. 끝까지 -1이면 이미 6명이 차 있다.
        int seat = -1;
        for (int i = 0; i < MAX_PLAYERS; i++) if (players[i] == null) { seat = i; break; }
        if (seat == -1) throw new IllegalArgumentException("테이블이 가득 찼습니다. 최대 6명입니다.");
        players[seat] = new Player(seat, name, peer);
        log(name + " 님이 접속했습니다." + (running() ? " 다음 핸드부터 참가합니다." : ""));
        broadcast();
    }
    // [연결 종료] 진행 중인 참가자는 팟 계산에 필요하므로 곧바로 배열에서 지우지 않는다.
    // 그 사람 차례가 되면 자동 체크/폴드하고, 이미 올인한 사람의 승리 자격은 유지한다.
    // 핸드가 끝나거나 아직 참가하지 않은 사람은 좌석을 바로 비울 수 있다.
    void disconnected(Peer peer) {
        Player p = find(peer);
        if (p == null || !p.connected) return;
        p.connected = false;
        log(p.name + " 님의 연결이 종료되었습니다.");
        if (!running() || !p.inHand) players[p.seat] = null;
        else if (actor == p.seat) {
            // 지금 그 사람 차례라면 자동 처리한다. 차례 밖에서 강제로 행동 순서를 바꾸지는 않는다.
            act(p, p.street >= currentBet ? "CHECK" : "FOLD", 0);
            return;
        }
        broadcast();
    }
    // after 좌석 다음부터 원형으로 순회해 이번 핸드 참가 좌석을 찾는다. % MAX_PLAYERS로 마지막 다음은 0이 된다.
    private int nextInHand(int after) {
        for (int k = 1; k <= MAX_PLAYERS; k++) {
            int i = (after + k + MAX_PLAYERS) % MAX_PLAYERS;
            if (players[i] != null && players[i].inHand) return i;
        }
        throw new IllegalStateException("no players");
    }
    // [새 핸드 준비] 조건 검사 → 지난 상태 정리 → 딜러/블라인드 배치 → 셔플/분배 → 강제 베팅 → 첫 차례.
    // 보유 칩은 유지하고, 이전 개인 카드·공유 카드·단계별 베팅·폴드 표시를 초기화한다.
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
        // 참가 가능한 좌석 기준으로 딜러를 이동한다. 이 기본 버전은 대회용 데드 버튼 규칙까지 구현하지 않았다.
        dealer = nextInHand(dealer);
        // 2인전(헤즈업)은 딜러가 SB도 맡는다. 3인 이상이면 딜러 다음 사람이 SB다.
        smallBlind = funded() == 2 ? dealer : nextInHand(dealer);
        bigBlind = nextInHand(smallBlind);
        // 0~51을 한 번씩 넣고 섞는다. 매번 카드를 임의 생성하는 방식과 달리 중복 카드를 뽑지 않는다.
        List<Integer> shuffled = new ArrayList<Integer>();
        for (int card = 0; card < 52; card++) shuffled.add(card);
        Collections.shuffle(shuffled, random); deck.clear(); deck.addAll(shuffled);
        // 딜러 다음 좌석부터 한 장씩 두 바퀴 돌려 개인 카드 2장을 준다. removeFirst()는 준 카드를 더미에서 제거한다.
        for (int round = 0; round < 2; round++) {
            int seat = dealer;
            do { seat = nextInHand(seat); players[seat].hole.add(deck.removeFirst()); } while (seat != dealer);
        }
        // SB/BB를 강제로 낸다. 칩이 블라인드보다 적으면 가진 칩만 내고 올인이 된다.
        pay(players[smallBlind], Math.min(SMALL_BLIND, players[smallBlind].chips));
        pay(players[bigBlind], Math.min(BIG_BLIND, players[bigBlind].chips));
        phase = "PREFLOP";
        for (Player p : players) if (p != null && p.canAct()) pending.add(p.seat);
        log("핸드 #" + hand + " 시작 · SB " + players[smallBlind].name + " / BB " + players[bigBlind].name);
        // 프리플롭은 BB 다음 좌석부터 행동한다. 2명일 때는 SB인 딜러가 먼저 행동하게 된다.
        progress(bigBlind);
    }
    // [칩 이동] 실제 추가로 낼 amount를 보유 칩에서 빼고, 단계별/핸드 전체 납부액에 더한다.
    // 예: chips=980, street=20인 사람이 40을 추가로 내면 chips=940, street=60이다.
    // 여기서는 승자를 정하지 않고 칩이 손에서 팟으로 이동한 사실만 기록한다.
    private static void pay(Player p, int amount) {
        if (amount < 0 || amount > p.chips) throw new IllegalArgumentException("칩 범위를 확인하세요.");
        p.chips -= amount; p.street += amount; p.contributed += amount;
    }
    // [심화 / 레이즈 가능 여부] 칩이 있고, 상대 중 추가 행동할 수 있는 사람이 있어야 한다.
    // 이미 행동했다면 이후 베팅이 정식 레이즈 폭 이상 커졌는지도 확인한다.
    // 작은 올인 하나로 이미 행동한 사람의 재레이즈 권한이 무조건 다시 열리지는 않는다.
    private boolean mayRaise(Player p) {
        if (!p.canAct() || p.street + p.chips <= currentBet) return false;
        boolean opponentCanAct = false;
        for (Player other : players) if (other != null && other != p && other.canAct()) opponentCanAct = true;
        // lastActedBet=-1: 아직 행동 안 함 / 0: 베팅이 없을 때 체크한 상태.
        // 예: 100까지 행동했고 정식 증액 폭이 80이면, 150의 짧은 올인에는 재레이즈할 수 없다.
        // 짧은 올인이 누적되어 180이 되면 차이가 80이므로 재레이즈할 수 있다.
        return opponentCanAct && (p.lastActedBet <= 0 || currentBet - p.lastActedBet >= lastFullRaise);
    }
    // [행동 처리] 요청이 현재 차례인지는 handle에서 확인했다. 여기서는 행동의 규칙과 금액을 확인한다.
    // target은 추가로 내는 금액이 아니라 '이번 단계에서 총 얼마까지 내겠다'는 값이다.
    // 예: street=20, currentBet=40일 때 target=80이면 실제로 60을 추가 납부한다.
    private void act(Player p, String action, int target) {
        // 콜하려면 추가로 필요한 금액이다. 이미 낸 street를 빼서 같은 칩을 두 번 청구하지 않는다.
        int owed = Math.max(0, currentBet - p.street);
        String description;
        // 폴드는 이번 핸드의 승리 자격을 포기한다. 이미 팟에 낸 칩은 돌려주지 않는다.
        if (action.equals("FOLD")) {
            p.folded = true; description = "폴드";
        } else if (action.equals("CHECK")) {
            if (owed != 0) throw new IllegalArgumentException("콜해야 할 금액이 있어 체크할 수 없습니다.");
            description = "체크";
        } else if (action.equals("CALL")) {
            if (owed == 0) throw new IllegalArgumentException("체크를 선택하세요.");
            // 콜 금액보다 잔액이 작으면 잔액만 낸다. 이 경우 p.chips가 0이 되어 올인으로 취급된다.
            int amount = Math.min(owed, p.chips); pay(p, amount); description = "콜 " + amount;
        } else if (action.equals("RAISE")) {
            if (!mayRaise(p)) throw new IllegalArgumentException("현재 레이즈할 수 없습니다.");
            if (target <= currentBet || target > p.street + p.chips)
                throw new IllegalArgumentException("레이즈 총액이 허용 범위를 벗어났습니다.");
            // 레이즈 폭은 새 목표 총액 - 기존 테이블 총액이다. 내 잔액이나 추가 납부액과 구별해야 한다.
            int increment = target - currentBet;
            boolean allIn = target == p.street + p.chips;
            // 정식 최소 증액보다 작으면 거절한다. 남은 칩을 전부 쓰는 올인만 작은 증액을 허용한다.
            if (increment < lastFullRaise && !allIn)
                throw new IllegalArgumentException("최소 레이즈 총액: " + (currentBet + lastFullRaise));
            pay(p, target - p.street);
            // 정식 레이즈인 경우에만 다음 최소 증액 폭을 갱신한다. 작은 올인은 그 기준을 줄이지 않는다.
            if (increment >= lastFullRaise) lastFullRaise = increment;
            currentBet = target;
            // 새 레이즈 금액에 아직 못 맞춘 다른 행동 가능 참가자들을 pending에 다시 넣어 응답 기회를 준다.
            for (Player other : players)
                if (other != null && other != p && other.canAct() && other.street < currentBet) pending.add(other.seat);
            description = "베팅/레이즈 총액 " + target;
        } else throw new IllegalArgumentException("행동은 FOLD, CHECK, CALL, RAISE 중 하나여야 합니다.");
        p.lastActedBet = currentBet;
        // 이 사람의 이번 행동은 완료했다. 현재 행동자를 비운 뒤 progress()에서 다음 사람/단계를 정한다.
        pending.remove(p.seat); actor = -1;
        log(p.name + " · " + description + (p.chips == 0 && !p.folded ? " (올인)" : ""));
        progress(p.seat);
    }
    // [가장 중요한 흐름] 직전 행동 다음에 무엇을 할지 결정하는 메서드다.
    // 1. 한 명만 남았으면 폴드 승리 정산.
    // 2. 아직 행동할 사람이 있으면 다음 차례와 제한 시간을 정하고 상태 전송.
    // 3. 모두 행동을 마쳤으면 다음 공유 카드를 열거나 쇼다운 정산.
    // 모두 올인한 경우 여러 단계를 while문에서 연속 진행한다. 사람이 응답해야 할 때는 return한다.
    private void progress(int after) {
        while (running()) {
            // contenders는 승리 자격이 남은 수(올인 포함), actors는 지금 칩을 더 낼 수 있는 수다.
            int contenders = 0, actors = 0; Player loneActor = null;
            for (Player p : players) if (p != null && p.inHand && !p.folded) {
                contenders++;
                if (p.canAct()) { actors++; loneActor = p; }
            }
            if (contenders == 1) { finish(false); return; }
            // 폴드·올인 등으로 더는 행동할 수 없는 사람은 대기 집합에서 뺀다. removeIf는 조건에 맞는 원소 삭제다.
            pending.removeIf(i -> players[i] == null || !players[i].canAct());
            if (actors == 1) {
                // 다른 사람들은 올인 상태이므로 남은 한 사람이 실제 상대 납부액까지만 맞추면 된다.
                // 예: 2인전에서 상대 BB가 5점 올인이면 SB가 의미 없이 20점까지 더 낼 차례를 만들지 않는다.
                int opposingBet = 0;
                for (Player p : players) if (p != null && p != loneActor && p.inHand && !p.folded)
                    opposingBet = Math.max(opposingBet, p.street);
                currentBet = Math.min(currentBet, Math.max(loneActor.street, opposingBet));
                if (loneActor.street >= currentBet) pending.clear();
            }
            // 모두 응답했다. 리버까지 끝났으면 판정하고, 그 전이면 다음 공유 카드를 연다.
            if (pending.isEmpty()) {
                if (phase.equals("RIVER")) { finish(true); return; }
                deck.removeFirst(); // 공유 카드를 열기 전 버닝 카드 1장을 더미에서 제외한다.
                // 프리플롭 다음에는 플롭 3장, 그 이후에는 턴/리버 1장씩 추가한다.
                int count = phase.equals("PREFLOP") ? 3 : 1;
                for (int i = 0; i < count; i++) board.add(deck.removeFirst());
                phase = phase.equals("PREFLOP") ? "FLOP" : phase.equals("FLOP") ? "TURN" : "RIVER";
                currentBet = 0; lastFullRaise = BIG_BLIND; actor = -1;
                for (Player p : players) if (p != null) {
                    // 새 단계의 납부액과 행동 기록만 초기화한다. 전체 contributed는 마지막 팟 정산까지 누적 보존한다.
                    p.street = 0; p.lastActedBet = -1;
                    if (p.canAct()) pending.add(p.seat);
                }
                // 플롭 이후에는 딜러 다음의 행동 가능한 사람이 먼저 한다. 2인전에서도 BB가 먼저 하게 된다.
                after = dealer;
                log(phase + " · " + Cards.encode(board));
                continue;
            }
            // after 다음 좌석부터 원형 순회한다. pending을 순서대로 찾으므로 빈자리·폴드·올인을 건너뛴다.
            for (int k = 1; k <= MAX_PLAYERS; k++) {
                int i = (after + k + MAX_PLAYERS) % MAX_PLAYERS;
                if (pending.contains(i)) { actor = i; break; }
            }
            Player p = players[actor];
            if (!p.connected) {
                act(p, p.street >= currentBet ? "CHECK" : "FOLD", 0);
                return;
            }
            // 새 차례 번호와 서버 기준 종료 시각을 만든다. 밀리초로 바꾸기 위해 초에 1000L을 곱한다.
            turnToken++; deadline = System.currentTimeMillis() + turnSeconds * 1000L;
            // [시간 초과] 이 차례가 시작할 때의 번호를 별도 변수에 보관해 예약 작업이 기억하게 한다.
            // 예약 시간 전에 정상 행동이 끝나도 예약 작업 자체는 남지만, 번호가 달라졌으면 아무 일도 하지 않는다.
            final long expected = turnToken;
            // sleep()으로 게임 스레드를 멈추지 않고 나중에 실행할 일을 예약한다. 다른 사람 접속/채팅은 계속 처리한다.
            loop.schedule(() -> {
                if (running() && actor >= 0 && turnToken == expected) {
                    Player timedOut = players[actor];
                    log(timedOut.name + " 님 시간 초과 · 자동 체크/폴드");
                    act(timedOut, timedOut.street >= currentBet ? "CHECK" : "FOLD", 0);
                }
            }, turnSeconds, TimeUnit.SECONDS);
            // 새 상태를 모두에게 보내고 현재 작업을 끝낸다. 다음 메시지가 들어오면 handle()부터 다시 이어진다.
            broadcast(); return;
        }
    }
    // 모든 플레이어가 이번 핸드에 낸 contributed를 합친다. 폴드한 사람의 칩도 팟에 포함된다.
    private int pot() {
        int amount = 0;
        for (Player p : players) if (p != null) amount += p.contributed;
        return amount;
    }
    // [핸드 종료] showdown=true이면 공개된 카드로 족보를 비교한다.
    // showdown=false이면 모두 폴드해 한 명만 남았으므로 족보 비교 없이 정산한다.
    // 팟 분배 계산은 Pots.settle()에 맡기고 반환된 금액을 여기서 실제 보유 칩에 반영한다.
    private void finish(boolean showdown) {
        actor = -1; deadline = 0; turnToken++; reveal = showdown; lastPot = pot();
        // 좌석별 총 납부액, 승리 자격, 족보 점수 배열을 만들어 팟 계산기에 넘긴다.
        int[] contributions = new int[MAX_PLAYERS];
        boolean[] eligible = new boolean[MAX_PLAYERS];
        long[] strengths = new long[MAX_PLAYERS];
        StringBuilder details = new StringBuilder();
        for (Player p : players) if (p != null && p.inHand) {
            contributions[p.seat] = p.contributed; eligible[p.seat] = !p.folded;
            if (showdown && !p.folded) {
                // 공유 5장 + 개인 2장 = 7장에서 최고 5장 조합을 선택한다. 개인 카드를 반드시 2장 쓸 필요는 없다.
                List<Integer> seven = new ArrayList<Integer>(board); seven.addAll(p.hole);
                HandEvaluator.Rank rank = HandEvaluator.best(seven);
                strengths[p.seat] = rank.score;
                details.append(p.name).append(": ").append(rank.name()).append("  ");
            }
        }
        // 폴드 승리 때는 eligible이 한 명뿐이다. 그 경우 족보 점수의 크기를 비교할 상대가 없다.
        Pots.Result settlement = Pots.settle(contributions, eligible, strengths, dealer);
        StringBuilder winners = new StringBuilder();
        for (Player p : players) if (p != null) {
            int award = settlement.awards[p.seat], refund = settlement.refunds[p.seat];
            // award는 승리해서 받은 팟, refund는 상대가 따라오지 않아 돌려받은 내 칩이다. 둘을 잔액에 더한다.
            p.chips += award + refund;
            p.street = 0; p.contributed = 0;
            if (award > 0) winners.append(p.name).append(" +").append(award).append("  ");
            if (refund > 0) details.append(p.name).append(" 미콜 금액 반환 ").append(refund).append("  ");
        }
        phase = "FINISHED"; pending.clear(); currentBet = 0;
        result = (showdown ? "쇼다운 · " : "폴드 승리 · ") + winners + "\n" + details;
        log(result.replace('\n', ' '));
        // 정산이 끝나야 끊긴 사람의 좌석을 지운다. 이 기본 버전에는 재접속 계정·잔액 복원 기능이 없다.
        for (int i = 0; i < MAX_PLAYERS; i++)
            if (players[i] != null && !players[i].connected) players[i] = null;
        broadcast();
    }
    // 서버 콘솔과 연결된 모든 참가자에게 같은 LOG 문장을 보낸다. 채팅도 이 경로를 사용한다.
    private void log(String text) {
        System.out.println("[TABLE] " + text);
        String frame = Wire.message("LOG", "text", text);
        for (Player p : players) if (p != null && p.connected) p.peer.send(frame);
    }
    // 같은 게임 상태라도 보이는 개인 카드가 다르므로 참가자별 snapshot(p)를 각각 만든다.
    private void broadcast() {
        revision++;
        for (Player p : players) if (p != null && p.connected) p.peer.send(snapshot(p));
    }
    // [핵심 / 공개 범위] viewer라는 수신자에게 허용된 정보만 담는 STATE 메시지 생성기다.
    // 서버의 Player 객체를 통째로 보내지 않고 필요한 값만 새 Map으로 조립한다.
    // Map의 값 타입이 String이므로 숫자 앞의 빈 문자열은 문자열 변환 용도다.
    String snapshot(Player viewer) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("phase", phase); m.put("hand", "" + hand); m.put("revision", "" + revision);
        m.put("you", "" + viewer.seat); m.put("host", "" + host());
        m.put("dealer", "" + dealer); m.put("sb", "" + smallBlind); m.put("bb", "" + bigBlind);
        m.put("actor", "" + actor); m.put("turn", "" + turnToken); m.put("deadline", "" + deadline);
        m.put("pot", "" + pot()); m.put("lastPot", "" + lastPot); m.put("bet", "" + currentBet);
        m.put("board", Cards.encode(board)); m.put("result", result);
        // 수신자별로 시작/충전/레이즈 가능 여부와 필요한 콜 금액을 보내 GUI가 버튼을 켜고 끌 수 있게 한다.
        m.put("canStart", "" + (!running() && viewer.seat == host() && funded() >= 2));
        m.put("canRebuy", "" + (!running() && viewer.chips == 0));
        m.put("toCall", "" + Math.min(viewer.chips, Math.max(0, currentBet - viewer.street)));
        m.put("canRaise", "" + (running() && actor == viewer.seat && mayRaise(viewer)));
        m.put("minRaiseTo", "" + (currentBet + lastFullRaise));
        m.put("maxRaiseTo", "" + (viewer.street + viewer.chips));
        for (Player p : players) if (p != null) {
            // 한 메시지에 여러 사람을 넣기 위해 p0.name, p1.chips 같은 키를 만든다.
            String prefix = "p" + p.seat + ".";
            m.put(prefix + "name", p.name); m.put(prefix + "chips", "" + p.chips);
            m.put(prefix + "bet", "" + p.street); m.put(prefix + "connected", "" + p.connected);
            m.put(prefix + "inHand", "" + p.inHand);
            String status = !p.inHand ? "대기" : p.folded ? "폴드" : p.chips == 0 ? "올인" : "참가";
            if (!p.connected) status += " / 연결 종료";
            m.put(prefix + "status", status);
            // 본인의 패이거나, 쇼다운에서 폴드하지 않은 공개 대상 패일 때만 실제 카드를 담는다.
            // 숨겨야 하는 카드는 ""만 전송한다. 상대 클라이언트 메모리에 비밀 패가 도착하지 않게 하는 지점이다.
            boolean visible = p == viewer || (reveal && p.inHand && !p.folded);
            m.put(prefix + "cards", visible ? Cards.encode(p.hole) : "");
        }
        return Wire.message("STATE", m);
    }
}
