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
    static void bootOk(Context c, Ops.Say note) {
        Store s = new Store(c);
        if (s.flag("shareWaitBoot", false)) { s.setFlag("shareWaitBoot", false); event(c, FIRST_BOOT, note); }
        else flush(c, note);
    }

    /** "Restore everything": nothing to wait for any more. */
    static void restored(Context c, Ops.Say note) {
        Store s = new Store(c);
        s.setStr("shareAppliedAt", null); s.setFlag("shareWaitBoot", false);
        event(c, RESTORE, note);
    }

    static void event(Context c, String ev, Ops.Say note) {
        if (!on(c)) return;
        Store s = new Store(c);
        Set<String> p = s.set("sharePending"); p.add(ev); s.setSet("sharePending", p);
        flush(c, note);
    }

    /** Sends what is waiting (and the day-after report when it is due). note: where to log, may be null. */
    static void flush(Context c, Ops.Say note) {
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
        if (report.length() > 40000) report = report.substring(0, 40000) + "\n# (cut)";
        String ev = String.join(",", p);
        // The same events with the same report within a day say nothing new (two boot paths, a retry).
        String sig = ev + ":" + Integer.toHexString(report.hashCode());
        if (sig.equals(s.str("shareLastSig")) && System.currentTimeMillis() - parse(s.str("shareLastAt")) < DAY_MS) {
            s.setSet("sharePending", new TreeSet<>()); return;
        }
        try {
            post(c, ev, report);
            s.setSet("sharePending", new TreeSet<>());
            s.setStr("shareLastSig", sig);
            s.setStr("shareLastAt", String.valueOf(System.currentTimeMillis()));
            if (note != null) note.say(T.t("📤 Отчёт отправлен автору Уборщика: ") + ev);
        } catch (Exception e) {
            s.setSet("sharePending", p);
            if (note != null) note.say(T.t("Отчёт не ушёл (нет интернета?) — отправлю в следующий раз"));
        }
    }

    private static void post(Context c, String ev, String report) throws Exception {
        Store s = new Store(c);
        String tv = s.str("tvId");
        if (tv == null) { tv = java.util.UUID.randomUUID().toString().substring(0, 8); s.setStr("tvId", tv); }
        String ver = "?";
        try { ver = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName + (Edition.LITE ? "" : " full"); } catch (Exception ignored) {}
        String model = Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + "), Android " + Build.VERSION.RELEASE;
        String body = enc(E_EVENT, ev) + "&" + enc(E_VERSION, ver) + "&" + enc(E_MODEL, model) + "&" + enc(E_TV, tv) + "&" + enc(E_REPORT, report);
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection h = (HttpURLConnection) new URL(FORM).openConnection();
        try {
            h.setConnectTimeout(10000); h.setReadTimeout(20000);
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
