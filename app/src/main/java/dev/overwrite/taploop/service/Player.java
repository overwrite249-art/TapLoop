package dev.overwrite.taploop.service;

import android.os.SystemClock;

import java.util.Random;

import dev.overwrite.taploop.capture.ScreenGrabber;
import dev.overwrite.taploop.model.Macro;
import dev.overwrite.taploop.model.Step;

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

    private final TapService svc;
    private final Macro macro;
    private final Done done;
    private final Random rnd = new Random();
    private volatile boolean cancelled;
    private long deadline;
    private long lastJump;

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

    @Override
    public void run() {
        String msg = null;
        try {
            deadline = macro.maxMinutes > 0
                    ? SystemClock.uptimeMillis() + macro.maxMinutes * 60_000L : 0;
            int n = macro.steps.size();
            String total = macro.loops <= 0 ? "∞" : String.valueOf(macro.loops);
            int loop = 0;
            while (!cancelled && (macro.loops <= 0 || loop < macro.loops)) {
                loop++;
                int i = 0;
                while (i < n && !cancelled) {
                    if (timeUp()) {
                        msg = "Stopped after " + macro.maxMinutes + " min";
                        return;
                    }
                    Step s = macro.steps.get(i);
                    String name = s.label.isEmpty() ? "" : " " + s.label;
                    svc.setStatus(loop + "/" + total + "\n#" + (i + 1) + name);

                    int next = runStep(s);
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
            done.onDone(msg);
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
                if (g != null) {
                    int[] hit = waitFor(g, s);
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
            svc.dispatchAndWait(TapService.buildGesture(s.action,
                    s.x + dx, s.y + dy, s.x2 + dx, s.y2 + dy, s.duration), s.duration);
        }
        return s.cond != Step.COND_NONE ? s.goFound : -1;
    }

    private boolean timeUp() {
        return deadline > 0 && SystemClock.uptimeMillis() >= deadline;
    }

    /** like Thread.sleep but wakes up early when the time limit runs out */
    private void pause(long ms) throws InterruptedException {
        if (deadline > 0) ms = Math.min(ms, deadline - SystemClock.uptimeMillis());
        if (ms > 0) Thread.sleep(ms);
    }

    private void jumpGuard() throws InterruptedException {
        long gap = SystemClock.uptimeMillis() - lastJump;
        if (gap < MIN_JUMP_GAP) Thread.sleep(MIN_JUMP_GAP - gap);
        lastJump = SystemClock.uptimeMillis();
    }

    /** returns the matched screen point, or null on timeout */
    private int[] waitFor(ScreenGrabber g, Step s) throws InterruptedException {
        long end = SystemClock.uptimeMillis() + s.timeout;
        long seen = -1;
        while (!cancelled) {
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
