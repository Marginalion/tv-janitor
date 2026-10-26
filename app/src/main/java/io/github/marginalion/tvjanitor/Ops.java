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

    Ops(Context c, Say say) { this.c = c; this.store = new Store(c); this.say = say; }

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
            return r.contains("disabled") ? T.t("− отключено: ") + pkg : r.contains("Unknown package") ? T.t("· нет на ТВ: ") + pkg : "✗ " + pkg + ": " + why(r);
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
        if (store.has("snap_disabled")) return;
        java.util.Set<String> d = new java.util.HashSet<>();
        for (String l : adb.shell("pm list packages -d").split("\n")) { l = l.trim(); if (l.startsWith("package:")) d.add(l.substring(8)); }
        store.setSet("snap_disabled", d);
        for (String k : new String[]{"window_animation_scale", "transition_animation_scale", "animator_duration_scale",
                "activity_manager_constants", "private_dns_mode", "private_dns_specifier"})
            store.setStr("snap_" + k, adb.shell("settings get global " + k).trim());
        store.setStr("snap_home", homeComponent(adb));
        note(T.t("Снимок «как было» сохранён: отключено с завода ") + d.size() + T.t(" пакетов"));
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
    boolean reboot() {
        synchronized (LOCK) {
            Adb adb = connect(2);
            if (adb == null) { save(); return false; }
            try {
                store.setFlag("openAfterBoot", true);
                note(T.t("Перезагружаю телевизор…"));
                save();
                adb.shell("reboot");
                return true;
            } catch (Exception e) { return true; } finally { adb.close(); }
        }
    }

    static boolean adbInsecure(Adb adb) {
        try { return "0".equals(adb.shell("getprop ro.adb.secure").trim()); } catch (Exception e) { return false; }
    }

    private static int countLines(Adb adb, String cmd) {
        try { String r = adb.shell(cmd).trim(); return r.isEmpty() ? 0 : Integer.parseInt(r.split("\\s+")[0]); } catch (Exception e) { return 0; }
    }

    /** Signs that something keeps falling over: crash buffer entries and services Android restarts. */
    private static int trouble(Adb adb) {
        return countLines(adb, "logcat -d -b crash | grep -c -E 'FATAL|Fatal signal'")
                + countLines(adb, "logcat -d -b main -b system | grep -c 'Scheduling restart of crashed service'");
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

    /** After boot: put every choice back (some firmware re-enables packages at each boot). */
    boolean bootRun(String reason) {
        synchronized (LOCK) {
            note(T.t("Старт (") + reason + ")");
            Adb adb = connect(12);
            if (adb == null) { save(); return false; }
            try {
                note(T.t("Подключено"));
                boolean launcher = store.flag("launcher", false);
                if (launcher) sh(adb, "cmd package set-home-activity --user 0 " + BuildInfo.LAUNCHER_HOME);
                java.util.Set<String> nowOff = new java.util.HashSet<>();
                for (String l : adb.shell("pm list packages -d").split("\n")) { l = l.trim(); if (l.startsWith("package:")) nowOff.add(l.substring(8)); }
                java.util.Set<String> back = new java.util.HashSet<>();
                // Only what an earlier run really switched off counts as "the firmware put it back"; a choice
                // that was saved but never applied (the app was closed mid-way) is simply applied now.
                java.util.Set<String> applied = store.set("appliedOff");
                int pending = 0;
                for (String p : store.disabled()) if (!nowOff.contains(p) && installed(p)) { if (applied.contains(p)) back.add(p); else pending++; }
                if (pending > 0) note(T.t("Применяю выбор, который ещё не применялся: ") + pending);
                store.setSet("firmwareBack", back);
                if (!back.isEmpty()) note(T.t("Прошивка сама включила обратно при загрузке: ") + back.size() + T.t(" — отключаю снова")
                        + "  (" + String.join(", ", new java.util.TreeSet<>(back)) + ")");
                // Only what is on now: some packages are disabled by the firmware itself, and disable-user on
                // those only throws (org.droidtv.freeviewplayers on Philips).
                java.util.Set<String> todo = new java.util.TreeSet<>(store.disabled()); todo.removeAll(nowOff);
                disableAll(adb, todo);
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
                String used = usedLine(adb);
                note(used);
                store.setStr("bootReport", T.t("После перезагрузки: ") + (used.isEmpty() ? "" : used + ". ")
                        + (back.isEmpty() ? T.t("Прошивка ничего не вернула сама.") : T.t("Прошивка вернула сама ") + back.size() + T.t(", Уборщик отключил снова."))
                        + T.t(" Отключено: ") + store.disabled().size() + T.t(", не запускаются сами: ") + store.sleeping().size() + ".");
                Report.saveIfConsented(c);
                store.setFlag("reportUnseen", true);
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
                int batches = (disable.size() + 4) / 5;
                total = enable.size() + disable.size() + batches * 2 + 3 * sleep.size() + 2 * wake.size() + 7;
                done = 0;
                for (String p : enable) sh(adb, "pm enable " + p);
                // CleanYourTV: small batches, check between them. A batch that starts a crash loop is put back.
                List<String> todo = new ArrayList<>(disable);
                java.util.Set<String> rolled = new java.util.HashSet<>();
                for (int i = 0; i < todo.size(); i += 5) {
                    List<String> batch = todo.subList(i, Math.min(i + 5, todo.size()));
                    int before = trouble(adb);
                    int n = i / 5 + 1;
                    step(T.t("Порция ") + n + T.t(" из ") + batches + T.t(": отключаю ") + batch.size());
                    disableAll(adb, batch);
                    if (prog != null) prog.progress(done, total, T.t("Порция ") + n + T.t(" из ") + batches + T.t(" · проверяю, всё ли в порядке…"));
                    Thread.sleep(8000);
                    step(T.t("Порция ") + n + T.t(" из ") + batches + T.t(" проверена"));
                    int after = trouble(adb);
                    if (after - before >= 2) {
                        note(T.t("⚠ После этой порции что-то начало падать (") + (after - before) + T.t(" сбоев) — возвращаю её: ") + String.join(", ", batch));
                        for (String p : batch) sh(adb, "pm enable " + p);
                        rolled.addAll(batch);
                    }
                }
                if (!rolled.isEmpty()) {
                    Set<String> d = store.disabled(); d.removeAll(rolled); store.setDisabled(d);
                    store.setSet("rolledBack", rolled);
                    java.util.Set<String> all = store.set("rolledAll"); all.addAll(rolled); store.setSet("rolledAll", all);
                    disable.removeAll(rolled);
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
                Set<String> notOn = new java.util.TreeSet<>(reallyOff(adb, enable));
                if (!notOff.isEmpty()) note(T.t("⚠ Не отключилось: ") + notOff.size() + "  (" + String.join(", ", notOff) + ")"
                        + T.t(". Попробую ещё раз после перезагрузки."));
                if (!notOn.isEmpty()) note(T.t("⚠ Не включилось: ") + notOn.size() + "  (" + String.join(", ", notOn) + ")");
                if (prog != null) prog.progress(total, total, T.t("Готово"));
                note(T.t("Итог: ") + T.t("отключено ") + off.size() + (notOff.isEmpty() ? "" : T.t(" из ") + disable.size())
                        + T.t(", включено ") + (enable.size() - notOn.size()) + (notOn.isEmpty() ? "" : T.t(" из ") + enable.size())
                        + T.t(", не запускаются сами ") + sleep.size());
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
                m.put("_insecure", adbInsecure(adb) ? 1L : 0L);
                try { snapshot(adb); } catch (Exception ignored) {}
                return m;
            } catch (Exception e) { Map<String, Long> m = new HashMap<>(); m.put("_connected", 1L); return m; } finally { adb.close(); save(); }
        }
    }

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
                    if (safe.equals(s)) { sh(adb, "pm disable-user --user 0 " + s); stockOff = true; }
                }
                sh(adb, "cmd package set-home-activity --user 0 " + BuildInfo.LAUNCHER_HOME);
                String now = homePackage(adb);
                boolean onScreen = false;
                String top = "";
                for (int i = 0; i < 2 && !onScreen; i++) {
                    sh(adb, "input keyevent KEYCODE_HOME");
                    Thread.sleep(2500);
                    top = adb.shell("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'");
                    onScreen = top.contains(BuildInfo.LAUNCHER);
                }
                if (!onScreen) {
                    note(T.t("По «Домой» открылось: ") + firstLine(top.trim()));
                    if (s != null) {
                        if (stockOff) sh(adb, "pm enable " + s);
                        String[] l = adb.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME " + s).trim().split("\n");
                        String comp = l[l.length - 1].trim();
                        if (comp.startsWith(s + "/")) sh(adb, "cmd package set-home-activity --user 0 " + comp);
                    }
                    store.setFlag("launcher", false);
                    sh(adb, "input keyevent KEYCODE_HOME");
                    note(T.t("⚠ Новая оболочка не открылась по «Домой», поэтому заводская осталась на месте. Новую можно открыть из списка приложений."));
                    return false;
                }
                if (stockOff) { Set<String> d = store.disabled(); d.add(s); store.setDisabled(d); }
                store.setFlag("launcher", true);
                note(T.t("Главный экран теперь: ") + now + T.t(" — и он на экране ✔"));
                note(T.t("Готово"));
                return true;
            } catch (Exception e) {
                note(T.t("Ошибка: ") + e.getClass().getSimpleName() + " " + e.getMessage());
                return false;
            } finally { adb.close(); save(); }
        }
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
