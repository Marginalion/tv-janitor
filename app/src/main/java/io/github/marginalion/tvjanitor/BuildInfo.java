package io.github.marginalion.tvjanitor;

final class BuildInfo {
    /** A build for testers (…-dev…, …-test…): network debugging stays open by default, for the test kit. */
    static boolean testBuild(android.content.Context c) {
        try {
            String v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return v.contains("-dev") || v.contains("-test");
        } catch (Exception e) { return false; }
    }

    static final String SELF = "io.github.marginalion.tvjanitor";
    static final String LAUNCHER = "com.gathin.tvhome";
    static final String LAUNCHER_HOME = "com.gathin.tvhome/.MainActivity";
    /** Stock launchers that outrank a new Home (priority 2) and have a FallbackHome behind them. */
    static final String[] SAFE_TO_DISABLE_HOME = {"com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcherx", "com.google.android.leanbacklauncher"};
    /** Stock Homes in the order we look for them, installed or disabled. */
    static final String[] KNOWN_STOCK_HOMES = {"com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcherx", "com.google.android.leanbacklauncher", "com.yandex.tv.home"};
}
