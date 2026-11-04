package io.github.marginalion.tvjanitor;

import android.content.Context;
import android.os.Build;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;

/**
 * "Share your experience", sent: with the owner's consent the report goes to the author's Google Form by itself, at
 * moments that say something (decision of 04.11.2026). No name, no e-mail, no account: a random TV number only keeps
 * one TV's reports together. The owner's own apps go only with their separate tick, as in the file. Lite never sends.
 *
 * Moments: the first boot that came up fine after "Apply" (choices survived a restart), a day after "Apply", a
 * batch rolled back (something vital crashed), "Restore everything". Without network the event waits for the next
 * run. Call from a background thread only.
 */
final class Share {
    static final String FIRST_BOOT = "first_boot", DAY = "day_after", ROLLBACK = "rollback", RESTORE = "restore";

    private static final String FORM = "https://docs.google.com/forms/d/e/1FAIpQLSeN_wJ2mWx0xqY4GIbCE_C1TFbUCHgY-utFcacoILvfYYwW7w/formResponse";
    private static final String E_EVENT = "entry.1060640759", E_VERSION = "entry.1806818354", E_MODEL = "entry.1722400568",
            E_TV = "entry.1240508133", E_REPORT = "entry.1963188422";
    // «Журнал» and «Пакеты» (owner, 04.11): set once the form has them.
    private static final String E_LOG = "entry.1015749407", E_PKGS = "entry.340309280";
    /** A Google Sheets cell holds 50 000 characters. */
    private static final int MAX = 45000;
    /** The log is Russian: 2 bytes a letter, 6 once form-encoded. 12 000 letters is ~70 KB on the wire. */
    private static final int MAX_LOG = 12000;
    private static final long DAY_MS = 24L * 3600 * 1000;

    private Share() {}

    static boolean on(Context c) { return !Edition.LITE && new Store(c).flag("shareConsent", false); }

    /** Not asked yet: the question comes once, after the first "Apply" that went through. */
    static boolean shouldAsk(Context c) { return !Edition.LITE && !new Store(c).has("shareConsent"); }

    /** After an "Apply" that went through: wait for the first good boot, and for a day. */
    static void applied(Context c) {
        Store s = new Store(c);
        s.setStr("shareAppliedAt", String.valueOf(System.currentTimeMillis()));
        s.setFlag("shareDaySent", false);
        s.setFlag("shareWaitBoot", true);
    }

    /** The boot run reached its end: the first one after "Apply" is worth a report. */
    static void bootOk(Context c, Ops ops) {
        Store s = new Store(c);
        if (s.flag("shareWaitBoot", false)) { s.setFlag("shareWaitBoot", false); event(c, FIRST_BOOT, ops); }
        else flush(c, ops);
    }

    /** "Restore everything": nothing to wait for any more. */
    static void restored(Context c, Ops ops) {
        Store s = new Store(c);
        s.setStr("shareAppliedAt", null); s.setFlag("shareWaitBoot", false);
        event(c, RESTORE, ops);
    }

    static void event(Context c, String ev, Ops ops) {
        if (!on(c)) return;
        Store s = new Store(c);
        Set<String> p = s.set("sharePending"); p.add(ev); s.setSet("sharePending", p);
        flush(c, ops);
    }

