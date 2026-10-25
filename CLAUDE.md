# 통합테스트 현황 자동 메일 발송 (Java 17 / Maven)

사용자는 로컬에서 실행하지 않고 **프롬프트로만 개발·검증**한다. 변경 후에는 항상 직접 검증하고 결과를 보여줄 것.

## 검증 방법
- `scripts/verify.sh` : 빌드 + JUnit 테스트 + 샘플로 dry-run + 기관별 결과표 출력 + 메일 본문 캡처(`verify-output/mail.png`)
  - 캡처 이미지는 사용자에게 보여 준다. 다른 원천 건수로 보려면 `scripts/verify.sh <csv경로>`.
- 테스트만: `LANG=C.UTF-8 mvn -B test`
- 리눅스에서 한글 파일명을 쓰려면 `LANG=C.UTF-8` 과 `-Dsun.jnu.encoding=UTF-8` 이 필요하다 (verify.sh 와 surefire 에 설정됨).
- 이 클라우드 환경은 외부 SMTP(587/465) 접속이 막혀 있다. 메일 발송은 GreenMail(가짜 SMTP) 테스트로 검증한다.

## 구조
- `src/main/java/com/testreport/` : App(실행·스케줄·변경감지) → WorkbookUpdater(복사·원천연결·재계산·저장) → SummaryExtractor(요약 시트 읽기) → MailBodyBuilder / MailSender
- 엑셀 수식이 계산 로직의 원본이다. Java 에서 조건 판정을 재구현하지 말고 POI 로 재계산한 값을 읽는다.
- `samples/test-status-sample.xlsx` : 사용자가 준 실제 현황 엑셀. 테스트의 기대값(기관 12개, 원본 KB국민카드 40%, 원천 반영 시 100% 등)이 이 파일에 묶여 있다.
- `config/application.properties` : 실제 운영(사용자 Windows PC) 설정, `config/verify.properties` : 검증용 설정.

## 알려진 원본 엑셀 문제 (사용자에게 안내됨)
- 상세 시트 Q~W, AA~AC 의 VLOOKUP 범위가 `#REF!` → 원천 CSV 지정 시 `원천데이터` 시트로 자동 연결
- 요약 시트 R15~R17 이 상세 시트 Y14~Y16 을 참조 (한 행 어긋남)
- 요약 V5 '기관수(11)' 표기와 실제 기관 수(12) 불일치

## 규칙
- 메시지/로그/주석은 한국어. 비밀번호·토큰은 코드나 설정 파일에 커밋하지 않는다 (환경변수 MAIL_PASSWORD).
