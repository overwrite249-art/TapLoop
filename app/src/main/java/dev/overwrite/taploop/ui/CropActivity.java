package dev.overwrite.taploop.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import dev.overwrite.taploop.R;
import dev.overwrite.taploop.capture.ScreenGrabber;
import dev.overwrite.taploop.capture.TemplateMatcher;
import dev.overwrite.taploop.model.Step;

/**
 * Grabs a screenshot from the running capture and lets the user mark the
 * template (and optionally where to search). Returns the new step as JSON.
 */
public class CropActivity extends Activity {
    public static final String EXTRA_DELAY = "delay";
    public static final String EXTRA_STEP = "step";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private CropView crop;
    private TextView hint, info;
    private Button modeTpl, modeArea;
    private byte[] snap;
    private int snapW, snapH;
    private float snapScale = ScreenGrabber.SCALE;
    private boolean waiting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crop);
        crop = findViewById(R.id.crop);
        hint = findViewById(R.id.hint);
        info = findViewById(R.id.info);
        modeTpl = findViewById(R.id.mode_tpl);
        modeArea = findViewById(R.id.mode_area);

        crop.setListener(this::updateInfo);
        modeTpl.setOnClickListener(v -> setMode(CropView.MODE_TEMPLATE));
        modeArea.setOnClickListener(v -> setMode(CropView.MODE_AREA));
        findViewById(R.id.whole).setOnClickListener(v -> crop.clearArea());
        findViewById(R.id.retake).setOnClickListener(v -> grabLater(5000));
        findViewById(R.id.use).setOnClickListener(v -> finishWithStep());
        setMode(CropView.MODE_TEMPLATE);

        long delay = getIntent().getLongExtra(EXTRA_DELAY, 0);
        if (delay > 0) {
            grabLater(delay);
        } else if (!grab()) {
            Toast.makeText(this, "No screen frame yet, is screen capture on?", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void grabLater(long ms) {
        if (ScreenGrabber.get() == null) {
            Toast.makeText(this, "Screen capture is off. Turn it on in the main screen first.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        waiting = true;
        Toast.makeText(this, "Open the app you want, grabbing the screen in " + (ms / 1000) + " s",
                Toast.LENGTH_LONG).show();
        moveTaskToBack(true);
        handler.postDelayed(() -> {
            waiting = false;
            if (!grab()) {
                Toast.makeText(this, "Couldn't grab the screen, is capture still on?", Toast.LENGTH_LONG).show();
            }
            // come back to the front. Allowed from the background because the
            // accessibility service is bound, otherwise the user reopens TapLoop
            startActivity(new Intent(this, CropActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        }, ms);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // just brought back after a delayed grab, keep the original extras
    }

    private boolean grab() {
        ScreenGrabber g = ScreenGrabber.get();
        if (g == null) return false;
        snapScale = g.scale();
        Boolean ok = g.withFrame((rgba, stride, w, h) -> {
            byte[] out = new byte[w * h * 4];
            for (int y = 0; y < h; y++) System.arraycopy(rgba, y * stride, out, y * w * 4, w * 4);
            snap = out;
            snapW = w;
            snapH = h;
            return true;
        });
        if (ok == null) return false;
        int[] px = new int[snapW * snapH];
        for (int i = 0, p = 0; i < px.length; i++, p += 4) {
            px[i] = 0xFF000000 | ((snap[p] & 0xFF) << 16) | ((snap[p + 1] & 0xFF) << 8) | (snap[p + 2] & 0xFF);
        }
        crop.setBitmap(Bitmap.createBitmap(px, snapW, snapH, Bitmap.Config.ARGB_8888));
        setMode(CropView.MODE_TEMPLATE);
        updateInfo();
        return true;
    }

    private void setMode(int m) {
        crop.setMode(m);
        boolean t = m == CropView.MODE_TEMPLATE;
        modeTpl.setBackgroundResource(t ? R.drawable.btn_accent : R.drawable.btn);
        modeTpl.setTextColor(t ? getColor(R.color.bg) : getColor(R.color.text));
        modeArea.setBackgroundResource(t ? R.drawable.btn : R.drawable.btn_accent);
        modeArea.setTextColor(t ? getColor(R.color.text) : getColor(R.color.bg));
        hint.setText(t ? "Drag a box around the thing to find"
                : "Drag the area to search in (optional, smaller is faster)");
    }

    private void updateInfo() {
        Rect t = crop.getTemplate(), a = crop.getArea();
        StringBuilder b = new StringBuilder();
        if (t == null) b.append("No image picked yet");
        else b.append("Image ").append(px(t.width())).append('×').append(px(t.height()));
        b.append(" · ");
        if (a == null) b.append("search whole screen");
        else b.append("search ").append(px(a.width())).append('×').append(px(a.height()));
        info.setText(b);
    }

    private int px(int framePx) {
        return Math.round(framePx / snapScale);
    }

    private void finishWithStep() {
        if (snap == null) {
            Toast.makeText(this, waiting ? "Still waiting for the screenshot" : "No screenshot",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Rect t = crop.getTemplate();
        if (t == null) {
            Toast.makeText(this, "Drag a box around the image first", Toast.LENGTH_SHORT).show();
            return;
        }
        Step s = new Step();
        if (!TemplateMatcher.cut(snap, snapW * 4, snapW, snapH, t.left, t.top, t.width(), t.height(), s)) {
            Toast.makeText(this, "That box is too small", Toast.LENGTH_SHORT).show();
            return;
        }
        s.tplFrame = snapScale;
        if (TemplateMatcher.isFlat(s)) {
            Toast.makeText(this, "That's just one flat color, pick something with more detail",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Rect a = crop.getArea();
        if (a != null) {
            if (a.width() < t.width() || a.height() < t.height()) {
                Toast.makeText(this, "Search area is smaller than the image", Toast.LENGTH_SHORT).show();
                return;
            }
            s.areaX = px(a.left);
            s.areaY = px(a.top);
            s.areaW = px(a.width());
            s.areaH = px(a.height());
        }
        s.action = Step.FIND_IMAGE;
        s.x = s.x2 = px(t.centerX());
        s.y = s.y2 = px(t.centerY());
        s.delay = 500;
        s.onMiss = Step.MISS_SKIP;
        try {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_STEP, s.toJson().toString()));
        } catch (JSONException e) {
            setResult(RESULT_CANCELED);
        }
        finish();
    }
}
