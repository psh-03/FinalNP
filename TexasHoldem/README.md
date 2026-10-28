# Java Texas Hold'em — 네트워크프로그래밍 기본 프로젝트

**독립 실행하는 Java 서버 1개 + Swing 클라이언트 2~6개**로 플레이하는 텍사스 홀덤입니다. 수업에서 코드를 읽고 확장하기 위한 단일 테이블 기본 버전입니다. 칩은 게임 안에서만 쓰는 가상 점수입니다.

## 1. 먼저 실행해 보기

Java 17 이상이 필요합니다. 소스를 다시 빌드하려면 JRE가 아닌 **JDK 17 이상**을 설치하고 `java`, `javac` 명령을 사용할 수 있어야 합니다. 외부 Java 라이브러리, Maven, 데이터베이스는 필요하지 않습니다.

### Windows — 포함된 실행 파일 사용

1. ZIP 압축을 풉니다. ZIP 안에서 바로 실행하지 마세요.
2. `run-server.bat`를 실행합니다. 서버 콘솔에 `TCP 5555`가 표시되면 준비된 상태입니다.
3. `run-client.bat`를 두 번 실행합니다. 클라이언트 창이 두 개 열립니다.
4. 두 창에서 서버 IP `127.0.0.1`, 포트 `5555`, 서로 다른 이름을 입력하고 **접속**합니다.
5. 방장 창에서 **핸드 시작**을 누릅니다. 같은 PC에서도 두 창을 번갈아 조작할 수 있습니다.
6. 자신의 테두리가 금색으로 표시되면 체크·콜·폴드·레이즈·올인을 선택합니다.

JAR 파일을 직접 실행해도 됩니다. 명령마다 별도의 터미널 창을 사용하세요.

```bash
java -jar dist/poker-server.jar
java -jar dist/poker-client.jar
java -jar dist/poker-client.jar
```

클라이언트 실행 인자로 접속 정보를 미리 채울 수도 있습니다. 접속 버튼은 직접 누릅니다.

```bash
java -jar dist/poker-client.jar 127.0.0.1 5555 PlayerA
```

### Eclipse — 소스 코드 실행

1. **File → Import → General → Existing Projects into Workspace**를 선택합니다.
2. **Select root directory**에 압축을 푼 `TexasHoldem` 폴더를 지정합니다.
3. `PokerCommon`, `PokerServer`, `PokerClient` 세 프로젝트를 모두 선택해 가져옵니다. 필요하면 **Search for nested projects**를 켭니다.
4. JRE 오류가 있다면 **Window → Preferences → Java → Installed JREs**에서 JDK 17 이상을 등록하고 JavaSE-17 실행 환경을 연결합니다.
5. `PokerServer/src/poker/server/ServerMain.java`를 우클릭하고 **Run As → Java Application**으로 실행합니다.
6. `PokerClient/src/poker/client/ClientMain.java`를 같은 방법으로 두 번 실행합니다.

**실행 대상은 `ServerMain.java`와 `ClientMain.java`입니다.** `TablePanel.java`는 화면을 그리는 부품이어서 직접 실행하는 클래스가 아닙니다. `Run As`에 Ant Build만 보이면 선택한 파일을 다시 확인하세요.

### 서로 다른 컴퓨터에서 접속

- 서버 컴퓨터에서 서버를 한 번 실행합니다.
- 서버 컴퓨터의 `ipconfig`에 표시되는 해당 LAN 어댑터의 IPv4 주소를 확인합니다.
- 클라이언트 컴퓨터들은 서버 IP 칸에 그 주소를 입력합니다. 다른 PC의 서버에 연결할 때 `127.0.0.1`을 입력하면 안 됩니다.
- 같은 공유기/LAN에서 실행하고 서버 컴퓨터의 방화벽에서 Java의 해당 네트워크 연결 또는 TCP 5555 접근을 허용합니다.
- 학교 Wi-Fi의 기기 간 통신 차단이 있으면 같은 Wi-Fi여도 접속이 안 될 수 있습니다.

포트와 행동 제한 시간을 바꾸는 예:

```bash
java -jar dist/poker-server.jar 6000 60
```

클라이언트 포트도 `6000`으로 맞춥니다. 기본 포트는 5555, 기본 차례 제한은 45초입니다.

