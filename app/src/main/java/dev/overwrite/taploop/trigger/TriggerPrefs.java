package dev.overwrite.taploop.trigger;

import android.content.Context;
import android.content.SharedPreferences;

/** app wide start/stop trigger settings, everything off by default */
public final class TriggerPrefs {
    static final String COUNTDOWN = "countdown";
    static final String VOL_STOP = "vol_stop";
    static final String SCREEN_STOP = "screen_stop";
    static final String TIME_LIMIT = "time_limit";
    static final String DYN_SHORTCUTS = "dyn_shortcuts";
    static final String SCHED_ON = "sched_on";
    static final String SCHED_MACRO = "sched_macro";
    static final String SCHED_H = "sched_h";
    static final String SCHED_M = "sched_m";
    static final String SCHED_DAILY = "sched_daily";
    static final String SCHED_AT = "sched_at";

    private TriggerPrefs() {}

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("triggers", Context.MODE_PRIVATE);
    }

    /** seconds, 0..10 */
    public static int countdown(Context c) {
        return Math.max(0, Math.min(10, get(c).getInt(COUNTDOWN, 0)));
    }

    public static boolean volumeStop(Context c) {
        return get(c).getBoolean(VOL_STOP, false);
    }

    public static boolean screenOffStop(Context c) {
        return get(c).getBoolean(SCREEN_STOP, false);
    }

    /** minutes, 0 = no limit */
    public static int timeLimit(Context c) {
        return Math.max(0, get(c).getInt(TIME_LIMIT, 0));
    }
}
