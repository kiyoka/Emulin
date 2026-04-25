@echo off
copy bootwin.sh root\etc\rc
java emulin.Emulin xxx -S xxx > boot.bat
echo Done.
