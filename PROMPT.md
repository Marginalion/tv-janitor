# The prompt

Paste everything below the line into an AI coding assistant that can run commands on your computer
(Claude Code, Codex or similar), fill in the first two lines, and answer its questions.
A Russian version is in [PROMPT.ru.md](PROMPT.ru.md).

---

My TV: <brand and model, for example "Xiaomi Mi TV 4A 43">
Its IP address on my Wi-Fi: <for example 192.168.1.50, from the TV's network settings>

Set up TV Janitor (this repository) on my Android TV or Google TV and help me use it. TV Janitor is an
app that runs on the TV itself: after one approval it talks to the TV's own ADB on 127.0.0.1:5555,
disables what I choose with `pm disable-user`, keeps apps I choose from starting on their own, can
install CleanYourTV's clean home screen, and re-applies my choices after every boot. You prepare and
check; the choices are mine and are made in the app on the TV. Work in this order and tell me in one
line what you are doing at each step.

Before anything else
- I have switched on Developer options (OK seven times on Build number) and USB debugging, and Network
  or ADB debugging if the TV has it. My computer and the TV are on the same Wi-Fi. If `adb connect`
  fails, tell me exactly which setting to check; don't guess.
- This is not a webOS (LG), Tizen (Samsung) or VIDAA (most Hisense) TV, and not Fire TV: TV Janitor
  needs Android's `pm` and `cmd package`. If `adb shell getprop ro.build.version.sdk` doesn't answer,
  stop and tell me.
- Check `ro.build.version.sdk` is 26 or higher (the app's minSdk). Below that, stop: the app cannot
  be installed.
- Check `getprop ro.adb.secure`. If it is `0`, any device on my network can get a shell on this TV
  without a prompt. Tell me that plainly before going on. TV Janitor needs network ADB to stay on after
  setup (that is how it works without a computer), so on such a TV I should only continue if my Wi-Fi
  is private.

Back up first
- Save to a folder named after the TV: `pm list packages -f`, `pm list packages -d`, `getprop`,
  `settings list global`, `settings list secure`, and the current Home from `cmd package resolve-activity
  --brief -a android.intent.action.MAIN -c android.intent.category.HOME`.
- Write a restore script (`.sh` and, if I'm on Windows, `.bat`) that re-enables every package that is
  disabled now but was not in the saved `pm list packages -d`, resets the animation scales and
  `activity_manager_constants`, and sets the saved Home again. The app has its own "Restore everything"
  button; this script is the way back if the app itself can't run.
- Save `dumpsys meminfo` (the `Used RAM` and `Total RAM` lines) as the "before" measurement. Some
  firmware prints nonsense for MemAvailable (terabytes); use `dumpsys meminfo`, not /proc/meminfo.

Build and install
- Ask me which edition I want: Full (everything) or Lite (one button, only well-known packages, downloads
  nothing). If I'd rather not build, download the APK of that edition from the repository's Releases page and
  check its SHA-256 against `SHA256SUMS.txt` there; otherwise build:
- Build with `tools/build.sh` (or `tools\build.ps1` on Windows). It clones CleanYourTV at a pinned
  commit, builds its launcher with our patch and packs it into the app. If the Android SDK or a JDK 17+
  is missing, tell me what to install; don't download installers yourself without asking.
- Install with `adb install -r app/build/outputs/apk/full/release/app-full-release.apk` (Lite: `lite/release/app-lite-release.apk`). If the TV answers
  `INSTALL_FAILED_VERIFICATION_FAILURE` or refuses unknown sources, stop and tell me: some
  China-market firmware accepts no sideloaded apps at all.
- Start it: `adb shell am start -n io.github.marginalion.tvjanitor/.MainActivity`. Tell me to watch the TV: it asks
  "Allow USB debugging?" for the app's own key. I tick "Always allow". Without that the app cannot work.

On the TV (I do this; you explain)
- The first screen shows a warning, then "What do you use?" (voice search, aerial TV, HDMI, casting,
  screen mirroring, USB media, photo screensaver, firmware updates, browser, music, smart home, games,
  accessibility) and an opt-in to share my experience. Explain any item I ask about. Don't push me to
  share; it's off unless I tick it.
- Then the simple screen: three tiles (Gentle, Optimal, Maximum; Lite has one button). A tile shows what
  will change, warns if the new home screen isn't installed or if no app store would be left, and applies
  after I confirm, then offers a reboot. "For experts" (Full only) opens the whole list: each row cycles works
  → doesn't start on its own → disabled, then Apply. Rows marked ⚠ are important; changing them asks me to
  confirm. "❔ Unknown" rows are packages nobody knows anything about; modes never touch them. Android's core
  never appears and is never changed.
- The app applies in batches of five and puts a batch back by itself if something starts crashing
  right after it. While it runs, watch `logcat -b crash` and `logcat | grep 'Scheduling restart of
  crashed service'` from the computer too, and tell me if you see a loop the app missed.

Check
- Press Reboot in the app (or `adb reboot`). After boot the app re-applies, notes what the firmware
  switched back on by itself, and opens with a report. Then check from the computer:
  `pm list packages -d` against what I chose, `dumpsys activity activities | grep -E
  'mResumedActivity|topResumedActivity'` (is the Home I expect on screen?), and `dumpsys meminfo`
  for the "after" measurement. Wait two or three minutes after boot first: right after boot the
  numbers jump by a hundred MB.
- Ask me to try what I use: the remote's Home, Back and volume keys, inputs/HDMI, live TV, voice,
  casting or mirroring, and my streaming apps. If something I use broke, find which change did it
  (the app's log and the row hints help), undo only that, and tell me.
- If I installed the new home screen: Home must open it. On Google TV the setup wizard
  (`com.google.android.tungsten.setupwraith`) can take Home back; before anyone disables it, check
  `dumpsys wifi | grep -o 'cname=[^ ]*'`. If a Wi-Fi network belongs to it, Android deletes that network
  when it is disabled, and the TV drops off Wi-Fi and ADB.

Rules the app follows, and you follow too
- Never uninstall system packages; disable with `pm disable-user --user 0`, one `pm` command per
  shell call.
- Never disable Android's core: `android`, `com.android.systemui`, `com.android.shell`,
  `com.android.settings`, `com.android.tv.settings`, the package installer, the permission controller,
  ext services and ext shared, module metadata, `com.google.android.overlay.modules*`,
  `com.android.providers.*` (calendar, contacts, user dictionary and blocked numbers aside), Play Store,
  Play Services, WebView, Bluetooth.
- Don't open any app whose name says factory, fac, AT or factory menu, not even to look: on some boards
  that flips a factory flag the shell cannot undo. `dumpsys package <name>` tells you what it is.
- Ask before touching voice, casting, mirroring, the update service or streaming apps.
- Some firmware switches packages back on at every boot. That is fine: the app re-applies after boot.
  Note it in the report instead of fighting it.

Finish
- Give me: the before/after memory, what was changed, the restore script, and how to undo everything
  from the app ("Restore everything", then reboot).
- If I opted in to sharing, show me the app's report (`/sdcard/Android/data/io.github.marginalion.tvjanitor/files/report.txt`)
  and ask whether I want to send it to CleanYourTV as `examples/<brand>-<model>/debloat.txt` so owners of
  the same TV benefit. Send nothing without my yes.
- Remind me that network ADB stays on because TV Janitor needs it, what that means on my network, and
  how to remove the app's access: Developer options → Revoke USB debugging authorisations.
