package dev.overwrite.taploop.trigger;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.overwrite.taploop.R;
import dev.overwrite.taploop.model.Macro;
import dev.overwrite.taploop.model.MacroStore;

public final class Shortcuts {
    private static final String TAG = "Shortcuts";

    private Shortcuts() {}

    private static ShortcutInfo info(Context c, Macro m) {
        Intent i = new Intent(RunActivity.ACTION)
                .setClass(c, RunActivity.class)
                .putExtra(RunActivity.EXTRA_ID, m.id);
        String label = m.name.isEmpty() ? "Macro" : m.name;
        return new ShortcutInfo.Builder(c, "run_" + m.id)
                .setShortLabel(label.length() > 24 ? label.substring(0, 24) : label)
                .setLongLabel("Play " + label)
                .setIcon(Icon.createWithResource(c, R.mipmap.ic_launcher))
                .setIntent(i)
                .build();
    }

    /** false if the launcher can't pin */
    public static boolean pin(Context c, Macro m) {
        ShortcutManager sm = c.getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) return false;
        return sm.requestPinShortcut(info(c, m), null);
    }

    /** keeps the long-press menu and pinned labels in sync with the saved macros */
    public static void update(Context c) {
        ShortcutManager sm = c.getSystemService(ShortcutManager.class);
        if (sm == null) return;
        try {
            List<Macro> all = MacroStore.all(c);
            Map<String, Macro> byId = new HashMap<>();
            for (Macro m : all) byId.put("run_" + m.id, m);

            if (TriggerPrefs.get(c).getBoolean(TriggerPrefs.DYN_SHORTCUTS, false)) {
                List<ShortcutInfo> dyn = new ArrayList<>();
                int max = Math.min(4, sm.getMaxShortcutCountPerActivity());
                for (int i = 0; i < all.size() && i < max; i++) dyn.add(info(c, all.get(i)));
                sm.setDynamicShortcuts(dyn);
            } else {
                sm.removeAllDynamicShortcuts();
            }

            List<ShortcutInfo> keep = new ArrayList<>();
            List<String> gone = new ArrayList<>();
            for (ShortcutInfo s : sm.getPinnedShortcuts()) {
                Macro m = byId.get(s.getId());
                if (m != null) keep.add(info(c, m));
                else gone.add(s.getId());
            }
            if (!keep.isEmpty()) sm.updateShortcuts(keep);
            if (!gone.isEmpty()) sm.disableShortcuts(gone, "Macro was deleted");
        } catch (Exception e) {
            // rate limited or launcher being odd, not worth crashing over
            Log.w(TAG, "shortcut update failed", e);
        }
    }
}
