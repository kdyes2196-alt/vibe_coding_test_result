@echo off
rem 승인 대기 보고서를 엑셀로 열어 확인한 뒤, 담당자가 승인하면 메일링 리스트로 발송합니다.
chcp 65001 > nul
cd /d "%~dp0.."
rem 메일 비밀번호(앱 비밀번호). 시스템 환경변수로 등록했다면 이 줄은 지워도 됩니다.
rem set MAIL_PASSWORD=xxxxxxxxxxxxxxxx
set APP=java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -jar test-report-mailer.jar --config config\application.properties

%APP% --status
if errorlevel 1 (
  pause
  exit /b 1
)

rem 승인 대기 엑셀 열기 (state.dir 을 바꿨다면 경로도 함께 수정)
for %%f in ("state\pending\*.xlsx") do start "" "%%~ff"

echo.
choice /c YNR /m "엑셀 내용을 확인했습니까?  Y=메일 발송  N=보류(나중에 다시)  R=폐기"
if errorlevel 3 goto reject
if errorlevel 2 goto hold

%APP% --approve
pause
exit /b 0

:hold
echo 보류했습니다. 승인 대기 상태로 남아 있으며 다음에 approve.bat 으로 다시 확인할 수 있습니다.
pause
exit /b 0

:reject
%APP% --reject
pause
exit /b 0
