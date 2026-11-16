"""선택 사항: Python 3으로 실제 Java 서버에 여러 TCP 클라이언트를 연결해 검사한다."""
from pathlib import Path
import json
import select
import socket
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / "evidence"
EVIDENCE.mkdir(exist_ok=True)
results = []
peers = []


class Peer:
    def __init__(self, port):
        self.socket = socket.create_connection(("127.0.0.1", port), timeout=3)
        self.buffer = b""
        self.pending = []
        self.history = []
        self.closed = False
        peers.append(self)

    def send(self, text):
        self.socket.sendall((text + "\n").encode("utf-8"))

    def receive(self, timeout):
        if self.closed or not select.select([self.socket], [], [], timeout)[0]:
            return
        data = self.socket.recv(8192)
        if not data:
            self.closed = True
            return
        self.buffer += data
        while b"\n" in self.buffer:
            line, self.buffer = self.buffer.split(b"\n", 1)
            text = line.rstrip(b"\r").decode("utf-8")
            self.pending.append(text)
            self.history.append(text)

    def until(self, predicate, timeout=3):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            while self.pending:
                text = self.pending.pop(0)
                if predicate(text):
                    return text
            self.receive(max(0, end - time.monotonic()))
            if self.closed and not self.pending:
                break
        raise AssertionError("Expected response missing; received " + repr(self.history[-8:]))

    def line(self, prefix):
        return self.until(lambda s: s.startswith(prefix))

    def drain(self):
        self.receive(0.06)
        self.pending.clear()

    def close(self):
        self.socket.close()
        self.closed = True


def check(label, condition=True):
    assert condition, label
    results.append({"test": label, "result": "PASS"})


def join(port, nickname):
    p = Peer(port)
    p.send("/JOIN " + nickname)
    p.id = int(p.line("/JOIN_OK ").split()[1])
    return p


with socket.socket() as reserved:
    reserved.bind(("127.0.0.1", 0))
    port = reserved.getsockname()[1]
jar = ROOT / "dist/week2-server.jar"
command = ["java", "-jar", str(jar), str(port)] if jar.exists() else [
    "java", "-cp", str(ROOT / "bin"), "ServerMain", str(port)]
log = (EVIDENCE / "server_test.log").open("w", encoding="utf-8")
server = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
try:
    deadline = time.monotonic() + 5
    while not (EVIDENCE / "server_test.log").read_text(encoding="utf-8"):
        assert server.poll() is None, "Server exited early"
        assert time.monotonic() < deadline, "Server start timeout"
        time.sleep(0.03)

    a = join(port, "준석")
    a.send("/START")
    check("1명일 때 시작 거절", "2명" in a.line("/ERROR "))
    b = join(port, "신현")
    check("두 클라이언트의 고유 ID", a.id != b.id)
    a.until(lambda s: s.startswith("/TABLE ") and s.split()[3] == "2")
    b.until(lambda s: s.startswith("/TABLE ") and s.split()[3] == "2")

    duplicate = Peer(port)
    duplicate.send("/JOIN 준석")
    check("중복 닉네임 거절", "이미 사용" in duplicate.line("/ERROR "))
    duplicate.close()
    invalid = Peer(port)
    invalid.send("/JOIN 공백 닉네임")
    check("잘못된 닉네임 거절", "닉네임" in invalid.line("/ERROR "))
    invalid.close()
    prejoin = Peer(port)
    prejoin.send("/CHAT 등록 전")
    check("미등록 연결의 명령 거절", "먼저" in prejoin.line("/ERROR "))
    prejoin.close()

    a.drain(); b.drain()
    text = "한글 채팅  테스트! /JOIN 명령처럼 보여도 대화입니다."
    a.send("/CHAT " + text)
    check("한글과 중간 공백 채팅을 두 사람에게 전달",
          a.line("/CHAT ") == b.line("/CHAT ") == "/CHAT 준석 " + text)
    b.send("/START")
    check("일반 참가자의 시작 요청 거절", "방장" in b.line("/ERROR "))
    a.drain(); b.drain()
    a.send("/START")
    hands = [p.until(lambda s: s.startswith("/HAND ")).split()[1:] for p in [a, b]]
    check("개인 카드 2장씩 배분 및 네 장 중복 없음",
          all(len(h) == 2 for h in hands) and len(set(sum(hands, []))) == 4)
    check("전체 상태에는 개인 카드 필드가 없음",
          all(len(row.split(",")) == 3 for p in [a, b] for line in p.history
              if line.startswith("/TABLE ") for row in line.split()[4:]))
    (EVIDENCE / "two_client_protocol.txt").write_text(
        "[클라이언트 A가 받은 데이터]\n" + "\n".join(a.history) +
        "\n\n[클라이언트 B가 받은 데이터]\n" + "\n".join(b.history) + "\n", encoding="utf-8")

    c = join(port, "세번째")
    a.until(lambda s: s.startswith("/TABLE ") and " WAITING 3 " in s)
    a.until(lambda s: s == "/HAND")
    check("참가자 변경 시 대기 상태와 빈 손패로 초기화")
    a.close()
    new_state = b.until(lambda s: s.startswith("/TABLE ") and s.split()[3] == "2")
    check("방장 퇴장 후 다음 참가자에게 방장 이전", int(new_state.split()[1]) == b.id)

    active = [b, c]
    active.extend(join(port, "참가자" + str(i)) for i in range(4))
    active[-1].until(lambda s: s.startswith("/TABLE ") and s.split()[3] == "6")
    extra = Peer(port)
    extra.send("/JOIN 정원초과")
    check("6명 정원과 7번째 참가 거절", "정원" in extra.line("/ERROR "))
    extra.close()
    for p in active: p.drain()
    for repeat in range(2):
        b.send("/START")
        six_hands = [p.until(lambda s: s.startswith("/HAND ")).split()[1:] for p in active]
        check("6명 카드 배분 " + str(repeat + 1) + "회에서 12장 중복 없음",
              all(len(h) == 2 for h in six_hands) and len(set(sum(six_hands, []))) == 12)
        for p in active: p.drain()
    b.send("/CHAT " + "가" * 201)
    check("긴 채팅 거절", "200" in b.line("/ERROR "))
    b.send("/UNKNOWN")
    check("알 수 없는 명령 거절", "알 수 없는" in b.line("/ERROR "))
    check("WELCOME ACK BYE 확인 문자열을 사용하지 않음",
          all(not line.startswith(("WELCOME", "ACK ", "BYE")) for p in peers for line in p.history))
    for p in active: p.close()
    deadline = time.monotonic() + 3
    while "(0명)" not in (EVIDENCE / "server_test.log").read_text(encoding="utf-8"):
        assert time.monotonic() < deadline, "Disconnect cleanup timeout"
        time.sleep(0.03)
    fresh = join(port, "다시접속")
    fresh.until(lambda s: s.startswith("/TABLE ") and s.split()[3] == "1")
    check("모두 나간 뒤에도 서버가 새 접속 처리")
    fresh.close()
    print(json.dumps(results, ensure_ascii=False, indent=2))
finally:
    for p in peers:
        if not p.closed: p.close()
    server.terminate()
    server.wait(timeout=5)
    log.close()
    (EVIDENCE / "network_results.json").write_text(json.dumps({
        "environment": "Linux OpenJDK 21, Java 17 대상 빌드, 실제 TCP 소켓 검사",
        "tests": results
    }, ensure_ascii=False, indent=2), encoding="utf-8")