## 2. 구현된 기능과 게임 흐름

| 구분 | 동작 |
|---|---|
| 접속 | 2~6명, 이름 중복 검사, 테이블이 가득 차면 접속 거절 |
| 시작 | 칩이 있는 접속자 2명 이상일 때 방장이 핸드 시작 |
| 초기 칩 | 1인 1,000점, 고정 블라인드 10/20 |
| 카드 | 52장 중복 없는 셔플, 개인 카드 2장, 공유 카드 최대 5장 |
| 진행 | 프리플롭 → 플롭 3장 → 턴 1장 → 리버 1장 → 쇼다운 |
| 행동 | 체크, 콜, 폴드, 베팅/레이즈, 올인 |
| 판정 | 개인 2장과 공유 5장 중 가장 좋은 5장 조합, 족보와 키커 비교 |
| 올인 | 메인팟·사이드팟 분리, 미콜 금액 반환, 올인 후 자동 보드 진행 |
| 동점 | 팟 균등 분배, 나머지 1점은 딜러 다음 순서의 해당 승자부터 배분 |
| 다음 핸드 | 방장이 다시 시작, 참가 가능한 좌석 순서로 딜러 회전 |
| 중도 접속 | 현재 핸드를 관찰하고 다음 핸드부터 참가 |
| 연결 종료 | 자기 차례에 콜 금액이 없으면 자동 체크, 있으면 자동 폴드. 올인 패는 판정 유지 |
| 시간 초과 | 현재 차례의 제한 시간이 지나면 자동 체크/폴드 |
| 채팅 | UTF-8 한글 채팅, 최대 200자 |
| 칩 충전 | 핸드가 끝나고 칩이 0인 참가자는 1,000점으로 충전 가능 |

**레이즈 입력은 ‘이번 베팅 단계의 총액’입니다.** 예를 들어 이번 단계에 20을 냈고 입력창에 60을 입력했다면 추가로 40을 냅니다. 플롭·턴·리버로 넘어갈 때 이 단계의 베팅액은 0으로 초기화됩니다. 최소 레이즈는 화면에 표시되고, 서버가 다시 검사합니다. 남은 칩이 최소 레이즈보다 적은 경우에는 올인 예외가 적용됩니다.

**팟 획득 로그의 `+금액`은 받은 팟 금액입니다.** 그 핸드의 순이익과는 다릅니다. 이미 낸 칩을 제외한 최종 잔액은 각 좌석에 표시됩니다.

## 3. 서버와 클라이언트 분리

| 프로젝트 | 역할 | 실행 클래스 |
|---|---|---|
| `PokerCommon` | 통신 메시지 인코딩·해석, 카드 코드 표현 | 직접 실행하지 않음 |
| `PokerServer` | 연결 관리, 카드 분배, 차례·베팅 검증, 승패·팟 판정 | `poker.server.ServerMain` |
| `PokerClient` | 접속 화면, 카드·칩 표시, 사용자 입력 전송, 채팅 | `poker.client.ClientMain` |

`PokerCommon`은 두 프로그램이 같이 참조하는 코드입니다. 실행 시 별도 서버 프로세스가 생기는 프로젝트는 아닙니다. 배포용 서버 JAR과 클라이언트 JAR에는 필요한 공통 클래스가 각각 포함됩니다. 클라이언트 JAR에는 서버의 게임 판정 클래스가 들어가지 않습니다.

| 주요 파일 | 설명 |
|---|---|
| `PokerServer/src/poker/server/ServerMain.java` | `ServerSocket.accept()`로 연결을 수락하고 세션 생성 |
| `PokerServer/src/poker/server/ClientSession.java` | 클라이언트별 소켓 수신·송신 스레드와 송신 큐 |
| `PokerServer/src/poker/server/GameTable.java` | 핸드 상태, 요청 검증, 차례 진행, 개인별 상태 전송 |
| `PokerServer/src/poker/server/HandEvaluator.java` | 5~7장의 카드에서 최고 족보 계산 |
| `PokerServer/src/poker/server/Pots.java` | 사이드팟, 동점 배분, 미콜 금액 반환 |
| `PokerClient/src/poker/client/ClientMain.java` | 실행 진입점, Swing 창, 버튼 이벤트 |
| `PokerClient/src/poker/client/TablePanel.java` | 서버에서 받은 상태를 카드 테이블로 그림 |
| `PokerClient/src/poker/client/PokerConnection.java` | 접속·수신·송신, heartbeat |
| `PokerCommon/src/poker/common/Wire.java` | 길이 제한이 있는 줄 단위 메시지 프로토콜 |

