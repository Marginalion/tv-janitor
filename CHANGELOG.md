# Changelog

## Unreleased

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
- Experience report: a "Firmware quirks" section (boot signal blocked, protected packages, ro.adb.secure=0).

## 0.9.2-beta (unreleased)

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
