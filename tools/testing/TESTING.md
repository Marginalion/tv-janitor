# Testing TV Janitor

Thank you for testing! A **beta** (`…-beta`) has been run on a few TVs; a **test build** (`…-test.N`) has
changes nobody has run on a real TV yet. Either can misbehave on your TV; everything it does can be undone
with **Restore everything**. [По-русски ниже](#как-тестировать)

## What you need

- An Android TV or Google TV (Android 8 or newer). Not webOS, Tizen, VIDAA or Fire TV.
- A computer on the same Wi-Fi, with **adb** from Android
  [platform-tools](https://developer.android.com/tools/releases/platform-tools).
- From the release: `TVJanitor-…-full.apk`, `TVJanitor-…-lite.apk` and this kit (`tvj_test.bat` for Windows,
  `tvj_test.sh` for macOS and Linux; `tvj_deep.bat` is an optional, much bigger read-only snapshot for
  Windows). Put them all in one folder with adb, and keep only one version of the APKs there.

On the TV: Settings → About → press OK on **Build** seven times → Developer options → **USB debugging** on
(and **Network debugging** if there is one). Note the TV's IP address (Settings → Network).

## Steps

Run `tvj_test.bat` (or `./tvj_test.sh`), give the TV a short name and its IP, then:

| # | Step | Kit option |
|---|---|---|
| 1 | Snapshot **before**, on the TV as it is now | **1** |
| 2 | Install Full (or Lite) and open it; on the TV tick "Always allow" | **2** (Lite: **7**) |
| 3 | On the TV: answer "What do you use?", choose a mode, check what it says will change, apply | — |
| 4 | In the app: **Reboot**. If the TV doesn't restart, the app offers **Try the other way**: note whether it restarted or switched off. Wait 2–3 minutes after the TV is up | — |
| 5 | Snapshot **after** | **3** |
| 6 | Check what you use: remote (Home, Back, volume), HDMI, live TV, voice, casting, your apps | — |
| 7 | Log, report and a screenshot | **4** |
| 8 | In the app: **Restore everything** (if it asks about packages disabled before TV Janitor, note what it lists), reboot, then compare with the snapshot before | **5** |
| 9 | Pack everything into one zip | **8** |

Then open an issue: **[My TV](https://github.com/Marginalion/tv-janitor/issues/new?template=1-tv-report.yml)** and attach the zip, or
**[Something went wrong](https://github.com/Marginalion/tv-janitor/issues/new?template=2-bug.yml)**. The zip holds the model, firmware, the
package lists, memory numbers, the app's log and a screenshot of the TV; look through it before you attach it.

**The kit can't reach the TV?** If you unticked "Keep network debugging on", TV Janitor switches debugging off
when it is done. Open the app (debugging comes back while it runs) or tick the box again for the test.

For a full picture, run `tvj_deep.bat` with the same TV name and the label `before` at step 1 and `after` at
step 5 (2–4 minutes each, nothing on the TV changes). Option **8** packs them into the zip too.

## What to look at

The release notes say what changed. Always worth a look:

- Does the boot report make sense ("the firmware switched N back on" only for what really came back)?
- Did anything you said you use stop working?
- Anything that looked stuck, cut off, or in the wrong language?
- **Reboot** from the app: did the TV restart the first time? If not, did **Try the other way** work, and
  the next time did it restart at once?
- The descriptions in "For experts": anything wrong for your TV?

---

# Как тестировать

Спасибо, что помогаете! **Бета** (`…-beta`) уже проверена на нескольких телевизорах, а **тестовая сборка**
(`…-test.N`) содержит изменения, которые ещё никто не запускал на живом телевизоре. На вашем ТВ что-то может
пойти не так, но всё отменяется кнопкой **«Вернуть всё как было»**.

**Что нужно.** Телевизор на Android TV или Google TV (Android 8+). Компьютер в той же сети Wi-Fi и **adb** из
Android [platform-tools](https://developer.android.com/tools/releases/platform-tools). Из выпуска скачайте
`TVJanitor-…-full.apk`, `TVJanitor-…-lite.apk` и этот набор (`tvj_test.bat` для Windows, `tvj_test.sh` для
macOS и Linux; `tvj_deep.bat` — необязательный, гораздо более подробный снимок только для чтения, для Windows).
Сложите всё в одну папку вместе с adb; APK держите только одной версии.

На телевизоре: Настройки → Об устройстве → 7 раз OK на «Сборка» → Для разработчиков → **Отладка по USB**
(и **по сети**, если есть). Запомните IP телевизора (Настройки → Сеть).

**Шаги.** Запустите `tvj_test.bat`, введите короткое имя ТВ и его IP:
1. **1** — снимок «до», пока телевизор нетронутый.
2. **2** — поставить Full (Lite — **7**). На ТВ отметьте «Всегда разрешать».
3. На ТВ: ответьте «Чем вы пользуетесь?», выберите режим, посмотрите, что изменится, примените.
4. В приложении «Перезагрузить». Если ТВ не перезагрузился, Уборщик предложит «Попробовать другой способ»:
   запишите, перезагрузился телевизор или выключился. Подождите 2–3 минуты после включения.
5. **3** — снимок «после».
6. Проверьте то, чем пользуетесь: пульт, HDMI, эфир, голос, трансляции, свои приложения.
7. **4** — журнал, отчёт и снимок экрана.
8. В приложении «Вернуть всё как было» (если спросит про отключённое до Уборщика, запишите список),
   перезагрузка, затем **5** — сравнение со снимком «до».
9. **8** — всё в один zip.

Потом откройте issue [«Мой телевизор»](https://github.com/Marginalion/tv-janitor/issues/new?template=1-tv-report.yml) и приложите zip, или
[«Что-то пошло не так»](https://github.com/Marginalion/tv-janitor/issues/new?template=2-bug.yml). В zip лежат модель, прошивка, списки
пакетов, цифры памяти, журнал приложения и снимок экрана: посмотрите его, прежде чем прикладывать.

**Набор не достучался до ТВ?** Если вы сняли галочку «Держать отладку по сети включённой», Уборщик выключает
отладку, когда закончит. Откройте приложение (на время работы отладка включится) или на время теста верните
галочку.

**Подробный снимок:** `tvj_deep.bat` с тем же именем ТВ и меткой `before` на шаге 1 и `after` на шаге 5
(2–4 минуты каждый, на телевизоре ничего не меняется). Пункт **8** сам положит их в zip.
