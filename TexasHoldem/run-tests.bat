@echo off
setlocal
cd /d "%~dp0"
call build.bat
if errorlevel 1 exit /b 1
if not exist build\test-classes mkdir build\test-classes
javac --release 17 -encoding UTF-8 -cp build\classes -d build\test-classes tests\poker\server\*.java tests\poker\client\*.java
if errorlevel 1 exit /b 1
java -cp "build\classes;build\test-classes" poker.server.SmokeTests
if errorlevel 1 exit /b 1
java -Djava.awt.headless=true -cp "build\classes;build\test-classes" poker.client.RenderPreview
if errorlevel 1 exit /b 1
echo Optional TCP test: python tests\network_test.py
