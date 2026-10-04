package dev.overwrite.taploop.capture;

import android.os.SystemClock;

import java.util.function.BooleanSupplier;

import dev.overwrite.taploop.model.Step;

/** Decides if a step's condition is met on the current frame and waits for it. */
public final class Matcher {
    /** if an image match jumps further than this (screen px) the stable timer restarts */
    private static final int JITTER = 8;

    private Matcher() {
    }

    public static boolean usesColor(int cond) {
        return cond == Step.COND_COLOR || cond == Step.COND_COLOR_GONE
                || cond == Step.COND_EITHER || cond == Step.COND_BOTH;
    }

    public static boolean usesImage(int cond) {
        return cond == Step.COND_IMAGE || cond == Step.COND_IMAGE_GONE
                || cond == Step.COND_EITHER || cond == Step.COND_BOTH;
    }

    /** screen point to tap if the condition holds right now, otherwise null */
    public static int[] check(ScreenGrabber g, Step s) {
        if (!g.hasFrame()) return null;
        int[] here = {s.x, s.y};
        switch (s.cond) {
            case Step.COND_NONE:
                return here;
            case Step.COND_COLOR:
                return colorThere(g, s) ? here : null;
            case Step.COND_COLOR_GONE: {
                int c = g.colorAt(s.x, s.y);
                return c != -1 && ScreenGrabber.colorDistance(c, s.color) > s.tolerance ? here : null;
            }
            case Step.COND_IMAGE:
                return image(g, s);
            case Step.COND_IMAGE_GONE:
                return image(g, s) == null ? here : null;
            case Step.COND_EITHER: {
                int[] p = image(g, s);
                if (p != null) return p;
                return colorThere(g, s) ? here : null;
            }
            case Step.COND_BOTH: {
                if (!colorThere(g, s)) return null;
                return image(g, s);
            }
            default:
                // something newer than this build, don't block on it
                return here;
        }
    }

    private static boolean colorThere(ScreenGrabber g, Step s) {
        int c = g.colorAt(s.x, s.y);
        return c != -1 && ScreenGrabber.colorDistance(c, s.color) <= s.tolerance;
    }

    private static int[] image(ScreenGrabber g, Step s) {
        return g.findPatch(s.patch, s.patchSize, s.x, s.y, s.searchRadius, s.tolerance);
    }

    /**
     * Waits until the condition holds (for s.stable ms if set). Returns the
     * point to tap, or null if it timed out.
     */
    public static int[] waitFor(ScreenGrabber g, Step s, BooleanSupplier cancelled)
            throws InterruptedException {
        long end = SystemClock.uptimeMillis() + s.timeout;
        long seen = -1;
        long since = -1;
        int[] held = null;
        while (!cancelled.getAsBoolean()) {
            long ft = g.frameTime();
            if (ft != seen) {
                seen = ft;
                int[] p = check(g, s);
                if (p == null) {
                    since = -1;
                    held = null;
                } else {
                    if (held == null || Math.abs(p[0] - held[0]) > JITTER
                            || Math.abs(p[1] - held[1]) > JITTER) {
                        since = SystemClock.uptimeMillis();
                    }
                    held = p;
                }
            }
            long now = SystemClock.uptimeMillis();
            // no new frame means the screen didn't change, so a match keeps holding
            if (held != null && now - since >= s.stable) return held;

            long left = end - now;
            // a match that's already holding gets to finish even past the timeout
            if (left <= 0 && held == null) return null;
            if (!g.hasFrame()) {
                // capture got stopped mid-run
                return s.onMiss == Step.MISS_STOP ? null : new int[]{s.x, s.y};
            }
            long wait = s.interval > 0 ? s.interval : 50;
            if (left > 0) wait = Math.min(wait, left);
            if (held != null) wait = Math.min(wait, s.stable - (now - since));
            wait = Math.max(1, wait);
            if (s.interval > 0) Thread.sleep(wait);
            else g.awaitFrame(seen, wait);
        }
        throw new InterruptedException();
    }
}
