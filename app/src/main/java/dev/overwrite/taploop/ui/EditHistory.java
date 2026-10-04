package dev.overwrite.taploop.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import dev.overwrite.taploop.model.Step;

/** Undo/redo for the step list, only lives as long as the editor is open. */
class EditHistory {
    private static final int MAX = 100;

    private final ArrayDeque<List<Step>> undo = new ArrayDeque<>();
    private final ArrayDeque<List<Step>> redo = new ArrayDeque<>();

    static List<Step> snapshot(List<Step> steps) {
        List<Step> out = new ArrayList<>(steps.size());
        for (Step s : steps) out.add(s.copy());
        return out;
    }

    /** call right before changing the list */
    void save(List<Step> current) {
        saveSnapshot(snapshot(current));
    }

    void saveSnapshot(List<Step> snap) {
        undo.push(snap);
        while (undo.size() > MAX) undo.removeLast();
        redo.clear();
    }

    boolean canUndo() {
        return !undo.isEmpty();
    }

    boolean canRedo() {
        return !redo.isEmpty();
    }

    boolean undo(List<Step> current) {
        if (undo.isEmpty()) return false;
        redo.push(snapshot(current));
        restore(current, undo.pop());
        return true;
    }

    boolean redo(List<Step> current) {
        if (redo.isEmpty()) return false;
        undo.push(snapshot(current));
        restore(current, redo.pop());
        return true;
    }

    private static void restore(List<Step> current, List<Step> snap) {
        current.clear();
        current.addAll(snap);
    }
}
