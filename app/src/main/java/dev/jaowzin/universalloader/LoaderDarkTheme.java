package dev.jaowzin.universalloader;

import android.app.Activity;
import android.app.Application;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.android.material.card.MaterialCardView;

final class LoaderDarkTheme {
    private static final String TOGGLE_TAG = "ul_dark_mode_toggle";

    private static final int BLUE = Color.rgb(82, 126, 239);
    private static final int BLUE_DARK = Color.rgb(69, 109, 218);
    private static final int LIGHT_BG = Color.rgb(246, 247, 249);
    private static final int LIGHT_TEXT_A = Color.rgb(53, 59, 69);
    private static final int LIGHT_TEXT_B = Color.rgb(48, 54, 64);
    private static final int LIGHT_MUTED = Color.rgb(125, 132, 143);
    private static final int LIGHT_BLUE_A = Color.rgb(233, 239, 255);
    private static final int LIGHT_BLUE_B = Color.rgb(237, 242, 255);

    private static final int DARK_BG = Color.rgb(17, 19, 24);
    private static final int DARK_SURFACE = Color.rgb(28, 31, 38);
    private static final int DARK_SURFACE_2 = Color.rgb(35, 40, 50);
    private static final int DARK_TEXT = Color.rgb(232, 235, 241);
    private static final int DARK_MUTED = Color.rgb(154, 163, 178);
    private static final int DARK_STROKE = Color.rgb(55, 61, 72);

    private LoaderDarkTheme() {}

    static void register(Application application) {
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
            @Override public void onActivityStarted(Activity activity) {}

            @Override
            public void onActivityResumed(Activity activity) {
                if (!isLoaderActivity(activity)) return;
                apply(activity);
                if (activity instanceof LauncherActivity) attachToggle(activity);
            }

            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }

    private static boolean isLoaderActivity(Activity activity) {
        return activity != null
                && activity.getClass().getName().startsWith("dev.jaowzin.universalloader.");
    }

    private static void apply(Activity activity) {
        boolean dark = ThemePrefs.isDark(activity);
        Window window = activity.getWindow();
        window.setStatusBarColor(dark ? Color.rgb(24, 28, 36) : BLUE_DARK);
        window.setNavigationBarColor(dark ? DARK_BG : LIGHT_BG);
        window.getDecorView().setSystemUiVisibility(
                dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        View content = window.getDecorView().findViewById(android.R.id.content);
        if (content != null) styleTree(content, dark);
    }

    private static void styleTree(View view, boolean dark) {
        if (view == null) return;

        if (view instanceof MaterialCardView) {
            MaterialCardView card = (MaterialCardView) view;
            card.setCardBackgroundColor(dark ? DARK_SURFACE : Color.WHITE);
            card.setStrokeColor(dark ? DARK_STROKE : Color.rgb(225, 228, 234));
        }

        Drawable background = view.getBackground();
        if (background instanceof ColorDrawable) {
            int source = ((ColorDrawable) background).getColor();
            Integer mapped = mapBackground(source, dark);
            if (mapped != null) view.setBackgroundColor(mapped);
        } else if (background instanceof GradientDrawable) {
            GradientDrawable gradient = (GradientDrawable) background.mutate();
            ColorStateList colors = gradient.getColor();
            if (colors != null) {
                Integer mapped = mapBackground(colors.getDefaultColor(), dark);
                if (mapped != null) {
                    gradient.setColor(mapped);
                    if (dark && (mapped == DARK_SURFACE || mapped == DARK_SURFACE_2)) {
                        gradient.setStroke(dp(view, 1), DARK_STROKE);
                    }
                }
            }
        }

        if (view instanceof TextView) {
            TextView text = (TextView) view;
            int current = text.getCurrentTextColor();
            if (dark) {
                if (same(current, LIGHT_TEXT_A) || same(current, LIGHT_TEXT_B) || same(current, Color.DKGRAY)) {
                    text.setTextColor(DARK_TEXT);
                } else if (same(current, LIGHT_MUTED)) {
                    text.setTextColor(DARK_MUTED);
                }
                if (text instanceof EditText) {
                    EditText edit = (EditText) text;
                    edit.setTextColor(DARK_TEXT);
                    edit.setHintTextColor(DARK_MUTED);
                    edit.setBackground(inputBackground(true));
                }
            } else if (text instanceof EditText) {
                EditText edit = (EditText) text;
                edit.setTextColor(LIGHT_TEXT_B);
                edit.setHintTextColor(LIGHT_MUTED);
                edit.setBackground(inputBackground(false));
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                styleTree(group.getChildAt(i), dark);
            }
        }
    }

    private static Integer mapBackground(int color, boolean dark) {
        if (dark) {
            if (same(color, LIGHT_BG)) return DARK_BG;
            if (same(color, Color.WHITE)) return DARK_SURFACE;
            if (same(color, LIGHT_BLUE_A) || same(color, LIGHT_BLUE_B)) return DARK_SURFACE_2;
            return null;
        }
        if (same(color, DARK_BG)) return LIGHT_BG;
        if (same(color, DARK_SURFACE)) return Color.WHITE;
        if (same(color, DARK_SURFACE_2)) return LIGHT_BLUE_A;
        return null;
    }

    private static GradientDrawable inputBackground(boolean dark) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(dark ? DARK_SURFACE : Color.WHITE);
        drawable.setCornerRadius(12f);
        drawable.setStroke(1, dark ? DARK_STROKE : Color.rgb(225, 228, 234));
        return drawable;
    }

    private static void attachToggle(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) return;
        ViewGroup root = (ViewGroup) decor;
        if (root.findViewWithTag(TOGGLE_TAG) != null) return;

        TextView toggle = new TextView(activity);
        toggle.setTag(TOGGLE_TAG);
        toggle.setText(ThemePrefs.isDark(activity) ? "☀" : "☾");
        toggle.setTextColor(Color.WHITE);
        toggle.setTextSize(18f);
        toggle.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(230, 50, 55, 68));
        bg.setCornerRadius(dp(activity, 22));
        toggle.setBackground(bg);
        toggle.setElevation(dp(activity, 8));
        toggle.setOnClickListener(v -> {
            ThemePrefs.setDark(activity, !ThemePrefs.isDark(activity));
            activity.recreate();
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(activity, 44), dp(activity, 44), Gravity.END | Gravity.BOTTOM);
        lp.rightMargin = dp(activity, 16);
        lp.bottomMargin = dp(activity, 18);
        activity.addContentView(toggle, lp);
    }

    private static boolean same(int a, int b) {
        return (a & 0x00ffffff) == (b & 0x00ffffff);
    }

    private static int dp(View view, float value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    private static int dp(Activity activity, float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
