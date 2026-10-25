package io.github.marginalion.tvjanitor;

import android.app.Application;

/** Loads the translation before any screen or service is created. */
public final class App extends Application {
    @Override public void onCreate() { super.onCreate(); T.init(this); }
}
