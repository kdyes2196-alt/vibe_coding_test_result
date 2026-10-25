@echo off
rem 통합테스트 현황 갱신 + 메일 발송 (작업 스케줄러에서 이 파일을 실행)
chcp 65001 > nul
cd /d "%~dp0.."
rem 메일 비밀번호(Gmail 앱 비밀번호). 시스템 환경변수로 등록했다면 이 줄은 지워도 됩니다.
rem set MAIL_PASSWORD=xxxxxxxxxxxxxxxx
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -jar test-report-mailer.jar --config config\application.properties %*
