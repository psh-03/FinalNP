@echo off
title Holdem Week2 Server
chcp 949 >nul
cd /d "%~dp0"
java -jar dist\week2-server.jar %*
pause
