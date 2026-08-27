package dev.jaowzin.universalloader;

import android.content.Context;

final class ThemePrefs {
    private static final String PREFS = "universal_loader_ui";
    private static final String KEY_DARK = "dark_mode";

    private ThemePrefs() {}

    static boolean isDark(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DARK, false);
    }

    static void setDark(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DARK, enabled)
                .apply();
    }
}
