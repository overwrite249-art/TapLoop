package dev.overwrite.taploop.ui;

import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import java.util.List;

import dev.overwrite.taploop.R;
import dev.overwrite.taploop.model.Step;

/** The "Go to" field and the collapsible Advanced part of the step dialog. */
class StepFlowFields {
    private final List<Step> steps;
    private final View gotoBox, advBox, jumpRow, foundBox, repeatBox, offsetBox;
    private final TextView toggle;
    private final EditText goTo, label, repeat, jitter, offset, goFound, goMiss;
    private boolean open;

    StepFlowFields(View root, Step s, List<Step> steps) {
        this.steps = steps;
        gotoBox = root.findViewById(R.id.goto_box);
        advBox = root.findViewById(R.id.adv_box);
        jumpRow = root.findViewById(R.id.jump_row);
        foundBox = root.findViewById(R.id.found_box);
        repeatBox = root.findViewById(R.id.repeat_box);
        offsetBox = root.findViewById(R.id.offset_box);
        toggle = root.findViewById(R.id.adv_toggle);
        goTo = root.findViewById(R.id.go_to);
        label = root.findViewById(R.id.label);
        repeat = root.findViewById(R.id.repeat);
        jitter = root.findViewById(R.id.jitter);
        offset = root.findViewById(R.id.offset);
        goFound = root.findViewById(R.id.go_found);
        goMiss = root.findViewById(R.id.go_miss);

        goTo.setText(target(s.goFound));
        goFound.setText(target(s.goFound));
        goMiss.setText(target(s.goMiss));
        label.setText(s.label);
        repeat.setText(String.valueOf(s.repeat));
        jitter.setText(String.valueOf(s.jitter));
        offset.setText(String.valueOf(s.offset));

        // open it right away if something in there is already set
        boolean jumps = s.cond != Step.COND_NONE && (s.goFound >= 0 || s.goMiss >= 0);
        setOpen(jumps || !s.label.isEmpty() || s.repeat != 1 || s.jitter > 0 || s.offset > 0);
        toggle.setOnClickListener(v -> setOpen(!open));
    }

    private void setOpen(boolean o) {
        open = o;
        advBox.setVisibility(o ? View.VISIBLE : View.GONE);
        toggle.setText(o ? "Advanced ▾" : "Advanced ▸");
    }

    void update(int action, int cond) {
        boolean gesture = action == Step.TAP || action == Step.SWIPE;
        gotoBox.setVisibility(action == Step.GOTO ? View.VISIBLE : View.GONE);
        jumpRow.setVisibility(cond != Step.COND_NONE ? View.VISIBLE : View.GONE);
        // for go to, the target above is the "found" jump
        foundBox.setVisibility(action == Step.GOTO ? View.INVISIBLE : View.VISIBLE);
        repeatBox.setVisibility(gesture || action == Step.WAIT ? View.VISIBLE : View.INVISIBLE);
        offsetBox.setVisibility(gesture ? View.VISIBLE : View.INVISIBLE);
    }

    void apply(Step s) {
        s.label = label.getText().toString().trim();
        s.repeat = Math.max(1, Math.min(10000, num(repeat, s.repeat)));
        s.jitter = Math.max(0, num(jitter, (int) s.jitter));
        s.offset = Math.max(0, Math.min(500, num(offset, s.offset)));
        if (s.action == Step.GOTO) s.goFound = parse(goTo);
        else s.goFound = s.cond != Step.COND_NONE ? parse(goFound) : -1;
        s.goMiss = s.cond != Step.COND_NONE ? parse(goMiss) : -1;
    }

    private String target(int i) {
        return i >= 0 ? String.valueOf(i + 1) : "";
    }

    /** step number or label, blank or unknown = -1 */
    private int parse(EditText e) {
        String t = e.getText().toString().trim();
        if (t.isEmpty()) return -1;
        try {
            int n = Integer.parseInt(t);
            return n >= 1 && n <= steps.size() ? n - 1 : -1;
        } catch (NumberFormatException ignored) {
        }
        for (int i = 0; i < steps.size(); i++) {
            if (t.equalsIgnoreCase(steps.get(i).label)) return i;
        }
        return -1;
    }

    private static int num(EditText e, int def) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }
}
