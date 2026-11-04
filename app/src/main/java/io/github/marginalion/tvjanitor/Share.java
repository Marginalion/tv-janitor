package io.github.marginalion.tvjanitor;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * "Share your experience", sent: with the owner's consent the report goes to the author's Google Form by itself, at
 * moments that say something (decision of 04.11.2026). No name, no e-mail, no account: a random TV number only keeps
 * one TV's reports together. The owner's own apps go only with their separate tick. Lite never sends.
 *
 * Moments: the first boot after "Apply" that really is a new boot, a day after "Apply", a batch rolled back,
 * "Restore everything" (whole or partial). Since 0.9.5 (audit F94.4, F94.7, F94.8) each moment is its own record,
 * written when it happens with the state of that moment, an id and its outcome, kept in files/share/ until the form
 * has taken it. One sender at a time; only what was sent leaves the queue. A lost answer can still mean a second row
 * with the same id: the id in the "Событие" column shows it. Call from a background thread only.
 */
final class Share {
    static final String FIRST_BOOT = "first_boot", DAY = "day_after", ROLLBACK = "rollback", RESTORE = "restore",
            RESTORE_PARTIAL = "restore_partial";

    private static final String FORM = "https://docs.google.com/forms/d/e/1FAIpQLSeN_wJ2mWx0xqY4GIbCE_C1TFbUCHgY-utFcacoILvfYYwW7w/formResponse";
    private static final String E_EVENT = "entry.1060640759", E_VERSION = "entry.1806818354", E_MODEL = "entry.1722400568",
            E_TV = "entry.1240508133", E_REPORT = "entry.1963188422", E_LOG = "entry.1015749407", E_PKGS = "entry.340309280";
    /** A Google Sheets cell holds 50 000 characters. */
    private static final int MAX = 45000;
    /** The log is Russian: 2 bytes a letter, 6 once form-encoded. 12 000 letters is ~70 KB on the wire. */
    private static final int MAX_LOG = 12000;
    private static final int MAX_QUEUE = 12;
    private static final long DAY_MS = 24L * 3600 * 1000;
    /** One sender: the screen, a boot run and a job may all try at once (F94.7). */
    private static final Object SEND = new Object();

    private Share() {}

    static boolean on(Context c) { return !Edition.LITE && new Store(c).flag("shareConsent", false); }

    /** Not asked yet: the question comes once, after the first "Apply" that went through. */
    static boolean shouldAsk(Context c) { return !Edition.LITE && !new Store(c).has("shareConsent"); }

    /** After an "Apply" that went through: wait for the first new boot, and for a day. */
    static void applied(Context c) {
        Store s = new Store(c);
        s.edit().putString("shareAppliedAt", String.valueOf(System.currentTimeMillis()))
                .putString("shareBootAtApply", String.valueOf(Boot.count(c)))
                .putBoolean("shareDaySent", false).putBoolean("shareWaitBoot", true).commit();
    }

    /** A boot after "Apply" really happened: another boot number, or (no counter) the TV is up for less than since. */
    private static boolean rebootedSinceApply(Context c, Store s) {
        int now = Boot.count(c);
        String at = s.str("shareBootAtApply");
        if (now >= 0 && at != null && !at.equals("-1")) return !at.equals(String.valueOf(now));
        long applied = parse(s.str("shareAppliedAt"));
        return applied > 0 && SystemClock.elapsedRealtime() < System.currentTimeMillis() - applied;
    }

    /** The boot run reached its end: the first one after "Apply" on a new boot is worth a report (F94.8). */
    static void bootOk(Context c, Ops ops) {
        Store s = new Store(c);
        if (s.flag("shareWaitBoot", false) && rebootedSinceApply(c, s)) { s.edit().putBoolean("shareWaitBoot", false).commit(); event(c, FIRST_BOOT, ops); }
        else flush(c, ops);
    }

    /** "Restore everything": nothing to wait for any more; the event says whether all came back. */
    static void restored(Context c, Ops ops, boolean ok) {
        Store s = new Store(c);
        s.edit().remove("shareAppliedAt").putBoolean("shareWaitBoot", false).commit();
        event(c, ok ? RESTORE : RESTORE_PARTIAL, ops);
    }

    private static File dir(Context c) { File d = new File(c.getFilesDir(), "share"); d.mkdirs(); return d; }

    private static boolean full(String ev) { return !ev.equals(DAY); }

