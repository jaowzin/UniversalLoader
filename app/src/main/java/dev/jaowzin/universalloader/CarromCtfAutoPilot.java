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
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

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
 * User-triggered CTF auto-shot helper for the Carrom sample plugin.
 *
 * The helper stays inside the UniversalLoader-managed virtual process. It detects the board and
 * pieces from the rendered frame, chooses a clear piece-to-pocket candidate, synthesizes the same
 * drag gesture a player would make, and uses the read-only native aim snapshot to correct the
 * second MOVE before releasing the shot.
 */
public final class CarromCtfAutoPilot {
    private static final String TAG = "ULCarromAuto";
    private static final String OVERLAY_TAG = "ul_carrom_ctf_auto";
    private static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();
    private static final Map<Application, State> INSTALLED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private CarromCtfAutoPilot() {}

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

        AutoOverlay overlay = new AutoOverlay(activity, state);
        overlay.setTag(OVERLAY_TAG);
        activity.addContentView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static void detach(Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) return;
        View existing = ((ViewGroup) decor).findViewWithTag(OVERLAY_TAG);
        if (existing instanceof AutoOverlay) ((AutoOverlay) existing).stop();
        if (existing != null && existing.getParent() instanceof ViewGroup) {
            ((ViewGroup) existing.getParent()).removeView(existing);
        }
    }

    private static final class AutoOverlay extends View {
        private final Activity activity;
        private final State state;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final AtomicBoolean busy = new AtomicBoolean(false);
        private final Paint button = fill(Color.argb(245, 26, 30, 38));
        private final Paint buttonReady = fill(Color.rgb(48, 196, 104));
        private final Paint text = fill(Color.WHITE);
        private final Paint subText = fill(Color.rgb(190, 198, 212));
        private final Paint aimPaint = stroke(Color.argb(235, 255, 180, 46), 2.4f);
        private final Paint targetPaint = stroke(Color.argb(235, 78, 240, 128), 2.2f);

        private volatile boolean stopped;
        private volatile String status = "AUTO ready";
        private volatile Candidate preview;
        private Bitmap bitmap;

        AutoOverlay(Activity activity, State state) {
            super(activity);
            this.activity = activity;
            this.state = state;
            setWillNotDraw(false);
            setClickable(false);
            text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }

        void stop() {
            stopped = true;
            main.removeCallbacksAndMessages(null);
            worker.shutdownNow();
            busy.set(false);
            preview = null;

            // PixelCopy and Vision may still hold a reference to the capture target while an
            // Activity is being paused/destroyed. Clearing our reference is enough; recycling the
            // Bitmap here can invalidate native pixels that an in-flight callback is still using.
            bitmap = null;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float left = dp(16);
            float top = dp(92);
            float width = dp(92);
            float height = dp(46);
            RectF rect = new RectF(left, top, left + width, top + height);
            canvas.drawRoundRect(rect, dp(23), dp(23), busy.get() ? button : buttonReady);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(dp(12));
            canvas.drawText(busy.get() ? "AUTO..." : "AUTO", rect.centerX(), rect.centerY() + dp(4), text);

            subText.setTextAlign(Paint.Align.LEFT);
            subText.setTextSize(dp(9.5f));
            canvas.drawText(status, left, rect.bottom + dp(14), subText);

            Candidate p = preview;
            if (p != null) {
                canvas.drawLine(p.striker.x, p.striker.y, p.contact.x, p.contact.y, aimPaint);
                canvas.drawLine(p.target.x, p.target.y, p.pocket.x, p.pocket.y, targetPaint);
                canvas.drawCircle(p.contact.x, p.contact.y, dp(5), targetPaint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event == null) return false;
            float left = dp(16);
            float top = dp(92);
            float width = dp(92);
            float height = dp(46);
            boolean hit = event.getX() >= left && event.getX() <= left + width
                    && event.getY() >= top && event.getY() <= top + height;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) return hit;
            if (event.getActionMasked() == MotionEvent.ACTION_UP && hit) {
                runAutoShot();
                return true;
            }
            return hit;
        }

        private void runAutoShot() {
            if (stopped || !busy.compareAndSet(false, true)) return;
            status = "capturing board";
            preview = null;
            invalidate();

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
                finishBusy("AUTO no render surface");
                return;
            }

            if (bitmap == null || bitmap.isRecycled()
                    || bitmap.getWidth() != width || bitmap.getHeight() != height) {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            }

            final SurfaceView source = surface;
            final int fx = offsetX;
            final int fy = offsetY;
            final Bitmap captureTarget = bitmap;
            PixelCopy.OnPixelCopyFinishedListener listener = result -> {
                if (stopped) {
                    busy.set(false);
                    return;
                }
                if (result != PixelCopy.SUCCESS) {
                    finishBusy("AUTO capture failed " + result);
                    return;
                }

                try {
                    worker.execute(() -> {
                        if (stopped) return;

                        final Frame frame;
                        final Candidate candidate;
                        try {
                            frame = Vision.analyze(captureTarget, fx, fy);
                            candidate = chooseCandidate(frame);
                        } catch (Throwable error) {
                            Log.w(TAG, "frame analysis failed", error);
                            main.post(() -> {
                                if (stopped) busy.set(false);
                                else finishBusy("AUTO analyze failed");
                            });
                            return;
                        }

                        main.post(() -> {
                            if (stopped) {
                                busy.set(false);
                                return;
                            }
                            preview = candidate;
                            if (candidate == null) {
                                finishBusy("AUTO no clear shot");
                                return;
                            }
                            status = "aiming " + candidate.label;
                            invalidate();
                            fire(candidate, frame);
                        });
                    });
                } catch (RuntimeException error) {
                    // shutdownNow() can race a just-finished PixelCopy callback during pause.
                    if (!stopped) {
                        Log.w(TAG, "analysis worker rejected frame", error);
                        finishBusy("AUTO worker unavailable");
                    } else {
                        busy.set(false);
                    }
                }
            };

            try {
                if (source != null) PixelCopy.request(source, captureTarget, listener, main);
                else PixelCopy.request(activity.getWindow(), captureTarget, listener, main);
            } catch (Throwable error) {
                Log.w(TAG, "PixelCopy request failed", error);
                finishBusy("AUTO capture exception");
            }
        }

        private void fire(Candidate candidate, Frame frame) {
            if (candidate == null || frame == null || stopped) {
                finishBusy("AUTO invalid candidate");
                return;
            }

            NativeState before = readNative();
            try { nativeStart(); } catch (Throwable ignored) {}

            float dx = candidate.contact.x - candidate.striker.x;
            float dy = candidate.contact.y - candidate.striker.y;
            float length = (float) Math.hypot(dx, dy);
            if (length < 1f) {
                finishBusy("AUTO invalid direction");
                return;
            }
            dx /= length;
            dy /= length;

            float totalPath = length + distance(candidate.target.x, candidate.target.y,
                    candidate.pocket.x, candidate.pocket.y);
            float side = Math.max(1f, Math.min(frame.playable.width(), frame.playable.height()));
            float desiredPower = clamp(0.40f + totalPath / side * 0.34f, 0.48f, 0.88f);

            float startX = clamp(getWidth() * 0.52f, dp(120), getWidth() - dp(120));
            float startY = clamp(getHeight() * 0.79f, dp(180), getHeight() - dp(90));
            float baseDistance = dp(78) + dp(190) * desiredPower;

            long downTime = SystemClock.uptimeMillis();
            dispatch(MotionEvent.ACTION_DOWN, startX, startY, downTime);
            float firstX = clamp(startX - dx * baseDistance, dp(20), getWidth() - dp(20));
            float firstY = clamp(startY - dy * baseDistance, dp(20), getHeight() - dp(20));
            dispatch(MotionEvent.ACTION_MOVE, firstX, firstY, downTime);

            final float desiredDx = dx;
            final float desiredDy = dy;
            final float initialDistance = baseDistance;
            final float sx = startX;
            final float sy = startY;
            main.postDelayed(() -> {
                NativeState observed = readNative();
                float commandAngle = (float) Math.atan2(-desiredDy, desiredDx);
                float correctedAngle = commandAngle;
                float distance = initialDistance;

                if (observed.usable()) {
                    float observedAngle = (float) observed.angle;
                    float angleOffset = normalizeAngle(observedAngle - commandAngle);
                    correctedAngle = commandAngle - angleOffset;
                    if (observed.power > 0.03) {
                        float scale = clamp(desiredPower / (float) observed.power, 0.68f, 1.38f);
                        distance = clamp(initialDistance * scale, dp(80), dp(310));
                    }
                } else if (before.usable()) {
                    status = "AUTO native sample stale; direct drag";
                }

                float cdx = (float) Math.cos(correctedAngle);
                float cdy = (float) -Math.sin(correctedAngle);
                float moveX = clamp(sx - cdx * distance, dp(20), getWidth() - dp(20));
                float moveY = clamp(sy - cdy * distance, dp(20), getHeight() - dp(20));
                dispatch(MotionEvent.ACTION_MOVE, moveX, moveY, downTime);

                main.postDelayed(() -> {
                    NativeState finalState = readNative();
                    if (finalState.usable()) {
                        float desiredAngle = (float) Math.atan2(-desiredDy, desiredDx);
                        float error = Math.abs(normalizeAngle((float) finalState.angle - desiredAngle));
                        status = "AUTO fire err=" + String.format(java.util.Locale.US, "%.2f", error)
                                + " p=" + String.format(java.util.Locale.US, "%.2f", finalState.power);
                    } else {
                        status = "AUTO fire direct";
                    }
                    dispatch(MotionEvent.ACTION_UP, moveX, moveY, downTime);
                    busy.set(false);
                    invalidate();
                    main.postDelayed(() -> {
                        preview = null;
                        status = "AUTO ready";
                        invalidate();
                    }, 1300L);
                }, 95L);
            }, 95L);
        }

        private void dispatch(int action, float x, float y, long downTime) {
            long now = SystemClock.uptimeMillis();
            MotionEvent event = MotionEvent.obtain(downTime, now, action, x, y, 0);
            try {
                activity.dispatchTouchEvent(event);
            } catch (Throwable error) {
                Log.w(TAG, "synthetic game touch failed", error);
            } finally {
                event.recycle();
            }
        }

        private void finishBusy(String value) {
            busy.set(false);
            if (stopped) return;
            status = value;
            invalidate();
        }

        private float dp(float value) {
            return value * getResources().getDisplayMetrics().density;
        }
    }

    private static Candidate chooseCandidate(Frame frame) {
        if (frame == null || frame.striker == null || frame.discs.isEmpty()) return null;
        ArrayList<Candidate> candidates = new ArrayList<>();
        // CALIBRATED_POCKETS_V2: rail decorations are circular too, so never use a detected
        // disc as a target when its center is still sitting in the rail guard band.
        float edgeGuard = Math.min(frame.playable.width(), frame.playable.height()) * 0.070f;
        for (Disc target : frame.discs) {
            if (target.x < frame.playable.left + edgeGuard
                    || target.x > frame.playable.right - edgeGuard
                    || target.y < frame.playable.top + edgeGuard
                    || target.y > frame.playable.bottom - edgeGuard) {
                continue;
            }
            for (int pocketIndex = 0; pocketIndex < frame.pockets.size(); pocketIndex++) {
                PointF pocket = frame.pockets.get(pocketIndex);
                float vx = pocket.x - target.x;
                float vy = pocket.y - target.y;
                float vl = (float) Math.hypot(vx, vy);
                if (vl < 1f) continue;
                vx /= vl;
                vy /= vl;

                float contactX = target.x - vx * (target.radius + frame.striker.radius);
                float contactY = target.y - vy * (target.radius + frame.striker.radius);
                if (!frame.playable.contains(contactX, contactY)) continue;
                if (!segmentClear(frame.striker.x, frame.striker.y, contactX, contactY,
                        frame.striker.radius * 0.90f, frame.discs, target)) continue;
                if (!segmentClear(target.x, target.y, pocket.x, pocket.y,
                        target.radius * 0.70f, frame.discs, target)) continue;

                float strikerPath = distance(frame.striker.x, frame.striker.y, contactX, contactY);
                float targetPath = distance(target.x, target.y, pocket.x, pocket.y);
                float cutX = contactX - frame.striker.x;
                float cutY = contactY - frame.striker.y;
                float cutLen = Math.max(1f, (float) Math.hypot(cutX, cutY));
                cutX /= cutLen;
                cutY /= cutLen;
                float cutDot = cutX * vx + cutY * vy;
                float cutPenalty = (1f - clamp(cutDot, -1f, 1f)) * frame.playable.width() * 0.25f;
                float score = strikerPath + targetPath * 0.72f + cutPenalty;
                candidates.add(new Candidate(frame.striker, target, pocket,
                        new PointF(contactX, contactY), score, "P" + (pocketIndex + 1)));
            }
        }
        candidates.sort(Comparator.comparingDouble(c -> c.score));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static final class Vision {
        static Frame analyze(Bitmap bitmap, int offsetX, int offsetY) {
            if (bitmap == null || bitmap.isRecycled()) return null;
            RectF board = findBoard(bitmap);
            if (board == null) return null;
            float side = Math.min(board.width(), board.height());

            // The old model treated the corners of an inset rectangle as pockets. On the CTF
            // board those coordinates land on the orange rail markers. Detect the actual dark
            // pocket cores near each outer corner instead, with a conservative geometric fallback.
            float pocketRadius = side * 0.045f;
            ArrayList<PointF> pockets = detectPockets(bitmap, board);

            // Keep piece detection inside the rail line. The candidate pass below adds another
            // guard band so orange rail decorations cannot become shot targets.
            float playInset = side * 0.130f;
            RectF playable = new RectF(board.left + playInset, board.top + playInset,
                    board.right - playInset, board.bottom - playInset);
            if (playable.width() < side * 0.55f || playable.height() < side * 0.55f) {
                playInset = side * 0.105f;
                playable.set(board.left + playInset, board.top + playInset,
                        board.right - playInset, board.bottom - playInset);
            }

            ArrayList<Disc> discs = detectDiscs(bitmap, playable, pocketRadius);
            Disc striker = chooseStriker(discs, playable);
            if (striker != null) discs.remove(striker);

            board.offset(offsetX, offsetY);
            playable.offset(offsetX, offsetY);
            for (PointF pocket : pockets) { pocket.x += offsetX; pocket.y += offsetY; }
            for (Disc disc : discs) { disc.x += offsetX; disc.y += offsetY; }
            if (striker != null) { striker.x += offsetX; striker.y += offsetY; }
            return new Frame(board, playable, discs, striker, pockets, pocketRadius);
        }

        private static ArrayList<PointF> detectPockets(Bitmap bitmap, RectF board) {
            float side = Math.min(board.width(), board.height());
            float inset = side * 0.055f;
            float searchRadius = side * 0.050f;
            ArrayList<PointF> result = new ArrayList<>(4);
            result.add(findPocketNear(bitmap,
                    new PointF(board.left + inset, board.top + inset), searchRadius, side));
            result.add(findPocketNear(bitmap,
                    new PointF(board.right - inset, board.top + inset), searchRadius, side));
            result.add(findPocketNear(bitmap,
                    new PointF(board.left + inset, board.bottom - inset), searchRadius, side));
            result.add(findPocketNear(bitmap,
                    new PointF(board.right - inset, board.bottom - inset), searchRadius, side));
            return result;
        }

        private static PointF findPocketNear(Bitmap bitmap, PointF expected,
                                             float searchRadius, float side) {
            int step = Math.max(2, Math.round(side / 220f));
            int minX = Math.max(1, Math.round(expected.x - searchRadius));
            int maxX = Math.min(bitmap.getWidth() - 2, Math.round(expected.x + searchRadius));
            int minY = Math.max(1, Math.round(expected.y - searchRadius));
            int maxY = Math.min(bitmap.getHeight() - 2, Math.round(expected.y + searchRadius));
            float coreRadius = Math.max(2f, side * 0.010f);
            float ringRadius = Math.max(5f, side * 0.036f);

            PointF best = new PointF(expected.x, expected.y);
            float bestScore = -Float.MAX_VALUE;
            for (int y = minY; y <= maxY; y += step) {
                for (int x = minX; x <= maxX; x += step) {
                    float core = coreLuminance(bitmap, x, y, coreRadius);
                    float ring = ringLuminance(bitmap, x, y, ringRadius);
                    float contrast = ring - core;
                    float distancePenalty = distance(x, y, expected.x, expected.y) * 0.65f;
                    float darkBonus = (110f - Math.min(110f, core)) * 1.35f;
                    float score = darkBonus + contrast * 2.10f - distancePenalty;
                    if (core > 105f) score -= (core - 105f) * 2.4f;
                    if (contrast < 8f) score -= (8f - contrast) * 5f;
                    if (score > bestScore) {
                        bestScore = score;
                        best.set(x, y);
                    }
                }
            }

            // A bad capture should still aim at the known corner geometry rather than at an
            // unrelated rail marker. A genuine pocket normally scores far above this threshold.
            return bestScore >= 35f ? best : new PointF(expected.x, expected.y);
        }

        private static float coreLuminance(Bitmap bitmap, float cx, float cy, float radius) {
            float d = radius * 0.72f;
            float sum = 0f;
            sum += luminance(safePixel(bitmap, cx, cy));
            sum += luminance(safePixel(bitmap, cx + radius, cy));
            sum += luminance(safePixel(bitmap, cx - radius, cy));
            sum += luminance(safePixel(bitmap, cx, cy + radius));
            sum += luminance(safePixel(bitmap, cx, cy - radius));
            sum += luminance(safePixel(bitmap, cx + d, cy + d));
            sum += luminance(safePixel(bitmap, cx + d, cy - d));
            sum += luminance(safePixel(bitmap, cx - d, cy + d));
            sum += luminance(safePixel(bitmap, cx - d, cy - d));
            return sum / 9f;
        }

        private static float ringLuminance(Bitmap bitmap, float cx, float cy, float radius) {
            int samples = 16;
            float sum = 0f;
            for (int i = 0; i < samples; i++) {
                double angle = i * (Math.PI * 2.0 / samples);
                sum += luminance(safePixel(bitmap,
                        cx + (float) Math.cos(angle) * radius,
                        cy + (float) Math.sin(angle) * radius));
            }
            return sum / samples;
        }

        private static RectF findBoard(Bitmap bitmap) {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int step = Math.max(3, Math.min(w, h) / 220);
            boolean[] rows = new boolean[h];
            int rowSamples = Math.max(1, (w + step - 1) / step);
            for (int y = 0; y < h; y += step) {
                int warm = 0;
                for (int x = 0; x < w; x += step) if (isWood(bitmap.getPixel(x, y))) warm++;
                boolean ok = warm / (float) rowSamples >= 0.34f;
                for (int yy = y; yy < Math.min(h, y + step); yy++) rows[yy] = ok;
            }
            int[] rowSpan = largestSpan(rows, Math.max(60, h / 4));
            if (rowSpan == null) return null;

            boolean[] cols = new boolean[w];
            int colSamples = Math.max(1, (rowSpan[1] - rowSpan[0] + step - 1) / step);
            for (int x = 0; x < w; x += step) {
                int warm = 0;
                for (int y = rowSpan[0]; y < rowSpan[1]; y += step) {
                    if (isWood(bitmap.getPixel(x, y))) warm++;
                }
                boolean ok = warm / (float) colSamples >= 0.30f;
                for (int xx = x; xx < Math.min(w, x + step); xx++) cols[xx] = ok;
            }
            int[] colSpan = largestSpan(cols, Math.max(60, w / 3));
            if (colSpan == null) return null;
            RectF result = new RectF(colSpan[0], rowSpan[0], colSpan[1], rowSpan[1]);
            float ratio = result.width() / Math.max(1f, result.height());
            return ratio >= 0.72f && ratio <= 1.30f ? result : null;
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
                for (float[] corner : corners) {
                    if (distance(candidate.x, candidate.y, corner[0], corner[1]) < cornerGuard) {
                        nearCorner = true;
                        break;
                    }
                }
                if (nearCorner) continue;
                boolean overlap = false;
                for (Disc existing : kept) {
                    if (distance(candidate.x, candidate.y, existing.x, existing.y)
                            < Math.max(candidate.radius, existing.radius) * 1.45f) {
                        overlap = true;
                        break;
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
                double angle = i * (Math.PI * 2.0 / samples);
                float cos = (float) Math.cos(angle);
                float sin = (float) Math.sin(angle);
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
            Disc best = null;
            float bestScore = -Float.MAX_VALUE;
            float side = Math.min(board.width(), board.height());
            for (Disc disc : discs) {
                float yNorm = (disc.y - board.top) / Math.max(1f, board.height());
                float xNorm = (disc.x - board.left) / Math.max(1f, board.width());
                if (yNorm < 0.58f || xNorm < 0.10f || xNorm > 0.90f) continue;

                // The striker starts in the lower shooting lane and, unlike the orange side
                // markers, is usually much closer to the horizontal centre of the board.
                float centerBonus = (1f - Math.min(1f, Math.abs(xNorm - 0.5f) * 2f)) * 58f;
                float score = yNorm * 72f + centerBonus + disc.centerLum * 0.18f
                        + (disc.radius / side) * 430f + disc.score * 0.06f;
                if (score > bestScore) {
                    bestScore = score;
                    best = disc;
                }
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

        private static float luminance(int color) {
            return Color.red(color) * 0.299f + Color.green(color) * 0.587f + Color.blue(color) * 0.114f;
        }

        private static boolean isWood(int color) {
            int r = Color.red(color);
            int g = Color.green(color);
            int b = Color.blue(color);
            return r > 95 && g > 48 && b > 24 && r > g + 10 && g > b - 8 && r > b + 24;
        }

        private static int[] largestSpan(boolean[] values, int minimum) {
            int bestStart = -1;
            int bestEnd = -1;
            int start = -1;
            for (int i = 0; i <= values.length; i++) {
                boolean value = i < values.length && values[i];
                if (value && start < 0) {
                    start = i;
                } else if (!value && start >= 0) {
                    if (i - start >= minimum && i - start > bestEnd - bestStart) {
                        bestStart = start;
                        bestEnd = i;
                    }
                    start = -1;
                }
            }
            return bestStart < 0 ? null : new int[]{bestStart, bestEnd};
        }
    }

    private static SurfaceView findLargestSurface(View root) {
        if (root == null) return null;
        SurfaceView best = root instanceof SurfaceView ? (SurfaceView) root : null;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                SurfaceView child = findLargestSurface(group.getChildAt(i));
                if (child != null && (best == null
                        || child.getWidth() * child.getHeight() > best.getWidth() * best.getHeight())) {
                    best = child;
                }
            }
        }
        return best;
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
            t = clamp(t, 0f, 1f);
            float px = x1 + vx * t;
            float py = y1 + vy * t;
            if (distance(px, py, disc.x, disc.y) < radius + disc.radius) return false;
        }
        return true;
    }

    private static NativeState readNative() {
        try {
            int status = nativeStart();
            double[] values = nativeSnapshot();
            if (values == null || values.length < 5) return new NativeState(status, Double.NaN, Double.NaN);
            return new NativeState((int) values[0], values[1], values[2]);
        } catch (Throwable error) {
            return new NativeState(-10, Double.NaN, Double.NaN);
        }
    }

    private static float normalizeAngle(float value) {
        while (value > Math.PI) value -= (float) (Math.PI * 2.0);
        while (value < -Math.PI) value += (float) (Math.PI * 2.0);
        return value;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x2 - x1, y2 - y1);
    }

    private static Paint fill(int color) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        return paint;
    }

    private static Paint stroke(int color, float widthDp) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(widthDp);
        paint.setColor(color);
        return paint;
    }

    private static native int nativeStart();
    private static native double[] nativeSnapshot();

    private static final class NativeState {
        final int status;
        final double angle;
        final double power;

        NativeState(int status, double angle, double power) {
            this.status = status;
            this.angle = angle;
            this.power = power;
        }

        boolean usable() {
            return status == 2 && Double.isFinite(angle) && Double.isFinite(power);
        }
    }

    private static final class Frame {
        final RectF board;
        final RectF playable;
        final ArrayList<Disc> discs;
        final Disc striker;
        final ArrayList<PointF> pockets;
        final float pocketRadius;

        Frame(RectF board, RectF playable, ArrayList<Disc> discs, Disc striker,
              ArrayList<PointF> pockets, float pocketRadius) {
            this.board = board;
            this.playable = playable;
            this.discs = discs;
            this.striker = striker;
            this.pockets = pockets;
            this.pocketRadius = pocketRadius;
        }
    }

    private static final class Disc {
        float x;
        float y;
        final float radius;
        final float score;
        final float centerLum;

        Disc(float x, float y, float radius, float score, float centerLum) {
            this.x = x;
            this.y = y;
            this.radius = radius;
            this.score = score;
            this.centerLum = centerLum;
        }
    }

    private static final class CircleScore {
        final float score;
        final float centerLum;

        CircleScore(float score, float centerLum) {
            this.score = score;
            this.centerLum = centerLum;
        }
    }

    private static final class Candidate {
        final Disc striker;
        final Disc target;
        final PointF pocket;
        final PointF contact;
        final float score;
        final String label;

        Candidate(Disc striker, Disc target, PointF pocket, PointF contact,
                  float score, String label) {
            this.striker = striker;
            this.target = target;
            this.pocket = pocket;
            this.contact = contact;
            this.score = score;
            this.label = label;
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
