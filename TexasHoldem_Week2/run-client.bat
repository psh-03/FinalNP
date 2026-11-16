@echo off
cd /d "%~dp0"
where javaw >nul 2>nul
if errorlevel 1 goto no_java
if not exist dist\week2-client.jar goto no_jar
start "" javaw -jar dist\week2-client.jar
exit /b 0
:no_java
echo Install Java 17 or later and add its bin directory to PATH.
pause
exit /b 1
:no_jar
echo Run build.bat first.
pause
exit /b 1
