package dev.overwrite.taploop.capture;

import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * Keeps the latest screen frame in memory (half resolution by default, see
 * the capture scale setting) so we can check colors and small image patches quickly.
 * All coordinates passed in are real screen pixels.
 */
public final class ScreenGrabber {
    private static final String TAG = "ScreenGrabber";
    /** default capture scale, also what patches from older versions were taken at */
    public static final float SCALE = 0.5f;
    /** patch side length in frame pixels (= 2x that on screen) */
    public static final int PATCH = 16;

    private static volatile ScreenGrabber current;

    public static ScreenGrabber get() {
        return current;
    }

    private final Object lock = new Object();
    private final HandlerThread thread;
    private final Handler handler;
    private final VirtualDisplay display;
    private final int dpi;
    // only touched on the grabber thread
    private volatile ImageReader reader;
    private volatile int screenW, screenH;
    private volatile boolean stopped;

    // guarded by lock
    private int w, h;
    private float scale;
    private byte[] frame;
    private int stride;
    private long frameTime;

    private ScreenGrabber(MediaProjection mp, int screenW, int screenH, int dpi, float scale) {
        this.dpi = dpi;
        this.screenW = screenW;
        this.screenH = screenH;
        this.scale = cleanScale(scale);
        w = dim(screenW, this.scale);
        h = dim(screenH, this.scale);
        thread = new HandlerThread("grabber");
        thread.start();
        handler = new Handler(thread.getLooper());
        reader = newReader(w, h);
        try {
            display = mp.createVirtualDisplay("taploop", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, handler);
        } catch (RuntimeException e) {
            reader.close();
            thread.quitSafely();
            throw e;
        }
    }

    static void start(MediaProjection mp, int screenW, int screenH, int dpi) {
        start(mp, screenW, screenH, dpi, SCALE);
    }

    static void start(MediaProjection mp, int screenW, int screenH, int dpi, float scale) {
        stop();
        current = new ScreenGrabber(mp, screenW, screenH, dpi, scale);
    }

    private static float cleanScale(float s) {
        return s > 0.05f && s <= 1f ? s : SCALE;
    }

    private static int dim(int screen, float scale) {
        return Math.max(1, Math.round(screen * scale));
    }

    private ImageReader newReader(int w, int h) {
        ImageReader r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        r.setOnImageAvailableListener(this::onImage, handler);
        return r;
    }

    public float scale() {
        synchronized (lock) {
            return scale;
        }
    }

    /** true once capture was shut down, the object is useless after that */
    public boolean isStopped() {
        return stopped;
    }

    public void setScale(float s) {
        resize(screenW, screenH, s);
    }

    /**
     * Screen rotated or the scale setting changed. Android 14 only allows one
     * virtual display per projection, so we resize it and swap in a new reader.
     */
    public void resize(int newScreenW, int newScreenH, float newScale) {
        if (newScreenW <= 0 || newScreenH <= 0) return;
        handler.post(() -> {
            if (stopped) return;
            float s = cleanScale(newScale);
            int nw = dim(newScreenW, s), nh = dim(newScreenH, s);
            synchronized (lock) {
                if (nw == w && nh == h && s == scale) return;
            }
            ImageReader old = reader;
            ImageReader nr = null;
            try {
                nr = newReader(nw, nh);
                display.resize(nw, nh, dpi);
                display.setSurface(nr.getSurface());
            } catch (RuntimeException e) {
                Log.w(TAG, "resize failed", e);
                if (nr != null) nr.close();
                return;
            }
            reader = nr;
            screenW = newScreenW;
            screenH = newScreenH;
            synchronized (lock) {
                w = nw;
                h = nh;
                scale = s;
                // old frame has the wrong size, wait for a fresh one
                frame = null;
                lock.notifyAll();
            }
            old.close();
        });
    }

    static void stop() {
        ScreenGrabber g = current;
        current = null;
        if (g != null) g.release();
    }

    private void release() {
        stopped = true;
        synchronized (lock) {
            frame = null;
            lock.notifyAll();
        }
        // close on the grabber thread so the reader never goes away under onImage
        Runnable close = () -> {
            try { display.release(); } catch (Exception ignored) {}
            try { reader.close(); } catch (Exception ignored) {}
            thread.quitSafely();
        };
        if (!handler.post(close)) close.run();
    }

    private void onImage(ImageReader r) {
        Image img;
        try {
            img = r.acquireLatestImage();
        } catch (RuntimeException e) {
            // reader closed, or maxImages hit
            return;
        }
        if (img == null) return;
        try {
            if (r != reader || stopped) return;
            Image.Plane[] planes = img.getPlanes();
            if (planes == null || planes.length == 0) return;
            Image.Plane p = planes[0];
            int iw = img.getWidth(), ih = img.getHeight();
            int ps = p.getPixelStride(), rs = p.getRowStride();
            ByteBuffer buf = p.getBuffer();
            int n = buf.remaining();
            // some drivers give odd strides or skip the padding on the last row
            if (ps != 4 || rs < iw * 4 || n < (ih - 1) * rs + iw * 4) return;
            synchronized (lock) {
                if (iw != w || ih != h) return;
                if (frame == null || frame.length < n) frame = new byte[n];
                buf.get(frame, 0, n);
                stride = rs;
                frameTime = SystemClock.uptimeMillis();
                lock.notifyAll();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "bad frame", e);
        } finally {
            try { img.close(); } catch (RuntimeException ignored) {}
        }
    }

    public boolean hasFrame() {
        synchronized (lock) {
            return frame != null;
        }
    }

    public long frameTime() {
        synchronized (lock) {
            return frameTime;
        }
    }

