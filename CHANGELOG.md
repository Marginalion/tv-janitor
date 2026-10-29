# Changelog

## Unreleased

- **A release closes the TV by default.** On first connection, a release build no longer asks: it checks that
  debugging comes back on this TV and from then on opens it only while it works (about a minute after boot).
  If the check fails, debugging stays on and the log says so; "Keep it on" in the same window, or the "Keep
  network debugging on" box, leaves it open for people who connect from a computer. A test build (…-dev…,
  …-test…) keeps it open by default, so the testers' kit reaches the TV, and says so with a warning.

## 0.9.3-beta (2026-10-28)

- **Ticks survive the other buttons.** Ticking packages and then pressing the keyboard, launcher or refresh
  button no longer resets the list: the rebuild carries over what was ticked but not applied yet. Apply and
  Restore still start from the TV as it is, and a tick goes if its package changed meanwhile (rolled back,
  switched on from outside).
- **"Restart" checks that the TV really restarted, and never fires a second way on a timer.** Some firmware
  (Novex) accepts `svc power reboot` and does nothing; on Sunwind plain `reboot` powers the TV off. TV Janitor
  uses one way; if the TV is still running 20 s later it says so and offers "Try the other way". The way that
  restarted this TV is remembered (by the boot number) and used next time. The after-restart job is cancelled
  then, and also does nothing if it ever runs on the same boot it was asked on. An ADB error during the command
  is no longer taken for success.
- **Was there a restart? By Android's boot counter, not the clock** (the clock jumps: NTP, a hand-set time).
  Unknown counts as a restart, the side where choices are kept.
- **A package switched on from outside stays on.** Without a restart since the last run, a package that came
  back on was put back by something other than TV Janitor (a person with adb, Settings, another app): its tick
  comes off instead of it being switched off again.
