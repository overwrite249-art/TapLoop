package dev.overwrite.taploop.service;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.List;

import dev.overwrite.taploop.R;

/**
 * Full screen layer on top of everything, used by the point picker and the
 * macro preview. Closing it brings TapLoop back to the front.
 */
abstract class ScreenOverlay {
    final TapService svc;
    final WindowManager wm;
    final float dp;
    final Handler main = new Handler(Looper.getMainLooper());

    private FrameLayout root;

    ScreenOverlay(TapService svc) {
        this.svc = svc;
        wm = svc.getSystemService(WindowManager.class);
        dp = svc.getResources().getDisplayMetrics().density;
    }

    abstract void build(FrameLayout root, Context c);

    void onClosed() {
    }

    boolean show() {
        Context c = new ContextThemeWrapper(svc, R.style.AppTheme);
        root = new FrameLayout(c) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (e.getAction() == KeyEvent.ACTION_UP) close(true);
                    return true;
                }
                return super.dispatchKeyEvent(e);
            }
        };
        build(root, c);

        // focusable so we get the back key
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setFitInsetsTypes(0);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try {
            wm.addView(root, lp);
            return true;
        } catch (Exception e) {
            root = null;
            return false;
        }
    }

    boolean isOpen() {
        return root != null;
    }

    void close(boolean openApp) {
        if (root == null) return;
        onClosed();
        // bring the app back while our window is still up, that keeps us
        // clear of the background activity start rules
        if (openApp) openApp();
        try { wm.removeView(root); } catch (Exception ignored) {}
        root = null;
    }

    private void openApp() {
        try {
            ActivityManager am = svc.getSystemService(ActivityManager.class);
            List<ActivityManager.AppTask> tasks = am.getAppTasks();
            if (!tasks.isEmpty()) {
                tasks.get(0).moveToFront();
                return;
            }
        } catch (Exception ignored) {
        }
        Intent i = svc.getPackageManager().getLaunchIntentForPackage(svc.getPackageName());
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { svc.startActivity(i); } catch (Exception ignored) {}
    }

    /** real display size in px */
    @SuppressWarnings("deprecation")
    Point screenSize() {
        Point p = new Point();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect b = wm.getCurrentWindowMetrics().getBounds();
            p.set(b.width(), b.height());
        } else {
            wm.getDefaultDisplay().getRealSize(p);
        }
        return p;
    }

    static int[] offset(View v) {
        int[] l = new int[2];
        v.getLocationOnScreen(l);
        return l;
    }
}
