package dev.jaowzin.universalloader;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import androidx.appcompat.view.WindowCallbackWrapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CTF-only Carrom plugin host.
 *
 * Native code reads only the game's live aim angle/power. Board, striker, pieces and pockets are
 * detected in screen space from the real rendered SurfaceView. This intentionally avoids the old
 * world-to-screen scale guesses that made the previous trajectory overlay drift or point at the
 * wrong objects.
 */
public final class CarromCtfHost {
    private static final String TAG = "ULCarromCTF";
    private static final String OVERLAY_TAG = "ul_carrom_ctf_overlay";
    private static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();
    private static final Map<Application, State> INSTALLED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private CarromCtfHost() {}

    static void prepare(Application application, String packageName, String processName) {
        PENDING.set(new Pending(application, packageName, processName));
    }

    static void clearPending() {
        PENDING.remove();
    }

    /** Called by libul_carrom_ctf.so from JNI_OnLoad. */
    public static void installFromNative() {
        Pending pending = PENDING.get();
        if (pending == null || pending.application == null) return;
        synchronized (INSTALLED) {
            if (INSTALLED.containsKey(pending.application)) return;
            State state = new State(pending.packageName, pending.processName);
            INSTALLED.put(pending.application, state);
            pending.application.registerActivityLifecycleCallbacks(new Callbacks(state));
        }
    }

    private static final class Callbacks implements Application.ActivityLifecycleCallbacks {
        private final State state;

        Callbacks(State state) {
            this.state = state;
        }

        @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
        @Override public void onActivityStarted(Activity activity) {}

        @Override
        public void onActivityResumed(Activity activity) {
            if (activity == null || activity.isFinishing()) return;
            if (!state.packageName.isEmpty() && !state.packageName.equals(activity.getPackageName())) return;
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
        Window window = activity.getWindow();
        if (window == null) return;
        View decor = window.getDecorView();
        if (!(decor instanceof ViewGroup)) return;
        ViewGroup root = (ViewGroup) decor;
        if (root.findViewWithTag(OVERLAY_TAG) != null) return;

        TrajectoryOverlay overlay = new TrajectoryOverlay(activity, state);
        overlay.setTag(OVERLAY_TAG);
        activity.addContentView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        Window.Callback callback = window.getCallback();
        if (!(callback instanceof TouchObserverCallback)) {
            window.setCallback(new TouchObserverCallback(callback, overlay));
        }
        overlay.start();
    }

    private static void detach(Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        Window window = activity.getWindow();
        Window.Callback callback = window.getCallback();
        if (callback instanceof TouchObserverCallback) {
            window.setCallback(((TouchObserverCallback) callback).wrapped());
        }

        View decor = window.getDecorView();
        if (!(decor instanceof ViewGroup)) return;
        View existing = ((ViewGroup) decor).findViewWithTag(OVERLAY_TAG);
        if (existing instanceof TrajectoryOverlay) {
            ((TrajectoryOverlay) existing).stop();
        }
        if (existing != null && existing.getParent() instanceof ViewGroup) {
            ((ViewGroup) existing.getParent()).removeView(existing);
        }
    }

    private static final class TouchObserverCallback extends WindowCallbackWrapper {
        private final Window.Callback original;
        private final TrajectoryOverlay overlay;

        TouchObserverCallback(Window.Callback original, TrajectoryOverlay overlay) {
            super(original);
            this.original = original;
            this.overlay = overlay;
        }

        Window.Callback wrapped() {
            return original;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            overlay.observeGameTouch(event);
            return super.dispatchTouchEvent(event);
        }
    }

    private static final class TrajectoryOverlay extends View {
        private final Activity activity;
        private final State state;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ExecutorService visionWorker = Executors.newSingleThreadExecutor();
        private final AtomicBoolean captureBusy = new AtomicBoolean(false);

        private final Paint line = stroke(Color.argb(245, 42, 242, 96), 3.2f);
        private final Paint lineHalo = stroke(Color.argb(70, 42, 242, 96), 9f);
        private final Paint objectLine = stroke(Color.argb(245, 85, 255, 120), 2.8f);
        private final Paint residualLine = stroke(Color.argb(190, 255, 96, 72), 2.2f);
        private final Paint candidateLine = stroke(Color.argb(105, 42, 242, 96), 1.8f);
        private final Paint detectedRing = stroke(Color.argb(210, 0, 220, 255), 1.7f);
        private final Paint pocketRing = stroke(Color.argb(230, 255, 224, 46), 2.5f);
        private final Paint bubbleFill = fill(Color.argb(245, 24, 27, 34));
        private final Paint panelFill = fill(Color.argb(247, 22, 25, 31));
        private final Paint text = fill(Color.WHITE);
        private final Paint muted = fill(Color.rgb(180, 188, 202));
        private final Paint green = fill(Color.rgb(76, 235, 118));
        private final Paint yellow = fill(Color.rgb(255, 214, 76));

