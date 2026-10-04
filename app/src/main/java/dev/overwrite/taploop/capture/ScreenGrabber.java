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

import java.nio.ByteBuffer;

/**
 * Keeps the latest screen frame in memory (at half resolution) so we can
 * check colors and small image patches quickly.
 * All coordinates passed in are real screen pixels.
 */
public final class ScreenGrabber {
    public static final float SCALE = 0.5f;
    /** patch side length in frame pixels (= 2x that on screen) */
    public static final int PATCH = 16;

    private static volatile ScreenGrabber current;

    public static ScreenGrabber get() {
        return current;
    }

    private final Object lock = new Object();
    private final HandlerThread thread;
    private final ImageReader reader;
    private final VirtualDisplay display;
    private final int w, h;

    private byte[] frame;
    private int stride;
    private long frameTime;

    private ScreenGrabber(MediaProjection mp, int screenW, int screenH, int dpi) {
        w = Math.max(1, Math.round(screenW * SCALE));
        h = Math.max(1, Math.round(screenH * SCALE));
        thread = new HandlerThread("grabber");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onImage, handler);
        display = mp.createVirtualDisplay("taploop", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, handler);
    }

    static void start(MediaProjection mp, int screenW, int screenH, int dpi) {
        stop();
        current = new ScreenGrabber(mp, screenW, screenH, dpi);
    }

    static void stop() {
        ScreenGrabber g = current;
        current = null;
        if (g != null) g.release();
    }

    private void release() {
        try { display.release(); } catch (Exception ignored) {}
        try { reader.close(); } catch (Exception ignored) {}
        thread.quitSafely();
        synchronized (lock) {
            frame = null;
            lock.notifyAll();
        }
    }

    private void onImage(ImageReader r) {
        Image img;
        try {
            img = r.acquireLatestImage();
        } catch (IllegalStateException e) {
            return;
        }
        if (img == null) return;
        try {
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int n = buf.remaining();
            synchronized (lock) {
                if (frame == null || frame.length < n) frame = new byte[n];
                buf.get(frame, 0, n);
                stride = p.getRowStride();
                frameTime = SystemClock.uptimeMillis();
                lock.notifyAll();
            }
        } finally {
            img.close();
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
            while (frameTime <= since && frame != null) {
                long left = end - SystemClock.uptimeMillis();
                if (left <= 0) return;
                lock.wait(left);
            }
        }
    }

    public interface FrameReader<T> {
        T read(byte[] rgba, int stride, int width, int height);
    }

    /** runs r on the latest frame while holding the lock, keep it quick. null if no frame */
    public <T> T withFrame(FrameReader<T> r) {
        synchronized (lock) {
            if (frame == null) return null;
            return r.read(frame, stride, w, h);
        }
    }

    private int fx(int sx) { return clamp(Math.round(sx * SCALE), w - 1); }
    private int fy(int sy) { return clamp(Math.round(sy * SCALE), h - 1); }

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
        if (patch == null || size <= 0 || patch.length < size * size * 3) return null;
        synchronized (lock) {
            if (frame == null) return null;
            int cx = fx(sx) - size / 2, cy = fy(sy) - size / 2;
            long limit = (long) tol * size * size * 3;
            if (diff(patch, size, cx, cy, limit) <= limit) return new int[]{sx, sy};
            int r = Math.round(radius * SCALE);
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
                    return new int[]{Math.round((cx + bx + size / 2f) / SCALE),
                            Math.round((cy + by + size / 2f) / SCALE)};
                }
            }
            return null;
        }
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
