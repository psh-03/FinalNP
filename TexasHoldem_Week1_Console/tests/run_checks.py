"""1주차 에코 통신을 실제 Java 프로세스로 검사한다. Python은 검증에만 필요하다."""
from pathlib import Path
from subprocess import Popen, PIPE, run
from queue import Queue, Empty
from threading import Thread
import json
import os
import platform
import socket

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'evidence'
OUT.mkdir(exist_ok=True)
JAVA = ['java', '-jar']
results = []


def free_port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


def start_server():
    port = free_port()
    proc = Popen(JAVA + [str(ROOT/'dist/week1-server-console.jar'), str(port)],
                 stdout=PIPE, stderr=PIPE, text=True, encoding='utf-8')
    lines, ready = [], Queue()

    def collect():
        for line in proc.stdout:
            lines.append(line)
            if '접속 대기 중' in line:
                ready.put(True)

    reader = Thread(target=collect, daemon=True)
    reader.start()
    try:
        ready.get(timeout=5)
    except Empty:
        proc.kill()
        proc.wait()
        raise AssertionError('서버가 5초 안에 준비되지 않았습니다.')
    return port, proc, lines, reader


def stop(proc):
    if proc.poll() is None:
        proc.kill()
        proc.wait()


def finish_server(proc, lines, reader):
    try:
        proc.wait(timeout=5)
    finally:
        stop(proc)
    reader.join(timeout=2)
    error = proc.stderr.read()
    assert proc.returncode == 0 and not error, error
    return ''.join(lines)


def client(port, data):
    result = run(JAVA + [str(ROOT/'dist/week1-client-console.jar'), '127.0.0.1', str(port)],
                 input=data, capture_output=True, text=True, encoding='utf-8', timeout=8)
    assert result.returncode == 0 and not result.stderr, result.stderr
    return result.stdout


def save(name, value):
    (OUT/name).write_text(value, encoding='utf-8')


# T1: 원문 보존과 QUIT의 로컬 종료 처리. 종료 명령은 서버에 전송하지 않는다.
port, proc, lines, reader = start_server()
try:
    messages = ['안녕하세요', '텍사스 홀덤 통신 확인', '  한글과 공백  ', 'pp']
    output = client(port, '\n'.join(messages + ['QUIT']) + '\n')
    server = finish_server(proc, lines, reader)
finally:
    stop(proc)
for text in messages:
    assert '서버 응답: ' + text + '\n' in output
    assert '받은 메시지: ' + text + '\n' in server
assert '받은 메시지: QUIT' not in server
assert 'BYE' not in output + server
save('01_normal_client.txt', output)
save('01_normal_server.txt', server)
results.append({'id':'T1', 'condition':'한글·공백·영문 원문 왕복, QUIT은 전송 없이 종료', 'result':'PASS'})

# T2: 서버 미실행 상태의 접속 실패 안내.
output = client(free_port(), '')
assert '서버에 연결할 수 없습니다.' in output
save('02_no_server_client.txt', output)
results.append({'id':'T2', 'condition':'서버 미실행 시 연결 실패 안내', 'result':'PASS'})

# T3: 원시 TCP로 검사. 접속 안내 메시지가 없고, 줄바꿈 뒤 원문 바이트만 응답한다.
port, proc, lines, reader = start_server()
probe = []
try:
    with socket.create_connection(('127.0.0.1', port), timeout=3) as sock:
        sock.settimeout(.3)
        try:
            sock.recv(1)
            raise AssertionError('접속만으로 메시지를 보내면 안 됩니다.')
        except socket.timeout:
            probe.append('1. 접속 후 서버의 별도 안내 메시지 없음')
        value = '줄바꿈 없이 전송'.encode('utf-8')
        sock.sendall(value)
        try:
            sock.recv(1)
            raise AssertionError('줄바꿈 전에는 응답이 없어야 합니다.')
        except socket.timeout:
            probe.append('2. 문자열만 보낸 뒤 0.3초 동안 응답 없음')
        sock.settimeout(3)
        sock.sendall(b'\n')
        answer = bytearray()
        while not answer.endswith(b'\n'):
            part = sock.recv(1)
            assert part, '응답 전 연결 종료'
            answer.extend(part)
        assert bytes(answer).rstrip(b'\r\n') == value
        probe.append('3. LF 추가 후 원문 수신: ' + answer.decode('utf-8').rstrip('\r\n'))
        sock.shutdown(socket.SHUT_WR)
        assert sock.recv(1) == b''
        probe.append('4. 입력 연결 종료 후 추가 응답 없이 EOF 수신')
    server = finish_server(proc, lines, reader)
