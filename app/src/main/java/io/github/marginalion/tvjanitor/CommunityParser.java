package io.github.marginalion.tvjanitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The same reading of debloat.txt as tools/community.py: disabled lines, DO NOT DISABLE blocks, model ids. */
final class CommunityParser {
    static final class Tv {
        String name, tv; Set<String> ids = new TreeSet<>(); List<String> off = new ArrayList<>(); Set<String> keep = new TreeSet<>();
        Map<String, String> offWhy = new HashMap<>(), keepWhy = new HashMap<>();
    }

    private static final String PKG = "[a-z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+";
    private static final Pattern LINE = Pattern.compile("^\\s*(" + PKG + ")\\s*(?:#\\s*(.*))?$");
    private static final Pattern DND = Pattern.compile("do not disable|don't disable|keep enabled|never disable", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_PKG = Pattern.compile("`?(" + PKG + ")`?");
    private static final Pattern BLOCK = Pattern.compile("^\\s{1,6}(" + PKG + ")\\s{2,}(.*)$");
    private static final Pattern ID = Pattern.compile("ro\\.product\\.(?:model|device|name)\\s*[=:]\\s*([^\\s,;)]+)");
    private static final Pattern FP = Pattern.compile("[Ff]ingerprint:?\\s*([^\\s]+)");

    private static boolean fileLike(String p) {
        return p.endsWith(".md") || p.endsWith(".txt") || p.endsWith(".sh") || p.endsWith(".py") || p.endsWith(".json") || p.endsWith(".xml") || p.endsWith(".java");
    }

    static Tv parse(String name, String tvName, String text) {
        Tv t = new Tv(); t.name = name; t.tv = tvName;
        Matcher m = ID.matcher(text);
        while (m.find()) t.ids.add(m.group(1).replaceAll("[`\"']", ""));
        m = FP.matcher(text);
        while (m.find()) {
            String[] parts = m.group(1).split("/");
            if (parts.length > 2) { t.ids.add(parts[1]); t.ids.add(parts[2].split(":")[0]); }
        }
        boolean inDnd = false;
        for (String raw : text.split("\n")) {
            String line = raw.replaceAll("\\s+$", "");
            if (line.trim().isEmpty()) { inDnd = false; continue; }
            if (!line.trim().startsWith("#")) {
                Matcher x = LINE.matcher(line);
                if (x.find()) { t.off.add(x.group(1)); t.offWhy.put(x.group(1), x.group(2) == null ? "" : x.group(2).trim()); }
                inDnd = false;
                continue;
            }
            String body = line.trim().substring(1);
            if (DND.matcher(body).find()) {
                inDnd = true;
                Matcher p = ANY_PKG.matcher(body);
                while (p.find()) {
                    String pk = p.group(1);
                    boolean looks = pk.split("\\.").length >= 3 || pk.startsWith("com.") || pk.startsWith("org.") || pk.startsWith("ru.") || pk.startsWith("tv.") || pk.startsWith("mitv.");
                    if (looks && !fileLike(pk)) { t.keep.add(pk); t.keepWhy.putIfAbsent(pk, clip(body.replaceAll("\\s+", " ").trim(), 200)); }
                }
                continue;
            }
            if (inDnd) {
                Matcher b = BLOCK.matcher(body);
                if (b.find() && !fileLike(b.group(1))) { t.keep.add(b.group(1)); t.keepWhy.putIfAbsent(b.group(1), clip(b.group(2).trim(), 200)); }
                else if (!body.startsWith("    ") && !body.trim().isEmpty() && !Character.isWhitespace(body.charAt(0))) inDnd = false;
            }
        }
        return t;
    }

    private static String clip(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }

    private static String best(List<String> notes) {
        String best = "";
        double bestKey = Double.MAX_VALUE;
        for (String n : notes) {
            if (n.isEmpty()) continue;
            double key = (n.length() < 15 ? 1000 : 0) + Math.abs(n.length() - 90);
            if (key < bestKey) { bestKey = key; best = n; }
        }
        return best;
    }

    static String communityTsv(List<Tv> tvs) {
        Map<String, Set<String>> offBy = new HashMap<>(), keepBy = new HashMap<>();
        Map<String, List<String>> offNotes = new HashMap<>(), keepNotes = new HashMap<>();
        for (Tv t : tvs) {
            for (String p : t.off) { offBy.computeIfAbsent(p, k -> new LinkedHashSet<>()).add(t.name); offNotes.computeIfAbsent(p, k -> new ArrayList<>()).add(t.offWhy.getOrDefault(p, "")); }
            for (String p : t.keep) { keepBy.computeIfAbsent(p, k -> new LinkedHashSet<>()).add(t.name); keepNotes.computeIfAbsent(p, k -> new ArrayList<>()).add(t.keepWhy.getOrDefault(p, "")); }
        }
        Set<String> all = new TreeSet<>(offBy.keySet()); all.addAll(keepBy.keySet());
        StringBuilder sb = new StringBuilder();
        for (String p : all) {
            sb.append(p).append('\t').append(offBy.getOrDefault(p, Collections.emptySet()).size()).append('\t')
              .append(keepBy.getOrDefault(p, Collections.emptySet()).size()).append('\t')
              .append(best(offNotes.getOrDefault(p, Collections.emptyList())).replace('\t', ' ')).append('\t')
              .append(best(keepNotes.getOrDefault(p, Collections.emptyList())).replace('\t', ' ')).append('\n');
        }
        return sb.toString();
    }

    static String devicesTsv(List<Tv> tvs) {
        StringBuilder sb = new StringBuilder();
        for (Tv t : tvs) sb.append(t.name).append('\t').append(t.tv.replace('\t', ' ')).append('\t').append(String.join(",", t.ids)).append('\t')
                .append(String.join(",", t.off)).append('\t').append(String.join(",", t.keep)).append('\n');
        return sb.toString();
    }
}
