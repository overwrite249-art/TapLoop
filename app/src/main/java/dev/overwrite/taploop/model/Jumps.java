package dev.overwrite.taploop.model;

import java.util.List;

/** Keeps go-to targets pointing at the same step when steps get moved around in the editor. */
public final class Jumps {
    private Jumps() {}

    /** a step was inserted at index at */
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
