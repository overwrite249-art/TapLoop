package dev.overwrite.taploop.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Switch;

import dev.overwrite.taploop.Prefs;
import dev.overwrite.taploop.R;
import dev.overwrite.taploop.capture.ScreenGrabber;

public class SettingsActivity extends Activity {
    private static final int[] SCALE_IDS = {R.id.scale_25, R.id.scale_50, R.id.scale_100};

    private EditText tol, timeout, radius, wait;
    private Spinner miss;
    private Switch smartImage, haptic, keepOn;
    private RadioGroup scale;
    private boolean resetting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(Prefs.theme(this));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        tol = findViewById(R.id.tol);
        timeout = findViewById(R.id.timeout);
        radius = findViewById(R.id.smart_radius);
        wait = findViewById(R.id.smart_wait);
        miss = findViewById(R.id.smart_miss);
        smartImage = findViewById(R.id.smart_image);
        haptic = findViewById(R.id.haptic);
        keepOn = findViewById(R.id.keep_on);
        scale = findViewById(R.id.scale);

        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"Tap anyway", "Skip this step", "Stop the macro"});
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        miss.setAdapter(a);

        load();
        buildAccents();

        findViewById(R.id.reset).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Reset all settings?")
                .setPositiveButton("Reset", (d, w) -> {
                    resetting = true;
                    Prefs.sp(this).edit().clear().apply();
                    applyScale();
                    recreate();
                })
                .setNegativeButton("Cancel", null)
                .show());
    }

    private void load() {
        tol.setText(String.valueOf(Prefs.tolerance(this)));
        timeout.setText(String.valueOf(Prefs.timeout(this)));
        radius.setText(String.valueOf(Prefs.smartRadius(this)));
        wait.setText(String.valueOf(Prefs.smartMaxWait(this) / 1000));
        miss.setSelection(Prefs.smartMiss(this));
        smartImage.setChecked(Prefs.smartImage(this));
        haptic.setChecked(Prefs.haptic(this));
        keepOn.setChecked(Prefs.keepScreenOn(this));
        float s = Prefs.captureScale(this);
        for (int i = 0; i < Prefs.SCALES.length; i++) {
            if (Prefs.SCALES[i] == s) scale.check(SCALE_IDS[i]);
        }
    }

    private void buildAccents() {
        LinearLayout row = findViewById(R.id.accents);
        int size = Math.round(36 * getResources().getDisplayMetrics().density);
        int gap = Math.round(14 * getResources().getDisplayMetrics().density);
        int current = Prefs.accent(this);
        for (int i = 0; i < Prefs.ACCENTS.length; i++) {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(Prefs.ACCENTS[i]);
            if (i == current) dot.setStroke(Math.max(2, size / 12), getColor(R.color.text));
            View v = new View(this);
            v.setBackground(dot);
            v.setContentDescription("Accent " + (i + 1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(gap);
            row.addView(v, lp);
            int pick = i;
            v.setOnClickListener(x -> {
                if (pick == Prefs.accent(this)) return;
                save();
                Prefs.sp(this).edit().putInt(Prefs.ACCENT, pick).apply();
                recreate();
            });
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!resetting) save();
    }

    private void save() {
        Prefs.sp(this).edit()
                .putInt(Prefs.TOL, num(tol, Prefs.tolerance(this)))
                .putLong(Prefs.TIMEOUT, num(timeout, Prefs.timeout(this)))
                .putInt(Prefs.SMART_RADIUS, num(radius, Prefs.smartRadius(this)))
                .putLong(Prefs.SMART_MAX_WAIT, num(wait, Prefs.smartMaxWait(this) / 1000) * 1000)
                .putInt(Prefs.SMART_MISS, miss.getSelectedItemPosition())
                .putBoolean(Prefs.SMART_IMAGE, smartImage.isChecked())
                .putBoolean(Prefs.HAPTIC, haptic.isChecked())
                .putBoolean(Prefs.KEEP_SCREEN_ON, keepOn.isChecked())
                .putFloat(Prefs.CAPTURE_SCALE, checkedScale())
                .apply();
        applyScale();
    }

    private float checkedScale() {
        int id = scale.getCheckedRadioButtonId();
        for (int i = 0; i < SCALE_IDS.length; i++) {
            if (SCALE_IDS[i] == id) return Prefs.SCALES[i];
        }
        return Prefs.captureScale(this);
    }

    // capture already running: switch it over now instead of on the next start
    private void applyScale() {
        ScreenGrabber g = ScreenGrabber.get();
        float s = Prefs.captureScale(this);
        if (g != null && g.scale() != s) g.setScale(s);
    }

    private static long num(EditText e, long def) {
        try {
            return Math.max(0, Long.parseLong(e.getText().toString().trim()));
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private static int num(EditText e, int def) {
        return (int) Math.min(Integer.MAX_VALUE, num(e, (long) def));
    }
}
