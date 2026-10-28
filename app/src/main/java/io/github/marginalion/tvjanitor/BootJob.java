package io.github.marginalion.tvjanitor;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.SystemClock;

/** The fallback start after boot, for firmware that doesn't deliver BOOT_COMPLETED to us. */
public class BootJob extends JobService {
    @Override public boolean onStartJob(JobParameters p) {
        Store s = new Store(this);
        boolean blockedBefore = s.flag("bootBlocked", false);
        // Nothing to do, or the receiver still has time (it starts 15 s after boot on most TVs).
        if (Boot.count(this) < 0 || !Boot.setUp(this) || Boot.done(this) || (SystemClock.elapsedRealtime() < 120000 && !blockedBefore)) {
            leave();
            return false;
        }
        if (!Boot.receiverCame(this)) s.setFlag("bootBlocked", true);
        new Thread(() -> {
            new Ops(this, null).bootRun("job");
            if (App.visible() == 0) AdbGate.close(this);
            jobFinished(p, false);
            leave();
        }).start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters p) { return false; }

    /** Leave nothing behind, as the rest of the app does, unless a screen is open. */
    private void leave() {
        new Thread(() -> {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            if (App.visible() == 0 && !MainActivity.busy()) android.os.Process.killProcess(android.os.Process.myPid());
        }).start();
    }
}
