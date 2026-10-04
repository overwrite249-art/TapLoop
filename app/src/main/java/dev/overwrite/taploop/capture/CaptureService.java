package dev.overwrite.taploop.capture;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import dev.overwrite.taploop.Prefs;
import dev.overwrite.taploop.R;
import dev.overwrite.taploop.ui.MainActivity;

public class CaptureService extends Service {
    private static final String TAG = "CaptureService";
    private static final String CHANNEL = "capture";
    private static final String ACTION_STOP = "dev.overwrite.taploop.STOP_CAPTURE";
    private static final String EXTRA_CODE = "code";
    private static final String EXTRA_DATA = "data";

    private static volatile boolean running;
    private MediaProjection projection;

    public static boolean isRunning() {
        return running && ScreenGrabber.get() != null;
    }

    public static void start(Context c, int resultCode, Intent data) {
        Intent i = new Intent(c, CaptureService.class)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data);
        c.startForegroundService(i);
    }

    public static void stop(Context c) {
        c.startService(new Intent(c, CaptureService.class).setAction(ACTION_STOP));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }

        // Has to be in the foreground with the mediaProjection type *before*
        // getMediaProjection() is called, otherwise Android 14 throws.
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }

        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = getData(intent);
        if (data == null) {
            shutdown();
            return START_NOT_STICKY;
        }

        try {
            if (projection != null) {
                MediaProjection old = projection;
                projection = null;
                ScreenGrabber.stop();
                old.stop();
            }
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            MediaProjection mp = mpm.getMediaProjection(code, data);
            if (mp == null) {
                shutdown();
                return START_NOT_STICKY;
            }
            projection = mp;
            // Android 14 wants a callback registered before the virtual display
            mp.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    if (projection == mp) {
                        projection = null;
                        shutdown();
                    }
                }
            }, new Handler(Looper.getMainLooper()));

            Rect size = screenSize();
            ScreenGrabber.start(mp, size.width(), size.height(),
                    getResources().getDisplayMetrics().densityDpi, Prefs.captureScale(this));
            running = true;
        } catch (Exception e) {
            Log.e(TAG, "couldn't start capture", e);
            shutdown();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // rotation: keep the frame the same shape as the screen so coords still line up
        ScreenGrabber g = ScreenGrabber.get();
        if (g == null) return;
        Rect size = screenSize();
        g.resize(size.width(), size.height(), g.scale());
    }

    @SuppressWarnings("deprecation")
    private static Intent getData(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(EXTRA_DATA, Intent.class);
        }
        return intent.getParcelableExtra(EXTRA_DATA);
    }

    @SuppressWarnings("deprecation")
    private Rect screenSize() {
        WindowManager wm = getSystemService(WindowManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return wm.getMaximumWindowMetrics().getBounds();
        }
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new Rect(0, 0, dm.widthPixels, dm.heightPixels);
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Screen capture",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, CaptureService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("TapLoop is watching the screen")
                .setContentText("Used for color / image checks")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true)
                .build();
    }

    private void shutdown() {
        running = false;
        ScreenGrabber.stop();
        if (projection != null) {
            MediaProjection p = projection;
            projection = null;
            try { p.stop(); } catch (Exception ignored) {}
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        ScreenGrabber.stop();
        if (projection != null) {
            try { projection.stop(); } catch (Exception ignored) {}
            projection = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
