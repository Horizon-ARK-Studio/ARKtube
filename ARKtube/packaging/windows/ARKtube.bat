@echo off
rem ARKtube.bat - double-click entry point.
rem
rem .ps1 files don't run on double-click by default on Windows (they
rem open in an editor instead), so this thin .bat is the actual
rem recommended entry point in the zip. It just runs Launch-ARKtube.ps1
rem with the execution policy bypassed for this one process only (does
rem not change the user's system-wide PowerShell execution policy).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Launch-ARKtube.ps1"
