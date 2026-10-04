package dev.overwrite.taploop.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class ScreenFitTest {
    @Test
    public void unknownSizeIsUntouched() {
        assertSame(ScreenFit.SAME, ScreenFit.of(0, 0, 1080, 2400));
    }

    @Test
    public void sameSizeIsUntouched() {
        ScreenFit f = ScreenFit.of(1080, 2400, 1080, 2400);
        assertNull(f.warning);
        assertEquals(500, f.x(500));
    }

    @Test
    public void rotatedOnlyWarns() {
        ScreenFit f = ScreenFit.of(1080, 2400, 2400, 1080);
        assertNotNull(f.warning);
        assertEquals(500, f.x(500));
        assertEquals(700, f.y(700));
    }

    @Test
    public void otherResolutionScales() {
        ScreenFit f = ScreenFit.of(1080, 2400, 720, 1600);
        assertNotNull(f.warning);
        assertEquals(360, f.x(540));
        assertEquals(800, f.y(1200));
    }
}
