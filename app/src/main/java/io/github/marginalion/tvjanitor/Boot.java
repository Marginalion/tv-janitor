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
    static final int JOB = 1;

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

    /** Set up at least once: there is something to re-apply. */
    static boolean setUp(Context c) { return new Store(c).has("snap_disabled"); }

    /** Every 15 minutes, surviving reboots: a near-free check that does nothing when the boot was handled. */
    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB) != null) return;
        js.schedule(new JobInfo.Builder(JOB, new ComponentName(c, BootJob.class))
                .setPeriodic(15 * 60 * 1000L).setPersisted(true).build());
    }
}
