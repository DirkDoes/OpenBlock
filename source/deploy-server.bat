@echo off
setlocal
cd /d "%~dp0"

if /I "%~1"=="--no-start" (
	call gradlew.bat build
) else if "%~1"=="" (
	call gradlew.bat build runServer
) else (
	echo Usage: deploy-server.bat [--no-start]
	exit /b 1
)

exit /b %errorlevel%
