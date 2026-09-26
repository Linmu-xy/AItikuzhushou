@echo off
setlocal
set "PGROOT=C:\Program Files\PostgreSQL\17"
set "NMAKE=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Tools\MSVC\14.44.35207\bin\Hostx64\x64\nmake.exe"
cd /d "%~dp0"
"%NMAKE%" /F Makefile.win install
endlocal
