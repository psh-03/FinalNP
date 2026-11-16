@echo off
cd /d "%~dp0"
where javac >nul 2>nul
if errorlevel 1 goto no_jdk
if not exist bin mkdir bin
if not exist dist mkdir dist
javac --release 17 -encoding UTF-8 -d bin src\*.java
if errorlevel 1 goto failed
jar cfe dist\week2-server.jar ServerMain -C bin .
if errorlevel 1 goto failed
jar cfe dist\week2-client.jar ClientMain -C bin .
if errorlevel 1 goto failed
echo Build complete.
pause
exit /b 0
:no_jdk
echo Install JDK 17 or later and add its bin directory to PATH.
pause
exit /b 1
:failed
echo Build failed. Check the error above.
pause
exit /b 1
