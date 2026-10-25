#!/usr/bin/env bash
# 프롬프트로 원장 거래를 추가해 가며 진척률 변화를 확인하는 샌드박스 (H2 파일 DB: sandbox/ledger.mv.db)
#
#   scripts/ledger-db.sh reset [empty]          DB 초기화 (기본: samples/ledger_scenario.sql 로 시작, empty: 빈 테이블)
#   scripts/ledger-db.sh add <기관명> <거래구분코드> [옵션]   거래 1건 추가
#        --stat 00|01        거래상태 (기본 00 정상)
#        --cond 1|2          추가조회조건 (기본 NULL=전체조회)
#        --paging            페이징 거래 (980001 조건3)
#        --case 변경성공|해지실패|무응답|재신규성공|재신규실패   (980004 케이스)
#        --at "2026-01-01 10:00:00"   거래일시 (기본: 현재 시각)
#        --no 거래고유번호   (기본: 자동 생성)
#   scripts/ledger-db.sh sql "<SQL>"            임의 SQL 실행 (예: DELETE ...)
#   scripts/ledger-db.sh list [기관명]          원장 거래 목록
#   scripts/ledger-db.sh agencies               기관명 · 매입사 · 발급사 코드 목록
#   scripts/ledger-db.sh run [--as-of 시각]     프로그램 실행 → 기관별 진척률(직전 대비) · 변경 항목 · 메일 캡처
set -euo pipefail
cd "$(dirname "$0")/.."
export LANG=C.UTF-8 LC_ALL=C.UTF-8
JAR=target/test-report-mailer.jar
URL="jdbc:h2:file:./sandbox/ledger;MODE=Oracle"
SAMPLE=samples/test-status-sample.xlsx

[ -f "$JAR" ] || { mvn -B -q package -DskipTests > /dev/null 2>&1 || { echo "빌드 실패: mvn package 확인"; exit 1; }; }
mkdir -p sandbox

h2() { java -cp "$JAR" org.h2.tools.Shell -url "$URL" -user sa -password "" -sql "$1" 2>&1 | grep -v JAVA_TOOL; }
q() { printf "'%s'" "${1//\'/\'\'}"; }   # SQL 문자열 리터럴
nullable() { [ -z "$1" ] && echo NULL || q "$1"; }

