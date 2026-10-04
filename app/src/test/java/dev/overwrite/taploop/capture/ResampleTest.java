package dev.overwrite.taploop.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ResampleTest {
    @Test
    public void upscaleDoublesPixels() {
        byte[] src = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        byte[] out = ScreenGrabber.resample(src, 2, 4);
        assertEquals(4 * 4 * 3, out.length);
        assertArrayEquals(new byte[]{1, 2, 3}, new byte[]{out[0], out[1], out[2]});
        assertArrayEquals(new byte[]{1, 2, 3}, new byte[]{out[3], out[4], out[5]});
        assertArrayEquals(new byte[]{4, 5, 6}, new byte[]{out[6], out[7], out[8]});
        // last pixel comes from the bottom right source pixel
        assertArrayEquals(new byte[]{10, 11, 12}, new byte[]{out[45], out[46], out[47]});
    }

    @Test
    public void sameSizeIsCopy() {
        byte[] src = new byte[3 * 3 * 3];
        for (int i = 0; i < src.length; i++) src[i] = (byte) i;
        assertArrayEquals(src, ScreenGrabber.resample(src, 3, 3));
    }
}
