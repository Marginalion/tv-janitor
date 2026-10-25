package io.github.marginalion.tvjanitor;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int BG = Color.parseColor("#101714"), ACCENT = Color.parseColor("#F2C14E"),
            TEXT = Color.parseColor("#F1F5F2"), DIM = Color.parseColor("#A9BAB1"), WARN = Color.parseColor("#FF8A65");
    private static final String SAFE = Catalog.SAFE, ASK = Catalog.ASK, IMPORTANT = "important", UNKNOWN = "unknown";

    private static final class Row {
        String pkg, label, note, level, sub; boolean enabled, tele, userApp, factoryOff; long kb; int state; Button box;
        Features.F feature; boolean needed, known;
    }

    private Store store;
    private TextView status, memory, warning, progText;
    private android.widget.ProgressBar progBar;
    private LinearLayout list;
    private Button launcherBtn, applyBtn;
    private final List<Row> rows = new ArrayList<>();
    private Map<String, Long> mem = new HashMap<>();
    private CheckBox anim, cache, killbg, dns, expert;
    private volatile boolean busy;
    private boolean openingLog;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        if (!store.has("features")) { startActivity(new Intent(this, FeaturesActivity.class)); finish(); return; }
        // Answers given before the "app store" question existed: keep stores, the safe choice.
        if (!store.flag("askedStores", false)) {
            Set<String> f = new HashSet<>(store.set("features")); f.add("stores");
            store.setSet("features", f); store.setFlag("askedStores", true);
        }
        // Some firmware (Yandex TV on HiSilicon) draws the wallpaper through a default window: make it opaque.
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(64, 40, 64, 40);
        sv.addView(root);

        root.addView(text(T.t("Уборщик"), 32, ACCENT));
        memory = text(T.t("Подключаюсь к телевизору… Если появится «Разрешить отладку?», отметьте «Всегда разрешать»."), 16, DIM);
        root.addView(memory);
        warning = text("", 16, WARN);
        warning.setVisibility(android.view.View.GONE);
        root.addView(warning);
        if (Edition.LITE) { store.setFlag("expertUi", false); store.setFlag("expert", false); }
        boolean simpleUi = !store.flag("expertUi", false);
        if (getIntent().getBooleanExtra("fromFeatures", false))
            root.addView(text(simpleUi ? T.t("✔ Ответы сохранены. Выберите, насколько чистить — обычно хватает «Оптимально».")
                    : T.t("✔ Ответы сохранены. Нажмите «👍 Оптимально», посмотрите список и «Применить»."), 17, ACCENT));
        if (getIntent().getBooleanExtra("report", false) && store.str("bootReport") != null)
            root.addView(text("✔ " + store.str("bootReport"), 17, ACCENT));

        // Shared: status and progress.
        status = text("", 19, TEXT);
        status.setVisibility(android.view.View.GONE);
        root.addView(status);
        progBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progBar.setMax(100); progBar.setVisibility(android.view.View.GONE);
        progBar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        progBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#3A4A42")));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, 36); plp.setMargins(6, 8, 6, 4);
        root.addView(progBar, plp);
        progText = text("", 17, DIM); progText.setVisibility(android.view.View.GONE);
        root.addView(progText);

        // Simple screen: three tiles and a few buttons.
        LinearLayout simple = new LinearLayout(this);
        simple.setOrientation(LinearLayout.VERTICAL);
        LinearLayout tiles = row();
        Button tSafe = tile(T.t("🛡  Бережно"), T.t("реклама и слежка")),
                tRec = tile(T.t("👍  Оптимально"), T.t("плюс то, чем вы не пользуетесь")),
                tMax = tile(T.t("🔥  Максимум"), T.t("всё, что можно отключить без вреда"));
        Button tOne = tile(T.t("🧹  Оптимизировать телевизор"), T.t("отключит только то, про что точно известно, что оно лишнее"));
        if (Edition.LITE) addTile(tiles, tOne);
        else { addTile(tiles, tSafe); addTile(tiles, tRec); addTile(tiles, tMax); }
        simple.addView(tiles);
        simple.addView(text(T.t("То, чем вы пользуетесь, не тронем. Перед применением покажем, что изменится."), 15, DIM));
        LinearLayout sb = row();
        simpleLauncher = button(store.flag("launcher", false) ? T.t("Вернуть старую оболочку") : T.t("Новая оболочка"));
        Button sFeat = button(T.t("Чем я пользуюсь")), sShare = button(T.t("Поделиться опытом"));
        add(sb, simpleLauncher); add(sb, sFeat); add(sb, sShare);
        simple.addView(sb);
        Button toExpert = button(T.t("Для опытных  ›")), sStore = button(T.t("📦 Поставить магазин"));
        Button sRestore = button(T.t("Вернуть всё как было"));
        LinearLayout sb2 = row();
        if (Edition.LITE) add(sb2, sRestore); else { add(sb2, sStore); add(sb2, toExpert); }
        simple.addView(sb2);
        root.addView(simple);

        LinearLayout full = new LinearLayout(this);
        full.setOrientation(LinearLayout.VERTICAL);
        root.addView(full);
        Button toSimple = button(T.t("‹  Простой режим")), eStore = button(T.t("📦 Поставить магазин"));
        LinearLayout bar0 = row(); add(bar0, toSimple); add(bar0, eStore); full.addView(bar0);
        LinearLayout root0 = root; root = full;

        LinearLayout bar = row();
        applyBtn = button(T.t("Применить"));
        Button rebootBtn = button(T.t("Перезагрузить"));
        launcherBtn = button(store.flag("launcher", false) ? T.t("Вернуть старую оболочку") : T.t("Новая оболочка"));
        Button restoreBtn = button(T.t("Вернуть всё как было"));
        add(bar, applyBtn); add(bar, rebootBtn); add(bar, launcherBtn);
        root.addView(bar);
        LinearLayout bar2 = row();
        LinearLayout bar3 = row();
        Button refresh = button(T.t("Обновить список"));
        Button logBtn = button(T.t("Журнал"));
        Button featBtn = button(T.t("Чем я пользуюсь"));
        Button dbBtn = button(T.t("Обновить базу"));
        Button shareBtn = button(T.t("Поделиться опытом"));
        add(bar2, restoreBtn); add(bar2, featBtn); add(bar2, refresh);
        add(bar3, dbBtn); add(bar3, shareBtn); add(bar3, logBtn);
        root.addView(bar2); root.addView(bar3);

        root.addView(header(T.t("Режимы")));
        LinearLayout modes = row();
        Button mSafe = button(T.t("🛡 Бережно")), mRec = button(T.t("👍 Оптимально")), mMax = button(T.t("🔥 Максимум"));
        modes.addView(mSafe); modes.addView(mRec); modes.addView(mMax);
        root.addView(modes);
        root.addView(text(T.t("Режим только расставляет переключатели — посмотрите список и нажмите «Применить». ")
                + T.t("Бережно: телеметрия, демо и реклама. Оптимально: плюс то, чем вы не пользуетесь, и то, что отключали на таком же ТВ; сторонние приложения не запускаются сами. ")
                + T.t("То, чем вы пользуетесь, не трогает ни один режим. ")
                + T.t("Максимум: всё, что не защищено; приложения с главного экрана не отключаются, а только не запускаются сами."), 14, DIM));

        root.addView(header(T.t("Настройки")));
        anim = check(T.t("Ускорить анимации меню"), T.t("в 2 раза"), store.flag("anim", true));
        cache = check(T.t("Ограничить фоновые приложения"), T.t("держать в памяти не больше 8 закрытых приложений"), store.flag("cache", false));
        killbg = check(T.t("Чистить фон после включения"), T.t("закрывает всё фоновое через 15 секунд после загрузки"), store.flag("killbg", false));
        dns = check(T.t("Блокировать рекламу через DNS"), T.t("AdGuard DNS для всего телевизора. Рекламу в YouTube не убирает. Если роутер уже фильтрует (Pi-hole, AdGuard Home, VPN) — не включайте"), store.flag("dns", false));
        expert = check(T.t("Показать скрытые системные пакеты"), T.t("служебные пакеты производителя, о которых ничего не известно. Ядро Android не показывается и не меняется никогда"), store.flag("expert", false));
        root.addView(anim); root.addView(cache); root.addView(killbg);
        if (android.os.Build.VERSION.SDK_INT >= 28) root.addView(dns);
        root.addView(expert);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        root = root0;
        setContentView(sv);
        simple.setVisibility(simpleUi ? android.view.View.VISIBLE : android.view.View.GONE);
        full.setVisibility(simpleUi ? android.view.View.GONE : android.view.View.VISIBLE);
        toExpert.setOnClickListener(v -> { store.setFlag("expertUi", true); recreate(); });
        toSimple.setOnClickListener(v -> { store.setFlag("expertUi", false); recreate(); });
        tSafe.setOnClickListener(v -> quick(0));
        tRec.setOnClickListener(v -> quick(1));
        tMax.setOnClickListener(v -> quick(2));
        tOne.setOnClickListener(v -> quick(1));
        sRestore.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle(T.t("Вернуть всё как было?"))
                .setMessage(T.t("Уборщик включит обратно всё, что отключал, и вернёт заводские настройки, которые менял. Потом лучше перезагрузить телевизор."))
                .setNegativeButton(T.t("Отмена"), null)
                .setPositiveButton(T.t("Вернуть"), (d, w) -> run(T.t("Возвращаю всё как было…"), () -> new Ops(this, this::say).restoreAll()))
                .show());
        simpleLauncher.setOnClickListener(v -> toggleLauncher());
        sStore.setOnClickListener(v -> chooseStore());
        eStore.setOnClickListener(v -> chooseStore());
        sFeat.setOnClickListener(v -> { openingLog = true; startActivity(new Intent(this, FeaturesActivity.class)); finish(); });
        sShare.setOnClickListener(v -> { openingLog = true; startActivity(new Intent(this, ReportActivity.class)); });

        applyBtn.setOnClickListener(v -> apply());
        rebootBtn.setOnClickListener(v -> { saveFlags(); run(T.t("Перезагружаю…"), () -> new Ops(this, this::say).reboot()); });
        launcherBtn.setOnClickListener(v -> toggleLauncher());
        restoreBtn.setOnClickListener(v -> run(T.t("Возвращаю всё как было…"), () -> new Ops(this, this::say).restoreAll()));
        refresh.setOnClickListener(v -> scan());
        dbBtn.setOnClickListener(v -> {
            show(status, T.t("Обновляю базу CleanYourTV…"));
            busy = true;
            new Thread(() -> {
                String r = Updater.update(this, this::say);
                busy = false;
                runOnUiThread(() -> { show(status, r); buildList(); });
            }).start();
        });
        shareBtn.setOnClickListener(v -> { openingLog = true; startActivity(new Intent(this, ReportActivity.class)); });
        featBtn.setOnClickListener(v -> { openingLog = true; startActivity(new Intent(this, FeaturesActivity.class)); finish(); });
        logBtn.setOnClickListener(v -> { openingLog = true; startActivity(new Intent(this, LogActivity.class)); });
        mSafe.setOnClickListener(v -> preset(0));
        mRec.setOnClickListener(v -> preset(1));
        mMax.setOnClickListener(v -> preset(2));
        expert.setOnCheckedChangeListener((v, on) -> { store.setFlag("expert", on); buildList(); });
        buildList();
        (Edition.LITE ? tOne : simpleUi ? tRec : applyBtn).requestFocus();
        scan();
    }

    private Button simpleLauncher;
    private Set<String> stores = new HashSet<>();

    /** Warning when the planned states leave no app store working, or null. */
    private String noStoreWarn() {
        if (stores.isEmpty()) return null;
        PackageManager pm = getPackageManager();
        for (String p : stores) {
            Row r = findRow(p);
            if (r != null) { if (r.state != 2) return null; continue; }
            try {   // not in the list (Google Play, hidden): counts if it is on
                int st = pm.getApplicationEnabledSetting(p);
                if (pm.getApplicationInfo(p, 0).enabled && st != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        && st != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) return null;
            } catch (Exception ignored) {}
        }
        return T.t("📦 Не останется ни одного магазина приложений: устанавливать и обновлять приложения будет нечем. ")
                + T.t("Если нужно, оставьте один магазин включённым или поставьте другой: Aurora Store (каталог Google Play без аккаунта), ")
                + T.t("F-Droid (приложения с открытым кодом) или магазин вашего региона.");
    }
    private boolean simpleRun;

    /** One press: set the mode, show what changes, apply, then offer a reboot. */
    private void quick(int mode) {
        if (busy) return;
        preset(mode);
        int off = 0, sleep = 0; long kb = 0;
        Set<String> slept = store.sleeping();
        for (Row r : rows) {
            int now = !r.enabled ? 2 : slept.contains(r.pkg) ? 1 : 0;
            if (r.state == now) continue;
            if (r.state == 2) { off++; kb += r.kb; } else if (r.state == 1) { sleep++; kb += r.kb; }
        }
        String[] names = {T.t("🛡 Бережно"), Edition.LITE ? T.t("🧹 Оптимизация") : T.t("👍 Оптимально"), T.t("🔥 Максимум")};
        if (off + sleep == 0) { show(status, names[mode] + T.t(": менять нечего, всё уже так ✔")); return; }
        String msg = (off > 0 ? T.t("Отключим: ") + off + "\n" : "")
                + (sleep > 0 ? T.t("Не будут запускаться сами: ") + sleep + T.t(" (открываются как обычно)\n") : "")
                + (kb > 0 ? T.t("Сейчас они занимают около ") + kb / 1024 + T.t(" МБ\n") : "")
                + (Edition.LITE ? T.t("\nОтключаем только то, про что точно известно, что оно лишнее. То, чем вы пользуетесь, не тронем. Вернуть всё можно кнопкой «Вернуть всё как было».")
                        : T.t("\nТо, чем вы пользуетесь, не тронем. Список можно посмотреть в «Для опытных», а вернуть всё — там же, кнопкой «Вернуть всё как было»."));
        boolean noLauncher = !store.flag("launcher", false);
        android.app.AlertDialog.Builder db = new android.app.AlertDialog.Builder(this)
                .setTitle(names[mode])
                .setMessage((noLauncher ? NO_LAUNCHER_WARN + "\n\n" : "") + (noStoreWarn() != null ? noStoreWarn() + "\n\n" : "") + msg)
                .setNegativeButton(T.t("Отмена"), (d, w) -> buildList())
                .setPositiveButton(noLauncher ? T.t("Всё равно применить") : T.t("Применить"), (d, w) -> { simpleRun = true; doApply(); });
        if (noLauncher) db.setNeutralButton(T.t("Сначала оболочку"), (d, w) -> { buildList(); toggleLauncher(); });
        db.show();
    }

    /** Without our home screen the stock one stays, and it may rely on what gets disabled. */
    private static final String NO_LAUNCHER_WARN = T.t("⚠ Новая оболочка не установлена. Заводская оболочка может зависеть от того, что будет отключено, ")
            + T.t("поэтому её работоспособность не гарантируется: могут пропасть плитки, разделы или перестать открываться приложения. ")
            + T.t("Надёжнее сначала поставить новую оболочку.");

    /** Pick an app store to install; each one is checked against its developer's signature. */
    private void chooseStore() {
        if (busy) return;
        Ops.StoreApp[] all = Ops.STORES;
        CharSequence[] items = new CharSequence[all.length];
        for (int i = 0; i < all.length; i++) {
            boolean have;
            try { getPackageManager().getPackageInfo(all[i].pkg, 0); have = true; } catch (Exception e) { have = false; }
            items[i] = Style.twoLines(all[i].name + (have ? T.t("  ·  уже установлен") : ""), all[i].about);
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(T.t("Какой магазин поставить?"))
                .setItems(items, (d, w) -> {
                    Ops.StoreApp app = all[w];
                    new android.app.AlertDialog.Builder(this)
                            .setTitle(app.name)
                            .setMessage(app.about + T.t(".\n\nУборщик скачает его с официального сайта разработчика и поставит, только если ")
                                    + T.t("файл подписан самим разработчиком. Это стороннее приложение: за его работу отвечают его авторы."))
                            .setNegativeButton(T.t("Отмена"), null)
                            .setPositiveButton(T.t("Скачать и поставить"), (d2, w2) -> run(T.t("Ставлю ") + app.name + "…", () -> new Ops(this, this::say).installStore(app)))
                            .show();
                })
                .setNegativeButton(T.t("Отмена"), null)
                .show();
    }

    private void offerReboot() {
        new android.app.AlertDialog.Builder(this)
                .setTitle(T.t("Готово ✔"))
                .setMessage(T.t("Чтобы память освободилась полностью, телевизор лучше перезагрузить. После загрузки Уборщик проверит, что всё на месте."))
                .setNegativeButton(T.t("Позже"), null)
                .setPositiveButton(T.t("Перезагрузить"), (d, w) -> { saveFlags(); run(T.t("Перезагружаю…"), () -> new Ops(this, this::say).reboot()); })
                .show();
    }

    private Button tile(String title, String sub) {
        Button b = button("");
        b.setSingleLine(false);
        b.setText(Style.twoLines(title, sub));
        b.setPadding(28, 36, 28, 36);
        return b;
    }

    private static void addTile(LinearLayout row, Button b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(6, 6, 6, 6);
        row.addView(b, lp);
    }

    private String baseInfo() {
        String i = store.str("dbInfo");
        return i != null && new java.io.File(Updater.dir(this), "devices.tsv").isFile() ? i : Community.tvCount(this) + T.t(" ТВ, встроенная");
    }

    private void say(String s) { runOnUiThread(() -> show(status, s)); }

    /** Empty lines take no room. */
    private static void show(TextView t, CharSequence s) {
        t.setText(s);
        t.setVisibility(s == null || s.length() == 0 ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    private void scan() {
        show(status, T.t("Считаю память…"));
        new Thread(() -> {
            Map<String, Long> m = new Ops(this, null).scanMemory();
            runOnUiThread(() -> {
                mem = m;
                if (m.containsKey("_used"))
                    memory.setText(T.t("Занято ") + m.get("_used") / 1024 + T.t(" из ") + m.get("_total") / 1024 + T.t(" МБ")
                            + (store.flag("expertUi", false) ? T.t(". Цифра у приложения — сколько оно занимает сейчас.") : ""));
                else memory.setText(T.t("Нет связи с ADB телевизора. Включите «Отладку по USB / сети» и разрешите Уборщика."));
                show(warning, m.getOrDefault("_insecure", 0L) == 1L
                        ? T.t("⚠ На этой прошивке отладка по сети открыта без подтверждения (ro.adb.secure=0): любое устройство в домашней сети может управлять телевизором. Держите Wi-Fi под паролем и не пускайте в него посторонние устройства.")
                        : "");
                show(status, "");
                buildList();
            });
        }).start();
    }

    private void buildList() {
        list.removeAllViews();
        rows.clear();
        boolean exp = store.flag("expert", false);
        PackageManager pm = getPackageManager();
        Map<String, Catalog.Entry> cat = Catalog.get(this);
        Map<String, String> imp = Detector.important(this);
        String ime = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime != null && ime.contains("/")) ime = ime.substring(0, ime.indexOf('/'));
        Set<String> chosen = store.disabled(), sleepy = store.sleeping(), factory = store.set("snap_disabled"),
                rolled = store.set("rolledBack"), back = store.set("firmwareBack");
        Map<String, Community.Stat> com = Community.stats(this);
        Community.Match same = Community.device(this);
        list.addView(text(same != null
                ? T.t("Ваш телевизор есть в базе CleanYourTV (") + baseInfo() + "): " + same.tv + T.t(". Подсказки ниже — с такого же телевизора.")
                : T.t("Вашей модели нет в базе CleanYourTV (") + baseInfo() + T.t(") — Уборщик сам определит важное, а подсказки собраны со всех телевизоров."), 14, DIM));
        stores = Features.stores(this);
        List<Row> safe = new ArrayList<>(), ask = new ArrayList<>(), important = new ArrayList<>(), unknown = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)) {
            String pkg = ai.packageName;
            if (Detector.core(pkg) || pkg.equals(BuildInfo.LAUNCHER)) continue;
            Catalog.Entry e = cat.get(pkg);
            Community.Stat cs = com.get(pkg);
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean launchable = pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null;
            boolean sameOff = same != null && same.off.contains(pkg), sameKeep = same != null && same.keep.contains(pkg);
            boolean tele = Detector.looksLikeTelemetry(pkg);
            String level, hint = "";
            if (e != null) {
                level = e.level.equals(Catalog.LOCK) ? IMPORTANT : e.level;
                if (level.equals(IMPORTANT)) hint = T.t("на некоторых моделях без него ломается") + (sameOff ? T.t(" (хотя в базе такой ТВ его отключал)") : "");
            } else if (Detector.overlay(pkg)) { level = IMPORTANT; hint = T.t("настройки прошивки (оверлей): памяти почти не занимает, отключать нет смысла"); }
            else if (imp.containsKey(pkg) || pkg.equals(ime)) { level = IMPORTANT; hint = pkg.equals(ime) ? T.t("текущая клавиатура") : imp.get(pkg); }
            else if (sameKeep) { level = IMPORTANT; hint = T.t("на таком же ТВ: не отключать") + (cs != null && !cs.keepWhy.isEmpty() ? " — " + cs.keepWhy : ""); }
            else if (Catalog.lockedByPattern(pkg, ime) && !sameOff && (cs == null || cs.off == 0)) {
                if (!exp) continue;
                level = IMPORTANT; hint = T.t("системный пакет производителя");
            } else if (sameOff) { level = cs != null && cs.keep > 0 ? ASK : SAFE; hint = T.t("отключали на таком же ТВ"); }
            else if (system && !launchable && cs == null && !tele) {
                if (!exp) continue;
                level = UNKNOWN;
            } else level = (cs != null && cs.off >= 10 && cs.keep == 0) || (tele && cs == null && system) ? SAFE : ASK;
            if (tele) hint = hint.isEmpty() ? T.t("по названию похоже на телеметрию или рекламу") : hint + T.t("  ·  похоже на телеметрию/рекламу");
            if (cs != null) {
                String c2 = cs.keep > 0 ? T.t("⚠ на ") + cs.keep + T.t(" ТВ просят не трогать") + (cs.keepWhy.isEmpty() ? "" : ": " + cs.keepWhy)
                        : cs.off > 0 ? T.t("отключали на ") + cs.off + T.t(" ТВ") : "";
                if (!c2.isEmpty()) hint = hint.isEmpty() ? c2 : hint + "  ·  " + c2;
                if (cs.keep == 0 && !cs.why.isEmpty() && (e == null || e.note.isEmpty())) hint += " — " + cs.why;
            }
            Features.F feat = Features.of(pkg);
            if (feat == null && stores.contains(pkg)) feat = Features.byId("stores");
            Describe.D desc = Describe.get(this, pkg);
            // Nothing known about it: no description, no statistics, no feature, name says nothing.
            if (level.equals(ASK) && system && e == null && desc == null && cs == null && feat == null && !tele && !launchable) level = UNKNOWN;
            if (level.equals(UNKNOWN) && (desc != null || feat != null)) level = ASK;
            Set<String> used = store.set("features");
            boolean needed = feat != null && used.contains(feat.id);
            if (feat != null) hint = (needed ? T.t("нужно вам: ") : T.t("для функции, которой вы не пользуетесь: ")) + Features.plain(feat.title) + (hint.isEmpty() ? "" : "  ·  " + hint);
            if (rolled.contains(pkg)) hint = T.t("↩ Уборщик вернул его: после отключения что-то начало падать  ·  ") + hint;
            if (back.contains(pkg)) hint = T.t("прошивка включает его обратно при загрузке  ·  ") + hint;
            Row r = new Row();
            r.pkg = pkg; r.level = level; r.tele = tele; r.userApp = !system && launchable;
            r.feature = feat; r.needed = needed;
            r.known = e != null || desc != null || cs != null || sameOff || feat != null;
            String pmLabel = String.valueOf(ai.loadLabel(pm));
            r.label = e != null ? e.label : desc != null ? desc.label : pmLabel;
            String what = e != null && !e.note.isEmpty() ? e.note : desc != null ? desc.note : "";
            if (level.equals(UNKNOWN)) what = T.t("Что это — неизвестно: нет ни описания, ни статистики. Не уверены — оставьте ✅");
            else if (what.isEmpty() && pmLabel.equals(pkg)) what = T.t("служебный пакет без описания");
            r.note = what.isEmpty() ? hint : what + (hint.isEmpty() ? "" : "  ·  " + hint);
            if (r.note.length() > 260) r.note = r.note.substring(0, 260) + "…";
            int st = pm.getApplicationEnabledSetting(pkg);
            r.enabled = ai.enabled && st != PackageManager.COMPONENT_ENABLED_STATE_DISABLED && st != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
            r.factoryOff = !r.enabled && factory.contains(pkg);
            r.kb = mem.getOrDefault(pkg, 0L);
            r.state = (chosen.contains(pkg) || !r.enabled) ? 2 : sleepy.contains(pkg) ? 1 : 0;
            r.sub = (r.note.isEmpty() ? "" : r.note + "  ·  ") + pkg;
            r.box = rowButton();
            paint(r);
            r.box.setOnClickListener(v -> { r.state = (r.state + 1) % 3; paint(r); });
            (level.equals(SAFE) ? safe : level.equals(IMPORTANT) ? important : level.equals(UNKNOWN) ? unknown : ask).add(r);
            rows.add(r);
        }
        java.util.Comparator<Row> byMem = (x, y) -> Long.compare(y.kb, x.kb);
        safe.sort(byMem); ask.sort(byMem); important.sort(byMem); unknown.sort(byMem);
        if (!safe.isEmpty()) { list.addView(header(T.t("✅ Рекомендуем отключить"))); for (Row r : safe) list.addView(r.box); }
        if (!ask.isEmpty()) { list.addView(header(T.t("❓ На ваш выбор"))); for (Row r : ask) list.addView(r.box); }
        if (!unknown.isEmpty()) {
            list.addView(header(T.t("❔ Неизвестное")));
            list.addView(text(T.t("Про эти пакеты никто ничего не знает: ни описания, ни опыта других владельцев. Режимы их не трогают."), 14, DIM));
            for (Row r : unknown) list.addView(r.box);
        }
        if (!important.isEmpty()) {
            list.addView(header(T.t("⚠ Важное — без этого телевизор может работать неправильно")));
            list.addView(text(T.t("Менять можно, но на свой риск: Уборщик попросит подтверждения. Ядро Android, без которого телевизор не загрузится, сюда не попадает и не меняется никогда."), 14, DIM));
            for (Row r : important) list.addView(r.box);
        }
        list.addView(text(T.t("OK на строке: ✅ работает → 💤 не запускается само (открывается вручную как обычно) → ⛔ отключено. Потом «Применить». ")
                + T.t("Уборщик отключает порциями по 5 и, если после порции что-то начинает падать, сам её возвращает."), 14, DIM));
    }

    /** 0 safe, 1 recommended, 2 maximum: only sets the switches, "Apply" does the rest. */
    private void preset(int mode) {
        boolean exp = store.flag("expert", false);
        for (Row r : rows) {
            if (r.level.equals(IMPORTANT) || r.level.equals(UNKNOWN)) continue;
            if (Edition.LITE && !r.known && !r.factoryOff) { r.state = !r.enabled ? 2 : store.sleeping().contains(r.pkg) ? 1 : 0; continue; }
            if (r.factoryOff) { r.state = 2; paint(r); continue; }
            if (r.needed) { r.state = 0; paint(r); continue; }
            int s = 0;
            boolean safeHit = r.level.equals(SAFE) && (mode >= 1 || r.tele || Catalog.get(this).containsKey(r.pkg));
            if (mode == 0) s = (r.level.equals(SAFE) && (r.tele || Catalog.get(this).containsKey(r.pkg))) ? 2 : 0;
            if (mode == 1) s = r.level.equals(SAFE) || (r.tele && !r.userApp) ? 2 : r.userApp ? 1 : 0;
            if (mode == 2) {
                boolean launchable = getPackageManager().getLeanbackLaunchIntentForPackage(r.pkg) != null
                        || getPackageManager().getLaunchIntentForPackage(r.pkg) != null;
                s = launchable ? 1 : 2;
                if (r.level.equals(SAFE)) s = 2;
            }
            if (safeHit && mode > 0) s = Math.max(s, 2);
            if (r.feature != null && mode >= 1) s = r.userApp ? Math.max(s, 1) : 2;
            r.state = s;
            if (r.box.isEnabled()) paint(r);
        }
        show(status, mode == 0 ? T.t("🛡 Бережно: проверьте список и нажмите «Применить»")
                : mode == 1 ? T.t("👍 Оптимально: проверьте список и нажмите «Применить»")
                : T.t("🔥 Максимум: проверьте особенно внимательно, потом «Применить»"));
        if (store.flag("expertUi", false)) applyBtn.requestFocus(); else show(status, "");
    }

    private void saveFlags() {
        store.setFlag("anim", anim.isChecked());
        store.setFlag("cache", cache.isChecked());
        store.setFlag("killbg", killbg.isChecked());
        store.setFlag("dns", dns.isChecked());
    }

    private void apply() {
        List<String> risky = new ArrayList<>();
        for (Row r : rows) {
            int now = !r.enabled ? 2 : store.sleeping().contains(r.pkg) ? 1 : 0;
            if (r.level.equals(IMPORTANT) && r.state != 0 && r.state != now)
                risky.add("• " + r.label + (r.note.isEmpty() ? "" : " — " + r.note.split("  ·  ")[0]));
        }
        boolean noLauncher = !store.flag("launcher", false);
        boolean disables = false;
        for (Row r : rows) if (r.state != 0 && r.enabled && !store.disabled().contains(r.pkg) && !store.sleeping().contains(r.pkg)) disables = true;
        String storeWarn = noStoreWarn();
        if (risky.isEmpty() && !(noLauncher && disables) && storeWarn == null) { doApply(); return; }
        if (risky.isEmpty()) {
            String m = ((noLauncher && disables ? NO_LAUNCHER_WARN + "\n\n" : "") + (storeWarn != null ? storeWarn : "")).trim();
            android.app.AlertDialog.Builder ab = new android.app.AlertDialog.Builder(this)
                    .setTitle(T.t("Перед применением"))
                    .setMessage(m)
                    .setNegativeButton(T.t("Отмена"), null)
                    .setPositiveButton(T.t("Всё равно применить"), (d, w) -> doApply());
            if (noLauncher && disables) ab.setNeutralButton(T.t("Сначала оболочку"), (d, w) -> toggleLauncher());
            ab.show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(T.t("⚠ Вы меняете важное"))
                .setMessage((noLauncher ? NO_LAUNCHER_WARN + "\n\n" : "") + (storeWarn != null ? storeWarn + "\n\n" : "") + T.t("Это может нарушить работу телевизора:\n\n") + String.join("\n", risky)
                        + T.t("\n\nВы действуете на свой риск. Авторы Уборщика не несут ответственности за последствия, ")
                        + T.t("включая неработающие функции, потерю настроек и необходимость сброса или перепрошивки. ")
                        + T.t("Обычно всё можно вернуть кнопкой «Вернуть всё как было», но это не гарантируется."))
                .setNegativeButton(T.t("Отмена"), null)
                .setPositiveButton(T.t("Понимаю, применить"), (d, w) -> doApply())
                .show();
    }

    private void doApply() {
        saveFlags();
        Set<String> chosen = new HashSet<>(), disable = new HashSet<>(), enable = new HashSet<>();
        Set<String> sleep = new HashSet<>(), wake = new HashSet<>();
        Set<String> before = store.disabled(), sleptBefore = store.sleeping();
        for (Row r : rows) {
            if (r.state == 2) { chosen.add(r.pkg); if (r.enabled) disable.add(r.pkg); }
            else if (!r.enabled || before.contains(r.pkg)) enable.add(r.pkg);
            if (r.state == 1) sleep.add(r.pkg);
            else if (sleptBefore.contains(r.pkg)) wake.add(r.pkg);
        }
        for (String p : before) if (findRow(p) == null) chosen.add(p);
        store.setDisabled(chosen);
        store.setSleeping(sleep);
        store.setSet("rolledBack", new HashSet<>());
        progBar.setProgress(0); progBar.setVisibility(android.view.View.VISIBLE);
        progText.setText(T.t("Подключаюсь…")); progText.setVisibility(android.view.View.VISIBLE);
        run(T.t("Применяю порциями…"), () -> new Ops(this, this::say).progress((d, t, w) -> runOnUiThread(() -> {
            progBar.setProgress(d * 100 / t);
            progText.setText(d * 100 / t + "%  ·  " + w);
        })).apply(disable, enable, sleep, wake));
    }

    private void toggleLauncher() {
        boolean on = store.flag("launcher", false);
        run(on ? T.t("Возвращаю старую оболочку…") : T.t("Ставлю новую оболочку…"), () -> {
            Ops ops = new Ops(this, this::say);
            return on ? ops.restoreLauncher() : ops.installLauncher();
        });
    }

    @Override protected void onStart() { super.onStart(); openingLog = false; }

    /** Leave nothing behind: once the screen is closed and no job runs, end the process. */
    @Override protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations() && !openingLog) {
            finishAndRemoveTask();
            new Thread(() -> {
                while (busy) { try { Thread.sleep(500); } catch (InterruptedException ignored) {} }
                synchronized (Ops.LOCK) { android.os.Process.killProcess(android.os.Process.myPid()); }
            }).start();
        }
    }

    private interface Job { boolean run(); }

    private void run(String msg, Job job) {
        applyBtn.setEnabled(false); launcherBtn.setEnabled(false); simpleLauncher.setEnabled(false);
        show(status, msg);
        busy = true;
        new Thread(() -> {
            boolean ok = job.run();
            busy = false;
            runOnUiThread(() -> {
                applyBtn.setEnabled(true); launcherBtn.setEnabled(true); simpleLauncher.setEnabled(true);
                if (progBar.getVisibility() == android.view.View.VISIBLE) {
                    progBar.setProgress(100);
                    progBar.postDelayed(() -> { progBar.setVisibility(android.view.View.GONE); progText.setVisibility(android.view.View.GONE); }, 4000);
                }
                launcherBtn.setText(store.flag("launcher", false) ? T.t("Вернуть старую оболочку") : T.t("Новая оболочка"));
                simpleLauncher.setText(launcherBtn.getText());
                if (simpleRun) { simpleRun = false; if (ok) offerReboot(); }
                String last = status.getText().toString();
                show(status, (ok ? T.t("Готово ✔") : T.t("Не получилось — см. «Журнал»")) + (last.startsWith("⚠") || last.startsWith(T.t("Итог: ")) ? "  ·  " + last : ""));
                scan();
            });
        }).start();
    }

    private Row findRow(String pkg) { for (Row r : rows) if (r.pkg.equals(pkg)) return r; return null; }

    /** Three equal buttons per row: fits any TV width, nothing is squeezed off screen. */
    private static void add(LinearLayout row, Button b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(6, 6, 6, 6);
        b.setSingleLine(true); b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(b, lp);
    }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setPadding(0, 8, 0, 4); return l; }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); t.setPadding(0, 4, 0, 4); return t;
    }

    private TextView header(String s) {
        TextView t = text(s, 20, ACCENT); t.setTypeface(Typeface.DEFAULT_BOLD); t.setPadding(0, 28, 0, 8); return t;
    }

    private Button button(String s) {
        Button x = new Button(this); x.setText(s); x.setTextSize(19); x.setAllCaps(false); x.setPadding(32, 16, 32, 16);
        focusStyle(x);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMargins(6, 6, 6, 6); x.setLayoutParams(lp);
        return x;
    }

    private void focusStyle(TextView v) { Style.focus(v, ACCENT, TEXT, DIM); }

    private Button rowButton() {
        Button b = new Button(this);
        b.setAllCaps(false); b.setTextSize(20); b.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        b.setPadding(24, 12, 24, 12);
        focusStyle(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(6, 6, 6, 6);
        b.setLayoutParams(lp);
        return b;
    }

    private void paint(Row r) {
        String mark = r.state == 0 ? T.t("✅ Работает") : r.state == 1 ? T.t("💤 Не запускается само") : T.t("⛔ Отключено");
        String warn = r.level.equals(IMPORTANT) ? "⚠ " : "";
        r.box.setText(Style.twoLines(mark + "   " + warn + r.label + (r.kb > 0 ? "  ·  " + r.kb / 1024 + T.t(" МБ") : ""), r.sub));
    }

    private CheckBox check(String label, String sub, boolean checked) {
        CheckBox c = new CheckBox(this);
        c.setText(Style.twoLines(label, sub));
        c.setTextSize(20); c.setChecked(checked); c.setPadding(12, 10, 12, 10);
        c.setFocusable(true);
        focusStyle(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(6, 6, 6, 6);
        c.setLayoutParams(lp);
        return c;
    }
}
