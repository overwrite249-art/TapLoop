package dev.overwrite.taploop.model;

import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

public class Step {
    public static final int TAP = 0;
    public static final int SWIPE = 1;
    public static final int WAIT = 2;
    public static final int GOTO = 3;
    public static final int STOP = 4;

    public static final int COND_NONE = 0;
    public static final int COND_COLOR = 1;
    public static final int COND_IMAGE = 2;

    public static final int MISS_TAP = 0;
    public static final int MISS_SKIP = 1;
    public static final int MISS_STOP = 2;

    public int action = TAP;
    public int x, y, x2, y2;
    /** ms to wait before running this step */
    public long delay;
    /** how long the finger stays down (or swipe time) */
    public long duration = 40;

    public int cond = COND_NONE;
    public int color;
    public int tolerance = 28;
    public long timeout = 5000;
    public int onMiss = MISS_TAP;
    /** px around x,y to look for the image if it moved, 0 = exact spot only */
    public int searchRadius;
    public int patchSize;
    public byte[] patch;

    // flow / humanizing, all optional in json
    public String label = "";
    /** run this step N times in a row */
    public int repeat = 1;
    /** random +/- ms added to delay */
    public long jitter;
    /** random tap offset radius in px */
    public int offset;
    /** step index to jump to when found (or the target of GOTO), -1 = next */
    public int goFound = -1;
    /** step index to jump to when the condition times out, -1 = use onMiss */
    public int goMiss = -1;

    public Step copy() {
        Step s = new Step();
        s.action = action; s.x = x; s.y = y; s.x2 = x2; s.y2 = y2;
        s.delay = delay; s.duration = duration;
        s.cond = cond; s.color = color; s.tolerance = tolerance; s.timeout = timeout;
        s.onMiss = onMiss; s.searchRadius = searchRadius;
        s.patchSize = patchSize; s.patch = patch;
        s.label = label; s.repeat = repeat; s.jitter = jitter; s.offset = offset;
        s.goFound = goFound; s.goMiss = goMiss;
        return s;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("action", action);
        o.put("x", x).put("y", y).put("x2", x2).put("y2", y2);
        o.put("delay", delay).put("duration", duration);
        o.put("cond", cond).put("color", color).put("tol", tolerance);
        o.put("timeout", timeout).put("miss", onMiss).put("radius", searchRadius);
        if (!label.isEmpty()) o.put("label", label);
        if (repeat != 1) o.put("repeat", repeat);
        if (jitter > 0) o.put("jitter", jitter);
        if (offset > 0) o.put("offset", offset);
        if (goFound >= 0) o.put("goFound", goFound);
        if (goMiss >= 0) o.put("goMiss", goMiss);
        if (patch != null) {
            o.put("psize", patchSize);
            o.put("patch", Base64.encodeToString(patch, Base64.NO_WRAP));
        }
        return o;
    }

    public static Step fromJson(JSONObject o) {
        Step s = new Step();
        s.action = o.optInt("action", TAP);
        s.x = o.optInt("x"); s.y = o.optInt("y");
        s.x2 = o.optInt("x2"); s.y2 = o.optInt("y2");
        s.delay = o.optLong("delay"); s.duration = o.optLong("duration", 40);
        s.cond = o.optInt("cond"); s.color = o.optInt("color");
        s.tolerance = o.optInt("tol", 28); s.timeout = o.optLong("timeout", 5000);
        s.onMiss = o.optInt("miss"); s.searchRadius = o.optInt("radius");
        s.label = o.optString("label", "");
        s.repeat = Math.max(1, o.optInt("repeat", 1));
        s.jitter = Math.max(0, o.optLong("jitter"));
        s.offset = Math.max(0, o.optInt("offset"));
        s.goFound = o.optInt("goFound", -1);
        s.goMiss = o.optInt("goMiss", -1);
        String p = o.optString("patch", null);
        if (p != null && !p.isEmpty()) {
            s.patch = Base64.decode(p, Base64.NO_WRAP);
            s.patchSize = o.optInt("psize");
        }
        if (s.cond == COND_IMAGE && s.patch == null) s.cond = COND_NONE;
        return s;
    }

    public String summary() {
        StringBuilder b = new StringBuilder();
        if (!label.isEmpty()) b.append(label).append(": ");
        switch (action) {
            case GOTO: b.append("Go to step ").append(goFound >= 0 ? String.valueOf(goFound + 1) : "?"); break;
            case STOP: b.append("Stop macro"); break;
            case SWIPE: b.append("Swipe ").append(x).append(',').append(y)
                    .append(" → ").append(x2).append(',').append(y2); break;
            case WAIT: b.append("Wait"); break;
            default: b.append(duration >= 400 ? "Hold " : "Tap ").append(x).append(',').append(y);
        }
        return b.toString();
    }

    public String details() {
        StringBuilder b = new StringBuilder();
        b.append("after ").append(delay).append(" ms");
        if (jitter > 0) b.append(" ±").append(jitter);
        if (action == TAP || action == SWIPE) b.append(" · ").append(duration).append(" ms down");
        if (repeat > 1) b.append(" · x").append(repeat);
        if (offset > 0) b.append(" · ±").append(offset).append(" px");
        if (cond == COND_COLOR) b.append(" · color ").append(String.format("#%06X", color & 0xFFFFFF));
        else if (cond == COND_IMAGE) b.append(" · image");
        if (cond != COND_NONE && action != GOTO && goFound >= 0) b.append(" · found → ").append(goFound + 1);
        if (cond != COND_NONE && goMiss >= 0) b.append(" · else → ").append(goMiss + 1);
        return b.toString();
    }
}
