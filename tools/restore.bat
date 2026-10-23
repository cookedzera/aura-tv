@echo off
rem Double-click to put the TV back exactly as it was. Add -RemoveAura to also uninstall Aura,
rem or -DryRun to only print what would be done. Example: restore.bat -RemoveAura
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0restore.ps1" %*
pause
