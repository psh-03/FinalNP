"""Real TCP/JAR integration checks. Optional Python 3 standard library only."""
from pathlib import Path
import select
import socket
import subprocess
import time
from urllib.parse import quote_plus, unquote_plus

ROOT = Path(__file__).resolve().parents[1]


def frame(kind, **fields):
    return (kind + "".join("\t" + k + "=" + quote_plus(str(v)) for k, v in fields.items()) + "\n").encode()


def parse(line):
    parts = line.decode().split("\t")
    return dict(type=parts[0], **{k: unquote_plus(v) for k, v in (p.split("=", 1) for p in parts[1:])})


class Client:
    def __init__(self, port):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=3)
        self.sock.setblocking(False)
        self.buffer = b""
        self.state = {}
        self.errors = []
        self.logs = []
        self.pongs = 0

    def send(self, kind, **fields):
        self.sock.sendall(frame(kind, **fields))

    def read(self):
        data = self.sock.recv(65536)
        if not data:
            raise AssertionError("unexpected closed socket")
        self.buffer += data
        while b"\n" in self.buffer:
            line, self.buffer = self.buffer.split(b"\n", 1)
            m = parse(line)
            if m["type"] == "STATE":
                self.state = m
            elif m["type"] == "ERROR":
                self.errors.append(m["text"])
            elif m["type"] == "LOG":
                self.logs.append(m["text"])
            elif m["type"] == "PONG":
                self.pongs += 1


def wait(clients, predicate, timeout=4):
    deadline = time.monotonic() + timeout
    while not predicate():
        if time.monotonic() >= deadline:
            raise AssertionError("network wait timed out")
        sockets = [c.sock for c in clients]
        ready, _, _ = select.select(sockets, [], [], .05)
        for client in clients:
            if client.sock in ready:
                client.read()


def run():
    with socket.socket() as temporary:
        temporary.bind(("127.0.0.1", 0))
        port = temporary.getsockname()[1]
    log_path = ROOT / "build" / "network-test-server.log"
    log_path.parent.mkdir(exist_ok=True)
    clients = []
    with log_path.open("w", encoding="utf-8") as server_log:
        server = subprocess.Popen(["java", "-Dfile.encoding=UTF-8", "-jar", str(ROOT / "dist/poker-server.jar"), str(port), "2"], stdout=server_log, stderr=subprocess.STDOUT)
        try:
            for attempt in range(100):
                try:
                    first = Client(port)
                    break
                except OSError:
                    if server.poll() is not None:
                        raise AssertionError("server exited; see build/network-test-server.log")
                    time.sleep(.02)
            else:
                raise AssertionError("server failed to listen")
            clients.append(first)
            # Fragment a frame across two TCP writes; two other frames share one write.
            join = frame("JOIN", name="준석A")
            first.sock.sendall(join[:7])
            first.sock.sendall(join[7:] + frame("PING") + frame("PING"))
            wait(clients, lambda: first.state and first.pongs == 2)
            second = Client(port); clients.append(second); second.send("JOIN", name="플레이어B")
            wait(clients, lambda: second.state and first.state.get("canStart") == "true")
            first.send("START")
            wait(clients, lambda: all(c.state.get("phase") == "PREFLOP" for c in clients))
            assert first.state["p1.cards"] == second.state["p0.cards"] == ""
            assert len(first.state["p0.cards"].split(",")) == 2
            first_token = first.state["turn"]
            second.send("ACT", hand=1, turn=first_token, action="CALL")
            wait(clients, lambda: len(second.errors) == 1)
            assert second.state["actor"] == "0"
            request = frame("ACT", hand=1, turn=first_token, action="CALL")
            first.sock.sendall(request + request)
            wait(clients, lambda: first.errors and second.state.get("actor") == "1")
            second.send("ACT", hand=1, turn=second.state["turn"], action="CHECK")
            wait(clients, lambda: all(c.state.get("phase") == "FLOP" for c in clients))
            second.send("ACT", hand=1, turn=second.state["turn"], action="RAISE", amount=-1)
            wait(clients, lambda: len(second.errors) == 2)
            assert second.state["bet"] == "0"

            def play_to_end():
                for _ in range(100):
                    state = first.state
                    if state.get("phase") == "FINISHED":
                        wait(clients, lambda: all(c.state.get("phase") == "FINISHED" for c in clients))
                        return
                    actor = int(state["actor"])
                    active = next(c for c in clients if c.state.get("you") == str(actor))
                    token = state["turn"]
                    wait(clients, lambda: active.state.get("turn") == token)
                    active.send("ACT", hand=state["hand"], turn=token,
                                action="CHECK" if int(active.state["toCall"]) == 0 else "CALL")
                    wait(clients, lambda: first.state.get("turn") != token)
                raise AssertionError("hand failed to finish")

            play_to_end()
            assert all(first.state[f"p{i}.cards"] for i in range(2))
            assert sum(int(first.state[f"p{i}.chips"]) for i in range(2)) == 2000
            first.send("CHAT", text="한글 채팅 + & = 정상")
            wait(clients, lambda: any("한글 채팅 + & = 정상" in m for m in second.logs))
            # A fresh actor timeout folds an outstanding preflop call.
            first.send("START")
            wait(clients, lambda: first.state.get("hand") == "2")
            wait(clients, lambda: first.state.get("phase") == "FINISHED", timeout=5)
            assert first.state["board"] == "" and first.state["p1.cards"] == ""
            for i in range(2, 6):
                c = Client(port); clients.append(c); c.send("JOIN", name=f"P{i}")
                wait(clients, lambda: bool(c.state))
            seventh = Client(port); clients.append(seventh); seventh.send("JOIN", name="P6")
            wait(clients, lambda: bool(seventh.errors))
            clients.remove(seventh); seventh.sock.close()
            first.send("START")
            wait(clients, lambda: all(c.state.get("hand") == "3" for c in clients))
            play_to_end()
            assert sum(int(first.state[f"p{i}.chips"]) for i in range(6)) == 6000
            departed = clients.pop(); departed.sock.close()
            wait(clients, lambda: "p5.name" not in first.state)
            replacement = Client(port); clients.append(replacement); replacement.send("JOIN", name="새참가자")
            wait(clients, lambda: bool(replacement.state))
            assert replacement.state["you"] == "5" and replacement.state["p5.chips"] == "1000"
            print("PASS: real TCP server JAR; 2/6 clients, framing, Korean chat, private cards, illegal/stale actions, showdown, timeout, capacity, disconnect/new join.")
        finally:
            for client in clients:
                client.sock.close()
            server.terminate()
            try:
                server.wait(timeout=4)
            except subprocess.TimeoutExpired:
                server.kill(); server.wait()


if __name__ == "__main__":
    run()
