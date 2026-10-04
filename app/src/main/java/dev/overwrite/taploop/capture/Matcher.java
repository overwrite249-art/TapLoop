package dev.overwrite.taploop.capture;

import android.os.SystemClock;

import java.util.function.BooleanSupplier;

import dev.overwrite.taploop.model.Step;

/** Decides if a step's condition is met on the current frame and waits for it. */
public final class Matcher {
    /** if an image match jumps further than this (screen px) the stable timer restarts */
    private static final int JITTER = 8;
    /** waitFor result when screen capture went away */
    public static final int[] LOST = new int[0];

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
        return check(g, s, s.x, s.y);
    }

    /** same, with x,y being the step's point already fitted to this screen */
    public static int[] check(ScreenGrabber g, Step s, int x, int y) {
        if (!g.hasFrame()) return null;
        int[] here = {x, y};
        switch (s.cond) {
            case Step.COND_NONE:
                return here;
            case Step.COND_COLOR:
                return colorThere(g, s, x, y) ? here : null;
            case Step.COND_COLOR_GONE: {
                int c = g.colorAt(x, y);
                return c != -1 && ScreenGrabber.colorDistance(c, s.color) > s.tolerance ? here : null;
            }
            case Step.COND_IMAGE:
                return image(g, s, x, y);
            case Step.COND_IMAGE_GONE:
                return image(g, s, x, y) == null ? here : null;
            case Step.COND_EITHER: {
                int[] p = image(g, s, x, y);
                if (p != null) return p;
                return colorThere(g, s, x, y) ? here : null;
            }
            case Step.COND_BOTH: {
                if (!colorThere(g, s, x, y)) return null;
                return image(g, s, x, y);
            }
            default:
                // something newer than this build, don't block on it
                return here;
        }
    }

    private static boolean colorThere(ScreenGrabber g, Step s, int x, int y) {
        int c = g.colorAt(x, y);
        return c != -1 && ScreenGrabber.colorDistance(c, s.color) <= s.tolerance;
    }

    private static int[] image(ScreenGrabber g, Step s, int x, int y) {
        return g.findPatch(s.patch, s.patchSize, s.patchScale, x, y, s.searchRadius, s.tolerance);
    }

    /**
     * Waits until the condition holds (for s.stable ms if set). Returns the
     * point to tap, null if it timed out, or LOST if capture went away.
     */
    public static int[] waitFor(ScreenGrabber g, Step s, int x, int y, BooleanSupplier cancelled)
            throws InterruptedException {
        long end = SystemClock.uptimeMillis() + s.timeout;
        long seen = -1;
        long since = -1;
        int[] held = null;
        while (!cancelled.getAsBoolean()) {
            long ft = g.frameTime();
            if (ft != seen) {
                seen = ft;
                int[] p = check(g, s, x, y);
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
            if (g.isStopped()) {
                // capture got stopped mid-run, or restarted with a new grabber
                ScreenGrabber n = ScreenGrabber.get();
                if (n == null || n.isStopped()) return LOST;
                g = n;
                seen = -1;
                continue;
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
