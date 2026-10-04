package dev.overwrite.taploop.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.json.JSONObject;
import org.junit.Test;

public class MacroJsonTest {
    // written by TapLoop 1.0: a plain tap, a smart image step and a smart color step
    private static final String V1_0 = "{\"name\":\"Smart Mar 3, 14:02\",\"loops\":0,\"speed\":150,"
            + "\"loopDelay\":800,\"steps\":["
            + "{\"action\":0,\"x\":540,\"y\":1200,\"x2\":540,\"y2\":1200,\"delay\":420,\"duration\":48,"
            + "\"cond\":0,\"color\":0,\"tol\":28,\"timeout\":5000,\"miss\":0,\"radius\":0},"
            + "{\"action\":0,\"x\":300,\"y\":900,\"x2\":300,\"y2\":900,\"delay\":0,\"duration\":61,"
            + "\"cond\":2,\"color\":16711680,\"tol\":28,\"timeout\":4400,\"miss\":1,\"radius\":60,"
            + "\"psize\":2,\"patch\":\"AAECAwQFBgcICQoL\"},"
            + "{\"action\":1,\"x\":100,\"y\":1500,\"x2\":900,\"y2\":1500,\"delay\":0,\"duration\":300,"
            + "\"cond\":1,\"color\":65280,\"tol\":20,\"timeout\":3000,\"miss\":2,\"radius\":0}"
            + "]}";

    @Test
    public void loadsV1Macro() throws Exception {
        Macro m = Macro.fromJson("m1", new JSONObject(V1_0));
        assertEquals("m1", m.id);
        assertEquals("Smart Mar 3, 14:02", m.name);
        assertEquals(0, m.loops);
        assertEquals(150, m.speed);
        assertEquals(800, m.loopDelay);
        assertEquals(0, m.screenW);
        assertEquals(0, m.screenH);
        assertEquals(3, m.steps.size());

        Step tap = m.steps.get(0);
        assertEquals(Step.TAP, tap.action);
        assertEquals(540, tap.x);
        assertEquals(420, tap.delay);
        assertEquals(-1, tap.recGap);

        Step img = m.steps.get(1);
        assertEquals(Step.COND_IMAGE, img.cond);
        assertEquals(2, img.patchSize);
        assertArrayEquals(new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}, img.patch);
        // 1.0 always captured at half res
        assertEquals(0.5f, img.patchScale, 0f);
        assertEquals(Step.MISS_SKIP, img.onMiss);
        assertEquals(60, img.searchRadius);
        assertEquals(4400, img.timeout);

        Step sw = m.steps.get(2);
        assertEquals(Step.SWIPE, sw.action);
        assertEquals(Step.COND_COLOR, sw.cond);
        assertEquals(0x00FF00, sw.color);
        assertEquals(20, sw.tolerance);
        assertEquals(Step.MISS_STOP, sw.onMiss);
    }

    @Test
    public void v1MacroSavesBackTheSame() throws Exception {
        Macro m = Macro.fromJson("m1", new JSONObject(V1_0));
        JSONObject out = m.toJson();
        assertFalse(out.has("screenW"));
        assertFalse(out.has("screenH"));
        Macro again = Macro.fromJson("m1", new JSONObject(out.toString()));
        assertEquals(m.totalTime(), again.totalTime());
        assertEquals(m.steps.size(), again.steps.size());
        for (int i = 0; i < m.steps.size(); i++) {
            assertEquals(m.steps.get(i).toJson().toString(), again.steps.get(i).toJson().toString());
        }
    }

    @Test
    public void screenSizeRoundTrip() throws Exception {
        Macro m = new Macro();
        m.name = "rot";
        m.screenW = 1080;
        m.screenH = 2400;
        m.steps.add(new Step());
        Macro r = Macro.fromJson("x", new JSONObject(m.toJson().toString()));
        assertEquals(1080, r.screenW);
        assertEquals(2400, r.screenH);
        assertEquals("rot", r.name);
        assertEquals(1, r.steps.size());
    }

    @Test
    public void junkValuesAreSane() throws Exception {
        Macro m = Macro.fromJson("x", new JSONObject(
                "{\"speed\":0,\"screenW\":-5,\"steps\":[1,null,{\"x\":3}]}"));
        assertEquals(10, m.speed);
        assertEquals(0, m.screenW);
        assertEquals(1, m.steps.size());
        assertEquals("Untitled", m.name);
    }

    @Test
    public void missingStepsIsEmpty() throws Exception {
        Macro m = Macro.fromJson("x", new JSONObject("{\"name\":\"a\"}"));
        assertEquals(0, m.steps.size());
        assertEquals(1, m.loops);
    }
}
