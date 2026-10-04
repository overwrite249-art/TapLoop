package dev.overwrite.taploop.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import dev.overwrite.taploop.R;
import dev.overwrite.taploop.capture.Matcher;
import dev.overwrite.taploop.capture.ScreenGrabber;
import dev.overwrite.taploop.model.Step;

/** Detection bits of the step dialog: condition list, patch preview, re-capture, timing. */
class DetectViews {
    // spinner order, values are Step.COND_*
    private static final int[] ORDER = {
            Step.COND_NONE, Step.COND_COLOR, Step.COND_COLOR_GONE, Step.COND_IMAGE,
            Step.COND_IMAGE_GONE, Step.COND_EITHER, Step.COND_BOTH};
    static final String[] NAMES = {
            "Always (just use the timing)", "Color present at X,Y", "Color gone from X,Y",
            "Image present", "Image gone", "Color OR image", "Color AND image"};

    /** how long the user gets to switch to the right screen before we grab */
    private static final long GRAB_DELAY = 3000;

    static int condAt(int pos) {
        return pos >= 0 && pos < ORDER.length ? ORDER[pos] : Step.COND_NONE;
    }

    static int indexOf(int cond) {
        for (int i = 0; i < ORDER.length; i++) if (ORDER[i] == cond) return i;
        return 0;
    }

    private final Activity act;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final EditText color, x, y, interval, stable;
    private final ImageView preview;
    private final View swatch;

    private byte[] patch;
    private int patchSize;

    DetectViews(Activity act, View root, Step s) {
        this.act = act;
        color = root.findViewById(R.id.color);
        x = root.findViewById(R.id.x);
        y = root.findViewById(R.id.y);
        interval = root.findViewById(R.id.interval);
        stable = root.findViewById(R.id.stable);
        preview = root.findViewById(R.id.patch_preview);
        swatch = root.findViewById(R.id.color_preview);
        patch = s.patch;
        patchSize = s.patchSize;

        if (s.interval > 0) interval.setText(String.valueOf(s.interval));
        if (s.stable > 0) stable.setText(String.valueOf(s.stable));

        showPatch();
        showColor();
        color.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence t, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence t, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                showColor();
            }
        });
        root.findViewById(R.id.recapture).setOnClickListener(v -> recapture());
    }

    /** call after s.cond was set from the spinner */
    void apply(Step s) {
        s.patch = patch;
        s.patchSize = patch == null ? 0 : patchSize;
        s.interval = Math.max(0, Math.min(60000, num(interval, 0)));
        s.stable = Math.max(0, Math.min(60000, num(stable, 0)));
        if (patch == null && Matcher.usesImage(s.cond)) {
            s.cond = s.cond == Step.COND_IMAGE_GONE || s.cond == Step.COND_IMAGE
                    ? Step.COND_NONE : Step.COND_COLOR;
            toast("No image saved for this step yet, use Re-capture first");
        }
    }

    private void recapture() {
        if (ScreenGrabber.get() == null) {
            toast("Turn on screen capture in the main screen first");
            return;
        }
        int sx = num(x, 0), sy = num(y, 0);
        toast("Grabbing at " + sx + "," + sy + " in 3 s, go to that screen");
        // otherwise we'd just capture this dialog
        act.moveTaskToBack(true);
        handler.postDelayed(() -> grab(sx, sy), GRAB_DELAY);
    }

    private void grab(int sx, int sy) {
        if (act.isDestroyed()) return;
        ScreenGrabber g = ScreenGrabber.get();
        if (g == null || !g.hasFrame()) {
            toast("Screen capture stopped, nothing grabbed");
        } else {
            int c = g.colorAt(sx, sy);
            byte[] p = g.patchAt(sx, sy);
            if (c != -1) color.setText(String.format("#%06X", c & 0xFFFFFF));
            if (p != null) {
                patch = p;
                patchSize = ScreenGrabber.PATCH;
                showPatch();
                toast("Captured");
            } else {
                toast("Too close to the edge for an image, only got the color");
            }
        }
        // bring the editor back, the open dialog stays as it was.
        // may be ignored by the system, then the user just switches back.
        try {
            act.startActivity(new Intent(act, act.getClass())
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (RuntimeException ignored) {
        }
    }

    private void showPatch() {
        Bitmap b = render(patch, patchSize, 6);
        if (b == null) {
            preview.setImageDrawable(null);
            return;
        }
        BitmapDrawable d = new BitmapDrawable(act.getResources(), b);
        d.setFilterBitmap(false);
        preview.setImageDrawable(d);
    }

    private void showColor() {
        String hex = color.getText().toString().trim();
        try {
            swatch.setBackgroundColor(0xFF000000 | Color.parseColor(hex.startsWith("#") ? hex : "#" + hex));
        } catch (IllegalArgumentException e) {
            swatch.setBackgroundColor(0);
        }
    }

    /** RGB bytes to a bitmap, blown up with nearest neighbour so pixels stay sharp */
    static Bitmap render(byte[] rgb, int size, int scale) {
        if (rgb == null || size <= 0 || rgb.length < size * size * 3) return null;
        int[] px = new int[size * size];
        for (int i = 0, o = 0; i < px.length; i++, o += 3) {
            px[i] = 0xFF000000 | ((rgb[o] & 0xFF) << 16) | ((rgb[o + 1] & 0xFF) << 8) | (rgb[o + 2] & 0xFF);
        }
        Bitmap small = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        Bitmap big = Bitmap.createScaledBitmap(small, size * scale, size * scale, false);
        if (big != small) small.recycle();
        return big;
    }

    private void toast(String s) {
        Toast.makeText(act, s, Toast.LENGTH_SHORT).show();
    }

    private static int num(EditText e, int def) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }
}
