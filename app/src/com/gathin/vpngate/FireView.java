package com.gathin.vpngate;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * Full-screen fire for the karaoke screen: a dark backdrop, a warm glow, four flame sheets made of
 * overlapping tongues and rising embers. Purely decorative; it stops drawing when the view leaves the
 * window.
 */
public class FireView extends View {

    private static final int FRAME_MS = 33;
    private static final int EMBERS = 54;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path flame = new Path();
    private final FlameSheet back, mid, front, core;
    private final float[] emberX = new float[EMBERS];
    private final float[] emberPhase = new float[EMBERS];
    private final float[] emberSpeed = new float[EMBERS];
    private final float[] emberSize = new float[EMBERS];

    private long start;
    private boolean running;

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            invalidate();
            postInvalidateDelayed(FRAME_MS);
        }
    };

    public FireView(Context context) {
        super(context);
        back = new FlameSheet(26, 20261104L, 0xFFB71C1C);
        mid = new FlameSheet(34, 7L, 0xFFFF4D00);
        front = new FlameSheet(42, 99L, 0xFFFF9800);
        core = new FlameSheet(50, 4242L, 0xFFFFE082);
        init();
    }

    public FireView(Context context, AttributeSet attrs) {
        this(context);
    }

    private void init() {
        setLayerType(LAYER_TYPE_HARDWARE, null);
        Random random = new Random(51L);
        for (int i = 0; i < EMBERS; i++) {
            emberX[i] = random.nextFloat();
            emberPhase[i] = random.nextFloat();
            emberSpeed[i] = 0.5f + random.nextFloat() * 1.0f;
            emberSize[i] = 2.0f + random.nextFloat() * 4.5f;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start = SystemClock.uptimeMillis();
        running = true;
        postInvalidateDelayed(FRAME_MS);
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;
        float t = (SystemClock.uptimeMillis() - start) / 1000f;

        paint.setAlpha(255);
        paint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0xFF160500, 0xFF060100, 0xFF000000}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        paint.setShader(new RadialGradient(w / 2f, h * 1.02f, Math.max(w * 0.5f, h * 0.65f),
                0x99FF3D00, 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        // The hot base of the fire, so the flame sheets read as fire even between breaths.
        paint.setShader(new LinearGradient(0, h * 0.72f, 0, h,
                0x00FF6A00, 0xCCFF7A00, Shader.TileMode.CLAMP));
        canvas.drawRect(0, h * 0.72f, w, h, paint);
        paint.setShader(null);

        drawSheet(canvas, w, h, t, back, 0.94f, 0.46f, 0xFF, 0.050f, 0.045f);
        drawSheet(canvas, w, h, t, mid, 0.97f, 0.35f, 0xFF, 0.035f, 0.06f);
        drawSheet(canvas, w, h, t, front, 1.00f, 0.24f, 0xFF, 0.024f, 0.08f);
        drawSheet(canvas, w, h, t, core, 1.02f, 0.13f, 0xFF, 0.016f, 0.10f);

        drawEmbers(canvas, w, h, t);
    }

    /**
     * One sheet of flames: a row of tongues whose heights breathe and whose bases drift. Overlapping the
     * tongues into a single path keeps it one draw call; the pointed tips are what read as fire.
     */
    private void drawSheet(Canvas c, int w, int h, float t, FlameSheet sheet,
                           float base, float height, int alpha, float halfWidth, float speed) {
        float baseY = h * base;
        float maxH = h * height;
        float step = Math.max(4f, w / 200f);

        paint.setAlpha(255);
        flame.reset();
        flame.moveTo(0, h);
        for (float x = 0; x <= w; x += step) {
            float y = baseY;
            for (int i = 0; i < sheet.count; i++) {
                float centre = (sheet.x[i] + (float) Math.sin(t * speed + sheet.phase[i]) * 0.035f) * w;
                float half = halfWidth * w * sheet.width[i];
                float d = Math.abs(x - centre);
                if (d >= half) continue;
                float tip = (float) Math.pow(1f - d / half, 0.55f);
                float breath = 0.55f + 0.45f * (float) Math.sin(t * (0.9f + sheet.phase[i] * 0.5f) + sheet.phase[i] * 6f);
                float tongue = maxH * sheet.height[i] * breath * tip;
                if (baseY - tongue < y) y = baseY - tongue;
            }
            flame.lineTo(x, y);
        }
        flame.lineTo(w, h);
        flame.close();

        int top = withAlpha(0x00FFFFFF & sheet.colour, 0);
        paint.setShader(new LinearGradient(0, baseY - maxH, 0, h, top, withAlpha(sheet.colour, alpha),
                Shader.TileMode.CLAMP));
        c.drawPath(flame, paint);
        paint.setShader(null);
    }

    private void drawEmbers(Canvas c, int w, int h, float t) {
        for (int i = 0; i < EMBERS; i++) {
            float cycle = (t * emberSpeed[i] + emberPhase[i]) % 1f;
            float y = h * (0.94f - 0.8f * cycle);
            float x = w * emberX[i] + (float) Math.sin(t * 1.4f + i) * w * 0.015f;
            int alpha = (int) (230 * (1f - cycle) * Math.min(1f, cycle * 5f));
            paint.setColor(Color.argb(Math.max(0, alpha), 255, 190, 90));
            c.drawCircle(x, y, emberSize[i] * (1f - 0.4f * cycle), paint);
        }
    }

    private static int withAlpha(int colour, int alpha) {
        return (colour & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    /** Fixed random shape of one flame sheet; the animation only moves and breathes it. */
    private static final class FlameSheet {
        final int count;
        final float[] x, width, height, phase;
        final int colour;

        FlameSheet(int count, long seed, int colour) {
            this.count = count;
            this.colour = colour;
            Random random = new Random(seed);
            x = new float[count];
            width = new float[count];
            height = new float[count];
            phase = new float[count];
            for (int i = 0; i < count; i++) {
                x[i] = (i + 0.15f + random.nextFloat() * 0.7f) / count;
                width[i] = 0.65f + random.nextFloat() * 0.7f;
                height[i] = 0.55f + random.nextFloat() * 0.75f;
                phase[i] = random.nextFloat() * 6.283f;
            }
        }
    }
}
