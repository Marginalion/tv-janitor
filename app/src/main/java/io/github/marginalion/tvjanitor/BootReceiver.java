package io.github.marginalion.tvjanitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    /**
     * From our launcher, once per its start (so at every boot while it is Home). After a force-stop Android gives
     * TV Janitor no boot broadcast and drops its jobs until it is opened by hand; debugging left open by the stopped
     * run then stayed open (rc1 remark 6). This closes it and brings the boot run back. Anyone may send it: it only
     * does what a boot would do anyway.
     */
    static final String HOME_UP = "io.github.marginalion.tvjanitor.HOME_UP";

    private static void homeUp(Context c, PendingResult done) {
        new Thread(() -> {
            try {
                AdbGate.afterCrash(c);
                if (Boot.setUp(c)) { Boot.schedule(c); if (!Boot.done(c)) Boot.afterRestart(c); }
            } catch (Exception ignored) {
            } finally { done.finish(); }
        }).start();
    }

    @Override public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (HOME_UP.equals(a)) { homeUp(c, goAsync()); return; }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        // Updated by TV Janitor itself: only reopen it (CleanerService "updated"); otherwise nothing to do on an update.
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(a) && (Edition.LITE || !Ops.ownUpdateLanded(c))) return;
        Intent i = new Intent(c, CleanerService.class).putExtra("reason", String.valueOf(intent.getAction()));
        c.startForegroundService(i);
    }
}
