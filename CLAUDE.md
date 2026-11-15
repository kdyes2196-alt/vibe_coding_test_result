# 통합테스트 현황 자동 메일 발송 (Java 17 / Maven)

사용자는 로컬에서 실행하지 않고 **프롬프트로만 개발·검증**한다. 변경 후에는 항상 직접 검증하고 결과를 보여줄 것.

## 검증 방법
- `scripts/verify.sh` : 빌드 + JUnit 테스트 + 샘플로 dry-run + 기관별 결과표 출력 + 메일 본문 캡처(`verify-output/mail.png`)
  - 캡처 이미지는 사용자에게 보여 준다. 다른 원천 건수로 보려면 `scripts/verify.sh <csv경로>`.
- `scripts/verify-ledger.sh [시각...]` : H2 메모리 DB 에 `samples/ledger_scenario.sql` 원장을 올려 시각별 조회 결과표와 메일 캡처(`verify-output/ledger/mail_*.png`)를 만든다.
  원장 관련 요청(시나리오 추가 등)은 이 SQL 에 거래를 추가해 재현하고 `LedgerScenarioTest` 에 기대값을 넣는다.
- `scripts/ledger-db.sh` : 프롬프트로 원장 거래를 쌓아 가며 확인하는 샌드박스 (H2 파일 DB `sandbox/`, git 제외)
  - `reset [empty]` / `add <기관명> <거래구분> [--stat 01] [--cond 1] [--paging] [--case 변경성공] [--at "..."] [--memo ".."] [--temp]` / `list [기관명]` / `sql "..."` / `run [--as-of T]` / `status` / `approve` / `reject`
  - 사용자가 "OO카드 거래 넣고 돌려줘"라고 하면 add → run 후 진척률(직전 대비)·변경 항목·`sandbox/mail.png` 를 보여 준다.
  - **사용자가 말하는 시나리오는 항상 `add` 로 넣어 `samples/ledger_scenario_user.sql` 에 누적하고 커밋·푸시한다** (시나리오 설명은 `--memo`).
    기본 시나리오 `samples/ledger_scenario.sql`(11건)은 고정이며 자동 테스트 기대값이 묶여 있으니 수정하지 않는다.
  - **테스트 DB 를 보여 줄 때는 항상 `list` 를 사용**해 현황 엑셀에 기재된 거래고유번호(엑셀 기재 열)를 함께 보여 준다.
  - **메일 발송은 반드시 사용자 승인 후**: `run` 으로 승인 대기 엑셀을 만들어 사용자에게 파일을 보내고, 사용자가 OK 해야 `approve` 한다.
    이 환경에서는 외부 SMTP 가 막혀 있어 `approve` 는 로컬 가짜 SMTP(sandbox/mailbox/)로 수신을 확인하는 것이며 실제 메일은 나가지 않는다.
  - 세션(컨테이너)이 바뀌면 sandbox/ 는 사라진다. `reset` 으로 기본 + 추가 시나리오부터 다시 시작한다.
- 테스트만: `LANG=C.UTF-8 mvn -B test`
- 리눅스에서 한글 파일명을 쓰려면 `LANG=C.UTF-8` 과 `-Dsun.jnu.encoding=UTF-8` 이 필요하다 (verify.sh 와 surefire 에 설정됨).
- 이 클라우드 환경은 외부 SMTP(587/465) 접속이 막혀 있다. 메일 발송은 GreenMail(가짜 SMTP) 테스트로 검증한다.

## 구조
- `src/main/java/com/testreport/` : App(실행·스케줄) → WorkbookUpdater(복사·원천연결·재계산·저장) → SummaryExtractor(요약 시트 읽기) → MailBodyBuilder / MailSender
- 원장 조회: LedgerRule(`config/ledger_rules.csv`) → LedgerChecker(JDBC 조회, 기관=매입사|발급사) → LedgerSheetUpdater(기관별 시트의 확인 일시·거래고유번호 기록)
- PendingReport: `mail.approval.required=true` 면 보고서를 `state/pending/` 에 승인 대기로 저장, `--approve` 시 발송·`--reject` 시 폐기
- StatusSnapshot: 직전 발송 O/X 를 `state/last_status.tsv` 에 저장, 메일의 '직전 발송 대비 변경'과 변경 없음 시 발송 생략에 사용
- 엑셀 수식이 계산 로직의 원본이다. Java 에서 조건 판정을 재구현하지 말고 POI 로 재계산한 값을 읽는다.
- `samples/test-status-sample.xlsx` : 사용자가 준 실제 현황 엑셀. 테스트의 기대값(기관 12개, 원본 KB국민카드 40%, 원천 반영 시 100% 등)이 이 파일에 묶여 있다.
- `config/application.properties` : 실제 운영(사용자 Windows PC) 설정, `config/verify.properties` : 검증용 설정.

## 알려진 원본 엑셀 문제 (사용자에게 안내됨)
- 상세 시트 Q~W, AA~AC 의 VLOOKUP 범위가 `#REF!` → 원천 CSV 지정 시 `원천데이터` 시트로 자동 연결
- 요약 시트 R15~R17 이 상세 시트 Y14~Y16 을 참조 (한 행 어긋남)
- 요약 V5 '기관수(11)' 표기와 실제 기관 수(12) 불일치

## 미확정 사항 (사용자 확인 필요)
- 원장에 없는 정보로 판정해야 하는 조건: 980001 조건3(페이징), 980004 케이스별(재신규여부·결과코드).
  `ledger_rules.csv` 에서 가정 컬럼 PAGING_YN / RENEW_YN / RSLT_CD 를 쓰고 있다.
- 실제 테이블 컬럼명, DBMS 종류(현재 Oracle 드라이버 포함), 900N02/AC4111(파일·배치) 원천.

## 규칙
- 메시지/로그/주석은 한국어. 비밀번호·토큰은 코드나 설정 파일에 커밋하지 않는다 (환경변수 MAIL_PASSWORD).
