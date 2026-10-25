package io.github.marginalion.tvjanitor;

import android.content.Context;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Translations. The Russian source text is the key; assets/i18n/<language>.tsv holds "source TAB translation".
 *  The TV's language is used when a file exists for it, English otherwise. */
final class T {
    private static Map<String, String> map;   // null: show the Russian source
    private static String lang = "ru";

    static synchronized void init(Context c) {
        if (map != null || c == null) return;
        String l = Locale.getDefault().getLanguage();
        if ("ru".equals(l)) { lang = "ru"; map = null; return; }
        Map<String, String> m = load(c, l);
        if (m == null) { l = "en"; m = load(c, "en"); }
        lang = l; map = m;
    }

    static String lang() { return lang; }

    private static Map<String, String> en;

    /** English, whatever the TV's language: reports for the shared CleanYourTV base are written in English. */
    static synchronized String en(Context c, String ru) {
        if (en == null) { en = load(c, "en"); if (en == null) en = new HashMap<>(); }
        String v = en.get(ru);
        return v != null ? v : ru;
    }

    static String t(String ru) {
        Map<String, String> m = map;
        if (m == null || ru == null) return ru;
        String v = m.get(ru);
        return v != null ? v : ru;
    }

    private static Map<String, String> load(Context c, String l) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("i18n/" + l + ".tsv"), StandardCharsets.UTF_8))) {
            Map<String, String> m = new HashMap<>();
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0 || line.startsWith("#")) continue;
                m.put(unesc(line.substring(0, tab)), unesc(line.substring(tab + 1)));
            }
            return m;
        } catch (Exception e) { return null; }
    }

    private static String unesc(String s) { return s.replace("\\n", "\n").replace("\\t", "\t"); }
}
