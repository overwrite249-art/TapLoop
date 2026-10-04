package dev.overwrite.taploop.service;

import android.annotation.SuppressLint;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Invisible layer over the whole screen while a macro plays, so a stray touch
 * doesn't mess it up. The panel sits above it so Stop still works. Our own
 * gestures would land on it too, so it goes see-through for each one.
 */
class TouchBlocker {
    private final TapService svc;
    private final WindowManager wm;
    private View layer;
    private WindowManager.LayoutParams lp;
    private long lastHint;

    TouchBlocker(TapService svc, WindowManager wm) {
        this.svc = svc;
        this.wm = wm;
    }

    @SuppressLint("ClickableViewAccessibility")
    void attach() {
        if (layer != null) return;
        layer = new View(svc);
        layer.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN
                    && SystemClock.uptimeMillis() - lastHint > 3000) {
                lastHint = SystemClock.uptimeMillis();
                svc.toast("Touches are blocked while the macro plays, press Stop on the panel");
            }
            return true;
        });
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
        try {
            wm.addView(layer, lp);
        } catch (Exception e) {
            layer = null;
        }
    }

    void detach() {
        if (layer == null) return;
        try { wm.removeViewImmediate(layer); } catch (Exception ignored) {}
        layer = null;
    }

    boolean isOn() {
        return layer != null;
    }

    /** let gestures through (or stop letting them), returns true if anything changed */
    boolean setPassing(boolean pass) {
        if (layer == null) return false;
        boolean now = (lp.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0;
        if (now == pass) return false;
        if (pass) lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { wm.updateViewLayout(layer, lp); } catch (Exception ignored) {}
        return true;
    }
}