    /** The moment as it is now, kept until sent: what the TV looked like then, not when the network comes back. */
    static void event(Context c, String ev, Ops ops) {
        if (!on(c)) return;
        try {
            Store s = new Store(c);
            boolean own = s.flag("shareOwn", false);
            long at = System.currentTimeMillis();
            String id = Long.toHexString(at).substring(Math.max(0, Long.toHexString(at).length() - 6))
                    + Integer.toHexString(new java.util.Random().nextInt(0x10000) | 0x10000).substring(1);
            JSONObject r = new JSONObject();
            r.put("id", id).put("ev", ev).put("at", at).put("boot", Boot.count(c)).put("own", own);
            r.put("report", report(c, ev, own));
            if (full(ev)) { r.put("journal", journal(c, ops, MAX_LOG, own)); r.put("pkgs", packages(c, own)); }
            File[] old = dir(c).listFiles();
            if (old != null && old.length >= MAX_QUEUE) {
                Arrays.sort(old);
                for (int i = 0; i <= old.length - MAX_QUEUE; i++) old[i].delete();
            }
            try (FileOutputStream o = new FileOutputStream(new File(dir(c), at + "-" + id + ".json"))) { o.write(r.toString().getBytes(StandardCharsets.UTF_8)); }
        } catch (Exception e) { if (ops != null) ops.note(T.t("Отчёт не записан: ") + e.getClass().getSimpleName()); }
        flush(c, ops);
    }

    private static String report(Context c, String ev, boolean own) {
        Store s = new Store(c);
        String report = Report.build(c, own);
        if (ev.startsWith(RESTORE)) {
            Set<String> was = Report.system(c.getPackageManager(), s.set("shareWasOff"));
            if (!was.isEmpty()) report += "\n# Before \"Restore everything\" these were off (system packages):\n#   " + String.join(" ", was) + "\n";
        }
        return report.length() > MAX ? report.substring(0, MAX) + "\n# (cut)" : report;
    }

    /** The form answered with an error code: it got the request and refused it (size, a field). */
    static final class Refused extends java.io.IOException { Refused(int code) { super("HTTP " + code); } }

