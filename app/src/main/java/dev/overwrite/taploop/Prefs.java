package dev.overwrite.taploop;

import android.content.Context;
import android.content.SharedPreferences;

import dev.overwrite.taploop.model.Step;

/** All user settings live here. Values are clamped on read so a bad file can't break anything. */
public final class Prefs {
    public static final String TOL = "tol";
    public static final String TIMEOUT = "timeout";
    public static final String SMART_IMAGE = "smart_image";
    public static final String SMART_RADIUS = "smart_radius";
    public static final String SMART_MISS = "smart_miss";
    public static final String SMART_MAX_WAIT = "smart_max_wait";
    public static final String CAPTURE_SCALE = "capture_scale";
    public static final String HAPTIC = "haptic";
    public static final String KEEP_SCREEN_ON = "keep_screen_on";
    public static final String ACCENT = "accent";

    public static final float[] SCALES = {0.25f, 0.5f, 1f};
    public static final int[] ACCENTS = {0xFF5AA9FF, 0xFF4CC38A, 0xFFFFB454, 0xFFFF7AB6, 0xFFB48CFF};
    private static final int[] THEMES = {R.style.AppTheme, R.style.AppTheme_Green,
            R.style.AppTheme_Orange, R.style.AppTheme_Pink, R.style.AppTheme_Purple};

    private Prefs() {}

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static int tolerance(Context c) {
        return clamp(sp(c).getInt(TOL, 28), 0, 255);
    }

    public static long timeout(Context c) {
        return clamp(sp(c).getLong(TIMEOUT, 5000), 0, 600000);
    }

    /** smart record: match the image under the finger, or only its color */
    public static boolean smartImage(Context c) {
        return sp(c).getBoolean(SMART_IMAGE, true);
    }

    public static int smartRadius(Context c) {
        return clamp(sp(c).getInt(SMART_RADIUS, 0), 0, 400);
    }

    public static int smartMiss(Context c) {
        return clamp(sp(c).getInt(SMART_MISS, Step.MISS_TAP), Step.MISS_TAP, Step.MISS_STOP);
    }

    /** upper limit for the timeout smart record picks, ms */
    public static long smartMaxWait(Context c) {
        return clamp(sp(c).getLong(SMART_MAX_WAIT, 60000), 3000, 600000);
    }

    public static float captureScale(Context c) {
        float s = sp(c).getFloat(CAPTURE_SCALE, 0.5f);
        // snap to one of the supported values
        float best = SCALES[1];
        for (float v : SCALES) if (Math.abs(v - s) < Math.abs(best - s)) best = v;
        return best;
    }

    public static boolean haptic(Context c) {
        return sp(c).getBoolean(HAPTIC, false);
    }

    public static boolean keepScreenOn(Context c) {
        return sp(c).getBoolean(KEEP_SCREEN_ON, false);
    }

    public static int accent(Context c) {
        return clamp(sp(c).getInt(ACCENT, 0), 0, ACCENTS.length - 1);
    }

    public static int theme(Context c) {
        return THEMES[accent(c)];
    }

    /** a fresh step with the user's default tolerance and timeout */
    public static Step newStep(Context c) {
        Step s = new Step();
        s.tolerance = tolerance(c);
        s.timeout = timeout(c);
        return s;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static long clamp(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }
}
