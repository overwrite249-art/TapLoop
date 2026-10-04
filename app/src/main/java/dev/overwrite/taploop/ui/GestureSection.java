package dev.overwrite.taploop.ui;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import dev.overwrite.taploop.R;
import dev.overwrite.taploop.model.Step;

/**
 * The gesture part of the step dialog: which kind of touch, the recorded path
 * preview and the second finger. Lives in its own layout that gets slotted in
 * under the end X/Y row.
 */
class GestureSection {
    static final int TAP = 0, LONG = 1, DOUBLE = 2, SWIPE = 3, PATH = 4, WAIT = 5;

    private final Activity act;
    private final Step step;
    private final Spinner action, fingers;
    private final EditText x, y, x2, y2, duration, fx, fy, fx2, fy2;
    private final TextView durLabel;
    private final View endRow, pathBox, fingersBox, f2Box, f2EndRow, pinchRow;
    private final List<Integer> kinds = new ArrayList<>();
    private int lastKind;

    GestureSection(Activity act, View dialog, Step s) {
        this.act = act;
        step = s;
        action = dialog.findViewById(R.id.action);
        x = dialog.findViewById(R.id.x);
        y = dialog.findViewById(R.id.y);
        x2 = dialog.findViewById(R.id.x2);
        y2 = dialog.findViewById(R.id.y2);
        duration = dialog.findViewById(R.id.duration);
        durLabel = dialog.findViewById(R.id.dur_label);
        endRow = dialog.findViewById(R.id.end_row);

        View v = act.getLayoutInflater().inflate(R.layout.step_gesture, (ViewGroup) endRow.getParent(), false);
        ViewGroup parent = (ViewGroup) endRow.getParent();
        parent.addView(v, parent.indexOfChild(endRow) + 1);

        pathBox = v.findViewById(R.id.path_box);
        fingersBox = v.findViewById(R.id.fingers_box);
        f2Box = v.findViewById(R.id.f2_box);
        f2EndRow = v.findViewById(R.id.f2_end_row);
        pinchRow = v.findViewById(R.id.pinch_row);
        fingers = v.findViewById(R.id.fingers);
        fx = v.findViewById(R.id.fx);
        fy = v.findViewById(R.id.fy);
        fx2 = v.findViewById(R.id.fx2);
        fy2 = v.findViewById(R.id.fy2);

        List<String> names = new ArrayList<>();
        names.add("Tap"); kinds.add(TAP);
        names.add("Long press"); kinds.add(LONG);
        names.add("Double tap"); kinds.add(DOUBLE);
        names.add("Swipe"); kinds.add(SWIPE);
        if (s.path != null) {
            names.add("Path (recorded)"); kinds.add(PATH);
        }
        names.add("Just wait"); kinds.add(WAIT);
        action.setAdapter(adapter(names.toArray(new String[0])));
        lastKind = kindOf(s);
        action.setSelection(kinds.indexOf(lastKind));

        if (s.path != null) {
            int n = s.path.length / 3;
            ((TextView) v.findViewById(R.id.path_info)).setText(n + " points, recorded over "
                    + s.path[s.path.length - 1] + " ms");
            ((PathPreview) v.findViewById(R.id.path_preview)).setPoints(s.path);
            v.findViewById(R.id.simplify).setOnClickListener(b -> {
                int last = s.path.length - 3;
                x2.setText(String.valueOf(s.path[last] + num(x, s.x) - s.path[0]));
                y2.setText(String.valueOf(s.path[last + 1] + num(y, s.y) - s.path[1]));
                action.setSelection(kinds.indexOf(SWIPE));
            });
        }

        fingers.setAdapter(adapter("1 finger", "2 fingers"));
        fingers.setSelection(s.fingers > 1 ? 1 : 0);
        fx.setText(String.valueOf(s.fx));
        fy.setText(String.valueOf(s.fy));
        fx2.setText(String.valueOf(s.fx2));
        fy2.setText(String.valueOf(s.fy2));
        fingers.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View view, int i, long id) {
                if (i == 1 && num(fx, 0) == 0 && num(fy, 0) == 0) {
                    // put the second finger next to the first so it's not stuck in the corner
                    int off = dp(80);
                    fx.setText(String.valueOf(num(x, 0) + off));
                    fy.setText(String.valueOf(num(y, 0)));
                    fx2.setText(String.valueOf(num(x2, 0) + off));
                    fy2.setText(String.valueOf(num(y2, 0)));
                }
                refresh();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        v.findViewById(R.id.pinch_in).setOnClickListener(b -> pinch(true));
        v.findViewById(R.id.pinch_out).setOnClickListener(b -> pinch(false));
    }

