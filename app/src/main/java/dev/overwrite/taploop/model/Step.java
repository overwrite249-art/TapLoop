package dev.overwrite.taploop.model;

import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

public class Step {
    public static final int TAP = 0;
    public static final int SWIPE = 1;
    public static final int WAIT = 2;
    /** look for a template on screen and tap where it is, has its own dialog */
    public static final int FIND_IMAGE = 3;

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

    // find image: RGB template, tplScale = template px per frame px (< 1 if it got shrunk)
    public byte[] tpl;
    public int tplW, tplH;
    public float tplScale = 1f;
    /** search area in screen px, areaW 0 = whole screen */
    public int areaX, areaY, areaW, areaH;
    /** tap this far from the template center */
    public int offX, offY;
    /** how similar it has to be, percent */
    public int match = 85;

    public Step copy() {
        Step s = new Step();
        s.action = action; s.x = x; s.y = y; s.x2 = x2; s.y2 = y2;
        s.delay = delay; s.duration = duration;
        s.cond = cond; s.color = color; s.tolerance = tolerance; s.timeout = timeout;
        s.onMiss = onMiss; s.searchRadius = searchRadius;
        s.patchSize = patchSize; s.patch = patch;
        s.tpl = tpl; s.tplW = tplW; s.tplH = tplH; s.tplScale = tplScale;
        s.areaX = areaX; s.areaY = areaY; s.areaW = areaW; s.areaH = areaH;
        s.offX = offX; s.offY = offY; s.match = match;
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
        if (tpl != null) {
            o.put("tpl", Base64.encodeToString(tpl, Base64.NO_WRAP));
            o.put("tw", tplW).put("th", tplH).put("ts", (double) tplScale);
            o.put("ax", areaX).put("ay", areaY).put("aw", areaW).put("ah", areaH);
            o.put("ox", offX).put("oy", offY).put("match", match);
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
        String t = o.optString("tpl", null);
        if (t != null && !t.isEmpty()) {
            try {
                s.tpl = Base64.decode(t, Base64.NO_WRAP);
            } catch (IllegalArgumentException ignored) {
                // broken template, treated as missing below
            }
            s.tplW = o.optInt("tw"); s.tplH = o.optInt("th");
            s.tplScale = (float) o.optDouble("ts", 1);
            s.areaX = o.optInt("ax"); s.areaY = o.optInt("ay");
            s.areaW = o.optInt("aw"); s.areaH = o.optInt("ah");
            s.offX = o.optInt("ox"); s.offY = o.optInt("oy");
            s.match = o.optInt("match", 85);
        }
        if (s.action == FIND_IMAGE && !s.hasTemplate()) s.action = TAP;
        return s;
    }

    public boolean hasTemplate() {
        return tpl != null && tplW > 0 && tplH > 0 && tpl.length >= tplW * tplH * 3;
    }

    public String summary() {
        StringBuilder b = new StringBuilder();
        switch (action) {
            case SWIPE: b.append("Swipe ").append(x).append(',').append(y)
                    .append(" → ").append(x2).append(',').append(y2); break;
            case WAIT: b.append("Wait"); break;
            case FIND_IMAGE:
                b.append("Find image");
                if (offX != 0 || offY != 0) b.append(", tap ").append(offX).append(',').append(offY).append(" off");
                break;
            default: b.append(duration >= 400 ? "Hold " : "Tap ").append(x).append(',').append(y);
        }
        return b.toString();
    }

    public String details() {
        StringBuilder b = new StringBuilder();
        b.append("after ").append(delay).append(" ms");
        if (action != WAIT) b.append(" · ").append(duration).append(" ms down");
        if (action == FIND_IMAGE) {
            b.append(" · ").append(match).append("% · ").append(areaW > 0 ? "in area" : "whole screen");
            return b.toString();
        }
        if (cond == COND_COLOR) b.append(" · color ").append(String.format("#%06X", color & 0xFFFFFF));
        else if (cond == COND_IMAGE) b.append(" · image");
        return b.toString();
    }
}
