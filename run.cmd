@echo off
rem Windows launcher. Everything lives in the jar; this only finds Java and hands over.
cd /d "%~dp0"

set "JAVA=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
"%JAVA%" -version >nul 2>nul
if errorlevel 1 (
    echo Java 21 is not installed, or not on PATH.
    echo Install it from https://adoptium.net/temurin/releases/?version=21
    pause
    exit /b 2
)

if not exist "build\libs\sockseek-cli.jar" (
    echo Building...
    call gradlew.bat --quiet fatJar || exit /b 1
)

"%JAVA%" -jar "build\libs\sockseek-cli.jar" %*
set RC=%errorlevel%
if "%~1"=="" pause
exit /b %RC%
