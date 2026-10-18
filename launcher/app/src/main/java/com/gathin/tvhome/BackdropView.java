package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Home background, behind the tiles:
 *  - while music plays, the album art blown up into a soft blur that drifts slowly;
 *  - a glow in the focused app's colour behind its tile.
 * The blur is free: the art is shrunk to a few pixels and drawn back up with filtering.
 */
public class BackdropView extends View {

    private static final int BLUR_PX = 10;
    private static final long FADE_MS = 900L;
    private static final long DRIFT_MS = 45_000L;
    private static final int SCRIM = 0x8C000000;

    private final Paint artPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();

    private Bitmap art, oldArt;
    private float artMix = 1f;      // 0 → oldArt, 1 → art
    private float artAlpha = 0f;    // fades the whole art layer in and out
    private ValueAnimator artAnim, alphaAnim, glowAnim;
    private final ValueAnimator drift;
    private float driftT;
    private boolean resumed, artWanted;

    private int glowColor = Color.TRANSPARENT;
    private float glowX = 0.5f, glowAlpha = 0f;

    public BackdropView(Context context, AttributeSet attrs) {
        super(context, attrs);
        drift = ValueAnimator.ofFloat(0f, 1f).setDuration(DRIFT_MS);
        drift.setRepeatMode(ValueAnimator.REVERSE);
        drift.setRepeatCount(ValueAnimator.INFINITE);
        drift.setInterpolator(new LinearInterpolator());
        drift.addUpdateListener(a -> {
            driftT = (float) a.getAnimatedValue();
            invalidate();
        });
    }

    /** Album art to show (null = none). Same art again is a no-op. */
    void setArt(Bitmap source) {
        artWanted = source != null;
        updateDrift();
        if (source == null) {
            fadeArt(0f);
            return;
        }
        Bitmap tiny = Bitmap.createScaledBitmap(source, BLUR_PX, BLUR_PX, true);
        if (art != null && sameImage(art, tiny)) {
            fadeArt(1f);
            return;
        }
        oldArt = art;
        art = tiny;
        if (artAnim != null) artAnim.cancel();
        artAnim = ValueAnimator.ofFloat(0f, 1f).setDuration(FADE_MS);
        artAnim.addUpdateListener(a -> {
            artMix = (float) a.getAnimatedValue();
            invalidate();
        });
        artAnim.start();
        fadeArt(1f);
    }

    /** Glow behind a focused tile, in a colour sampled from its banner; null hides it. */
    void glowFor(Drawable banner, View tile) {
        if (banner == null || tile == null) {
            animateGlow(glowColor, glowX, 0f);
            return;
        }
        int[] at = new int[2], me = new int[2];
        tile.getLocationOnScreen(at);
        getLocationOnScreen(me);
        float x = (at[0] - me[0] + tile.getWidth() / 2f) / Math.max(1, getWidth());
        animateGlow(vividColor(banner), x, 1f);
    }

    void pause() {
        resumed = false;
        updateDrift();
    }

    void resume() {
        resumed = true;
        updateDrift();
    }

    /** Only animate while home is on screen and there is art to move. */
    private void updateDrift() {
        if (resumed && artWanted) {
            if (!drift.isStarted()) drift.start();
            else if (drift.isPaused()) drift.resume();
        } else if (drift.isRunning()) {
            drift.pause();
        }
    }

    // ---- drawing ---------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.drawColor(Color.BLACK);

        if (artAlpha > 0f && art != null) {
            if (oldArt != null && artMix < 1f) drawArt(canvas, oldArt, artAlpha * (1f - artMix), w, h);
            drawArt(canvas, art, artAlpha * artMix, w, h);
            canvas.drawColor(SCRIM);
        }

        if (glowAlpha > 0f) {
            float cx = glowX * w, cy = h * 0.55f, r = w * 0.55f;
            int c = glowColor & 0x00FFFFFF;
            int a = (int) (0x55 * glowAlpha);
            glowPaint.setShader(new RadialGradient(cx, cy, r,
                    new int[]{(a << 24) | c, ((a / 3) << 24) | c, c}, new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, glowPaint);
        }
    }

    /** Cover the screen, oversized so the slow drift (pan + zoom) never shows an edge. */
    private void drawArt(Canvas canvas, Bitmap bmp, float alpha, int w, int h) {
        float base = Math.max(w, h) * 1.35f / bmp.getWidth();
        float zoom = 1f + 0.08f * driftT;
        float s = base * zoom;
        float dx = (w - bmp.getWidth() * s) / 2f + (driftT - 0.5f) * w * 0.10f;
        float dy = (h - bmp.getHeight() * s) / 2f - (driftT - 0.5f) * h * 0.08f;
        matrix.setScale(s, s);
        matrix.postTranslate(dx, dy);
        artPaint.setAlpha((int) (255 * alpha));
        canvas.drawBitmap(bmp, matrix, artPaint);
    }

    private void fadeArt(float to) {
        if (alphaAnim != null) alphaAnim.cancel();
        if (artAlpha == to) return;
        alphaAnim = ValueAnimator.ofFloat(artAlpha, to).setDuration(FADE_MS);
        alphaAnim.addUpdateListener(a -> {
            artAlpha = (float) a.getAnimatedValue();
            invalidate();
        });
        alphaAnim.start();
    }

    private void animateGlow(int color, float x, float alpha) {
        if (glowAnim != null) glowAnim.cancel();
        int fromColor = glowAlpha == 0f ? color : glowColor;
        float fromX = glowAlpha == 0f ? x : glowX, fromA = glowAlpha;
        ArgbEvaluator argb = new ArgbEvaluator();
        glowAnim = ValueAnimator.ofFloat(0f, 1f).setDuration(450);
        glowAnim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            glowColor = (int) argb.evaluate(t, fromColor, color);
            glowX = fromX + (x - fromX) * t;
            glowAlpha = fromA + (alpha - fromA) * t;
            invalidate();
        });
        glowAnim.start();
    }

    private static boolean sameImage(Bitmap a, Bitmap b) {
        for (int y = 0; y < BLUR_PX; y += 3) {
            for (int x = 0; x < BLUR_PX; x += 3) if (a.getPixel(x, y) != b.getPixel(x, y)) return false;
        }
        return true;
    }

    /** A vivid colour from a banner or icon (from the original launcher's ambient glow). */
    static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }
}