    static int kindOf(Step s) {
        if (s.action == Step.WAIT) return WAIT;
        if (s.action == Step.SWIPE) return s.path != null ? PATH : SWIPE;
        if (s.taps > 1) return DOUBLE;
        return s.duration >= 400 ? LONG : TAP;
    }

    int kind() {
        int i = action.getSelectedItemPosition();
        return i >= 0 && i < kinds.size() ? kinds.get(i) : TAP;
    }

    /** the plain Step action for the selected kind */
    int action() {
        int k = kind();
        if (k == WAIT) return Step.WAIT;
        return k == SWIPE || k == PATH ? Step.SWIPE : Step.TAP;
    }

    void refresh() {
        int k = kind();
        if (k != lastKind) {
            int d = num(duration, 40);
            if (k == LONG && d < 400) d = 600;
            else if ((k == TAP || k == DOUBLE) && d >= 400) d = 40;
            else if (k == SWIPE && lastKind != PATH && d < 100) d = 300;
            duration.setText(String.valueOf(d));
            lastKind = k;
        }
        boolean finger = k != PATH && k != WAIT;
        boolean two = finger && fingers.getSelectedItemPosition() == 1;
        if (k == PATH) endRow.setVisibility(View.GONE);
        pathBox.setVisibility(k == PATH ? View.VISIBLE : View.GONE);
        fingersBox.setVisibility(finger ? View.VISIBLE : View.GONE);
        f2Box.setVisibility(two ? View.VISIBLE : View.GONE);
        f2EndRow.setVisibility(two && k == SWIPE ? View.VISIBLE : View.GONE);
        pinchRow.setVisibility(two && k == SWIPE ? View.VISIBLE : View.GONE);
        durLabel.setText(k == SWIPE || k == PATH ? "Swipe time (ms)"
                : k == LONG ? "Hold time (ms)" : "Press time (ms)");
    }

    private void pinch(boolean in) {
        int cx = (num(x, 0) + num(fx, 0)) / 2;
        int cy = (num(y, 0) + num(fy, 0)) / 2;
        int far = dp(140), near = dp(24);
        int a = in ? far : near, b = in ? near : far;
        x.setText(String.valueOf(Math.max(0, cx - a)));
        y.setText(String.valueOf(cy));
        x2.setText(String.valueOf(Math.max(0, cx - b)));
        y2.setText(String.valueOf(cy));
        fx.setText(String.valueOf(cx + a));
        fy.setText(String.valueOf(cy));
        fx2.setText(String.valueOf(cx + b));
        fy2.setText(String.valueOf(cy));
        if (num(duration, 0) < 200) duration.setText("400");
    }

    /** call after the dialog wrote x, y, x2, y2 and duration into the step */
    void apply(Step s) {
        int k = kind();
        if (k == WAIT) return;
        s.taps = k == DOUBLE ? 2 : 1;
        if (k == PATH && s.path != null) {
            int[] p = s.path;
            int dx = s.x - p[0], dy = s.y - p[1];
            long old = Math.max(1, p[p.length - 1]);
            for (int i = 0; i < p.length; i += 3) {
                p[i] += dx;
                p[i + 1] += dy;
                if (s.duration != old) p[i + 2] = (int) (p[i + 2] * s.duration / old);
            }
            s.x2 = p[p.length - 3];
            s.y2 = p[p.length - 2];
            s.fingers = 1;
            return;
        }
        s.path = null;
        s.fingers = fingers.getSelectedItemPosition() == 1 ? 2 : 1;
        s.fx = Math.max(0, num(fx, s.fx));
        s.fy = Math.max(0, num(fy, s.fy));
        s.fx2 = k == SWIPE ? Math.max(0, num(fx2, s.fx2)) : s.fx;
        s.fy2 = k == SWIPE ? Math.max(0, num(fy2, s.fy2)) : s.fy;
    }

    private ArrayAdapter<String> adapter(String... items) {
        ArrayAdapter<String> a = new ArrayAdapter<>(act, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    private static int num(EditText e, int def) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }
}