### 한 번의 베팅이 처리되는 과정

1. 클라이언트의 버튼 이벤트가 `ACT` 메시지를 송신 큐에 넣습니다.
2. 별도 송신 스레드가 소켓으로 메시지를 전송합니다.
3. 서버 수신 스레드가 한 메시지를 복원하고 게임 스레드에 처리를 맡깁니다.
4. `GameTable`이 핸드 번호, 차례 토큰, 실제 차례, 베팅액을 검사합니다.
5. 올바른 요청만 서버 상태에 반영하고, 각 참가자용 `STATE`를 따로 만듭니다.
6. 각 클라이언트가 받은 상태를 `SwingUtilities.invokeLater()`를 통해 화면에 반영합니다.

게임 상태는 **서버의 단일 게임 스레드만 변경**합니다. 여러 사람이 동시에 버튼을 눌러도 차례와 칩 계산이 동시에 수정되지 않도록 한 구조입니다. 소켓 읽기·쓰기는 게임 스레드와 별도로 처리합니다. 느린 클라이언트 때문에 전체 테이블이 송신에서 멈추지 않도록 클라이언트별 송신 큐를 둡니다.

상대 개인 카드는 단순히 화면에서 가리는 것이 아닙니다. 서버가 수신자별 `STATE`를 만들 때 **상대 카드 자체를 빈 문자열로 보냅니다**. 쇼다운에 남은 패만 공개하고, 폴드로 종료되면 상대 패를 공개하지 않습니다.

## 4. 통신 프로토콜

TCP는 메시지 경계가 없으므로 줄바꿈 `\n`을 하나의 메시지 끝으로 정했습니다. 하나의 메시지가 여러 TCP 수신으로 나뉘거나, 여러 메시지가 한 번에 수신되어도 `Wire.readLine()`이 메시지를 구분합니다.

형식은 `TYPE\tkey=value\tkey=value\n`입니다. `\t`는 실제 탭 문자입니다. 각 value는 UTF-8로 URL 인코딩하므로 한글·공백·탭·특수문자가 구분자를 깨뜨리지 않습니다. 최대 메시지 길이는 16,384자입니다.

| 방향 | 메시지 | 주요 필드 |
|---|---|---|
| 클라이언트 → 서버 | `JOIN` | `name` |
| 클라이언트 → 서버 | `START` | 없음 |
| 클라이언트 → 서버 | `ACT` | `hand`, `turn`, `action`, `amount` |
| 클라이언트 → 서버 | `CHAT` | `text` |
| 클라이언트 → 서버 | `REBUY` | 없음 |
| 클라이언트 → 서버 | `PING` | 없음 |
| 서버 → 클라이언트 | `STATE` | 전체 공개 상태 + 수신자에게 허용된 카드 |
| 서버 → 클라이언트 | `LOG` | `text` |
| 서버 → 클라이언트 | `ERROR` | `text` |
| 서버 → 클라이언트 | `PONG` | 없음 |

`ACT.action`은 `FOLD`, `CHECK`, `CALL`, `RAISE` 중 하나입니다. 올인은 별도 규칙 경로를 만들지 않고, 남은 칩에 따라 올인 콜 또는 최대 총액 레이즈로 보냅니다. `amount`는 `RAISE`에서만 읽습니다.

```java
Wire.message("JOIN", "name", "플레이어A");
Wire.message("ACT", "hand", "1", "turn", "3", "action", "RAISE", "amount", "60");
```

클라이언트가 아무 숫자나 보내더라도 서버가 요청을 검증합니다. `hand`와 `turn`은 이전 핸드의 메시지나 버튼 중복 클릭으로 남은 요청이 다음 차례에서 실행되는 것을 막습니다. 인증 토큰은 아닙니다. 자신의 좌석은 소켓 세션에 연결되어 있어 클라이언트가 플레이어 번호를 바꾸어 다른 사람을 대신할 수 없습니다.

