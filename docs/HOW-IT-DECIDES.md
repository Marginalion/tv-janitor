# How TV Janitor decides

Everything below is what the code does, in plain words, so you can check it before trusting it with your TV.
[По-русски ниже](#как-уборщик-принимает-решения)

## 1. What it never touches

- **Android's core** is never listed and never changed: `android`, System UI, Settings and TV Settings, the
  package installer and permission controller, ext services, module metadata and overlay modules, the content
  providers (except calendar, contacts, user dictionary and blocked numbers), Play Store, Play Services,
  WebView, Bluetooth, the network stack and Wi-Fi.
- **Nothing is uninstalled.** Every change is `pm disable-user --user 0` or "doesn't start on its own"
  (below), and "Restore everything" undoes it.

## 2. Sorting every other app into four groups

For each installed package, the first rule that matches wins:

| Rule | Group |
|---|---|
| Listed in the built-in catalog | as the catalog says (safe / ask / important) |
| A firmware overlay (RRO): changes settings or looks, uses almost no memory | ⚠ Important |
| A TV input (HDMI, tuner), the current keyboard, an enabled accessibility service (remote keys often live there), a persistent service, something named like remote, Bluetooth, inputs or channels, a factory menu, a setup wizard | ⚠ Important |
| Owners of **the same TV** in the CleanYourTV base asked to keep it | ⚠ Important |
| A vendor system package nobody switched off | ⚠ Important, hidden unless "Show hidden system packages" |
| Owners of the same TV switched it off | ✅ Recommended (❓ Your choice if others asked to keep it) |
| A system service with no launcher icon, no description, no statistics, no telling name | ❔ Unknown, hidden unless "Show hidden system packages" |
| Switched off on 10+ TVs and kept on none, or a system package whose name says telemetry or ads | ✅ Recommended |
| Anything else | ❓ Your choice |

A package that has no description, no statistics, no feature and no telling name ends up in **❔ Unknown**.

## 3. "What do you use?"

The answers (voice, aerial TV, HDMI, casting, mirroring, USB media, screensaver, updates, browser, music, smart
home, games, app stores, accessibility) map to known packages and name patterns. **What you use always ends
up working**, even if it had been switched off before TV Janitor. App stores are also found by which apps open
`market://` links.

## 4. The modes

A mode only sets the switches; you see what will change and confirm. No mode ever touches ⚠ Important or
❔ Unknown, and what was already off before TV Janitor stays off unless you said you use it.

| | Gentle | Optimal | Maximum |
|---|---|---|---|
| ✅ Recommended named after telemetry/ads or in the catalog | off | off | off |
| Other ✅ Recommended | — | off | off |
| A feature you don't use: system package / your own app | — | off / doesn't start on its own | off / doesn't start on its own |
| Your own apps | — | don't start on their own | don't start on their own |
| ❓ Your choice | — | — | off; home-screen apps only stop starting on their own |

**Lite** has one button (Optimal) and switches off only **known** packages: in the catalog, described, in the
base, seen on the same TV, or a feature you don't use. No guessing by name.

"**Doesn't start on its own**" (💤) keeps the app installed and openable from Home; it loses
`RUN_ANY_IN_BACKGROUND` / `RUN_IN_BACKGROUND` and is force-stopped after every boot.

## 5. Before applying, it warns you when

- you change something ⚠ Important (with a responsibility notice);
- the new home screen isn't installed: the stock one may depend on what gets switched off;
- no app store would be left.

## 6. Applying

1. The first time, a **snapshot** of what was already off and of the settings it may change.
2. Switch back on what you unticked.
3. Switch off in **batches of five**. Before and after each batch it counts crashes in the crash log and
   "Scheduling restart of crashed service" lines; if a batch adds two or more, the batch is **put back** and
   marked "do not disable on this model".
4. Apply "doesn't start on its own", then the optional settings (animations, cached-app limit, DNS).
5. Write down what Android **really** reports as disabled. This is what counts as "was off" at the next boot.
6. The summary counts the same way: "switched off 28 of 30", with the names of what didn't change.

**New home screen.** It is checked against its SHA-256 and installed. Google's stock launcher outranks it, so
that one is switched off; then the app presses Home (twice if needed). If the new home screen did not open,
the stock launcher is switched back on at once and stays the Home screen.

## 7. After every boot

Some firmware (Yandex TV, Philips' Play-Fi) switches packages back on at boot. So, 15 seconds after boot:

1. It compares your choice with what is really off. Something that **was off and is on again** is noted as
   "the firmware switched it back on"; a choice that simply had not been applied yet is applied, not blamed on
   the firmware.
2. It switches off only what is on now (packages the firmware keeps disabled itself are left alone).
3. It re-applies "doesn't start on its own" and the settings, and brings the new home screen up only if the TV
   is sitting on a boot or stock home screen, **never over live TV or an input**.
4. It writes a short report. The app opens with it only over the Home screen; otherwise the report waits until
   you open the app.

## 8. Restore everything

Switches back on everything that is off now but was not off in the snapshot, wakes the sleeping apps, puts
back the snapshot's settings and the stock home screen. Then it asks Android again: what is still off is
named, with a hint to press the button once more (if it still stays off, the firmware won't allow it). Then
reboot.

## 9. Memory

"Used" is `Used RAM` from `dumpsys meminfo`, waiting up to 40 s on slow TVs; if that fails, the kernel's own
numbers (`MemTotal − MemAvailable`, ignored when nonsense, as on some Xiaomi firmware). Part of "used" is the
kernel and video buffers, which no app can free: compare a TV with itself, before and after.

## 10. Network and data

- The app talks to the TV's own ADB at `127.0.0.1` with its own key, approved once on screen.
- **Full** can update the CleanYourTV base from GitHub (accepted only if at least 80 % of the lists arrive).
  **Lite** never goes online.
- "Share your experience" is **off unless you tick it**. The report (in English, for the shared base) lists the
  model, firmware and system packages; your own apps only if you tick that too. It is saved on the TV;
  nothing is sent anywhere.

---

# Как Уборщик принимает решения

Здесь простыми словами описано, что делает код, чтобы это можно было проверить, прежде чем доверить ему
телевизор.

**1. Что не трогается никогда.** Ядро Android: системный интерфейс, настройки, установщик и разрешения,
служебные модули, хранилища данных (кроме календаря, контактов, словаря и заблокированных номеров), Play
Маркет, сервисы Google Play, WebView, Bluetooth, сеть и Wi-Fi. Ничего не удаляется: только «отключить» или
«не запускается само», и всё возвращается кнопкой «Вернуть всё как было».

**2. Четыре группы.** Для каждого пакета срабатывает первое подходящее правило:
- **⚠ Важное** — оверлей прошивки; входы ТВ; текущая клавиатура; включённые спецвозможности (на них часто
  держатся кнопки пульта); постоянные службы; всё, что похоже на пульт, Bluetooth, входы и каналы; заводское
  меню; мастер настройки; то, что владельцы такого же ТВ просили не трогать;
- **✅ Рекомендуем** — то, что отключали на таком же ТВ; то, что отключали на 10+ ТВ и нигде не просили
  оставить; системные пакеты с названием про телеметрию или рекламу;
- **❔ Неизвестное** — о пакете ничего не известно: нет ни описания, ни статистики, ни функции, ни говорящего
  названия;
- **❓ На ваш выбор** — всё остальное.

**3. «Чем вы пользуетесь».** Отмеченное всегда остаётся рабочим, даже если было выключено до Уборщика.

**4. Режимы** только расставляют переключатели, а перед применением показывают, что изменится. ⚠ Важное и
❔ Неизвестное не трогает ни один режим.
- **Бережно** — телеметрия и реклама из рекомендуемого.
- **Оптимально** — плюс остальное рекомендуемое и то, чем вы не пользуетесь; ваши приложения «не запускаются
  сами».
- **Максимум** — плюс «на ваш выбор»; приложения с главного экрана не отключаются, а только не запускаются
  сами.
- **Lite** — одна кнопка («Оптимально») и только известные пакеты, никаких догадок по названию.

**5. Предупреждения:** при изменении важного; если не стоит новая оболочка; если не останется ни одного
магазина приложений.

**6. Применение:**
1. при первом запуске — снимок «как было»;
2. отключение порциями по пять, с подсчётом сбоев до и после каждой порции;
3. если сбоев стало на два и больше, порция возвращается и помечается «на этой модели не отключать»;
4. в конце записывается, что Android **на самом деле** показывает отключённым, и итог считается так же:
   «отключено 28 из 30» и что именно не поддалось.

**Новая оболочка** проверяется по SHA-256 и ставится. Заводская оболочка Google главнее, поэтому её
приходится выключить; затем Уборщик нажимает «Домой» (если нужно, дважды). Не открылась новая, и заводская
тут же включается обратно и остаётся главным экраном.

**7. После каждой загрузки** (через 15 секунд):
1. сверка с реально отключённым: «прошивка вернула» — только про то, что точно было выключено;
2. отключается только то, что сейчас включено;
3. оболочка выводится только поверх загрузочного или заводского экрана, никогда поверх эфира;
4. отчёт ждёт, пока вы сами откроете приложение, если на экране что-то смотрят.

**8. «Вернуть всё как было»** — включает всё, чего не было в снимке, будит «уснувшие» приложения, возвращает
настройки и заводскую оболочку, а потом перепроверяет: что не вернулось, называется по имени с подсказкой
нажать ещё раз (если не помогает, прошивка не даёт это включить).

**9. Память** — «Used RAM» из `dumpsys meminfo` с ожиданием до 40 секунд, запасной вариант — цифры ядра с
проверкой на бред. Часть занятой памяти — ядро и видеобуферы, их не освободить, поэтому сравнивать стоит ТВ
с самим собой.

**10. Сеть и данные.** Связь только с ADB самого телевизора (127.0.0.1). Full может обновить базу с GitHub,
Lite в интернет не ходит. «Поделиться опытом» выключено, пока вы не поставите галочку; отчёт остаётся на
телевизоре и никуда не отправляется.
