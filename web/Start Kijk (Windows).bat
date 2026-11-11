@echo off
cd /d "%~dp0"
python kijk_server.py
if errorlevel 1 py kijk_server.py
pause
