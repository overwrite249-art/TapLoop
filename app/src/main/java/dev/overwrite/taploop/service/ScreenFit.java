package dev.overwrite.taploop.service;

import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowManager;

/**
 * Maps coordinates from the screen a macro was recorded on to the current one.
 * Same size: nothing changes. Rotated: we can't fix that by scaling, so we
 * only warn. Different size (other device, resolution change): scale.
 */
public final class ScreenFit {
    public static final ScreenFit SAME = new ScreenFit(1f, 1f, null);

    public final float sx, sy;
    /** shown to the user once, null if all is fine */
    public final String warning;

    private ScreenFit(float sx, float sy, String warning) {
        this.sx = sx;
        this.sy = sy;
        this.warning = warning;
    }

    public int x(int v) {
        return sx == 1f ? v : Math.round(v * sx);
    }

    public int y(int v) {
        return sy == 1f ? v : Math.round(v * sy);
    }

    public static ScreenFit of(int recW, int recH, int curW, int curH) {
        if (recW <= 0 || recH <= 0 || curW <= 0 || curH <= 0) return SAME;
        if (recW == curW && recH == curH) return SAME;
        boolean recPortrait = recH >= recW;
        boolean curPortrait = curH >= curW;
        if (recPortrait != curPortrait) {
            return new ScreenFit(1f, 1f, "Recorded in " + (recPortrait ? "portrait" : "landscape")
                    + ", rotate the screen back or taps will miss");
        }
        return new ScreenFit(curW / (float) recW, curH / (float) recH,
                "Screen size changed (" + recW + "x" + recH + " → " + curW + "x" + curH
                        + "), scaling taps");
    }

    /** real screen size in the current orientation, {w, h} */
    @SuppressWarnings("deprecation")
    public static int[] screenSize(Context c) {
        WindowManager wm = c.getSystemService(WindowManager.class);
        if (wm == null) return new int[]{0, 0};
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            return new int[]{b.width(), b.height()};
        }
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new int[]{dm.widthPixels, dm.heightPixels};
    }
}
