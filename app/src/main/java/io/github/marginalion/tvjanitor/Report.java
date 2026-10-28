package io.github.marginalion.tvjanitor;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** The owner's experience in CleanYourTV's examples/NAME/debloat.txt format. Only system packages: own apps stay private. */
final class Report {
    static String build(Context c) { return build(c, false); }

    /** includeOwn: the owner chose to share their own (non-system) apps too. */
    static String build(Context c, boolean includeOwn) {
        Store s = new Store(c);
        PackageManager pm = c.getPackageManager();
        StringBuilder r = new StringBuilder();
        r.append("# ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" (").append(Build.DEVICE)
         .append("), Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        r.append("# ro.product.model=").append(Build.MODEL).append("  ro.product.device=").append(Build.DEVICE)
         .append("  ro.product.name=").append(Build.PRODUCT).append('\n');
        r.append("# Fingerprint: ").append(Build.FINGERPRINT).append('\n');
        r.append("# Written by Uborshchik (TV Janitor) on ").append(new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()))
         .append(" from what the owner chose and kept on this TV. Everything is DISABLED (pm disable-user), not uninstalled.\n");
        Community.Match m = Community.device(c);
        if (m != null) r.append("# Closest TV in the CleanYourTV base: ").append(m.tv).append('\n');
        Set<String> back = system(pm, s.set("firmwareBack"));
        if (!back.isEmpty()) r.append("#\n# This firmware re-enables these at every boot; the cleaner turns them off again after boot:\n#   ")
                .append(String.join(" ", back)).append('\n');
        // Only what was found guilty on its own; not what is off on this TV right now, nor what Android refused.
        Set<String> rolled = system(pm, s.set("crashers"));
        rolled.removeAll(s.set("protectedPkgs"));
        rolled.removeAll(s.disabled());
        if (!rolled.isEmpty()) {
            r.append("#\n# DO NOT DISABLE on this model: something started crashing right after they were disabled, so they were put back:\n");
            for (String p : rolled) r.append("#   ").append(pad(p)).append(label(pm, p)).append('\n');
        }
        Set<String> sleep = system(pm, s.sleeping());
        if (!sleep.isEmpty()) {
            r.append("#\n# Kept enabled but not allowed to start on their own (appops RUN_ANY_IN_BACKGROUND ignore + am force-stop after boot):\n");
            for (String p : sleep) r.append("#   ").append(pad(p)).append(label(pm, p)).append('\n');
        }
        StringBuilder q = new StringBuilder();
        if (s.flag("bootBlocked", false)) q.append("#   BOOT_COMPLETED is not delivered to third-party apps: re-applied by a scheduled job a few minutes after boot.\n");
        Set<String> prot = system(pm, s.set("protectedPkgs"));
        if (!prot.isEmpty()) q.append("#   Protected packages (pm disable-user refused by the firmware): ").append(String.join(" ", prot)).append('\n');
        Set<String> frozen = system(pm, s.set("frozenPkgs")), immortal = system(pm, s.set("immortalPkgs"));
        if (!frozen.isEmpty()) q.append("#   Protected, but pm suspend works (frozen instead): ").append(String.join(" ", frozen)).append('\n');
        if (!immortal.isEmpty()) q.append("#   Persistent: can't be disabled, suspended or force-stopped over ADB (needs root): ").append(String.join(" ", immortal)).append('\n');
        if (s.flag("rebootPowersOff", false)) q.append("#   \"reboot\" powers the TV off instead of restarting it: it has to be switched on with the remote.\n");
        if (s.flag("adbInsecure", false)) q.append("#   ro.adb.secure=0: debugging accepts any computer without asking.\n");
        if (q.length() > 0) r.append("#\n# Firmware quirks seen by TV Janitor:\n").append(q);
        Set<String> used = s.set("features");
        StringBuilder kept = new StringBuilder();
        for (Features.F f : Features.ALL) if (used.contains(f.id)) kept.append(kept.length() == 0 ? "" : ", ").append(T.en(c, f.ru).replaceAll("^\\S+\\s+", ""));
        if (kept.length() > 0) r.append("#\n# The owner uses: ").append(kept).append('\n');
        r.append('\n');
        Set<String> off = system(pm, s.disabled());
        off.removeAll(s.set("protectedPkgs"));         // chosen, but the firmware keeps them on
        Set<String> ownOff = own(pm, s.disabled()), ownSleep = own(pm, s.sleeping());
        for (String p : off) r.append(pad(p)).append("# ").append(label(pm, p)).append('\n');
        if (includeOwn) {
            if (!ownOff.isEmpty() || !ownSleep.isEmpty()) r.append("\n# The owner's own (installed) apps, shared by choice:\n");
            for (String p : ownOff) r.append(pad(p)).append("# ").append(label(pm, p)).append(" (installed app, disabled)\n");
            for (String p : ownSleep) r.append("# ").append(pad(p)).append(label(pm, p)).append(" (installed app, kept from starting on its own)\n");
        } else if (!ownOff.isEmpty() || !ownSleep.isEmpty()) {
            r.append("\n# (").append(ownOff.size() + ownSleep.size()).append(" of the owner's own apps are also off or asleep; not listed, they are personal.)\n");
        }
        return r.toString();
    }

    /** With the owner's consent the report is kept current on the TV (and later sent); without it nothing is written. */
    static void saveIfConsented(Context c) {
        Store s = new Store(c);
        java.io.File f = new java.io.File(c.getExternalFilesDir(null), "report.txt");
        if (!s.flag("shareConsent", false)) { return; }
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
            o.write(build(c, s.flag("shareOwn", false)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private static Set<String> own(PackageManager pm, Set<String> pkgs) {
        Set<String> out = new TreeSet<>();
        for (String p : pkgs) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(p, PackageManager.MATCH_DISABLED_COMPONENTS);
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0) out.add(p);
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static Set<String> system(PackageManager pm, Set<String> pkgs) {
        Set<String> out = new TreeSet<>();
        for (String p : pkgs) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(p, PackageManager.MATCH_DISABLED_COMPONENTS);
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) out.add(p);
            } catch (Exception ignored) {}
        }
        return out;
    }

    /** The app's own name in English (the shared base is English), falling back to its label as shown. */
    private static String label(PackageManager pm, String p) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(p, PackageManager.MATCH_DISABLED_COMPONENTS);
            if (ai.labelRes != 0) {
                try {
                    android.content.res.Resources r = pm.getResourcesForApplication(ai);
                    android.content.res.Configuration cfg = new android.content.res.Configuration(r.getConfiguration());
                    cfg.setLocale(java.util.Locale.US);
                    @SuppressWarnings("deprecation")
                    android.content.res.Resources er = new android.content.res.Resources(r.getAssets(), r.getDisplayMetrics(), cfg);
                    CharSequence t = er.getText(ai.labelRes);
                    if (t != null && t.length() > 0) return t.toString();
                } catch (Exception ignored) {}
            }
            return String.valueOf(ai.loadLabel(pm));
        } catch (Exception x) { return ""; }
    }

    private static String pad(String p) { StringBuilder b = new StringBuilder(p); while (b.length() < 42) b.append(' '); return b.append(' ').toString(); }
}
