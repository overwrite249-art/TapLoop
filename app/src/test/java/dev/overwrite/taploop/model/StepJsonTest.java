package dev.overwrite.taploop.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class StepJsonTest {

    private static Step roundTrip(Step s) throws Exception {
        return Step.fromJson(new JSONObject(s.toJson().toString()));
    }

    @Test
    public void tapRoundTrip() throws Exception {
        Step s = new Step();
        s.x = 120; s.y = 840; s.x2 = 120; s.y2 = 840;
        s.delay = 350; s.duration = 55;
        Step r = roundTrip(s);
        assertEquals(Step.TAP, r.action);
        assertEquals(120, r.x);
        assertEquals(840, r.y);
        assertEquals(350, r.delay);
        assertEquals(55, r.duration);
        assertEquals(Step.COND_NONE, r.cond);
        assertNull(r.patch);
        assertEquals(-1, r.recGap);
    }

    @Test
    public void swipeWithColorRoundTrip() throws Exception {
        Step s = new Step();
        s.action = Step.SWIPE;
        s.x = 10; s.y = 20; s.x2 = 300; s.y2 = 1400;
        s.duration = 600;
        s.cond = Step.COND_COLOR;
        s.color = 0x12AB34;
        s.tolerance = 40;
        s.timeout = 9000;
        s.onMiss = Step.MISS_STOP;
        Step r = roundTrip(s);
        assertEquals(Step.SWIPE, r.action);
        assertEquals(300, r.x2);
        assertEquals(1400, r.y2);
        assertEquals(600, r.duration);
        assertEquals(Step.COND_COLOR, r.cond);
        assertEquals(0x12AB34, r.color);
        assertEquals(40, r.tolerance);
        assertEquals(9000, r.timeout);
        assertEquals(Step.MISS_STOP, r.onMiss);
    }

    @Test
    public void imageRoundTrip() throws Exception {
        Step s = new Step();
        s.cond = Step.COND_IMAGE;
        s.patchSize = 4;
        s.patch = new byte[4 * 4 * 3];
        for (int i = 0; i < s.patch.length; i++) s.patch[i] = (byte) (i * 7);
        s.patchScale = 0.25f;
        s.searchRadius = 80;
        s.recGap = 1234;
        Step r = roundTrip(s);
        assertEquals(Step.COND_IMAGE, r.cond);
        assertEquals(4, r.patchSize);
        assertArrayEquals(s.patch, r.patch);
        assertEquals(0.25f, r.patchScale, 0.0001f);
        assertEquals(80, r.searchRadius);
        assertEquals(1234, r.recGap);
    }

    @Test
    public void copyKeepsNewFields() {
        Step s = new Step();
        s.patchScale = 1f;
        s.recGap = 77;
        Step c = s.copy();
        assertEquals(1f, c.patchScale, 0f);
        assertEquals(77, c.recGap);
    }

    @Test
    public void emptyObjectGivesDefaults() throws Exception {
        Step r = Step.fromJson(new JSONObject("{}"));
        Step d = new Step();
        assertEquals(Step.TAP, r.action);
        assertEquals(d.duration, r.duration);
        assertEquals(d.tolerance, r.tolerance);
        assertEquals(d.timeout, r.timeout);
        assertEquals(Step.MISS_TAP, r.onMiss);
        assertEquals(0.5f, r.patchScale, 0f);
        assertEquals(-1, r.recGap);
    }

    @Test
    public void imageWithoutPatchFallsBackToTiming() throws Exception {
        Step r = Step.fromJson(new JSONObject("{\"cond\":2}"));
        assertEquals(Step.COND_NONE, r.cond);
    }

    @Test
    public void brokenPatchDoesNotThrow() throws Exception {
        Step r = Step.fromJson(new JSONObject("{\"cond\":2,\"psize\":16,\"patch\":\"%%not base64%%\"}"));
        assertEquals(Step.COND_NONE, r.cond);
        assertNull(r.patch);
    }

    @Test
    public void oldStepsDontGetNewKeys() throws Exception {
        // a plain step should serialize exactly like 1.0 did
        JSONObject o = new Step().toJson();
        assertFalse(o.has("gap"));
        assertFalse(o.has("pscale"));
        assertTrue(o.has("tol"));
    }
}
