package dev.overwrite.taploop.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import java.util.List;

import dev.overwrite.taploop.R;
import dev.overwrite.taploop.model.Step;

/** The "Edit..." menu in multi-select mode. */
class BulkEdit {
    interface Apply {
        /** wraps the change so it can be undone */
        void run(Runnable change);
    }

    static void show(Activity a, List<Step> sel, Apply apply) {
        String[] items = {"Set delay", "Scale delays by %", "Set press time", "Shift X / Y"};
        new AlertDialog.Builder(a)
                .setTitle(sel.size() + (sel.size() == 1 ? " step" : " steps"))
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            ask(a, "Set delay", "Delay before each step (ms)", "500", null, null,
                                    (v1, v2) -> apply.run(() -> {
                                        for (Step s : sel) s.delay = Math.max(0, v1);
                                    }));
                            break;
                        case 1:
                            ask(a, "Scale delays", "Percent (50 = twice as fast)", "100", null, null,
                                    (v1, v2) -> apply.run(() -> {
                                        int pct = Math.max(0, v1);
                                        for (Step s : sel) s.delay = Math.round(s.delay * pct / 100.0);
                                    }));
                            break;
                        case 2:
                            ask(a, "Set press time", "Finger down / swipe time (ms)", "40", null, null,
                                    (v1, v2) -> apply.run(() -> {
                                        for (Step s : sel) {
                                            if (s.action != Step.WAIT) s.duration = Math.max(1, v1);
                                        }
                                    }));
                            break;
                        case 3:
                            ask(a, "Shift X / Y", "Move X by (px, can be negative)", "0",
                                    "Move Y by (px, can be negative)", "0",
                                    (v1, v2) -> apply.run(() -> {
                                        for (Step s : sel) {
                                            s.x = Math.max(0, s.x + v1);
                                            s.y = Math.max(0, s.y + v2);
                                            s.x2 = Math.max(0, s.x2 + v1);
                                            s.y2 = Math.max(0, s.y2 + v2);
                                        }
                                    }));
                            break;
                    }
                })
                .show();
    }

    private interface Values {
        void got(int v1, int v2);
    }

    private static void ask(Activity a, String title, String label1, String def1,
                            String label2, String def2, Values cb) {
        View v = a.getLayoutInflater().inflate(R.layout.dialog_bulk, null);
        EditText f1 = v.findViewById(R.id.value1), f2 = v.findViewById(R.id.value2);
        ((TextView) v.findViewById(R.id.label1)).setText(label1);
        f1.setText(def1);
        if (label2 != null) {
            ((TextView) v.findViewById(R.id.label2)).setText(label2);
            f2.setText(def2);
            v.findViewById(R.id.box2).setVisibility(View.VISIBLE);
            // shifting needs negatives
            f1.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
            f2.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        }
        f1.selectAll();
        new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(v)
                .setPositiveButton("Apply", (d, w) -> {
                    Integer n1 = parse(f1), n2 = label2 == null ? Integer.valueOf(0) : parse(f2);
                    if (n1 == null || n2 == null) return;
                    cb.got(n1, n2);
                })
                .setNegativeButton("Cancel", null)
                .show();
        f1.requestFocus();
    }

    private static Integer parse(EditText e) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
