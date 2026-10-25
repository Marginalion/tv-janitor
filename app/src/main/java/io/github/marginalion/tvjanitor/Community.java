package io.github.marginalion.tvjanitor;

import android.content.Context;
import android.os.Build;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** What CleanYourTV owners found on ~300 TVs (examples/NAME/debloat.txt, MIT licence). */
final class Community {
    static final class Stat { int off, keep; String why = "", keepWhy = ""; }

    static final class Match { String tv = ""; Set<String> off = new HashSet<>(), keep = new HashSet<>(); }

    private static Map<String, Stat> stats;
    private static Match match;
    private static int tvCount;

    static synchronized void reset() { stats = null; match = null; tvCount = 0; }

    static synchronized Map<String, Stat> stats(Context c) { load(c); return stats; }
    static synchronized Match device(Context c) { load(c); return match; }
    static synchronized int tvCount(Context c) { load(c); return tvCount; }

    private static void load(Context c) {
        if (stats != null) return;
        stats = new HashMap<>();
        try (BufferedReader r = reader(c, "community.tsv")) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length < 5) continue;
                Stat s = new Stat();
                s.off = Integer.parseInt(p[1]); s.keep = Integer.parseInt(p[2]); s.why = p[3]; s.keepWhy = p[4];
                stats.put(p[0], s);
            }
        } catch (Exception ignored) {}
        // The TV is matched by model or device name against the owners' descriptions.
        String[] me = {Build.MODEL, Build.DEVICE, Build.PRODUCT};
        int best = 0;
        try (BufferedReader r = reader(c, "devices.tsv")) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length < 5) continue;
                tvCount++;
                String hay = (p[1] + " " + p[2]).toLowerCase(Locale.ROOT);
                Set<String> ids = new HashSet<>(Arrays.asList(p[2].toLowerCase(Locale.ROOT).split(",")));
                int score = 0;
                for (String m : me) {
                    if (m == null || m.length() < 4) continue;
                    String x = m.toLowerCase(Locale.ROOT);
                    if (ids.contains(x)) score += 3;
                    else if (hay.contains(x)) score += 2;
                }
                if (score > best) {
                    best = score;
                    match = new Match();
                    match.tv = p[1];
                    for (String s : p[3].split(",")) if (!s.isEmpty()) match.off.add(s);
                    for (String s : p[4].split(",")) if (!s.isEmpty()) match.keep.add(s);
                }
            }
        } catch (Exception ignored) {}
    }

    /** The downloaded base when there is one, the built-in one otherwise. */
    private static BufferedReader reader(Context c, String name) throws Exception {
        java.io.File f = new java.io.File(Updater.dir(c), name);
        if (f.isFile() && f.length() > 1000)
            return new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8));
        return new BufferedReader(new InputStreamReader(c.getAssets().open(name), StandardCharsets.UTF_8));
    }
}
