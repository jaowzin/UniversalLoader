package dev.jaowzin.universalloader;

import android.app.Activity;
import android.app.Application;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Small host API that native plugins can opt into from JNI_OnLoad.
 *
 * NativePluginRuntime exposes the guest Application only for the duration of System.load(). A
 * plugin can call installFromNative() during JNI_OnLoad to register a harmless floating test menu
 * for that guest application. No gameplay/app methods are hooked or modified here.
 */
public final class FloatingMenuHost {
    private static final Object LOCK = new Object();
    private static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();
    private static final Map<Application, State> INSTALLED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private FloatingMenuHost() {}

    static void prepare(Application application, String packageName, String processName) {
        PENDING.set(new Pending(application, packageName, processName));
    }

    static void clearPending() {
        PENDING.remove();
    }

    /** Called by a native plugin from JNI_OnLoad. */
    public static void installFromNative() {
        Pending pending = PENDING.get();
        if (pending == null || pending.application == null) return;

        synchronized (LOCK) {
            if (INSTALLED.containsKey(pending.application)) return;
            State state = new State(pending.packageName, pending.processName);
            INSTALLED.put(pending.application, state);
            pending.application.registerActivityLifecycleCallbacks(
                    new Callbacks(pending.application, state));
        }
    }

    private static final class Callbacks implements Application.ActivityLifecycleCallbacks {
        private final Application application;
        private final State state;

        Callbacks(Application application, State state) {
            this.application = application;
            this.state = state;
        }

        @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
        @Override public void onActivityStarted(Activity activity) {}

        @Override
        public void onActivityResumed(Activity activity) {
            if (activity == null || activity.isFinishing()) return;
            if (state.packageName != null && !state.packageName.isEmpty()
                    && !state.packageName.equals(activity.getPackageName())) {
                return;
            }
            attach(activity, state);
        }

        @Override
        public void onActivityPaused(Activity activity) {
            detach(activity);
        }

        @Override public void onActivityStopped(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

        @Override
        public void onActivityDestroyed(Activity activity) {
            detach(activity);
        }
    }

    private static void attach(Activity activity, State state) {
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) return;
        ViewGroup root = (ViewGroup) decor;
        String tag = "universal_loader_floating_menu";
        if (root.findViewWithTag(tag) != null) return;

        FrameLayout container = new FrameLayout(activity);
        container.setTag(tag);
        container.setClipChildren(false);
        container.setClipToPadding(false);

        TextView bubble = new TextView(activity);
        bubble.setText("UL");
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(14f);
        bubble.setTypeface(Typeface.DEFAULT_BOLD);
        bubble.setGravity(Gravity.CENTER);
        bubble.setBackground(roundRect(Color.rgb(82, 126, 239), dp(activity, 28)));
        bubble.setElevation(dp(activity, 8));

        FrameLayout.LayoutParams bubbleLp = new FrameLayout.LayoutParams(
                dp(activity, 56), dp(activity, 56), Gravity.TOP | Gravity.END);
        bubbleLp.topMargin = dp(activity, 110);
        bubbleLp.rightMargin = dp(activity, 16);
        container.addView(bubble, bubbleLp);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 14));
        panel.setBackground(roundRect(Color.argb(245, 28, 31, 38), dp(activity, 16)));
        panel.setElevation(dp(activity, 10));
        panel.setVisibility(View.GONE);

        TextView title = new TextView(activity);
        title.setText("UniversalLoader plugin");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        panel.addView(title);

        TextView status = new TextView(activity);
        String process = state.processName == null || state.processName.isEmpty()
                ? "all app processes" : state.processName;
        status.setText("Floating menu active\nProcess: " + process);
        status.setTextColor(Color.rgb(190, 197, 210));
        status.setTextSize(12f);
        status.setPadding(0, dp(activity, 6), 0, dp(activity, 10));
        panel.addView(status);

        TextView close = new TextView(activity);
        close.setText("Hide menu");
        close.setTextColor(Color.rgb(130, 167, 255));
        close.setTextSize(13f);
        close.setTypeface(Typeface.DEFAULT_BOLD);
        close.setPadding(0, dp(activity, 6), 0, dp(activity, 4));
        close.setOnClickListener(v -> panel.setVisibility(View.GONE));
        panel.addView(close);

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                dp(activity, 230), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        panelLp.topMargin = dp(activity, 176);
        panelLp.rightMargin = dp(activity, 16);
        container.addView(panel, panelLp);

        final float[] down = new float[4];
        bubble.setOnTouchListener((v, event) -> {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bubble.getLayoutParams();
            FrameLayout.LayoutParams pp = (FrameLayout.LayoutParams) panel.getLayoutParams();
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                down[0] = event.getRawX();
                down[1] = event.getRawY();
                down[2] = lp.rightMargin;
                down[3] = lp.topMargin;
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx = event.getRawX() - down[0];
                float dy = event.getRawY() - down[1];
                lp.rightMargin = Math.max(0, Math.round(down[2] - dx));
                lp.topMargin = Math.max(0, Math.round(down[3] + dy));
                bubble.setLayoutParams(lp);
                pp.rightMargin = lp.rightMargin;
                pp.topMargin = lp.topMargin + dp(activity, 66);
                panel.setLayoutParams(pp);
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                float moved = Math.abs(event.getRawX() - down[0]) + Math.abs(event.getRawY() - down[1]);
                if (moved < dp(activity, 10)) {
                    panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                }
                return true;
            }
            return false;
        });

        activity.addContentView(container, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static void detach(Activity activity) {
        if (activity == null) return;
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) return;
        ViewGroup root = (ViewGroup) decor;
        View existing = root.findViewWithTag("universal_loader_floating_menu");
        if (existing != null && existing.getParent() instanceof ViewGroup) {
            ((ViewGroup) existing.getParent()).removeView(existing);
        }
    }

    private static GradientDrawable roundRect(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private static int dp(Activity activity, float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static final class Pending {
        final Application application;
        final String packageName;
        final String processName;

        Pending(Application application, String packageName, String processName) {
            this.application = application;
            this.packageName = packageName == null ? "" : packageName;
            this.processName = processName == null ? "" : processName;
        }
    }

    private static final class State {
        final String packageName;
        final String processName;

        State(String packageName, String processName) {
            this.packageName = packageName == null ? "" : packageName;
            this.processName = processName == null ? "" : processName;
        }
    }
}