finally:
    stop(proc)
save('03_newline_probe.txt', '\n'.join(probe) + '\n')
save('03_newline_server.txt', server)
results.append({'id':'T3', 'condition':'접속 안내 없음, 줄바꿈 기준 원문 응답, EOF 종료', 'result':'PASS'})

# T4: 빈 문자열도 한 줄이며 EOF(null)와 다르다.
port, proc, lines, reader = start_server()
try:
    output = client(port, '\n빈 줄 다음 문장\nQUIT\n')
    server = finish_server(proc, lines, reader)
finally:
    stop(proc)
assert '서버 응답: \n' in output
assert '서버 응답: 빈 줄 다음 문장\n' in output
save('04_empty_client.txt', output)
save('04_empty_server.txt', server)
results.append({'id':'T4', 'condition':'빈 줄을 그대로 돌려준 뒤 다음 문장 처리', 'result':'PASS'})

# T5: Linux 실제 터미널(PTY)을 연결하여 Console.reader() 입력 경로 검사.
# Windows CMD와 한글 IME를 검증한 결과는 아니다.
if os.name == 'posix':
    import errno
    import pty
    import select
    import termios
    import time
    port, proc, lines, reader = start_server()
    master, slave = pty.openpty()
    settings = termios.tcgetattr(slave)
    settings[3] &= ~termios.ECHO
    termios.tcsetattr(slave, termios.TCSANOW, settings)
    env = os.environ.copy()
    env['TERM'] = 'dumb'
    terminal = Popen(JAVA + [str(ROOT/'dist/week1-client-console.jar'), '127.0.0.1', str(port)],
                     stdin=slave, stdout=slave, stderr=slave, env=env)
    os.close(slave)
    captured = bytearray()

    def until(fragment):
        deadline = time.monotonic() + 6
        while fragment not in captured:
            assert time.monotonic() < deadline, captured.decode('utf-8', errors='replace')
            if select.select([master], [], [], .1)[0]:
                captured.extend(os.read(master, 4096))

    try:
        until('입력> '.encode('utf-8'))
        os.write(master, '터미널 한글 입력\n'.encode('utf-8'))
        until('서버 응답: 터미널 한글 입력'.encode('utf-8'))
        os.write(master, b'QUIT\n')
        terminal.wait(timeout=5)
        while select.select([master], [], [], .1)[0]:
            try:
                part = os.read(master, 4096)
                if not part:
                    break
                captured.extend(part)
            except OSError as error:
                if error.errno == errno.EIO:
                    break
                raise
        assert terminal.returncode == 0
        server = finish_server(proc, lines, reader)
    finally:
        os.close(master)
        stop(terminal)
        stop(proc)
    output = captured.decode('utf-8').replace('\r', '')
    assert '받은 메시지: 터미널 한글 입력' in server
    save('05_terminal_client.txt', output)
    save('05_terminal_server.txt', server)
    results.append({'id':'T5', 'condition':'Linux 터미널의 한글 입력과 원문 응답', 'result':'PASS',
                    'scope':'Linux PTY 검증; Windows CMD/IME 실기는 별도 확인'})

version = run(['java', '-version'], capture_output=True, text=True).stderr.splitlines()[0]
summary = {'environment':{'os':platform.system(), 'java':version, 'host':'127.0.0.1',
                         'execution':'작성 지원 환경의 검증; 학생 PC 실행 결과와 구분'},
           'tests':results}
save('test_results.json', json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
for item in results:
    print(item['id'], item['result'], item['condition'])
