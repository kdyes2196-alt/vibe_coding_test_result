@echo off
rem 평일 09:00, 18:00 에 자동 실행되도록 Windows 작업 스케줄러에 등록
rem 시각을 바꾸려면 /ST 값을 수정하세요. 삭제: schtasks /Delete /TN "통합테스트현황_0900" /F
chcp 65001 > nul
set RUN=%~dp0run.bat
schtasks /Create /F /TN "통합테스트현황_0900" /TR "\"%RUN%\"" /SC WEEKLY /D MON,TUE,WED,THU,FRI /ST 09:00
schtasks /Create /F /TN "통합테스트현황_1800" /TR "\"%RUN%\"" /SC WEEKLY /D MON,TUE,WED,THU,FRI /ST 18:00
echo.
echo 등록 완료. 작업 스케줄러에서 "통합테스트현황_*" 작업을 확인하세요.
pause
