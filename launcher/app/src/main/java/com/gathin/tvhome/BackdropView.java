package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Home background: a painting (ArtRotator) filling the screen, crossfading to the next one, undimmed
 * except for a soft shade along the top edge so the clock and top bar stay readable.
 */
public class BackdropView extends View {

    private static final long WALL_FADE_MS = 1_500L;

    private final Paint wallPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint topShade = new Paint();
    private final Matrix matrix = new Matrix();
    private Bitmap wall, oldWall;
    private float wallMix = 1f;
    private ValueAnimator wallAnim;

    public BackdropView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** The painting behind everything; crossfades from the previous one. */
    void setWallpaper(Bitmap image) {
        oldWall = wall;
        wall = image;
        if (wallAnim != null) wallAnim.cancel();
        wallAnim = ValueAnimator.ofFloat(0f, 1f).setDuration(WALL_FADE_MS);
        wallAnim.addUpdateListener(a -> {
            wallMix = (float) a.getAnimatedValue();
            invalidate();
        });
        wallAnim.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        topShade.setShader(new LinearGradient(0, 0, 0, h * 0.32f, new int[]{0x8C000000, 0x40000000, 0x00000000},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.drawColor(Color.BLACK);
        if (wall == null) return;
        if (oldWall != null && wallMix < 1f) drawCover(canvas, oldWall, 1f, w, h);
        drawCover(canvas, wall, wallMix, w, h);
        canvas.drawRect(0, 0, w, h * 0.32f, topShade);
    }

    /** Scale to cover the screen, centred, cropping whichever edges overflow. */
    private void drawCover(Canvas canvas, Bitmap bmp, float alpha, int w, int h) {
        float s = Math.max((float) w / bmp.getWidth(), (float) h / bmp.getHeight());
        matrix.setScale(s, s);
        matrix.postTranslate((w - bmp.getWidth() * s) / 2f, (h - bmp.getHeight() * s) / 2f);
        wallPaint.setAlpha((int) (255 * alpha));
        canvas.drawBitmap(bmp, matrix, wallPaint);
    }
}
