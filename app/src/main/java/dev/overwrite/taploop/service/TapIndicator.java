package dev.overwrite.taploop.service;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import dev.overwrite.taploop.model.Step;

/**
 * Full screen overlay that draws a short fading ring where a replayed
 * tap or swipe lands. It's not touchable, so input goes straight through.
 */
class TapIndicator {
    private static final long FADE = 350;
    // capture frames lag the screen a little, keep them dirty a bit longer
    private static final long CAPTURE_LAG = 120;

    private final Context ctx;
    private final WindowManager wm;
    private final List<Mark> marks = new ArrayList<>();
    private MarkView view;
    private volatile long clearAt;

    private static class Mark {
        int action, x, y, x2, y2;
        long start, life;
    }

    TapIndicator(Context ctx, WindowManager wm) {
        this.ctx = ctx;
        this.wm = wm;
    }

    /** uptime after which captured frames no longer show any marker */
    long clearAt() {
        return clearAt;
    }

    /** main thread only */
    void show(int action, int x, int y, int x2, int y2, long duration) {
        if (action == Step.WAIT) return;
        if (!attach()) return;
        Mark m = new Mark();
        m.action = action;
        m.x = x; m.y = y; m.x2 = x2; m.y2 = y2;
        m.start = SystemClock.uptimeMillis();
        m.life = (action == Step.SWIPE ? Math.min(duration, 1500) : 0) + FADE;
        marks.add(m);
        clearAt = Math.max(clearAt, m.start + m.life + CAPTURE_LAG);
        view.postInvalidateOnAnimation();
    }

    private boolean attach() {
        if (view != null) return true;
        view = new MarkView(ctx);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setFitInsetsTypes(0);
        }
        try {
            wm.addView(view, lp);
            return true;
        } catch (Exception e) {
            view = null;
            return false;
        }
    }

    void detach() {
        marks.clear();
        if (view == null) return;
        try { wm.removeView(view); } catch (Exception ignored) {}
        view = null;
    }

    private class MarkView extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float radius;
        private final int[] loc = new int[2];

        MarkView(Context c) {
            super(c);
            float dp = c.getResources().getDisplayMetrics().density;
            radius = 16 * dp;
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(3 * dp);
            ring.setStrokeCap(Paint.Cap.ROUND);
            fill.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            getLocationOnScreen(loc);
            boolean alive = false;
            Iterator<Mark> it = marks.iterator();
            while (it.hasNext()) {
                Mark m = it.next();
                long age = now - m.start;
                if (age >= m.life) {
                    it.remove();
                    continue;
                }
                alive = true;
                long fadeStart = m.life - FADE;
                float a = age < fadeStart ? 1f : 1f - (age - fadeStart) / (float) FADE;
                float grow = 0.6f + 0.4f * Math.min(1f, age / 120f);
                ring.setColor(withAlpha(0x5AA9FF, a));
                fill.setColor(withAlpha(0x5AA9FF, a * 0.25f));
                float x = m.x - loc[0], y = m.y - loc[1];
                if (m.action == Step.SWIPE) {
                    float x2 = m.x2 - loc[0], y2 = m.y2 - loc[1];
                    c.drawLine(x, y, x2, y2, ring);
                    c.drawCircle(x2, y2, radius * grow, fill);
                    c.drawCircle(x2, y2, radius * grow, ring);
                    c.drawCircle(x, y, radius * 0.4f, fill);
                } else {
                    c.drawCircle(x, y, radius * grow, fill);
                    c.drawCircle(x, y, radius * grow, ring);
                }
            }
            if (alive) postInvalidateOnAnimation();
        }

        private int withAlpha(int rgb, float a) {
            int al = Math.max(0, Math.min(255, Math.round(a * 255)));
            return (al << 24) | rgb;
        }
    }
}