    /** Sends what is waiting (and the day-after report when it is due), oldest first. ops: where to log, may be null. */
    static void flush(Context c, Ops ops) {
        synchronized (SEND) {
            Store s = new Store(c);
            File[] files = dir(c).listFiles();
            if (!on(c)) { if (files != null) for (File f : files) f.delete(); s.edit().remove("sharePending").commit(); return; }
            // Events left waiting by 0.9.4 (names only): written now as records, once.
            if (s.has("sharePending")) {
                Set<String> old = s.set("sharePending");
                s.edit().remove("sharePending").commit();
                for (String ev : old) event(c, ev, ops);
                return;
            }
            long at = parse(s.str("shareAppliedAt"));
            if (at > 0 && !s.flag("shareDaySent", false) && System.currentTimeMillis() - at >= DAY_MS) {
                s.edit().putBoolean("shareDaySent", true).commit();
                event(c, DAY, ops);    // writes the record and comes back here (same thread, same lock)
                return;
            }
            if (files == null || files.length == 0) return;
            Arrays.sort(files);
            for (File f : files) {
                if (!on(c)) return;    // consent taken back meanwhile: the next flush clears the queue
                JSONObject r;
                try { r = new JSONObject(new String(Ops.readAll(f), StandardCharsets.UTF_8)); }
                catch (Exception e) { f.delete(); continue; }
                String ev = r.optString("ev"), id = r.optString("id");
                boolean own = s.flag("shareOwn", false);
                String report = r.optString("report"), journal = r.optString("journal"), pkgs = r.optString("pkgs");
                // The "my apps too" tick was taken off after this moment: what it allowed is not sent any more.
                if (r.optBoolean("own") && !own) {
                    report = report(c, ev, false);
                    if (full(ev)) { journal = journal(c, null, MAX_LOG, false); pkgs = packages(c, false); }
                }
                String label = ev + " #" + id + " " + new java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(new java.util.Date(r.optLong("at")))
                        + " boot " + r.optInt("boot", -1);
                try {
                    try { post(c, label, report, journal, pkgs); }
                    catch (Refused big) {
                        // Philips 04.11: the form refused a long journal at once (the Russian log grows sixfold when
                        // encoded). Only a refusal is retried shorter; a lost connection waits for the next run.
                        if (!full(ev) || !on(c)) throw big;
                        post(c, label + " (short)", report, tail(journal, 4000), "");
                    }
                    f.delete();
                    if (ops != null) ops.note(T.t("📤 Отчёт отправлен автору Уборщика: ") + ev + " #" + id);
                } catch (Exception e) {
                    if (ops != null) ops.note(T.t("Отчёт не ушёл — отправлю в следующий раз") + " (" + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage()) + ")");
                    return;    // the rest waits too, in order
                }
            }
        }
    }

    private static String tail(String s, int max) { return s.length() > max ? s.substring(s.length() - max) : s; }

    /** Tokens that look like a package (with an optional /Activity after it) in free text such as the journal. */
    private static final Pattern PKG = Pattern.compile("(?<![\\w.-])([a-z][a-z0-9_]*(?:\\.[A-Za-z0-9_$]+)+)(/[A-Za-z0-9_.$]+)?(?![\\w-])");
    private static final Set<String> FILE_ENDS = new HashSet<>(Arrays.asList("txt", "apk", "log", "json", "xml", "png", "sh", "bat", "tsv", "zip"));
    private static final String[] NOT_APPS = {"java.", "javax.", "android.", "dalvik.", "libcore.", "kotlin.", "org.json."};

    /**
     * The journal as it leaves the TV: with the tick off, every name that is not a system package of this TV (an
     * app installed by the owner, one removed since, an Activity of either) becomes "(own app)" (audit F94.4).
     * When in doubt, hidden.
     */
    static String mask(Context c, String text, boolean own) {
        if (own) return text;
        PackageManager pm = c.getPackageManager();
        Map<String, Catalog.Entry> cat = Catalog.get(c);
        Set<String> sys = new HashSet<>(), user = new HashSet<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)) {
            boolean ours = ai.packageName.equals(BuildInfo.SELF) || ai.packageName.equals(BuildInfo.LAUNCHER) || ai.packageName.equals(Ops.KEYBOARD);
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 || ours) sys.add(ai.packageName); else user.add(ai.packageName);
        }
        Matcher m = PKG.matcher(text);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String tok = m.group(1);
            boolean keep = false;
            String last = tok.substring(tok.lastIndexOf('.') + 1);
            if (FILE_ENDS.contains(last.toLowerCase(java.util.Locale.ROOT))) keep = true;
            for (String p : NOT_APPS) if (tok.startsWith(p)) keep = true;
            if (!keep) {
                // The package itself, or the package a class name starts with.
                String p = tok;
                while (true) {
                    if (user.contains(p)) { keep = false; break; }
                    if (sys.contains(p) || cat.containsKey(p)) { keep = true; break; }
                    int i = p.lastIndexOf('.');
                    if (i <= 0 || p.indexOf('.') == i) break;
                    p = p.substring(0, i);
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(keep ? m.group(0) : "(own app)"));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The last part of the journal, filtered as above. */
    static String journal(Context c, Ops ops, int max, boolean own) {
        String j = ops != null ? ops.journal() : "";
        if (ops == null) try { File f = Ops.logFile(c); if (f.isFile()) j = new String(Ops.readAll(f), StandardCharsets.UTF_8); } catch (Exception ignored) {}
        j = j.replaceAll("\n{3,}", "\n\n");
        // Cut first (a little more than needed), then mask: masking a 30 000-character file costs for nothing.
        return tail(mask(c, tail(j, max + 2000), own), max);
    }

    /** Every package with its state, name and whether it was off before TV Janitor: the snapshot, in one cell. */
    static String packages(Context c, boolean own) {
        Store s = new Store(c);
        PackageManager pm = c.getPackageManager();
        Set<String> before = s.set("snap_disabled"), sleep = s.sleeping(), chosen = s.disabled();
        StringBuilder b = new StringBuilder("# package | state now (on/off/sleep) | off before TV Janitor | chosen off | system | label\n");
        java.util.List<ApplicationInfo> all = pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS);
        all.sort((x, y) -> x.packageName.compareTo(y.packageName));
        for (ApplicationInfo ai : all) {
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (!system && !own) continue;
            int st = pm.getApplicationEnabledSetting(ai.packageName);
            boolean on = ai.enabled && st != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    && st != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
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

    /** Exactly what a first-boot report would send now, for the "Share your experience" screen (audit 4.4). */
    static String preview(Context c, boolean own) {
        Store s = new Store(c);
        return "== " + T.t("Событие") + ": first_boot #…  · " + T.t("Версия") + ": " + version(c) + "  · " + T.t("Модель") + ": " + model()
                + "  · ID: " + (s.str("tvId") != null ? s.str("tvId") : "—") + "\n\n== " + T.t("Отчёт") + "\n" + report(c, FIRST_BOOT, own)
                + "\n\n== " + T.t("Журнал") + "\n" + journal(c, null, MAX_LOG, own) + "\n\n== " + T.t("Пакеты") + "\n" + packages(c, own);
    }

    private static String version(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName + (Edition.LITE ? "" : " full"); } catch (Exception e) { return "?"; }
    }

    private static String model() { return Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + "), Android " + Build.VERSION.RELEASE; }

    private static void post(Context c, String ev, String report, String journal, String pkgs) throws Exception {
        Store s = new Store(c);
        String tv = s.str("tvId");
        if (tv == null) { tv = java.util.UUID.randomUUID().toString().substring(0, 8); s.commitStr("tvId", tv); }
        String body = enc(E_EVENT, ev) + "&" + enc(E_VERSION, version(c)) + "&" + enc(E_MODEL, model()) + "&" + enc(E_TV, tv) + "&" + enc(E_REPORT, report)
                + (!journal.isEmpty() ? "&" + enc(E_LOG, journal) : "")
                + (!pkgs.isEmpty() ? "&" + enc(E_PKGS, pkgs) : "");
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
            if (code != 200 && code != 302) throw new Refused(code);
        } finally { h.disconnect(); }
    }

    private static String enc(String k, String v) throws Exception { return k + "=" + URLEncoder.encode(v, "UTF-8"); }

    private static long parse(String v) { try { return v == null ? 0 : Long.parseLong(v); } catch (Exception e) { return 0; } }
}
