#!/usr/bin/env bash
# 원장 조회 시나리오 검증: H2 메모리 DB(samples/ledger_scenario.sql)를 시각별로 조회해 현황 변화를 보여 준다.
#   사용법: scripts/verify-ledger.sh [조회시각 ...]   (기본: 2026-01-01T09:00 2026-01-01T15:00)
set -euo pipefail
cd "$(dirname "$0")/.."
export LANG=C.UTF-8 LC_ALL=C.UTF-8
TIMES=("$@"); [ ${#TIMES[@]} -gt 0 ] || TIMES=(2026-01-01T09:00 2026-01-01T15:00)

[ -f target/test-report-mailer.jar ] || mvn -B -q package -DskipTests 2>&1 | grep -v JAVA_TOOL || true
rm -rf verify-output/ledger && mkdir -p verify-output/ledger

for T in "${TIMES[@]}"; do
  STAMP=$(echo "$T" | sed 's/[-:]//g; s/T/_/')
  echo "================ ${T} 조회 ================"
  java -Dsun.jnu.encoding=UTF-8 -jar target/test-report-mailer.jar --config config/verify-ledger.properties \
       --dry-run --as-of "$T" 2>&1 | grep -v JAVA_TOOL | sed 's/^[0-9-]* [0-9:]* \[INFO\] /  /'
  XLSX=$(ls verify-output/ledger/*_${STAMP}.xlsx)
  python3 -I - "$XLSX" <<'PY'
import sys, openpyxl
wb = openpyxl.load_workbook(sys.argv[1], data_only=True)
ws = wb.worksheets[0]
print("  기관명        진척률  980001 980002 980003 980004  900N02  AC4111   원장 근거(확인일시/거래고유번호)")
for row in ws.iter_rows(min_row=6, max_row=40, min_col=3, max_col=19, values_only=True):
    if not row[0]: break
    m = [x or "-" for x in row[2:]]
    groups = ["".join(m[0:3]), m[3], m[4], m[5], "".join(m[6:12]), "".join(m[12:15])]
    ev = []
    sh = wb[row[0]]
    for r in sh.iter_rows(min_row=5, values_only=True):
        if r[1] and r[6] and r[7]: ev.append(f"{r[1]} {r[6]}/{r[7]}")
    print(f"  {row[0]:<10}{row[1]:>5.0f}%   {groups[0]:<6} {groups[1]:<6} {groups[2]:<6} {groups[3]:<6}  {groups[4]:<6}  {groups[5]:<6}   {'; '.join(ev)}")
PY
  HTML=$(ls verify-output/ledger/*_${STAMP}_메일미리보기.html)
  cat > verify-output/shot.js <<'JS'
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 900, height: 700 } });
  await p.goto('file://' + process.argv[2]); await p.screenshot({ path: process.argv[3], fullPage: true }); await b.close();
})();
JS
  NODE_PATH="$(npm root -g 2>/dev/null)" node verify-output/shot.js "$(realpath "$HTML")" "verify-output/ledger/mail_${STAMP}.png" \
    && echo "  메일 캡처: verify-output/ledger/mail_${STAMP}.png" || echo "  (브라우저 없음 - 캡처 생략)"
  rm -f verify-output/shot.js
  echo
done