    /** waits until a frame newer than {@code since} shows up, or maxMs passes */
    public void awaitFrame(long since, long maxMs) throws InterruptedException {
        long end = SystemClock.uptimeMillis() + maxMs;
        synchronized (lock) {
            while (frameTime <= since && !stopped) {
                long left = end - SystemClock.uptimeMillis();
                if (left <= 0) return;
                lock.wait(left);
            }
        }
    }

    private int fx(int sx) { return clamp(Math.round(sx * scale), w - 1); }
    private int fy(int sy) { return clamp(Math.round(sy * scale), h - 1); }

    private static int clamp(int v, int max) {
        return v < 0 ? 0 : Math.min(v, max);
    }

    /** average color of a 3x3 block, 0xRRGGBB, or -1 if no frame yet */
    public int colorAt(int sx, int sy) {
        synchronized (lock) {
            if (frame == null) return -1;
            int cx = fx(sx), cy = fy(sy);
            int r = 0, g = 0, b = 0, n = 0;
            for (int y = cy - 1; y <= cy + 1; y++) {
                if (y < 0 || y >= h) continue;
                for (int x = cx - 1; x <= cx + 1; x++) {
                    if (x < 0 || x >= w) continue;
                    int i = y * stride + x * 4;
                    r += frame[i] & 0xFF;
                    g += frame[i + 1] & 0xFF;
                    b += frame[i + 2] & 0xFF;
                    n++;
                }
            }
            if (n == 0) return -1;
            return ((r / n) << 16) | ((g / n) << 8) | (b / n);
        }
    }

    public static int colorDistance(int a, int b) {
        int dr = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF));
        int dg = Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF));
        int db = Math.abs((a & 0xFF) - (b & 0xFF));
        return Math.max(dr, Math.max(dg, db));
    }

    /** RGB bytes of a PATCH x PATCH square centered on the screen point */
    public byte[] patchAt(int sx, int sy) {
        synchronized (lock) {
            if (frame == null) return null;
            int x0 = fx(sx) - PATCH / 2, y0 = fy(sy) - PATCH / 2;
            if (x0 < 0 || y0 < 0 || x0 + PATCH > w || y0 + PATCH > h) return null;
            byte[] out = new byte[PATCH * PATCH * 3];
            int o = 0;
            for (int y = 0; y < PATCH; y++) {
                int row = (y0 + y) * stride + x0 * 4;
                for (int x = 0; x < PATCH; x++) {
                    int i = row + x * 4;
                    out[o++] = frame[i];
                    out[o++] = frame[i + 1];
                    out[o++] = frame[i + 2];
                }
            }
            return out;
        }
    }

    /**
     * Looks for the patch around the point. Returns the screen point where it
     * matched (center), or null.
     */
    public int[] findPatch(byte[] patch, int size, int sx, int sy, int radius, int tol) {
        return findPatch(patch, size, SCALE, sx, sy, radius, tol);
    }

    /** same, for a patch taken at another capture scale (it gets resized to match) */
    public int[] findPatch(byte[] patch, int size, float patchScale, int sx, int sy, int radius, int tol) {
        if (patch == null || size <= 0 || patch.length < size * size * 3) return null;
        synchronized (lock) {
            if (frame == null) return null;
            if (Math.abs(patchScale - scale) > 0.001f && patchScale > 0) {
                int ns = Math.max(2, Math.round(size * scale / patchScale));
                patch = resample(patch, size, ns);
                size = ns;
            }
            int cx = fx(sx) - size / 2, cy = fy(sy) - size / 2;
            long limit = (long) tol * size * size * 3;
            if (diff(patch, size, cx, cy, limit) <= limit) return new int[]{sx, sy};
            int r = Math.round(radius * scale);
            long best = Long.MAX_VALUE;
            int bx = 0, by = 0;
            for (int ring = 1; ring <= r; ring++) {
                for (int dy = -ring; dy <= ring; dy++) {
                    int step = (dy == -ring || dy == ring) ? 1 : ring * 2;
                    for (int dx = -ring; dx <= ring; dx += step) {
                        long d = diff(patch, size, cx + dx, cy + dy, Math.min(best, limit));
                        if (d < best) { best = d; bx = dx; by = dy; }
                    }
                }
                // nearest good match wins, no need to scan further out
                if (best <= limit) {
                    return new int[]{Math.round((cx + bx + size / 2f) / scale),
                            Math.round((cy + by + size / 2f) / scale)};
                }
            }
            return null;
        }
    }

    /** nearest neighbour resize of an RGB patch */
    static byte[] resample(byte[] src, int size, int ns) {
        byte[] out = new byte[ns * ns * 3];
        for (int y = 0; y < ns; y++) {
            int syy = Math.min(size - 1, y * size / ns);
            for (int x = 0; x < ns; x++) {
                int sxx = Math.min(size - 1, x * size / ns);
                int si = (syy * size + sxx) * 3, di = (y * ns + x) * 3;
                out[di] = src[si];
                out[di + 1] = src[si + 1];
                out[di + 2] = src[si + 2];
            }
        }
        return out;
    }

    private long diff(byte[] patch, int size, int x0, int y0, long stopAt) {
        if (x0 < 0 || y0 < 0 || x0 + size > w || y0 + size > h) return Long.MAX_VALUE;
        long sum = 0;
        int p = 0;
        for (int y = 0; y < size; y++) {
            int row = (y0 + y) * stride + x0 * 4;
            for (int x = 0; x < size; x++) {
                int i = row + x * 4;
                sum += Math.abs((frame[i] & 0xFF) - (patch[p++] & 0xFF));
                sum += Math.abs((frame[i + 1] & 0xFF) - (patch[p++] & 0xFF));
                sum += Math.abs((frame[i + 2] & 0xFF) - (patch[p++] & 0xFF));
            }
            if (sum > stopAt) return sum;
        }
        return sum;
    }
}
