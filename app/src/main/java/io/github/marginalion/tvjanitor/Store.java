package io.github.marginalion.tvjanitor;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;

/** The owner's choices, kept on the TV. */
final class Store {
    private final SharedPreferences p;
    Store(Context c) { p = c.getSharedPreferences("choices", Context.MODE_PRIVATE); }

    Set<String> disabled() { return new HashSet<>(p.getStringSet("disabled", new HashSet<>())); }
    void setDisabled(Set<String> s) { p.edit().putStringSet("disabled", new HashSet<>(s)).apply(); }
    /** Kept installed and enabled, but stopped after boot and not allowed to run in the background. */
    Set<String> sleeping() { return new HashSet<>(p.getStringSet("sleep", new HashSet<>())); }
    void setSleeping(Set<String> s) { p.edit().putStringSet("sleep", new HashSet<>(s)).apply(); }
    Set<String> set(String key) { return new HashSet<>(p.getStringSet(key, new HashSet<>())); }
    void setSet(String key, Set<String> s) { p.edit().putStringSet(key, new HashSet<>(s)).apply(); }
    boolean has(String key) { return p.contains(key); }
    boolean flag(String key, boolean def) { return p.getBoolean(key, def); }
    void setFlag(String key, boolean v) { p.edit().putBoolean(key, v).apply(); }
    String str(String key) { return p.getString(key, null); }
    void setStr(String key, String v) { p.edit().putString(key, v).apply(); }
}