cmd="${1:-help}"; shift || true
case "$cmd" in
  reset)
    rm -rf sandbox
    mkdir -p sandbox
    if [ "${1:-}" = "empty" ]; then
      sed -n '/^CREATE TABLE/,/^);/p' samples/ledger_scenario.sql > sandbox/schema.sql
      h2 "RUNSCRIPT FROM 'sandbox/schema.sql'" > /dev/null
      echo "빈 원장으로 초기화했습니다."
    else
      h2 "RUNSCRIPT FROM 'samples/ledger_scenario.sql'" > /dev/null
      echo "시나리오 원장(samples/ledger_scenario.sql)으로 초기화했습니다: $(h2 "SELECT COUNT(*) FROM ONLINE_LEDG" | sed -n 2p)건"
    fi
    ;;

  agencies)
    python3 -I scripts/lib/agencies.py "$SAMPLE"
    ;;

  add)
    name="${1:?기관명이 필요합니다}"; code="${2:?거래구분코드가 필요합니다}"; shift 2
    stat=00; cond=""; paging=N; renew=""; rslt=""; at=""; no=""
    while [ $# -gt 0 ]; do
      case "$1" in
        --stat) stat="$2"; shift 2 ;;
        --cond) cond="$2"; shift 2 ;;
        --paging) paging=Y; shift ;;
        --at) at="$2"; shift 2 ;;
        --no) no="$2"; shift 2 ;;
        --case)
          case "$2" in
            변경성공) renew=N; rslt=0000 ;; 해지실패) renew=N; rslt=1920 ;; 무응답) renew=N; rslt=1924 ;;
            재신규성공) renew=Y; rslt=0000 ;; 재신규실패) renew=Y; rslt=1920 ;;
            *) echo "알 수 없는 케이스: $2"; exit 1 ;;
          esac; shift 2 ;;
        *) echo "알 수 없는 옵션: $1"; exit 1 ;;
      esac
    done
    line=$(python3 -I scripts/lib/agencies.py "$SAMPLE" | awk -v n="$name" '$1==n')
    [ -n "$line" ] || { echo "기관명을 찾지 못했습니다: $name  (scripts/ledger-db.sh agencies 로 확인)"; exit 1; }
    acq=$(echo "$line" | awk '{print $2}'); iss=$(echo "$line" | awk '{print $3}')
    [ -n "$at" ] || at=$(date '+%Y-%m-%d %H:%M:%S')
    [ -n "$no" ] || no="${acq}$(date +%H%M%S)$(printf '%03d' $((RANDOM % 1000)))"
    [ -f sandbox/ledger.mv.db ] || { echo "DB 가 없어 시나리오 원장으로 먼저 초기화합니다."; "$0" reset; }
    h2 "INSERT INTO ONLINE_LEDG VALUES (TIMESTAMP $(q "$at"), $(q "$no"), $(q "$acq"), $(q "$iss"), $(q "$code"), $(q "$stat"), $(nullable "$cond"), $(q "$paging"), $(nullable "$renew"), $(nullable "$rslt"))" > /dev/null
    echo "추가: $name($acq/$iss) $code 상태=$stat 추가조회조건=${cond:-NULL} 페이징=$paging 재신규=${renew:--} 결과코드=${rslt:--} 일시=$at 거래고유번호=$no"
    ;;

  sql)
    h2 "${1:?SQL 이 필요합니다}"
    ;;

  list)
    where=""
    if [ -n "${1:-}" ]; then
      line=$(python3 -I scripts/lib/agencies.py "$SAMPLE" | awk -v n="$1" '$1==n')
      [ -n "$line" ] || { echo "기관명을 찾지 못했습니다: $1"; exit 1; }
      where="WHERE ACQ_INST_CD = '$(echo "$line" | awk '{print $2}')' AND ISS_INST_CD = '$(echo "$line" | awk '{print $3}')'"
    fi
    h2 "SELECT TRX_DT 거래일시, TRX_UNQ_NO 거래고유번호, ACQ_INST_CD 매입, ISS_INST_CD 발급, TRX_DV_CD 거래구분, TRX_STAT_CD 상태, ADD_INQ_COND 추가조회, PAGING_YN 페이징, RENEW_YN 재신규, RSLT_CD 결과 FROM ONLINE_LEDG $where ORDER BY TRX_DT"
    ;;

  run)
    [ -f sandbox/ledger.mv.db ] || { echo "DB 가 없어 시나리오 원장으로 먼저 초기화합니다."; "$0" reset; }
    prev=$(ls -t sandbox/output/*.xlsx 2>/dev/null | head -1 || true)
    # 같은 분에 여러 번 실행해도 파일이 겹치지 않도록 이전 결과는 보관 폴더로 이동
    if [ -n "$prev" ]; then mkdir -p sandbox/history; mv sandbox/output/* sandbox/history/; prev="sandbox/history/$(basename "$prev")"; fi
    echo "== 프로그램 실행 =="
    java -Dsun.jnu.encoding=UTF-8 -jar "$JAR" --config config/sandbox-ledger.properties --dry-run "$@" 2>&1 \
      | grep -v -e JAVA_TOOL -e '원장 조회 \[' | sed 's/^[0-9-]* [0-9:]* \[INFO\] /  /'
    xlsx=$(ls sandbox/output/*.xlsx); html=$(ls sandbox/output/*.html)
    echo; echo "== 기관별 현황 (괄호: 직전 실행 대비 진척률 변화) =="
    python3 -I scripts/lib/print_status.py "$xlsx" ${prev:+"$prev"}
    cat > sandbox/shot.js <<'JS'
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 900, height: 700 } });
  await p.goto('file://' + process.argv[2]); await p.screenshot({ path: process.argv[3], fullPage: true }); await b.close();
})();
JS
    NODE_PATH="$(npm root -g 2>/dev/null)" node sandbox/shot.js "$(realpath "$html")" sandbox/mail.png > /dev/null 2>&1 \
      && echo && echo "메일 캡처: sandbox/mail.png" || true
    rm -f sandbox/shot.js
    ;;

  *)
    sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'
    ;;
esac
