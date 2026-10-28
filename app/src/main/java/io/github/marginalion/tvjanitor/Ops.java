package io.github.marginalion.tvjanitor;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Everything that talks to the TV's own adbd. */
final class Ops {
    interface Say { void say(String s); }
    interface Prog { void progress(int done, int total, String what); }
    private Prog prog;
    private int done, total;
    Ops progress(Prog p) { this.prog = p; return this; }
    private void step(String what) { if (prog != null && total > 0) prog.progress(Math.min(++done, total), total, what); }
    static final Object LOCK = new Object();
    private final Context c;
    private final Store store;
    private final StringBuilder log = new StringBuilder();
    private final Say say;

    /** Dev builds keep a second, detailed log (debug.txt): every crash line, processes and memory around changes. */
    private final boolean dev;
    private final StringBuilder dbgLog = new StringBuilder();

    Ops(Context c, Say say) {
        this.c = c; this.store = new Store(c); this.say = say;
        boolean d = false;
        try { d = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName.contains("-dev"); } catch (Exception ignored) {}
        this.dev = d;
    }

    private void dbg(String s) {
        if (dev) dbgLog.append(new SimpleDateFormat("dd.MM HH:mm:ss", Locale.US).format(new Date())).append("  ").append(s).append('\n');
    }

    /** Dev log: load, what is on screen, memory and the biggest processes. */
    private void dbgState(Adb adb, String when) {
        if (!dev) return;
        try {
            dbg("=== " + when + "  load " + adb.shell("cat /proc/loadavg").trim() + "  top " + topActivity(adb));
            Map<String, Long> m = memory(adb);
            if (m.containsKey("_used")) dbg("used " + m.get("_used") / 1024 + " MB of " + m.getOrDefault("_total", 0L) / 1024);
            List<Map.Entry<String, Long>> big = new ArrayList<>();
            for (Map.Entry<String, Long> e : m.entrySet()) if (!e.getKey().startsWith("_")) big.add(e);
            big.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            for (int i = 0; i < Math.min(30, big.size()); i++) dbg(String.format(Locale.US, "  %7d K  %s", big.get(i).getValue(), big.get(i).getKey()));
        } catch (Exception e) { dbg("state: " + e); }
    }

    /** The activity in front, as "package/.Activity", or "" when Android shows none. */
    private static String topActivity(Adb adb) throws Exception {
        String top = adb.shell("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'");
        Matcher m = Pattern.compile("u0 (\\S+/\\S+)").matcher(top);
        return m.find() ? m.group(1) : "";
    }

    void note(String s) {
        log.append(new SimpleDateFormat("dd.MM HH:mm:ss", Locale.US).format(new Date())).append("  ").append(s).append('\n');
        if (say != null) say.say(s);
    }

    Adb connect(int attempts) {
        // Debugging may be switched off while the app is not working (AdbGate): open it and give adbd a moment.
        if (AdbGate.open(c)) { note(T.t("Включаю отладку на время работы")); try { Thread.sleep(3000); } catch (InterruptedException ignored) {} }
        for (int i = 1; i <= attempts; i++) {
            try {
                Adb adb = Adb.connect("127.0.0.1", 5555, c.getFilesDir(), 90000);
                if (!AdbGate.can(c)) {
                    // Once: lets the app switch debugging on and off itself. Shell may grant this "development" permission.
                    adb.shell("pm grant " + BuildInfo.SELF + " " + AdbGate.PERM);
                    if (AdbGate.can(c)) note(T.t("Получил право самому включать и выключать отладку"));
                }
                return adb;
            }
            catch (Exception e) {
                note(T.t("Нет связи с ADB (") + i + "/" + attempts + "): " + e.getClass().getSimpleName() + " " + e.getMessage());
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            }
        }
        note(T.t("Не подключился. Включена ли отладка по сети и разрешён ли Уборщик на экране телевизора?"));
        return null;
    }

    String sh(Adb adb, String cmd) throws Exception {
        String r = adb.shell(cmd + " 2>&1").trim();
        if (cmd.startsWith("pm disable-user") && r.contains("protected package")) {
            // The firmware's own list: Android refuses, whatever we do. Remember it, never retry, never blame.
            String[] w = cmd.split(" ");
            Set<String> pr = store.set("protectedPkgs"); pr.add(w[w.length - 1]); store.setSet("protectedPkgs", pr);
        }
        String line = shortLine(cmd, r);
        if (line != null) note(line);
        step(line == null ? "" : line);
        return r;
    }

    /** One short line per command: what was done, and the first line of any error. */
    /** The line of an error that says why: Android prints "Exception occurred while executing" first. */
    private static String why(String r) {
        for (String l : r.split("\n")) {
            l = l.trim();
            if (l.isEmpty() || l.startsWith("Exception occurred while executing") || l.startsWith("at ")) continue;
            return l.length() > 140 ? l.substring(0, 140) + "…" : l;
        }
        return firstLine(r);
    }

    private static String shortLine(String cmd, String r) {
        String[] w = cmd.split(" ");
        String pkg = w[w.length - 1];
        if (cmd.startsWith("pm disable-user"))
            return r.contains("disabled") ? T.t("− отключено: ") + pkg : r.contains("Unknown package") ? T.t("· нет на ТВ: ") + pkg
                    : r.contains("protected package") ? T.t("🔒 прошивка не даёт отключить: ") + pkg : "✗ " + pkg + ": " + why(r);
        if (cmd.startsWith("cmd appops set")) return r.isEmpty() ? null : "✗ " + pkgOf(cmd) + ": " + firstLine(r);
        if (cmd.startsWith("am force-stop")) return r.isEmpty() ? T.t("💤 не запускается само: ") + pkg : "✗ " + pkg + ": " + firstLine(r);
        if (cmd.startsWith("pm enable"))
            return r.contains("enabled") ? T.t("+ включено: ") + pkg : "✗ " + pkg + ": " + why(r);
        String out = r.isEmpty() ? "ok" : firstLine(r);
        return "$ " + (cmd.length() > 70 ? cmd.substring(0, 70) + "…" : cmd) + "  →  " + out;
    }

    private static String pkgOf(String cmd) { String[] w = cmd.split(" "); return w.length > 3 ? w[3] : cmd; }

    private static String firstLine(String r) {
        String l = r.split("\n")[0].trim();
        int at = l.indexOf("\tat ");
        if (at > 0) l = l.substring(0, at);
        return l.length() > 120 ? l.substring(0, 120) + "…" : l;
    }

    /** PSS (or RSS) per package in KB, plus "_used" and "_total". */
    static Map<String, Long> memory(Adb adb) throws Exception {
        // Some TVs (Philips on MediaTek) need longer than dumpsys' default 10 s right after boot.
        String out = adb.shell("dumpsys -t 40 meminfo");
        Map<String, Long> m = new HashMap<>();


        int section = 0;
        for (String line : out.split("\n")) {
            if (line.startsWith("Total PSS by process") || line.startsWith("Total RSS by process")) section = 1;
            else if (section == 1 && line.startsWith("Total ")) section = 2;
            if (section != 1) continue;
            Matcher x = Pattern.compile("^\\s*([\\d,]+)K: ([A-Za-z0-9_.]+)").matcher(line);
            if (x.find()) {
                long kb = Long.parseLong(x.group(1).replace(",", ""));
                m.merge(x.group(2), kb, Long::sum);
            }
        }
        Matcher used = Pattern.compile("Used RAM:\\s*([\\d,]+)K").matcher(out);
        if (used.find()) m.put("_used", Long.parseLong(used.group(1).replace(",", "")));
        Matcher total = Pattern.compile("Total RAM:\\s*([\\d,]+)K").matcher(out);
        if (total.find()) m.put("_total", Long.parseLong(total.group(1).replace(",", "")));
        if (!m.containsKey("_used")) {
            // Fallback: the kernel's own numbers. MemAvailable is nonsense on some firmware (terabytes), so check it.
            String pm = adb.shell("cat /proc/meminfo");
            Matcher t = Pattern.compile("MemTotal:\\s*(\\d+)").matcher(pm), a = Pattern.compile("MemAvailable:\\s*(\\d+)").matcher(pm);
            if (t.find() && a.find()) {
                long tk = Long.parseLong(t.group(1)), ak = Long.parseLong(a.group(1));
                if (ak > 0 && ak < tk) { m.put("_total", tk); m.put("_used", tk - ak); }
            }
        }
        return m;
    }

    String usedLine(Adb adb) {
        try {
            Map<String, Long> m = memory(adb);
            if (m.containsKey("_used") && m.containsKey("_total"))
                return T.t("Занято ") + m.get("_used") / 1024 + T.t(" из ") + m.get("_total") / 1024 + T.t(" МБ");
        } catch (Exception ignored) {}
        return "";
    }

