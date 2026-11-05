"""실제 Java 서버/클라이언트를 실행하여 1주차 실습 조건을 확인한다.

Python은 검증 보조 도구이며, 게임 프로젝트 실행에는 필요하지 않다.
로그는 실행할 때마다 evidence/에 갱신한다.
"""
from pathlib import Path
from subprocess import Popen, PIPE, run
from queue import Queue, Empty
from threading import Thread
import socket
import json
import platform

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'evidence'
OUT.mkdir(exist_ok=True)
JAVA = ['java', '-Dfile.encoding=UTF-8', '-jar']
results = []


def port_number():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


def start_server():
    port = port_number()
    proc = Popen(JAVA + [str(ROOT/'dist/week1-server.jar'), str(port)],
                 stdout=PIPE, stderr=PIPE, text=True, encoding='utf-8')
    lines = []
    first_line = Queue()

    def collect():
        for line in proc.stdout:
            lines.append(line)
            if '에서 접속 대기' in line:
                first_line.put(line)

    thread = Thread(target=collect, daemon=True)
    thread.start()
    try:
        first_line.get(timeout=5)
    except Empty:
        proc.kill()
        proc.wait()
        raise AssertionError('서버가 5초 이내에 준비되지 않았습니다.')
    return port, proc, lines, thread


def finish_server(proc, lines, thread):
    try:
        proc.wait(timeout=5)
    except Exception:
        proc.kill()
        proc.wait()
        raise
    thread.join(timeout=2)
    err = proc.stderr.read()
    assert proc.returncode == 0 and not err, err
    return ''.join(lines)


def java_client(port, data):
    r = run(JAVA + [str(ROOT/'dist/week1-client.jar'), '127.0.0.1', str(port)],
            input=data, capture_output=True, text=True, encoding='utf-8', timeout=8)
    assert r.returncode == 0 and not r.stderr, r.stderr
    return r.stdout


def save(name, text):
    (OUT/name).write_text(text, encoding='utf-8')


# 1. 두 Java 프로그램 사이의 실제 한글 왕복 송수신.
port, proc, lines, thread = start_server()
try:
    client = java_client(port, '안녕하세요\n텍사스 홀덤 통신 확인\nQUIT\n')
    server = finish_server(proc, lines, thread)
finally:
    if proc.poll() is None:
        proc.kill(); proc.wait()
for message in ['안녕하세요', '텍사스 홀덤 통신 확인']:
    assert '[서버 수신] '+message in server
    assert '[클라이언트 수신] ACK '+message in client
assert '[클라이언트 수신] WELCOME' in client
assert '[클라이언트 수신] BYE' in client
save('01_normal_server.txt', server)
save('01_normal_client.txt', client)
results.append({'id':'T1','condition':'한글 메시지 2회 및 QUIT','result':'PASS','port':port})

# 2. 서버가 실행 중이지 않은 포트에 연결하면 오류 안내가 나와야 한다.
port = port_number()
client = java_client(port, '')
assert '서버에 연결할 수 없습니다.' in client
assert '서버 실행 여부와 IP/포트를 확인하세요.' in client
save('02_no_server_client.txt', client)
results.append({'id':'T2','condition':'서버 미실행 포트로 접속','result':'PASS','port':port})

# 3. 소켓으로 바이트를 보내도 줄바꿈이 없으면 readLine()은 계속 기다린다.
port, proc, lines, thread = start_server()
check_log = []
try:
    with socket.create_connection(('127.0.0.1', port), timeout=3) as sock:
        def read_line():
            buf = bytearray()
            while not buf.endswith(b'\n'):
                chunk = sock.recv(1)
                if not chunk:
                    raise AssertionError('응답 전에 연결이 종료됨')
                buf.extend(chunk)
            return buf.decode('utf-8').rstrip('\r\n')

        assert read_line() == 'WELCOME'
        sock.sendall('줄바꿈 없이 전송'.encode('utf-8'))
        check_log.append('1. UTF-8 문자열만 전송하고 줄바꿈은 보내지 않음')
        sock.settimeout(0.3)
        try:
            sock.recv(1)
            raise AssertionError('줄바꿈 전에는 응답이 없어야 함')
        except socket.timeout:
            check_log.append('2. 0.3초 동안 응답 없음')
        sock.settimeout(3)
        sock.sendall(b'\n')
        check_log.append('3. 같은 연결에서 줄바꿈 LF만 추가 전송')
        reply = read_line()
        assert reply == 'ACK 줄바꿈 없이 전송'
        check_log.append('4. 수신: '+reply)
        sock.sendall(b'QUIT\n')
        assert read_line() == 'BYE'
    server = finish_server(proc, lines, thread)
finally:
    if proc.poll() is None:
        proc.kill(); proc.wait()
save('03_newline_probe.txt', '\n'.join(check_log)+'\n')
save('03_newline_server.txt', server)
results.append({'id':'T3','condition':'줄바꿈 생략 후 LF 추가 전송','result':'PASS','port':port})

# 4. 빈 줄을 보낸 경우 오류 응답 후에도 QUIT을 처리할 수 있어야 한다.
port, proc, lines, thread = start_server()
try:
    client = java_client(port, '\nQUIT\n')
    server = finish_server(proc, lines, thread)
finally:
    if proc.poll() is None:
        proc.kill(); proc.wait()
assert '[클라이언트 수신] ERROR 빈 메시지는 보낼 수 없습니다.' in client
assert '[클라이언트 수신] BYE' in client
save('04_empty_server.txt', server)
save('04_empty_client.txt', client)
results.append({'id':'T4','condition':'빈 줄 전송 후 QUIT','result':'PASS','port':port})

java_version = run(['java','-version'], capture_output=True, text=True).stderr.splitlines()[0]
summary={'environment':{'os':platform.system(),'java':java_version,'host':'127.0.0.1',
                         'execution':'작성 지원 환경의 자동 검증; 학생 PC 실행 결과는 별도 확인'},
         'tests':results}
save('test_results.json', json.dumps(summary, ensure_ascii=False, indent=2)+'\n')
for item in results:
    print(item['id'], item['result'], item['condition'])
