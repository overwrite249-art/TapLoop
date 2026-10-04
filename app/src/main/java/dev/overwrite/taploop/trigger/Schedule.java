package dev.overwrite.taploop.trigger;

import android.Manifest;
import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.util.Calendar;

import dev.overwrite.taploop.R;
import dev.overwrite.taploop.model.Macro;
import dev.overwrite.taploop.model.MacroStore;
import dev.overwrite.taploop.service.TapService;
import dev.overwrite.taploop.ui.MainActivity;

/**
 * Scheduled start. We don't ask for SCHEDULE_EXACT_ALARM, so on Android 12+ the alarm is
 * inexact and can be late while the phone dozes. To keep it on time while the phone is awake
 * (which is the only time a macro can run anyway) we also post a plain handler callback.
 * Whichever comes first wins, the other one sees the slot is used up.
 */
public final class Schedule {
    private static final String TAG = "Schedule";
    private static final String CHANNEL = "triggers";

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static Runnable pending;

    private Schedule() {}

    /** re-reads the settings and arms or cancels */
    public static void apply(Context c) {
        SharedPreferences p = TriggerPrefs.get(c);
        if (!p.getBoolean(TriggerPrefs.SCHED_ON, false)) {
            cancel(c);
            p.edit().putLong(TriggerPrefs.SCHED_AT, 0).apply();
            return;
        }
        long at = next(p, System.currentTimeMillis());
        p.edit().putLong(TriggerPrefs.SCHED_AT, at).apply();
        arm(c, at);
    }

    /** alarms don't survive a reboot, TapService calls this when it connects */
    public static void restore(Context c) {
        SharedPreferences p = TriggerPrefs.get(c);
        if (!p.getBoolean(TriggerPrefs.SCHED_ON, false)) return;
        long at = p.getLong(TriggerPrefs.SCHED_AT, 0);
        if (p.getBoolean(TriggerPrefs.SCHED_DAILY, false)) {
            apply(c);
        } else if (at > System.currentTimeMillis()) {
            arm(c, at);
        } else {
            // missed while the phone was off
            p.edit().putBoolean(TriggerPrefs.SCHED_ON, false).putLong(TriggerPrefs.SCHED_AT, 0).apply();
        }
    }

    public static long nextRun(Context c) {
        SharedPreferences p = TriggerPrefs.get(c);
        return p.getBoolean(TriggerPrefs.SCHED_ON, false) ? p.getLong(TriggerPrefs.SCHED_AT, 0) : 0;
    }

    private static long next(SharedPreferences p, long from) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(from);
        cal.set(Calendar.HOUR_OF_DAY, p.getInt(TriggerPrefs.SCHED_H, 8));
        cal.set(Calendar.MINUTE, p.getInt(TriggerPrefs.SCHED_M, 0));
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= from) cal.add(Calendar.DAY_OF_MONTH, 1);
        return cal.getTimeInMillis();
    }

    private static PendingIntent alarmIntent(Context c) {
        Intent i = new Intent(c, ScheduleReceiver.class).setAction(ScheduleReceiver.ACTION);
        return PendingIntent.getBroadcast(c, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void arm(Context c, long at) {
        Context app = c.getApplicationContext();
        AlarmManager am = app.getSystemService(AlarmManager.class);
        PendingIntent pi = alarmIntent(app);
        // exact only where it's free (pre 12) or the user granted it some other way
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
        if (pending != null) handler.removeCallbacks(pending);
        pending = () -> fire(app);
        handler.postDelayed(pending, Math.max(0, at - System.currentTimeMillis()));
    }

    private static void cancel(Context c) {
        Context app = c.getApplicationContext();
        app.getSystemService(AlarmManager.class).cancel(alarmIntent(app));
        if (pending != null) handler.removeCallbacks(pending);
        pending = null;
    }

    static void fire(Context c) {
        SharedPreferences p = TriggerPrefs.get(c);
        long at = p.getLong(TriggerPrefs.SCHED_AT, 0);
        long now = System.currentTimeMillis();
        if (!p.getBoolean(TriggerPrefs.SCHED_ON, false) || at == 0) return;
        if (now < at - 2000) {
            // handler ran early or a stale alarm, wait for the real time
            arm(c, at);
            return;
        }
        if (p.getBoolean(TriggerPrefs.SCHED_DAILY, false)) {
            long nxt = next(p, now + 60_000);
            p.edit().putLong(TriggerPrefs.SCHED_AT, nxt).apply();
            arm(c, nxt);
        } else {
            p.edit().putBoolean(TriggerPrefs.SCHED_ON, false).putLong(TriggerPrefs.SCHED_AT, 0).apply();
            cancel(c);
        }

        Macro m = MacroStore.load(c, p.getString(TriggerPrefs.SCHED_MACRO, null));
        TapService svc = TapService.get();
        String err;
        if (m == null) err = "the macro was deleted";
        else if (svc == null) err = "accessibility is off";
        else if (!c.getSystemService(PowerManager.class).isInteractive()) err = "the screen was off";
        else if (c.getSystemService(KeyguardManager.class).isKeyguardLocked()) err = "the phone was locked";
        else err = svc.playMacro(m);
        if (err != null) {
            Log.i(TAG, "scheduled run skipped: " + err);
            notifySkipped(c, m == null ? "Scheduled run" : m.name, err);
        }
    }

    private static void notifySkipped(Context c, String name, String why) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Triggers",
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
        PendingIntent open = PendingIntent.getActivity(c, 0,
                new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(name + " didn't start")
                .setContentText("Skipped because " + why)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        nm.notify(2001, n);
    }
}
