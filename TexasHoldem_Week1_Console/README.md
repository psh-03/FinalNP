# 텍사스 홀덤 1주차 통신 실습

팀 37 / 송준석, 표신현

Java 서버 1개와 클라이언트 1개가 TCP로 문장을 주고받는 최소 실습입니다.
클라이언트가 입력한 문장을 보내면 서버가 그 문장을 그대로 돌려줍니다(에코).

## 동작

- 서버: 연결 대기 → 받은 문장 표시 → 원문 응답.
- 클라이언트: 콘솔에서 문장 입력 → 전송 → 서버 응답 한 번 표시.
- `QUIT`: 클라이언트에서 연결을 닫고 종료합니다. 서버에는 전송하지 않습니다.
- 서버는 연결이 닫혀 `readLine()`이 `null`을 반환하면 종료합니다.
- 빈 줄도 원문인 빈 줄로 응답합니다.

별도 입력창과 WELCOME, ACK, BYE 응답을 제거했습니다.
화면의 `받은 메시지:`와 `서버 응답:`은 확인용 표시이며 네트워크로 전송되지 않습니다.
예를 들어 `안녕하세요`를 입력하면 실제 응답 문자열도 `안녕하세요`입니다.

## 역할과 범위

- 송준석: 접속 대기, 문자열 수신과 에코 응답, 연결 종료, 서버 역할과 참가 규약 초안.
- 표신현: 서버 접속, 콘솔 입력과 송수신, 연결 실패 안내, 화면 배치 초안.
- 공동: 규칙과 메시지 형식 검토, 한 명과의 통신 확인.
- 다음 단계: 수업 다중 클라이언트 코드 검토, 두 명 접속, 참가자 관리, 기본 게임 화면.

현재는 한 번의 접속만 처리하며, 그 연결이 끝나면 서버도 종료합니다.
게임 화면·채팅방·카드 배분·베팅·족보 판정은 이후 구현할 내용입니다.
`docs/screen_draft.png`는 화면 스케치이고 실제 실행 화면은 아닙니다.
`docs/01_week1_design.md`의 `/JOIN`, `/JOIN_OK`도 아직 구현하지 않은 설계 초안입니다.

## Eclipse에서 실행하기 (권장)

1. JDK 17 이상을 준비합니다.
2. File > Import > General > Existing Projects into Workspace를 선택합니다.
3. 압축을 푼 `TexasHoldem_Week1_Console` 폴더에서 두 프로젝트를 가져옵니다.
4. Run > Run Configurations > Java Application에서 서버와 클라이언트 실행 설정을 만듭니다.
   - 서버 Main class: `poker.server.ServerMain`
   - 클라이언트 Main class: `poker.client.ClientMain`
5. **두 실행 설정의 Common > Encoding > Other를 UTF-8로 지정**하고 Apply를 누릅니다.
6. 서버부터 실행하고 클라이언트를 실행합니다.
7. 클라이언트 Console에 `안녕하세요`를 입력하고 Enter를 누릅니다.
8. `서버 응답: 안녕하세요`를 확인하고, `QUIT`으로 종료합니다.

프로젝트와 Java 소스도 UTF-8로 저장되어 있습니다.
이전 실행 설정에 있던 `-Dpoker.inputMode=dialog`, `-Dpoker.consoleEncoding=MS949`는 삭제하세요.
코드는 해당 옵션을 더 이상 사용하지 않습니다.

## Windows 배치 실행

이 배포본의 압축 폴더는 `TexasHoldem_Week1_Console`입니다.
창 제목은 서버 `Week1 Server - Console`, 클라이언트 `Week1 Client - Console`입니다.
배치는 `dist/week1-server-console.jar`, `dist/week1-client-console.jar`를 실행합니다.
작은 입력 팝업이나 WELCOME, ACK가 보이면 다른 버전이 실행된 것입니다.

