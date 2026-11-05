# 텍사스 홀덤 1주차 통신 실습

팀 37 / 송준석, 표신현

서버 1개와 클라이언트 1개가 TCP 소켓으로 문자열을 주고받는 콘솔 실습입니다.
개인 보고서에서 설명하는 코드는 이 폴더의 소스와 일치합니다.

## 역할과 이번 주 범위

- 송준석: `PokerServer/src/poker/server/ServerMain.java`의 접속 대기, 수신, ACK 응답, 종료 처리.
- 표신현: `PokerClient/src/poker/client/ClientMain.java`의 서버 접속, 콘솔 입력, 송수신, 연결 실패 안내.
- 이번 주 기능: 한글 문자열 왕복 송수신, 빈 메시지 오류 응답, QUIT 종료.
- 다음 단계: 여러 클라이언트 동시 접속, 닉네임 관리, GUI와 수신 스레드, 게임 규칙 구현.

이 실습본은 클라이언트 한 명만 처리합니다. 접속이 끝나면 서버도 종료합니다.
GUI, 채팅방, 카드 배분, 베팅, 족보 판정은 이 실습본에 포함되지 않습니다.

## Eclipse에서 열기

1. JDK 17 이상을 준비합니다.
2. ZIP을 풀고 File > Import > General > Existing Projects into Workspace를 선택합니다.
3. `TexasHoldem_Week1` 폴더를 root directory로 지정합니다.
4. `PokerServer_Week1`, `PokerClient_Week1`을 모두 가져옵니다.
5. `poker.server.ServerMain.java`를 Run As > Java Application으로 먼저 실행합니다.
6. `poker.client.ClientMain.java`를 같은 방법으로 실행합니다.
7. 클라이언트 Console에 `안녕하세요`를 입력하고 Enter를 누릅니다.
8. `ACK 안녕하세요`가 오는지 확인한 뒤 `QUIT`으로 종료합니다.

기본 주소는 `127.0.0.1`, 포트는 `5555`입니다. 같은 PC에서 실습합니다.
기존 완성본의 서버가 5555 포트를 사용하고 있으면 종료한 뒤 실행하세요.
Eclipse Console의 입력·출력이 깨지면 실행 설정의 Common > Encoding을 UTF-8로 맞춥니다.

## 배치 파일로 실행

Windows에서는 `run-server.bat`를 먼저, `run-client.bat`를 다음에 실행합니다.
JAR 파일이 포함되어 있어 소스를 바꾸지 않았다면 바로 실행할 수 있습니다.
수정 후에는 `build.bat`로 JAR를 다시 만듭니다.

서버와 클라이언트의 포트를 변경하는 예:

```text
java -Dfile.encoding=UTF-8 -jar dist/week1-server.jar 6000
java -Dfile.encoding=UTF-8 -jar dist/week1-client.jar 127.0.0.1 6000
```

## 메시지 약속

| 메시지 | 의미 |
|---|---|
| WELCOME | 서버가 연결 직후 보내는 안내 |
| 일반 문자열 | 클라이언트가 입력한 한 줄 |
| ACK + 원문 | 서버가 해당 문자열을 받았다는 응답 |
| ERROR + 설명 | 빈 줄에 대한 오류 응답 |
| QUIT / BYE | 클라이언트 종료 요청 / 서버 종료 응답 |

모든 네트워크 문자열은 UTF-8입니다. 한 줄을 메시지 하나로 구분합니다.
`write()` 후 `newLine()`으로 메시지 끝을 쓰고 `flush()`로 출력 버퍼를 비웁니다.
`readLine()`이 반환하는 문자열에는 끝의 줄바꿈 문자가 포함되지 않습니다.
ACK와 ERROR는 이 실습에서 직접 정한 문자열이며 TCP 자체의 제어 메시지가 아닙니다.

## 실행 근거와 개인 확인

`evidence/`는 작성 지원 환경(OpenJDK 17, Linux)에서 실제 실행해 얻은 로그입니다.
학생 개인의 Windows 또는 Eclipse에서 실행한 화면을 대신한 자료는 아닙니다.
각자 소스를 읽고 실행한 뒤 보고서의 학번과 개인 실습 확인란을 채우세요.
동작이 다르면 실제 결과에 맞춰 보고서를 수정하고 Console 내용을 함께 제출하세요.

| 검증 | 조건 | 근거 파일 |
|---|---|---|
| T1 | 한글 2회 송수신, QUIT | 01_normal_server.txt / 01_normal_client.txt |
| T2 | 서버 미실행 시 클라이언트 접속 | 02_no_server_client.txt |
| T3 | 줄바꿈 없이 전송 후 LF 추가 | 03_newline_probe.txt / 03_newline_server.txt |
| T4 | 빈 줄 전송 후 QUIT | 04_empty_server.txt / 04_empty_client.txt |

Python 3가 있으면 선택적으로 자동 검증을 다시 실행할 수 있습니다.
프로젝트 루트에서 `python tests/run_checks.py`를 실행합니다.
실행할 때마다 `evidence/`의 로그가 새 결과로 갱신됩니다.
검증은 빈 포트를 골라 사용하므로 로그의 포트 번호는 기본 포트 5555와 다를 수 있습니다.
T3의 Python 코드는 줄바꿈을 생략하는 실험용 클라이언트입니다.
Java 프로젝트 자체의 실행에는 Python이나 외부 Java 라이브러리가 필요하지 않습니다.

## 제출 전

각자의 개인 보고서와 이 프로젝트 폴더를 압축한 ZIP을 제출합니다.
개인 보고서는 담당 코드에 대한 설명과 직접 확인한 실행 결과를 기준으로 최종 점검합니다.
