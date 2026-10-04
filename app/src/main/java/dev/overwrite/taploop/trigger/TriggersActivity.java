package dev.overwrite.taploop.trigger;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.app.TimePickerDialog;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import dev.overwrite.taploop.R;
import dev.overwrite.taploop.model.Macro;
import dev.overwrite.taploop.model.MacroStore;
import dev.overwrite.taploop.service.TapService;

public class TriggersActivity extends Activity {
    private SharedPreferences prefs;
    private List<Macro> macros;

    private TextView cdLabel, schedInfo;
    private Switch schedOn, limitOn;
    private Button schedMacro, schedTime;
    private CheckBox schedDaily;
    private EditText limitMin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_triggers);
        prefs = TriggerPrefs.get(this);

        cdLabel = findViewById(R.id.cd_label);
        SeekBar cdBar = findViewById(R.id.cd_bar);
        cdBar.setProgress(TriggerPrefs.countdown(this));
        showCountdown(cdBar.getProgress());
        cdBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean user) {
                showCountdown(v);
                if (user) prefs.edit().putInt(TriggerPrefs.COUNTDOWN, v).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {}
        });

        schedOn = findViewById(R.id.sched_on);
        schedMacro = findViewById(R.id.sched_macro);
        schedTime = findViewById(R.id.sched_time);
        schedDaily = findViewById(R.id.sched_daily);
        schedInfo = findViewById(R.id.sched_info);
        if (!prefs.contains(TriggerPrefs.SCHED_H)) {
            Calendar now = Calendar.getInstance();
            now.add(Calendar.MINUTE, 5);
            prefs.edit().putInt(TriggerPrefs.SCHED_H, now.get(Calendar.HOUR_OF_DAY))
                    .putInt(TriggerPrefs.SCHED_M, now.get(Calendar.MINUTE)).apply();
        }
        schedMacro.setOnClickListener(v -> pickMacro(m -> {
            prefs.edit().putString(TriggerPrefs.SCHED_MACRO, m.id).apply();
            scheduleChanged();
        }));
        schedTime.setOnClickListener(v -> new TimePickerDialog(this, (tp, h, min) -> {
            prefs.edit().putInt(TriggerPrefs.SCHED_H, h).putInt(TriggerPrefs.SCHED_M, min).apply();
            scheduleChanged();
        }, prefs.getInt(TriggerPrefs.SCHED_H, 8), prefs.getInt(TriggerPrefs.SCHED_M, 0),
                DateFormat.is24HourFormat(this)).show());
        schedDaily.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(TriggerPrefs.SCHED_DAILY, on).apply();
            scheduleChanged();
        });
        schedOn.setOnCheckedChangeListener((b, on) -> {
            if (on && schedMacroOrNull() == null) {
                Toast.makeText(this, "Pick a macro first", Toast.LENGTH_SHORT).show();
                b.setChecked(false);
                return;
            }
            prefs.edit().putBoolean(TriggerPrefs.SCHED_ON, on).apply();
            scheduleChanged();
        });

        bindSwitch(R.id.dyn_shortcuts, TriggerPrefs.DYN_SHORTCUTS, () -> Shortcuts.update(this));
        bindSwitch(R.id.vol_stop, TriggerPrefs.VOL_STOP, null);
        bindSwitch(R.id.screen_stop, TriggerPrefs.SCREEN_STOP, null);

        findViewById(R.id.pin).setOnClickListener(v -> pickMacro(m -> {
            if (!Shortcuts.pin(this, m)) {
                Toast.makeText(this, "Your launcher doesn't support pinned shortcuts", Toast.LENGTH_SHORT).show();
            }
        }));

        View addTile = findViewById(R.id.add_tile);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addTile.setOnClickListener(v -> requestTile());
        } else {
            addTile.setVisibility(View.GONE);
        }

        limitOn = findViewById(R.id.limit_on);
        limitMin = findViewById(R.id.limit_min);
        int lim = TriggerPrefs.timeLimit(this);
        limitOn.setChecked(lim > 0);
        limitMin.setText(String.valueOf(lim > 0 ? lim : prefs.getInt("time_limit_last", 30)));
        limitMin.setEnabled(lim > 0);
        limitOn.setOnCheckedChangeListener((b, on) -> {
            limitMin.setEnabled(on);
            saveLimit();
        });
        limitMin.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                saveLimit();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        macros = MacroStore.all(this);
        // a one-off run may have fired while we were away
        schedOn.setChecked(prefs.getBoolean(TriggerPrefs.SCHED_ON, false));
        schedDaily.setChecked(prefs.getBoolean(TriggerPrefs.SCHED_DAILY, false));
        showSchedule();
    }

    private void showCountdown(int s) {
        cdLabel.setText(s == 0 ? "Countdown before play: off" : "Countdown before play: " + s + " s");
    }

    private interface OnMacro {
        void picked(Macro m);
    }

    private void pickMacro(OnMacro cb) {
        if (macros.isEmpty()) {
            Toast.makeText(this, "No macros yet", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[macros.size()];
        for (int i = 0; i < names.length; i++) names[i] = macros.get(i).name;
        new AlertDialog.Builder(this)
                .setTitle("Pick a macro")
                .setItems(names, (d, w) -> cb.picked(macros.get(w)))
                .show();
    }

    private Macro schedMacroOrNull() {
        String id = prefs.getString(TriggerPrefs.SCHED_MACRO, null);
        if (id == null) {
            // default to whatever is loaded on the panel
            TapService svc = TapService.get();
            Macro a = svc == null ? null : svc.getActive();
            if (a == null && !macros.isEmpty()) a = macros.get(0);
            if (a == null) return null;
            prefs.edit().putString(TriggerPrefs.SCHED_MACRO, a.id).apply();
            return a;
        }
        for (Macro m : macros) if (m.id.equals(id)) return m;
        return null;
    }

    private void scheduleChanged() {
        Schedule.apply(this);
        showSchedule();
    }

    private void showSchedule() {
        Macro m = schedMacroOrNull();
        schedMacro.setText(m == null ? "Pick macro" : m.name);
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, prefs.getInt(TriggerPrefs.SCHED_H, 8));
        cal.set(Calendar.MINUTE, prefs.getInt(TriggerPrefs.SCHED_M, 0));
        schedTime.setText(DateFormat.getTimeFormat(this).format(cal.getTime()));

        long at = Schedule.nextRun(this);
        if (at == 0) {
            schedInfo.setText("the phone has to be on and unlocked at that time");
        } else {
            String when = DateFormat.format(DateFormat.is24HourFormat(this)
                    ? "EEE HH:mm" : "EEE h:mm a", at).toString();
            schedInfo.setText(String.format(Locale.getDefault(),
                    "next run %s. If the phone is asleep it can start a few minutes late.", when));
        }
    }

    private void bindSwitch(int id, String key, Runnable after) {
        Switch s = findViewById(id);
        s.setChecked(prefs.getBoolean(key, false));
        s.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(key, on).apply();
            if (after != null) after.run();
        });
    }

    private void saveLimit() {
        int v;
        try {
            v = Math.max(0, Math.min(24 * 60, Integer.parseInt(limitMin.getText().toString().trim())));
        } catch (NumberFormatException e) {
            v = 0;
        }
        SharedPreferences.Editor e = prefs.edit();
        if (v > 0) e.putInt("time_limit_last", v);
        e.putInt(TriggerPrefs.TIME_LIMIT, limitOn.isChecked() ? v : 0).apply();
    }

    private void requestTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        StatusBarManager sbm = getSystemService(StatusBarManager.class);
        sbm.requestAddTileService(new ComponentName(this, PanelTile.class), "TapLoop panel",
                Icon.createWithResource(this, R.drawable.ic_notif), getMainExecutor(), r -> {
                    if (r == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                        Toast.makeText(this, "Tile is already in quick settings", Toast.LENGTH_SHORT).show();
                    }
                });
    }
}