    /** Sends what is waiting (and the day-after report when it is due). note: where to log, may be null. */
    static void flush(Context c, Ops ops) {
        Store s = new Store(c);
        if (!on(c)) { if (s.has("sharePending")) s.setSet("sharePending", new TreeSet<>()); return; }
        Set<String> p = new TreeSet<>(s.set("sharePending"));
        long at = parse(s.str("shareAppliedAt"));
        if (at > 0 && !s.flag("shareDaySent", false) && System.currentTimeMillis() - at >= DAY_MS) {
            p.add(DAY); s.setFlag("shareDaySent", true);
        }
        if (p.isEmpty()) return;
        String report = Report.build(c, s.flag("shareOwn", false));
        if (p.contains(RESTORE)) {
            Set<String> was = Report.system(c.getPackageManager(), s.set("shareWasOff"));
            if (!was.isEmpty()) report += "\n# Before \"Restore everything\" these were off (system packages):\n#   " + String.join(" ", was) + "\n";
        }
        if (report.length() > MAX) report = report.substring(0, MAX) + "\n# (cut)";
        String ev = String.join(",", p);
        // The same events with the same report within a day say nothing new (two boot paths, a retry).
        String sig = ev + ":" + Integer.toHexString(report.hashCode());
        if (sig.equals(s.str("shareLastSig")) && System.currentTimeMillis() - parse(s.str("shareLastAt")) < DAY_MS) {
            s.setSet("sharePending", new TreeSet<>()); return;
        }
        try {
            // The journal and the full package list go with the events that need explaining (owner, 04.11): a new TV
            // can't be understood from the summary alone. The day-after report is the summary only.
            boolean full = p.contains(FIRST_BOOT) || p.contains(ROLLBACK) || p.contains(RESTORE);
            String j = full ? journal(c, ops, MAX_LOG) : "", pk = full ? packages(c) : "";
            try { post(c, ev, report, j, pk); }
            catch (java.io.IOException big) {
                // Philips 04.11: the form refused a long journal at once (the Russian log grows sixfold when encoded).
                // Once more with only its end, and without the package list, rather than nothing.
                if (!full) throw big;
                post(c, ev, report, journal(c, ops, 4000), "");
            }
            s.setSet("sharePending", new TreeSet<>());
            s.setStr("shareLastSig", sig);
            s.setStr("shareLastAt", String.valueOf(System.currentTimeMillis()));
            if (ops != null) ops.note(T.t("📤 Отчёт отправлен автору Уборщика: ") + ev);
        } catch (Exception e) {
            s.setSet("sharePending", p);
            if (ops != null) ops.note(T.t("Отчёт не ушёл — отправлю в следующий раз") + " (" + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage()) + ")");
        }
    }

    /** The last part of the journal; the owner's own apps are named only with their tick. */
    static String journal(Context c, Ops ops, int max) {
        String j = ops != null ? ops.journal() : "";
        if (ops == null) try { java.io.File f = Ops.logFile(c); if (f.isFile()) j = new String(Ops.readAll(f), StandardCharsets.UTF_8); } catch (Exception ignored) {}
        if (!new Store(c).flag("shareOwn", false)) for (String own : ownApps(c)) j = j.replace(own, "(own app)");
        j = j.replaceAll("\n{3,}", "\n\n");
        return j.length() > max ? j.substring(j.length() - max) : j;
    }

    /** Every package with its state, name and whether it was off before TV Janitor: the snapshot, in one cell. */
    static String packages(Context c) {
        Store s = new Store(c);
        android.content.pm.PackageManager pm = c.getPackageManager();
        boolean own = s.flag("shareOwn", false);
        Set<String> before = s.set("snap_disabled"), sleep = s.sleeping(), chosen = s.disabled();
        StringBuilder b = new StringBuilder("# package | state now (on/off/sleep) | off before TV Janitor | chosen off | system | label\n");
        java.util.List<android.content.pm.ApplicationInfo> all = pm.getInstalledApplications(android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS);
        all.sort((x, y) -> x.packageName.compareTo(y.packageName));
        for (android.content.pm.ApplicationInfo ai : all) {
            boolean system = (ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
            if (!system && !own) continue;
            int st = pm.getApplicationEnabledSetting(ai.packageName);
            boolean on = ai.enabled && st != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    && st != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
            String label;
            try { label = String.valueOf(ai.loadLabel(pm)).replace('\n', ' ').replace('|', '/'); } catch (Exception e) { label = ""; }
            b.append(ai.packageName).append(" | ").append(!on ? "off" : sleep.contains(ai.packageName) ? "sleep" : "on")
             .append(" | ").append(before.contains(ai.packageName) ? "yes" : "-")
             .append(" | ").append(chosen.contains(ai.packageName) ? "yes" : "-")
             .append(" | ").append(system ? "sys" : "own").append(" | ").append(label).append('\n');
            if (b.length() > 30000) { b.append("# (cut)\n"); break; }
        }
        return b.toString();
    }

    private static Set<String> ownApps(Context c) {
        Set<String> out = new TreeSet<>((x, y) -> y.length() != x.length() ? y.length() - x.length() : x.compareTo(y));   // longest first
        for (android.content.pm.ApplicationInfo ai : c.getPackageManager().getInstalledApplications(android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS))
            if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 && !ai.packageName.equals(BuildInfo.SELF)
                    && !ai.packageName.equals(BuildInfo.LAUNCHER) && !ai.packageName.equals(Ops.KEYBOARD)) out.add(ai.packageName);
        return out;
    }

    private static void post(Context c, String ev, String report, String journal, String pkgs) throws Exception {
        Store s = new Store(c);
        String tv = s.str("tvId");
        if (tv == null) { tv = java.util.UUID.randomUUID().toString().substring(0, 8); s.setStr("tvId", tv); }
        String ver = "?";
        try { ver = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName + (Edition.LITE ? "" : " full"); } catch (Exception ignored) {}
        String model = Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + "), Android " + Build.VERSION.RELEASE;
        String body = enc(E_EVENT, ev) + "&" + enc(E_VERSION, ver) + "&" + enc(E_MODEL, model) + "&" + enc(E_TV, tv) + "&" + enc(E_REPORT, report)
                + (E_LOG != null && !journal.isEmpty() ? "&" + enc(E_LOG, journal) : "")
                + (E_PKGS != null && !pkgs.isEmpty() ? "&" + enc(E_PKGS, pkgs) : "");
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection h = (HttpURLConnection) new URL(FORM).openConnection();
        try {
            h.setConnectTimeout(15000); h.setReadTimeout(60000);
            h.setRequestMethod("POST"); h.setDoOutput(true); h.setInstanceFollowRedirects(false);
            h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            h.setRequestProperty("User-Agent", "Uborshchik-TV");
            h.setFixedLengthStreamingMode(b.length);
            try (OutputStream o = h.getOutputStream()) { o.write(b); }
            int code = h.getResponseCode();
            if (code != 200 && code != 302) throw new java.io.IOException("HTTP " + code);
        } finally { h.disconnect(); }
    }

    private static String enc(String k, String v) throws Exception { return k + "=" + URLEncoder.encode(v, "UTF-8"); }

    private static long parse(String v) { try { return v == null ? 0 : Long.parseLong(v); } catch (Exception e) { return 0; } }
}
