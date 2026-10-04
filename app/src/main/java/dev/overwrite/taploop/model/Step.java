package dev.overwrite.taploop.model;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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

    /** 2 = double tap */
    public int taps = 1;
    /** 2 = second finger at fx,fy (and fx2,fy2 for swipes) */
    public int fingers = 1;
    public int fx, fy, fx2, fy2;
    /** recorded swipe as x,y,t triples (t = ms since down), null = straight line */
    public int[] path;

    public Step copy() {
        Step s = new Step();
        s.action = action; s.x = x; s.y = y; s.x2 = x2; s.y2 = y2;
        s.delay = delay; s.duration = duration;
        s.cond = cond; s.color = color; s.tolerance = tolerance; s.timeout = timeout;
        s.onMiss = onMiss; s.searchRadius = searchRadius;
        s.patchSize = patchSize; s.patch = patch;
        s.taps = taps; s.fingers = fingers;
        s.fx = fx; s.fy = fy; s.fx2 = fx2; s.fy2 = fy2;
        s.path = path == null ? null : path.clone();
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
            o.put("patch", Base64.encodeToString(patch, Base64.NO_WRAP));
        }
        if (taps != 1) o.put("taps", taps);
        if (fingers > 1) {
            o.put("fingers", fingers);
            o.put("fx", fx).put("fy", fy).put("fx2", fx2).put("fy2", fy2);
        }
        if (path != null) {
            JSONArray arr = new JSONArray();
            for (int v : path) arr.put(v);
            o.put("path", arr);
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
        String p = o.optString("patch", null);
        if (p != null && !p.isEmpty()) {
            s.patch = Base64.decode(p, Base64.NO_WRAP);
            s.patchSize = o.optInt("psize");
        }
        if (s.cond == COND_IMAGE && s.patch == null) s.cond = COND_NONE;
        s.taps = Math.max(1, Math.min(2, o.optInt("taps", 1)));
        s.fingers = Math.max(1, Math.min(2, o.optInt("fingers", 1)));
        s.fx = o.optInt("fx"); s.fy = o.optInt("fy");
        s.fx2 = o.optInt("fx2"); s.fy2 = o.optInt("fy2");
        JSONArray arr = o.optJSONArray("path");
        if (arr != null && arr.length() >= 6 && arr.length() % 3 == 0) {
            s.path = new int[arr.length()];
            for (int i = 0; i < s.path.length; i++) s.path[i] = arr.optInt(i);
        }
        return s;
    }

    public String summary() {
        StringBuilder b = new StringBuilder();
        switch (action) {
            case SWIPE: b.append(path != null ? "Path " : "Swipe ").append(x).append(',').append(y)
                    .append(" → ").append(x2).append(',').append(y2); break;
            case WAIT: b.append("Wait"); break;
            default: b.append(taps > 1 ? "Double tap " : duration >= 400 ? "Hold " : "Tap ")
                    .append(x).append(',').append(y);
        }
        if (action != WAIT && fingers > 1 && path == null) b.append(" (2 fingers)");
        return b.toString();
    }

    public String details() {
        StringBuilder b = new StringBuilder();
        b.append("after ").append(delay).append(" ms");
        if (action != WAIT) b.append(" · ").append(duration).append(" ms down");
        if (action == SWIPE && path != null) b.append(" · ").append(path.length / 3).append(" points");
        if (cond == COND_COLOR) b.append(" · color ").append(String.format("#%06X", color & 0xFFFFFF));
        else if (cond == COND_IMAGE) b.append(" · image");
        return b.toString();
    }
}
