@echo off
rem ---------------------------------------------------------------------------
rem  Fallback launcher for the local fake LLM endpoint (port 18765).
rem  Only needed if the endpoint is not already running -- i.e. if the
rem  companion in-game does not react to "/numen tell Alice ...".
rem
rem  Double-click this file. Keep the window open while you test.
rem  ASCII-only on purpose: cmd.exe on a Chinese Windows reads .bat as GBK,
rem  so UTF-8 Chinese text here would turn into garbage.
rem ---------------------------------------------------------------------------
chcp 65001 >nul
cd /d "%~dp0"
echo.
echo   Starting local fake LLM endpoint on port 18765 ...
echo   DO NOT close this window while testing in-game.
echo.
"C:\Users\cfl\.workbuddy-ai\binaries\python\versions\3.13.12\python.exe" fake-llm.py 18765
echo.
echo   Endpoint stopped.
pause
