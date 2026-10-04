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
    private volatile String cancelReason;
    private boolean captureWarned;
    private String fitWarning;

    // waitFor result when screen capture went away
    private static final int[] LOST = new int[0];
    // this many gestures in a row not going through means the service is gone
    private static final int MAX_FAILED = 3;

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

    /** stop with a message for the user */
    void cancel(String reason) {
        cancelReason = reason;
        cancel();
    }

    @Override
    public void run() {
        String msg = null;
        boolean hadCapture = ScreenGrabber.get() != null;
        int failed = 0;
        RunGuard.begin(svc, macro.name);
        try {
            int loop = 0;
            while (!cancelled && (macro.loops <= 0 || loop < macro.loops)) {
                loop++;
                // checked every loop in case the screen got rotated
                int[] cur = ScreenFit.screenSize(svc);
                ScreenFit fit = ScreenFit.of(macro.screenW, macro.screenH, cur[0], cur[1]);
                if (fit.warning != null && !fit.warning.equals(fitWarning)) svc.toast(fit.warning);
                fitWarning = fit.warning;
                for (int i = 0; i < macro.steps.size() && !cancelled; i++) {
                    if (TapService.get() != svc) {
                        msg = "Accessibility service stopped, playback ended";
                        return;
                    }
                    Step s = macro.steps.get(i);
                    String total = macro.loops <= 0 ? "∞" : String.valueOf(macro.loops);
                    svc.setStatus(loop + "/" + total + "\n#" + (i + 1));

                    long d = s.delay * 100 / Math.max(10, macro.speed);
                    if (d > 0) Thread.sleep(d);

                    int x = fit.x(s.x), y = fit.y(s.y);
                    int dx = 0, dy = 0;
                    if (s.cond != Step.COND_NONE) {
                        ScreenGrabber g = ScreenGrabber.get();
                        int[] hit = g == null || g.isStopped() ? LOST : waitFor(g, s, x, y);
                        if (hit == LOST) {
                            // no capture, fall back to the recorded timing
                            if (hadCapture && !captureWarned) {
                                captureWarned = true;
                                svc.toast("Screen capture stopped, going by timing only");
                            }
                            if (s.delay == 0 && s.recGap > 0) {
                                Thread.sleep(s.recGap * 100 / Math.max(10, macro.speed));
                            }
                        } else if (hit == null) {
                            if (s.onMiss == Step.MISS_SKIP) continue;
                            if (s.onMiss == Step.MISS_STOP) {
                                msg = "Stopped, step " + (i + 1) + " didn't show up";
                                return;
                            }
                        } else {
                            dx = hit[0] - x;
                            dy = hit[1] - y;
                        }
                    }
                    if (s.action == Step.WAIT) continue;
                    boolean ok = svc.dispatchAndWait(TapService.buildGesture(s.action,
                            x + dx, y + dy, fit.x(s.x2) + dx, fit.y(s.y2) + dy, s.duration), s.duration);
                    failed = ok ? 0 : failed + 1;
                    if (failed >= MAX_FAILED) {
                        msg = "Taps aren't going through, stopped. Is the accessibility service still on?";
                        return;
                    }
                }
                if (macro.loopDelay > 0 && !cancelled) Thread.sleep(macro.loopDelay);
            }
            if (!cancelled) msg = "Done";
        } catch (InterruptedException ignored) {
            // stop pressed
        } finally {
            RunGuard.end(svc);
            if (msg == null) msg = cancelReason;
            done.onDone(msg);
        }
    }

    /** returns the matched screen point, null on timeout, LOST if capture went away */
    private int[] waitFor(ScreenGrabber g, Step s, int x, int y) throws InterruptedException {
        long end = SystemClock.uptimeMillis() + s.timeout;
        long seen = -1;
        while (!cancelled) {
            long ft = g.frameTime();
            if (ft != seen) {
                seen = ft;
                if (s.cond == Step.COND_COLOR) {
                    int c = g.colorAt(x, y);
                    if (c != -1 && ScreenGrabber.colorDistance(c, s.color) <= s.tolerance) {
                        return new int[]{x, y};
                    }
                } else {
                    int[] p = g.findPatch(s.patch, s.patchSize, s.patchScale, x, y,
                            s.searchRadius, s.tolerance);
                    if (p != null) return p;
                }
            }
            if (g.isStopped()) {
                // capture got stopped mid-run, or restarted with a new grabber
                ScreenGrabber n = ScreenGrabber.get();
                if (n == null || n.isStopped()) return LOST;
                g = n;
                seen = -1;
                continue;
            }
            long left = end - SystemClock.uptimeMillis();
            if (left <= 0) return null;
            g.awaitFrame(seen, Math.min(50, left));
        }
        throw new InterruptedException();
    }
}
