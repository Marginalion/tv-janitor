package io.github.marginalion.tvjanitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Re-applies the owner's choices after boot, off the main thread, then stops. */
public class CleanerService extends Service {
    private volatile boolean running;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("c", T.t("Уборщик"), NotificationManager.IMPORTANCE_MIN));
        startForeground(1, new Notification.Builder(this, "c").setContentTitle(T.t("Уборщик"))
                .setContentText(T.t("Наводит порядок…")).setSmallIcon(android.R.drawable.ic_menu_manage).build());
        if (!running) {
            running = true;
            String reason = intent != null && intent.getStringExtra("reason") != null ? intent.getStringExtra("reason") : "service";
            new Thread(() -> {
                if (reason.endsWith("BOOT_COMPLETED")) { try { Thread.sleep(15000); } catch (InterruptedException ignored) {} }
                new Ops(this, null).bootRun(reason);
                running = false;
                stopForeground(true);
                stopSelf();
                // Nothing to keep after the boot run: free the memory now instead of waiting for Android.
                android.os.Process.killProcess(android.os.Process.myPid());
            }).start();
        }
        return START_NOT_STICKY;
    }
}
