package dev.overwrite.taploop.ui;

import android.app.Activity;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

import dev.overwrite.taploop.model.Step;
import dev.overwrite.taploop.service.PreviewOverlay;
import dev.overwrite.taploop.service.TapService;

/** hides the editor and opens the on-screen picker or the macro preview */
final class ScreenPick {
    private ScreenPick() {
    }

    private static TapService service(Activity a) {
        TapService svc = TapService.get();
        if (svc == null) {
            Toast.makeText(a, "Turn on TapLoop in Accessibility settings first", Toast.LENGTH_SHORT).show();
            return null;
        }
        if (svc.isBusy()) {
            Toast.makeText(a, "Stop recording or playback first", Toast.LENGTH_SHORT).show();
            return null;
        }
        return svc;
    }

    /** picks a point into ex/ey. color and cond can be null when color makes no sense (swipe end) */
    static void point(Activity a, EditText ex, EditText ey, int[] from, EditText color, Spinner cond) {
        TapService svc = service(a);
        if (svc == null) return;
        boolean ok = svc.showPicker(num(ex), num(ey), from, color != null, (x, y, c) -> {
            if (a.isDestroyed()) return;
            ex.setText(String.valueOf(x));
            ey.setText(String.valueOf(y));
            if (c >= 0 && color != null) {
                color.setText(String.format(Locale.US, "#%06X", c & 0xFFFFFF));
                if (cond != null) cond.setSelection(Step.COND_COLOR);
            }
        });
        if (ok) a.moveTaskToBack(true);
        else Toast.makeText(a, "Couldn't open the picker", Toast.LENGTH_SHORT).show();
    }

    static void preview(Activity a, List<Step> steps, PreviewOverlay.Listener l) {
        boolean any = false;
        for (Step s : steps) if (s.action != Step.WAIT) any = true;
        if (!any) {
            Toast.makeText(a, "No taps or swipes to show", Toast.LENGTH_SHORT).show();
            return;
        }
        TapService svc = service(a);
        if (svc == null) return;
        boolean ok = svc.showPreview(steps, hits -> {
            if (!a.isDestroyed()) l.onStep(hits);
        });
        if (ok) a.moveTaskToBack(true);
        else Toast.makeText(a, "Couldn't open the preview", Toast.LENGTH_SHORT).show();
    }

    private static int num(EditText e) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
