package dev.overwrite.taploop.service;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * Remembers that a run is in progress. If the process or the accessibility
 * service gets killed mid-run the flag is still there on the next connect,
 * so we can tell the user instead of silently doing nothing.
 */
final class RunGuard {
    private RunGuard() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("run", Context.MODE_PRIVATE);
    }

    // commit on purpose, the player thread calls this and it has to be on disk
    @SuppressLint("ApplySharedPref")
    static void begin(Context c, String name) {
        sp(c).edit().putString("running", name == null ? "" : name).commit();
    }

    static void end(Context c) {
        sp(c).edit().remove("running").apply();
    }

    /** name of the macro that got cut off last time, or null. clears the flag. */
    static String takeInterrupted(Context c) {
        String n = sp(c).getString("running", null);
        if (n != null) end(c);
        return n;
    }
}
