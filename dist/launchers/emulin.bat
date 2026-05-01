@echo off
rem --------------------------------------------------------------------
rem  Emulin launcher (Windows)
rem
rem  使い方:
rem    emulin.bat                  : busybox ash -i (対話シェル)
rem    emulin.bat ls /             : busybox ls / を 1 回実行
rem    emulin.bat ash -c "echo hi"
rem
rem  必要な物: java (JRE 11+) が PATH に存在すること
rem --------------------------------------------------------------------
setlocal

set "HERE=%~dp0"
if "%HERE:~-1%"=="\" set "HERE=%HERE:~0,-1%"
set "ROOTFS=%HERE%\rootfs"

set "JAR="
for %%i in ("%HERE%\lib\emulin-*-all.jar") do set "JAR=%%i"

if not defined JAR (
    echo emulin.bat: error: lib\emulin-*-all.jar not found under %HERE% 1>&2
    exit /b 2
)
if not exist "%ROOTFS%\bin\busybox" (
    echo emulin.bat: error: %ROOTFS%\bin\busybox not found 1>&2
    exit /b 2
)
where java >nul 2>nul
if errorlevel 1 (
    echo emulin.bat: error: java not found on PATH ^(install JRE 11+^) 1>&2
    exit /b 2
)

cd /d "%ROOTFS%"

if "%~1"=="" (
    java -jar "%JAR%" "%ROOTFS%" -CJ /bin/busybox ash -i
) else (
    java -jar "%JAR%" "%ROOTFS%" -CJ /bin/busybox %*
)

endlocal
