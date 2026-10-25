package io.github.marginalion.tvjanitor;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Finds, on any TV, what the TV itself relies on (CleanYourTV PROMPT.md rules), and guesses telemetry by name. */
final class Detector {
    /** Android's own core: the TV may not boot without these. Never offered, not even in expert mode. */
    static boolean core(String pkg) {
        if (pkg.equals("android") || pkg.equals(BuildInfo.SELF)) return true;
        String[] exact = {"com.android.systemui", "com.android.shell", "com.android.phone", "com.android.settings",
                "com.android.tv.settings", "com.android.packageinstaller", "com.google.android.packageinstaller",
                "com.android.permissioncontroller", "com.google.android.permissioncontroller",
                "com.android.ext.services", "com.google.android.ext.services", "com.android.ext.shared",
                "com.google.android.ext.shared", "com.android.modulemetadata", "com.google.android.modulemetadata",
                "com.android.vending", "com.google.android.gms", "com.google.android.gsf", "com.android.bluetooth",
                "com.android.inputdevices", "com.android.location.fused", "com.android.keychain",
                "com.google.android.webview", "com.android.webview", "com.android.networkstack.inprocess",
                "com.android.wifi.resources", "com.android.se", "com.android.externalstorage",
                "com.android.defcontainer", "com.android.media", "com.android.server.telecom"};
        for (String e : exact) if (pkg.equals(e)) return true;
        if (pkg.startsWith("com.android.providers.")) {
            return !(pkg.endsWith(".calendar") || pkg.endsWith(".contacts") || pkg.endsWith(".userdictionary") || pkg.endsWith(".blockednumber"));
        }
        return pkg.startsWith("com.google.android.overlay.modules") || pkg.startsWith("com.android.networkstack")
                || pkg.startsWith("android.") || pkg.contains(".auto_generated_rro") || pkg.endsWith(".overlay")
                || pkg.startsWith("com.android.wifi") || pkg.equals("com.android.connectivity.resources");
    }

    /** Runtime resource overlays: vendor tweaks to system settings and looks; tiny, risky to disable. */
    static boolean overlay(String pkg) {
        return pkg.contains(".rro.") || pkg.endsWith(".rro") || pkg.contains("overlay") || pkg.contains(".rro_");
    }

    /** Why the TV may need this package, or null. These are protected unless expert mode is on. */
    static Map<String, String> important(Context c) {
        Map<String, String> m = new HashMap<>();
        PackageManager pm = c.getPackageManager();
        int flags = PackageManager.MATCH_DISABLED_COMPONENTS;
        for (ResolveInfo ri : pm.queryIntentServices(new Intent("android.media.tv.TvInputService"), flags))
            m.put(ri.serviceInfo.packageName, T.t("источник ТВ: HDMI, антенна или эфир"));
        try {
            InputMethodManager imm = c.getSystemService(InputMethodManager.class);
            for (InputMethodInfo i : imm.getInputMethodList()) m.put(i.getPackageName(), T.t("экранная клавиатура"));
        } catch (Exception ignored) {}
        String acc = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (acc != null) for (String s : acc.split(":")) if (s.contains("/")) m.put(s.substring(0, s.indexOf('/')), T.t("служба спецвозможностей: на ней часто держатся кнопки пульта"));
        List<ApplicationInfo> apps = pm.getInstalledApplications(flags);
        Pattern remote = Pattern.compile("bluetooth|\\.bt\\.|remote|rcu|keyhandler|hotkey|globalkey|\\.key\\.|inputservice|tvinput|tvservice|tvcenter|livetv|playtv|channel|tuner|dtv|source|cec", Pattern.CASE_INSENSITIVE);
        for (ApplicationInfo ai : apps) {
            if ((ai.flags & ApplicationInfo.FLAG_PERSISTENT) != 0) m.putIfAbsent(ai.packageName, T.t("постоянная системная служба"));
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && remote.matcher(ai.packageName).find())
                m.putIfAbsent(ai.packageName, T.t("похоже на пульт, Bluetooth, входы или каналы"));
            String n = ai.packageName.toLowerCase();
            if (n.contains("factory") || n.contains(".fac.") || n.endsWith(".fac") || n.contains("factorymenu"))
                m.putIfAbsent(ai.packageName, T.t("заводское меню: не трогать и не открывать"));
        }
        // The current Home and anything that can take Home over (setup wizards).
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : pm.queryIntentActivities(home, flags)) {
            String p = ri.activityInfo.packageName;
            if (p.contains("setupwraith") || p.contains("setupwizard")) m.putIfAbsent(p, T.t("мастер настройки: может перехватить «Домой» и забрать Wi-Fi"));
        }
        return m;
    }

    private static final Pattern TELEMETRY = Pattern.compile(
            "analytic|statistic|telemetry|tracker|metrica|feedback|bugreport|crashreport|usagelog|logger|\\.log\\.|"
                    + "\\.ads?\\b|\\.ad\\.|advert|advid|promo|\\.demo|demome|retail|sticker|recommend", Pattern.CASE_INSENSITIVE);

    /** A guess from the name only; shown as a hint and used by the presets. */
    static boolean looksLikeTelemetry(String pkg) { return TELEMETRY.matcher(pkg).find(); }
}
