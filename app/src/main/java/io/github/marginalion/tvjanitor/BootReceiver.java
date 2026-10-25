package io.github.marginalion.tvjanitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Intent i = new Intent(c, CleanerService.class).putExtra("reason", String.valueOf(intent.getAction()));
        c.startForegroundService(i);
    }
}
