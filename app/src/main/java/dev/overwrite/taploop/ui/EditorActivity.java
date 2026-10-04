package dev.overwrite.taploop.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import dev.overwrite.taploop.R;
import dev.overwrite.taploop.model.Macro;
import dev.overwrite.taploop.model.MacroStore;
import dev.overwrite.taploop.model.Step;
import dev.overwrite.taploop.service.TapService;

public class EditorActivity extends Activity {
    private Macro macro;
    private EditText name, loops, speed, loopDelay;
    private TextView count;
    private StepAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        macro = MacroStore.load(this, getIntent().getStringExtra("id"));
        if (macro == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_editor);

        name = findViewById(R.id.name);
        loops = findViewById(R.id.loops);
        speed = findViewById(R.id.speed);
        loopDelay = findViewById(R.id.loop_delay);
        count = findViewById(R.id.count);

        name.setText(macro.name);
        loops.setText(String.valueOf(macro.loops));
        speed.setText(String.valueOf(macro.speed));
        loopDelay.setText(String.valueOf(macro.loopDelay));

        ListView list = findViewById(R.id.steps);
        adapter = new StepAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> editStep(pos));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            stepMenu(pos);
            return true;
        });

        findViewById(R.id.add).setOnClickListener(v -> {
            Step s = new Step();
            if (!macro.steps.isEmpty()) {
                Step last = macro.steps.get(macro.steps.size() - 1);
                s.x = last.x;
                s.y = last.y;
            } else {
                s.x = getResources().getDisplayMetrics().widthPixels / 2;
                s.y = getResources().getDisplayMetrics().heightPixels / 2;
            }
            s.delay = 500;
            macro.steps.add(s);
            changed();
            editStep(macro.steps.size() - 1);
        });
        findViewById(R.id.preview).setOnClickListener(v ->
                ScreenPick.preview(this, macro.steps, this::editFromPreview));
        findViewById(R.id.save).setOnClickListener(v -> {
            save();
            finish();
        });
        findViewById(R.id.delete).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Delete \"" + macro.name + "\"?")
                .setPositiveButton("Delete", (d, w) -> {
                    TapService svc = TapService.get();
                    if (svc != null && svc.getActive() != null && macro.id.equals(svc.getActive().id)) {
                        svc.setActive(null);
                    }
                    MacroStore.delete(this, macro.id);
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show());
        changed();
    }

    private void changed() {
        count.setText(macro.steps.size() + " steps");
        adapter.notifyDataSetChanged();
    }

    private void save() {
        String n = name.getText().toString().trim();
        macro.name = n.isEmpty() ? "Untitled" : n;
        macro.loops = Math.max(0, num(loops, 1));
        macro.speed = Math.max(10, Math.min(1000, num(speed, 100)));
        macro.loopDelay = Math.max(0, num(loopDelay, 0));
        MacroStore.save(this, macro);
        TapService svc = TapService.get();
        if (svc != null && !svc.isBusy() && svc.getActive() != null
                && macro.id.equals(svc.getActive().id)) {
            svc.setActive(macro);
        }
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
    }

    private static int num(EditText e, int def) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private void stepMenu(int pos) {
        String[] items = {"Move up", "Move down", "Duplicate", "Delete"};
        new AlertDialog.Builder(this)
                .setTitle("Step " + (pos + 1))
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            if (pos > 0) macro.steps.add(pos - 1, macro.steps.remove(pos));
                            break;
                        case 1:
                            if (pos < macro.steps.size() - 1) macro.steps.add(pos + 1, macro.steps.remove(pos));
                            break;
                        case 2:
                            macro.steps.add(pos + 1, macro.steps.get(pos).copy());
                            break;
                        case 3:
                            macro.steps.remove(pos);
                            break;
                    }
                    changed();
                })
                .show();
    }

    private void editStep(int pos) {
        Step s = macro.steps.get(pos);
        View v = getLayoutInflater().inflate(R.layout.dialog_step, null);

        Spinner action = v.findViewById(R.id.action);
        Spinner cond = v.findViewById(R.id.cond);
        Spinner miss = v.findViewById(R.id.miss);
        EditText x = v.findViewById(R.id.x), y = v.findViewById(R.id.y);
        EditText x2 = v.findViewById(R.id.x2), y2 = v.findViewById(R.id.y2);
        EditText delay = v.findViewById(R.id.delay), duration = v.findViewById(R.id.duration);
        EditText color = v.findViewById(R.id.color), tol = v.findViewById(R.id.tol);
        EditText timeout = v.findViewById(R.id.timeout), radius = v.findViewById(R.id.radius);
        View endRow = v.findViewById(R.id.end_row), durBox = v.findViewById(R.id.dur_box);
        View condBox = v.findViewById(R.id.cond_box), colorBox = v.findViewById(R.id.color_box);
        View radiusBox = v.findViewById(R.id.radius_box);
        View pickRow = v.findViewById(R.id.pick_row), pickEnd = v.findViewById(R.id.pick_end);
        v.findViewById(R.id.pick).setOnClickListener(b -> ScreenPick.point(this, x, y, null, color, cond));
        pickEnd.setOnClickListener(b -> ScreenPick.point(this, x2, y2,
                new int[]{num(x, s.x), num(y, s.y)}, null, null));

        action.setAdapter(spinner("Tap / hold", "Swipe", "Just wait"));
        String[] condNames = s.patch != null
                ? new String[]{"Always (just use the timing)", "Color is at X,Y", "Recorded image is there"}
                : new String[]{"Always (just use the timing)", "Color is at X,Y"};
        cond.setAdapter(spinner(condNames));
        miss.setAdapter(spinner("Tap anyway", "Skip this step", "Stop the macro"));

        action.setSelection(s.action);
        cond.setSelection(Math.min(s.cond, condNames.length - 1));
        miss.setSelection(s.onMiss);
        x.setText(String.valueOf(s.x));
        y.setText(String.valueOf(s.y));
        x2.setText(String.valueOf(s.x2));
        y2.setText(String.valueOf(s.y2));
        delay.setText(String.valueOf(s.delay));
        duration.setText(String.valueOf(s.duration));
        color.setText(String.format("#%06X", s.color & 0xFFFFFF));
        tol.setText(String.valueOf(s.tolerance));
        timeout.setText(String.valueOf(s.timeout));
        radius.setText(String.valueOf(s.searchRadius));

        Runnable vis = () -> {
            int a = action.getSelectedItemPosition();
            int c = cond.getSelectedItemPosition();
            endRow.setVisibility(a == Step.SWIPE ? View.VISIBLE : View.GONE);
            pickRow.setVisibility(a == Step.WAIT ? View.GONE : View.VISIBLE);
            pickEnd.setVisibility(a == Step.SWIPE ? View.VISIBLE : View.GONE);
            durBox.setVisibility(a == Step.WAIT ? View.INVISIBLE : View.VISIBLE);
            condBox.setVisibility(c == Step.COND_NONE ? View.GONE : View.VISIBLE);
            colorBox.setVisibility(c == Step.COND_COLOR ? View.VISIBLE : View.GONE);
            radiusBox.setVisibility(c == Step.COND_IMAGE ? View.VISIBLE : View.INVISIBLE);
        };
        AdapterView.OnItemSelectedListener l = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View view, int i, long id) {
                vis.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        };
        action.setOnItemSelectedListener(l);
        cond.setOnItemSelectedListener(l);
        vis.run();

        new AlertDialog.Builder(this)
                .setTitle("Step " + (pos + 1))
                .setView(v)
                .setPositiveButton("OK", (d, w) -> {
                    s.action = action.getSelectedItemPosition();
                    s.cond = cond.getSelectedItemPosition();
                    s.onMiss = miss.getSelectedItemPosition();
                    s.x = num(x, s.x);
                    s.y = num(y, s.y);
                    s.x2 = num(x2, s.x2);
                    s.y2 = num(y2, s.y2);
                    if (s.action != Step.SWIPE) {
                        s.x2 = s.x;
                        s.y2 = s.y;
                    }
                    s.delay = Math.max(0, num(delay, (int) s.delay));
                    s.duration = Math.max(1, num(duration, (int) s.duration));
                    s.tolerance = Math.max(0, Math.min(255, num(tol, s.tolerance)));
                    s.timeout = Math.max(0, num(timeout, (int) s.timeout));
                    s.searchRadius = Math.max(0, Math.min(400, num(radius, s.searchRadius)));
                    String hex = color.getText().toString().trim();
                    if (!TextUtils.isEmpty(hex)) {
                        try {
                            s.color = Color.parseColor(hex.startsWith("#") ? hex : "#" + hex) & 0xFFFFFF;
                        } catch (IllegalArgumentException e) {
                            Toast.makeText(this, "Bad color, kept the old one", Toast.LENGTH_SHORT).show();
                        }
                    }
                    changed();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void editFromPreview(int[] hits) {
        if (hits.length == 1) {
            if (hits[0] < macro.steps.size()) editStep(hits[0]);
            return;
        }
        String[] items = new String[hits.length];
        for (int i = 0; i < hits.length; i++) {
            items[i] = (hits[i] + 1) + ". " + macro.steps.get(hits[i]).summary();
        }
        new AlertDialog.Builder(this)
                .setTitle("Steps here")
                .setItems(items, (d, w) -> editStep(hits[w]))
                .show();
    }

    private ArrayAdapter<String> spinner(String... items) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    private class StepAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return macro.steps.size();
        }

        @Override
        public Step getItem(int i) {
            return macro.steps.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            if (v == null) v = getLayoutInflater().inflate(R.layout.item_step, parent, false);
            Step s = getItem(i);
            ((TextView) v.findViewById(R.id.num)).setText(String.valueOf(i + 1));
            ((TextView) v.findViewById(R.id.title)).setText(s.summary());
            ((TextView) v.findViewById(R.id.sub)).setText(s.details());
            View sw = v.findViewById(R.id.swatch);
            if (s.cond == Step.COND_COLOR || s.cond == Step.COND_IMAGE) {
                sw.setVisibility(View.VISIBLE);
                sw.setBackgroundColor(0xFF000000 | s.color);
            } else {
                sw.setVisibility(View.GONE);
            }
            return v;
        }
    }
}
