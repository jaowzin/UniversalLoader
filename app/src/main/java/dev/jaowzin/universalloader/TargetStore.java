package dev.jaowzin.universalloader;

import android.content.Context;
import android.content.SharedPreferences;

final class TargetStore {
    private static final String PREFS = "universal_loader";
    private static final String KEY_PACKAGE = "selected_package";

    private TargetStore() {}

    static String getSelectedPackage(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String value = prefs.getString(KEY_PACKAGE, "");
        return value == null ? "" : value;
    }

    static void setSelectedPackage(Context context, String packageName) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PACKAGE, packageName == null ? "" : packageName)
                .apply();
    }
}
