package dev.overwrite.taploop.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Base64;

public class Step {
    public static final int TAP = 0;
    public static final int SWIPE = 1;
    public static final int WAIT = 2;
    public static final int GOTO = 3;
    public static final int STOP = 4;
    /** look for a template on screen and tap where it is, has its own dialog */
    public static final int FIND_IMAGE = 5;

    public static final int COND_NONE = 0;
    public static final int COND_COLOR = 1;
    public static final int COND_IMAGE = 2;
    public static final int COND_COLOR_GONE = 3;
    public static final int COND_IMAGE_GONE = 4;
    public static final int COND_EITHER = 5;
    public static final int COND_BOTH = 6;

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
    /** how often to look while waiting, 0 = every new frame */
    public long interval;
    /** the match has to hold this long before we tap */
    public long stable;

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

    /** 2 = double tap */
    public int taps = 1;
    /** 2 = second finger at fx,fy (and fx2,fy2 for swipes) */
    public int fingers = 1;
    public int fx, fy, fx2, fy2;
    /** recorded swipe as x,y,t triples (t = ms since down), null = straight line */
    public int[] path;
    /** capture scale the patch was taken at, older macros were always 0.5 */
    public float patchScale = 0.5f;
    /** smart record: the real gap before this tap, used when capture is off. -1 = unknown */
    public long recGap = -1;

    // find image: RGB template, tplScale = template px per frame px (< 1 if it got shrunk)
    public byte[] tpl;
    public int tplW, tplH;
    public float tplScale = 1f;
    /** capture scale of the screenshot it was cut from */
    public float tplFrame = 0.5f;
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
        s.interval = interval; s.stable = stable;
        s.label = label; s.repeat = repeat; s.jitter = jitter; s.offset = offset;
        s.goFound = goFound; s.goMiss = goMiss;
        s.taps = taps; s.fingers = fingers;
        s.fx = fx; s.fy = fy; s.fx2 = fx2; s.fy2 = fy2;
        s.path = path == null ? null : path.clone();
        s.patchScale = patchScale; s.recGap = recGap;
        s.tpl = tpl; s.tplW = tplW; s.tplH = tplH; s.tplScale = tplScale; s.tplFrame = tplFrame;
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
        if (interval > 0) o.put("interval", interval);
        if (stable > 0) o.put("stable", stable);
        if (!label.isEmpty()) o.put("label", label);
        if (repeat != 1) o.put("repeat", repeat);
        if (jitter > 0) o.put("jitter", jitter);
        if (offset > 0) o.put("offset", offset);
        if (goFound >= 0) o.put("goFound", goFound);
        if (goMiss >= 0) o.put("goMiss", goMiss);
        if (patch != null) {
            o.put("psize", patchSize);
            o.put("patch", Base64.getEncoder().encodeToString(patch));
            o.put("pscale", (double) patchScale);
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
        if (recGap >= 0) o.put("gap", recGap);
        if (tpl != null) {
            o.put("tpl", Base64.getEncoder().encodeToString(tpl));
            o.put("tw", tplW).put("th", tplH).put("ts", (double) tplScale)
                    .put("tf", (double) tplFrame);
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
        s.label = o.optString("label", "");
        s.repeat = Math.max(1, o.optInt("repeat", 1));
        s.jitter = Math.max(0, o.optLong("jitter"));
        s.offset = Math.max(0, o.optInt("offset"));
        s.goFound = o.optInt("goFound", -1);
        s.goMiss = o.optInt("goMiss", -1);
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
        s.interval = Math.max(0, o.optLong("interval"));
        s.stable = Math.max(0, o.optLong("stable"));
        if (s.patch == null) {
            if (s.cond == COND_IMAGE || s.cond == COND_IMAGE_GONE) s.cond = COND_NONE;
            else if (s.cond == COND_EITHER || s.cond == COND_BOTH) s.cond = COND_COLOR;
        }
        s.taps = Math.max(1, Math.min(2, o.optInt("taps", 1)));
        s.fingers = Math.max(1, Math.min(2, o.optInt("fingers", 1)));
        s.fx = o.optInt("fx"); s.fy = o.optInt("fy");
        s.fx2 = o.optInt("fx2"); s.fy2 = o.optInt("fy2");
        JSONArray arr = o.optJSONArray("path");
        if (arr != null && arr.length() >= 6 && arr.length() % 3 == 0) {
            s.path = new int[arr.length()];
            for (int i = 0; i < s.path.length; i++) s.path[i] = arr.optInt(i);
        }
        s.recGap = o.optLong("gap", -1);
        String t = o.optString("tpl", null);
        if (t != null && !t.isEmpty()) {
            try {
                s.tpl = Base64.getMimeDecoder().decode(t);
            } catch (IllegalArgumentException ignored) {
                // broken template, treated as missing below
            }
            s.tplW = o.optInt("tw"); s.tplH = o.optInt("th");
            s.tplScale = (float) o.optDouble("ts", 1);
            double tf = o.optDouble("tf", 0.5);
            s.tplFrame = tf > 0.05 && tf <= 1 ? (float) tf : 0.5f;
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
        if (!label.isEmpty()) b.append(label).append(": ");
        switch (action) {
            case GOTO: b.append("Go to step ").append(goFound >= 0 ? String.valueOf(goFound + 1) : "?"); break;
            case STOP: b.append("Stop macro"); break;
            case SWIPE: b.append(path != null ? "Path " : "Swipe ").append(x).append(',').append(y)
                    .append(" → ").append(x2).append(',').append(y2); break;
            case WAIT: b.append("Wait"); break;
            case FIND_IMAGE:
                b.append("Find image");
                if (offX != 0 || offY != 0) b.append(", tap ").append(offX).append(',').append(offY).append(" off");
                break;
            default: b.append(taps > 1 ? "Double tap " : duration >= 400 ? "Hold " : "Tap ")
                    .append(x).append(',').append(y);
        }
        if (action != WAIT && fingers > 1 && path == null) b.append(" (2 fingers)");
        return b.toString();
    }

    public String details() {
        StringBuilder b = new StringBuilder();
        b.append("after ").append(delay).append(" ms");
        if (jitter > 0) b.append(" ±").append(jitter);
        if (action == TAP || action == SWIPE || action == FIND_IMAGE) b.append(" · ").append(duration).append(" ms down");
        if (action == FIND_IMAGE) {
            b.append(" · ").append(match).append("% · ").append(areaW > 0 ? "in area" : "whole screen");
            return b.toString();
        }
        if (repeat > 1) b.append(" · x").append(repeat);
        if (offset > 0) b.append(" · ±").append(offset).append(" px");
        String hex = String.format("#%06X", color & 0xFFFFFF);
        switch (cond) {
            case COND_COLOR: b.append(" · color ").append(hex); break;
            case COND_COLOR_GONE: b.append(" · no ").append(hex); break;
            case COND_IMAGE: b.append(" · image"); break;
            case COND_IMAGE_GONE: b.append(" · image gone"); break;
            case COND_EITHER: b.append(" · color or image"); break;
            case COND_BOTH: b.append(" · color + image"); break;
            default: break;
        }
        if (cond != COND_NONE && stable > 0) b.append(" · stable ").append(stable).append(" ms");
        if (cond != COND_NONE && action != GOTO && goFound >= 0) b.append(" · found → ").append(goFound + 1);
        if (cond != COND_NONE && goMiss >= 0) b.append(" · else → ").append(goMiss + 1);
        if (action == SWIPE && path != null) b.append(" · ").append(path.length / 3).append(" points");
        return b.toString();
    }
}
