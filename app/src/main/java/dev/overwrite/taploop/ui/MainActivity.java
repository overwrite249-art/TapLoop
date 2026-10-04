package dev.overwrite.taploop.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.overwrite.taploop.Prefs;
import dev.overwrite.taploop.R;
import dev.overwrite.taploop.capture.CaptureService;
import dev.overwrite.taploop.model.Macro;
import dev.overwrite.taploop.model.MacroStore;
import dev.overwrite.taploop.service.TapService;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 10;
    private static final int REQ_NOTIF = 11;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Macro> macros = new ArrayList<>();
    private Adapter adapter;

    private TextView accState, capState;
    private Button accBtn, capBtn, panelBtn;
    private View accHint, empty;
    private ListView list;
    private int theme;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        theme = Prefs.theme(this);
        setTheme(theme);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        accState = findViewById(R.id.acc_state);
        capState = findViewById(R.id.cap_state);
        accBtn = findViewById(R.id.acc_btn);
        capBtn = findViewById(R.id.cap_btn);
        panelBtn = findViewById(R.id.panel_btn);
        accHint = findViewById(R.id.acc_hint);
        empty = findViewById(R.id.empty);
        list = findViewById(R.id.list);

        adapter = new Adapter();
        list.setAdapter(adapter);

        accBtn.setOnClickListener(v -> openAccessibility());
        capBtn.setOnClickListener(v -> toggleCapture());
        panelBtn.setOnClickListener(v -> togglePanel());
        findViewById(R.id.settings_btn).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        accHint.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null))));

        askNotifications();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // accent changed in settings
        if (theme != Prefs.theme(this)) {
            recreate();
            return;
        }
        refresh();
    }

    private void refresh() {
        boolean acc = accessibilityOn();
        accState.setText(acc ? "on" : "off, needed to tap for you");
        accState.setTextColor(getColor(acc ? R.color.ok : R.color.muted));
        accBtn.setText(acc ? "Settings" : "Enable");
        accHint.setVisibility(acc ? View.GONE : View.VISIBLE);

        boolean cap = CaptureService.isRunning();
        capState.setText(cap ? "running" : "needed for color / image checks");
        capState.setTextColor(getColor(cap ? R.color.ok : R.color.muted));
        capBtn.setText(cap ? "Stop" : "Start");

        TapService svc = TapService.get();
        panelBtn.setText(svc != null && svc.isPanelShown() ? "Hide floating panel" : "Show floating panel");

        macros.clear();
        macros.addAll(MacroStore.all(this));
        adapter.notifyDataSetChanged();
        boolean none = macros.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        list.setVisibility(none ? View.GONE : View.VISIBLE);
    }

    private boolean accessibilityOn() {
        if (TapService.get() != null) return true;
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName me = new ComponentName(this, TapService.class);
        TextUtils.SimpleStringSplitter split = new TextUtils.SimpleStringSplitter(':');
        split.setString(enabled);
        for (String s : split) {
            ComponentName c = ComponentName.unflattenFromString(s);
            if (me.equals(c)) return true;
        }
        return false;
    }

    private void openAccessibility() {
        if (accessibilityOn()) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Turn on accessibility")
                .setMessage("TapLoop uses an accessibility service to send taps and swipes "
                        + "and to draw the floating panel. It doesn't read text on screen.\n\n"
                        + "In the next screen open \"Installed apps\" (or \"Downloaded apps\") → "
                        + "TapLoop → turn it on.")
                .setPositiveButton("Open settings", (d, w) ->
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    private void toggleCapture() {
        if (CaptureService.isRunning()) {
            CaptureService.stop(this);
            handler.postDelayed(this::refresh, 300);
            return;
        }
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        Toast.makeText(this, "Pick \"Entire screen\" so coordinates line up", Toast.LENGTH_LONG).show();
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode == RESULT_OK && data != null) {
            CaptureService.start(this, resultCode, data);
            handler.postDelayed(this::refresh, 500);
        } else {
            Toast.makeText(this, "Screen capture not allowed", Toast.LENGTH_SHORT).show();
        }
    }

    private void togglePanel() {
        TapService svc = TapService.get();
        if (svc == null) {
            openAccessibility();
            return;
        }
        if (svc.isPanelShown()) {
            svc.stopAll();
            svc.hidePanel();
        } else {
            svc.showPanel();
        }
        refresh();
    }

    private void useMacro(Macro m) {
        TapService svc = TapService.get();
        if (svc == null) {
            openAccessibility();
            return;
        }
        if (svc.isBusy()) {
            Toast.makeText(this, "Stop the current run first", Toast.LENGTH_SHORT).show();
            return;
        }
        svc.setActive(m);
        svc.showPanel();
        Toast.makeText(this, "Loaded. Go to your app and press Play.", Toast.LENGTH_SHORT).show();
        refresh();
    }

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return macros.size();
        }

        @Override
        public Macro getItem(int i) {
            return macros.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            if (v == null) v = getLayoutInflater().inflate(R.layout.item_macro, parent, false);
            Macro m = getItem(i);
            TapService svc = TapService.get();
            boolean active = svc != null && svc.getActive() != null && m.id.equals(svc.getActive().id);
            ((TextView) v.findViewById(R.id.name)).setText(m.name);
            String loops = m.loops <= 0 ? "loops forever" : (m.loops == 1 ? "once" : m.loops + " loops");
            ((TextView) v.findViewById(R.id.info)).setText(String.format(Locale.US,
                    "%d steps · %.1f s · %s", m.steps.size(), m.totalTime() / 1000f, loops));
            v.findViewById(R.id.dot).setVisibility(active ? View.VISIBLE : View.INVISIBLE);
            v.findViewById(R.id.use).setOnClickListener(b -> useMacro(m));
            v.findViewById(R.id.edit).setOnClickListener(b -> startActivity(
                    new Intent(MainActivity.this, EditorActivity.class).putExtra("id", m.id)));
            return v;
        }
    }
}