        private volatile FrameState frame;
        private Bitmap captureBitmap;
        private boolean stopped;
        private boolean menuOpen;
        private boolean linesEnabled = true;
        private boolean bankEnabled = true;
        private boolean pocketsEnabled = true;
        private boolean candidatesEnabled;
        private boolean debugDiscs = true;

        private float touchStartX;
        private float touchStartY;
        private float touchCurrentX;
        private float touchCurrentY;
        private boolean touchActive;

        TrajectoryOverlay(Activity activity, State state) {
            super(activity);
            this.activity = activity;
            this.state = state;
            setWillNotDraw(false);
            setClickable(true);
            line.setStrokeCap(Paint.Cap.ROUND);
            lineHalo.setStrokeCap(Paint.Cap.ROUND);
            objectLine.setStrokeCap(Paint.Cap.ROUND);
            residualLine.setStrokeCap(Paint.Cap.ROUND);
            candidateLine.setStrokeCap(Paint.Cap.ROUND);
            text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }

        void start() {
            try { nativeStart(); } catch (Throwable error) { Log.w(TAG, "nativeStart", error); }
            main.post(this::captureTick);
            postInvalidateOnAnimation();
        }

        void stop() {
            stopped = true;
            main.removeCallbacksAndMessages(null);
            visionWorker.shutdownNow();
            Bitmap bitmap = captureBitmap;
            captureBitmap = null;
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }

        void observeGameTouch(MotionEvent event) {
            if (event == null) return;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                touchStartX = event.getX();
                touchStartY = event.getY();
                touchCurrentX = touchStartX;
                touchCurrentY = touchStartY;
                touchActive = true;
            } else if (action == MotionEvent.ACTION_MOVE) {
                touchCurrentX = event.getX();
                touchCurrentY = event.getY();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                touchCurrentX = event.getX();
                touchCurrentY = event.getY();
                main.postDelayed(() -> touchActive = false, 700L);
            }
            invalidate();
        }