1. 기존 실행 창을 닫고 최신 ZIP을 **새 폴더에** 풉니다.
2. `run-server.bat` → `run-client.bat` 순서로 실행합니다.
3. 입력창 팝업 없이 클라이언트 CMD 창에서 직접 입력합니다.

기본 주소는 `127.0.0.1`, 포트는 `5555`입니다.
새 서버와 새 클라이언트를 함께 사용하세요. 이전 버전과는 응답 방식이 다릅니다.
다시 실습할 때는 서버도 다시 실행해야 합니다.
수정 후에는 JDK의 `javac`, `jar`가 실행되는 환경에서 `build.bat`로 JAR를 새로 만듭니다.

CMD에서는 `Console.reader()`로 입력을 읽고 출력도 Java가 확인한 콘솔 문자 설정에 맞춥니다.
Eclipse처럼 시스템 콘솔이 없는 환경에서는 입력과 출력을 UTF-8로 처리합니다.
소켓 통신은 모든 환경에서 UTF-8로 고정합니다.
Windows CMD/IME 실기는 이 검증 환경에서 확인하지 못했습니다.
CMD에서 한글 문제가 계속되면 위의 Eclipse UTF-8 설정으로 실행하세요.

## 통신 약속

| 항목 | 현재 구현 |
|---|---|
| 연결 | TCP, 기본 포트 5555, 클라이언트 1명 |
| 문자 | UTF-8 |
| 메시지 구분 | 한 줄을 한 메시지로 사용 |
| 요청 | 사용자가 입력한 원문 + 줄바꿈 |
| 응답 | 서버가 받은 원문 + 줄바꿈 |
| 종료 | 클라이언트에서 QUIT 입력 → 소켓 닫기 → 서버에서 EOF 확인 |

`write()`는 문자를 출력 버퍼에 기록하고 `newLine()`은 메시지 끝을 표시합니다.
`flush()`는 버퍼의 내용을 전송합니다. 수신 측 `readLine()`에는 끝의 줄바꿈이 포함되지 않습니다.
`QUIT`은 클라이언트 내부 명령입니다. 다른 프로그램이 서버에 QUIT 문자열을 직접 보내면
서버는 일반 문자열처럼 그대로 응답합니다.

## 실제 검증과 개인 확인

`evidence/`의 로그는 작성 지원 환경(OpenJDK 21, Linux)에서 얻었습니다.
Java 클래스는 JDK 17 대상 형식으로 컴파일했습니다. 학생 PC의 실행 화면을 대신하지 않습니다.

| 검사 | 확인 내용 | 로그 |
|---|---|---|
| T1 | 한글·공백·영문 원문 왕복, QUIT 미전송과 종료 | 01_normal_server.txt / 01_normal_client.txt |
| T2 | 서버 미실행 시 연결 실패 안내 | 02_no_server_client.txt |
| T3 | 별도 접속 안내 없음, 줄바꿈 뒤 원문 응답, EOF 종료 | 03_newline_probe.txt / 03_newline_server.txt |
| T4 | 빈 줄과 그다음 문장 처리 | 04_empty_server.txt / 04_empty_client.txt |
| T5 | Linux 터미널에서 직접 한글 입력과 원문 응답 | 05_terminal_server.txt / 05_terminal_client.txt |

T1~T4는 파이프로 입력을 주입하므로 로그에 키보드 입력 자체는 표시되지 않습니다.
T5는 Linux PTY로 `Console.reader()` 경로를 검사하며 Windows CMD/IME 검증은 아닙니다.
Python이 있으면 프로젝트 루트에서 `python tests/run_checks.py`로 검증을 다시 실행할 수 있습니다.
Java 프로그램 실행에는 Python이나 외부 Java 라이브러리가 필요하지 않습니다.

보고서의 학번과 개인 실습 확인란을 채우고 실제 실행 결과와 맞춰 제출하세요.
한글 송수신, 원문 일치, 종료를 직접 확인한 화면을 사용하세요.

## 참고

- Java 17 System API: https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/System.html
- Java 17 Console API: https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/io/Console.html
