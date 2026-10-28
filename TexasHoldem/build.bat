@echo off
setlocal
cd /d "%~dp0"
if exist build\classes rmdir /s /q build\classes
if not exist build\classes mkdir build\classes
if not exist dist mkdir dist
javac --release 17 -encoding UTF-8 -Xlint:all -d build\classes PokerCommon\src\poker\common\*.java PokerServer\src\poker\server\*.java PokerClient\src\poker\client\*.java
if errorlevel 1 exit /b 1
jar cfe dist\poker-server.jar poker.server.ServerMain -C build\classes poker\common -C build\classes poker\server
if errorlevel 1 exit /b 1
jar cfe dist\poker-client.jar poker.client.ClientMain -C build\classes poker\common -C build\classes poker\client
if errorlevel 1 exit /b 1
echo Built server and client JAR files. Requires Java 17 or later.
