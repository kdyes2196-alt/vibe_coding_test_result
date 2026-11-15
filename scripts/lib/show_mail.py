"""가짜 SMTP 가 받은 .eml 을 요약 출력하고, 첨부 엑셀을 <폴더>/attachments/ 에 꺼낸다. 사용법: show_mail.py <eml> ..."""
import os
import sys
from email import message_from_binary_file
from email.header import decode_header, make_header

for path in sys.argv[1:]:
    with open(path, "rb") as fp:
        m = message_from_binary_file(fp)
    dec = lambda v: str(make_header(decode_header(v))) if v else "-"
    print(f"  [{os.path.basename(path)}]")
    print(f"    실제 수신(봉투): {m['X-Envelope-To']}")
    print(f"    From : {dec(m['From'])}")
    print(f"    To   : {dec(m['To'])}")
    if m["Cc"]:
        print(f"    Cc   : {dec(m['Cc'])}")
    print(f"    제목 : {dec(m['Subject'])}")
    for part in m.walk():
        name = part.get_filename()
        if name:
            name = dec(name)
            body = part.get_payload(decode=True)
            folder = os.path.join(os.path.dirname(path), "attachments")
            os.makedirs(folder, exist_ok=True)
            with open(os.path.join(folder, name), "wb") as fp:
                fp.write(body)
            print(f"    첨부 : {name} ({len(body):,} bytes) → {os.path.join(folder, name)}")