## 5. 수정 후 빌드와 테스트

Windows:

```bat
build.bat
run-tests.bat
```

macOS/Linux:

```bash
bash build.sh
bash run-tests.sh
```

클라이언트 실행에는 그래픽 데스크톱 환경이 필요합니다. Linux에서 한글이 네모로 보이면 한글을 지원하는 시스템 폰트를 설치해야 합니다.

빌드 결과는 `dist/poker-server.jar`, `dist/poker-client.jar`입니다. Eclipse에서 소스를 수정한 것만으로 기존 `dist` JAR이 갱신되지는 않습니다. JAR을 배포할 때는 `build.bat` 또는 `build.sh`를 다시 실행하세요.

Java 테스트는 외부 테스트 라이브러리 없이 실행됩니다. Python 3가 있다면 실제 TCP 통합 테스트도 실행할 수 있습니다. 게임 자체에는 Python이 필요하지 않습니다.

```bash
python tests/network_test.py
```

검증 항목과 실제 실행 결과는 `VALIDATION.md`에 정리했습니다.

## 6. 기본 버전의 범위

- 단일 테이블입니다. 로그인 계정, 방 목록, 여러 테이블, 전적 저장, AI 상대는 아직 없습니다.
- 접속 종료 후 재접속은 새 참가입니다. 이전 칩·좌석을 복원하지 않습니다. 진행 중인 이전 좌석은 해당 핸드가 끝나야 해제됩니다.
- 기존 좌석을 유지한 참가자 중 가장 앞선 좌석 번호가 방장입니다. 방장이 나가면 자동으로 바뀝니다.
- 서버 재시작 시 상태가 초기화됩니다. 소스는 학습용 LAN 시연을 기준으로 작성했으며 인증·TLS·영구 저장은 포함하지 않습니다.
- 블라인드는 고정이고, 참가 가능 좌석을 따라 딜러가 이동합니다. 대회용 데드 버튼·탈락 시 블라인드 위치 보정·블라인드 레벨 상승·앤티는 구현하지 않았습니다.
- 쇼다운 카드 공개는 동시에 처리합니다. 실제 대회의 개별 공개 순서·먹(muck) 선택·오프라인 진행 판정은 다루지 않습니다.
- `STATE`의 시간은 서버 시계 기준이므로 PC 시계가 다르면 클라이언트의 남은 시간 표시는 오차가 날 수 있습니다. 실제 시간 초과 처리는 서버가 결정합니다.

## 7. 발표에서 설명할 핵심

“TCP 소켓 기반의 다중 클라이언트 텍사스 홀덤입니다. 서버가 게임 상태와 규칙 판정을 소유하고, 클라이언트는 GUI와 입력을 담당합니다. 각 소켓의 I/O 스레드와 단일 게임 스레드를 분리해 동시성 문제를 줄였고, 수신자별 상태 메시지로 개인 카드를 보호했습니다. 메시지 경계를 줄바꿈으로 정의하고, 핸드 번호와 차례 토큰으로 중복·지연 요청을 검증합니다.”

다음 확장 과제로는 채팅 분리, 방 생성/입장, 서버 관리 GUI, 접속 복구, DB 전적 저장 중 수업 요구사항에 맞는 것을 선택할 수 있습니다.

## 참고한 공식 자료

- [Oracle Java: ServerSocket과 다중 클라이언트](https://docs.oracle.com/javase/tutorial/networking/sockets/clientServer.html)
- [Oracle Java: Socket 읽기와 쓰기](https://docs.oracle.com/javase/tutorial/networking/sockets/readingWriting.html)
- [Oracle Swing: Event Dispatch Thread](https://docs.oracle.com/javase/tutorial/uiswing/concurrency/dispatch.html)
- [Poker TDA: 베팅·레이즈·팟 분배 규칙 참고](https://www.pokertda.com/view-poker-tda-rules/)

Oracle 튜토리얼은 JDK 8 기준 설명이지만 여기서는 Java 17을 빌드·검증 기준으로 사용했습니다. Poker TDA 자료는 개념 확인용으로 참고했으며, 이 프로젝트가 모든 대회 규칙을 구현하거나 인증받았다는 뜻은 아닙니다.
