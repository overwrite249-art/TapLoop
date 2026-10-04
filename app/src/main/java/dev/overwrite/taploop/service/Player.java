package dev.overwrite.taploop.service;

import dev.overwrite.taploop.capture.Matcher;
import android.os.SystemClock;

import java.util.Random;

import dev.overwrite.taploop.capture.ScreenGrabber;
import dev.overwrite.taploop.capture.TemplateMatcher;
import dev.overwrite.taploop.model.Macro;
import dev.overwrite.taploop.model.Step;
import dev.overwrite.taploop.trigger.TriggerPrefs;

class Player extends Thread {
    interface Done {
        void onDone(String message);
    }

    // a jump that lands this soon after the last one gets slowed down, so a
    // "go to" loop with nothing in it can't spin the cpu
    private static final long MIN_JUMP_GAP = 10;
    // special results from runStep
    private static final int HALT = -2;
    private static final int HALT_MISS = -3;
    // this many gestures in a row not going through means the service is gone
    private static final int MAX_FAILED = 3;

    private final TapService svc;
    private final Macro macro;
    private final Done done;
    private final Random rnd = new Random();
    private volatile boolean cancelled;
    private long deadline;
    private long lastJump;
    private volatile String stopReason;
    private volatile boolean paused;
    private final Object pauseLock = new Object();
    private final boolean hadCapture = ScreenGrabber.get() != null;
    private boolean captureWarned;
    private String fitWarning;
    private ScreenFit fit = ScreenFit.SAME;
    private int failed;

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

    /** stop with a message for the toast */
    void cancel(String reason) {
        stopReason = reason;
        cancel();
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
        long held = SystemClock.uptimeMillis() - t0;
        // time limit doesn't count while paused
        if (deadline > 0) deadline += held;
        return held;
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
        RunGuard.begin(svc, macro.name);
        try {
            for (int s = TriggerPrefs.countdown(svc); s > 0 && !cancelled; s--) {
                svc.setStatus("start in\n" + s);
                Thread.sleep(1000);
            }
            deadline = macro.maxMinutes > 0
                    ? SystemClock.uptimeMillis() + macro.maxMinutes * 60_000L : 0;
            int n = macro.steps.size();
            int loop = 0;
            while (!cancelled && (macro.loops <= 0 || loop < macro.loops)) {
                loop++;
                // checked every loop in case the screen got rotated
                int[] cur = ScreenFit.screenSize(svc);
                fit = ScreenFit.of(macro.screenW, macro.screenH, cur[0], cur[1]);
                if (fit.warning != null && !fit.warning.equals(fitWarning)) svc.toast(fit.warning);
                fitWarning = fit.warning;
                int i = 0;
                while (i < n && !cancelled) {
                    if (timeUp()) {
                        msg = "Stopped after " + macro.maxMinutes + " min";
                        return;
                    }
                    holdIfPaused();
                    if (TapService.get() != svc) {
                        msg = "Accessibility service stopped, playback ended";
                        return;
                    }
                    Step s = fitted(macro.steps.get(i));
                    svc.onProgress(loop, macro.loops, i + 1, n);

                    int next = runStep(s);
                    if (failed >= MAX_FAILED) {
                        msg = "Taps aren't going through, stopped. Is the accessibility service still on?";
                        return;
                    }
                    if (next == HALT) {
                        msg = "Stopped at step " + (i + 1);
                        return;
                    }
                    if (next == HALT_MISS) {
                        msg = "Stopped, step " + (i + 1) + " didn't show up";
                        return;
                    }
                    if (next >= 0 && next < n) {
                        jumpGuard();
                        i = next;
                    } else {
                        i++;
                    }
                }
                if (cancelled) break;
                long pause = macro.loopDelay;
                if (macro.loopJitter > 0) pause += (long) (rnd.nextDouble() * (macro.loopJitter + 1));
                if (pause > 0) pause(pause);
                else jumpGuard();
            }
            if (!cancelled) msg = timeUp() ? "Stopped after " + macro.maxMinutes + " min" : "Done";
        } catch (InterruptedException ignored) {
            // stop pressed
        } finally {
            RunGuard.end(svc);
            done.onDone(msg != null ? msg : stopReason);
        }
    }

