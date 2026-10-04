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

    private long downTime;
    private int downX, downY, lastX, lastY;
    private boolean moved;
    private int downColor = -1;
    private byte[] downPatch;

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
        if (passing) return true;
        int x = Math.round(e.getRawX());
        int y = Math.round(e.getRawY());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downTime = e.getEventTime();
                downX = lastX = x;
                downY = lastY = y;
                moved = false;
                downColor = -1;
                downPatch = null;
                if (smart) {
                    ScreenGrabber g = ScreenGrabber.get();
                    if (g != null) {
                        downColor = g.colorAt(x, y);
                        downPatch = g.patchAt(x, y);
                    }
                }
                break;
            case MotionEvent.ACTION_MOVE:
                lastX = x;
                lastY = y;
                if (Math.hypot(x - downX, y - downY) > slop) moved = true;
                break;
            case MotionEvent.ACTION_UP:
                lastX = x;
                lastY = y;
                finishTouch(e.getEventTime());
                break;
            default:
                break;
        }
        return true;
    }

    private void finishTouch(long upTime) {
        Step s = new Step();
        s.action = moved ? Step.SWIPE : Step.TAP;
        s.x = downX;
        s.y = downY;
        s.x2 = moved ? lastX : downX;
        s.y2 = moved ? lastY : downY;
        s.duration = Math.max(10, upTime - downTime);
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
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        wm.updateViewLayout(layer, lp);
        // give the window manager a moment to apply the flag before injecting
        main.postDelayed(() -> svc.dispatch(
                TapService.buildGesture(s.action, s.x, s.y, s.x2, s.y2, s.duration),
                this::restore), 40);
    }

    private void restore() {
        passing = false;
        lastEnd = SystemClock.uptimeMillis();
        if (layer == null) return;
        lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { wm.updateViewLayout(layer, lp); } catch (Exception ignored) {}
    }
}
