# Changelog

## 0.9.1-beta (unreleased)

- Fix: Apply could be lost when the new home screen was installed just before it (the app closed itself
  while a job was still queued). Jobs are now counted and the app waits for the last one.
- Fix: the boot report no longer says "the firmware switched N back on" for choices that had simply not been
  applied yet; the experience report no longer lists them as re-enabled by the firmware.
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
- Install Aurora Store or F-Droid from their developers, with a pinned signing certificate (Full).
- Apply in batches of five with a progress bar and automatic rollback on crash loops.
- Re-applies choices after every boot and reports what the firmware switched back on.
- New home screen from CleanYourTV, checked by SHA-256.
- CleanYourTV base of ~300 TVs, updatable from GitHub (Full).
- Opt-in "Share your experience" report in CleanYourTV's format.
- Package id `io.github.marginalion.tvjanitor`; release builds signed with the project key.
