package dev.overwrite.taploop.service;

import android.annotation.SuppressLint;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.overwrite.taploop.R;
import dev.overwrite.taploop.capture.ScreenGrabber;
import dev.overwrite.taploop.model.Step;

/**
 * Full screen invisible layer that catches a touch, writes it down and then
 * replays it to whatever is underneath so the app you're recording still reacts.
 */
class Recorder implements View.OnTouchListener {
    final boolean smart;

    private final TapService svc;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Step> steps = new ArrayList<>();
    private final int slop;

    private View layer;
    private WindowManager.LayoutParams lp;
    private long lastEnd;
    private boolean passing;
    private boolean selfHit;
    private Step inFlight;
    private int attempt;

    // how long to wait for the window flag to apply before re-sending the touch.
    // if our own injected touch still lands on the layer we retry with a longer wait.
    private static final long[] PASS_DELAYS = {50, 120, 250, 500};

    private long downTime;
    private int downX, downY, lastX, lastY;
    private boolean moved;
    private int downColor = -1;
    private byte[] downPatch;

    // finger path for swipes, x,y,t triples
    private static final int MAX_POINTS = 400;
    private int[] pts = new int[MAX_POINTS * 3];
    private int npts;
    private long minGap;

    Recorder(TapService svc, WindowManager wm, boolean smart) {
        this.svc = svc;
        this.wm = wm;
        this.smart = smart;
        slop = ViewConfiguration.get(svc).getScaledTouchSlop() * 2;
    }

    int count() {
        return steps.size();
    }

    @SuppressLint("ClickableViewAccessibility")
    void start() {
        layer = new View(svc);
        // no border in smart mode, it would end up in the captured patches
        if (!smart) layer.setBackgroundResource(R.drawable.rec_frame);
        layer.setOnTouchListener(this);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setFitInsetsTypes(0);
        }
        wm.addView(layer, lp);
        lastEnd = SystemClock.uptimeMillis();
    }

    List<Step> stop() {
        if (layer != null) {
            try { wm.removeView(layer); } catch (Exception ignored) {}
            layer = null;
        }
        return steps;
    }

    @Override
    public boolean onTouch(View v, MotionEvent e) {
        int x = Math.round(e.getRawX());
        int y = Math.round(e.getRawY());
        if (passing) {
            // the flag wasn't applied yet and our replayed touch hit the layer
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN && inFlight != null
                    && Math.abs(x - inFlight.x) < 12 && Math.abs(y - inFlight.y) < 12) {
                selfHit = true;
            }
            return true;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downTime = e.getEventTime();
                downX = lastX = x;
                downY = lastY = y;
                moved = false;
                downColor = -1;
                downPatch = null;
                npts = 0;
                minGap = 16;
                addPoint(x, y, 0, true);
                if (smart) {
                    ScreenGrabber g = ScreenGrabber.get();
                    if (g != null) {
                        downColor = g.colorAt(x, y);
                        downPatch = g.patchAt(x, y);
                    }
                }
                break;
            case MotionEvent.ACTION_MOVE:
                float ox = e.getRawX() - e.getX(), oy = e.getRawY() - e.getY();
                for (int h = 0; h < e.getHistorySize(); h++) {
                    addPoint(Math.round(e.getHistoricalX(h) + ox), Math.round(e.getHistoricalY(h) + oy),
                            e.getHistoricalEventTime(h) - downTime, false);
                }
                addPoint(x, y, e.getEventTime() - downTime, false);
                lastX = x;
                lastY = y;
                if (Math.hypot(x - downX, y - downY) > slop) moved = true;
                break;
            case MotionEvent.ACTION_UP:
                lastX = x;
                lastY = y;
                addPoint(x, y, e.getEventTime() - downTime, true);
                finishTouch(e.getEventTime());
                break;
            default:
                break;
        }
        return true;
    }

    private void addPoint(int x, int y, long t, boolean force) {
        if (npts > 0) {
            int last = (npts - 1) * 3;
            long dt = t - pts[last + 2];
            if (!force && dt < minGap) return;
            if (dt <= 0) {
                pts[last] = x;
                pts[last + 1] = y;
                return;
            }
            // finger is resting, just stretch the last point instead of piling up copies
            if (npts > 1 && same(last, x, y) && same(last - 3, x, y)) {
                pts[last + 2] = (int) t;
                return;
            }
        }
        if (npts == MAX_POINTS) {
            // too long, drop every other point and sample half as often from now on
            int keep = 0;
            for (int i = 0; i < npts; i += 2, keep++) System.arraycopy(pts, i * 3, pts, keep * 3, 3);
            if ((npts - 1) % 2 != 0) {
                System.arraycopy(pts, (npts - 1) * 3, pts, keep * 3, 3);
                keep++;
            }
            npts = keep;
            minGap *= 2;
        }
        int i = npts * 3;
        pts[i] = x;
        pts[i + 1] = y;
        pts[i + 2] = (int) t;
        npts++;
    }

    private boolean same(int i, int x, int y) {
        return Math.abs(pts[i] - x) <= 2 && Math.abs(pts[i + 1] - y) <= 2;
    }

    private void finishTouch(long upTime) {
        Step s = new Step();
        s.action = moved ? Step.SWIPE : Step.TAP;
        s.x = downX;
        s.y = downY;
        s.x2 = moved ? lastX : downX;
        s.y2 = moved ? lastY : downY;
        s.duration = Math.max(10, upTime - downTime);
        if (moved && npts >= 3) {
            s.path = Arrays.copyOf(pts, npts * 3);
            s.path[s.path.length - 1] = (int) s.duration;
        }
        long gap = Math.max(0, downTime - lastEnd);
        s.delay = gap;

        if (smart && (downPatch != null || downColor != -1)) {
            if (downPatch != null) {
                s.cond = Step.COND_IMAGE;
                s.patch = downPatch;
                s.patchSize = ScreenGrabber.PATCH;
            } else {
                s.cond = Step.COND_COLOR;
            }
            s.color = downColor == -1 ? 0 : downColor;
            // in smart mode we don't wait the recorded time, we wait for the
            // thing to show up. the recorded gap is only used for the timeout.
            s.delay = 0;
            s.timeout = Math.min(60000, Math.max(3000, gap * 3 + 2000));
        }
        steps.add(s);
        svc.onRecorded();
        passThrough(s);
    }

    private void passThrough(Step s) {
        if (layer == null) return;
        passing = true;
        inFlight = s;
        attempt = 0;
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        wm.updateViewLayout(layer, lp);
        send();
    }

    private void send() {
        Step s = inFlight;
        selfHit = false;
        main.postDelayed(() -> {
            if (layer == null || s == null) return;
            Gestures.send(svc, Gestures.build(s, 0, 0), this::afterSend);
        }, PASS_DELAYS[Math.min(attempt, PASS_DELAYS.length - 1)]);
    }

    private void afterSend() {
        if (selfHit && attempt < PASS_DELAYS.length - 1 && layer != null) {
            attempt++;
            try { wm.updateViewLayout(layer, lp); } catch (Exception ignored) {}
            send();
            return;
        }
        restore();
    }

    private void restore() {
        passing = false;
        inFlight = null;
        selfHit = false;
        lastEnd = SystemClock.uptimeMillis();
        if (layer == null) return;
        lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { wm.updateViewLayout(layer, lp); } catch (Exception ignored) {}
    }
}
