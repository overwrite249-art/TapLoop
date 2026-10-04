package dev.overwrite.taploop.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Macro {
    public String id;
    public String name = "Untitled";
    /** 0 = loop forever */
    public int loops = 1;
    /** playback speed in percent, only scales plain delays */
    public int speed = 100;
    public long loopDelay = 0;
    /** screen size the macro was recorded at, 0 = unknown (older macros) */
    public int screenW, screenH;
    public final List<Step> steps = new ArrayList<>();

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", name).put("loops", loops).put("speed", speed).put("loopDelay", loopDelay);
        JSONArray arr = new JSONArray();
        for (Step s : steps) arr.put(s.toJson());
        o.put("steps", arr);
        if (screenW > 0 && screenH > 0) o.put("screenW", screenW).put("screenH", screenH);
        return o;
    }

    public static Macro fromJson(String id, JSONObject o) {
        Macro m = new Macro();
        m.id = id;
        m.name = o.optString("name", "Untitled");
        m.loops = o.optInt("loops", 1);
        m.speed = Math.max(10, o.optInt("speed", 100));
        m.loopDelay = o.optLong("loopDelay", 0);
        m.screenW = Math.max(0, o.optInt("screenW", 0));
        m.screenH = Math.max(0, o.optInt("screenH", 0));
        JSONArray arr = o.optJSONArray("steps");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null) m.steps.add(Step.fromJson(s));
            }
        }
        return m;
    }

    public long totalTime() {
        long t = 0;
        for (Step s : steps) t += s.delay + (s.action == Step.WAIT ? 0 : s.duration);
        return t;
    }
}
