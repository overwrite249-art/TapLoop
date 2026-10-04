package dev.overwrite.taploop.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.Collection;
import java.util.List;

import dev.overwrite.taploop.R;
import dev.overwrite.taploop.model.Step;

/** Thin bar at the top of the editor, one segment per step sized by its time. */
public class TimelineBar extends View {
    public interface OnStepTap {
        void onStepTap(int index);
    }

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wait = new Paint();
    private final Paint tap = new Paint();
    private final Paint swipe = new Paint();
    private final Paint sel = new Paint();
    private final RectF rect = new RectF();
    private final Path clip = new Path();
    private final float radius, gap;

    private List<Step> steps;
    private Collection<Step> selected;
    private OnStepTap onStepTap;

    public TimelineBar(Context c, AttributeSet attrs) {
        super(c, attrs);
        float dp = getResources().getDisplayMetrics().density;
        radius = 4 * dp;
        gap = Math.max(1, dp);
        bg.setColor(c.getColor(R.color.card));
        wait.setColor(c.getColor(R.color.line));
        tap.setColor(c.getColor(R.color.accent));
        swipe.setColor(c.getColor(R.color.ok));
        sel.setColor(c.getColor(R.color.text));
    }

    public void setSteps(List<Step> steps, Collection<Step> selected) {
        this.steps = steps;
        this.selected = selected;
        invalidate();
    }

    public void setOnStepTap(OnStepTap l) {
        onStepTap = l;
    }

    private static long weight(Step s) {
        return Math.max(1, s.delay + (s.action == Step.WAIT ? 0 : s.duration));
    }

    private long total() {
        long t = 0;
        for (Step s : steps) t += weight(s);
        return t;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, radius, radius, bg);
        if (steps == null || steps.isEmpty()) return;

        clip.reset();
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        float total = total();
        float x = 0;
        for (Step s : steps) {
            float segW = w * weight(s) / total;
            float g = segW > gap * 3 ? gap : 0;
            // delay part first, then the press itself
            float act = s.action == Step.WAIT ? 0 : segW * s.duration / weight(s);
            float delayEnd = x + segW - act;
            canvas.drawRect(x, 0, delayEnd, h, wait);
            if (act > 0) {
                // keep taps visible even when the delay dwarfs them
                float start = Math.min(delayEnd, x + segW - Math.max(act, Math.min(segW - g, gap * 2)));
                canvas.drawRect(start, 0, x + segW - g, h, s.action == Step.SWIPE ? swipe : tap);
            }
            if (selected != null && selected.contains(s)) {
                canvas.drawRect(x, h - h / 4f, x + segW - g, h, sel);
            }
            if (g > 0) canvas.drawRect(x + segW - g, 0, x + segW, h, bg);
            x += segW;
        }
        canvas.restore();
    }

    private int indexAt(float px) {
        if (steps == null || steps.isEmpty()) return -1;
        float total = total();
        float x = 0;
        for (int i = 0; i < steps.size(); i++) {
            x += getWidth() * weight(steps.get(i)) / total;
            if (px < x) return i;
        }
        return steps.size() - 1;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (onStepTap == null) return false;
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            int i = indexAt(e.getX());
            if (i >= 0) onStepTap.onStepTap(i);
        }
        return true;
    }
}
