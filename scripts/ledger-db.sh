#!/usr/bin/env bash
# 프롬프트로 원장 거래를 추가해 가며 진척률 변화와 승인 후 메일 발송을 확인하는 샌드박스
#   (H2 파일 DB: sandbox/ledger.mv.db, 가짜 SMTP 수신함: sandbox/mailbox/)
#
#   scripts/ledger-db.sh reset [empty]          DB 초기화: 기본 시나리오(11건) + 추가 시나리오 (empty: 빈 원장)
#   scripts/ledger-db.sh add <기관명> <거래구분코드> [옵션]   거래 1건 추가 (samples/ledger_scenario_user.sql 에도 기록)
#        --stat 00|01        거래상태 (기본 00 정상)
#        --cond 1|2          추가조회조건 (기본 NULL=전체조회)
#        --paging            페이징 거래 (980001 조건3)
#        --case 변경성공|해지실패|무응답|재신규성공|재신규실패   (980004 케이스)
#        --at "2026-01-01 10:00:00"   거래일시 (기본: 현재 시각)
#        --no 거래고유번호   (기본: 자동 생성)
#        --memo "설명"       추가 시나리오 파일에 남길 설명
#        --temp              추가 시나리오 파일에 기록하지 않음 (이번 세션에서만 사용)
#   scripts/ledger-db.sh sql "<SQL>"            임의 SQL 실행 (파일에는 기록되지 않음)
#   scripts/ledger-db.sh list [기관명]          원장 거래 + 최근 현황 엑셀에 기재된 거래고유번호 표시
#   scripts/ledger-db.sh agencies               기관명 · 매입사 · 발급사 코드 목록
#   scripts/ledger-db.sh run [--as-of 시각]     현황 엑셀 생성 → 승인 대기 (메일은 아직 안 나감)
#   scripts/ledger-db.sh status                 승인 대기 보고서 · 수신자 확인
#   scripts/ledger-db.sh approve                승인 → 메일링 리스트로 발송 (가짜 SMTP 로 수신 확인)
#   scripts/ledger-db.sh reject                 승인 대기 보고서 폐기
set -euo pipefail
cd "$(dirname "$0")/.."
export LANG=C.UTF-8 LC_ALL=C.UTF-8
JAR=target/test-report-mailer.jar
CFG=config/sandbox-ledger.properties
URL="jdbc:h2:file:./sandbox/ledger;MODE=Oracle"
SAMPLE=samples/test-status-sample.xlsx
BASE_SQL=samples/ledger_scenario.sql
USER_SQL=samples/ledger_scenario_user.sql

[ -f "$JAR" ] || { mvn -B -q package -DskipTests > /dev/null 2>&1 || { echo "빌드 실패: mvn package 확인"; exit 1; }; }
mkdir -p sandbox

