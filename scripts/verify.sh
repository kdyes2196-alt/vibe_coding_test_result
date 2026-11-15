#!/usr/bin/env bash
# 클라우드(리눅스) 검증용: 빌드 + 자동 테스트 + 샘플 데이터로 보고서/메일 미리보기 생성 + 결과 요약 출력
#   사용법: scripts/verify.sh [원천건수CSV]   (생략 시 samples/raw_counts_sample.csv)
set -euo pipefail
cd "$(dirname "$0")/.."
export LANG=C.UTF-8 LC_ALL=C.UTF-8
RAW="${1:-samples/raw_counts_sample.csv}"

rm -rf verify-output && mkdir -p verify-output
echo "== 1. 빌드 + 자동 테스트 =="
if ! mvn -B package > verify-output/build.log 2>&1; then
  grep -E "Tests run|FAIL|ERROR\]|expected" verify-output/build.log | head -40
  echo "빌드/테스트 실패 (전체 로그: verify-output/build.log)"; exit 1
fi
grep -h "Tests run" target/surefire-reports/*.txt

echo; echo "== 2. 샘플 데이터로 실행 (--dry-run, 메일 미발송) =="
CFG=verify-output/verify.properties
sed "s#^rawCounts.file=.*#rawCounts.file=${RAW}#" config/verify.properties > "$CFG"
java -Dsun.jnu.encoding=UTF-8 -jar target/test-report-mailer.jar --config "$CFG" --dry-run 2>&1 | grep -v JAVA_TOOL

XLSX=$(ls verify-output/*.xlsx | head -1)
HTML=$(ls verify-output/*.html | head -1)

echo; echo "== 3. 기관별 결과 (생성된 엑셀의 요약 시트) =="
python3 -I - "$XLSX" <<'PY' || echo "(openpyxl 없음 - 생략)"
import sys, openpyxl
ws = openpyxl.load_workbook(sys.argv[1], data_only=True).worksheets[0]
for row in ws.iter_rows(min_row=6, max_row=40, min_col=3, max_col=19, values_only=True):
    if not row[0]: break
    marks = "".join(m or "-" for m in row[2:])
    print(f"{row[0]:<10}\t{row[1] if row[1] is not None else '-':>5}%\t{marks}")
PY

echo; echo "== 4. 메일 본문 캡처 =="
cat > verify-output/shot.js <<'JS'
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 900, height: 700 } });
  await p.goto('file://' + process.argv[2]); await p.screenshot({ path: process.argv[3], fullPage: true }); await b.close();
})();
JS
NODE_PATH="$(npm root -g 2>/dev/null)" node verify-output/shot.js "$(realpath "$HTML")" verify-output/mail.png \
  && echo "verify-output/mail.png" || echo "(브라우저 없음 - 캡처 생략)"
rm -f verify-output/shot.js
echo; echo "결과 폴더: verify-output/  (엑셀: $XLSX)"
