package io.github.marginalion.tvjanitor;

import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * Debugging open only while TV Janitor works. With WRITE_SECURE_SETTINGS (granted once over its own ADB) the app
 * switches Android's "USB debugging" (adb_enabled, which also carries network debugging) on before it connects and
 * off when it is done: after boot the port is open for about a minute, not all the time.
 *
 * It closes debugging only after a test on this TV has shown that it can open it again by itself ("adbCycleOk"),
 * and never when the owner asked to keep it on ("keepAdb").
 */
final class AdbGate {
    static final String PERM = "android.permission.WRITE_SECURE_SETTINGS";

    static boolean can(Context c) { return c.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED; }

    static boolean isOn(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
    }

    static boolean devOn(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
    }

    /**
     * Before connecting: switch debugging on if the app may and it is off. Some firmware switches Developer options
     * off at every boot, and Android keeps debugging off without them: then those are switched on too, and off
     * again by close(). True if anything was switched on now.
     */
    static boolean open(Context c) {
        if (!can(c)) return false;
        boolean changed = false;
        try {
            if (!devOn(c)) {
                Settings.Global.putInt(c.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1);
                new Store(c).setFlag("devOpenedByUs", true);
                changed = true;
            }
            if (!isOn(c)) changed |= Settings.Global.putInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
        } catch (SecurityException e) { return false; }
        return changed;
    }

    /** Whether closing is allowed on this TV: tested, and not kept on by the owner. */
    static boolean mayClose(Context c) {
        Store s = new Store(c);
        return can(c) && s.flag("adbCycleOk", false) && !s.flag("keepAdb", true);
    }

    /** When done: switch debugging off. Waits for any running job (they hold Ops.LOCK). */
    static void close(Context c) {
        if (!mayClose(c)) return;
        synchronized (Ops.LOCK) {
            try {
                if (isOn(c)) Settings.Global.putInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0);
                // Developer options go back off only if the app switched them on (the firmware wants them off).
                Store s = new Store(c);
                if (s.flag("devOpenedByUs", false)) {
                    Settings.Global.putInt(c.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0);
                    s.setFlag("devOpenedByUs", false);
                }
            } catch (SecurityException ignored) {}
        }
    }

    /** Switch off without the checks: only for the cycle test, which switches it on again at once. */
    static void forceOff(Context c) {
        try { Settings.Global.putInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0); } catch (SecurityException ignored) {}
    }
}
