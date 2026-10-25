"""검증용 가짜 SMTP 서버. 받은 메일을 <저장폴더>/NNN.eml 로 저장한다. 사용법: smtp_sink.py <저장폴더> [포트]"""
import os
import socket
import sys

out = sys.argv[1]
port = int(sys.argv[2]) if len(sys.argv) > 2 else 2525
os.makedirs(out, exist_ok=True)
srv = socket.socket()
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", port))
srv.listen(5)
n = len([f for f in os.listdir(out) if f.endswith(".eml")])
while True:
    conn, _ = srv.accept()
    f = conn.makefile("rb")
    send = lambda s: conn.sendall((s + "\r\n").encode())
    send("220 sandbox-smtp")
    rcpts, data = [], b""
    while True:
        line = f.readline()
        if not line:
            break
        cmd = line.decode(errors="replace").strip()
        up = cmd.upper()
        if up.startswith(("EHLO", "HELO")):
            send("250 ok")
        elif up.startswith("RCPT"):
            rcpts.append(cmd.split(":", 1)[1].strip().strip("<>"))
            send("250 ok")
        elif up == "DATA":
            send("354 go")
            data = b""
            while True:
                l = f.readline()
                if l in (b".\r\n", b".\n", b""):
                    break
                data += l[1:] if l.startswith(b"..") else l
            n += 1
            with open(os.path.join(out, f"{n:03d}.eml"), "wb") as fp:
                fp.write(b"X-Envelope-To: " + ", ".join(rcpts).encode() + b"\r\n" + data)
            rcpts = []
            send("250 queued")
        elif up == "QUIT":
            send("221 bye")
            break
        else:
            send("250 ok")
    conn.close()
