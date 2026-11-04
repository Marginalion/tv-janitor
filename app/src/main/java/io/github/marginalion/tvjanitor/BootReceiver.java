package io.github.marginalion.tvjanitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        // Updated by TV Janitor itself: only reopen it (CleanerService "updated"); otherwise nothing to do on an update.
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(a) && (Edition.LITE || !Ops.ownUpdateLanded(c))) return;
        Intent i = new Intent(c, CleanerService.class).putExtra("reason", String.valueOf(intent.getAction()));
        c.startForegroundService(i);
    }
}