        private void captureTick() {
            if (stopped) return;
            if (!captureBusy.compareAndSet(false, true)) {
                main.postDelayed(this::captureTick, 150L);
                return;
            }

            SurfaceView surface = findLargestSurface(activity.getWindow().getDecorView());
            int width;
            int height;
            int offsetX = 0;
            int offsetY = 0;
            if (surface != null && surface.getHolder().getSurface().isValid()
                    && surface.getWidth() > 80 && surface.getHeight() > 80) {
                width = surface.getWidth();
                height = surface.getHeight();
                int[] src = new int[2];
                int[] root = new int[2];
                surface.getLocationInWindow(src);
                activity.getWindow().getDecorView().getLocationInWindow(root);
                offsetX = src[0] - root[0];
                offsetY = src[1] - root[1];
            } else {
                surface = null;
                View decor = activity.getWindow().getDecorView();
                width = decor.getWidth();
                height = decor.getHeight();
            }

            if (width < 80 || height < 80) {
                captureBusy.set(false);
                main.postDelayed(this::captureTick, 350L);
                return;
            }

            if (captureBitmap == null || captureBitmap.isRecycled()
                    || captureBitmap.getWidth() != width || captureBitmap.getHeight() != height) {
                if (captureBitmap != null && !captureBitmap.isRecycled()) captureBitmap.recycle();
                captureBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            }

            final SurfaceView source = surface;
            final int fx = offsetX;
            final int fy = offsetY;
            PixelCopy.OnPixelCopyFinishedListener listener = result -> {
                if (result != PixelCopy.SUCCESS || stopped) {
                    captureBusy.set(false);
                    main.postDelayed(this::captureTick, 350L);
                    return;
                }
                Bitmap bitmap = captureBitmap;
                visionWorker.execute(() -> {
                    FrameState detected = Vision.analyze(bitmap, fx, fy);
                    main.post(() -> {
                        if (!stopped) {
                            frame = detected;
                            invalidate();
                        }
                        captureBusy.set(false);
                        if (!stopped) main.postDelayed(this::captureTick, 330L);
                    });
                });
            };

            try {
                if (source != null) {
                    PixelCopy.request(source, captureBitmap, listener, main);
                } else {
                    PixelCopy.request(activity.getWindow(), captureBitmap, listener, main);
                }
            } catch (Throwable error) {
                captureBusy.set(false);
                main.postDelayed(this::captureTick, 500L);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            FrameState current = frame;
            NativeState nativeState = readNative();

            if (current != null) {
                if (pocketsEnabled) {
                    for (PointF pocket : current.pockets) {
                        canvas.drawCircle(pocket.x, pocket.y, current.pocketRadius, pocketRing);
                    }
                }
                if (debugDiscs) {
                    for (Disc disc : current.discs) {
                        canvas.drawCircle(disc.x, disc.y, disc.radius, detectedRing);
                    }
                    if (current.striker != null) {
                        canvas.drawCircle(current.striker.x, current.striker.y,
                                current.striker.radius * 1.08f, pocketRing);
                    }
                }

                if (candidatesEnabled && current.striker != null) {
                    drawCandidates(canvas, current);
                }
                if (linesEnabled && current.striker != null && nativeState.usable()) {
                    drawLiveTrajectory(canvas, current, nativeState);
                    postInvalidateOnAnimation();
                }
            }

            drawMenu(canvas, current, nativeState);
        }

        private void drawLiveTrajectory(Canvas canvas, FrameState f, NativeState ns) {
            PointF origin = new PointF(f.striker.x, f.striker.y);
            float dx = (float) Math.cos(ns.angle);
            float dy = (float) -Math.sin(ns.angle);
            float len = (float) Math.hypot(dx, dy);
            if (len < 0.001f) return;
            dx /= len;
            dy /= len;

            if (touchActive) {
                float tx = touchStartX - touchCurrentX;
                float ty = touchStartY - touchCurrentY;
                float tl = (float) Math.hypot(tx, ty);
                if (tl > dp(4)) {
                    tx /= tl;
                    ty /= tl;
                    if (dx * tx + dy * ty < 0f) { dx = -dx; dy = -dy; }
                } else if (rayToRect(origin.x, origin.y, -dx, -dy, f.playable) >
                        rayToRect(origin.x, origin.y, dx, dy, f.playable)) {
                    dx = -dx; dy = -dy;
                }
            } else if (rayToRect(origin.x, origin.y, -dx, -dy, f.playable) >
                    rayToRect(origin.x, origin.y, dx, dy, f.playable)) {
                dx = -dx; dy = -dy;
            }

            Collision hit = firstCollision(origin.x, origin.y, dx, dy,
                    f.striker.radius, f.discs, null);
            float wallT = rayToRect(origin.x, origin.y, dx, dy,
                    inset(f.playable, f.striker.radius));

            if (hit != null && hit.t < wallT) {
                float cx = origin.x + dx * hit.t;
                float cy = origin.y + dy * hit.t;
                drawNeon(canvas, origin.x, origin.y, cx, cy);

                float nx = hit.disc.x - cx;
                float ny = hit.disc.y - cy;
                float nl = (float) Math.hypot(nx, ny);
                if (nl < 0.001f) return;
                nx /= nl;
                ny /= nl;

                PointF objectEnd = targetEndForDisc(f, hit.disc, nx, ny);
                canvas.drawLine(hit.disc.x, hit.disc.y, objectEnd.x, objectEnd.y, objectLine);

                float dot = dx * nx + dy * ny;
                float rx = dx - nx * dot;
                float ry = dy - ny * dot;
                float rl = (float) Math.hypot(rx, ry);
                if (rl > 0.08f) {
                    rx /= rl;
                    ry /= rl;
                    float residual = rayToRect(cx, cy, rx, ry,
                            inset(f.playable, f.striker.radius));
                    residual *= Math.max(0.18f, Math.min(0.72f, 1f - Math.abs(dot)));
                    canvas.drawLine(cx, cy, cx + rx * residual, cy + ry * residual, residualLine);
                }
                return;
            }

            if (!Float.isFinite(wallT) || wallT <= 0f) return;
            float wx = origin.x + dx * wallT;
            float wy = origin.y + dy * wallT;
            drawNeon(canvas, origin.x, origin.y, wx, wy);

            if (bankEnabled) {
                RectF play = inset(f.playable, f.striker.radius);
                boolean hitVertical = Math.abs(wx - play.left) < dp(2) || Math.abs(wx - play.right) < dp(2);
                boolean hitHorizontal = Math.abs(wy - play.top) < dp(2) || Math.abs(wy - play.bottom) < dp(2);
                float bx = hitVertical ? -dx : dx;
                float by = hitHorizontal ? -dy : dy;
                float bankT = rayToRect(wx, wy, bx, by, play);
                Collision bankHit = firstCollision(wx, wy, bx, by,
                        f.striker.radius, f.discs, null);
                if (bankHit != null && bankHit.t < bankT) bankT = bankHit.t;
                if (Float.isFinite(bankT) && bankT > 2f) {
                    canvas.drawLine(wx, wy, wx + bx * bankT, wy + by * bankT, objectLine);
                }
            }
        }

        private void drawCandidates(Canvas canvas, FrameState f) {
            ArrayList<ShotCandidate> candidates = new ArrayList<>();
            for (Disc target : f.discs) {
                for (PointF pocket : f.pockets) {
                    float vx = pocket.x - target.x;
                    float vy = pocket.y - target.y;
                    float vl = (float) Math.hypot(vx, vy);
                    if (vl < 1f) continue;
                    vx /= vl;
                    vy /= vl;
                    float contactX = target.x - vx * (target.radius + f.striker.radius);
                    float contactY = target.y - vy * (target.radius + f.striker.radius);
                    if (!f.playable.contains(contactX, contactY)) continue;
                    if (!segmentClear(f.striker.x, f.striker.y, contactX, contactY,
                            f.striker.radius * 0.92f, f.discs, target)) continue;
                    if (!segmentClear(target.x, target.y, pocket.x, pocket.y,
                            target.radius * 0.75f, f.discs, target)) continue;
                    float score = distance(f.striker.x, f.striker.y, contactX, contactY) + vl * 0.8f;
                    candidates.add(new ShotCandidate(target, pocket, contactX, contactY, score));
                }
            }
            candidates.sort(Comparator.comparingDouble(c -> c.score));
            int count = Math.min(4, candidates.size());
            for (int i = 0; i < count; i++) {
                ShotCandidate c = candidates.get(i);
                canvas.drawLine(f.striker.x, f.striker.y, c.contactX, c.contactY, candidateLine);
                canvas.drawLine(c.target.x, c.target.y, c.pocket.x, c.pocket.y, candidateLine);
            }
        }

        private PointF targetEndForDisc(FrameState f, Disc disc, float dx, float dy) {
            PointF bestPocket = null;
            float bestDot = 0.92f;
            for (PointF pocket : f.pockets) {
                float px = pocket.x - disc.x;
                float py = pocket.y - disc.y;
                float pl = (float) Math.hypot(px, py);
                if (pl < 1f) continue;
                float dot = dx * (px / pl) + dy * (py / pl);
                if (dot > bestDot && segmentClear(disc.x, disc.y, pocket.x, pocket.y,
                        disc.radius * 0.75f, f.discs, disc)) {
                    bestDot = dot;
                    bestPocket = pocket;
                }
            }
            if (bestPocket != null) return bestPocket;
            float t = rayToRect(disc.x, disc.y, dx, dy, inset(f.playable, disc.radius));
            if (!Float.isFinite(t) || t < 0f) t = 0f;
            Collision collision = firstCollision(disc.x, disc.y, dx, dy,
                    disc.radius, f.discs, disc);
            if (collision != null && collision.t < t) t = collision.t;
            return new PointF(disc.x + dx * t, disc.y + dy * t);
        }

        private void drawNeon(Canvas canvas, float x1, float y1, float x2, float y2) {
            canvas.drawLine(x1, y1, x2, y2, lineHalo);
            canvas.drawLine(x1, y1, x2, y2, line);
        }

        private void drawMenu(Canvas canvas, FrameState f, NativeState ns) {
            float w = getWidth();
            float bubbleSize = dp(52);
            float bx = w - dp(18) - bubbleSize;
            float by = dp(92);
            RectF bubble = new RectF(bx, by, bx + bubbleSize, by + bubbleSize);
            canvas.drawRoundRect(bubble, bubbleSize / 2f, bubbleSize / 2f, bubbleFill);
            text.setTextSize(dp(12));
            text.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("CTF", bubble.centerX(), bubble.centerY() + dp(4), text);

            if (!menuOpen) return;
            float panelW = dp(260);
            float row = dp(34);
            float panelX = w - dp(18) - panelW;
            float panelY = by + bubbleSize + dp(8);
            float panelH = row * 7f + dp(64);
            RectF panel = new RectF(panelX, panelY, panelX + panelW, panelY + panelH);
            canvas.drawRoundRect(panel, dp(14), dp(14), panelFill);

            text.setTextAlign(Paint.Align.LEFT);
            text.setTextSize(dp(13));
            canvas.drawText("Carrom CTF trajectory", panelX + dp(14), panelY + dp(22), text);
            float y = panelY + dp(48);
            drawToggle(canvas, panelX, y, "Lines", linesEnabled); y += row;
            drawToggle(canvas, panelX, y, "Bank preview", bankEnabled); y += row;
            drawToggle(canvas, panelX, y, "Pockets", pocketsEnabled); y += row;
            drawToggle(canvas, panelX, y, "Candidate shots", candidatesEnabled); y += row;
            drawToggle(canvas, panelX, y, "Debug discs", debugDiscs); y += row;

            muted.setTextAlign(Paint.Align.LEFT);
            muted.setTextSize(dp(10.5f));
            String hook = ns.statusText();
            String vision = f == null ? "VISION waiting" :
                    "VISION board=" + (f.boardDetected ? "OK" : "fallback")
                            + " discs=" + f.discs.size()
                            + " striker=" + (f.striker == null ? "MISS" : "OK");
            canvas.drawText(hook, panelX + dp(14), y + dp(5), muted);
            canvas.drawText(vision, panelX + dp(14), y + dp(22), muted);
            canvas.drawText("Auto Play: after line calibration", panelX + dp(14), y + dp(39), yellow);
        }

        private void drawToggle(Canvas canvas, float x, float y, String label, boolean enabled) {
            Paint p = enabled ? green : muted;
            p.setTextAlign(Paint.Align.LEFT);
            p.setTextSize(dp(11.5f));
            canvas.drawText((enabled ? "●  " : "○  ") + label, x + dp(14), y, p);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event == null) return false;
            float w = getWidth();
            float bubbleSize = dp(52);
            float bx = w - dp(18) - bubbleSize;
            float by = dp(92);
            if (event.getActionMasked() != MotionEvent.ACTION_UP) {
                return isMenuPoint(event.getX(), event.getY());
            }
            float x = event.getX();
            float y = event.getY();
            if (x >= bx && x <= bx + bubbleSize && y >= by && y <= by + bubbleSize) {
                menuOpen = !menuOpen;
                invalidate();
                return true;
            }
            if (!menuOpen) return false;
            float panelW = dp(260);
            float panelX = w - dp(18) - panelW;
            float panelY = by + bubbleSize + dp(8);
            float row = dp(34);
            float first = panelY + dp(28);
            if (x < panelX || x > panelX + panelW || y < first || y > first + row * 5f) return false;
            int index = Math.max(0, Math.min(4, (int) ((y - first) / row)));
            if (index == 0) linesEnabled = !linesEnabled;
            else if (index == 1) bankEnabled = !bankEnabled;
            else if (index == 2) pocketsEnabled = !pocketsEnabled;
            else if (index == 3) candidatesEnabled = !candidatesEnabled;
            else debugDiscs = !debugDiscs;
            invalidate();
            return true;
        }

