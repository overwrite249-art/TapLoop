package dev.overwrite.taploop.model;

import java.util.List;

/** Keeps go-to targets pointing at the same step when steps get moved around in the editor. */
public final class Jumps {
    private Jumps() {}

    /** a step was inserted at index at */
    /**
     * Runs a change to the list and keeps go-to targets pointing at the same
     * step objects. Targets that got deleted go back to "next".
     */
    public static void keep(List<Step> steps, Runnable change) {
        List<Step> before = new java.util.ArrayList<>(steps);
        java.util.Map<Step, Step[]> targets = new java.util.IdentityHashMap<>();
        for (Step s : before) targets.put(s, new Step[]{at(before, s.goFound), at(before, s.goMiss)});
        change.run();
        for (Step s : steps) {
            Step[] t = targets.get(s);
            // fresh copies still carry the old indexes
            if (t == null) t = new Step[]{at(before, s.goFound), at(before, s.goMiss)};
            s.goFound = indexOf(steps, t[0]);
            s.goMiss = indexOf(steps, t[1]);
        }
    }

    private static Step at(List<Step> steps, int i) {
        return i >= 0 && i < steps.size() ? steps.get(i) : null;
    }

    private static int indexOf(List<Step> steps, Step target) {
        if (target == null) return -1;
        for (int i = 0; i < steps.size(); i++) if (steps.get(i) == target) return i;
        return -1;
    }

    public static void inserted(List<Step> steps, int at) {
        for (Step s : steps) {
            if (s.goFound >= at) s.goFound++;
            if (s.goMiss >= at) s.goMiss++;
        }
    }

    /** the step at index at is gone, jumps to it fall back to the default */
    public static void removed(List<Step> steps, int at) {
        for (Step s : steps) {
            s.goFound = fix(s.goFound, at);
            s.goMiss = fix(s.goMiss, at);
        }
    }

    /** steps a and b swapped places */
    public static void swapped(List<Step> steps, int a, int b) {
        for (Step s : steps) {
            s.goFound = swap(s.goFound, a, b);
            s.goMiss = swap(s.goMiss, a, b);
        }
    }

    private static int fix(int t, int at) {
        if (t == at) return -1;
        return t > at ? t - 1 : t;
    }

    private static int swap(int t, int a, int b) {
        if (t == a) return b;
        if (t == b) return a;
        return t;
    }
}
