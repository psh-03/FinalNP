@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist build\server mkdir build\server
if not exist build\client mkdir build\client
if not exist dist mkdir dist
javac --release 17 -encoding UTF-8 -Xlint:all -d build\server PokerServer\src\poker\server\ServerMain.java
if errorlevel 1 goto failed
javac --release 17 -encoding UTF-8 -Xlint:all -d build\client PokerClient\src\poker\client\ClientMain.java
if errorlevel 1 goto failed
jar cfe dist\week1-server.jar poker.server.ServerMain -C build\server .
if errorlevel 1 goto failed
jar cfe dist\week1-client.jar poker.client.ClientMain -C build\client .
if errorlevel 1 goto failed
echo 빌드 완료
pause
exit /b 0
:failed
echo 빌드 실패. JDK 17 이상과 오류 메시지를 확인하세요.
pause
exit /b 1
