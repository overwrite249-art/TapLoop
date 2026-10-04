package dev.overwrite.taploop.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import dev.overwrite.taploop.R;

/** Small drawing of a recorded swipe, scaled to fit. Green dot = start. */
public class PathPreview extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final float pad;
    private int[] pts;

    public PathPreview(Context c, AttributeSet attrs) {
        super(c, attrs);
        float dp = getResources().getDisplayMetrics().density;
        pad = 10 * dp;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.5f * dp);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(c.getColor(R.color.accent));
    }

    public void setPoints(int[] pts) {
        this.pts = pts;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (pts == null || pts.length < 6) return;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (int i = 0; i < pts.length; i += 3) {
            minX = Math.min(minX, pts[i]); maxX = Math.max(maxX, pts[i]);
            minY = Math.min(minY, pts[i + 1]); maxY = Math.max(maxY, pts[i + 1]);
        }
        float w = getWidth() - 2 * pad, h = getHeight() - 2 * pad;
        float scale = Math.min(w / Math.max(1, maxX - minX), h / Math.max(1, maxY - minY));
        // keep it centered and don't blow up tiny paths too much
        scale = Math.min(scale, 4);
        float ox = pad + (w - (maxX - minX) * scale) / 2 - minX * scale;
        float oy = pad + (h - (maxY - minY) * scale) / 2 - minY * scale;

        shape.rewind();
        shape.moveTo(pts[0] * scale + ox, pts[1] * scale + oy);
        for (int i = 3; i < pts.length; i += 3) shape.lineTo(pts[i] * scale + ox, pts[i + 1] * scale + oy);
        canvas.drawPath(shape, line);

        float r = line.getStrokeWidth() * 1.8f;
        int n = pts.length - 3;
        dot.setColor(getContext().getColor(R.color.rec));
        canvas.drawCircle(pts[n] * scale + ox, pts[n + 1] * scale + oy, r, dot);
        dot.setColor(getContext().getColor(R.color.ok));
        canvas.drawCircle(pts[0] * scale + ox, pts[1] * scale + oy, r, dot);
    }
}
