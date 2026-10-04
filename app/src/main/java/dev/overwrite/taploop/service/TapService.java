package dev.overwrite.taploop.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.PopupMenu;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.overwrite.taploop.Prefs;
import dev.overwrite.taploop.capture.ScreenGrabber;
import dev.overwrite.taploop.model.Macro;
import dev.overwrite.taploop.model.MacroStore;
import dev.overwrite.taploop.model.Step;
import dev.overwrite.taploop.trigger.Schedule;

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
    private StopTriggers stops;

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
        if (stops != null) stops.end();
        stops = new StopTriggers(this);
        Schedule.restore(this);
        String cut = RunGuard.takeInterrupted(this);
        if (cut != null) toast("\"" + cut + "\" was cut off last time, TapLoop got restarted");
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
    protected boolean onKeyEvent(KeyEvent event) {
        if (player != null && stops != null && stops.onKey(event)) return true;
        return super.onKeyEvent(event);
    }

    @Override
    public void onInterrupt() {
        stopAll();
    }

    @Override
    public void onDestroy() {
        if (player != null) player.cancel("Accessibility service was turned off, playback stopped");
        stopAll();
        if (stops != null) stops.end();
        closeOverlay();
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
    boolean bringPanelToFront() {
        return panelUi == null || panelUi.bringToFront();
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
                int[] size = ScreenFit.screenSize(this);
                m.screenW = size[0];
                m.screenH = size[1];
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
        recorder.mode = SmartMode.get(this);
        recorder.start();
        if (!bringPanelToFront()) {
            // without the panel there'd be no way to stop it
            recorder.stop();
            recorder = null;
            toast("Couldn't show the panel over the record layer, recording cancelled");
        }
        updatePanel();
    }

    void chooseSmartMode(View anchor) {
        int cur = SmartMode.get(this);
        try {
            PopupMenu menu = new PopupMenu(anchor.getContext(), anchor);
            for (int i = 0; i < SmartMode.NAMES.length; i++) {
                menu.getMenu().add(0, i, i, SmartMode.NAMES[i]).setCheckable(true).setChecked(i == cur);
            }
            menu.setOnMenuItemClickListener(item -> {
                setSmartMode(item.getItemId());
                return true;
            });
            menu.show();
        } catch (RuntimeException e) {
            // popup couldn't attach to the overlay, just cycle instead
            setSmartMode((cur + 1) % SmartMode.NAMES.length);
        }
    }

    private void setSmartMode(int mode) {
        SmartMode.set(this, mode);
        if (recorder != null) recorder.mode = mode;
        toast("Smart record waits for: " + SmartMode.NAMES[mode].toLowerCase(Locale.US));
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
        for (Step s : active.steps) {
            if (s.cond != Step.COND_NONE || s.action == Step.FIND_IMAGE) needsCapture = true;
        }
        if (needsCapture && ScreenGrabber.get() == null) {
            toast("Screen capture is off, color/image checks will be skipped");
        }
        player = new Player(this, active, reason -> main.post(() -> {
            player = null;
            if (stops != null) stops.end();
            if (indicator != null) indicator.detach();
            if (panelUi != null) {
                panelUi.restoreTouch();
                panelUi.setKeepScreenOn(false);
            }
            updatePanel();
            if (reason != null) toast(reason);
        }));
        if (panelUi != null) {
            panelUi.closePopup();
            panelUi.setKeepScreenOn(Prefs.keepScreenOn(this));
        }
        updatePanel();
        player.start();
        if (stops != null) stops.begin();
    }

    /** start a macro from a shortcut or the scheduler, returns why it didn't start or null */
    public String playMacro(Macro m) {
        if (isBusy()) return "Something is already running";
        if (m == null || m.steps.isEmpty()) return "That macro has no steps";
        setActive(m);
        showPanel();
        togglePlay();
        return null;
    }

    void stopPlay(String reason) {
        if (player != null) player.cancel(reason);
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

    // ---- picker / preview ----

    private ScreenOverlay overlay;

    /** crosshair picker, from = start point of a swipe to draw a line from, or null */
    public boolean showPicker(int x, int y, int[] from, boolean wantColor, PickerOverlay.Listener l) {
        closeOverlay();
        overlay = new PickerOverlay(this, x, y, from, wantColor, l);
        return overlay.show();
    }

    public boolean showPreview(List<Step> steps, PreviewOverlay.Listener l) {
        closeOverlay();
        overlay = new PreviewOverlay(this, steps, l);
        return overlay.show();
    }

    public void closeOverlay() {
        if (overlay != null) overlay.close(false);
        overlay = null;
    }

    // ---- gestures ----

    /** player thread. sends one step's gesture, getting the panel out of the way if needed */
    boolean playGesture(Step s, int dx, int dy) throws InterruptedException {
        int action = s.action;
        int x = s.x + dx, y = s.y + dy;
        int x2 = s.x2 + dx, y2 = s.y2 + dy;
        long duration = s.duration;
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
            return Gestures.play(this, s, dx, dy);
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

    /** returns false if the system refused the gesture */
    boolean dispatch(GestureDescription g, Runnable done) {
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
        return ok;
    }

    /** called from the player thread, returns false if it timed out or was refused */
    boolean dispatchAndWait(GestureDescription g, long duration) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] sent = {true};
        main.post(() -> sent[0] = dispatch(g, latch::countDown));
        return latch.await(duration + 3000, TimeUnit.MILLISECONDS) && sent[0];
    }

    void toast(String s) {
        // app context, so this still works while the service is going down
        Context app = getApplicationContext();
        main.post(() -> Toast.makeText(app, s, Toast.LENGTH_SHORT).show());
    }
}