h2() { java -cp "$JAR" org.h2.tools.Shell -url "$URL" -user sa -password "" -sql "$1" 2>&1 | grep -v JAVA_TOOL; }
app() { java -Dsun.jnu.encoding=UTF-8 -jar "$JAR" --config "$CFG" "$@" 2>&1 | grep -v -e JAVA_TOOL -e '원장 조회 \[' | sed 's/^[0-9-]* [0-9:]* \[[A-Z]*\] /  /'; }
q() { printf "'%s'" "${1//\'/\'\'}"; }
nullable() { [ -z "$1" ] && echo NULL || q "$1"; }
agency_line() { python3 -I scripts/lib/agencies.py "$SAMPLE" | awk -v n="$1" '$1==n'; }
ensure_db() { [ -f sandbox/ledger.mv.db ] || { echo "DB 가 없어 기본 시나리오로 먼저 초기화합니다."; "$0" reset; }; }
latest_report() { ls -t sandbox/state/pending/*.xlsx sandbox/output/*.xlsx 2>/dev/null | head -1 || true; }
shot() {  # $1 html → $2 png
  cat > sandbox/shot.js <<'JS'
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 900, height: 700 } });
  await p.goto('file://' + process.argv[2]); await p.screenshot({ path: process.argv[3], fullPage: true }); await b.close();
})();
JS
  NODE_PATH="$(npm root -g 2>/dev/null)" node sandbox/shot.js "$(realpath "$1")" "$2" > /dev/null 2>&1 && echo "메일 본문 캡처: $2" || true
  rm -f sandbox/shot.js
}

cmd="${1:-help}"; shift || true
case "$cmd" in
  reset)
    rm -rf sandbox && mkdir -p sandbox
    sed -n '/^CREATE TABLE/,/^);/p' "$BASE_SQL" > sandbox/schema.sql
    if [ "${1:-}" = "empty" ]; then
      h2 "RUNSCRIPT FROM 'sandbox/schema.sql'" > /dev/null
      echo "빈 원장으로 초기화했습니다."
    else
      h2 "RUNSCRIPT FROM '$BASE_SQL'" > /dev/null
      base=$(h2 "SELECT COUNT(*) FROM ONLINE_LEDG" | sed -n 2p)
      [ -f "$USER_SQL" ] && h2 "RUNSCRIPT FROM '$USER_SQL'" > /dev/null
      total=$(h2 "SELECT COUNT(*) FROM ONLINE_LEDG" | sed -n 2p)
      echo "원장 초기화: 기본 시나리오 ${base}건 + 추가 시나리오 $((total - base))건 = ${total}건"
    fi
    ;;

  agencies)
    python3 -I scripts/lib/agencies.py "$SAMPLE"
    ;;

  add)
    name="${1:?기관명이 필요합니다}"; code="${2:?거래구분코드가 필요합니다}"; shift 2
    stat=00; cond=""; paging=N; renew=""; rslt=""; at=""; no=""; memo=""; temp=false
    while [ $# -gt 0 ]; do
      case "$1" in
        --stat) stat="$2"; shift 2 ;;
        --cond) cond="$2"; shift 2 ;;
        --paging) paging=Y; shift ;;
        --at) at="$2"; shift 2 ;;
        --no) no="$2"; shift 2 ;;
        --memo) memo="$2"; shift 2 ;;
        --temp) temp=true; shift ;;
        --case)
          case "$2" in
            변경성공) renew=N; rslt=0000 ;; 해지실패) renew=N; rslt=1920 ;; 무응답) renew=N; rslt=1924 ;;
            재신규성공) renew=Y; rslt=0000 ;; 재신규실패) renew=Y; rslt=1920 ;;
            *) echo "알 수 없는 케이스: $2"; exit 1 ;;
          esac; shift 2 ;;
        *) echo "알 수 없는 옵션: $1"; exit 1 ;;
      esac
    done
    line=$(agency_line "$name")
    [ -n "$line" ] || { echo "기관명을 찾지 못했습니다: $name  (scripts/ledger-db.sh agencies 로 확인)"; exit 1; }
    acq=$(echo "$line" | awk '{print $2}'); iss=$(echo "$line" | awk '{print $3}')
    [ -n "$at" ] || at=$(date '+%Y-%m-%d %H:%M:%S')
    [ -n "$no" ] || no="${acq}$(date +%H%M%S)$(printf '%03d' $((RANDOM % 1000)))"
    ensure_db
    sql="INSERT INTO ONLINE_LEDG VALUES (TIMESTAMP $(q "$at"), $(q "$no"), $(q "$acq"), $(q "$iss"), $(q "$code"), $(q "$stat"), $(nullable "$cond"), $(q "$paging"), $(nullable "$renew"), $(nullable "$rslt"))"
    h2 "$sql" > /dev/null
    saved=""
    if [ "$temp" = false ]; then
      { [ -n "$memo" ] && echo "-- ${memo}"; echo "${sql};"; } >> "$USER_SQL"
      saved=" → ${USER_SQL} 에 기록"
    fi
    echo "추가: $name($acq/$iss) $code 상태=$stat 추가조회조건=${cond:-NULL} 페이징=$paging 재신규=${renew:--} 결과코드=${rslt:--} 일시=$at 거래고유번호=$no${saved}"
    ;;

  sql)
    h2 "${1:?SQL 이 필요합니다}"
    ;;

  list)
    ensure_db
    where=""
    if [ -n "${1:-}" ]; then
      line=$(agency_line "$1")
      [ -n "$line" ] || { echo "기관명을 찾지 못했습니다: $1"; exit 1; }
      where="WHERE ACQ_INST_CD = ''$(echo "$line" | awk '{print $2}')'' AND ISS_INST_CD = ''$(echo "$line" | awk '{print $3}')''"
    fi
    h2 "CALL CSVWRITE('sandbox/ledger_list.csv', 'SELECT * FROM ONLINE_LEDG $where ORDER BY TRX_DT, TRX_UNQ_NO', 'charset=UTF-8')" > /dev/null
    report=$(latest_report)
    python3 -I scripts/lib/list_ledger.py sandbox/ledger_list.csv "${report:--}" "$SAMPLE"
    ;;

  run)
    ensure_db
    prev=$(latest_report)
    if [ -n "$prev" ]; then mkdir -p sandbox/history; cp "$prev" sandbox/history/; prev="sandbox/history/$(basename "$prev")"; fi
    rm -rf sandbox/output
    echo "== 현황 엑셀 생성 =="
    app "$@"
    xlsx=$(ls sandbox/state/pending/*.xlsx 2>/dev/null || ls sandbox/output/*.xlsx)
    echo; echo "== 기관별 현황 (괄호: 직전 실행 대비 진척률 변화) =="
    python3 -I scripts/lib/print_status.py "$xlsx" ${prev:+"$prev"}
    echo; shot "$(ls sandbox/output/*_메일미리보기.html)" sandbox/mail.png
    echo "확인할 엑셀: $xlsx"
    [ -d sandbox/state/pending ] && echo "※ 승인 대기 중입니다. 확인 후 'approve' 하면 메일링 리스트로 발송됩니다."
    ;;

  status)
    app --status || true
    ;;

  approve)
    mkdir -p sandbox/mailbox
    before=$(find sandbox/mailbox -name '*.eml' | wc -l)
    python3 -I scripts/lib/smtp_sink.py sandbox/mailbox 2525 > /dev/null 2>&1 &
    sink=$!
    trap 'kill $sink 2>/dev/null || true' EXIT
    sleep 1
    echo "== 승인 → 메일 발송 =="
    app --approve
    sleep 1
    new=$(find sandbox/mailbox -maxdepth 1 -name '*.eml' | sort | tail -n +$((before + 1)))
    if [ -n "$new" ]; then
      echo; echo "== 가짜 SMTP 서버가 받은 메일 =="
      python3 -I scripts/lib/show_mail.py $new
    fi
    ;;

  reject)
    app --reject || true
    ;;

  *)
    sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'
    ;;
esac
