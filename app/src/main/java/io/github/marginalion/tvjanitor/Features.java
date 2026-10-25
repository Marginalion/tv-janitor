package io.github.marginalion.tvjanitor;

import java.util.regex.Pattern;

/** Everyday features in plain words, and the packages behind them on Android TV, Google TV, Xiaomi, Philips and Yandex TV. */
final class Features {
    static final class F {
        final String id, title, sub; final String[] pkgs; final Pattern pattern;
        F(String id, String title, String sub, String regex, String... pkgs) {
            this.id = id; this.title = title; this.sub = sub; this.pkgs = pkgs;
            this.pattern = regex == null ? null : Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        }
        boolean owns(String pkg) {
            for (String p : pkgs) if (p.equals(pkg)) return true;
            return pattern != null && pattern.matcher(pkg).find();
        }
    }

    static final F[] ALL = {
        new F("voice", T.t("🎤 Голосовой поиск и ассистент"), T.t("кнопка микрофона на пульте, Google Ассистент, Алиса"),
                "katniss|assistant|alexa|\\.voice|voiceassist|speech|\\.alice",
                "com.google.android.katniss", "com.yandex.tv.alice", "com.yandex.io.sdk", "com.xiaomi.amazon.alexa", "org.droidtv.amazonalexa"),
        new F("tv", T.t("📡 Эфирное и кабельное ТВ"), T.t("каналы через антенну или кабель"),
                "\\.efir|livechannels|epgdata|teletext|euinstaller|\\.dtv\\.|tvguide",
                "com.yandex.tv.input.efir", "com.google.android.tv", "org.droidtv.epgdata", "org.droidtv.teletext", "org.droidtv.ci"),
        new F("hdmi", T.t("🎮 HDMI-устройства"), T.t("приставки, консоли, ресиверы и управление ими пультом ТВ (CEC)"),
                "hdmi|\\.cec", "com.yandex.tv.live"),
        new F("cast", T.t("📱 Трансляция с телефона (Chromecast)"), T.t("кнопка «Транслировать» в YouTube и других приложениях"),
                "mediashell|chromecast|googlecast", "com.google.android.apps.mediashell"),
        new F("mirror", T.t("🪞 Повтор экрана (Miracast) и DLNA"), T.t("экран телефона или ноутбука на телевизоре"),
                "miracast|\\.wfd|wfdsink|screenmirror|smartshare|milink|dlna|dssshare|airplay|playfi",
                "com.xiaomi.mitv.smartshare", "com.xiaomi.wfdsinkhelperservice", "com.mitv.milinkservice", "com.cvte.dssshare.dlna", "org.droidtv.dlna"),
        new F("usb", T.t("💾 Фото и видео с флешки"), T.t("проводник, галерея, видеоплеер для USB"),
                "mediaexplorer|contentexplorer|filemanager|fileexplorer|gallery|mediacenter|\\.usb",
                "com.mitv.gallery", "com.xiaomi.mitv.mediaexplorer", "org.droidtv.contentexplorer", "com.cvte.tv.media"),
        new F("saver", T.t("🖼 Заставка с фото"), T.t("картинки на экране, пока телевизор простаивает"),
                "backdrop|\\.dream|daydream|screensaver|ambient",
                "com.google.android.backdrop", "com.mitv.dream", "com.yandex.tv.daydream"),
        new F("updates", T.t("🔄 Обновления прошивки"), T.t("автоматические обновления системы телевизора"),
                "updateservice|updater|\\.ota|fota|systemupdate|\\.oad",
                "com.xiaomi.mitv.updateservice", "com.yandex.launcher.updaterapp", "org.droidtv.oad", "com.easy.oad"),
        new F("browser", T.t("🌐 Браузер и «красная кнопка»"), T.t("сайты на телевизоре, интерактив на каналах (HbbTV)"),
                "browser|hbbtv|webcontent", "com.vewd.core.browserui", "org.droidtv.nettvbrowser", "com.yandex.browser.tv"),
        new F("music", T.t("🎵 Музыкальные приложения"), T.t("YouTube Music, Яндекс Музыка, Mi Music"),
                "tvmusic|\\.music|mimusic|spotify", "com.google.android.youtube.tvmusic", "com.yandex.tv.music", "com.xiaomi.mimusic2"),
        new F("smarthome", T.t("💡 Умный дом и колонки"), T.t("лампы Philips Hue, колонки DTS Play-Fi, Ambilight+Hue"),
                "\\.hue|alhue|playfi|smarthome", "org.droidtv.alhuewizard", "com.phorus.playfi.softap", "com.phorus.playfi.tv"),
        new F("games", T.t("🕹 Игры и геймпады"), T.t("Google Play Игры, облачные игры"),
                "play\\.games|boosteroid|gamepad|\\.games\\.", "com.google.android.play.games", "com.boosteroidtv.streaming"),
        new F("stores", T.t("📦 Магазин приложений"), T.t("ставлю и обновляю приложения (Google Play, магазин производителя и другие)"),
                "appstore|appmarket|\\.market|tvstore|appgallery|galaxyapps|rustore|aptoide|auroraoss|fdroid|\\.store\\b",
                "com.android.vending"),
        new F("access", T.t("♿ Спецвозможности"), T.t("озвучка экрана и прочее для людей с нарушениями зрения"),
                "talkback|accessibility|\\.tts", "com.google.android.marvin.talkback"),
    };

    /** Every installed app that opens market:// links, i.e. every app store, whatever its name. */
    static java.util.Set<String> stores(android.content.Context c) {
        java.util.Set<String> out = new java.util.HashSet<>();
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("market://details?id=com.example"));
        for (android.content.pm.ResolveInfo ri : c.getPackageManager().queryIntentActivities(i,
                android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS))
            out.add(ri.activityInfo.packageName);
        return out;
    }

    static F byId(String id) { for (F f : ALL) if (f.id.equals(id)) return f; return null; }

    static F of(String pkg) { for (F f : ALL) if (f.owns(pkg)) return f; return null; }
    /** Title without the leading emoji: old TV fonts lack many of them. */
    static String plain(String t) {
        int sp = t.indexOf(' ');
        return sp > 0 && sp <= 3 ? t.substring(sp + 1) : t;
    }
}
