package io.github.marginalion.tvjanitor;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/** Loads the translation before any screen or service is created; closes debugging once no screen is left. */
public final class App extends Application {
    private static volatile int started;
    static int visible() { return started; }

    @Override public void onCreate() {
        super.onCreate();
        T.init(this);
        Boot.schedule(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) { started++; }
            @Override public void onActivityStopped(Activity a) {
                if (--started > 0) return;
                // The owner left the app (from any screen): once jobs are done, close debugging (AdbGate).
                new Thread(() -> {
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                    while (MainActivity.busy()) { try { Thread.sleep(500); } catch (InterruptedException ignored) {} }
                    if (started == 0) AdbGate.close(App.this);
                }).start();
            }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityResumed(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }
}
