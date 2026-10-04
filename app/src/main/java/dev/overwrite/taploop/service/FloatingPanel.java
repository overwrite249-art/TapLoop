package dev.overwrite.taploop.service;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

import dev.overwrite.taploop.R;
import dev.overwrite.taploop.model.Macro;
import dev.overwrite.taploop.model.MacroStore;

/**
 * The floating controls. Either the full panel or a small bubble is on screen,
 * plus at most one popup (macro list or panel settings) next to it.
 */
class FloatingPanel {
    static final String PREFS = "panel";

    private final TapService svc;
    private final WindowManager wm;
    private final SharedPreferences state;
    private final SharedPreferences prefs;

    private boolean shown, collapsed;
    private Context ui;
    private int slop;

    /** panel size in percent of normal until the user picks one */
    static final int DEFAULT_SCALE = 80;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    private View panel;
    private WindowManager.LayoutParams panelLp;
    private TextView status;
    private ProgressBar progressBar;
    private Button recBtn, smartBtn, playBtn, pauseBtn, macrosBtn;
    private TextView smartMode;

    private BubbleView bubble;
    private WindowManager.LayoutParams bubbleLp;
    private ValueAnimator snapAnim;

    private View popup;
    private long popupClosedAt;

    private int pLoop, pLoops, pStep, pSteps;

    FloatingPanel(TapService svc, WindowManager wm, SharedPreferences state) {
        this.svc = svc;
        this.wm = wm;
        this.state = state;
        this.prefs = svc.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean blockTouches(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("block_touch", true);
    }

    static boolean indicatorsOn(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("indicators", false);
    }

    /** true if the panel was up last time the service was running */
    boolean wasShown() {
        return prefs.getBoolean("shown", false);
    }

    boolean isShown() {
        return shown;
    }

    void show() {
        if (shown) return;
        collapsed = prefs.getBoolean("collapsed", false);
        build();
        shown = attachCurrent();
        if (shown) prefs.edit().putBoolean("shown", true).apply();
        update();
    }

    /** user closed it */
    void hide() {
        prefs.edit().putBoolean("shown", false).apply();
        detach();
    }

    /** service going away, keep "shown" so it comes back on reconnect */
    void detach() {
        closePopup();
        if (snapAnim != null) snapAnim.cancel();
        remove(panel);
        remove(bubble);
        shown = false;
    }

    /** false if the panel couldn't be put back */
    boolean bringToFront() {
        if (!shown) return true;
        closePopup();
        boolean ok;
        if (collapsed) {
            remove(bubble);
            ok = add(bubble, bubbleLp);
        } else {
            remove(panel);
            ok = add(panel, panelLp);
        }
        if (!ok) shown = false;
        return ok;
    }

    // ---- building ----

    @SuppressLint({"InflateParams", "ClickableViewAccessibility"})
    private void build() {
        // size setting works by inflating with a scaled density
        float scale = prefs.getInt("scale", DEFAULT_SCALE) / 100f;
        Configuration conf = new Configuration(svc.getResources().getConfiguration());
        conf.densityDpi = Math.round(conf.densityDpi * scale);
        ui = new ContextThemeWrapper(svc.createConfigurationContext(conf), R.style.AppTheme);
        slop = ViewConfiguration.get(svc).getScaledTouchSlop();
        float alpha = prefs.getInt("opacity", 100) / 100f;

        panel = LayoutInflater.from(ui).inflate(R.layout.panel, null);
        status = panel.findViewById(R.id.status);
        progressBar = panel.findViewById(R.id.progress);
        recBtn = panel.findViewById(R.id.rec);
        smartBtn = panel.findViewById(R.id.smart);
        playBtn = panel.findViewById(R.id.play);
        pauseBtn = panel.findViewById(R.id.pause);
        macrosBtn = panel.findViewById(R.id.macros);

        recBtn.setOnClickListener(v -> svc.toggleRecord(false));
        smartBtn.setOnClickListener(v -> svc.toggleRecord(true));
        smartBtn.setOnLongClickListener(v -> {
            svc.chooseSmartMode(smartBtn);
            return true;
        });
        smartMode = panel.findViewById(R.id.smart_mode);
        smartMode.setOnClickListener(v -> svc.chooseSmartMode(smartBtn));
        playBtn.setOnClickListener(v -> svc.togglePlay());
        pauseBtn.setOnClickListener(v -> svc.togglePause());
        macrosBtn.setOnClickListener(v -> togglePopup(true));
        panel.findViewById(R.id.settings).setOnClickListener(v -> togglePopup(false));
        panel.findViewById(R.id.collapse).setOnClickListener(v -> collapse());
        panel.findViewById(R.id.close).setOnClickListener(v -> {
            svc.stopAll();
            hide();
        });
        panel.findViewById(R.id.handle).setOnTouchListener(new Drag(false));

        panelLp = params();
        panelLp.alpha = alpha;
        panelLp.x = state.getInt("px", 24);
        panelLp.y = state.getInt("py", 300);

        bubble = new BubbleView(ui);
        bubble.setOnTouchListener(new Drag(true));
        bubble.setOnClickListener(v -> expand());
        bubbleLp = params();
        bubbleLp.alpha = alpha;
        bubbleLp.x = prefs.getInt("bx", panelLp.x);
        bubbleLp.y = prefs.getInt("by", panelLp.y);
    }

    private static WindowManager.LayoutParams params() {
        // wrap_content + not focusable: anything outside our own views goes to the app below
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setFitInsetsTypes(0);
        }
        return lp;
    }

