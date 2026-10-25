# 통합테스트 현황 자동 갱신 · 메일 발송 (test-report-mailer)

통합테스트 현황 엑셀(`3. 테스트현황 예시파일.xlsx`)을 정해진 시각마다 **재계산**해 보고서 파일로 저장하고,
**요약 HTML 본문 + 엑셀 첨부**로 메일링 리스트에 발송합니다.

```
[작업 스케줄러 09:00 / 18:00]
   └─ run.bat
       ├─ ① 원본 엑셀 복사 후 열기 (OneDrive / 엑셀에서 열려 있어도 OK, 원본은 수정 안 함)
       ├─ ①-1 (선택) 원장(ONLINE_LEDG) 조회 → 기관별 시트의 '확인 일시'·'거래고유번호' 기록
       ├─ ② (선택) 900N02·AC4111 건수 CSV → '원천데이터' 시트에 기록, 끊긴 VLOOKUP(#REF!) 자동 연결
       ├─ ③ 전체 수식 재계산 (기관별 시트 → 상세 → 요약, 조건부 서식 유지)
       ├─ ④ output/카드이동서비스채널확대_테스트현황_YYYYMMDD_HHmm.xlsx 저장
       ├─ ⑤ 요약 시트를 읽어 메일 본문 생성 (진척률 60% 미만 기관 붉은색)
       └─ ⑥ 승인 대기(state/pending/) 저장 → (선택) 담당자에게 승인 요청 메일
   └─ approve.bat (담당자)
       ├─ 승인 대기 엑셀을 열어 확인
       └─ Y: mailing_list.csv 수신자에게 발송 / N: 보류 / R: 폐기
```

## 발송 전 담당자 승인

`mail.approval.required=true`(기본)이면 정해진 시각에 보고서를 만들어 **승인 대기**로만 두고 메일은 보내지 않습니다.

1. 담당자가 `scripts\approve.bat` 실행 → 승인 대기 엑셀이 열리고 제목·수신자가 표시됩니다.
2. 내용 확인 후 `Y` 를 누르면 그 엑셀을 첨부해 메일링 리스트로 발송, `N` 은 보류, `R` 은 폐기합니다.
3. 승인 전에 다음 회차가 실행되면 승인 대기 보고서는 최신 것으로 교체됩니다.
4. `mail.approval.notifyTo` 에 담당자 주소를 넣으면 보고서 생성 시 **[발송 승인 요청]** 메일(엑셀 첨부)을 담당자에게만 먼저 보냅니다.
5. 메일 본문의 '직전 발송 대비 변경'은 마지막으로 **실제 발송(승인)한** 보고서와 비교합니다.

명령으로 직접: `--status`(승인 대기 확인), `--approve`(발송), `--reject`(폐기)

## 폴더 구성

| 경로 | 설명 |
|---|---|
| `test-report-mailer.jar` | 실행 파일 (배포 zip 에 포함, 직접 빌드 시 `target/` 에 생성) |
| `config/application.properties` | 원본 경로, SMTP, 제목 형식 등 모든 설정 |
| `config/mailing_list.csv` | 수신자 목록 (이름, 이메일, TO/CC/BCC, 사용여부 Y/N) |
| `samples/` | 검증용 예시 파일 (현황 엑셀, 원천 건수 CSV 예시) |
| `config/verify.properties` | 클라우드 검증용 설정 (`scripts/verify.sh`) |
| `scripts/run.bat` | 1회 실행 (작업 스케줄러가 호출) |
| `scripts/register_task.bat` | 평일 09:00 / 18:00 작업 스케줄러 등록 |
| `scripts/approve.bat` | 승인 대기 엑셀 확인 후 발송(Y) / 보류(N) / 폐기(R) |
| `output/` · `logs/` · `state/` | 생성 보고서(기본 30일 보관) · 실행 로그 · 마지막 발송 기록 |

## 설치 및 설정

