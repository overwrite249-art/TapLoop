package dev.overwrite.taploop.io;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dev.overwrite.taploop.capture.ScreenGrabber;
import dev.overwrite.taploop.model.Macro;
import dev.overwrite.taploop.model.MacroStore;
import dev.overwrite.taploop.model.Step;

/**
 * Reading and writing macro files for export / import / backup / share.
 * File format: {"taploop": 1, "macros": [ ...macro json... ]}. On import we
 * also take a bare macro object (what's in files/macros) or a plain array.
 */
public final class MacroIO {
    public static final int FORMAT = 1;
    public static final String MIME = "application/json";

    // big image checks make files grow, but nothing real gets near this
    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final int MAX_NAME = 80;

    private MacroIO() {}

    public static class Result {
        public final List<Macro> macros = new ArrayList<>();
        public int skipped;
    }

    public static String toText(List<Macro> list, boolean pretty) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("taploop", FORMAT);
        o.put("exported", System.currentTimeMillis());
        JSONArray arr = new JSONArray();
        for (Macro m : list) arr.put(m.toJson());
        o.put("macros", arr);
        return pretty ? o.toString(1) : o.toString();
    }

    public static String toText(Macro m) throws JSONException {
        List<Macro> one = new ArrayList<>();
        one.add(m);
        return toText(one, true);
    }

    /** throws IOException with a message that can go straight into a toast */
    public static Result parse(String text) throws IOException {
        Object root;
        try {
            root = new JSONTokener(text.trim()).nextValue();
        } catch (JSONException e) {
            throw new IOException("Not a JSON file");
        }
        JSONArray items;
        if (root instanceof JSONArray) {
            items = (JSONArray) root;
        } else if (root instanceof JSONObject) {
            JSONObject o = (JSONObject) root;
            if (o.optInt("taploop", 0) > FORMAT) {
                throw new IOException("Made by a newer TapLoop, update the app first");
            }
            if (o.optJSONArray("macros") != null) {
                items = o.optJSONArray("macros");
            } else if (o.optJSONObject("macro") != null) {
                items = new JSONArray().put(o.optJSONObject("macro"));
            } else if (o.optJSONArray("steps") != null) {
                items = new JSONArray().put(o);
            } else {
                throw new IOException("No macros in this file");
            }
        } else {
            throw new IOException("No macros in this file");
        }

        Result r = new Result();
        for (int i = 0; i < items.length(); i++) {
            Macro m = readOne(items.optJSONObject(i));
            if (m != null) r.macros.add(m);
            else r.skipped++;
        }
        if (r.macros.isEmpty()) throw new IOException("No valid macros in this file");
        return r;
    }

    private static Macro readOne(JSONObject o) {
        if (o == null || o.optJSONArray("steps") == null) return null;
        JSONArray steps = o.optJSONArray("steps");
        for (int i = 0; i < steps.length(); i++) {
            JSONObject s = steps.optJSONObject(i);
            if (s == null) return null;
            int a = s.optInt("action", Step.TAP);
            if (a != Step.TAP && a != Step.SWIPE && a != Step.WAIT) return null;
        }
        Macro m;
        try {
            m = Macro.fromJson(null, o);
        } catch (RuntimeException e) {
            // broken base64 in an image patch and similar
            return null;
        }
        if (m.steps.size() != steps.length()) return null;
        clean(m);
        return m;
    }

    // only kicks in for hand edited or broken files, normal exports pass through unchanged
    private static void clean(Macro m) {
        m.name = m.name == null ? "" : m.name.trim();
        if (m.name.isEmpty()) m.name = "Imported";
        if (m.name.length() > MAX_NAME) m.name = m.name.substring(0, MAX_NAME);
        m.folder = cleanFolder(m.folder);
        m.loops = clamp(m.loops, 0, 1_000_000);
        m.speed = clamp(m.speed, 10, 10_000);
        m.loopDelay = clamp(m.loopDelay, 0, 86_400_000L);
        for (Step s : m.steps) {
            s.x = clamp(s.x, 0, 20_000); s.y = clamp(s.y, 0, 20_000);
            s.x2 = clamp(s.x2, 0, 20_000); s.y2 = clamp(s.y2, 0, 20_000);
            s.delay = clamp(s.delay, 0, 86_400_000L);
            s.duration = clamp(s.duration, 1, 60_000L);
            s.timeout = clamp(s.timeout, 0, 3_600_000L);
            s.tolerance = clamp(s.tolerance, 0, 765);
            s.searchRadius = clamp(s.searchRadius, 0, 2000);
            if (s.cond < Step.COND_NONE || s.cond > Step.COND_IMAGE) s.cond = Step.COND_NONE;
            if (s.onMiss < Step.MISS_TAP || s.onMiss > Step.MISS_STOP) s.onMiss = Step.MISS_TAP;
            if (s.patch != null && (s.patchSize <= 0 || s.patchSize > ScreenGrabber.PATCH * 8
                    || s.patch.length != s.patchSize * s.patchSize * 3)) {
                s.patch = null;
                s.patchSize = 0;
            }
            if (s.cond == Step.COND_IMAGE && s.patch == null) s.cond = Step.COND_NONE;
        }
    }

    public static String cleanFolder(String f) {
        if (f == null) return "";
        f = f.trim();
        return f.length() > 40 ? f.substring(0, 40) : f;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** saves copies with fresh ids, renames on name clashes, returns how many */
    public static int saveAll(Context c, List<Macro> list) {
        Set<String> names = new HashSet<>();
        for (Macro m : MacroStore.all(c)) names.add(m.name);
        long next = System.currentTimeMillis();
        int n = 0;
        for (Macro m : list) {
            while (MacroStore.load(c, "m" + next) != null) next++;
            m.id = "m" + next++;
            m.name = freeName(names, m.name);
            names.add(m.name);
            MacroStore.save(c, m);
            n++;
        }
        return n;
    }

    public static Macro duplicate(Context c, Macro src) throws JSONException {
        Macro m = Macro.fromJson(null, src.toJson());
        m.name = src.name + " copy";
        List<Macro> one = new ArrayList<>();
        one.add(m);
        saveAll(c, one);
        return m;
    }

    private static String freeName(Set<String> taken, String name) {
        if (!taken.contains(name)) return name;
        for (int i = 2; ; i++) {
            String n = name + " (" + i + ")";
            if (!taken.contains(n)) return n;
        }
    }

    public static String fileName(Macro m) {
        String n = m.name.replaceAll("[^A-Za-z0-9 ._-]+", "_").trim();
        if (n.isEmpty() || n.startsWith(".")) n = "macro" + n;
        if (n.length() > 60) n = n.substring(0, 60);
        return n + ".json";
    }

    public static String backupName() {
        return "taploop-backup-" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) + ".json";
    }

    public static String read(ContentResolver cr, Uri uri) throws IOException {
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new IOException("Can't open file");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                if (out.size() > MAX_BYTES) throw new IOException("File is too big");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (SecurityException e) {
            throw new IOException("No access to that file");
        }
    }

    public static void write(ContentResolver cr, Uri uri, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try {
            // "wt" so overwriting an existing longer file doesn't leave junk at the end
            writeMode(cr, uri, "wt", bytes);
        } catch (FileNotFoundException | IllegalArgumentException e) {
            writeMode(cr, uri, "w", bytes);
        }
    }

    private static void writeMode(ContentResolver cr, Uri uri, String mode, byte[] bytes) throws IOException {
        try (OutputStream out = cr.openOutputStream(uri, mode)) {
            if (out == null) throw new IOException("Can't write file");
            out.write(bytes);
        }
    }

    /** writes the text into the share cache and returns a content:// uri for it */
    public static Uri shareFile(Context c, String name, String text) throws IOException {
        File dir = ShareProvider.dir(c);
        File[] old = dir.listFiles();
        if (old != null) {
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            for (File f : old) if (f.lastModified() < cutoff) f.delete();
        }
        File f = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return ShareProvider.uriFor(c, f);
    }
}