- **No false crash loops at boot.** "Crashed again and again" now needs 10 crashes, one crash logged twice (its
  "Process:" line and Android's restart line) counts once, and on the same boot as the last run only crashes
  after that run count. The Yandex keyboard crashes three times at every boot on Novex, on or off.
- **"Restore everything" is the snapshot, exactly.** What was disabled before TV Janitor stays disabled. Then,
  separately, if Yandex packages were disabled before TV Janitor (probably another cleaner: Novex Cleaner had
  the Yandex home and the HDMI player off), the app lists them and asks whether to switch them on too.
- **New launcher: the stock Home comes back whatever happens.** A marker is written to disk before the stock
  Home is switched off; an error before the check puts it back at once, a power cut at the next run. The stock
  Home counts as off only if Android says so, and "our launcher is on screen" means its exact component.
- The launcher restart after writing its config is no longer logged as "💤 doesn't start by itself".
- **Plain-language descriptions for 344 packages** met on Novex, Sunwind, Philips and Xiaomi: what each does and
  what happens if it is disabled, each readable on its own. Russian in describe.txt, English in describe.en.txt
  for every other TV language.

- **Debugging open only while TV Janitor works.** Over its own ADB the app grants itself
  WRITE_SECURE_SETTINGS once; with it, it switches Android's debugging on before it connects and off when it is
  done: after boot the port is open for about a minute, not all the time. It closes debugging only after a test
  on that TV has shown it comes back by itself (off, on, reconnect), and never if "Keep network debugging on" is
  ticked. Asked once, with the owner watching. "Restore everything" puts debugging back to always on.
- Firmware that switches Developer options off at every boot (so debugging stays off): after boot the app
  switches them on itself (with the same permission, no ADB needed for that), applies the choices, and switches
  them off again if it was the one that switched them on.
- Firmware that keeps the boot signal from third-party apps (Sunwind on CVTE/MediaTek with Yandex TV): a
  persisted job Android restores by itself checks every 15 minutes whether this boot was handled, and if not,
  re-applies the choices; opening the app does it at once. The app notices this (Android's boot counter) and says
  so on screen and in the boot report, instead of failing silently.
- Protected packages: when Android refuses `disable-user` ("protected package"), the app says so (🔒), doesn't
  retry after every boot and doesn't count it as a failure. Crash-loop rollback now blames only what the batch
  really switched off.
- Crash-loop check allows for TVs that crash on their own: before the first batch it measures the same 8-second
  window with nothing changed, and a batch is put back only for clearly more than that (Sunwind falsely blamed
  Alice, the setup wizard and factory apps). Protected packages never appear as "do not disable".
- A batch that starts crashes no longer takes its innocent neighbours down: after putting it back, the app switches
  its packages off again one at a time, and only the one that brings the crashes back stays on and goes into
  "do not disable" (Sunwind: Alice, not the setup wizard and factory apps). Older blame that was never confirmed
  this way is dropped from the report, as is anything that is off on the TV now.
- Yandex TV's stock Home (about 180 MB with its Metrica processes) can be switched off safely: only once the new
  home screen is in place and the firmware has its FallbackHome; a marker is written to storage first, then Home
  must open the new home screen and still show it 3 seconds later (the exact component, not a name somewhere in
  the line). Otherwise, or if anything fails or the power goes halfway, the stock Home is switched back on and
  checked, at once or at the next start. It is never switched off in a crash-check batch.
- Packages the firmware won't let anyone disable (protected) are frozen instead (`pm suspend`) when the firmware
  allows that; otherwise they are kept out of the background (appops, the strictest standby bucket, force-stop),
  renewed after every boot. A process the firmware keeps alive whatever is done (Sunwind: the Yandex updater) is
  named in the log and in the report as needing root. Unticking it or "Restore everything" undoes all of this.
- **Light keyboard** (full edition): "⌨ Light keyboard" installs LeanKey, a keyboard made for the remote (MIT,
  built from source at a pinned commit, no internet permission), makes it the default and checks that Android
  did. The previous keyboard can then be switched off in the list; the same button, and "Restore everything",
  put the previous one back.
- The bundled home screen and keyboard are signed with the release key, so a build from any machine installs
  over the one on the TV. A home screen from an older build signed with a debug key is replaced (its own
  settings reset) instead of failing to install.
- Keyboards: only the one in use is locked. Yandex Keyboard, Gboard and others can be ticked in the expert list
  once another keyboard is the default.
- After boot, packages the firmware protects are no longer reported as "choices not applied yet".
- The experience report no longer lists protected packages as disabled: the owner chose them, but the firmware
  keeps them on.
- Experience report: a "Firmware quirks" section (boot signal blocked, protected packages, ro.adb.secure=0).
- Notices firmware where "restart" powers the TV off (Sunwind): if the TV came up minutes after the restart it
  asked for, the restart dialog says to switch it on with the remote, and the report lists it as a quirk.
- **Restart** uses `svc power reboot` (Android's own restart) instead of plain `reboot`, which powers some TVs
  off (Sunwind); plain `reboot` only if svc says it can't.
- Faster start after a restart the app asked for, on firmware without the boot signal: just before restarting it
  leaves a one-off job that Android restores after boot and runs once the network is up (about a minute), instead
  of waiting up to 15 minutes for the periodic one (Sunwind showed the Yandex home screen for 12 minutes).
- The app always opens on the simple screen; "For experts" lasts until it is closed.
- Simple screen: **Restart** (with a confirmation) and **Restore everything** buttons.
- **Crash check judges what fell, not how many.** It reads which process crashed (app crashes, native ones and
  services Android restarts) since the batch started. A package dying because it is being switched off is not a
  crash: on Sunwind, Alice's own two services were counted as two crashes and she was wrongly kept on. A batch is
  put back only when something vital falls: Android, the system UI, the home screen and keyboard in use, the TV's
  own services (MediaTek, CVTE) and the apps behind what the owner said they use. Anything else is written to
  the log and the report and the clean-up goes on.
- Protected packages: a process that gets a new number after force-stop was started again by the firmware, not
  stopped (♻), and is named as such; "💤" is said only when it stays gone.
- When switching the stock Home off is undone, the log says what was on screen instead. Any screen of our launcher
  counts as success, and only the stock Home or the empty FallbackHome coming back counts as failure.
- Packages switched back on between two runs without a reboot are named in the log.
- The light keyboard's settings tile is hidden from the home screen (through the launcher's own settings).
- **Yandex TV: HDMI without the Yandex home screen.** The launcher gets a config.json (only where there is none,
  or ours): inputs open with live-tv:// in Yandex's TV player, which otherwise sends them to the Yandex home
  screen and crashes once that is off.
- Persistent packages (Android starts them at every boot, even disabled, and then they crash over and over:
  Sunwind's platform services) are never disabled, only held back; one disabled earlier is switched back on.
- After boot, anything disabled that crashes again and again since boot is switched back on and left alone.
- CVTE's tvapi-service is never touched: Android asks it about every remote key, and without it only the power
  button works after a reboot (Sunwind).
- **The list folds into groups by purpose** (tracking and ads, voice, remote, HDMI and inputs, video, stores and
  updates, other apps and features, system services, unknown), or by risk with one button. Groups start closed
  and show how many rows are set to be switched off; a closed group builds no rows, which keeps the app light.
  Each row carries its risk: 🔵 safe, 🔶 your choice, 🔴 important, ⚪ unknown.
- **"🗑 Uninstall TV Janitor cleanly"**: uninstalling the app alone leaves everything it disabled disabled (Android
  keeps that). This button puts everything back first, checks that another home screen of any make answers Home,
  and only then removes the launcher, the light keyboard and the app; if anything didn't come back, it removes
  nothing.
- What the owner said they use is no longer switched off behind their back: a ticked one is flagged in the list
  and in the log, and one switched back on from outside stays on after the next boot.
- Dev builds keep a second, detailed log (debug.txt): every crash line with its verdict, memory, the biggest
  processes and the screen before and after changes. The test kit's option 4 pulls it too.

## 0.9.2-test.2 (2026-10-25)

- The experience report is always in English (app names and features), whatever the TV's language: it is
  meant for the shared CleanYourTV base.
- After boot, packages the firmware itself keeps disabled are no longer "disabled" again (that only threw an
  error, e.g. org.droidtv.freeviewplayers on Philips).
- The hint about the developers' test script appears only in debug builds.
- docs/HOW-IT-DECIDES.md: every rule the app follows, in English and Russian.
- New home screen: Google's stock launcher is kept switched off only if Home really opens the new one (checked
  twice); otherwise it is switched back on at once and stays the Home screen. Before, it could be left off
  even when the check failed.
- Honest summaries: "Apply" counts what Android reports as disabled and enabled ("28 of 30") and names what
  didn't change; "Restore everything" checks afterwards and names what didn't come back, instead of counting
  attempts.
- README: "My TV. My rules." and how it came about (an outside review by ChatGPT).

## 0.9.1-beta (2026-10-25)

- Fix: Apply could be lost when the new home screen was installed just before it (the app closed itself
  while a job was still queued). Jobs are now counted and the app waits for the last one.
- Fix: the boot report no longer says "the firmware switched N back on" for choices that had simply not been
  applied yet; the experience report no longer lists them as re-enabled by the firmware.
- Polite after boot: the report opens only over the Home screen, never over live TV or an input; otherwise it
  waits until the app is opened. The new home screen is no longer pushed over live TV either.
- Memory: longer wait for slow TVs (Philips), a fallback to the kernel's numbers, and an honest message when
  the TV is busy instead of "no ADB connection".
- App store installer (Aurora Store, F-Droid) reworked: new Aurora release layout, GitLab upload paths, download
  progress, store permissions granted over ADB (TV Settings lack those screens), open from the app. Hidden for
  now until it has more road testing.
- The log names the real reason a command failed and which packages the firmware switched back on.
- "What I use" now wins over a package that was already off before TV Janitor (for example Chromecast
  disabled by hand earlier): the modes switch it back on.

## 0.9-beta (2026-10-25)

First public beta.

- Interface in the TV's language: English, Russian, Ukrainian, German, French, Spanish, Portuguese, Turkish,
  Chinese, Japanese, Vietnamese; English for anything else.

- Two editions from one source: **Lite** (one button, only well-known packages, no downloads) and **Full**.
- Simple screen with three modes (Gentle, Optimal, Maximum): shows what will change, applies after
  confirmation, offers a reboot. "For experts" opens the full list (Full only).
- Warnings before applying: new home screen not installed; no app store would be left.
- "❔ Unknown" group: packages without a description or statistics; modes never touch them.
- Firmware overlays (RRO) are recognised as important.
- "App store" answer in "What do you use?"; stores are found by who opens `market://` links.
- App store installer with pinned signing certificates (Full; hidden from 0.9.1 until it is road-tested).
- Apply in batches of five with a progress bar and automatic rollback on crash loops.
- Re-applies choices after every boot and reports what the firmware switched back on.
- New home screen from CleanYourTV, checked by SHA-256.
- CleanYourTV base of ~300 TVs, updatable from GitHub (Full).
- Opt-in "Share your experience" report in CleanYourTV's format.
- Package id `io.github.marginalion.tvjanitor`; release builds signed with the project key.
