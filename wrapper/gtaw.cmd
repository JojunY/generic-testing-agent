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
if "%GTA_JAR:~0,1%"=="{" goto failed
:run
rem one line: cmd.exe reads a batch file line by line from its byte offset, and "gtaw upgrade" rewrites this
rem file while it runs, so a next line would be read from the new file. exit /b keeps java's exit code.
java %GTA_JAVA_OPTS% -jar "%GTA_JAR%" --root "%GTA_DIR%" %* & exit /b

:failed
rem the JSON error of gtaw-download.ps1, with the exit code of its category as ./gtaw gives it: config 21,
rem environment 30. Delayed expansion keeps the JSON's quotes intact; it is enabled only here, since it would
rem eat a "!" in the arguments passed on to gtaw above. exit /b runs outside any ( ) block, as in mvnw.cmd.
setlocal EnableDelayedExpansion
echo !GTA_JAR!
set "GTA_CODE=30"
if not "!GTA_JAR:CONFIG_NOT_INITIALIZED=!"=="!GTA_JAR!" set "GTA_CODE=21"
exit /b !GTA_CODE!
