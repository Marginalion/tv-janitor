@echo off
setlocal EnableDelayedExpansion
REM TV Janitor test kit for Windows. Results go to tests\<name>\. Only options 2, 6 and 7 change the TV.
REM Put this file next to adb.exe (Android platform-tools) and the TV Janitor APKs from the release.
cd /d "%~dp0"
where adb >nul 2>&1 || if not exist adb.exe (
  echo  adb.exe not found. Download Android "platform-tools" for Windows:
  echo    https://developer.android.com/tools/releases/platform-tools
  echo  unzip it and put this file, the APKs and adb.exe in the same folder.
  pause & exit /b 1
)
set FULL=
for %%f in (TVJanitor-*-full.apk tvjanitor.apk) do if "!FULL!"=="" if exist "%%f" set FULL=%%f
set LITE=
for %%f in (TVJanitor-*-lite.apk tvjanitor-lite.apk) do if "!LITE!"=="" if exist "%%f" set LITE=%%f
set /p NAME=Short TV name (for example xiaomi, novex, philips): 
REM Drop spaces typed around the name: an unquoted "tests\ philips" breaks every path below.
for /f "tokens=* delims= " %%a in ("%NAME%") do set NAME=%%a
set NAME=%NAME: =_%
set DIR=tests\%NAME%
if not exist %DIR% mkdir %DIR%
set TV_IP=
if exist %DIR%\ip.txt set /p TV_IP=<%DIR%\ip.txt
if "%TV_IP%"=="" set /p TV_IP=TV IP address: 
echo %TV_IP%>%DIR%\ip.txt
set DEV=%TV_IP%:5555
call :connect

:menu
echo.
echo  TV: %NAME% (%TV_IP%)
echo   1  Snapshot BEFORE (do this first, on the untouched TV)
echo   2  Install / update TV Janitor FULL and open it on the TV
echo   7  Install / update TV Janitor LITE instead (one button)
echo   3  Snapshot AFTER (2-3 minutes after a reboot)
echo   4  Copy the app log, report and a screenshot of the TV
echo   5  Compare: what is disabled now vs BEFORE (checks "Restore everything")
echo   6  Reset TV Janitor data (start again from the first screen; asks for debugging again)
echo   8  Pack the results into tests\%NAME%.zip to attach to a GitHub issue
echo   0  Exit
set /p C=Choose: 
if "%C%"=="1" call :snap before & goto menu
if "%C%"=="2" goto install
if "%C%"=="3" call :snap after & goto menu
if "%C%"=="4" goto pull
if "%C%"=="5" goto compare
if "%C%"=="6" goto reset
if "%C%"=="7" goto installlite
if "%C%"=="8" goto pack
if "%C%"=="0" exit /b 0
goto menu

:install
call :connect || goto menu
if "%FULL%"=="" (echo  No TVJanitor-*-full.apk next to this file. & goto menu)
adb -s %DEV% install -r "%FULL%"
adb -s %DEV% shell am start -n io.github.marginalion.tvjanitor/.MainActivity
echo  On the TV: "Allow USB debugging?" - tick "Always allow", OK.
goto menu

:installlite
call :connect || goto menu
if "%LITE%"=="" (echo  No TVJanitor-*-lite.apk next to this file. & goto menu)
adb -s %DEV% install -r "%LITE%"
adb -s %DEV% shell am start -n io.github.marginalion.tvjanitor/.MainActivity
echo  On the TV: "Allow USB debugging?" - tick "Always allow", OK.
goto menu

:pack
powershell -NoProfile -Command "Compress-Archive -Force -Path '%DIR%\*' -DestinationPath '%DIR%.zip'"
echo  Packed: %DIR%.zip  (attach it to your issue on GitHub)
goto menu

:reset
call :connect || goto menu
adb -s %DEV% shell pm clear io.github.marginalion.tvjanitor
adb -s %DEV% shell am start -n io.github.marginalion.tvjanitor/.MainActivity
echo  Data cleared. On the TV: "Allow USB debugging?" - tick "Always allow", OK.
goto menu

:pull
call :connect || goto menu
adb -s %DEV% shell cat /sdcard/Android/data/io.github.marginalion.tvjanitor/files/log.txt > %DIR%\app_log.txt
adb -s %DEV% shell cat /sdcard/Android/data/io.github.marginalion.tvjanitor/files/debug.txt > %DIR%\debug.txt 2>nul
adb -s %DEV% shell cat /sdcard/Android/data/io.github.marginalion.tvjanitor/files/report.txt > %DIR%\report.txt 2>nul
adb -s %DEV% shell screencap -p /data/local/tmp/tvj.png
adb -s %DEV% pull /data/local/tmp/tvj.png %DIR%\screen_%RANDOM%.png >nul
adb -s %DEV% shell rm /data/local/tmp/tvj.png
echo  Copied to %DIR%
goto menu

:compare
call :connect || goto menu
adb -s %DEV% shell pm list packages -d > %DIR%\disabled_now.txt
echo --- only BEFORE (left) / only NOW (right): > %DIR%\compare.txt
fc %DIR%\before_disabled.txt %DIR%\disabled_now.txt >> %DIR%\compare.txt
type %DIR%\compare.txt
goto menu

:snap
call :connect || exit /b 1
set S=%DIR%\%1
> %S%.txt (
  echo === when
  echo %date% %time%
  adb -s %DEV% shell uptime
  echo === device
  adb -s %DEV% shell getprop ro.product.manufacturer
  adb -s %DEV% shell getprop ro.product.model
  adb -s %DEV% shell getprop ro.product.device
  adb -s %DEV% shell getprop ro.build.version.release
  adb -s %DEV% shell getprop ro.build.version.sdk
  adb -s %DEV% shell getprop ro.build.fingerprint
  adb -s %DEV% shell getprop ro.adb.secure
  echo === home and screen
  adb -s %DEV% shell cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN
  adb -s %DEV% shell "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'"
  echo === memory
  adb -s %DEV% shell "dumpsys -t 40 meminfo | grep -E 'Total RAM|Used RAM|Free RAM'"
  adb -s %DEV% shell "dumpsys -t 40 meminfo | head -n 30 | tail -n 26"
  echo === settings
  adb -s %DEV% shell settings get global window_animation_scale
  adb -s %DEV% shell settings get global activity_manager_constants
  adb -s %DEV% shell settings get global private_dns_mode
  echo === TV Janitor
  adb -s %DEV% shell "dumpsys package io.github.marginalion.tvjanitor | grep versionName"
  echo === crashes since boot
  adb -s %DEV% shell "logcat -d -b crash | tail -n 20"
)
adb -s %DEV% shell pm list packages -d > %S%_disabled.txt
adb -s %DEV% shell pm list packages -e > %S%_enabled.txt
echo  Saved %S%.txt
exit /b 0

:connect
REM Connect and wait until the TV really answers (right after "adb connect" it is "offline" for a few seconds).
adb connect %TV_IP% >nul 2>&1
for /l %%i in (1,1,15) do (
  adb -s %DEV% get-state 2>nul | findstr /x "device" >nul && exit /b 0
  timeout /t 2 /nobreak >nul
  if %%i==6 (adb disconnect %TV_IP% >nul 2>&1 & adb connect %TV_IP% >nul 2>&1)
  if %%i==11 (adb kill-server >nul 2>&1 & adb connect %TV_IP% >nul 2>&1)
)
echo  The TV does not answer at %TV_IP%. Check that the TV is on, USB debugging is on, and the IP is right.
echo  If the TV shows "Allow USB debugging?", tick "Always allow" and press OK.
exit /b 1
