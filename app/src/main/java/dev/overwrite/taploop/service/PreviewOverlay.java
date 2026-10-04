package dev.overwrite.taploop.service;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.overwrite.taploop.R;
import dev.overwrite.taploop.model.Step;

/**
 * Draws every tap and swipe of a macro where it will land. Taps are numbered
 * dots, swipes get an arrow. Tapping a marker hands the step back to the editor.
 */
public class PreviewOverlay extends ScreenOverlay {
    public interface Listener {
        /** indexes of the steps under the tapped marker, at least one */
        void onStep(int[] steps);
    }

    private final Listener listener;
    private final List<Step> steps;
    /** steps that land on (almost) the same spot share one dot */
    private final List<Group> groups = new ArrayList<>();
    private final float dotR;
    private int shown;
    private boolean barTop;
    private View bar;

    private static class Group {
        int x, y;
        final List<Integer> idx = new ArrayList<>();
        String label;
    }

    PreviewOverlay(TapService svc, List<Step> steps, Listener l) {
        super(svc);
        this.listener = l;
        this.steps = new ArrayList<>(steps);
        dotR = 15 * dp;
        for (int i = 0; i < this.steps.size(); i++) {
            Step s = this.steps.get(i);
            if (s.action == Step.WAIT) continue;
            shown++;
            Group g = null;
            for (Group o : groups) {
                if (Math.hypot(o.x - s.x, o.y - s.y) < dotR) { g = o; break; }
            }
            if (g == null) {
                g = new Group();
                g.x = s.x;
                g.y = s.y;
                groups.add(g);
            }
            g.idx.add(i);
        }
        for (Group g : groups) {
            StringBuilder b = new StringBuilder();
            for (int k = 0; k < g.idx.size(); k++) {
                if (k == 3) { b.append('…'); break; }
                if (k > 0) b.append(',');
                b.append(g.idx.get(k) + 1);
            }
            g.label = b.toString();
        }
    }

    @Override
    void build(FrameLayout root, Context c) {
        root.addView(new Markers(c), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        bar = LayoutInflater.from(c).inflate(R.layout.overlay_preview, root, false);
        ((TextView) bar.findViewById(R.id.info)).setText(
                shown + (shown == 1 ? " step" : " steps") + " · tap a marker to edit it");
        bar.findViewById(R.id.done).setOnClickListener(v -> close(true));
        bar.findViewById(R.id.move).setOnClickListener(v -> {
            barTop = !barTop;
            placeBar();
        });
        root.addView(bar);
        placeBar();
    }

    private void placeBar() {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bar.getLayoutParams();
        lp.gravity = barTop ? Gravity.TOP : Gravity.BOTTOM;
        lp.topMargin = barTop ? (int) (40 * dp) : 0;
        lp.bottomMargin = barTop ? 0 : (int) (56 * dp);
        bar.setLayoutParams(lp);
    }

    private int[] hitsAt(float x, float y) {
        float r = dotR * 1.4f;
        List<Integer> hits = new ArrayList<>();
        for (Group g : groups) {
            if (Math.hypot(g.x - x, g.y - y) <= r) hits.addAll(g.idx);
        }
        // swipe ends are tappable too
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            if (s.action == Step.SWIPE && !hits.contains(i)
                    && Math.hypot(s.x2 - x, s.y2 - y) <= r) hits.add(i);
        }
        Collections.sort(hits);
        int[] out = new int[hits.size()];
        for (int i = 0; i < out.length; i++) out[i] = hits.get(i);
        return out;
    }

    private class Markers extends View {
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int tapColor, swipeColor;
        private final int slop;
        private float sx, sy;
        private boolean moved;

        Markers(Context c) {
            super(c);
            tapColor = c.getColor(R.color.accent);
            swipeColor = c.getColor(R.color.ok);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            ring.setStyle(Paint.Style.STROKE);
            arrow.setStyle(Paint.Style.STROKE);
            arrow.setStrokeWidth(3 * dp);
            arrow.setStrokeCap(Paint.Cap.ROUND);
            arrow.setColor(swipeColor);
            arrow.setShadowLayer(2 * dp, 0, 0, 0x99000000);
            text.setColor(Color.WHITE);
            text.setFakeBoldText(true);
            text.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int[] off = offset(this);

            for (Step s : steps) {
                if (s.action != Step.SWIPE) continue;
                float x1 = s.x - off[0], y1 = s.y - off[1];
                float x2 = s.x2 - off[0], y2 = s.y2 - off[1];
                canvas.drawLine(x1, y1, x2, y2, arrow);
                double len = Math.hypot(x2 - x1, y2 - y1);
                if (len < 1) continue;
                double a = Math.atan2(y2 - y1, x2 - x1), head = 14 * dp;
                for (int k = -1; k <= 1; k += 2) {
                    double t = a + k * 2.65;
                    canvas.drawLine(x2, y2, (float) (x2 + Math.cos(t) * head),
                            (float) (y2 + Math.sin(t) * head), arrow);
                }
            }

            for (Group g : groups) {
                float x = g.x - off[0], y = g.y - off[1];
                Step first = steps.get(g.idx.get(0));
                // color checks get an outer ring in the color they wait for
                for (int i : g.idx) {
                    Step s = steps.get(i);
                    if (s.cond == Step.COND_COLOR) {
                        ring.setStrokeWidth(4 * dp);
                        ring.setColor(0xFF000000 | s.color);
                        canvas.drawCircle(x, y, dotR + 3 * dp, ring);
                        break;
                    }
                }
                dot.setColor(((first.action == Step.SWIPE ? swipeColor : tapColor) & 0xFFFFFF) | 0xDD000000);
                canvas.drawCircle(x, y, dotR, dot);
                ring.setStrokeWidth(1.5f * dp);
                ring.setColor(Color.WHITE);
                canvas.drawCircle(x, y, dotR, ring);

                text.setTextSize(12 * dp);
                float tw = text.measureText(g.label);
                if (tw > dotR * 1.7f) text.setTextSize(12 * dp * dotR * 1.7f / tw);
                canvas.drawText(g.label, x, y - (text.descent() + text.ascent()) / 2, text);
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sx = e.getRawX();
                    sy = e.getRawY();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.hypot(e.getRawX() - sx, e.getRawY() - sy) > slop) moved = true;
                    return true;
                case MotionEvent.ACTION_UP:
                    if (moved) return true;
                    int[] hits = hitsAt(e.getRawX(), e.getRawY());
                    if (hits.length > 0) {
                        performClick();
                        close(true);
                        listener.onStep(hits);
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
