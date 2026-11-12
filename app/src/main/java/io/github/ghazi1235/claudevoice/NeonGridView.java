package io.github.ghazi1235.claudevoice;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * A faint luminous floor: a perspective grid that vanishes at a horizon and drifts slowly toward
 * the viewer, under a soft haze of light along the horizon line. Pure Canvas, driven by one
 * framework ValueAnimator. Cosmetic only.
 */
public class NeonGridView extends View {

    private float horizon = 0.66f;      // horizon line, as a fraction of the height
    private long cycleMs = 12000;       // one grid cell of travel
    private float gridAlpha = 0.5f;
    private int gridColor = 0xFFFF2A6D;
    private int horizonColor = 0xFF05D9E8;

    private float phase;
    private long lastDraw;                    // throttles redraws: the floor drifts slowly
    private static final long FRAME_MS = 40;  // ~24 fps is plenty for a cosmetic luminous floor
    private boolean animated = true;
    private boolean running;
    private ValueAnimator anim;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hzLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hzGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haze = new Paint();
    private final float density;

    public NeonGridView(Context context) {
        this(context, null);
    }

    public NeonGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.NeonGridView);
            horizon = a.getFloat(R.styleable.NeonGridView_horizon, horizon);
            cycleMs = a.getInt(R.styleable.NeonGridView_cycleMs, (int) cycleMs);
            gridAlpha = a.getFloat(R.styleable.NeonGridView_gridAlpha, gridAlpha);
            gridColor = a.getColor(R.styleable.NeonGridView_gridColor, gridColor);
            horizonColor = a.getColor(R.styleable.NeonGridView_horizonColor, horizonColor);
            a.recycle();
        }
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);
        hzLine.setStyle(Paint.Style.STROKE);
        hzLine.setStrokeCap(Paint.Cap.ROUND);
        hzLine.setStrokeWidth(1.2f * density);
        hzLine.setColor(Color.WHITE);
        hzGlow.setStyle(Paint.Style.STROKE);
        hzGlow.setStrokeCap(Paint.Cap.ROUND);
        hzGlow.setStrokeWidth(6f * density);
        hzGlow.setColor(horizonColor);
    }

    /** With animations switched off in the TV's developer settings the floor is a still. */
    public void setAnimated(boolean animated) {
        this.animated = animated;
        if (!animated) {
            stop();
        }
    }

    public void setRunning(boolean run) {
        running = run;
        if (run && animated && isAttachedToWindow()) {
            start();
        } else {
            stop();
        }
    }

    private void start() {
        if (anim != null || getVisibility() != VISIBLE) {
            return;
        }
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(cycleMs);
        anim.setInterpolator(new LinearInterpolator());
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setRepeatMode(ValueAnimator.RESTART);
        anim.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            // Redraw at ~24 fps instead of the display's full rate: ~70 anti-aliased strokes every
            // vsync is needless heat on a weak 24/7 TV, and a slow drift doesn't need the frames.
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastDraw >= FRAME_MS) {
                lastDraw = now;
                invalidate();
            }
        });
        anim.start();
    }

    private void stop() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (running && animated) {
            start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) {
            stop();
        } else if (running && animated && isAttachedToWindow()) {
            start();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w == 0 || h == 0) {
            return;
        }
        float hy = h * horizon;
        // Lines fade out as they approach the horizon, so the grid dissolves into the haze.
        int clear = gridColor & 0x00FFFFFF;
        Shader fade = new LinearGradient(0, hy, 0, h,
                new int[]{clear, gridColor, gridColor}, new float[]{0f, 0.62f, 1f},
                Shader.TileMode.CLAMP);
        line.setShader(fade);
        glow.setShader(fade);
        float hazeUp = h * 0.18f;
        float hazeDown = h * 0.05f;
        int hc = horizonColor & 0x00FFFFFF;
        haze.setShader(new LinearGradient(0, hy - hazeUp, 0, hy + hazeDown,
                new int[]{hc, hc | 0x26000000, hc | 0x8C000000, hc | 0x40000000, hc},
                new float[]{0f, 0.55f, 0.78f, 0.84f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        float hy = h * horizon;
        float cx = w / 2f;
        int a = Math.round(255 * Math.max(0f, Math.min(1f, gridAlpha)));

        // Horizon haze
        haze.setAlpha(a);
        canvas.drawRect(0, hy - h * 0.18f, w, hy + h * 0.05f, haze);

        // Rails: lines that converge on the vanishing point
        glow.setStrokeWidth(5f * density);
        glow.setAlpha(Math.round(a * 0.16f));
        line.setStrokeWidth(1.1f * density);
        line.setAlpha(Math.round(a * 0.9f));
        float spacing = w / 5.5f;
        float y0 = hy + (h - hy) * 0.012f;
        for (int i = -12; i <= 12; i++) {
            float xb = cx + i * spacing;
            float x0 = cx + (xb - cx) * 0.012f;
            canvas.drawLine(x0, y0, xb, h, glow);
            canvas.drawLine(x0, y0, xb, h, line);
        }

        // Sleepers: rows that slide toward the viewer, bunching up near the horizon
        int rows = 9;
        for (int k = 0; k <= rows; k++) {
            float z = (k + phase) / rows;
            if (z > 1f) {
                break;
            }
            float t = (float) Math.pow(z, 2.4);
            float y = hy + (h - hy) * t;
            glow.setStrokeWidth((4f + 4f * t) * density);
            line.setStrokeWidth((0.9f + 1.1f * t) * density);
            canvas.drawLine(0, y, w, y, glow);
            canvas.drawLine(0, y, w, y, line);
        }
        // Horizon line itself
        hzGlow.setAlpha(Math.round(a * 0.35f));
        hzLine.setAlpha(Math.round(a * 0.55f));
        canvas.drawLine(0, hy, w, hy, hzGlow);
        canvas.drawLine(w * 0.08f, hy, w * 0.92f, hy, hzLine);
    }
}
