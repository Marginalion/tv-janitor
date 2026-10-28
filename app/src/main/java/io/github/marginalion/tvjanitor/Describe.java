package io.github.marginalion.tvjanitor;

import android.content.Context;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Plain-language names for the packages met on real TVs. Explains only; never changes a row's group.
 *  Russian in describe.txt; any other TV language reads the English twin, describe.en.txt. */
final class Describe {
    static final class D { final String label, note; D(String l, String n) { label = l; note = n; } }
    private static Map<String, D> map;

    static synchronized D get(Context c, String pkg) {
        if (map == null) {
            map = new HashMap<>();
            String file = "ru".equals(T.lang()) ? "describe.txt" : "describe.en.txt";
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("#") || line.trim().isEmpty()) continue;
                    String[] p = line.split("\\|", -1);
                    if (p.length >= 3) map.put(p[0].trim(), new D(p[1].trim(), p[2].trim()));
                }
            } catch (Exception ignored) {}
        }
        return map.get(pkg);
    }
}
