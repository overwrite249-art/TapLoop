package dev.overwrite.taploop.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Base64;

public class Step {
    public static final int TAP = 0;
    public static final int SWIPE = 1;
    public static final int WAIT = 2;

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
    /** capture scale the patch was taken at, older macros were always 0.5 */
    public float patchScale = 0.5f;
    /** smart record: the real gap before this tap, used when capture is off. -1 = unknown */
    public long recGap = -1;

    public Step copy() {
        Step s = new Step();
        s.action = action; s.x = x; s.y = y; s.x2 = x2; s.y2 = y2;
        s.delay = delay; s.duration = duration;
        s.cond = cond; s.color = color; s.tolerance = tolerance; s.timeout = timeout;
        s.onMiss = onMiss; s.searchRadius = searchRadius;
        s.patchSize = patchSize; s.patch = patch;
        s.patchScale = patchScale; s.recGap = recGap;
        return s;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("action", action);
        o.put("x", x).put("y", y).put("x2", x2).put("y2", y2);
        o.put("delay", delay).put("duration", duration);
        o.put("cond", cond).put("color", color).put("tol", tolerance);
        o.put("timeout", timeout).put("miss", onMiss).put("radius", searchRadius);
        if (patch != null) {
            o.put("psize", patchSize);
            o.put("patch", Base64.getEncoder().encodeToString(patch));
            o.put("pscale", (double) patchScale);
        }
        if (recGap >= 0) o.put("gap", recGap);
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
        String p = o.optString("patch", null);
        if (p != null && !p.isEmpty()) {
            try {
                s.patch = Base64.getMimeDecoder().decode(p);
                s.patchSize = o.optInt("psize");
            } catch (IllegalArgumentException e) {
                s.patch = null;
            }
            double ps = o.optDouble("pscale", 0.5);
            s.patchScale = ps > 0.05 && ps <= 1 ? (float) ps : 0.5f;
        }
        s.recGap = o.optLong("gap", -1);
        if (s.cond == COND_IMAGE && s.patch == null) s.cond = COND_NONE;
        return s;
    }

    public String summary() {
        StringBuilder b = new StringBuilder();
        switch (action) {
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
        if (action != WAIT) b.append(" · ").append(duration).append(" ms down");
        if (cond == COND_COLOR) b.append(" · color ").append(String.format("#%06X", color & 0xFFFFFF));
        else if (cond == COND_IMAGE) b.append(" · image");
        return b.toString();
    }
}
