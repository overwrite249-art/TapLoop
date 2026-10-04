package dev.overwrite.taploop.service;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import dev.overwrite.taploop.trigger.TriggerPrefs;

/** optional ways to stop a running macro, armed for the length of one run */
class StopTriggers {
    private final TapService svc;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable limit;
    private BroadcastReceiver screenOff;
    private boolean keys;

    StopTriggers(TapService svc) {
        this.svc = svc;
        limit = () -> svc.stopPlay("Time limit reached");
    }

    void begin() {
        end();
        int mins = TriggerPrefs.timeLimit(svc);
        if (mins > 0) {
            // countdown doesn't eat into the limit
            main.postDelayed(limit, TriggerPrefs.countdown(svc) * 1000L + mins * 60_000L);
        }
        if (TriggerPrefs.screenOffStop(svc)) {
            screenOff = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    svc.stopPlay("Stopped, screen turned off");
                }
            };
            IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                svc.registerReceiver(screenOff, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                svc.registerReceiver(screenOff, f);
            }
        }
        if (TriggerPrefs.volumeStop(svc)) setKeyFilter(true);
    }

    void end() {
        main.removeCallbacks(limit);
        if (screenOff != null) {
            try { svc.unregisterReceiver(screenOff); } catch (Exception ignored) {}
            screenOff = null;
        }
        if (keys) setKeyFilter(false);
    }

    /** true if the key should be swallowed */
    boolean onKey(KeyEvent e) {
        if (!keys || e.getKeyCode() != KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            svc.stopPlay("Stopped with volume key");
        }
        return true;
    }

    // only ask for key events while playing so we never sit on the volume keys otherwise
    private void setKeyFilter(boolean on) {
        AccessibilityServiceInfo info = svc.getServiceInfo();
        if (info == null) return;
        if (on) info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        else info.flags &= ~AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        svc.setServiceInfo(info);
        keys = on;
    }
}
