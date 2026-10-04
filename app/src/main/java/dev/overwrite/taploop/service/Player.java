package dev.overwrite.taploop.service;

import android.os.SystemClock;

import dev.overwrite.taploop.capture.ScreenGrabber;
import dev.overwrite.taploop.model.Macro;
import dev.overwrite.taploop.model.Step;

class Player extends Thread {
    interface Done {
        void onDone(String message);
    }

    private final TapService svc;
    private final Macro macro;
    private final Done done;
    private volatile boolean cancelled;
    private volatile boolean paused;
    private final Object pauseLock = new Object();

    Player(TapService svc, Macro macro, Done done) {
        super("player");
        this.svc = svc;
        this.macro = macro;
        this.done = done;
    }

    void cancel() {
        cancelled = true;
        interrupt();
    }

    boolean isPaused() {
        return paused;
    }

    void setPaused(boolean p) {
        synchronized (pauseLock) {
            paused = p;
            pauseLock.notifyAll();
        }
    }

    /** blocks while paused, returns how long we sat there */
    private long holdIfPaused() throws InterruptedException {
        if (!paused) return 0;
        long t0 = SystemClock.uptimeMillis();
        synchronized (pauseLock) {
            while (paused && !cancelled) pauseLock.wait();
        }
        if (cancelled) throw new InterruptedException();
        return SystemClock.uptimeMillis() - t0;
    }

    /** like Thread.sleep but the clock stops while paused */
    private void doze(long ms) throws InterruptedException {
        long end = SystemClock.uptimeMillis() + ms;
        while (true) {
            end += holdIfPaused();
            long left = end - SystemClock.uptimeMillis();
            if (left <= 0) return;
            synchronized (pauseLock) {
                if (!paused) pauseLock.wait(left);
            }
        }
    }

    @Override
    public void run() {
        String msg = null;
        try {
            int loop = 0;
            while (!cancelled && (macro.loops <= 0 || loop < macro.loops)) {
                loop++;
                for (int i = 0; i < macro.steps.size() && !cancelled; i++) {
                    holdIfPaused();
                    Step s = macro.steps.get(i);
                    svc.onProgress(loop, macro.loops, i + 1, macro.steps.size());

                    long d = s.delay * 100 / Math.max(10, macro.speed);
                    if (d > 0) doze(d);

                    int dx = 0, dy = 0;
                    if (s.cond != Step.COND_NONE) {
                        ScreenGrabber g = ScreenGrabber.get();
                        if (g != null) {
                            int[] hit = waitFor(g, s);
                            if (hit == null) {
                                if (s.onMiss == Step.MISS_SKIP) continue;
                                if (s.onMiss == Step.MISS_STOP) {
                                    msg = "Stopped, step " + (i + 1) + " didn't show up";
                                    return;
                                }
                            } else {
                                dx = hit[0] - s.x;
                                dy = hit[1] - s.y;
                            }
                        }
                    }
                    if (s.action == Step.WAIT) continue;
                    svc.playGesture(s.action, s.x + dx, s.y + dy, s.x2 + dx, s.y2 + dy, s.duration);
                }
                if (macro.loopDelay > 0 && !cancelled) doze(macro.loopDelay);
            }
            if (!cancelled) msg = "Done";
        } catch (InterruptedException ignored) {
            // stop pressed
        } finally {
            done.onDone(msg);
        }
    }

    /** returns the matched screen point, or null on timeout */
    private int[] waitFor(ScreenGrabber g, Step s) throws InterruptedException {
        // start counting once our own tap marker is gone from the screen
        long clear = svc.overlayClearAt();
        long end = Math.max(SystemClock.uptimeMillis(), clear) + s.timeout;
        long seen = -1;
        while (!cancelled) {
            end += holdIfPaused();
            long now = SystemClock.uptimeMillis();
            if (now < clear) {
                // the marker might still be in the frame
                Thread.sleep(Math.min(clear - now, 50));
                continue;
            }
            long ft = g.frameTime();
            if (ft != seen) {
                seen = ft;
                if (s.cond == Step.COND_COLOR) {
                    int c = g.colorAt(s.x, s.y);
                    if (c != -1 && ScreenGrabber.colorDistance(c, s.color) <= s.tolerance) {
                        return new int[]{s.x, s.y};
                    }
                } else {
                    int[] p = g.findPatch(s.patch, s.patchSize, s.x, s.y, s.searchRadius, s.tolerance);
                    if (p != null) return p;
                }
            }
            long left = end - SystemClock.uptimeMillis();
            if (left <= 0) return null;
            if (!g.hasFrame()) {
                // capture got stopped mid-run
                return s.onMiss == Step.MISS_STOP ? null : new int[]{s.x, s.y};
            }
            g.awaitFrame(seen, Math.min(50, left));
        }
        throw new InterruptedException();
    }
}
