@echo off
rem gtaw.cmd - gta wrapper for Windows (like mvnw.cmd). Downloads the pinned gta-cli jar from the company Nexus,
rem verifies its sha256 before every run, then runs it. Managed by gta: do not edit (gtaw upgrade rewrites it).
setlocal
rem an inherited GTA_JAR must never stand in for the verified jar
set "GTA_JAR="
set "GTA_DIR=%~dp0"
if "%GTA_DIR:~-1%"=="\" set "GTA_DIR=%GTA_DIR:~0,-1%"
if defined GTA_CLI_JAR (
  set "GTA_JAR=%GTA_CLI_JAR%"
  goto run
)
for /f "usebackq delims=" %%J in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%GTA_DIR%\.gta\wrapper\gtaw-download.ps1" -Root "%GTA_DIR%"`) do set "GTA_JAR=%%J"
if not defined GTA_JAR exit /b 30
if "%GTA_JAR:~0,1%"=="{" (
  echo %GTA_JAR%
  rem the exit code of the error category, as ./gtaw gives it: config 21, environment 30
  echo %GTA_JAR%| find "CONFIG_NOT_INITIALIZED" >nul && exit /b 21
  exit /b 30
)
:run
java %GTA_JAVA_OPTS% -jar "%GTA_JAR%" --root "%GTA_DIR%" %*
exit /b %ERRORLEVEL%
