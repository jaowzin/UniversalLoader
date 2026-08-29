#!/usr/bin/env python3
from pathlib import Path

PATH = Path("app/src/main/java/dev/jaowzin/universalloader/CarromCtfAutoPilot.java")
MARKER = "CALIBRATED_POCKETS_V2"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


text = PATH.read_text(encoding="utf-8")
if MARKER in text:
    print("Carrom CTF vision already recalibrated")
    raise SystemExit(0)

old_candidate = '''    private static Candidate chooseCandidate(Frame frame) {
        if (frame == null || frame.striker == null || frame.discs.isEmpty()) return null;
        ArrayList<Candidate> candidates = new ArrayList<>();
        for (Disc target : frame.discs) {
            for (int pocketIndex = 0; pocketIndex < frame.pockets.size(); pocketIndex++) {
'''
new_candidate = '''    private static Candidate chooseCandidate(Frame frame) {
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
'''
text = replace_once(text, old_candidate, new_candidate, "candidate rail guard")

old_analyze = '''        static Frame analyze(Bitmap bitmap, int offsetX, int offsetY) {
            if (bitmap == null || bitmap.isRecycled()) return null;
            RectF board = findBoard(bitmap);
            if (board == null) return null;
            float side = Math.min(board.width(), board.height());
            float edgeInset = side * 0.045f;
            RectF playable = new RectF(board.left + edgeInset, board.top + edgeInset,
                    board.right - edgeInset, board.bottom - edgeInset);
            float pocketRadius = side * 0.047f;
            ArrayList<PointF> pockets = new ArrayList<>();
            pockets.add(new PointF(playable.left, playable.top));
            pockets.add(new PointF(playable.right, playable.top));
            pockets.add(new PointF(playable.left, playable.bottom));
            pockets.add(new PointF(playable.right, playable.bottom));

            ArrayList<Disc> discs = detectDiscs(bitmap, playable, pocketRadius);
            Disc striker = chooseStriker(discs, playable);
'''
new_analyze = '''        static Frame analyze(Bitmap bitmap, int offsetX, int offsetY) {
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
'''
text = replace_once(text, old_analyze, new_analyze, "board/pocket model")

old_find_board = '''        private static RectF findBoard(Bitmap bitmap) {
'''
new_pocket_methods = '''        private static ArrayList<PointF> detectPockets(Bitmap bitmap, RectF board) {
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
'''
text = replace_once(text, old_find_board, new_pocket_methods, "pocket detector insertion")

old_striker = '''        private static Disc chooseStriker(List<Disc> discs, RectF board) {
            Disc best = null;
            float bestScore = -Float.MAX_VALUE;
            float side = Math.min(board.width(), board.height());
            for (Disc disc : discs) {
                float yNorm = (disc.y - board.top) / Math.max(1f, board.height());
                if (yNorm < 0.58f) continue;
                float score = yNorm * 80f + disc.centerLum * 0.16f
                        + (disc.radius / side) * 500f + disc.score * 0.08f;
                if (score > bestScore) {
                    bestScore = score;
                    best = disc;
                }
            }
            return best;
        }
'''
new_striker = '''        private static Disc chooseStriker(List<Disc> discs, RectF board) {
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
'''
text = replace_once(text, old_striker, new_striker, "striker selector")

PATH.write_text(text, encoding="utf-8")
print("Recalibrated Carrom CTF pocket geometry, rail filtering and striker selection")
