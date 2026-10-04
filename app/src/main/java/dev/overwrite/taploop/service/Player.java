package dev.overwrite.taploop.service;

import dev.overwrite.taploop.capture.Matcher;
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
            int loop = 0;
            while (!cancelled && (macro.loops <= 0 || loop < macro.loops)) {
                loop++;
                for (int i = 0; i < macro.steps.size() && !cancelled; i++) {
                    Step s = macro.steps.get(i);
                    String total = macro.loops <= 0 ? "∞" : String.valueOf(macro.loops);
                    svc.setStatus(loop + "/" + total + "\n#" + (i + 1));

                    long d = s.delay * 100 / Math.max(10, macro.speed);
                    if (d > 0) Thread.sleep(d);

                    int dx = 0, dy = 0;
                    if (s.cond != Step.COND_NONE) {
                        ScreenGrabber g = ScreenGrabber.get();
                        if (g != null) {
                            int[] hit = Matcher.waitFor(g, s, () -> cancelled);
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
                    svc.dispatchAndWait(TapService.buildGesture(s.action,
                            s.x + dx, s.y + dy, s.x2 + dx, s.y2 + dy, s.duration), s.duration);
                }
                if (macro.loopDelay > 0 && !cancelled) Thread.sleep(macro.loopDelay);
            }
            if (!cancelled) msg = "Done";
        } catch (InterruptedException ignored) {
            // stop pressed
        } finally {
            done.onDone(msg);
        }
    }
}
