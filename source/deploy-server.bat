@echo off
call "%~dp0deploy-test.bat" %*
exit /b %errorlevel%
