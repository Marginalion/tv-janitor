@echo off
setlocal EnableDelayedExpansion
REM TV Janitor deep snapshot: everything about packages and settings, read only, to compare before / after.
REM Usage: tvj_deep.bat, then type the TV name (as in tvj_test) and a label (before, after...).
cd /d "%~dp0"
set /p NAME=Short TV name (as in tvj_test, for example novex): 
for /f "tokens=* delims= " %%a in ("%NAME%") do set NAME=%%a
set NAME=%NAME: =_%
set DIR=tests\%NAME%
if not exist %DIR% mkdir %DIR%
set TV_IP=
if exist %DIR%\ip.txt set /p TV_IP=<%DIR%\ip.txt
if "%TV_IP%"=="" set /p TV_IP=TV IP address: 
echo %TV_IP%>%DIR%\ip.txt
set DEV=%TV_IP%:5555
set /p LABEL=Label (before / after / anything): 
set OUT=%DIR%\deep_%LABEL%
if not exist %OUT% mkdir %OUT%
adb connect %TV_IP% >nul 2>&1
timeout /t 3 /nobreak >nul
adb -s %DEV% get-state 2>nul | findstr /x "device" >nul || (echo  The TV does not answer at %TV_IP%. & pause & exit /b 1)
echo  Saving to %OUT% (takes 2-4 minutes, the TV is only read, nothing changes)
set A=adb -s %DEV% shell
echo  1/9 system
(echo %date% %time% & %A% "uptime; cat /proc/loadavg; df -h /data") > %OUT%\when.txt 2>&1
%A% getprop > %OUT%\props.txt 2>&1
echo  2/9 package lists
%A% pm list packages -f -i -U > %OUT%\packages_all.txt 2>&1
%A% pm list packages -d > %OUT%\disabled.txt 2>&1
%A% pm list packages -e > %OUT%\enabled.txt 2>&1
%A% pm list packages -s > %OUT%\system.txt 2>&1
%A% pm list packages -3 > %OUT%\third_party.txt 2>&1
%A% pm list packages -u > %OUT%\with_uninstalled.txt 2>&1
echo  3/9 package details (big)
%A% dumpsys package > %OUT%\dumpsys_package.txt 2>&1
echo  4/9 app ops and standby buckets (slow)
%A% "for p in $(pm list packages | cut -d: -f2); do echo == $p; cmd appops get $p; done" > %OUT%\appops.txt 2>&1
%A% "am get-standby-bucket" > %OUT%\standby.txt 2>&1
%A% "dumpsys deviceidle whitelist" > %OUT%\deviceidle.txt 2>&1
echo  5/9 settings
%A% settings list global > %OUT%\settings_global.txt 2>&1
%A% settings list secure > %OUT%\settings_secure.txt 2>&1
%A% settings list system > %OUT%\settings_system.txt 2>&1
echo  6/9 home, keyboard, overlays
(%A% "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME" & echo == all homes & %A% "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME") > %OUT%\home.txt 2>&1
(%A% "ime list -a -s" & echo == default & %A% settings get secure default_input_method) > %OUT%\ime.txt 2>&1
%A% "cmd overlay list" > %OUT%\overlays.txt 2>&1
echo  7/9 processes and memory
%A% "ps -A -o PID,PPID,USER,RSS,NAME" > %OUT%\processes.txt 2>&1
%A% "dumpsys -t 40 meminfo" > %OUT%\meminfo.txt 2>&1
%A% "dumpsys activity activities" > %OUT%\activities.txt 2>&1
%A% "dumpsys activity services" > %OUT%\services.txt 2>&1
%A% "dumpsys jobscheduler" > %OUT%\jobs.txt 2>&1
echo  8/9 logs
%A% "logcat -d -b crash" > %OUT%\crash.txt 2>&1
%A% "logcat -d" > %OUT%\logcat.txt 2>&1
echo  9/9 TV Janitor files
%A% cat /sdcard/Android/data/io.github.marginalion.tvjanitor/files/log.txt > %OUT%\janitor_log.txt 2>nul
%A% cat /sdcard/Android/data/io.github.marginalion.tvjanitor/files/report.txt > %OUT%\janitor_report.txt 2>nul
%A% cat /sdcard/Android/data/io.github.marginalion.tvjanitor/files/debug.txt > %OUT%\janitor_debug.txt 2>nul
%A% cat /sdcard/Android/data/com.gathin.tvhome/files/config.json > %OUT%\launcher_config.txt 2>nul
%A% "dumpsys package io.github.marginalion.tvjanitor | grep -E 'versionName|versionCode'" > %OUT%\janitor_version.txt 2>&1
%A% screencap -p /data/local/tmp/tvj.png
adb -s %DEV% pull /data/local/tmp/tvj.png %OUT%\screen.png >nul
%A% rm /data/local/tmp/tvj.png
echo.
echo  Done: %OUT%
pause
