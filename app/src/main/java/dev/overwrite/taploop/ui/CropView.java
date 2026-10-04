package dev.overwrite.taploop.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import dev.overwrite.taploop.R;

/** Shows a screenshot and lets you drag out the template and search area rects. */
public class CropView extends View {
    public static final int MODE_TEMPLATE = 0;
    public static final int MODE_AREA = 1;

    public interface Listener {
        void onChanged();
    }

    private Bitmap bmp;
    private final RectF dst = new RectF();
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint tplPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tplFill = new Paint();
    private final Paint areaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint();

    // both in bitmap px, null = not set
    private Rect tpl, area;
    private int mode = MODE_TEMPLATE;
    private float downX, downY;
    private Listener listener;

    public CropView(Context c, AttributeSet a) {
        super(c, a);
        float dp = getResources().getDisplayMetrics().density;
        tplPaint.setStyle(Paint.Style.STROKE);
        tplPaint.setStrokeWidth(2 * dp);
        tplPaint.setColor(c.getColor(R.color.ok));
        tplFill.setColor(0x334CC38A);
        areaPaint.setStyle(Paint.Style.STROKE);
        areaPaint.setStrokeWidth(2 * dp);
        areaPaint.setColor(c.getColor(R.color.accent));
        areaPaint.setPathEffect(new DashPathEffect(new float[]{8 * dp, 5 * dp}, 0));
        shade.setColor(0x88000000);
    }

    public void setBitmap(Bitmap b) {
        bmp = b;
        tpl = null;
        area = null;
        layoutBitmap();
        invalidate();
    }

    public void setMode(int m) {
        mode = m;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public Rect getTemplate() {
        return tpl;
    }

    public Rect getArea() {
        return area;
    }

    public void clearArea() {
        area = null;
        invalidate();
        if (listener != null) listener.onChanged();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        layoutBitmap();
    }

    private void layoutBitmap() {
        if (bmp == null || getWidth() == 0) return;
        float s = Math.min(getWidth() / (float) bmp.getWidth(), getHeight() / (float) bmp.getHeight());
        float w = bmp.getWidth() * s, h = bmp.getHeight() * s;
        float l = (getWidth() - w) / 2, t = (getHeight() - h) / 2;
        dst.set(l, t, l + w, t + h);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (bmp == null) return;
        c.drawBitmap(bmp, null, dst, bmpPaint);
        if (area != null) {
            RectF r = toView(area);
            // darken what's outside the search area
            c.drawRect(dst.left, dst.top, dst.right, r.top, shade);
            c.drawRect(dst.left, r.bottom, dst.right, dst.bottom, shade);
            c.drawRect(dst.left, r.top, r.left, r.bottom, shade);
            c.drawRect(r.right, r.top, dst.right, r.bottom, shade);
            c.drawRect(r, areaPaint);
        }
        if (tpl != null) {
            RectF r = toView(tpl);
            c.drawRect(r, tplFill);
            c.drawRect(r, tplPaint);
        }
    }

    private RectF toView(Rect r) {
        float s = dst.width() / bmp.getWidth();
        return new RectF(dst.left + r.left * s, dst.top + r.top * s,
                dst.left + r.right * s, dst.top + r.bottom * s);
    }

    private float bx(float vx) {
        float v = (vx - dst.left) * bmp.getWidth() / dst.width();
        return Math.max(0, Math.min(bmp.getWidth(), v));
    }

    private float by(float vy) {
        float v = (vy - dst.top) * bmp.getHeight() / dst.height();
        return Math.max(0, Math.min(bmp.getHeight(), v));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (bmp == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = bx(e.getX());
                downY = by(e.getY());
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                float x = bx(e.getX()), y = by(e.getY());
                Rect r = new Rect(Math.round(Math.min(downX, x)), Math.round(Math.min(downY, y)),
                        Math.round(Math.max(downX, x)), Math.round(Math.max(downY, y)));
                // ignore plain taps so a stray touch doesn't wipe the selection
                if (r.width() >= 2 && r.height() >= 2) {
                    if (mode == MODE_AREA) area = r;
                    else tpl = r;
                    invalidate();
                }
                if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    performClick();
                    if (listener != null) listener.onChanged();
                }
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
