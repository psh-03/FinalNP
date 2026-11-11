@echo off
title Week1 Server - Console
chcp 949 >nul
if errorlevel 1 goto encoding_failed
cd /d "%~dp0"
java -jar dist\week1-server-console.jar %*
pause
exit /b
:encoding_failed
echo Cannot select Windows Korean code page 949.
pause
