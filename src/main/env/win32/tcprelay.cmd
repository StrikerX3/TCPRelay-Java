@echo off

setlocal

:params
set PARAMS=%PARAMS% %1
shift
if "%1a" == "a" goto runjava
goto params

:runjava
java -jar tcprelay-${tcprelay.version}.jar %PARAMS%

endlocal
