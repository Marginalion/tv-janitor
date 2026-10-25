package io.github.marginalion.tvjanitor;

import android.content.Context;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Plain-language names for standard Android and Google TV packages. Explains only; never changes a row's group. */
final class Describe {
    static final class D { final String label, note; D(String l, String n) { label = l; note = n; } }
    private static Map<String, D> map;

    static synchronized D get(Context c, String pkg) {
        if (map == null) {
            map = new HashMap<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("describe.txt"), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("#") || line.trim().isEmpty()) continue;
                    String[] p = line.split("\\|", -1);
                    if (p.length >= 3) map.put(p[0].trim(), new D(T.t(p[1].trim()), T.t(p[2].trim())));
                }
            } catch (Exception ignored) {}
        }
        return map.get(pkg);
    }
}