    /** runs one step (with repeats), returns the step index to go to, -1 for next, or HALT / HALT_MISS */
    private int runStep(Step s) throws InterruptedException {
        int times = s.action == Step.TAP || s.action == Step.SWIPE || s.action == Step.WAIT
                ? Math.max(1, s.repeat) : 1;
        for (int r = 0; r < times && !cancelled; r++) {
            long d = s.delay * 100 / Math.max(10, macro.speed);
            if (s.jitter > 0) d += Math.round((rnd.nextDouble() * 2 - 1) * s.jitter);
            if (d > 0) pause(d);
            if (timeUp()) return -1;

            int dx = 0, dy = 0;
            if (s.cond != Step.COND_NONE) {
                ScreenGrabber g = ScreenGrabber.get();
                int[] hit = Matcher.LOST;
                if (g != null && !g.isStopped()) {
                    waitOverlayGone();
                    hit = Matcher.waitFor(g, s, s.x, s.y, () -> cancelled);
                }
                if (hit == Matcher.LOST) {
                    // no capture, fall back to the recorded timing
                    if (hadCapture && !captureWarned) {
                        captureWarned = true;
                        svc.toast("Screen capture stopped, going by timing only");
                    }
                    if (s.delay == 0 && s.recGap > 0) pause(s.recGap * 100 / Math.max(10, macro.speed));
                } else {
                    if (hit == null) {
                        if (s.goMiss >= 0) return s.goMiss;
                        if (s.onMiss == Step.MISS_SKIP) return -1;
                        if (s.onMiss == Step.MISS_STOP) return HALT_MISS;
                    } else {
                        dx = hit[0] - s.x;
                        dy = hit[1] - s.y;
                    }
                }
            }
            if (s.action == Step.FIND_IMAGE) {
                ScreenGrabber g = ScreenGrabber.get();
                if (g != null) waitOverlayGone();
                int[] hit = TemplateMatcher.waitFor(g, s, () -> cancelled || paused);
                if (hit == null && paused) {
                    // paused while looking, start over once we're back
                    holdIfPaused();
                    r--;
                    continue;
                }
                if (hit == null) {
                    if (s.goMiss >= 0) return s.goMiss;
                    if (s.onMiss == Step.MISS_SKIP) return -1;
                    if (s.onMiss == Step.MISS_STOP) return HALT_MISS;
                    // tap anyway: where the image was when it was cut
                    hit = new int[]{s.x, s.y};
                }
                dx = hit[0] + s.offX - s.x;
                dy = hit[1] + s.offY - s.y;
            }
            if (s.action == Step.STOP) return HALT;
            if (s.action == Step.GOTO) return s.goFound;
            if (s.action == Step.WAIT) continue;

            if (s.offset > 0) {
                // uniform point in a circle, same shift for both ends of a swipe
                double a = rnd.nextDouble() * Math.PI * 2;
                double len = Math.sqrt(rnd.nextDouble()) * s.offset;
                dx += (int) Math.round(Math.cos(a) * len);
                dy += (int) Math.round(Math.sin(a) * len);
            }
            boolean ok = svc.playGesture(s, dx, dy);
            failed = ok ? 0 : failed + 1;
            if (failed >= MAX_FAILED) return HALT;
        }
        return s.cond != Step.COND_NONE ? s.goFound : -1;
    }

    /** the step moved onto the current screen size, or the step itself if nothing changes */
    private Step fitted(Step s) {
        ScreenFit f = fit;
        if (f.sx == 1f && f.sy == 1f) return s;
        Step t = s.copy();
        t.x = f.x(s.x); t.y = f.y(s.y);
        t.x2 = f.x(s.x2); t.y2 = f.y(s.y2);
        t.fx = f.x(s.fx); t.fy = f.y(s.fy);
        t.fx2 = f.x(s.fx2); t.fy2 = f.y(s.fy2);
        if (t.path != null) {
            for (int k = 0; k + 2 < t.path.length; k += 3) {
                t.path[k] = f.x(t.path[k]);
                t.path[k + 1] = f.y(t.path[k + 1]);
            }
        }
        return t;
    }

    private boolean timeUp() {
        return deadline > 0 && SystemClock.uptimeMillis() >= deadline;
    }

    /** like Thread.sleep but wakes up early when the time limit runs out */
    private void pause(long ms) throws InterruptedException {
        if (deadline > 0) ms = Math.min(ms, deadline - SystemClock.uptimeMillis());
        if (ms > 0) doze(ms);
    }

    /** our own tap marker could still be on screen and mess up the color check */
    private void waitOverlayGone() throws InterruptedException {
        long left;
        while ((left = svc.overlayClearAt() - SystemClock.uptimeMillis()) > 0 && !cancelled) {
            Thread.sleep(Math.min(left, 50));
        }
        holdIfPaused();
    }

    private void jumpGuard() throws InterruptedException {
        long gap = SystemClock.uptimeMillis() - lastJump;
        if (gap < MIN_JUMP_GAP) Thread.sleep(MIN_JUMP_GAP - gap);
        lastJump = SystemClock.uptimeMillis();
    }
}
