@echo off
setlocal
cd /d "%~dp0"

set "NO_START=false"
if /I "%~1"=="--no-start" (
	set "NO_START=true"
) else if not "%~1"=="" (
	echo Usage: deploy-test.bat [--no-start]
	exit /b 1
)

if exist ".env" (
	for /f "usebackq eol=# tokens=1,* delims==" %%A in (".env") do set "%%A=%%B"
)

set "JAVA_DIR="
if defined OPENBLOCK_JAVA_HOME set "JAVA_DIR=%OPENBLOCK_JAVA_HOME%"
if not defined JAVA_DIR for /d %%D in ("%ProgramFiles%\Java\jdk-25*") do if exist "%%~fD\bin\java.exe" set "JAVA_DIR=%%~fD"
if not defined JAVA_DIR (
	echo Java 25 was not found. Set OPENBLOCK_JAVA_HOME to its install folder.
	exit /b 1
)
set "JAVA_HOME=%JAVA_DIR%"
set "PATH=%JAVA_DIR%\bin;%PATH%"

if not exist "..\test-server" (
	if not exist "..\server\start.bat" (
		echo The Minecraft 26.2 server template is missing: ..\server\start.bat
		exit /b 1
	)
	echo Creating test-server from server...
	powershell -NoProfile -Command "Copy-Item -LiteralPath '..\server' -Destination '..\test-server' -Recurse"
	if errorlevel 1 exit /b 1
)

call gradlew.bat build
if errorlevel 1 exit /b 1

powershell -NoProfile -Command "$root = (Resolve-Path '..\test-server').Path; $running = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -and $_.CommandLine.Contains($root) }; if ($running) { exit 1 }"
if errorlevel 1 (
	echo The test server is already running. Stop it cleanly, then deploy again.
	exit /b 1
)

if not exist "..\test-server\mods" mkdir "..\test-server\mods"
del /Q "..\test-server\mods\openblock-*.jar" >nul 2>&1
copy /Y "build\libs\openblock-1.0.0.jar" "..\test-server\mods\openblock-1.0.0.jar" >nul
if errorlevel 1 exit /b 1

echo Deployed OpenBlock to the Minecraft 26.2 test server.
if /I "%NO_START%"=="true" exit /b 0

pushd "..\test-server"
call start.bat
set "SERVER_EXIT=%errorlevel%"
popd
exit /b %SERVER_EXIT%
