package io.github.marginalion.tvjanitor;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fetches the current examples/NAME/debloat.txt lists from CleanYourTV and rebuilds the local base. */
final class Updater {
    interface Say { void say(String s); }
    static final String REPO = "Gathin23/cleanyourtv";

    static File dir(Context c) { File d = new File(c.getFilesDir(), "db"); d.mkdirs(); return d; }

    /** Returns a one-line result for the screen. The built-in base stays in use when anything fails. */
    static String update(Context c, Say say) {
        if (Edition.LITE) return T.t("В лёгкой версии база не обновляется из интернета");
        try {
            say.say(T.t("Спрашиваю GitHub, что нового…"));
            JSONObject tree = new JSONObject(get("https://api.github.com/repos/" + REPO + "/git/trees/main?recursive=1"));
            String sha = tree.optString("sha");
            Store store = new Store(c);
            if (sha.equals(store.str("dbSha")) && new File(dir(c), "devices.tsv").isFile())
                return T.t("База уже свежая: ") + store.str("dbInfo");
            JSONArray items = tree.getJSONArray("tree");
            List<String> names = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                String p = items.getJSONObject(i).optString("path");
                if (p.startsWith("examples/") && p.endsWith("/debloat.txt") && p.split("/").length == 3) names.add(p.split("/")[1]);
            }
            if (names.size() < 50) return T.t("Не получилось: на GitHub нашлось слишком мало списков (") + names.size() + ")";
            ExecutorService pool = Executors.newFixedThreadPool(6);
            List<Future<CommunityParser.Tv>> jobs = new ArrayList<>();
            for (String n : names) jobs.add(pool.submit(() -> {
                String base = "https://raw.githubusercontent.com/" + REPO + "/" + sha + "/examples/" + n + "/";
                String debloat = get(base + "debloat.txt");
                String tv = n;
                try { tv = new JSONObject(get(base + "tv.json")).optString("tv", n); } catch (Exception ignored) {}
                return CommunityParser.parse(n, tv, debloat);
            }));
            List<CommunityParser.Tv> tvs = new ArrayList<>();
            int done = 0;
            for (Future<CommunityParser.Tv> f : jobs) {
                try { tvs.add(f.get()); } catch (Exception ignored) {}
                if (++done % 20 == 0) say.say(T.t("Скачано ") + done + T.t(" из ") + names.size() + "…");
            }
            pool.shutdown();
            if (tvs.size() < names.size() * 0.8) return T.t("Не получилось: скачалось только ") + tvs.size() + T.t(" из ") + names.size() + T.t(". Осталась прежняя база.");
            File d = dir(c);
            write(new File(d, "community.tsv.tmp"), CommunityParser.communityTsv(tvs));
            write(new File(d, "devices.tsv.tmp"), CommunityParser.devicesTsv(tvs));
            new File(d, "community.tsv.tmp").renameTo(new File(d, "community.tsv"));
            new File(d, "devices.tsv.tmp").renameTo(new File(d, "devices.tsv"));
            String info = tvs.size() + T.t(" ТВ от ") + new SimpleDateFormat("dd.MM.yyyy", Locale.US).format(new Date());
            store.setStr("dbSha", sha);
            store.setStr("dbInfo", info);
            Community.reset();
            return T.t("База обновлена: ") + info;
        } catch (Exception e) {
            return T.t("Не удалось обновить (") + e.getClass().getSimpleName() + T.t("). Работаем на прежней базе — GitHub бывает недоступен.");
        }
    }

    private static String get(String u) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
        h.setConnectTimeout(10000); h.setReadTimeout(20000);
        h.setRequestProperty("User-Agent", "Uborshchik-TV");
        if (h.getResponseCode() != 200) throw new java.io.IOException("HTTP " + h.getResponseCode());
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[16384]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toString("UTF-8");
        } finally { h.disconnect(); }
    }

    private static void write(File f, String s) throws Exception {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(s.getBytes(StandardCharsets.UTF_8)); }
    }
}
