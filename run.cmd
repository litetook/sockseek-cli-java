@echo off
rem The one command. Everything it needs it fetches itself, says so on screen, and writes
rem the same lines into logs\install.log:
rem
rem   Java     -> a private copy in jdk\, pinned and sha256-checked (dependencies.yaml)
rem   the jar  -> from the latest GitHub release, if it is not here already
rem   sockseek -> the jar does that itself, the first time a download needs it
rem
rem Nothing is installed system-wide, nothing goes in the registry, no admin rights. Delete
rem the folder and it is gone.
setlocal
cd /d "%~dp0"

set "LOG=%~dp0logs\install.log"
if not exist "%~dp0logs" mkdir "%~dp0logs"

rem ---------------------------------------------------------------- Java
set "JAVA="
if exist "jdk\bin\java.exe" set "JAVA=%~dp0jdk\bin\java.exe"
if not defined JAVA if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA (
    where java >nul 2>nul && set "JAVA=java"
)

if defined JAVA call :check_version "%JAVA%" || set "JAVA="

if defined JAVA goto :have_java

rem No question here, by the owner's call of 2026-10-19: one command that pulls what it
rem needs and says what it is doing. The only thing anybody types is the Soulseek login.
echo Java 21 was not found, so this fetches a private copy into "%~dp0jdk".
echo About 47 MB, pinned and sha256-checked, nothing installed system-wide, no admin
echo rights needed. Deleting that folder undoes it. The log is "%LOG%".
powershell -NoProfile -ExecutionPolicy Bypass -File "tools\install-java.ps1" -LogFile "%LOG%"
if errorlevel 1 (
    echo Java could not be set up. The whole attempt is in "%LOG%".
    pause
    exit /b 2
)
set "JAVA=%~dp0jdk\bin\java.exe"

:have_java

rem ---------------------------------------------------------------- the jar
set "JAR="
if exist "sockseek-cli.jar" set "JAR=%~dp0sockseek-cli.jar"
if not defined JAR if exist "build\libs\sockseek-cli.jar" set "JAR=%~dp0build\libs\sockseek-cli.jar"

if defined JAR goto :have_jar
if not exist "gradlew.bat" goto :fetch_jar
echo Building the jar from this checkout...
call gradlew.bat --quiet fatJar
if exist "build\libs\sockseek-cli.jar" set "JAR=%~dp0build\libs\sockseek-cli.jar"
if defined JAR goto :have_jar

:fetch_jar

echo sockseek-cli.jar is not here yet; taking it from the latest release.
powershell -NoProfile -ExecutionPolicy Bypass -File "tools\fetch-jar.ps1" -LogFile "%LOG%"
if errorlevel 1 (
    echo The jar could not be downloaded. The whole attempt is in "%LOG%".
    echo https://github.com/litetook/sockseek-cli-java/releases also has it.
    pause
    exit /b 2
)
set "JAR=%~dp0sockseek-cli.jar"

:have_jar

rem ---------------------------------------------------------------- and over to the tool
"%JAVA%" -jar "%JAR%" %*
set RC=%errorlevel%
if "%~1"=="" pause
exit /b %RC%

rem ---------------------------------------------------------------- helpers
:check_version
rem "java -version" writes to stderr, and prints 21.0.12 for 21 and 1.8.0_421 for 8
for /f "tokens=3" %%v in ('"%~1" -version 2^>^&1 ^| findstr /i "version"') do (
    set "FOUND=%%~v"
    goto :check_known
)
exit /b 1
:check_known
for /f "tokens=1 delims=." %%m in ("%FOUND%") do set "MAJOR=%%m"
if "%MAJOR%"=="1" (
    echo Found Java %FOUND%, which is too old: this tool needs 21.
    exit /b 1
)
if %MAJOR% GEQ 21 exit /b 0
echo Found Java %FOUND%, which is too old: this tool needs 21.
exit /b 1