    private boolean attachCurrent() {
        if (collapsed) {
            clampBubble();
            return add(bubble, bubbleLp);
        }
        clampPanel();
        return add(panel, panelLp);
    }

    private void rebuild() {
        if (!shown) return;
        closePopup();
        remove(panel);
        remove(bubble);
        build();
        shown = attachCurrent();
        update();
    }

    // ---- collapse / expand ----

    private void collapse() {
        if (collapsed) return;
        closePopup();
        Rect s = screen();
        int[] size = measure(bubble);
        int[] psize = measure(panel);
        // land the bubble on whichever side the panel was closer to
        boolean right = panelLp.x + psize[0] / 2 > s.width() / 2;
        bubbleLp.x = right ? s.width() - size[0] : 0;
        bubbleLp.y = panelLp.y;
        collapsed = true;
        remove(panel);
        clampBubble();
        add(bubble, bubbleLp);
        saveBubble();
        prefs.edit().putBoolean("collapsed", true).apply();
    }

    private void expand() {
        if (!collapsed) return;
        if (snapAnim != null) snapAnim.cancel();
        Rect s = screen();
        int[] size = measure(panel);
        int[] bsize = measure(bubble);
        boolean right = bubbleLp.x + bsize[0] / 2 > s.width() / 2;
        panelLp.x = right ? s.width() - size[0] : 0;
        panelLp.y = bubbleLp.y;
        collapsed = false;
        remove(bubble);
        clampPanel();
        add(panel, panelLp);
        savePanel();
        prefs.edit().putBoolean("collapsed", false).apply();
        update();
    }

    private void snapBubble() {
        Rect s = screen();
        int w = measure(bubble)[0];
        int target = bubbleLp.x + w / 2 > s.width() / 2 ? s.width() - w : 0;
        bubbleLp.y = clamp(bubbleLp.y, 0, s.height() - measure(bubble)[1]);
        if (snapAnim != null) snapAnim.cancel();
        snapAnim = ValueAnimator.ofInt(bubbleLp.x, target);
        snapAnim.setDuration(180);
        snapAnim.setInterpolator(new DecelerateInterpolator());
        snapAnim.addUpdateListener(a -> {
            bubbleLp.x = (int) a.getAnimatedValue();
            relayout(bubble, bubbleLp);
        });
        snapAnim.start();
        bubbleLp.x = target;
        saveBubble();
    }

    /** rotation or resolution change, keep things on screen */
    void onScreenChanged() {
        if (!shown) return;
        closePopup();
        if (collapsed) {
            Rect s = screen();
            int w = measure(bubble)[0];
            // stay on the same side
            if (bubbleLp.x > 0) bubbleLp.x = s.width() - w;
            clampBubble();
            relayout(bubble, bubbleLp);
        } else {
            clampPanel();
            relayout(panel, panelLp);
        }
    }

    private void clampBubble() {
        Rect s = screen();
        int[] size = measure(bubble);
        bubbleLp.x = clamp(bubbleLp.x, 0, s.width() - size[0]);
        bubbleLp.y = clamp(bubbleLp.y, 0, s.height() - size[1]);
    }

    private void clampPanel() {
        Rect s = screen();
        int[] size = measure(panel);
        panelLp.x = clamp(panelLp.x, 0, s.width() - size[0]);
        panelLp.y = clamp(panelLp.y, 0, s.height() - size[1]);
    }