        private boolean isMenuPoint(float x, float y) {
            float w = getWidth();
            float bubbleSize = dp(52);
            float bx = w - dp(18) - bubbleSize;
            float by = dp(92);
            if (x >= bx && x <= bx + bubbleSize && y >= by && y <= by + bubbleSize) return true;
            if (!menuOpen) return false;
            float panelW = dp(260);
            float panelX = w - dp(18) - panelW;
            float panelY = by + bubbleSize + dp(8);
            return x >= panelX && x <= panelX + panelW && y >= panelY && y <= panelY + dp(302);
        }

        private float dp(float value) {
            return value * getResources().getDisplayMetrics().density;
        }

        private Paint stroke(int color, float widthDp) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(color);
            p.setStrokeWidth(dp(widthDp));
            return p;
        }

        private Paint fill(int color) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            return p;
        }
    }

    private static NativeState readNative() {
        try {
            int status = nativeStart();
            double[] v = nativeSnapshot();
            if (v == null || v.length < 5) return new NativeState(status, Double.NaN, Double.NaN, -1, -1);
            return new NativeState((int) v[0], v[1], v[2], v[3], v[4]);
        } catch (Throwable error) {
            return new NativeState(-10, Double.NaN, Double.NaN, -1, -1);
        }
    }

    private static native int nativeStart();
    private static native double[] nativeSnapshot();

    private static final class NativeState {
        final int status;
        final double angle;
        final double power;
        final double angleAgeMs;
        final double powerAgeMs;

        NativeState(int status, double angle, double power, double angleAgeMs, double powerAgeMs) {
            this.status = status;
            this.angle = angle;
            this.power = power;
            this.angleAgeMs = angleAgeMs;
            this.powerAgeMs = powerAgeMs;
        }

        boolean usable() {
            return status == 2 && Double.isFinite(angle) && Double.isFinite(power)
                    && angleAgeMs >= 0 && powerAgeMs >= 0;
        }

        String statusText() {
            if (status == 2) return "NATIVE HOOKED  power=" + String.format(java.util.Locale.US, "%.2f", power);
            if (status == 1) return "NATIVE waiting for libgame-CARROM";
            if (status == -2) return "NATIVE build mismatch (offsets)";
            if (status == -3) return "NATIVE hook failed";
            return "NATIVE status=" + status;
        }
    }

    private static final class Vision {
        static FrameState analyze(Bitmap bitmap, int offsetX, int offsetY) {
            if (bitmap == null || bitmap.isRecycled()) return null;
            RectF board = findBoard(bitmap);
            boolean detected = board != null;
            if (board == null) {
                float side = Math.min(bitmap.getWidth(), bitmap.getHeight()) * 0.90f;
                board = new RectF((bitmap.getWidth() - side) * 0.5f,
                        (bitmap.getHeight() - side) * 0.5f,
                        (bitmap.getWidth() + side) * 0.5f,
                        (bitmap.getHeight() + side) * 0.5f);
            }
            float side = Math.min(board.width(), board.height());
            float inset = side * 0.045f;
            RectF playable = new RectF(board.left + inset, board.top + inset,
                    board.right - inset, board.bottom - inset);
            float pocketRadius = side * 0.047f;
            ArrayList<PointF> pockets = new ArrayList<>();
            pockets.add(new PointF(playable.left, playable.top));
            pockets.add(new PointF(playable.right, playable.top));
            pockets.add(new PointF(playable.left, playable.bottom));
            pockets.add(new PointF(playable.right, playable.bottom));

            ArrayList<Disc> discs = detectDiscs(bitmap, playable, pocketRadius);
            Disc striker = chooseStriker(discs, playable);
            if (striker != null) discs.remove(striker);

            translate(playable, offsetX, offsetY);
            translate(board, offsetX, offsetY);
            for (PointF p : pockets) { p.x += offsetX; p.y += offsetY; }
            for (Disc d : discs) { d.x += offsetX; d.y += offsetY; }
            if (striker != null) { striker.x += offsetX; striker.y += offsetY; }

            return new FrameState(board, playable, discs, striker, pockets,
                    pocketRadius, detected);
        }

        private static RectF findBoard(Bitmap bitmap) {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int step = Math.max(3, Math.min(w, h) / 220);
            boolean[] rows = new boolean[h];
            int samples = Math.max(1, (w + step - 1) / step);
            for (int y = 0; y < h; y += step) {
                int warm = 0;
                for (int x = 0; x < w; x += step) if (isWood(bitmap.getPixel(x, y))) warm++;
                boolean ok = warm / (float) samples >= 0.34f;
                for (int yy = y; yy < Math.min(h, y + step); yy++) rows[yy] = ok;
            }
            int[] rowSpan = largestSpan(rows, Math.max(60, h / 4));
            if (rowSpan == null) return null;

            boolean[] cols = new boolean[w];
            int sampleRows = Math.max(1, (rowSpan[1] - rowSpan[0] + step - 1) / step);
            for (int x = 0; x < w; x += step) {
                int warm = 0;
                for (int y = rowSpan[0]; y < rowSpan[1]; y += step) {
                    if (isWood(bitmap.getPixel(x, y))) warm++;
                }
                boolean ok = warm / (float) sampleRows >= 0.30f;
                for (int xx = x; xx < Math.min(w, x + step); xx++) cols[xx] = ok;
            }
            int[] colSpan = largestSpan(cols, Math.max(60, w / 3));
            if (colSpan == null) return null;
            RectF result = new RectF(colSpan[0], rowSpan[0], colSpan[1], rowSpan[1]);
            float ratio = result.width() / Math.max(1f, result.height());
            if (ratio < 0.72f || ratio > 1.30f) return null;
            return result;
        }

        private static ArrayList<Disc> detectDiscs(Bitmap bitmap, RectF board, float pocketRadius) {
            float side = Math.min(board.width(), board.height());
            int step = Math.max(3, Math.round(side / 165f));
            float[] radii = {side * 0.0225f, side * 0.027f, side * 0.0315f};
            ArrayList<Disc> raw = new ArrayList<>();
            int left = Math.max(2, Math.round(board.left + side * 0.035f));
            int right = Math.min(bitmap.getWidth() - 3, Math.round(board.right - side * 0.035f));
            int top = Math.max(2, Math.round(board.top + side * 0.035f));
            int bottom = Math.min(bitmap.getHeight() - 3, Math.round(board.bottom - side * 0.035f));

            for (int y = top; y <= bottom; y += step) {
                for (int x = left; x <= right; x += step) {
                    float bestScore = 0f;
                    float bestRadius = radii[1];
                    float bestLum = 0f;
                    for (float radius : radii) {
                        CircleScore score = circleScore(bitmap, x, y, radius);
                        if (score.score > bestScore) {
                            bestScore = score.score;
                            bestRadius = radius;
                            bestLum = score.centerLum;
                        }
                    }
                    if (bestScore >= 58f) raw.add(new Disc(x, y, bestRadius, bestScore, bestLum));
                }
            }

            raw.sort((a, b) -> Float.compare(b.score, a.score));
            ArrayList<Disc> kept = new ArrayList<>();
            for (Disc candidate : raw) {
                boolean nearCorner = false;
                float cornerGuard = pocketRadius * 1.65f;
                float[][] corners = {{board.left, board.top}, {board.right, board.top},
                        {board.left, board.bottom}, {board.right, board.bottom}};
                for (float[] c : corners) {
                    if (distance(candidate.x, candidate.y, c[0], c[1]) < cornerGuard) {
                        nearCorner = true; break;
                    }
                }
                if (nearCorner) continue;
                boolean overlap = false;
                for (Disc existing : kept) {
                    if (distance(candidate.x, candidate.y, existing.x, existing.y)
                            < Math.max(candidate.radius, existing.radius) * 1.45f) {
                        overlap = true; break;
                    }
                }
                if (!overlap) kept.add(candidate);
                if (kept.size() >= 24) break;
            }
            return kept;
        }

        private static CircleScore circleScore(Bitmap bitmap, int cx, int cy, float radius) {
            int samples = 16;
            float edge = 0f;
            float fill = 0f;
            int edgeGood = 0;
            float centerLum = luminance(bitmap.getPixel(cx, cy));
            for (int i = 0; i < samples; i++) {
                double a = i * (Math.PI * 2.0 / samples);
                float cos = (float) Math.cos(a);
                float sin = (float) Math.sin(a);
                int inner = safePixel(bitmap, cx + cos * radius * 0.55f, cy + sin * radius * 0.55f);
                int edgeIn = safePixel(bitmap, cx + cos * radius * 0.82f, cy + sin * radius * 0.82f);
                int outer = safePixel(bitmap, cx + cos * radius * 1.24f, cy + sin * radius * 1.24f);
                float e = colorDistance(edgeIn, outer);
                float f = colorDistance(inner, outer);
                edge += e;
                fill += f;
                if (e > 28f) edgeGood++;
            }
            edge /= samples;
            fill /= samples;
            float coverage = edgeGood / (float) samples;
            if (coverage < 0.44f || fill < 28f) return new CircleScore(0f, centerLum);
            return new CircleScore(fill * 0.62f + edge * 0.30f + coverage * 28f, centerLum);
        }

        private static Disc chooseStriker(List<Disc> discs, RectF board) {
            if (discs.isEmpty()) return null;
            Disc best = null;
            float bestScore = -Float.MAX_VALUE;
            float side = Math.min(board.width(), board.height());
            for (Disc disc : discs) {
                float yNorm = (disc.y - board.top) / Math.max(1f, board.height());
                if (yNorm < 0.58f) continue;
                float score = yNorm * 80f + disc.centerLum * 0.16f
                        + (disc.radius / side) * 500f + disc.score * 0.08f;
                if (score > bestScore) { bestScore = score; best = disc; }
            }
            return best;
        }

        private static int safePixel(Bitmap bitmap, float x, float y) {
            int ix = Math.max(0, Math.min(bitmap.getWidth() - 1, Math.round(x)));
            int iy = Math.max(0, Math.min(bitmap.getHeight() - 1, Math.round(y)));
            return bitmap.getPixel(ix, iy);
        }

        private static float colorDistance(int a, int b) {
            int dr = Color.red(a) - Color.red(b);
            int dg = Color.green(a) - Color.green(b);
            int db = Color.blue(a) - Color.blue(b);
            return (float) Math.sqrt(dr * dr + dg * dg + db * db);
        }

        private static float luminance(int c) {
            return Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f;
        }

        private static boolean isWood(int c) {
            int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
            return r > 95 && g > 48 && b > 24 && r > g + 10 && g > b - 8 && r > b + 24;
        }

        private static int[] largestSpan(boolean[] values, int minimum) {
            int bestStart = -1, bestEnd = -1, start = -1;
            for (int i = 0; i <= values.length; i++) {
                boolean value = i < values.length && values[i];
                if (value && start < 0) start = i;
                else if (!value && start >= 0) {
                    if (i - start >= minimum && i - start > bestEnd - bestStart) {
                        bestStart = start; bestEnd = i;
                    }
                    start = -1;
                }
            }
            return bestStart < 0 ? null : new int[]{bestStart, bestEnd};
        }

        private static void translate(RectF rect, float x, float y) {
            rect.offset(x, y);
        }
    }

    private static SurfaceView findLargestSurface(View root) {
        if (root == null) return null;
        SurfaceView best = root instanceof SurfaceView ? (SurfaceView) root : null;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                SurfaceView child = findLargestSurface(group.getChildAt(i));
                if (child != null && (best == null || child.getWidth() * child.getHeight() > best.getWidth() * best.getHeight())) {
                    best = child;
                }
            }
        }
        return best;
    }

    private static Collision firstCollision(float ox, float oy, float dx, float dy,
                                            float movingRadius, List<Disc> discs, Disc ignored) {
        Collision best = null;
        for (Disc disc : discs) {
            if (disc == ignored) continue;
            float t = rayCircle(ox, oy, dx, dy, disc.x, disc.y, movingRadius + disc.radius);
            if (t > 1f && (best == null || t < best.t)) best = new Collision(disc, t);
        }
        return best;
    }

    private static float rayCircle(float ox, float oy, float dx, float dy,
                                   float cx, float cy, float radius) {
        float fx = ox - cx;
        float fy = oy - cy;
        float b = 2f * (fx * dx + fy * dy);
        float c = fx * fx + fy * fy - radius * radius;
        float d = b * b - 4f * c;
        if (d < 0f) return Float.POSITIVE_INFINITY;
        float root = (float) Math.sqrt(d);
        float t1 = (-b - root) * 0.5f;
        float t2 = (-b + root) * 0.5f;
        if (t1 > 1f) return t1;
        return t2 > 1f ? t2 : Float.POSITIVE_INFINITY;
    }

    private static float rayToRect(float ox, float oy, float dx, float dy, RectF rect) {
        float best = Float.POSITIVE_INFINITY;
        if (dx > 0.0001f) best = Math.min(best, (rect.right - ox) / dx);
        else if (dx < -0.0001f) best = Math.min(best, (rect.left - ox) / dx);
        if (dy > 0.0001f) best = Math.min(best, (rect.bottom - oy) / dy);
        else if (dy < -0.0001f) best = Math.min(best, (rect.top - oy) / dy);
        return best > 0f ? best : Float.POSITIVE_INFINITY;
    }

    private static boolean segmentClear(float x1, float y1, float x2, float y2,
                                        float radius, List<Disc> discs, Disc ignored) {
        float vx = x2 - x1;
        float vy = y2 - y1;
        float len2 = vx * vx + vy * vy;
        if (len2 < 1f) return false;
        for (Disc disc : discs) {
            if (disc == ignored) continue;
            float t = ((disc.x - x1) * vx + (disc.y - y1) * vy) / len2;
            t = Math.max(0f, Math.min(1f, t));
            float px = x1 + vx * t;
            float py = y1 + vy * t;
            if (distance(px, py, disc.x, disc.y) < radius + disc.radius) return false;
        }
        return true;
    }

    private static RectF inset(RectF src, float amount) {
        return new RectF(src.left + amount, src.top + amount, src.right - amount, src.bottom - amount);
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x2 - x1, y2 - y1);
    }

    private static final class FrameState {
        final RectF board;
        final RectF playable;
        final ArrayList<Disc> discs;
        final Disc striker;
        final ArrayList<PointF> pockets;
        final float pocketRadius;
        final boolean boardDetected;

        FrameState(RectF board, RectF playable, ArrayList<Disc> discs, Disc striker,
                   ArrayList<PointF> pockets, float pocketRadius, boolean boardDetected) {
            this.board = board;
            this.playable = playable;
            this.discs = discs;
            this.striker = striker;
            this.pockets = pockets;
            this.pocketRadius = pocketRadius;
            this.boardDetected = boardDetected;
        }
    }

    private static final class Disc {
        float x;
        float y;
        final float radius;
        final float score;
        final float centerLum;

        Disc(float x, float y, float radius, float score, float centerLum) {
            this.x = x; this.y = y; this.radius = radius; this.score = score; this.centerLum = centerLum;
        }
    }

    private static final class CircleScore {
        final float score;
        final float centerLum;
        CircleScore(float score, float centerLum) { this.score = score; this.centerLum = centerLum; }
    }

    private static final class Collision {
        final Disc disc;
        final float t;
        Collision(Disc disc, float t) { this.disc = disc; this.t = t; }
    }

    private static final class ShotCandidate {
        final Disc target;
        final PointF pocket;
        final float contactX;
        final float contactY;
        final float score;

        ShotCandidate(Disc target, PointF pocket, float contactX, float contactY, float score) {
            this.target = target; this.pocket = pocket; this.contactX = contactX;
            this.contactY = contactY; this.score = score;
        }
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