1. **Java 17 이상** 설치 (예: [Eclipse Temurin](https://adoptium.net) JDK 17/21). `java -version` 으로 확인.
2. 배포 zip 을 원하는 폴더에 압축 해제 (예: `C:\test-report-mailer`).
3. `config/application.properties` 수정
   - `source.file` : 현황 엑셀 경로. **역슬래시 대신 `/`** 를 사용하세요.
   - `rawCounts.file` : 900N02/AC4111 건수 CSV. 아직 없으면 **값을 비워 두세요** (예시 값이 반영되지 않도록).
   - `mail.*` : SMTP 정보. Gmail 은 구글 계정 → 보안 → 2단계 인증 → **앱 비밀번호** 를 발급받아 사용합니다.
4. 비밀번호는 설정 파일 대신 환경변수로 등록 (권장)
   ```
   setx MAIL_PASSWORD "앱비밀번호16자리"
   ```
5. `config/mailing_list.csv` 에 수신자 입력 (엑셀에서 편집 후 CSV 로 저장해도 됩니다).
6. 테스트 실행
   ```
   scripts\run.bat --dry-run    ← 메일 없이 output\ 에 엑셀 + 메일미리보기.html 생성
   scripts\run.bat              ← 실제 발송 (처음엔 수신자를 본인만 두고 확인)
   ```
7. 주기 실행 등록: `scripts\register_task.bat` 실행 (시각은 파일 안의 `/ST` 수정).

## 실행 옵션

| 옵션 | 설명 |
|---|---|
| (없음) | 1회 실행 후 종료 — 작업 스케줄러용 |
| `--dry-run` | 메일을 보내지 않고 보고서와 본문 미리보기만 생성 |
| `--force` | `mail.skipIfUnchanged=true` 여도 무조건 발송 |
| `--daemon` | 프로그램을 켜 둔 채 `schedule.times` 시각마다 실행 (작업 스케줄러 대신 사용 시) |
| `--as-of <시각>` | 원장을 해당 시각 기준으로 조회 (예: `2026-01-01T09:00`, 검증용) |
| `--config <파일>` | 다른 설정 파일 사용 |

## 원장 조회 (실시간 전문 980001~980004)

`ledger.enabled=true` 이면 실행할 때마다 원장 테이블을 조회해, 테스트 요건을 충족한 거래를
기관별 시트의 **확인 일시 / 거래고유번호** 칸에 기록합니다. 통과여부(O/X)와 진척률은 기존 엑셀 수식이 계산합니다.

- 기관 구분: 상세 시트의 매입사·발급사 코드 ↔ 원장의 매입사·발급사 기관코드
- 요건: `config/ledger_rules.csv` — 전문구분 + 테스트 케이스 문구별로 SQL 조건과 최소건수를 지정
  ```
  980001,전체 조회,1,"TRX_DV_CD = '980001' AND TRX_STAT_CD = '00' AND ADD_INQ_COND IS NULL"
  980001,요금별 조회,1,"TRX_DV_CD = '980001' AND TRX_STAT_CD = '00' AND ADD_INQ_COND IS NOT NULL"
  ```
- 테이블·컬럼명, DB 접속 정보는 `application.properties` 의 `ledger.*` 에서 변경 (비밀번호는 환경변수 `LEDGER_DB_PASSWORD`)
- `ledger.clearUnmatched=true` 이면 원장에서 확인되지 않은 행의 기존 입력값을 지웁니다 (원장 기준).
- 메일 본문에 **직전 발송 대비 신규 충족 / 충족 해제** 항목이 표시되고, `mail.skipIfUnchanged=true` 면 변경이 없을 때 발송을 건너뜁니다.

## 원천 건수 CSV 형식 (예시: `samples/raw_counts_sample.csv`)

상세 시트 Q~W, AA~AC 열은 `VLOOKUP(전문코드&매입사&발급사&구분, 범위, 2)` 로 건수를 가져오는데,
현재 엑셀에서는 범위가 `#REF!` 로 끊겨 있습니다. CSV 를 지정하면 프로그램이 `원천데이터` 시트를 만들고 그 범위로 연결합니다.

```
전문코드,매입사,발급사,구분,건수
900N02,381,381,S,7
AC4111,374,092,T1,5
```

- 900N02 구분: `S` 성공, `F` 실패, `B1` 변경전실패, `A1` 무응답-성공, `A2` 무응답-실패, `RN1` 재신규성공, `RN2` 재신규실패
- AC4111 구분: `T1` 성공, `T2` 변경전 해지실패, `T3` 변경후 신규실패
- 매입사/발급사 코드의 앞자리 0(`011`, `037`, `092`, `089`)이 엑셀 저장 시 사라지지 않도록 주의하세요.

## 프롬프트로 검증하기 (클라우드)

로컬 실행 없이 Claude 에게 요청하면 아래를 실행해 결과를 보여 줍니다.

```
scripts/verify.sh          → 빌드, 자동 테스트, 샘플 보고서 생성, 기관별 결과표, 메일 본문 캡처
scripts/verify-ledger.sh   → 원장 시나리오(samples/ledger_scenario.sql)를 1.1 09:00 / 15:00 에 조회한 결과표와 메일 캡처
scripts/verify-ledger.sh 2026-01-01T10:00 2026-01-01T17:00   ← 원하는 시각으로 조회
```

### 원장 거래를 추가해 가며 확인 (샌드박스 DB)

프롬프트로 "KB국민카드 980002 정상 거래 넣고 돌려줘"처럼 요청하면 아래 명령으로 거래를 쌓고 실행 결과를 비교합니다.
데이터는 `sandbox/ledger.mv.db` 파일에 남아 다음 실행까지 유지됩니다.

```
scripts/ledger-db.sh reset                         ← 기본 시나리오(11건) + 추가 시나리오로 초기화 (reset empty: 빈 원장)
scripts/ledger-db.sh add KB국민카드 980002          ← 정상 거래 1건 (현재 시각)
scripts/ledger-db.sh add 하나카드 980001 --stat 01  ← 비정상 거래
scripts/ledger-db.sh add 삼성카드 980001 --cond 1   ← 추가조회조건(통신요금) 거래
scripts/ledger-db.sh add 삼성카드 980001 --paging   ← 페이징 거래
scripts/ledger-db.sh add 삼성카드 980004 --case 재신규실패 --at "2026-01-01 16:30:00"
scripts/ledger-db.sh list [기관명]                  ← 원장 내용 + 현황 엑셀에 기재된 거래고유번호 표시
scripts/ledger-db.sh run                           ← 현황 엑셀 생성 → 승인 대기 (기관별 진척률, 변경 항목, 메일 캡처)
scripts/ledger-db.sh approve | reject | status     ← 승인 발송(가짜 SMTP 수신 확인) / 폐기 / 대기 확인
```

- 기본 시나리오(`samples/ledger_scenario.sql`, 11건)는 자동 테스트 기대값이 묶여 있어 고정입니다.
- `add` 로 넣은 거래는 `samples/ledger_scenario_user.sql` 에 누적 기록되어, 세션이 바뀌어도 `reset` 하면 다시 적재됩니다.

자동 테스트(`src/test`)는 실제 현황 엑셀(`samples/`)로 진척률 계산, `#REF!` 연결 복구,
메일링 리스트 처리, 발송 일정, 가짜 SMTP 서버(GreenMail)를 통한 메일·첨부 발송, 변경 없음 시 발송 생략,
오류 시 관리자 알림을 검증합니다.

## 직접 빌드

```
mvn package        → target\test-report-mailer.jar
```
