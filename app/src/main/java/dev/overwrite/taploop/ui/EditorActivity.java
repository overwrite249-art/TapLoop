package dev.overwrite.taploop.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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

    private final EditHistory history = new EditHistory();
    private final Set<Step> selected = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean selectMode;
    private StepListView list;
    private TimelineBar timeline;
    private View normalBar, selectBar, undoBar, undoBtn, redoBtn, selInsert;
    private TextView selCount, undoText;
    private List<Step> dragSnap;
    private String savedState;
    private final Runnable hideUndo = this::hideUndoBar;

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

        list = findViewById(R.id.steps);
        adapter = new StepAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (selectMode) toggle(pos);
            else editStep(pos);
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            selectMode = true;
            toggle(pos);
            return true;
        });
        setupListTools();

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
            history.save(macro.steps);
            macro.steps.add(s);
            changed();
            editStep(macro.steps.size() - 1);
        });
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
        savedState = state();
        if (Build.VERSION.SDK_INT >= 33) {
            // only used if predictive back gets turned on, otherwise onBackPressed runs
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
    }

    private void changed() {
        Set<Step> live = Collections.newSetFromMap(new IdentityHashMap<>());
        live.addAll(macro.steps);
        selected.retainAll(live);
        if (selected.isEmpty()) selectMode = false;
        hideUndoBar();
        undoBtn.setEnabled(history.canUndo());
        undoBtn.setAlpha(history.canUndo() ? 1f : 0.4f);
        redoBtn.setEnabled(history.canRedo());
        redoBtn.setAlpha(history.canRedo() ? 1f : 0.4f);
        updateCount();
        refreshSelection();
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
                    history.save(macro.steps);
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

    // undo/redo, multi-select, drag and swipe

    private void setupListTools() {
        timeline = findViewById(R.id.timeline);
        normalBar = findViewById(R.id.normal_bar);
        selectBar = findViewById(R.id.select_bar);
        undoBar = findViewById(R.id.undo_bar);
        undoText = findViewById(R.id.undo_text);
        undoBtn = findViewById(R.id.undo);
        redoBtn = findViewById(R.id.redo);
        selCount = findViewById(R.id.sel_count);
        selInsert = findViewById(R.id.sel_insert);

        undoBtn.setOnClickListener(v -> {
            if (history.undo(macro.steps)) changed();
        });
        redoBtn.setOnClickListener(v -> {
            if (history.redo(macro.steps)) changed();
        });
        findViewById(R.id.undo_bar_btn).setOnClickListener(v -> {
            if (history.undo(macro.steps)) changed();
        });

        timeline.setSteps(macro.steps, selected);
        timeline.setOnStepTap(i -> list.smoothScrollToPosition(i));
        speed.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence c, int a, int b, int n) {
            }

            @Override
            public void onTextChanged(CharSequence c, int a, int b, int n) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                updateCount();
            }
        });

        findViewById(R.id.sel_close).setOnClickListener(v -> setSelectMode(false));
        findViewById(R.id.sel_all).setOnClickListener(v -> {
            if (selected.size() == macro.steps.size()) {
                setSelectMode(false);
            } else {
                selected.addAll(macro.steps);
                refreshSelection();
            }
        });
        findViewById(R.id.sel_delete).setOnClickListener(v -> {
            int n = selected.size();
            edit(() -> macro.steps.removeIf(selected::contains));
            showUndoBar(n == 1 ? "Step deleted" : n + " steps deleted");
        });
        findViewById(R.id.sel_dup).setOnClickListener(v -> {
            List<Step> sel = selectedInOrder();
            if (sel.isEmpty()) return;
            // copies go after the last selected step, so a block stays a block
            int at = macro.steps.indexOf(sel.get(sel.size() - 1)) + 1;
            List<Step> copies = new ArrayList<>();
            for (Step s : sel) copies.add(s.copy());
            edit(() -> macro.steps.addAll(at, copies));
            selectMode = true;
            selected.clear();
            selected.addAll(copies);
            refreshSelection();
        });
        findViewById(R.id.sel_edit).setOnClickListener(v -> {
            List<Step> sel = selectedInOrder();
            if (!sel.isEmpty()) BulkEdit.show(this, sel, this::edit);
        });
        selInsert.setOnClickListener(v -> {
            List<Step> sel = selectedInOrder();
            if (sel.size() != 1) return;
            Step ref = sel.get(0);
            new AlertDialog.Builder(this)
                    .setItems(new String[]{"Insert step above", "Insert step below"}, (d, which) -> {
                        int pos = macro.steps.indexOf(ref);
                        insertAt(which == 0 ? pos : pos + 1, ref);
                    })
                    .show();
        });

        list.setListener(new StepListView.Listener() {
            @Override
            public void onDragStart(int pos) {
                dragSnap = EditHistory.snapshot(macro.steps);
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onDragMove(int from, int to) {
                macro.steps.add(to, macro.steps.remove(from));
                adapter.notifyDataSetChanged();
                timeline.invalidate();
            }

            @Override
            public void onDragEnd(int startPos, int endPos) {
                if (startPos != endPos && dragSnap != null) history.saveSnapshot(dragSnap);
                dragSnap = null;
                changed();
            }

            @Override
            public boolean canSwipe(int pos) {
                return !selectMode;
            }

            @Override
            public void onSwipe(int pos) {
                if (pos < 0 || pos >= macro.steps.size()) return;
                edit(() -> macro.steps.remove(pos));
                showUndoBar("Step " + (pos + 1) + " deleted");
            }
        }, R.id.handle);
    }

    /** every list change goes through here so it can be undone */
    private void edit(Runnable change) {
        history.save(macro.steps);
        change.run();
        changed();
    }

    private void insertAt(int at, Step ref) {
        Step s = new Step();
        s.x = ref.x;
        s.y = ref.y;
        s.delay = 500;
        selectMode = false;
        selected.clear();
        edit(() -> macro.steps.add(at, s));
        editStep(at);
    }

    private void setSelectMode(boolean on) {
        selectMode = on;
        if (!on) selected.clear();
        refreshSelection();
    }

    private void toggle(int pos) {
        Step s = macro.steps.get(pos);
        if (!selected.remove(s)) selected.add(s);
        if (selected.isEmpty()) selectMode = false;
        refreshSelection();
    }

    private List<Step> selectedInOrder() {
        List<Step> out = new ArrayList<>();
        for (Step s : macro.steps) if (selected.contains(s)) out.add(s);
        return out;
    }

    private void refreshSelection() {
        normalBar.setVisibility(selectMode ? View.GONE : View.VISIBLE);
        selectBar.setVisibility(selectMode ? View.VISIBLE : View.GONE);
        selCount.setText(selected.size() + " selected");
        selInsert.setEnabled(selected.size() == 1);
        selInsert.setAlpha(selected.size() == 1 ? 1f : 0.4f);
        adapter.notifyDataSetChanged();
        timeline.invalidate();
    }

    private void showUndoBar(String msg) {
        undoText.setText(msg);
        undoBar.setVisibility(View.VISIBLE);
        undoBar.removeCallbacks(hideUndo);
        undoBar.postDelayed(hideUndo, 4000);
    }

    private void hideUndoBar() {
        undoBar.removeCallbacks(hideUndo);
        undoBar.setVisibility(View.GONE);
    }

    private void updateCount() {
        int n = macro.steps.size();
        int spd = Math.max(10, Math.min(1000, num(speed, macro.speed)));
        long t = 0;
        for (Step s : macro.steps) {
            t += s.delay * 100 / spd + (s.action == Step.WAIT ? 0 : s.duration);
        }
        count.setText(n + (n == 1 ? " step" : " steps") + " · " + formatTime(t));
    }

    private static String formatTime(long ms) {
        if (ms < 1000) return ms + " ms";
        if (ms < 60000) return String.format(Locale.US, "%.1f s", ms / 1000f);
        long sec = ms / 1000;
        return String.format(Locale.US, "%d:%02d min", sec / 60, sec % 60);
    }

    /** what the user would lose by leaving, compared against the last save */
    private String state() {
        StringBuilder b = new StringBuilder();
        b.append(name.getText()).append('\n').append(loops.getText()).append('\n')
                .append(speed.getText()).append('\n').append(loopDelay.getText());
        try {
            for (Step s : macro.steps) b.append('\n').append(s.toJson());
        } catch (JSONException ignored) {
            // can't compare, treat as changed
            b.append(System.nanoTime());
        }
        return b.toString();
    }

    private void handleBack() {
        if (selectMode) {
            setSelectMode(false);
            return;
        }
        if (savedState == null || savedState.equals(state())) {
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Unsaved changes")
                .setMessage("Save before leaving?")
                .setPositiveButton("Save", (d, w) -> {
                    save();
                    finish();
                })
                .setNegativeButton("Discard", (d, w) -> finish())
                .setNeutralButton("Keep editing", null)
                .show();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        handleBack();
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
            v.setTranslationX(0);
            v.setAlpha(i == list.getDragPosition() ? 0f : 1f);
            v.setBackgroundResource(selected.contains(s) ? R.drawable.card_selected : R.drawable.card);
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
