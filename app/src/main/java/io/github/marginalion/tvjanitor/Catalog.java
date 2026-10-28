package io.github.marginalion.tvjanitor;

import android.content.Context;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** What we know about packages: recommended, your choice, or never to be touched. */
final class Catalog {
    static final String SAFE = "safe", ASK = "ask", LOCK = "lock";

    static final class Entry {
        final String level, label, note;
        Entry(String level, String label, String note) { this.level = level; this.label = label; this.note = note; }
    }

    private static Map<String, Entry> map;

    static synchronized Map<String, Entry> get(Context c) {
        if (map != null) return map;
        map = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("catalog.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("\\|", -1);
                if (p.length >= 3) map.put(p[0].trim(), new Entry(p[1].trim(), T.t(p[2].trim()), p.length > 3 ? T.t(p[3].trim()) : ""));
            }
        } catch (Exception ignored) {}
        return map;
    }

    /** Packages that are part of the TV itself. The catalog can still mark a few of them safe or ask. */
    static boolean lockedByPattern(String pkg, String ime) {
        if (pkg.equals("android") || pkg.equals(BuildInfo.SELF) || pkg.equals(BuildInfo.LAUNCHER)) return true;
        // Only the keyboard in use is locked: once another one is the default, the old one may go.
        if (ime != null && pkg.equals(ime)) return true;
        String[] prefixes = {"com.android.", "android.", "com.google.android.gms", "com.google.android.gsf",
                "com.google.android.ext.", "com.google.android.overlay", "com.google.android.permissioncontroller",
                "com.google.android.packageinstaller", "com.google.android.webview", "com.google.android.tungsten",
                "com.google.android.tv.remote", "com.mediatek.", "mediatek.",
                "com.mstar.", "com.cvte.", "com.yandex_uni.", "org.droidtv.", "com.xiaomi.android.tvsetup",
                "com.google.android.modulemetadata", "com.google.android.onetimeinitializer",
                "com.google.android.partnersetup", "com.google.android.syncadapters", "com.google.android.tts",
                "com.google.android.boot", "com.google.android.sss"};
        for (String p : prefixes) if (pkg.startsWith(p)) return true;
        return pkg.equals("com.android.vending");
    }
}