    private void saveBubble() {
        prefs.edit().putInt("bx", bubbleLp.x).putInt("by", bubbleLp.y).apply();
    }

    private void savePanel() {
        state.edit().putInt("px", panelLp.x).putInt("py", panelLp.y).apply();
    }

    private class Drag implements View.OnTouchListener {
        private final boolean isBubble;
        private float sx, sy;
        private int ox, oy;
        private boolean dragging;

        Drag(boolean isBubble) {
            this.isBubble = isBubble;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            WindowManager.LayoutParams lp = isBubble ? bubbleLp : panelLp;
            View win = isBubble ? bubble : panel;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (snapAnim != null) snapAnim.cancel();
                    sx = e.getRawX();
                    sy = e.getRawY();
                    ox = lp.x;
                    oy = lp.y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                    if (!dragging && Math.hypot(dx, dy) > slop) {
                        dragging = true;
                        closePopup();
                    }
                    if (dragging) {
                        lp.x = ox + (int) dx;
                        lp.y = oy + (int) dy;
                        relayout(win, lp);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        if (isBubble) {
                            snapBubble();
                        } else {
                            clampPanel();
                            relayout(panel, panelLp);
                            savePanel();
                        }
                    } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                        v.performClick();
                    }
                    return true;
                default:
                    return false;
            }
        }
    }

    // ---- state ----

    void update() {
        if (panel == null) return;
        boolean rec = svc.isRecording();
        boolean smart = svc.isSmartRecording();
        boolean playing = svc.isPlaying();
        boolean paused = svc.isPaused();
        int red = svc.getColor(R.color.rec);

        recBtn.setText(rec && !smart ? "Stop" : "Rec");
        smartBtn.setText(rec && smart ? "Stop" : "Smart");
        recBtn.setTextColor(rec && !smart ? red : Color.WHITE);
        smartBtn.setTextColor(rec && smart ? red : Color.WHITE);
        recBtn.setEnabled(!playing && (!rec || !smart));
        smartBtn.setEnabled(!playing && (!rec || smart));
        playBtn.setEnabled(!rec);
        playBtn.setText(playing ? "Stop" : "Play");
        playBtn.setTextColor(playing ? red : Color.WHITE);
        pauseBtn.setVisibility(playing ? View.VISIBLE : View.GONE);
        pauseBtn.setText(paused ? "Resume" : "Pause");
        pauseBtn.setTextColor(paused ? svc.getColor(R.color.accent) : Color.WHITE);
        macrosBtn.setEnabled(!playing && !rec);
        progressBar.setVisibility(playing ? View.VISIBLE : View.GONE);
        smartMode.setText(SmartMode.shortName(SmartMode.get(svc)));

        Macro active = svc.getActive();
        if (rec) {
            status.setText("rec " + svc.recordCount());
            bubble.set(BubbleView.REC, String.valueOf(svc.recordCount()), 0);
        } else if (playing) {
            showProgress();
        } else {
            pSteps = 0;
            status.setText(active == null ? "no macro" : active.name);
            bubble.set(BubbleView.IDLE, "", 0);
        }
        if (popup != null && Boolean.TRUE.equals(popup.getTag()) && (playing || rec)) closePopup();
        // panel height changes with the pause button, keep it on screen
        if (shown && !collapsed) {
            clampPanel();
            relayout(panel, panelLp);
        }
    }

    void setStatusText(String s) {
        if (status != null) status.setText(s);
    }

    void progress(int loop, int loops, int step, int steps) {
        pLoop = loop;
        pLoops = loops;
        pStep = step;
        pSteps = steps;
        if (svc.isPlaying()) showProgress();
    }

    private void showProgress() {
        boolean paused = svc.isPaused();
        if (pSteps <= 0) {
            status.setText(paused ? "paused" : "starting");
            progressBar.setProgress(0);
            bubble.set(paused ? BubbleView.PAUSE : BubbleView.PLAY, paused ? "II" : "", 0);
            return;
        }
        String total = pLoops <= 0 ? "∞" : String.valueOf(pLoops);
        String top = paused ? "paused" : "loop " + pLoop + "/" + total;
        status.setText(top + "\n" + pStep + "/" + pSteps);
        float frac;
        if (pLoops <= 0) frac = pStep / (float) pSteps;
        else frac = ((pLoop - 1) * pSteps + pStep) / (float) (pLoops * pSteps);
        progressBar.setProgress(Math.round(frac * 1000));
        bubble.set(paused ? BubbleView.PAUSE : BubbleView.PLAY,
                paused ? "II" : String.valueOf(pStep), frac);
    }

    // ---- replay pass-through ----

    /**
     * Makes our windows untouchable if a replayed gesture would land on them.
     * Returns true if anything changed, caller should give it a moment to apply.
     */
    boolean passThrough(int x, int y, int x2, int y2) {
        if (popup != null && hits(popup, x, y, x2, y2)) closePopup();
        if (!shown) return false;
        View v = collapsed ? bubble : panel;
        WindowManager.LayoutParams lp = collapsed ? bubbleLp : panelLp;
        if (!hits(v, x, y, x2, y2)) return false;
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        relayout(v, lp);
        return true;
    }

    void restoreTouch() {
        for (WindowManager.LayoutParams lp : new WindowManager.LayoutParams[]{panelLp, bubbleLp}) {
            if (lp == null) continue;
            if ((lp.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0) continue;
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            relayout(lp == panelLp ? panel : bubble, lp);
        }
    }

    private static boolean hits(View v, int x, int y, int x2, int y2) {
        if (v == null || !v.isAttachedToWindow()) return false;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        Rect r = new Rect(loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight());
        // walk along swipes too
        for (int i = 0; i <= 10; i++) {
            int px = x + (x2 - x) * i / 10;
            int py = y + (y2 - y) * i / 10;
            if (r.contains(px, py)) return true;
            if (x == x2 && y == y2) break;
        }
        return false;
    }

    // ---- popups ----

    /** while playing, if the user wants the screen to stay awake */
    void setKeepScreenOn(boolean on) {
        if (panel != null) panel.setKeepScreenOn(on);
        if (bubble != null) bubble.setKeepScreenOn(on);
    }

    void closePopup() {
        if (popup == null) return;
        remove(popup);
        popup = null;
        popupClosedAt = SystemClock.uptimeMillis();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void togglePopup(boolean macros) {
        if (popup != null) {
            Object kind = popup.getTag();
            closePopup();
            if (kind.equals(macros)) return;
        } else if (SystemClock.uptimeMillis() - popupClosedAt < 300) {
            // the outside touch on our own button just closed it
            return;
        }
        View content = macros ? buildMacroList() : buildSettings();
        content.setTag(macros);
        content.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) closePopup();
            return false;
        });
        WindowManager.LayoutParams lp = params();
        lp.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;

        Rect s = screen();
        int[] size = measure(content);
        int[] psize = measure(panel);
        int gap = Math.round(6 * ui.getResources().getDisplayMetrics().density);
        if (panelLp.x + psize[0] + gap + size[0] <= s.width()) lp.x = panelLp.x + psize[0] + gap;
        else lp.x = Math.max(0, panelLp.x - gap - size[0]);
        lp.y = clamp(panelLp.y, 0, s.height() - size[1]);
        if (add(content, lp)) popup = content;
    }

    private View buildMacroList() {
        float dp = ui.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(ui);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.panel_bg);
        int pad = Math.round(8 * dp);
        box.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(ui);
        title.setText("Macros");
        title.setTextColor(svc.getColor(R.color.muted));
        title.setTextSize(12);
        title.setPadding(Math.round(4 * dp), 0, 0, Math.round(6 * dp));
        box.addView(title);

        List<Macro> all = MacroStore.all(svc);
        Macro active = svc.getActive();
        int rowH = Math.round(40 * dp);
        int width = Math.round(200 * dp);
        LinearLayout rows = new LinearLayout(ui);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (Macro m : all) {
            TextView row = new TextView(ui);
            boolean on = active != null && m.id.equals(active.id);
            row.setText(m.name);
            row.setSingleLine(true);
            row.setEllipsize(TextUtils.TruncateAt.END);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setTextSize(13);
            row.setTextColor(svc.getColor(on ? R.color.accent : R.color.text));
            row.setBackgroundResource(R.drawable.btn);
            row.setPadding(Math.round(12 * dp), 0, Math.round(12 * dp), 0);
            row.setOnClickListener(v -> {
                closePopup();
                if (svc.isBusy()) return;
                svc.setActive(m);
                svc.toast("Loaded " + m.name);
            });
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(width, rowH);
            rlp.bottomMargin = Math.round(4 * dp);
            rows.addView(row, rlp);
        }
        if (all.isEmpty()) {
            TextView none = new TextView(ui);
            none.setText("No macros yet");
            none.setTextColor(svc.getColor(R.color.muted));
            none.setTextSize(13);
            none.setPadding(Math.round(4 * dp), Math.round(4 * dp), 0, Math.round(4 * dp));
            rows.addView(none, new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        ScrollView scroll = new ScrollView(ui);
        scroll.addView(rows);
        int maxH = Math.min(Math.round(6.5f * (rowH + 4 * dp)), screen().height() * 2 / 3);
        int h = all.size() * (rowH + Math.round(4 * dp));
        box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                all.isEmpty() || h <= maxH ? ViewGroup.LayoutParams.WRAP_CONTENT : maxH));
        return box;
    }

    @SuppressLint("InflateParams")
    private View buildSettings() {
        View v = LayoutInflater.from(ui).inflate(R.layout.panel_settings, null);
        SeekBar size = v.findViewById(R.id.size);
        SeekBar opacity = v.findViewById(R.id.opacity);
        TextView sizeLabel = v.findViewById(R.id.size_label);
        TextView opacityLabel = v.findViewById(R.id.opacity_label);
        CompoundButton ind = v.findViewById(R.id.indicators);

        buildMacroSettings(v.findViewById(R.id.macro_box));

        // size 50..150 %, opacity 30..100 %
        size.setProgress((prefs.getInt("scale", DEFAULT_SCALE) - 50) / 10);
        opacity.setProgress((prefs.getInt("opacity", 100) - 30) / 5);
        sizeLabel.setText(String.format(Locale.US, "Size  %d%%", prefs.getInt("scale", DEFAULT_SCALE)));
        opacityLabel.setText(String.format(Locale.US, "Opacity  %d%%", prefs.getInt("opacity", 100)));
        ind.setChecked(prefs.getBoolean("indicators", false));

        size.setOnSeekBarChangeListener(new SeekListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                sizeLabel.setText(String.format(Locale.US, "Size  %d%%", 50 + p * 10));
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                int val = 50 + s.getProgress() * 10;
                if (val == prefs.getInt("scale", DEFAULT_SCALE)) return;
                prefs.edit().putInt("scale", val).apply();
                // re-inflating closes this popup, which is fine
                rebuild();
            }
        });
        opacity.setOnSeekBarChangeListener(new SeekListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                int val = 30 + p * 5;
                opacityLabel.setText(String.format(Locale.US, "Opacity  %d%%", val));
                if (!user) return;
                panelLp.alpha = bubbleLp.alpha = val / 100f;
                relayout(panel, panelLp);
                relayout(bubble, bubbleLp);
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                prefs.edit().putInt("opacity", 30 + s.getProgress() * 5).apply();
            }
        });
        ind.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("indicators", on).apply());
        CompoundButton block = v.findViewById(R.id.block_touch);
        block.setChecked(prefs.getBoolean("block_touch", true));
        block.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("block_touch", on).apply());
        v.findViewById(R.id.done).setOnClickListener(b -> closePopup());
        return v;
    }

    /** loops / speed / pauses of the loaded macro, with -/+ buttons so no keyboard is needed */
    private void buildMacroSettings(LinearLayout box) {
        Macro m = svc.getActive();
        if (m == null) {
            box.setVisibility(View.GONE);
            return;
        }
        TextView title = new TextView(ui);
        title.setText(m.name);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextColor(svc.getColor(R.color.text));
        title.setTextSize(15);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(title);
        boolean busy = svc.isBusy();
        if (busy) {
            TextView note = new TextView(ui);
            note.setText("Stop it to change these");
            note.setTextColor(svc.getColor(R.color.muted));
            note.setTextSize(11);
            box.addView(note);
        }
        stepper(box, "Loops", busy, () -> m.loops, v -> m.loops = v, 1, 10, 0, 1_000_000,
                v -> v == 0 ? "∞" : String.valueOf(v), m);
        stepper(box, "Speed", busy, () -> m.speed, v -> m.speed = v, 10, 50, 10, 1000,
                v -> v + "%", m);
        stepper(box, "Pause between loops", busy, () -> (int) m.loopDelay, v -> m.loopDelay = v,
                100, 1000, 0, 86_400_000, FloatingPanel::ms, m);
        stepper(box, "Random extra pause", busy, () -> (int) m.loopJitter, v -> m.loopJitter = v,
                100, 1000, 0, 86_400_000, FloatingPanel::ms, m);
        stepper(box, "Stop after", busy, () -> m.maxMinutes, v -> m.maxMinutes = v, 1, 10, 0, 100_000,
                v -> v == 0 ? "never" : v + " min", m);
    }

    private static String ms(int v) {
        if (v == 0) return "0";
        if (v < 1000) return v + " ms";
        return v % 1000 == 0 ? v / 1000 + " s" : String.format(Locale.US, "%.1f s", v / 1000f);
    }

    private interface IntGet { int get(); }
    private interface IntSet { void set(int v); }
    private interface IntFmt { String fmt(int v); }

    /** tap -/+ for small steps, hold for big ones */
    private void stepper(LinearLayout box, String label, boolean busy, IntGet get, IntSet set,
                         int small, int big, int min, int max, IntFmt fmt, Macro m) {
        float dp = ui.getResources().getDisplayMetrics().density;
        TextView l = new TextView(ui);
        l.setText(label);
        l.setTextColor(svc.getColor(R.color.muted));
        l.setTextSize(11);
        l.setPadding(0, Math.round(6 * dp), 0, Math.round(2 * dp));
        box.addView(l);

        LinearLayout row = new LinearLayout(ui);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView val = new TextView(ui);
        val.setGravity(Gravity.CENTER);
        val.setTextColor(svc.getColor(R.color.text));
        val.setTextSize(14);
        val.setText(fmt.fmt(get.get()));
        int h = Math.round(32 * dp);
        Button minus = smallBtn("−"), plus = smallBtn("+");
        row.addView(minus, new LinearLayout.LayoutParams(Math.round(44 * dp), h));
        row.addView(val, new LinearLayout.LayoutParams(0, h, 1));
        row.addView(plus, new LinearLayout.LayoutParams(Math.round(44 * dp), h));
        box.addView(row);
        if (busy) {
            minus.setEnabled(false);
            plus.setEnabled(false);
            return;
        }
        Runnable[] save = new Runnable[1];
        save[0] = () -> {
            MacroStore.save(svc, m);
            svc.setActive(m);
        };
        java.util.function.IntConsumer by = d -> {
            int v = (int) Math.max(min, Math.min(max, (long) get.get() + d));
            if (v == get.get()) return;
            set.set(v);
            val.setText(fmt.fmt(v));
            // write once things settle instead of on every tap
            main.removeCallbacks(save[0]);
            main.postDelayed(save[0], 400);
        };
        minus.setOnClickListener(b -> by.accept(-small));
        plus.setOnClickListener(b -> by.accept(small));
        minus.setOnLongClickListener(b -> {
            by.accept(-big);
            return true;
        });
        plus.setOnLongClickListener(b -> {
            by.accept(big);
            return true;
        });
    }

    private Button smallBtn(String text) {
        Button b = new Button(ui);
        b.setText(text);
        b.setTextSize(16);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setBackgroundResource(R.drawable.btn);
        b.setTextColor(svc.getColor(R.color.text));
        return b;
    }

    private abstract static class SeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar s) {
        }
    }

    // ---- window helpers ----

    private boolean add(View v, WindowManager.LayoutParams lp) {
        if (v == null || v.isAttachedToWindow()) return v != null;
        try {
            wm.addView(v, lp);
            return true;
        } catch (Exception e) {
            // service token went away under us
            return false;
        }
    }

    private void remove(View v) {
        if (v == null || !v.isAttachedToWindow()) return;
        // immediate, removeView() detaches later and a re-add right after would be skipped
        try { wm.removeViewImmediate(v); } catch (Exception ignored) {}
    }

    private void relayout(View v, WindowManager.LayoutParams lp) {
        if (v == null || !v.isAttachedToWindow()) return;
        try { wm.updateViewLayout(v, lp); } catch (Exception ignored) {}
    }

    private static int[] measure(View v) {
        if (v.isAttachedToWindow() && v.getWidth() > 0) return new int[]{v.getWidth(), v.getHeight()};
        int spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        v.measure(spec, spec);
        return new int[]{v.getMeasuredWidth(), v.getMeasuredHeight()};
    }

    @SuppressWarnings("deprecation")
    private Rect screen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return wm.getCurrentWindowMetrics().getBounds();
        }
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new Rect(0, 0, dm.widthPixels, dm.heightPixels);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(v, Math.max(lo, hi)));
    }
}
