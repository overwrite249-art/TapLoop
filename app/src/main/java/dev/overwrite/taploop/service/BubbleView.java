package dev.overwrite.taploop.service;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;

import dev.overwrite.taploop.R;

/** the collapsed panel, a small circle with a progress ring */
class BubbleView extends View {
    static final int IDLE = 0, REC = 1, PLAY = 2, PAUSE = 3;

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final int size;
    private final float dp;

    private int mode = IDLE;
    private String label = "";
    private float progress;

    BubbleView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        size = Math.round(50 * dp);
        bg.setColor(0xE61A1D23);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(3 * dp);
        track.setColor(c.getColor(R.color.line));
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(3 * dp);
        arc.setStrokeCap(Paint.Cap.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13,
                c.getResources().getDisplayMetrics()));
        text.setColor(c.getColor(R.color.text));
        setContentDescription("TapLoop panel");
    }

    void set(int mode, String label, float progress) {
        this.mode = mode;
        this.label = label == null ? "" : label;
        this.progress = Math.max(0f, Math.min(1f, progress));
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas c) {
        float r = size / 2f;
        float inset = 3 * dp;
        c.drawCircle(r, r, r - 1, bg);
        oval.set(inset, inset, size - inset, size - inset);
        c.drawOval(oval, track);

        if (mode == REC) {
            arc.setColor(getContext().getColor(R.color.rec));
            c.drawOval(oval, arc);
        } else if (mode == PLAY || mode == PAUSE) {
            arc.setColor(getContext().getColor(mode == PLAY ? R.color.accent : R.color.muted));
            c.drawArc(oval, -90, 360 * progress, false, arc);
        }

        if (mode == IDLE) {
            // three dots, same as the panel's drag handle
            text.setColor(getContext().getColor(R.color.muted));
            float gap = 6 * dp;
            for (int i = -1; i <= 1; i++) c.drawCircle(r + i * gap, r, 1.8f * dp, text);
        } else {
            text.setColor(getContext().getColor(mode == PAUSE ? R.color.muted : R.color.text));
            float y = r - (text.descent() + text.ascent()) / 2;
            c.drawText(label, r, y, text);
        }
    }
}
