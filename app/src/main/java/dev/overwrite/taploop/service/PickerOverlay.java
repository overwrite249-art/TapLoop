package dev.overwrite.taploop.service;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Locale;

import dev.overwrite.taploop.R;
import dev.overwrite.taploop.capture.ScreenGrabber;

/**
 * Crosshair you drag around to pick a screen point. Dragging moves the cross
 * relative to the finger so the finger never covers the spot, a short tap
 * jumps it. With screen capture on there's a zoomed loupe and the color.
 */
public class PickerOverlay extends ScreenOverlay {
    public interface Listener {
        /** color is 0xRRGGBB, or -1 if the user didn't ask for it */
        void onPick(int x, int y, int color);
    }

    private static final int LOUPE_CELLS = 15;

    private final Listener listener;
    private final int[] from;
    private final boolean wantColor;
    private final int screenW, screenH;
    private int px, py;
    private int color = -1;
    private boolean barTop;

    private Cross cross;
    private View bar, swatch, useColor;
    private TextView readout;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!isOpen()) return;
            refresh();
            cross.invalidate();
            main.postDelayed(this, 120);
        }
    };

    PickerOverlay(TapService svc, int x, int y, int[] from, boolean wantColor, Listener l) {
        super(svc);
        this.listener = l;
        this.from = from;
        this.wantColor = wantColor;
        Point size = screenSize();
        screenW = size.x;
        screenH = size.y;
        if (x <= 0 && y <= 0) {
            if (from != null) {
                x = from[0];
                y = from[1];
            } else {
                x = screenW / 2;
                y = screenH / 2;
            }
        }
        px = clamp(x, screenW);
        py = clamp(y, screenH);
    }

    private static int clamp(int v, int size) {
        return Math.max(0, Math.min(size - 1, v));
    }

    @Override
    void build(FrameLayout root, Context c) {
        cross = new Cross(c);
        root.addView(cross, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        bar = LayoutInflater.from(c).inflate(R.layout.overlay_picker, root, false);
        swatch = bar.findViewById(R.id.swatch);
        readout = bar.findViewById(R.id.readout);
        useColor = bar.findViewById(R.id.use_color);
        bar.findViewById(R.id.cancel).setOnClickListener(v -> close(true));
        bar.findViewById(R.id.done).setOnClickListener(v -> finish(false));
        useColor.setOnClickListener(v -> finish(true));
        root.addView(bar);

        barTop = py > screenH / 2;
        placeBar();
        refresh();
        main.post(tick);
    }

    @Override
    void onClosed() {
        main.removeCallbacks(tick);
    }

    private void finish(boolean withColor) {
        refresh();
        int c = withColor ? color : -1;
        close(true);
        listener.onPick(px, py, c);
    }

    private void moveTo(int x, int y) {
        px = clamp(x, screenW);
        py = clamp(y, screenH);
        // keep the bar away from the cross
        if (barTop && py < screenH * 0.4f) {
            barTop = false;
            placeBar();
        } else if (!barTop && py > screenH * 0.6f) {
            barTop = true;
            placeBar();
        }
        refresh();
        cross.invalidate();
    }

    private void placeBar() {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bar.getLayoutParams();
        lp.gravity = barTop ? Gravity.TOP : Gravity.BOTTOM;
        lp.topMargin = barTop ? (int) (40 * dp) : 0;
        lp.bottomMargin = barTop ? 0 : (int) (56 * dp);
        bar.setLayoutParams(lp);
    }

    private void refresh() {
        ScreenGrabber g = ScreenGrabber.get();
        color = g != null ? g.colorAt(px, py) : -1;
        String s = String.format(Locale.US, "X %d  Y %d", px, py);
        if (color >= 0) {
            s += String.format(Locale.US, "  #%06X", color);
            swatch.setBackgroundColor(0xFF000000 | color);
            swatch.setVisibility(View.VISIBLE);
        } else {
            swatch.setVisibility(View.GONE);
        }
        readout.setText(s);
        useColor.setVisibility(wantColor && color >= 0 ? View.VISIBLE : View.GONE);
    }

    private class Cross extends View {
        private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint();
        private final Paint anchor = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        // nothing gets drawn inside this radius, otherwise the capture would
        // see our own lines when reading the color / loupe
        private final float gap;
        private final int slop;

        private float sx, sy;
        private int ox, oy;
        private boolean moved;

        Cross(Context c) {
            super(c);
            gap = Math.max(14 * dp, 20);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            shadow.setStyle(Paint.Style.STROKE);
            shadow.setStrokeWidth(3 * dp);
            shadow.setColor(0x99000000);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Math.max(1, dp));
            line.setColor(Color.WHITE);
            anchor.setStrokeWidth(2 * dp);
            anchor.setColor(c.getColor(R.color.ok));
            text.setColor(c.getColor(R.color.muted));
            text.setTextSize(11 * dp);
            text.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int[] off = offset(this);
            float cx = px - off[0], cy = py - off[1];
            int w = getWidth(), h = getHeight();

            if (from != null) {
                float ax = from[0] - off[0], ay = from[1] - off[1];
                float d = (float) Math.hypot(cx - ax, cy - ay);
                if (d > gap) {
                    float k = (d - gap) / d;
                    canvas.drawLine(ax, ay, ax + (cx - ax) * k, ay + (cy - ay) * k, anchor);
                }
                canvas.drawCircle(ax, ay, 6 * dp, anchor);
            }

            drawCross(canvas, cx, cy, w, h, shadow);
            drawCross(canvas, cx, cy, w, h, line);

            drawLoupe(canvas, cx, cy, w);
        }

        private void drawCross(Canvas canvas, float cx, float cy, int w, int h, Paint p) {
            canvas.drawLine(0, cy, cx - gap, cy, p);
            canvas.drawLine(cx + gap, cy, w, cy, p);
            canvas.drawLine(cx, 0, cx, cy - gap, p);
            canvas.drawLine(cx, cy + gap, cx, h, p);
            canvas.drawCircle(cx, cy, gap, p);
        }

        private void drawLoupe(Canvas canvas, float cx, float cy, int w) {
            float r = 60 * dp, away = 100 * dp;
            float lx = cx - away, ly = cy - away;
            if (lx - r < 0) lx = cx + away;
            if (lx + r > w) lx = cx - away;
            if (ly - r < 0) ly = cy + away;

            canvas.save();
            clip.reset();
            clip.addCircle(lx, ly, r, Path.Direction.CW);
            canvas.clipPath(clip);
            fill.setColor(svc.getColor(R.color.card));
            canvas.drawCircle(lx, ly, r, fill);

            ScreenGrabber g = ScreenGrabber.get();
            if (g != null && g.hasFrame()) {
                int n = LOUPE_CELLS, half = n / 2;
                int step = Math.max(1, Math.round(1 / ScreenGrabber.SCALE));
                float cell = 2 * r / n, x0 = lx - r, y0 = ly - r;
                for (int j = 0; j < n; j++) {
                    for (int i = 0; i < n; i++) {
                        int c = g.colorAt(px + (i - half) * step, py + (j - half) * step);
                        if (c < 0) continue;
                        fill.setColor(0xFF000000 | c);
                        canvas.drawRect(x0 + i * cell, y0 + j * cell,
                                x0 + (i + 1) * cell, y0 + (j + 1) * cell, fill);
                    }
                }
                float c0 = x0 + half * cell, c1 = y0 + half * cell;
                canvas.drawRect(c0, c1, c0 + cell, c1 + cell, shadow);
                canvas.drawRect(c0, c1, c0 + cell, c1 + cell, line);
            } else {
                canvas.drawText("turn on screen", lx, ly - 4 * dp, text);
                canvas.drawText("capture for zoom", lx, ly + 10 * dp, text);
            }
            canvas.restore();
            canvas.drawCircle(lx, ly, r, shadow);
            canvas.drawCircle(lx, ly, r, line);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sx = e.getRawX();
                    sy = e.getRawY();
                    ox = px;
                    oy = py;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                    if (!moved && Math.hypot(dx, dy) < slop) return true;
                    moved = true;
                    moveTo(ox + Math.round(dx), oy + Math.round(dy));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!moved) {
                        moveTo(Math.round(e.getRawX()), Math.round(e.getRawY()));
                        performClick();
                    }
                    return true;
            }
            return super.onTouchEvent(e);
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }
    }
}
