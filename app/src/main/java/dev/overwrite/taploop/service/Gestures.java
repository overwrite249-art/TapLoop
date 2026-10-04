package dev.overwrite.taploop.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.overwrite.taploop.model.Step;

/**
 * Turns a step into one or more gestures. Recorded paths get split into pieces
 * that are sent one after another with willContinue, because a single stroke
 * always moves at constant speed and we want the recorded speed changes back.
 */
final class Gestures {
    /** pause between the two taps of a double tap */
    static final long DOUBLE_GAP = 80;
    private static final int MAX_PARTS = 24;
    private static final long MIN_PART = 48;

    private static final Handler main = new Handler(Looper.getMainLooper());

    private Gestures() {}

    static List<GestureDescription> build(Step s, int dx, int dy) {
        List<GestureDescription> out = new ArrayList<>();
        if (s.action == Step.SWIPE && s.path != null && s.path.length >= 6) {
            buildPath(s.path, dx, dy, out);
            return out;
        }
        if (s.fingers <= 1 && s.taps <= 1) {
            // same as before for plain taps and swipes
            out.add(TapService.buildGesture(s.action, s.x + dx, s.y + dy, s.x2 + dx, s.y2 + dy, s.duration));
            return out;
        }
        boolean swipe = s.action == Step.SWIPE;
        int taps = swipe ? 1 : s.taps;
        long max = GestureDescription.getMaxGestureDuration();
        long d = Math.max(1, Math.min(s.duration, (max - DOUBLE_GAP) / taps));
        GestureDescription.Builder b = new GestureDescription.Builder();
        for (int i = 0; i < taps; i++) {
            long start = i * (d + DOUBLE_GAP);
            b.addStroke(new GestureDescription.StrokeDescription(
                    line(s.x + dx, s.y + dy, swipe ? s.x2 + dx : -1, s.y2 + dy), start, d));
            if (s.fingers > 1) {
                b.addStroke(new GestureDescription.StrokeDescription(
                        line(s.fx + dx, s.fy + dy, swipe ? s.fx2 + dx : -1, s.fy2 + dy), start, d));
            }
        }
        out.add(b.build());
        return out;
    }

    private static Path line(int x, int y, int x2, int y2) {
        Path p = new Path();
        p.moveTo(Math.max(0, x), Math.max(0, y));
        if (x2 >= 0) p.lineTo(Math.max(0, x2), Math.max(0, y2));
        return p;
    }

    private static void buildPath(int[] pts, int dx, int dy, List<GestureDescription> out) {
        int n = pts.length / 3;
        long max = GestureDescription.getMaxGestureDuration();
        long total = Math.max(1, pts[(n - 1) * 3 + 2] - pts[2]);
        long minPart = Math.max(MIN_PART, total / MAX_PARTS);

        // cut into pieces where the speed stays about the same
        List<int[]> parts = new ArrayList<>();
        int a = 0;
        long dur = 0;
        double len = 0;
        for (int i = 0; i < n - 1; i++) {
            long dt = Math.max(0, pts[(i + 1) * 3 + 2] - pts[i * 3 + 2]);
            double dl = Math.hypot(pts[(i + 1) * 3] - pts[i * 3], pts[(i + 1) * 3 + 1] - pts[i * 3 + 1]);
            if (i > a && (dur + dt > max || (dur >= minPart && !similar(dl, dt, len, dur)))) {
                parts.add(new int[]{a, i});
                a = i;
                dur = 0;
                len = 0;
            }
            dur += dt;
            len += dl;
        }
        parts.add(new int[]{a, n - 1});

        GestureDescription.StrokeDescription prev = null;
        for (int k = 0; k < parts.size(); k++) {
            int from = parts.get(k)[0], to = parts.get(k)[1];
            Path p = new Path();
            p.moveTo(Math.max(0, pts[from * 3] + dx), Math.max(0, pts[from * 3 + 1] + dy));
            for (int i = from + 1; i <= to; i++) {
                p.lineTo(Math.max(0, pts[i * 3] + dx), Math.max(0, pts[i * 3 + 1] + dy));
            }
            long d = Math.max(1, Math.min(max, pts[to * 3 + 2] - pts[from * 3 + 2]));
            boolean more = k < parts.size() - 1;
            prev = prev == null
                    ? new GestureDescription.StrokeDescription(p, 0, d, more)
                    : prev.continueStroke(p, 0, d, more);
            out.add(new GestureDescription.Builder().addStroke(prev).build());
        }
    }

    private static boolean similar(double dl, long dt, double len, long dur) {
        double v = dl / Math.max(1, dt);
        double vc = len / Math.max(1, dur);
        return Math.abs(v - vc) <= 0.4 * Math.max(v, vc) + 0.05;
    }

    static long duration(List<GestureDescription> parts) {
        long t = 0;
        for (GestureDescription g : parts) {
            long end = 0;
            for (int i = 0; i < g.getStrokeCount(); i++) {
                GestureDescription.StrokeDescription sd = g.getStroke(i);
                end = Math.max(end, sd.getStartTime() + sd.getDuration());
            }
            t += end;
        }
        return t;
    }

    /** sends the parts in order, stops early if one gets cancelled. call on main. */
    static void send(AccessibilityService svc, List<GestureDescription> parts, Runnable done) {
        next(svc, parts, 0, done, null);
    }

    private static void next(AccessibilityService svc, List<GestureDescription> parts, int i, Runnable done,
                             boolean[] refused) {
        if (i >= parts.size()) {
            done.run();
            return;
        }
        boolean ok = svc.dispatchGesture(parts.get(i), new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription g) {
                next(svc, parts, i + 1, done, refused);
            }

            @Override
            public void onCancelled(GestureDescription g) {
                done.run();
            }
        }, main);
        if (!ok) {
            if (refused != null) refused[0] = true;
            main.post(done);
        }
    }

    /** player thread version, returns false if it timed out or the system refused it */
    static boolean play(AccessibilityService svc, Step s, int dx, int dy) throws InterruptedException {
        List<GestureDescription> parts = build(s, dx, dy);
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] refused = {false};
        main.post(() -> next(svc, parts, 0, latch::countDown, refused));
        return latch.await(duration(parts) + 3000 + parts.size() * 100L, TimeUnit.MILLISECONDS)
                && !refused[0];
    }
}
