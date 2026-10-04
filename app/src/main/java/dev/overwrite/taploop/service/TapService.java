package dev.overwrite.taploop.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.overwrite.taploop.R;
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

    private View panel;
    private WindowManager.LayoutParams panelLp;
    private TextView status;
    private Button recBtn, smartBtn, playBtn;

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
        hidePanel();
        instance = null;
        super.onDestroy();
    }

    // ---- panel ----

    public boolean isPanelShown() {
        return panel != null;
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

    @SuppressLint({"InflateParams", "ClickableViewAccessibility"})
    public void showPanel() {
        if (panel != null) return;
        Context themed = new ContextThemeWrapper(this, R.style.AppTheme);
        panel = LayoutInflater.from(themed).inflate(R.layout.panel, null);
        status = panel.findViewById(R.id.status);
        recBtn = panel.findViewById(R.id.rec);
        smartBtn = panel.findViewById(R.id.smart);
        playBtn = panel.findViewById(R.id.play);

        recBtn.setOnClickListener(v -> toggleRecord(false));
        smartBtn.setOnClickListener(v -> toggleRecord(true));
        playBtn.setOnClickListener(v -> togglePlay());
        panel.findViewById(R.id.close).setOnClickListener(v -> {
            stopAll();
            hidePanel();
        });

        panelLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.START;
        panelLp.x = prefs.getInt("px", 24);
        panelLp.y = prefs.getInt("py", 300);

        panel.findViewById(R.id.handle).setOnTouchListener(new View.OnTouchListener() {
            float sx, sy;
            int ox, oy;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX(); sy = e.getRawY();
                        ox = panelLp.x; oy = panelLp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        panelLp.x = ox + (int) (e.getRawX() - sx);
                        panelLp.y = oy + (int) (e.getRawY() - sy);
                        wm.updateViewLayout(panel, panelLp);
                        return true;
                    case MotionEvent.ACTION_UP:
                        prefs.edit().putInt("px", panelLp.x).putInt("py", panelLp.y).apply();
                        return true;
                }
                return false;
            }
        });

        wm.addView(panel, panelLp);
        updatePanel();
    }

    public void hidePanel() {
        if (panel == null) return;
        try { wm.removeView(panel); } catch (Exception ignored) {}
        panel = null;
    }

    /** removes and re-adds the panel so it sits above the record layer */
    void bringPanelToFront() {
        if (panel == null) return;
        try {
            wm.removeView(panel);
            wm.addView(panel, panelLp);
        } catch (Exception ignored) {}
    }

    void setStatus(String s) {
        main.post(() -> {
            if (status != null) status.setText(s);
        });
    }

    private void updatePanel() {
        if (panel == null) return;
        boolean rec = recorder != null;
        boolean playing = player != null;
        recBtn.setText(rec && !recorder.smart ? "Stop" : "Rec");
        smartBtn.setText(rec && recorder.smart ? "Stop" : "Smart");
        recBtn.setTextColor(rec && !recorder.smart ? 0xFFFF5A5F : Color.WHITE);
        smartBtn.setTextColor(rec && recorder.smart ? 0xFFFF5A5F : Color.WHITE);
        recBtn.setEnabled(!playing && (!rec || !recorder.smart));
        smartBtn.setEnabled(!playing && (!rec || recorder.smart));
        playBtn.setEnabled(!rec);
        playBtn.setText(playing ? "Stop" : "Play");
        playBtn.setTextColor(playing ? 0xFFFF5A5F : Color.WHITE);
        if (rec) status.setText("rec " + recorder.count());
        else if (!playing) status.setText(active == null ? "no macro" : active.name);
    }

    // ---- record ----

    private void toggleRecord(boolean smart) {
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

    private void togglePlay() {
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
            updatePanel();
            if (reason != null) toast(reason);
        }));
        updatePanel();
        player.start();
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
