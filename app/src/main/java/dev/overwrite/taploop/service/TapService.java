package dev.overwrite.taploop.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.overwrite.taploop.capture.ScreenGrabber;
import dev.overwrite.taploop.model.Macro;
import dev.overwrite.taploop.model.MacroStore;
import dev.overwrite.taploop.model.Step;

public class TapService extends AccessibilityService {
    private static volatile TapService instance;

    public static TapService get() {
        return instance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private SharedPreferences prefs;

    private FloatingPanel panelUi;
    private TapIndicator indicator;

    private Recorder recorder;
    private Player player;
    private Macro active;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = getSystemService(WindowManager.class);
        prefs = getSharedPreferences("state", MODE_PRIVATE);
        active = MacroStore.load(this, prefs.getString("active", null));
        // can be called again on the same instance after a reconnect, the old windows are dead by then
        if (panelUi != null) panelUi.detach();
        if (indicator != null) indicator.detach();
        panelUi = new FloatingPanel(this, wm, prefs);
        indicator = new TapIndicator(this, wm);
        if (panelUi.wasShown()) main.post(this::showPanel);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (panelUi != null) main.post(panelUi::onScreenChanged);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
        stopAll();
    }

    @Override
    public void onDestroy() {
        stopAll();
        if (panelUi != null) panelUi.detach();
        if (indicator != null) indicator.detach();
        instance = null;
        super.onDestroy();
    }

    // ---- panel ----

    public boolean isPanelShown() {
        return panelUi != null && panelUi.isShown();
    }

    public void setActive(Macro m) {
        if (player != null) return;
        active = m;
        prefs.edit().putString("active", m == null ? null : m.id).apply();
        updatePanel();
    }

    public Macro getActive() {
        return active;
    }

    public void showPanel() {
        if (panelUi != null) panelUi.show();
    }

    public void hidePanel() {
        if (panelUi != null) panelUi.hide();
    }

    /** removes and re-adds the panel so it sits above the record layer */
    void bringPanelToFront() {
        if (panelUi != null) panelUi.bringToFront();
    }

    void setStatus(String s) {
        main.post(() -> {
            if (panelUi != null) panelUi.setStatusText(s);
        });
    }

    private void updatePanel() {
        if (panelUi != null) panelUi.update();
    }

    void onProgress(int loop, int loops, int step, int steps) {
        main.post(() -> {
            if (panelUi != null) panelUi.progress(loop, loops, step, steps);
        });
    }

    boolean isRecording() {
        return recorder != null;
    }

    boolean isSmartRecording() {
        return recorder != null && recorder.smart;
    }

    int recordCount() {
        return recorder == null ? 0 : recorder.count();
    }

    boolean isPlaying() {
        return player != null;
    }

    boolean isPaused() {
        return player != null && player.isPaused();
    }

    // ---- record ----

    void toggleRecord(boolean smart) {
        if (recorder != null) {
            List<Step> steps = recorder.stop();
            boolean wasSmart = recorder.smart;
            recorder = null;
            if (!steps.isEmpty()) {
                Macro m = new Macro();
                String time = new SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(new Date());
                m.name = (wasSmart ? "Smart " : "Recording ") + time;
                m.steps.addAll(steps);
                MacroStore.save(this, m);
                setActive(m);
                toast("Saved " + steps.size() + " steps");
            } else {
                toast("Nothing recorded");
            }
            updatePanel();
            return;
        }
        if (smart && ScreenGrabber.get() == null) {
            toast("Smart mode needs screen capture. Turn it on in the app first.");
            return;
        }
        recorder = new Recorder(this, wm, smart);
        recorder.start();
        bringPanelToFront();
        updatePanel();
    }

    void onRecorded() {
        main.post(this::updatePanel);
    }

    // ---- play ----

    void togglePlay() {
        if (player != null) {
            player.cancel();
            return;
        }
        if (active == null || active.steps.isEmpty()) {
            toast("Pick a macro in the app first");
            return;
        }
        boolean needsCapture = false;
        for (Step s : active.steps) if (s.cond != Step.COND_NONE) needsCapture = true;
        if (needsCapture && ScreenGrabber.get() == null) {
            toast("Screen capture is off, color/image checks will be skipped");
        }
        player = new Player(this, active, reason -> main.post(() -> {
            player = null;
            if (indicator != null) indicator.detach();
            if (panelUi != null) panelUi.restoreTouch();
            updatePanel();
            if (reason != null) toast(reason);
        }));
        if (panelUi != null) panelUi.closePopup();
        updatePanel();
        player.start();
    }

    void togglePause() {
        if (player == null) return;
        player.setPaused(!player.isPaused());
        updatePanel();
    }

    public void stopAll() {
        if (player != null) player.cancel();
        if (recorder != null) {
            recorder.stop();
            recorder = null;
        }
        main.post(this::updatePanel);
    }

    public boolean isBusy() {
        return player != null || recorder != null;
    }

    // ---- gestures ----

    /** player thread. sends one step's gesture, getting the panel out of the way if needed */
    void playGesture(int action, int x, int y, int x2, int y2, long duration) throws InterruptedException {
        boolean[] moved = {false};
        CountDownLatch ready = new CountDownLatch(1);
        main.post(() -> {
            if (panelUi != null) moved[0] = panelUi.passThrough(x, y, x2, y2);
            ready.countDown();
        });
        ready.await();
        try {
            // window flags take a moment to reach input dispatch
            if (moved[0]) Thread.sleep(80);
            if (indicator != null && FloatingPanel.indicatorsOn(this)) {
                main.post(() -> indicator.show(action, x, y, x2, y2, duration));
            }
            dispatchAndWait(buildGesture(action, x, y, x2, y2, duration), duration);
        } finally {
            if (moved[0]) main.post(() -> {
                if (panelUi != null) panelUi.restoreTouch();
            });
        }
    }

    long overlayClearAt() {
        return indicator == null ? 0 : indicator.clearAt();
    }

    static GestureDescription buildGesture(int action, int x, int y, int x2, int y2, long duration) {
        Path path = new Path();
        path.moveTo(Math.max(0, x), Math.max(0, y));
        if (action == Step.SWIPE) path.lineTo(Math.max(0, x2), Math.max(0, y2));
        long d = Math.max(1, Math.min(duration, GestureDescription.getMaxGestureDuration()));
        return new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, d))
                .build();
    }

    void dispatch(GestureDescription g, Runnable done) {
        boolean ok = dispatchGesture(g, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                done.run();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                done.run();
            }
        }, main);
        if (!ok) main.post(done);
    }

    /** called from the player thread, returns false if it timed out */
    boolean dispatchAndWait(GestureDescription g, long duration) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        main.post(() -> dispatch(g, latch::countDown));
        return latch.await(duration + 3000, TimeUnit.MILLISECONDS);
    }

    void toast(String s) {
        main.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }
}