    void tweaks(Adb adb) throws Exception {
        String anim = store.flag("anim", true) ? "0.5" : "1";
        sh(adb, "settings put global window_animation_scale " + anim);
        sh(adb, "settings put global transition_animation_scale " + anim);
        sh(adb, "settings put global animator_duration_scale " + anim);
        if (store.flag("cache", false)) sh(adb, "settings put global activity_manager_constants max_cached_processes=8");
        else sh(adb, "settings delete global activity_manager_constants");
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            if (store.flag("dns", false)) {
                sh(adb, "settings put global private_dns_specifier dns.adguard-dns.com");
                sh(adb, "settings put global private_dns_mode hostname");
            } else if (store.flag("dnsWasOn", false)) {
                String m = store.str("snap_private_dns_mode");
                sh(adb, "settings put global private_dns_mode " + (m == null || m.equals("null") ? "opportunistic" : m));
            }
            store.setFlag("dnsWasOn", store.flag("dns", false));
        }
    }

    /** Before the first change: what was disabled and how the touched settings looked, for "restore everything". */
    void snapshot(Adb adb) throws Exception {
        if (store.has("snap_disabled")) {
            // Snapshots from before dev11 had no such list: work it out from what they recorded.
            if (!store.has("snap_foreign")) {
                java.util.Set<String> foreign = new java.util.TreeSet<>();
                for (String p : store.set("snap_disabled")) if (p.startsWith("com.yandex.") || p.startsWith("ru.yandex.")) foreign.add(p);
                store.setSet("snap_foreign", foreign);
            }
            return;
        }
        java.util.Set<String> d = new java.util.HashSet<>();
        for (String l : adb.shell("pm list packages -d").split("\n")) { l = l.trim(); if (l.startsWith("package:")) d.add(l.substring(8)); }
        store.setSet("snap_disabled", d);
        for (String k : new String[]{"window_animation_scale", "transition_animation_scale", "animator_duration_scale",
                "activity_manager_constants", "private_dns_mode", "private_dns_specifier"})
            store.setStr("snap_" + k, adb.shell("settings get global " + k).trim());
        store.setStr("snap_home", homeComponent(adb));
        // A disabled com.yandex.* most likely means another cleaner got here first (Novex Cleaner had 23 off, the
        // Yandex home and the HDMI player among them). The snapshot keeps them off all the same ("restore" = the
        // snapshot); after restoring, the owner is asked whether to switch these on too.
        java.util.Set<String> foreign = new java.util.TreeSet<>();
        for (String p : d) if (p.startsWith("com.yandex.") || p.startsWith("ru.yandex.")) foreign.add(p);
        store.setSet("snap_foreign", foreign);
        note(T.t("Снимок «как было» сохранён: отключено до Уборщика ") + d.size() + T.t(" пакетов"));
        if (!foreign.isEmpty()) note(T.t("Из них, похоже, отключено другим чистильщиком (после «Вернуть всё как было» Уборщик спросит, включить ли их): ")
                + String.join(", ", foreign));
    }

    /** Back to the snapshot: everything we disabled or put to sleep comes back, settings too. */
    boolean restoreAll() {
        synchronized (LOCK) {
            note(T.t("Возвращаю всё как было"));
            Adb adb = connect(3);
            if (adb == null) { save(); return false; }
            try {
                java.util.Set<String> factory = store.set("snap_disabled");
                java.util.Set<String> tried = new java.util.TreeSet<>();
                for (String l : adb.shell("pm list packages -d").split("\n")) {
                    l = l.trim();
                    if (!l.startsWith("package:")) continue;
                    String p = l.substring(8);
                    if (!factory.contains(p)) { sh(adb, "pm enable " + p); tried.add(p); }
                }
                // Check with Android, not with our own count: what is still off has not come back.
                java.util.Set<String> still = reallyOff(adb, tried);
                wakeAll(adb, store.sleeping());
                releaseHeld(adb, null);
                backToOldIme(adb);
                for (String k : new String[]{"window_animation_scale", "transition_animation_scale", "animator_duration_scale",
                        "activity_manager_constants", "private_dns_mode", "private_dns_specifier"}) {
                    String v = store.str("snap_" + k);
                    if (v == null || v.equals("null") || v.isEmpty()) sh(adb, "settings delete global " + k);
                    else sh(adb, "settings put global " + k + " " + v);
                }
                String home = store.str("snap_home");
                String stock = store.str("stockHome");
                if ((home == null || !home.contains("/") || home.startsWith(BuildInfo.LAUNCHER)) && stock != null) {
                    String[] l = adb.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME " + stock).trim().split("\n");
                    home = l[l.length - 1].trim();
                }
                if (home != null && home.contains("/") && !home.startsWith(BuildInfo.LAUNCHER)) sh(adb, "cmd package set-home-activity --user 0 " + home);
                store.setDisabled(new java.util.HashSet<>());
                store.setSleeping(new java.util.HashSet<>());
                store.setFlag("launcher", false);
                store.setFlag("dns", false); store.setFlag("dnsWasOn", false);
                // "As it was" includes debugging: before TV Janitor it was on all the time.
                store.setFlag("keepAdb", true);
                sh(adb, "am start -a android.intent.action.MAIN -c android.intent.category.HOME");
                if (!still.isEmpty()) {
                    note(T.t("⚠ Не вернулось: ") + still.size() + "  (" + String.join(", ", still) + ")"
                            + T.t(". Нажмите «Вернуть всё как было» ещё раз; если не помогает, прошивка не даёт их включить."));
                    note(T.t("Итог: ") + T.t("включено обратно ") + (tried.size() - still.size()) + T.t(" из ") + tried.size()
                            + T.t(". Перезагрузите телевизор, чтобы всё встало на места."));
                    return false;
                }
                note(T.t("Итог: ") + T.t("включено обратно ") + tried.size() + T.t(". Перезагрузите телевизор, чтобы всё встало на места."));
                return true;
            } catch (Exception e) {
                note(T.t("Ошибка: ") + e.getClass().getSimpleName() + " " + e.getMessage());
                return false;
            } finally { adb.close(); save(); }
        }
    }

    /** Disabled before TV Janitor and probably by another cleaner (snap_foreign), still off now. */
    java.util.Set<String> foreignOff() {
        java.util.Set<String> f = new java.util.TreeSet<>();
        android.content.pm.PackageManager pm = c.getPackageManager();
        for (String p : store.set("snap_foreign")) {
            try {
                int st = pm.getApplicationEnabledSetting(p);
                if (st == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        || st == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) f.add(p);
            } catch (IllegalArgumentException notInstalled) {}
        }
        return f;
    }

    /** After "restore everything", and only if the owner said yes: switch on what another cleaner had switched off. */
    boolean enableForeign(java.util.Set<String> pkgs) {
        synchronized (LOCK) {
            Adb adb = connect(3);
            if (adb == null) { save(); return false; }
            try {
                for (String p : pkgs) sh(adb, "pm enable " + p);
                java.util.Set<String> still = reallyOff(adb, pkgs);
                note(T.t("Включено отключённое до Уборщика: ") + (pkgs.size() - still.size()) + T.t(" из ") + pkgs.size()
                        + (still.isEmpty() ? "" : T.t(". Не включилось: ") + String.join(", ", new java.util.TreeSet<>(still))));
                return still.isEmpty();
            } catch (Exception e) {
                note(T.t("Ошибка: ") + e.getClass().getSimpleName() + " " + e.getMessage());
                return false;
            } finally { adb.close(); save(); }
        }
    }

    /**
     * The clean way out. Uninstalling the app leaves everything it switched off switched off (Android keeps that,
     * not us), and if our launcher went too with the stock Home off, the TV would sit on a black FallbackHome. So:
     * put everything back, check that another home screen (any make: Yandex, Google, Xiaomi...) answers Home,
     * and only then remove the launcher, the light keyboard and this app.
     */
    boolean removeEverything() {
        if (!restoreAll()) { note(T.t("⚠ Не всё вернулось — удалять ничего не буду. Попробуйте ещё раз после перезагрузки.")); save(); return false; }
        synchronized (LOCK) {
            Adb adb = connect(3);
            if (adb == null) { save(); return false; }
            try {
                String homes = adb.shell("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME");
                boolean other = false;
                for (String l : homes.split("\n")) {
                    l = l.trim();
                    if (l.contains("/") && !l.startsWith(BuildInfo.LAUNCHER + "/") && !l.contains("FallbackHome") && !l.startsWith("com.android.settings/")) other = true;
                }
                if (installed(BuildInfo.LAUNCHER)) {
                    if (!other) { note(T.t("⚠ Кроме новой оболочки, главного экрана нет — её оставляю, иначе телевизор останется без «Домой».")); }
                    else {
                        String home = homePackage(adb);
                        if (BuildInfo.LAUNCHER.equals(home)) { note(T.t("⚠ «Домой» всё ещё ведёт в новую оболочку — её оставляю. Выберите заводской главный экран и повторите.")); }
                        else sh(adb, "pm uninstall " + BuildInfo.LAUNCHER);
                    }
                }
                if (installed(KEYBOARD)) {
                    if (isOurIme(currentIme(adb))) note(T.t("⚠ Лёгкая клавиатура всё ещё основная — её оставляю."));
                    else sh(adb, "pm uninstall " + KEYBOARD);
                }
                note(T.t("Удаляю Уборщика. Спасибо, что пользовались!"));
                save();
                adb.shell("pm uninstall " + BuildInfo.SELF);      // ends this process
                return true;
            } catch (Exception e) {
                note(T.t("Ошибка: ") + e.getClass().getSimpleName() + " " + e.getMessage());
                return false;
            } finally { adb.close(); save(); }
        }
    }

    /**
     * Once, with the owner watching: switch debugging off, on again, and reconnect. Only if that works on this TV
     * does the app close debugging when it is done. If it doesn't, debugging is left on and the owner is told.
     */
    boolean testAdbCycle() {
        synchronized (LOCK) {
            if (!AdbGate.can(c)) { note(T.t("⚠ Нет права включать отладку: откройте Уборщика ещё раз, пока отладка включена")); save(); return false; }
            if (store.flag("adbCycleOk", false)) return true;
            note(T.t("Проверяю: выключаю отладку и включаю обратно…"));
            AdbGate.forceOff(c);
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            AdbGate.open(c);
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            for (int i = 0; i < 6; i++) {
                try {
                    Adb.connect("127.0.0.1", 5555, c.getFilesDir(), 20000).close();
                    store.setFlag("adbCycleOk", true); store.setFlag("adbCycleFail", false);
                    note(T.t("✔ Получилось: отладка теперь включается, только пока Уборщик работает"));
                    save();
                    return true;
                } catch (Exception e) { try { Thread.sleep(5000); } catch (InterruptedException ignored) {} }
            }
            store.setFlag("adbCycleFail", true); store.setFlag("keepAdb", true);
            note(T.t("⚠ После выключения отладка по сети не вернулась сама. Включите «Отладку по сети» в настройках разработчика: Уборщик больше не будет её выключать."));
            save();
            return false;
        }
    }

    /** Reboot; after boot the Cleaner re-applies, checks what the firmware put back, and opens with a report. */
    boolean reboot() { return reboot(false); }

    /**
     * Two ways to restart: "svc power reboot" goes through Android (plain "reboot" powers some TVs off: Sunwind),
     * plain "reboot" is the only one some firmware obeys (Novex accepts svc and does nothing). The one that worked
     * on this TV is remembered at the next boot; {@code other} tries the one not remembered, at the owner's word.
     * Never both on a timer: a slow svc followed by "reboot" could power a Sunwind off.
     */
    boolean reboot(boolean other) {
        synchronized (LOCK) {
            Adb adb = connect(2);
            if (adb == null) { save(); return false; }
            String usual = "reboot".equals(store.str("rebootMethod")) ? "reboot" : "svc";
            String way = other ? (usual.equals("svc") ? "reboot" : "svc") : usual;
            try {
                store.setFlag("openAfterBoot", true);
                store.setStr("rebootAskedAt", String.valueOf(System.currentTimeMillis()));
                store.setStr("rebootAskedBoot", String.valueOf(Boot.count(c)));
                store.setStr("rebootTrying", way);
                note(T.t("Перезагружаю телевизор…"));
                save();
                Boot.afterRestart(c);
                adb.shell(way.equals("svc") ? "svc power reboot" : "reboot");
                android.os.SystemClock.sleep(20000);
            } catch (Exception e) {
                // The connection drops when the TV goes down, but also without that: alive 20 s later = no restart.
                android.os.SystemClock.sleep(20000);
            } finally { adb.close(); }
            store.setFlag("openAfterBoot", false);
            store.setStr("rebootAskedBoot", null);
            store.setStr("rebootTrying", null);
            Boot.cancelAfterRestart(c);
            if (way.equals(store.str("rebootMethod"))) store.setStr("rebootMethod", null);
            store.setFlag("rebootOfferOther", true);
            note(T.t("⚠ Телевизор не перезагрузился. Попробуйте другой способ или перезагрузите его кнопкой питания на пульте (подержите 5 секунд)."));
            save();
            return false;
        }
    }

    static boolean adbInsecure(Adb adb) {
        try { return "0".equals(adb.shell("getprop ro.adb.secure").trim()); } catch (Exception e) { return false; }
    }

    /** The TV's clock in the form logcat -T takes, so that a check reads only what was logged after it. */
    private static String now(Adb adb) {
        try { String t = adb.shell("date '+%m-%d %H:%M:%S.000'").trim(); return t.matches("\\d\\d-\\d\\d [\\d:.]+") ? t : null; }
        catch (Exception e) { return null; }
    }

    private static final Pattern JAVA_CRASH = Pattern.compile("Process: ([\\w.:]+), PID"),
            NATIVE_CRASH = Pattern.compile("Fatal signal .* pid \\d+ \\(([^)]+)\\)"),
            SERVICE_DIED = Pattern.compile("Scheduling restart of crashed service ([\\w.]+)/");

    /**
     * What fell over since {@code since} (since boot when null): one entry per crash, the process name and the log line that says so.
     * App crashes (FATAL), native ones (Fatal signal) and services Android has to restart.
     */
    private static List<String[]> crashes(Adb adb, String since) {
        List<String[]> out = new ArrayList<>();
        String t = since == null ? "" : " -T '" + since + "'";
        try {
            String r = adb.shell("logcat -d -b crash" + t + " | grep -E 'Process: |Fatal signal'")
                    + "\n" + adb.shell("logcat -d -b main -b system" + t + " | grep 'Scheduling restart of crashed service'");
            for (String l : r.split("\n")) {
                for (Pattern p : new Pattern[]{JAVA_CRASH, NATIVE_CRASH, SERVICE_DIED}) {
                    Matcher m = p.matcher(l);
                    if (m.find()) { out.add(new String[]{m.group(1), l.trim()}); break; }
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    /**
     * What the TV can't do without: Android itself, its system UI, our Home and keyboard, the TV's own services
     * (picture, inputs, sound: MediaTek, CVTE) and the apps behind what the owner said they use.
     */
    private Set<String> vital(Adb adb) {
        Set<String> v = new java.util.HashSet<>(java.util.Arrays.asList("system_server", "android", "com.android.systemui",
                "com.android.phone", "com.android.providers.settings", BuildInfo.SELF, BuildInfo.LAUNCHER));
        try { String ime = currentIme(adb); if (ime.contains("/")) v.add(ime.substring(0, ime.indexOf('/'))); } catch (Exception ignored) {}
        try { String h = homePackage(adb); if (h != null) v.add(h); } catch (Exception ignored) {}
        return v;
    }

    private boolean isVital(Set<String> vital, Set<String> used, String proc) {
        String pkg = proc.contains(":") ? proc.substring(0, proc.indexOf(':')) : proc;
        if (vital.contains(proc) || vital.contains(pkg)) return true;
        if (pkg.startsWith("com.mediatek.") || pkg.startsWith("com.cvte.") || pkg.startsWith("/system/") || pkg.startsWith("/vendor/")) return true;
        Features.F f = Features.of(pkg);
        return f != null && used.contains(f.id);
    }

    /**
     * Sort what fell over: packages we are switching off ourselves dying is expected (Alice's own services on
     * Sunwind, which looked like two crashes), anything vital is trouble, the rest is only written down.
     * Returns how many vital crashes there were.
     */
    private int judge(List<String[]> fell, Collection<String> ours, Set<String> vital, Set<String> used, Set<String> minor) {
        int bad = 0;
        for (String[] f : fell) {
            String pkg = f[0].contains(":") ? f[0].substring(0, f[0].indexOf(':')) : f[0];
            boolean own = ours.contains(pkg), v = !own && isVital(vital, used, f[0]);
            dbg((own ? "ours  " : v ? "VITAL " : "minor ") + f[1]);
            if (own) continue;
            if (v) { bad++; note(T.t("⚠ Упало важное: ") + f[0]); }
            else minor.add(pkg);
        }
        return bad;
    }

    /**
     * A batch was put back: switch its packages off again one at a time, keeping the earlier ones off, so only the
     * one that brings vital crashes back (alone or together with those before it) is blamed and stays on.
     */
    private List<String> culprits(Adb adb, List<String> batch, int noise, Set<String> vital, Set<String> used, Set<String> minor) throws Exception {
        if (batch.size() == 1) return batch;
        Thread.sleep(3000);                     // what was just put back settles before the next count
        note(T.t("Ищу виновника: отключаю их снова по одному…"));
        List<String> guilty = new ArrayList<>();
        for (String p : batch) {
            String since = now(adb);
            sh(adb, "pm disable-user --user 0 " + p);
            Thread.sleep(8000);
            if (judge(crashes(adb, since), java.util.Collections.singleton(p), vital, used, minor) >= 1 + 2 * noise) {
                sh(adb, "pm enable " + p);
                note(T.t("⚠ Виновник: ") + p + T.t(" — с ним падает важное. Оставляю включённым."));
                guilty.add(p);
                Thread.sleep(3000);
            }
        }
        if (guilty.isEmpty()) note(T.t("По одному ничего не упало: сбой был случайным, всё отключено."));
        return guilty;
    }

    /**
     * The firmware refused to disable these (protected). The next best thing, tried in order: freeze them
     * (pm suspend: installed and enabled, but nothing of theirs runs), else keep them out of the background
     * (appops, the strictest standby bucket, force-stop). What each one allowed is remembered for the report and
     * for "Restore everything"; a process the firmware keeps alive whatever we do is named, not hidden.
     */
    private void holdBack(Adb adb, Collection<String> pkgs) throws Exception {
        Set<String> frozen = store.set("frozenPkgs"), held = store.set("heldPkgs"), immortal = store.set("immortalPkgs");
        for (String p : pkgs) {
            if (!frozen.contains(p) && adb.shell("pm suspend --user 0 " + p + " 2>&1").contains("new suspended state: true")) {
                frozen.add(p); held.remove(p); immortal.remove(p);
                note(T.t("🧊 заморожено: ") + p);
                continue;
            }
            if (frozen.contains(p)) continue;
            adb.shell("cmd appops set " + p + " RUN_ANY_IN_BACKGROUND ignore 2>&1");
            adb.shell("cmd appops set " + p + " RUN_IN_BACKGROUND ignore 2>&1");
            adb.shell("am set-standby-bucket " + p + (android.os.Build.VERSION.SDK_INT >= 30 ? " restricted" : " rare") + " 2>&1");
            String before = adb.shell("pidof " + p).trim();
            adb.shell("am force-stop " + p);
            Thread.sleep(1500);
            String after = adb.shell("pidof " + p).trim();
            // A new process number is not a stop: the firmware started it again at once (the updater on Sunwind).
            // And an empty one is checked once more a few seconds later, for the same reason.
            if (after.isEmpty() && !before.isEmpty()) { Thread.sleep(5000); after = adb.shell("pidof " + p).trim(); }
            dbg("hold " + p + ": pid " + before + " -> " + after);
            held.add(p);
            if (!after.isEmpty() && after.equals(before)) {
                immortal.add(p);
                note(T.t("🛡 прошивка не даёт ни отключить, ни заморозить, ни остановить: ") + p);
            } else if (!after.isEmpty()) {
                immortal.add(p);
                note(T.t("♻ останавливается, но прошивка тут же запускает снова: ") + p);
            } else {
                immortal.remove(p);
                note(T.t("💤 не заморозить, но в фоне не работает: ") + p);
            }
        }
        store.setSet("frozenPkgs", frozen); store.setSet("heldPkgs", held); store.setSet("immortalPkgs", immortal);
    }

    /** Undo holdBack for these packages (all of them when null): the owner unticked them, or "Restore everything". */
    private void releaseHeld(Adb adb, Collection<String> only) throws Exception {
        Set<String> frozen = store.set("frozenPkgs"), held = store.set("heldPkgs"), immortal = store.set("immortalPkgs");
        for (String p : new java.util.TreeSet<>(frozen)) {
            if (only != null && !only.contains(p)) continue;
            sh(adb, "pm unsuspend --user 0 " + p); frozen.remove(p);
        }
        for (String p : new java.util.TreeSet<>(held)) {
            if (only != null && !only.contains(p)) continue;
            sh(adb, "cmd appops set " + p + " RUN_ANY_IN_BACKGROUND allow");
            sh(adb, "cmd appops set " + p + " RUN_IN_BACKGROUND allow");
            adb.shell("am set-standby-bucket " + p + " active 2>&1");
            held.remove(p); immortal.remove(p);
        }
        store.setSet("frozenPkgs", frozen); store.setSet("heldPkgs", held); store.setSet("immortalPkgs", immortal);
    }

    /** Of the chosen packages, the ones Android really reports as disabled right now. */
    private static Set<String> reallyOff(Adb adb, Set<String> chosen) throws Exception {
        Set<String> off = new java.util.HashSet<>();
        for (String l : adb.shell("pm list packages -d").split("\n")) {
            l = l.trim();
            if (l.startsWith("package:") && chosen.contains(l.substring(8))) off.add(l.substring(8));
        }
        return off;
    }

    void disableAll(Adb adb, Collection<String> pkgs) throws Exception {
        for (String p : pkgs) sh(adb, "pm disable-user --user 0 " + p);
    }

    /** No background running; force-stop also keeps the boot broadcast away until the app is opened by hand. */
    void sleepAll(Adb adb, Collection<String> pkgs) throws Exception {
        for (String p : pkgs) {
            sh(adb, "cmd appops set " + p + " RUN_ANY_IN_BACKGROUND ignore");
            sh(adb, "cmd appops set " + p + " RUN_IN_BACKGROUND ignore");
            sh(adb, "am force-stop " + p);
        }
    }

    void wakeAll(Adb adb, Collection<String> pkgs) throws Exception {
        for (String p : pkgs) {
            sh(adb, "cmd appops set " + p + " RUN_ANY_IN_BACKGROUND allow");
            sh(adb, "cmd appops set " + p + " RUN_IN_BACKGROUND allow");
        }
    }

    /** When and on which boot Uborshchik last put the TV in order, to tell a real reboot from a run on the same boot. */
    private void markRun() {
        store.setStr("lastRunAt", String.valueOf(System.currentTimeMillis()));
        store.setStr("lastRunBoot", String.valueOf(Boot.count(c)));
    }

    /**
     * By Android's boot counter, not the clock (the clock jumps: NTP, a hand-set time). Not known (no counter, no
     * mark) counts as a reboot: the cautious side, where the owner's choices are kept and applied again.
     */
    private boolean rebootedSinceLastRun() {
        int n = Boot.count(c);
        String s = store.str("lastRunBoot");
        return n < 0 || s == null || !s.equals(String.valueOf(n));
    }

    /** After boot: put every choice back (some firmware re-enables packages at each boot). */
    boolean bootRun(String reason) {
        synchronized (LOCK) {
            boolean receiver = reason.endsWith("BOOT_COMPLETED");
            if (receiver) { store.setStr("bootTried", String.valueOf(Boot.count(c))); store.setFlag("bootBlocked", false); }
            if (Boot.done(c)) return true;
            Boot.checkReboot(c);
            note(T.t("Старт (") + reason + ")" + (receiver ? "" : T.t(", через ") + Boot.upMinutes() + T.t(" мин после включения")));
            Adb adb = connect(12);
            if (adb == null) { save(); return false; }
            try {
                note(T.t("Подключено"));
                dbgState(adb, "boot run, before");
                launcherConfig(adb);
                rollbackStockHome(adb);                // only if a switch-off was cut short (power, crash)
                boolean launcher = store.flag("launcher", false);
                if (launcher) sh(adb, "cmd package set-home-activity --user 0 " + BuildInfo.LAUNCHER_HOME);
                java.util.Set<String> nowOff = new java.util.HashSet<>();
                for (String l : adb.shell("pm list packages -d").split("\n")) { l = l.trim(); if (l.startsWith("package:")) nowOff.add(l.substring(8)); }
                java.util.Set<String> back = new java.util.HashSet<>();
                // Only what an earlier run really switched off counts as "the firmware put it back"; a choice
                // that was saved but never applied (the app was closed mid-way) is simply applied now.
                java.util.Set<String> applied = store.set("appliedOff");
                int pending = 0;
                neverOff(adb, null);
                sparePersistent(adb, store.disabled());
                // Switched back on from outside since the last run, and the owner uses it (HDMI on Sunwind: the TV
                // player was put back with adb and Uborshchik switched it off again after the reboot). It stays on.
                java.util.Set<String> keep = new java.util.TreeSet<>();
                for (String p : store.disabled()) if (!nowOff.contains(p) && applied.contains(p) && needed(p)) keep.add(p);
                if (!keep.isEmpty()) {
                    Set<String> d = store.disabled(); d.removeAll(keep); store.setDisabled(d);
                    note(T.t("Включено не Уборщиком, а нужно вам — оставляю включённым: ") + String.join(", ", keep));
                }
                java.util.Set<String> prot = store.set("protectedPkgs");
                for (String p : store.disabled()) if (!nowOff.contains(p) && !prot.contains(p) && installed(p)) { if (applied.contains(p)) back.add(p); else pending++; }
                if (pending > 0) note(T.t("Применяю выбор, который ещё не применялся: ") + pending);
                // No reboot since our last run (a late job, or the reboot button did nothing): the firmware had
                // no boot to put anything back, so a person did it, by hand or with adb (Novex: input.efir). Their
                // choice wins: the tick comes off instead of switching it off again.
                if (!back.isEmpty() && !rebootedSinceLastRun()) {
                    Set<String> d = store.disabled(); d.removeAll(back); store.setDisabled(d);
                    Set<String> a = store.set("appliedOff"); a.removeAll(back); store.setSet("appliedOff", a);
                    note(T.t("Включено не Уборщиком (перезагрузки не было) — оставляю включённым и снимаю отметку: ")
                            + String.join(", ", new java.util.TreeSet<>(back)));
                    back.clear();
                }
                store.setSet("firmwareBack", back);
                if (!back.isEmpty()) note(T.t("Прошивка сама включила обратно при загрузке: ") + back.size() + T.t(" — отключаю снова")
                        + "  (" + String.join(", ", new java.util.TreeSet<>(back)) + ")");
                // Only what is on now: some packages are disabled by the firmware itself, and disable-user on
                // those only throws (org.droidtv.freeviewplayers on Philips).
                java.util.Set<String> todo = new java.util.TreeSet<>(store.disabled()); todo.removeAll(nowOff);
                todo.removeAll(store.set("protectedPkgs"));
                disableAll(adb, todo);
                // Protected ones the owner chose: the freeze survives a reboot, the background limits get renewed.
                java.util.Set<String> held = new java.util.TreeSet<>(store.disabled()); held.retainAll(store.set("heldPkgs"));
                if (!held.isEmpty()) holdBack(adb, held);
                loopsSinceBoot(adb);
                store.setSet("appliedOff", reallyOff(adb, store.disabled()));
                if (!store.disabled().isEmpty()) {
                    String first = store.disabled().iterator().next();
                    boolean ok = adb.shell("pm list packages -d").contains("package:" + first + "\n") || adb.shell("pm list packages -d").trim().endsWith(first);
                    if (!ok && installed(first)) note(T.t("⚠ Похоже, прошивка не даёт отключать приложения (") + first + T.t(" остался включён)"));
                }
                sleepAll(adb, store.sleeping());
                tweaks(adb);
                if (new File(c.getExternalFilesDir(null), "commands.txt").isFile())
                    note(T.t("Файл commands.txt больше не используется (любое приложение могло его изменить). Выбор задаётся в меню."));
                if (store.flag("killbg", false)) sh(adb, "am kill-all");
                if (launcher) {
                    // Bring our Home up only if the TV is sitting on a boot or stock home screen, not over live TV.
                    String top = adb.shell("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'");
                    String stock = store.str("stockHome");
                    if (top.trim().isEmpty() || top.contains("FallbackHome") || (stock != null && top.contains(stock + "/")))
                        sh(adb, "am start -a android.intent.action.MAIN -c android.intent.category.HOME");
                }
                dbgState(adb, "boot run, after");
                String used = usedLine(adb);
                note(used);
                store.setStr("bootReport", T.t("После перезагрузки: ") + (used.isEmpty() ? "" : used + ". ")
                        + (back.isEmpty() ? T.t("Прошивка ничего не вернула сама.") : T.t("Прошивка вернула сама ") + back.size() + T.t(", Уборщик отключил снова."))
                        + T.t(" Отключено: ") + store.disabled().size() + T.t(", не запускаются сами: ") + store.sleeping().size() + "."
                        + (receiver ? "" : T.t(" Применено через ") + Boot.upMinutes() + T.t(" мин после включения: прошивка не пускает Уборщика в автозапуск.")));
                Report.saveIfConsented(c);
                store.setFlag("reportUnseen", true);
                store.setStr("bootDone", String.valueOf(Boot.count(c)));
                markRun();
                // Late (scheduled) runs never pop the app up: minutes have passed, the owner is watching something.
                if (!receiver) store.setFlag("openAfterBoot", false);
                if (store.flag("openAfterBoot", false)) {
                    store.setFlag("openAfterBoot", false);
                    // Be polite: open with the report only over the Home screen. If the TV came up in live TV, an
                    // input or an app, stay out of the way; the report waits until the app is opened.
                    String top = adb.shell("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'");
                    String home = homePackage(adb);
                    if (home != null && top.contains(home + "/")) sh(adb, "am start -n " + BuildInfo.SELF + "/.MainActivity --ez report true");
                    else note(T.t("На экране что-то смотрят — отчёт покажу, когда откроете Уборщика"));
                }
                note(T.t("Готово"));
                return true;
            } catch (Exception e) {
                note(T.t("Ошибка: ") + e.getClass().getSimpleName() + " " + e.getMessage());
                return false;
            } finally { adb.close(); save(); }
        }
    }

    /** From the menu: disable what was ticked, enable what was unticked. */
    boolean apply(Set<String> disable, Set<String> enable, Set<String> sleep, Set<String> wake) {
        synchronized (LOCK) {
            note(T.t("Применяю выбор"));
            Adb adb = connect(3);
            if (adb == null) { save(); return false; }
            try {
                snapshot(adb);
                rollbackStockHome(adb);
                // Sunwind, 28.10: 22 packages came back on between two runs with no reboot and nothing in our log.
                Set<String> applied = store.set("appliedOff");
                applied.removeAll(reallyOff(adb, applied));
                applied.retainAll(store.disabled());
                if (!applied.isEmpty()) note(T.t("⚠ Кто-то включил обратно без Уборщика (не перезагрузка): ") + applied.size()
                        + "  (" + String.join(", ", new java.util.TreeSet<>(applied)) + ")");
                // A stock Home that has no Google-style FallbackHome rule (Yandex TV) is switched off only by
                // stockHomeOff(): checked, with a marker on disk and a rollback, never in a batch.
                String stock = store.str("stockHome");
                boolean homeOff = stock != null && disable.remove(stock);
                int batches = (disable.size() + 4) / 5;
                total = enable.size() + disable.size() + batches * 2 + 3 * sleep.size() + 2 * wake.size() + 7;
                done = 0;
                for (String p : enable) sh(adb, "pm enable " + p);
                releaseHeld(adb, enable);
                neverOff(adb, disable);
                sparePersistent(adb, disable);
                for (String p : disable) if (needed(p)) {
                    Features.F f = Features.of(p);
                    note(T.t("⚠ Отключаю то, что нужно вам для «") + Features.plain(f.title) + "»: " + p + T.t(". Если функция перестанет работать — снимите галочку."));
                }
                // CleanYourTV: small batches, check between them. A batch that starts a crash loop is put back.
                Set<String> prot = store.set("protectedPkgs");
                List<String> todo = new ArrayList<>(disable);
                todo.removeAll(prot);
                java.util.Set<String> rolled = new java.util.HashSet<>();
                // How often something vital falls over on its own: some TVs (Sunwind, load average 60) crash every few
                // seconds, and a batch must not be blamed for that. The same 8 s window with nothing changed.
                Set<String> vital = vital(adb), used = store.set("features"), minor = new java.util.TreeSet<>();
                int noise = 0;
                if (!todo.isEmpty()) {
                    String q0 = now(adb);
                    step(T.t("Смотрю, как часто телевизор падает сам…"));
                    Thread.sleep(8000);
                    noise = judge(crashes(adb, q0), java.util.Collections.emptySet(), vital, used, new java.util.HashSet<>());
                    if (noise > 0) note(T.t("Телевизор и сам по себе падает: ") + noise + T.t(" сбоев за 8 с. Учту это, чтобы не винить ваш выбор."));
                }
                dbgState(adb, "before apply");
                for (int i = 0; i < todo.size(); i += 5) {
                    List<String> batch = todo.subList(i, Math.min(i + 5, todo.size()));
                    String since = now(adb);
                    int n = i / 5 + 1;
                    step(T.t("Порция ") + n + T.t(" из ") + batches + T.t(": отключаю ") + batch.size());
                    disableAll(adb, batch);
                    if (prog != null) prog.progress(done, total, T.t("Порция ") + n + T.t(" из ") + batches + T.t(" · проверяю, всё ли в порядке…"));
                    Thread.sleep(8000);
                    step(T.t("Порция ") + n + T.t(" из ") + batches + T.t(" проверена"));
                    // Everything switched off in this run so far: a package dying because we switched it off is no crash.
                    int bad = judge(crashes(adb, since), todo.subList(0, Math.min(i + 5, todo.size())), vital, used, minor);
                    if (bad >= 1 + 2 * noise) {
                        // Only what this batch really switched off can be to blame (not what Android refused).
                        List<String> blame = new ArrayList<>(reallyOff(adb, new java.util.HashSet<>(batch)));
                        if (!blame.isEmpty()) {
                            note(T.t("⚠ После этой порции падает важное (") + bad + T.t(" сбоев) — возвращаю её: ") + String.join(", ", blame));
                            for (String p : blame) sh(adb, "pm enable " + p);
                            rolled.addAll(culprits(adb, blame, noise, vital, used, minor));
                        }
                    }
                }
                if (!minor.isEmpty()) {
                    note(T.t("ℹ Падали после отключения, но телевизору это не мешает: ") + String.join(", ", minor));
                    Set<String> all = store.set("minorCrashers"); all.addAll(minor); store.setSet("minorCrashers", all);
                }
                // An earlier run's verdict was made by the old counter, which blamed Alice for her own services.
                Set<String> cr = store.set("crashers"); cr.removeAll(todo); store.setSet("crashers", cr);
                if (!rolled.isEmpty()) {
                    Set<String> d = store.disabled(); d.removeAll(rolled); store.setDisabled(d);
                    store.setSet("rolledBack", rolled);
                    java.util.Set<String> all = store.set("crashers"); all.addAll(rolled); store.setSet("crashers", all);
                    disable.removeAll(rolled);
                }
                if (homeOff) {
                    if (stockHomeOff(adb, stock)) disable.add(stock);
                    else { Set<String> d = store.disabled(); d.remove(stock); store.setDisabled(d); }
                }
                wakeAll(adb, wake);
                sleepAll(adb, sleep);
                store.setSet("appliedOff", reallyOff(adb, store.disabled()));
                Report.saveIfConsented(c);
                tweaks(adb);
                note(usedLine(adb));
                // The summary counts what Android reports, not what was asked for.
                Set<String> off = reallyOff(adb, disable);
                Set<String> notOff = new java.util.TreeSet<>(disable); notOff.removeAll(off);
                Set<String> refused = new java.util.TreeSet<>(notOff); refused.retainAll(store.set("protectedPkgs"));
                notOff.removeAll(refused);
                if (!refused.isEmpty()) {
                    Set<String> firm = new java.util.TreeSet<>(refused); firm.removeAll(store.set("persistentPkgs"));
                    if (!firm.isEmpty()) note(T.t("🔒 Эта прошивка не даёт отключить: ") + firm.size() + "  (" + String.join(", ", firm) + ")"
                            + T.t(". Это защита самой прошивки, не ошибка; пробовать снова не буду."));
                    holdBack(adb, refused);
                }
                Set<String> notOn = new java.util.TreeSet<>(reallyOff(adb, enable));
                if (!notOff.isEmpty()) note(T.t("⚠ Не отключилось: ") + notOff.size() + "  (" + String.join(", ", notOff) + ")"
                        + T.t(". Попробую ещё раз после перезагрузки."));
                if (!notOn.isEmpty()) note(T.t("⚠ Не включилось: ") + notOn.size() + "  (" + String.join(", ", notOn) + ")");
                dbgState(adb, "after apply");
                if (prog != null) prog.progress(total, total, T.t("Готово"));
                note(T.t("Итог: ") + T.t("отключено ") + off.size() + (notOff.isEmpty() ? "" : T.t(" из ") + disable.size())
                        + T.t(", включено ") + (enable.size() - notOn.size()) + (notOn.isEmpty() ? "" : T.t(" из ") + enable.size())
                        + T.t(", не запускаются сами ") + sleep.size());
                markRun();
                return true;
            } catch (Exception e) {
                note(T.t("Ошибка: ") + e.getClass().getSimpleName() + " " + e.getMessage());
                return false;
            } finally { adb.close(); save(); }
        }
    }

    Map<String, Long> scanMemory() {
        synchronized (LOCK) {
            Adb adb = connect(2);
            if (adb == null) return new HashMap<>();
            try {
                Map<String, Long> m = new HashMap<>();
                try { m = memory(adb); } catch (Exception ignored) {}
                m.put("_connected", 1L);
                boolean insecure = adbInsecure(adb);
                m.put("_insecure", insecure ? 1L : 0L);
                store.setFlag("adbInsecure", insecure);
                try { snapshot(adb); } catch (Exception ignored) {}
                return m;
            } catch (Exception e) { Map<String, Long> m = new HashMap<>(); m.put("_connected", 1L); return m; } finally { adb.close(); save(); }
        }
    }

    /**
     * Switch the stock Home off (Yandex TV: about 180 MB with its Metrica processes) only when it is safe: our
     * launcher is the Home, the firmware's FallbackHome is there to catch a failed boot, and after the switch
     * "Home" really opens our launcher and it is still there seconds later. A marker on disk before the first change
     * means a power cut or crash halfway is put right at the next start (rollbackStockHome).
     */
    private boolean stockHomeOff(Adb adb, String stock) throws Exception {
        if (!store.flag("launcher", false)) { note(T.t("Заводской «Домой» не трогаю: сначала поставьте новую оболочку.")); return false; }
        String homes = adb.shell("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME");
        if (!homes.contains("FallbackHome") && !homes.contains("com.example.user.myapplication")) {
            note(T.t("Заводской «Домой» не трогаю: на этой прошивке нет запасного (FallbackHome)."));
            return false;
        }
        if (!store.commitStr("homeTx", stock)) { note(T.t("Не смог записать отметку на диск — заводской «Домой» не трогаю.")); return false; }
        try {
            note(T.t("Отключаю заводской «Домой» с проверкой: ") + stock);
            sh(adb, "pm disable-user --user 0 " + stock);
            sh(adb, "cmd package set-home-activity --user 0 " + BuildInfo.LAUNCHER_HOME);
            boolean ok = false;
            String top = "";
            for (int i = 0; i < 2 && !ok; i++) {
                sh(adb, "input keyevent KEYCODE_HOME");
                Thread.sleep(2500);
                top = topActivity(adb);
                ok = top.startsWith(BuildInfo.LAUNCHER + "/");      // any screen of our launcher, not only the main one
                dbg("home check " + i + ": " + top);
            }
            if (ok) {
                // And it stays there. Something else coming up over it (a permission dialog, a notice) is fine;
                // the stock Home or the empty FallbackHome coming back is not. Sunwind 28.10 rolled back here
                // without saying what it saw, hence the name in the note.
                Thread.sleep(3000);
                top = topActivity(adb);
                dbg("home hold: " + top);
                ok = !top.isEmpty() && !top.startsWith(stock + "/") && !top.contains("FallbackHome");
            }
            if (!ok) {
                note(T.t("⚠ Новая оболочка не удержалась на экране — возвращаю заводской «Домой».") + " " + T.t("На экране было: ") + (top.isEmpty() ? "—" : top));
                dbgState(adb, "home switch-off failed");
                rollbackStockHome(adb);
                return false;
            }
            store.commitStr("homeTx", null);
            note(T.t("✔ Заводской «Домой» отключён, по «Домой» открывается наша оболочка"));
            launcherConfig(adb);
            return true;
        } catch (Exception e) {
            rollbackStockHome(adb);
            throw e;
        }
    }

    /** Put the stock Home back if a switch-off didn't finish; checked by asking Android, marker cleared only then. */
    private void rollbackStockHome(Adb adb) throws Exception {
        String stock = store.str("homeTx");
        if (stock == null) return;
        note(T.t("Возвращаю заводской «Домой»: ") + stock);
        sh(adb, "pm enable " + stock);
        String[] l = adb.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME " + stock).trim().split("\n");
        String comp = l[l.length - 1].trim();
        if (comp.startsWith(stock + "/")) sh(adb, "cmd package set-home-activity --user 0 " + comp);
        if (reallyOff(adb, java.util.Collections.singleton(stock)).isEmpty()) {
            Set<String> d = store.disabled(); d.remove(stock); store.setDisabled(d);
            store.commitStr("homeTx", null);
            sh(adb, "input keyevent KEYCODE_HOME");
            note(T.t("Заводской «Домой» включён обратно."));
        } else note(T.t("⚠ Заводской «Домой» пока не включился, попробую при следующем запуске."));
    }

    /** The exact component on screen, not just a package name somewhere in the line (audit A4). */

    boolean installLauncher() {
        synchronized (LOCK) {
            note(T.t("Ставлю новую оболочку"));
            Adb adb = connect(3);
            if (adb == null) { save(); return false; }
            try {
                File apk = new File(c.getExternalFilesDir(null), "tvhome.apk");
                try (InputStream in = c.getAssets().open("tvhome.apk"); OutputStream out = new FileOutputStream(apk)) {
                    byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n);
                }
                String stock = stockHome(adb);
                if (stock != null) store.setStr("stockHome", stock);
                note(T.t("Заводская оболочка: ") + stock);
                // system_server may not read app storage (Android 11 sdcardfs): hand it over via /data/local/tmp.
                String tmp = "/data/local/tmp/tvj-tvhome.apk";
                sh(adb, "rm -f " + tmp);
                sh(adb, "cp " + apk.getAbsolutePath() + " " + tmp);
                apk.delete();
                // Shared storage can be rewritten by other apps between our write and the copy, so check the
                // copy itself: /data/local/tmp is writable only by the shell user.
                String want = sha256Asset("tvhome.apk");
                String got = adb.shell("sha256sum " + tmp).trim().split("\\s+")[0];
                if (!want.equalsIgnoreCase(got)) {
                    sh(adb, "rm -f " + tmp);
                    note(T.t("Файл оболочки не совпал с оригиналом — установка отменена"));
                    return false;
                }
                String r = sh(adb, "pm install -r -g " + tmp);
                if (r.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")) {
                    // An older copy signed with another key (a debug build): it can only be replaced, not updated.
                    // Home is still the stock one at this point, so removing ours leaves the TV usable.
                    note(T.t("Старая оболочка подписана другим ключом — ставлю заново (её настройки сбросятся)"));
                    sh(adb, "pm uninstall --user 0 " + BuildInfo.LAUNCHER);
                    sh(adb, "pm uninstall " + BuildInfo.LAUNCHER);
                    r = sh(adb, "pm install -r -g " + tmp);
                }
                sh(adb, "rm -f " + tmp);
                if (r.contains("VERIFICATION_FAILURE") || r.contains("NO_CERTIFICATES") || r.contains("INSTALL_FAILED_ABORTED")) {
                    note(T.t("Эта прошивка не принимает сторонние приложения (") + firstLine(r) + T.t("). Новую оболочку поставить нельзя, остальное работает."));
                    return false;
                }
                if (!r.contains("Success")) { note(T.t("Установка не удалась")); return false; }
                sh(adb, "cmd package compile -m speed -f " + BuildInfo.LAUNCHER);
                sh(adb, "cmd package set-home-activity --user 0 " + BuildInfo.LAUNCHER_HOME);
                sh(adb, "am start -n " + BuildInfo.LAUNCHER_HOME);
                String claim = adb.shell("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME");
                if (claim.contains("setupwraith")) {
                    String cname = adb.shell("dumpsys wifi | grep -o 'cname=[^ ]*'");
                    note(cname.contains("setupwraith")
                            ? T.t("⚠ Мастер настройки Google владеет вашей сетью Wi-Fi: его не трогаю, иначе Android удалит сеть. Если «Домой» откроет не нас — забудьте сеть и подключитесь заново в Настройках.")
                            : T.t("Мастер настройки Google тоже претендует на «Домой» — проверяю, что победили мы."));
                }
                // Google's stock Home outranks ours, so it has to be switched off for "Home" to reach us. It stays
                // off only once "Home" has really opened our launcher; otherwise it is switched back on at once.
                String s = store.str("stockHome");
                boolean stockOff = false;
                if (s != null) for (String safe : BuildInfo.SAFE_TO_DISABLE_HOME) {
                    if (!safe.equals(s)) continue;
                    // Marker on disk first: whatever happens next (an error, a power cut), the stock Home comes back.
                    if (!store.commitStr("homeTx", s)) { note(T.t("Не смог записать отметку на диск — заводской «Домой» не трогаю.")); break; }
                    sh(adb, "pm disable-user --user 0 " + s);
                    stockOff = !reallyOff(adb, java.util.Collections.singleton(s)).isEmpty();
                    if (!stockOff) store.commitStr("homeTx", null);
                }
                sh(adb, "cmd package set-home-activity --user 0 " + BuildInfo.LAUNCHER_HOME);
                String now = homePackage(adb);
                boolean onScreen = false;
                String top = "";
                for (int i = 0; i < 2 && !onScreen; i++) {
                    sh(adb, "input keyevent KEYCODE_HOME");
                    Thread.sleep(2500);
                    top = adb.shell("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'");
                    onScreen = top.contains(BuildInfo.LAUNCHER + "/");
                }
                if (!onScreen) {
                    note(T.t("По «Домой» открылось: ") + firstLine(top.trim()));
                    if (stockOff) rollbackStockHome(adb);
                    else if (s != null) {
                        String[] l = adb.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME " + s).trim().split("\n");
                        String comp = l[l.length - 1].trim();
                        if (comp.startsWith(s + "/")) sh(adb, "cmd package set-home-activity --user 0 " + comp);
                    }
                    store.setFlag("launcher", false);
                    sh(adb, "input keyevent KEYCODE_HOME");
                    note(T.t("⚠ Новая оболочка не открылась по «Домой», поэтому заводская осталась на месте. Новую можно открыть из списка приложений."));
                    return false;
                }
                if (stockOff) { Set<String> d = store.disabled(); d.add(s); store.setDisabled(d); store.commitStr("homeTx", null); }
                store.setFlag("launcher", true);
                launcherConfig(adb);
                note(T.t("Главный экран теперь: ") + now + T.t(" — и он на экране ✔"));
                note(T.t("Готово"));
                return true;
            } catch (Exception e) {
                note(T.t("Ошибка: ") + e.getClass().getSimpleName() + " " + e.getMessage());
                // Stock Home already off and something broke before the check: put it back now, or at the next run.
                try { if (store.str("homeTx") != null) rollbackStockHome(adb); } catch (Exception ignored) {}
                return false;
            } finally { adb.close(); save(); }
        }
    }

    private static final String LAUNCHER_CONFIG = "/sdcard/Android/data/" + BuildInfo.LAUNCHER + "/files/config.json";
    private static final String CONFIG_MARK = "written by TV Janitor";

    /**
     * Settings for our launcher (its config.json), written only when there is none or the one there is ours.
     * Yandex TV: HDMI and AV play in com.yandex.tv.live, which takes live-tv:// links; the content:// one sends
     * it to the Yandex home screen's TV section and, with that switched off, it crashes (Sunwind, 28.10). And the
     * light keyboard's settings tile is kept off the home screen.
     */
    private void launcherConfig(Adb adb) {
        try {
            if (!store.flag("launcher", false)) return;
            String old = adb.shell("cat " + LAUNCHER_CONFIG + " 2>/dev/null");
            if (!old.trim().isEmpty() && !old.contains(CONFIG_MARK)) { dbg("launcher config is someone else's, left as is"); return; }
            StringBuilder j = new StringBuilder("{\"_comment\": \"" + CONFIG_MARK + "\"");
            if (installed("com.yandex.tv.live"))
                j.append(", \"input_uri_scheme\": \"live-tv\", \"live_tv_package\": \"com.yandex.tv.live\"");
            j.append(", \"hidden_apps\": [\"com.mediatek.wwtv.tvcenter\", \"com.mitv.gallery\"");
            if (installed(KEYBOARD)) j.append(", \"").append(KEYBOARD).append("\"");
            j.append("]}");
            if (old.trim().equals(j.toString())) return;
            adb.shell("mkdir -p /sdcard/Android/data/" + BuildInfo.LAUNCHER + "/files");
            adb.shell("echo '" + j + "' > " + LAUNCHER_CONFIG);
            adb.shell("am force-stop " + BuildInfo.LAUNCHER);  // it reads the file when it starts; a restart, not 💤
            note(T.t("Настроил новую оболочку под этот телевизор (источники HDMI, скрытые плитки)"));
        } catch (Exception e) { dbg("launcher config: " + e); }
    }

    /** Started by Android at boot whatever its state: switched off, it only crashes over and over (Sunwind's platform services). */
    private boolean persistent(String p) {
        try {
            return (c.getPackageManager().getApplicationInfo(p, android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS).flags
                    & android.content.pm.ApplicationInfo.FLAG_PERSISTENT) != 0;
        } catch (Exception e) { return false; }
    }

    /** What the owner said they use, by package: never switched off behind their back. */
    private boolean needed(String p) {
        Features.F f = Features.of(p);
        return f != null && store.set("features").contains(f.id);
    }

    /**
     * Of the chosen packages, the persistent ones are never switched off: they join the protected ones and are held
     * back instead (holdBack). One switched off earlier is switched back on.
     */
    private Set<String> sparePersistent(Adb adb, Collection<String> chosen) throws Exception {
        Set<String> eternal = new java.util.TreeSet<>();
        for (String p : chosen) if (persistent(p)) eternal.add(p);
        if (eternal.isEmpty()) return eternal;
        for (String p : reallyOff(adb, eternal)) sh(adb, "pm enable " + p);
        Set<String> prot = store.set("protectedPkgs"), known = store.set("persistentPkgs");
        Set<String> fresh = new java.util.TreeSet<>(eternal); fresh.removeAll(known);
        prot.addAll(eternal); known.addAll(eternal);
        store.setSet("protectedPkgs", prot); store.setSet("persistentPkgs", known);
        if (!fresh.isEmpty()) note(T.t("♾ Android запускает их при каждой загрузке, даже отключённые (и тогда они падают по кругу): не отключаю, а усыпляю: ")
                + String.join(", ", fresh));
        return eternal;
    }

    /** What the TV can't work without (Detector.core) never stays switched off, whatever an older version chose. */
    private void neverOff(Adb adb, Set<String> disable) throws Exception {
        Set<String> d = store.disabled(), bad = new java.util.TreeSet<>();
        for (String p : d) if (Detector.core(p)) bad.add(p);
        if (disable != null) for (String p : disable) if (Detector.core(p)) bad.add(p);
        if (bad.isEmpty()) return;
        for (String p : reallyOff(adb, bad)) sh(adb, "pm enable " + p);
        d.removeAll(bad); store.setDisabled(d);
        if (disable != null) disable.removeAll(bad);
        note(T.t("Без этого телевизор не работает — включаю и больше не трогаю: ") + String.join(", ", bad));
    }

    /**
     * Something switched off that still crashes again and again since boot (a persistent service no flag gave
     * away): switched back on and kept out of the choice, so the TV doesn't spend the day restarting it.
     */
    private void loopsSinceBoot(Adb adb) throws Exception {
        Set<String> off = store.disabled();
        Map<String, Integer> n = new HashMap<>();
        // Same boot as our last run: crashes from before it happened while the package was still on (Novex: the
        // Yandex keyboard crashes 3 times at every boot, on or off). Only what came after counts then.
        String since = null;
        if (!rebootedSinceLastRun()) {
            try { since = new java.text.SimpleDateFormat("MM-dd HH:mm:ss.000", java.util.Locale.US)
                    .format(new java.util.Date(Long.parseLong(store.str("lastRunAt")))); } catch (Exception ignored) {}
        }
        // One crash of a service can be logged twice (its "Process:" line and Android's "Scheduling restart"): count
        // each kind apart and take the larger, never the sum.
        Map<String, Integer> restarts = new HashMap<>();
        for (String[] f : crashes(adb, since)) {
            String pkg = f[0].contains(":") ? f[0].substring(0, f[0].indexOf(':')) : f[0];
            if (off.contains(pkg)) (f[1].contains("Scheduling restart") ? restarts : n).merge(pkg, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : restarts.entrySet()) n.merge(e.getKey(), e.getValue(), Math::max);
        Set<String> loop = new java.util.TreeSet<>();
        // A real loop restarts every few seconds (Sunwind: 676 times); a handful at boot is not one. One look, not a
        // watch: a slow loop below 10 at this moment is not caught.
        for (Map.Entry<String, Integer> e : n.entrySet()) if (e.getValue() >= 10) loop.add(e.getKey());
        if (loop.isEmpty()) return;
        for (String p : loop) sh(adb, "pm enable " + p);
        off.removeAll(loop); store.setDisabled(off);
        Set<String> cr = store.set("crashers"); cr.addAll(loop); store.setSet("crashers", cr);
        note(T.t("♻ Падали снова и снова (10 раз и больше) с момента загрузки, хотя отмечены на отключение — включил обратно: ") + String.join(", ", loop));
    }

    /** LeanKey: a light keyboard made for the remote, bundled with the full edition (MIT, built from source). */
    static final String KEYBOARD = "com.liskovsoft.leankeyboard", KEYBOARD_IME = KEYBOARD + "/.ime.LeanbackImeService";

    static boolean hasKeyboard(Context c) {
        try (InputStream in = c.getAssets().open("leankey.apk")) { return true; } catch (Exception e) { return false; }
    }

    private static String currentIme(Adb adb) throws Exception { return adb.shell("settings get secure default_input_method").trim(); }

    private static boolean isOurIme(String ime) {
        return ime.equals(KEYBOARD_IME) || ime.equals(KEYBOARD + "/com.liskovsoft.leankeyboard.ime.LeanbackImeService");
    }

    /**
     * Put LeanKey in and make it the keyboard; the old one stays as it is, and only once LeanKey is really the
     * default can the old one be switched off in the list (only the keyboard in use is locked).
     */
    boolean installKeyboard() {
        synchronized (LOCK) {
            note(T.t("Ставлю лёгкую клавиатуру"));
            Adb adb = connect(3);
            if (adb == null) { save(); return false; }
            try {
                snapshot(adb);
                String was = currentIme(adb);
                if (!isOurIme(was) && store.str("snap_ime") == null) store.setStr("snap_ime", was);
                if (!installed(KEYBOARD)) {
                    File apk = new File(c.getExternalFilesDir(null), "leankey.apk");
                    try (InputStream in = c.getAssets().open("leankey.apk"); OutputStream out = new FileOutputStream(apk)) {
                        byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    }
                    String tmp = "/data/local/tmp/tvj-leankey.apk";
                    sh(adb, "rm -f " + tmp);
                    sh(adb, "cp " + apk.getAbsolutePath() + " " + tmp);
                    apk.delete();
                    String got = adb.shell("sha256sum " + tmp).trim().split("\\s+")[0];
                    if (!sha256Asset("leankey.apk").equalsIgnoreCase(got)) {
                        sh(adb, "rm -f " + tmp);
                        note(T.t("Файл клавиатуры не совпал с оригиналом — установка отменена"));
                        return false;
                    }
                    String r = sh(adb, "pm install -r -g " + tmp);
                    sh(adb, "rm -f " + tmp);
                    if (!r.contains("Success")) { note(T.t("Установка не удалась")); return false; }
                }
                sh(adb, "pm enable " + KEYBOARD);
                sh(adb, "ime enable " + KEYBOARD_IME);
                sh(adb, "ime set " + KEYBOARD_IME);
                if (!isOurIme(currentIme(adb))) {
                    note(T.t("⚠ Клавиатура поставилась, но Android не сделал её основной. Прежняя остаётся."));
                    return false;
                }
                // Its settings screen is also a launcher tile: our launcher is told to hide it.
                launcherConfig(adb);
                note(T.t("✔ Лёгкая клавиатура теперь основная. Прежнюю можно отключить в списке."));
                return true;
            } catch (Exception e) {
                note(T.t("Ошибка: ") + e.getClass().getSimpleName() + " " + e.getMessage());
                return false;
            } finally { adb.close(); save(); }
        }
    }

    /** Back to the keyboard that was in use before LeanKey; switched back on first if it was turned off. */
    boolean restoreKeyboard() {
        synchronized (LOCK) {
            note(T.t("Возвращаю прежнюю клавиатуру"));
            Adb adb = connect(3);
            if (adb == null) { save(); return false; }
            try { return backToOldIme(adb); }
            catch (Exception e) {
                note(T.t("Ошибка: ") + e.getClass().getSimpleName() + " " + e.getMessage());
                return false;
            } finally { adb.close(); save(); }
        }
    }

    private boolean backToOldIme(Adb adb) throws Exception {
        String old = store.str("snap_ime");
        if (old == null || !old.contains("/") || !isOurIme(currentIme(adb))) return true;
        String pkg = old.substring(0, old.indexOf('/'));
        sh(adb, "pm enable " + pkg);
        Set<String> d = store.disabled(); d.remove(pkg); store.setDisabled(d);
        sh(adb, "ime enable " + old);
        sh(adb, "ime set " + old);
        if (!old.equals(currentIme(adb))) { note(T.t("⚠ Прежняя клавиатура не стала основной: ") + old); return false; }
        store.setStr("snap_ime", null);
        note(T.t("✔ Прежняя клавиатура снова основная"));
        return true;
    }

    boolean restoreLauncher() {
        synchronized (LOCK) {
            note(T.t("Возвращаю старую оболочку"));
            Adb adb = connect(3);
            if (adb == null) { save(); return false; }
            try {
                String s = store.str("stockHome");
                store.setFlag("launcher", false);
                if (s != null) {
                    sh(adb, "pm enable " + s);
                    Set<String> d = store.disabled(); d.remove(s); store.setDisabled(d);
                    String comp = adb.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME " + s).trim();
                    String[] lines = comp.split("\n");
                    String last = lines[lines.length - 1].trim();
                    if (last.contains("/")) sh(adb, "cmd package set-home-activity --user 0 " + last);
                }
                sh(adb, "am start -a android.intent.action.MAIN -c android.intent.category.HOME");
                note(T.t("Готово"));
                return true;
            } catch (Exception e) {
                note(T.t("Ошибка: ") + e.getClass().getSimpleName() + " " + e.getMessage());
                return false;
            } finally { adb.close(); save(); }
        }
    }

    /** The factory Home even when it is already disabled; never FallbackHome or the chooser. */
    private String stockHome(Adb adb) throws Exception {
        android.content.pm.PackageManager pm = c.getPackageManager();
        for (String k : BuildInfo.KNOWN_STOCK_HOMES) {
            try { pm.getApplicationInfo(k, android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS); return k; }
            catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
        }
        String now = homePackage(adb);
        if (now == null || now.equals("android") || now.equals(BuildInfo.LAUNCHER) || now.equals("com.android.tv.settings")) return null;
        return now;
    }

    private boolean installed(String p) {
        try { c.getPackageManager().getApplicationInfo(p, android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS); return true; }
        catch (Exception e) { return false; }
    }

    private static String homeComponent(Adb adb) throws Exception {
        String[] lines = adb.shell("cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN").trim().split("\n");
        return lines[lines.length - 1].trim();
    }

    private static String homePackage(Adb adb) throws Exception {
        String[] lines = adb.shell("cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN").trim().split("\n");
        String last = lines[lines.length - 1].trim();
        return last.contains("/") ? last.substring(0, last.indexOf('/')) : last;
    }


    /** An app store the user may install. The signing certificate is pinned: whatever the server sends, only
     *  an APK signed by the real developer gets installed. */
    static final class StoreApp {
        final String name, pkg, cert, about; final String[] urls;
        StoreApp(String name, String pkg, String cert, String about, String... urls) {
            this.name = name; this.pkg = pkg; this.cert = cert; this.about = about; this.urls = urls;
        }
    }

    static final StoreApp[] STORES = {
        new StoreApp("Aurora Store", "com.aurora.store",
                "4c626157ad02bda3401a7263555f68a79663fc3e13a4d4369a12570941aa280f",
                T.t("каталог Google Play без аккаунта Google. Интерфейс телефонный, пультом не очень удобно"),
                "gitlab:AuroraOSS/AuroraStore"),
        new StoreApp("F-Droid", "org.fdroid.fdroid",
                "43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab",
                T.t("бесплатные приложения с открытым кодом, без рекламы и слежки"),
                "https://f-droid.org/F-Droid.apk"),
    };

    boolean installStore(StoreApp app) {
        if (Edition.LITE) return false;   // Lite downloads nothing
        synchronized (LOCK) {
            note(T.t("Ставлю ") + app.name);
            File priv = new File(c.getFilesDir(), "store.apk");
            try {
                String url = null;
                for (String u : app.urls) { url = u.startsWith("gitlab:") ? gitlabLatestApk(u.substring(7)) : u; if (url != null) break; }
                if (url == null) { note(T.t("Не нашёл файл ") + app.name + T.t(" на сервере. Есть ли интернет?")); return false; }
                note(T.t("Скачиваю: ") + url);
                download(url, priv, (d, t) -> { if (prog != null) prog.progress((int) (d / 1024), (int) Math.max(t / 1024, d / 1024 + 1), T.t("Скачиваю: ") + d / 1048576 + (t > 0 ? " / " + t / 1048576 : "") + T.t(" МБ")); });
                // Check who signed it before anything else.
                String signer = signerSha(priv);
                android.content.pm.PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(priv.getAbsolutePath(), 0);
                if (pi == null || !app.pkg.equals(pi.packageName) || !app.cert.equalsIgnoreCase(signer)) {
                    note(T.t("⚠ Файл подписан не разработчиком ") + app.name + " (" + (signer == null ? T.t("нет подписи") : signer.substring(0, 16) + "…") + T.t(") — установка отменена"));
                    return false;
                }
                note(T.t("Подпись разработчика совпала ✔ (") + app.name + " " + pi.versionName + ")");
                String want = sha256File(priv);
                Adb adb = connect(3);
                if (adb == null) { save(); return false; }
                File ext = new File(c.getExternalFilesDir(null), "store.apk");
                copy(priv, ext);
                String tmp = "/data/local/tmp/tvj-store.apk";
                sh(adb, "rm -f " + tmp);
                sh(adb, "cp " + ext.getAbsolutePath() + " " + tmp);
                ext.delete();
                String got = adb.shell("sha256sum " + tmp).trim().split("\\s+")[0];
                if (!want.equalsIgnoreCase(got)) { sh(adb, "rm -f " + tmp); note(T.t("Файл изменился при копировании — установка отменена")); return false; }
                String r = sh(adb, "pm install -r " + tmp);
                sh(adb, "rm -f " + tmp);
                if (r.contains("UPDATE_INCOMPATIBLE")) { note(T.t("Уже стоит другая сборка ") + app.name + T.t(" с другой подписью: удалите её и попробуйте снова")); return false; }
                if (r.contains("VERIFICATION_FAILURE") || r.contains("INSTALL_FAILED_ABORTED")) { note(T.t("Эта прошивка не принимает сторонние приложения (") + firstLine(r) + ")"); return false; }
                if (!r.contains("Success")) { note(T.t("Установка не удалась: ") + firstLine(r)); return false; }
                storePermissions(adb, app.pkg);
                note(app.name + T.t(" установлен ✔ Открываю его. Если на главном экране его не видно, открывайте через «📦 Поставить магазин»."));
                note(T.t("Готово"));
                return true;
            } catch (Exception e) {
                note(T.t("Ошибка: ") + e.getMessage() + T.t(". Есть ли интернет?"));
                return false;
            } finally {
                priv.delete();
                save();
            }
        }
    }

    /** An app store needs a few special permissions that Android TV's Settings often has no screen for, so the
     *  store's own "grant it" buttons lead nowhere. Granted over ADB, only for a store the owner chose to install. */
    private void storePermissions(Adb adb, String pkg) throws Exception {
        note(T.t("Выдаю магазину нужные разрешения: установка приложений, фоновые загрузки, хранилище"));
        sh(adb, "appops set " + pkg + " REQUEST_INSTALL_PACKAGES allow");
        if (android.os.Build.VERSION.SDK_INT >= 30) sh(adb, "appops set " + pkg + " MANAGE_EXTERNAL_STORAGE allow");
        sh(adb, "cmd deviceidle whitelist +" + pkg);
        if (android.os.Build.VERSION.SDK_INT >= 33) sh(adb, "pm grant " + pkg + " android.permission.POST_NOTIFICATIONS");
        if (android.os.Build.VERSION.SDK_INT >= 31) sh(adb, "pm set-app-links-user-selection --user 0 --package " + pkg + " true all");
    }

    /** For a store installed earlier (or by hand): grant the same permissions again. */
    boolean grantStore(String pkg) {
        synchronized (LOCK) {
            Adb adb = connect(2);
            if (adb == null) { save(); return false; }
            try { storePermissions(adb, pkg); note(T.t("Готово")); return true; }
            catch (Exception e) { note(T.t("Ошибка: ") + e.getMessage()); return false; }
            finally { adb.close(); save(); }
        }
    }

    /** Latest release APK of a GitLab project, from its public API. The signature check decides anyway. */
    private String gitlabLatestApk(String project) {
        try {
            String json = fetch("https://gitlab.com/api/v4/projects/" + project.replace("/", "%2F") + "/releases/permalink/latest");
            // Aurora attaches the APKs as uploads linked from the release text: [AuroraStore-4.8.4.apk](/uploads/<id>/AuroraStore-4.8.4.apk)
            // (the hw and preload variants are for special devices). Older releases used asset links instead.
            Matcher up = Pattern.compile("\\((/uploads/[0-9a-f]+/" + Pattern.quote(project.substring(project.indexOf('/') + 1)) + "-[0-9.]+\\.apk)\\)").matcher(json);
            if (up.find()) {
                // GitLab serves project uploads at /-/project/<numeric id>/uploads/...; the old /<group>/<name>/uploads path is 404.
                Matcher id = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(fetch("https://gitlab.com/api/v4/projects/" + project.replace("/", "%2F")));
                return id.find() ? "https://gitlab.com/-/project/" + id.group(1) + up.group(1) : "https://gitlab.com/" + project + up.group(1);
            }
            Matcher m = Pattern.compile("\"(?:direct_asset_url|url)\"\\s*:\\s*\"(https://[^\"]+\\.apk)\"").matcher(json);
            String best = null;
            while (m.find()) {
                String u = m.group(1).replace("\\/", "/");
                if (u.toLowerCase(Locale.ROOT).contains("debug")) continue;
                if (best == null || u.contains("direct")) best = u;
            }
            if (best == null) note("GitLab: " + T.t("в выпуске нет APK"));
            return best;
        } catch (Exception e) { note("GitLab: " + e.getClass().getSimpleName() + " " + e.getMessage()); return null; }
    }

    private static String fetch(String url) throws Exception {
        java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        h.setConnectTimeout(15000); h.setReadTimeout(30000);
        h.setRequestProperty("User-Agent", "TV-Janitor");
        try (InputStream in = h.getInputStream()) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[16384]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toString("UTF-8");
        }
    }

    interface Bytes { void got(long done, long total); }

    private static void download(String url, File to, Bytes cb) throws Exception {
        for (int hop = 0; hop < 6; hop++) {
            java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            h.setInstanceFollowRedirects(false);
            h.setConnectTimeout(15000); h.setReadTimeout(60000);
            h.setRequestProperty("User-Agent", "TV-Janitor");
            int code = h.getResponseCode();
            if (code >= 300 && code < 400 && h.getHeaderField("Location") != null) {
                url = new java.net.URL(new java.net.URL(url), h.getHeaderField("Location")).toString();
                if (!url.startsWith("https://")) throw new Exception(T.t("небезопасная ссылка"));
                continue;
            }
            if (code != 200) throw new Exception(T.t("сервер ответил ") + code);
            long total = 0, size = h.getContentLengthLong(), shown = 0;
            try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                byte[] b = new byte[65536]; int n;
                while ((n = in.read(b)) > 0) {
                    out.write(b, 0, n); total += n;
                    if (total > 200L << 20) throw new Exception(T.t("файл слишком большой"));
                    if (cb != null && total - shown > 262144) { shown = total; cb.got(total, size); }
                }
            }
            return;
        }
        throw new Exception(T.t("слишком много перенаправлений"));
    }

    @SuppressWarnings("deprecation")
    private String signerSha(File apk) throws Exception {
        android.content.pm.PackageManager pm = c.getPackageManager();
        android.content.pm.Signature[] sigs;
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            android.content.pm.PackageInfo pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(), android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
            if (pi == null || pi.signingInfo == null) return null;
            if (pi.signingInfo.hasMultipleSigners()) return null;
            sigs = pi.signingInfo.getApkContentsSigners();
        } else {
            android.content.pm.PackageInfo pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(), android.content.pm.PackageManager.GET_SIGNATURES);
            if (pi == null) return null;
            sigs = pi.signatures;
        }
        if (sigs == null || sigs.length != 1) return null;
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        return hex(md.digest(sigs[0].toByteArray()));
    }

    private static String sha256File(File f) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) md.update(b, 0, n); }
        return hex(md.digest());
    }

    private static void copy(File a, File b) throws Exception {
        try (InputStream in = new FileInputStream(a); OutputStream out = new FileOutputStream(b)) {
            byte[] buf = new byte[65536]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static String hex(byte[] d) { StringBuilder sb = new StringBuilder(); for (byte x : d) sb.append(String.format("%02x", x)); return sb.toString(); }

    private String sha256Asset(String name) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream in = c.getAssets().open(name)) {
            byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static File logFile(Context c) {
        File d = c.getExternalFilesDir(null);
        return new File(d != null ? d : c.getFilesDir(), "log.txt");
    }

    private void save() {
        try {
            File lf = logFile(c);
            String old = lf.isFile() ? new String(readAll(lf), StandardCharsets.UTF_8) : "";
            if (old.length() > 30000) old = old.substring(old.length() - 30000);
            try (FileOutputStream o = new FileOutputStream(lf)) { o.write((old + "\n" + log).getBytes(StandardCharsets.UTF_8)); }
            log.setLength(0);
            if (dbgLog.length() > 0) {
                File df = new File(lf.getParentFile(), "debug.txt");
                String o2 = df.isFile() ? new String(readAll(df), StandardCharsets.UTF_8) : "";
                if (o2.length() > 300000) o2 = o2.substring(o2.length() - 300000);
                try (FileOutputStream o = new FileOutputStream(df)) { o.write((o2 + "\n" + dbgLog).getBytes(StandardCharsets.UTF_8)); }
                dbgLog.setLength(0);
            }
        } catch (Exception ignored) {}
    }

    static String readLog(Context c) {
        try {
            String s = new String(readAll(logFile(c)), StandardCharsets.UTF_8);
            return s.length() > 6000 ? s.substring(s.length() - 6000) : s;
        } catch (Exception e) { return T.t("Журнал пуст"); }
    }

    static byte[] readAll(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        }
    }
}
