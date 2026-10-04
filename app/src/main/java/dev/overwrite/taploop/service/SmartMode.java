package dev.overwrite.taploop.service;

import android.content.Context;

import dev.overwrite.taploop.model.Step;

/** What smart record should wait for on each recorded tap. */
public final class SmartMode {
    public static final int IMAGE = 0;
    public static final int COLOR = 1;
    public static final int BOTH = 2;

    public static final String[] NAMES = {"Image", "Color", "Both"};
    private static final String[] SHORT = {"img", "color", "both"};

    private SmartMode() {
    }

    public static int get(Context c) {
        int m = c.getSharedPreferences("state", Context.MODE_PRIVATE).getInt("smart_mode", IMAGE);
        return m < IMAGE || m > BOTH ? IMAGE : m;
    }

    public static void set(Context c, int mode) {
        c.getSharedPreferences("state", Context.MODE_PRIVATE).edit().putInt("smart_mode", mode).apply();
    }

    public static String shortName(int mode) {
        return SHORT[Math.max(0, Math.min(SHORT.length - 1, mode))];
    }

    /** falls back to whatever we managed to grab */
    static int condFor(int mode, boolean hasPatch, boolean hasColor) {
        if (mode == BOTH && hasPatch && hasColor) return Step.COND_BOTH;
        if (mode == COLOR && hasColor) return Step.COND_COLOR;
        return hasPatch ? Step.COND_IMAGE : Step.COND_COLOR;
    }
}
