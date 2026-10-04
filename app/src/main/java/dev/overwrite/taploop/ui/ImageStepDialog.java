package dev.overwrite.taploop.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import dev.overwrite.taploop.R;
import dev.overwrite.taploop.model.Step;

/** Edit dialog for "Find image" steps. */
final class ImageStepDialog {
    private static final int MIN_MATCH = 50;

    private ImageStepDialog() {
    }

    static void show(Activity a, Step s, int pos, Runnable changed) {
        View v = a.getLayoutInflater().inflate(R.layout.dialog_find_image, null);
        ImageView preview = v.findViewById(R.id.preview);
        TextView info = v.findViewById(R.id.tpl_info);
        SeekBar match = v.findViewById(R.id.match);
        TextView matchVal = v.findViewById(R.id.match_val);
        EditText offX = v.findViewById(R.id.off_x), offY = v.findViewById(R.id.off_y);
        EditText delay = v.findViewById(R.id.delay), duration = v.findViewById(R.id.duration);
        EditText timeout = v.findViewById(R.id.timeout);
        Spinner miss = v.findViewById(R.id.miss);

        Bitmap b = preview(s);
        if (b != null) {
            BitmapDrawable d = new BitmapDrawable(a.getResources(), b);
            d.setFilterBitmap(false);
            preview.setImageDrawable(d);
        }
        String size = Math.round(s.tplW / s.tplScale / s.tplFrame) + "×"
                + Math.round(s.tplH / s.tplScale / s.tplFrame) + " px";
        String where = s.areaW > 0
                ? "searching " + s.areaW + "×" + s.areaH + " at " + s.areaX + "," + s.areaY
                : "searching the whole screen";
        info.setText(size + ", cut at " + s.x + "," + s.y + "\n" + where
                + "\nretake it with a new image step from screenshot");

        match.setProgress(Math.max(0, Math.min(match.getMax(), s.match - MIN_MATCH)));
        matchVal.setText((match.getProgress() + MIN_MATCH) + "%");
        match.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                matchVal.setText((p + MIN_MATCH) + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });

        offX.setText(String.valueOf(s.offX));
        offY.setText(String.valueOf(s.offY));
        delay.setText(String.valueOf(s.delay));
        duration.setText(String.valueOf(s.duration));
        timeout.setText(String.valueOf(s.timeout));
        ArrayAdapter<String> ad = new ArrayAdapter<>(a, android.R.layout.simple_spinner_item,
                new String[]{"Tap where it was cut", "Skip this step", "Stop the macro"});
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        miss.setAdapter(ad);
        miss.setSelection(Math.max(0, Math.min(2, s.onMiss)));

        new AlertDialog.Builder(a)
                .setTitle("Step " + (pos + 1) + " · Find image")
                .setView(v)
                .setPositiveButton("OK", (dlg, w) -> {
                    s.match = match.getProgress() + MIN_MATCH;
                    s.offX = num(offX, s.offX);
                    s.offY = num(offY, s.offY);
                    s.delay = Math.max(0, num(delay, (int) s.delay));
                    s.duration = Math.max(1, num(duration, (int) s.duration));
                    s.timeout = Math.max(0, num(timeout, (int) s.timeout));
                    s.onMiss = miss.getSelectedItemPosition();
                    changed.run();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static Bitmap preview(Step s) {
        if (!s.hasTemplate()) return null;
        int[] px = new int[s.tplW * s.tplH];
        for (int i = 0, p = 0; i < px.length; i++, p += 3) {
            px[i] = 0xFF000000 | ((s.tpl[p] & 0xFF) << 16) | ((s.tpl[p + 1] & 0xFF) << 8) | (s.tpl[p + 2] & 0xFF);
        }
        return Bitmap.createBitmap(px, s.tplW, s.tplH, Bitmap.Config.ARGB_8888);
    }

    private static int num(EditText e, int def) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }
}
