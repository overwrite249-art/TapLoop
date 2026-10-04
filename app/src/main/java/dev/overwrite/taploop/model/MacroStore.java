package dev.overwrite.taploop.model;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MacroStore {
    private static final String TAG = "MacroStore";

    private MacroStore() {}

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "macros");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static List<Macro> all(Context c) {
        File[] files = dir(c).listFiles((f, n) -> n.endsWith(".json"));
        List<Macro> out = new ArrayList<>();
        if (files == null) return out;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : files) {
            Macro m = read(f);
            if (m != null) out.add(m);
        }
        return out;
    }

    public static Macro load(Context c, String id) {
        if (id == null) return null;
        return read(new File(dir(c), id + ".json"));
    }

    public static void save(Context c, Macro m) {
        if (m.id == null) m.id = "m" + System.currentTimeMillis();
        File f = new File(dir(c), m.id + ".json");
        File tmp = new File(dir(c), m.id + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(m.toJson().toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.e(TAG, "save failed", e);
            return;
        }
        if (!tmp.renameTo(f)) Log.w(TAG, "rename failed for " + f);
    }

    public static void delete(Context c, String id) {
        new File(dir(c), id + ".json").delete();
    }

    private static Macro read(File f) {
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int r = in.read(buf, off, buf.length - off);
                if (r < 0) break;
                off += r;
            }
            String id = f.getName().substring(0, f.getName().length() - 5);
            return Macro.fromJson(id, new JSONObject(new String(buf, 0, off, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            Log.e(TAG, "can't read " + f, e);
            return null;
        }
    }
}
