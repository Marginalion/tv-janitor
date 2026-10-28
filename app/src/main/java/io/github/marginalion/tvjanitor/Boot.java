package io.github.marginalion.tvjanitor;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * Which boot this is, whether it has been handled, and the fallback for firmware that keeps BOOT_COMPLETED away
 * from third-party apps (Sunwind/CVTE with Yandex TV): a persisted job that Android itself restores after boot.
 */
final class Boot {
    static final int JOB = 1, AFTER_RESTART = 2;

    /** Android's own boot counter; -1 if the firmware doesn't keep it. */
    static int count(Context c) { return Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1); }

    static long upMinutes() { return SystemClock.elapsedRealtime() / 60000; }

    /** The choices were re-applied after this boot. */
    static boolean done(Context c) {
        int n = count(c);
        return n >= 0 && String.valueOf(n).equals(new Store(c).str("bootDone"));
    }

    /** The boot receiver at least started after this boot. */
    static boolean receiverCame(Context c) {
        int n = count(c);
        return n >= 0 && String.valueOf(n).equals(new Store(c).str("bootTried"));
    }

    /**
     * After a restart the app asked for: if the TV came up much later than a restart takes, the firmware treated
     * "reboot" as "power off" and the owner switched it on by hand (Sunwind). Remembered for the screen and report.
     */
    static void checkReboot(Context c) {
        Store s = new Store(c);
        String asked = s.str("rebootAskedAt");
        if (asked == null) return;
        s.setStr("rebootAskedAt", null);
        long bootAt = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        long gap = bootAt - Long.parseLong(asked);
        if (gap < 0 || gap > 24L * 3600 * 1000) return;        // clock not set yet, or too long ago to tell
        if (gap > 3 * 60 * 1000) s.setFlag("rebootPowersOff", true);
        else if (gap < 2 * 60 * 1000) s.setFlag("rebootPowersOff", false);
    }

    /** Set up at least once: there is something to re-apply. */
    static boolean setUp(Context c) { return new Store(c).has("snap_disabled"); }

    /** Every 15 minutes, surviving reboots: a near-free check that does nothing when the boot was handled. */
    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB) != null) return;
        js.schedule(new JobInfo.Builder(JOB, new ComponentName(c, BootJob.class))
                .setPeriodic(15 * 60 * 1000L).setPersisted(true).build());
    }

    /**
     * Just before a restart the app asked for: a one-off job that Android restores after boot and runs as soon as
     * the network is up, so a firmware without the boot signal (Sunwind) waits about a minute, not up to 15.
     * Not due for 90 s, so it can't fire before the TV goes down; after boot that time has long passed.
     */
    static void afterRestart(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        js.schedule(new JobInfo.Builder(AFTER_RESTART, new ComponentName(c, BootJob.class))
                .setMinimumLatency(90 * 1000L).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build());
    }
}
