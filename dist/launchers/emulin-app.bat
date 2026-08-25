@echo off
rem  emulin-app.bat -- open the Emulin launcher / dashboard (issue #948).
rem  Double-click this file. It just calls "emulin.bat app" so that the terminal
rem  handling (Windows Terminal relaunch, issue #121) stays in one place.
call "%~dp0emulin.bat" app
