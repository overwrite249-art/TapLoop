package dev.overwrite.taploop.capture;

import android.os.SystemClock;

import dev.overwrite.taploop.model.Step;

/**
 * Looks for a "Find image" template in the screen frame.
 * First pass runs on a shrunk gray copy and keeps the few best spots,
 * second pass checks those spots again in color at template scale.
 */
public final class TemplateMatcher {
    /** templates bigger than this many pixels get scaled down when cut */
    public static final int MAX_PIXELS = 96 * 96;
    public static final int MIN_SIDE = 8;
    private static final int KEEP = 24;

    private final byte[] tpl;
    private final int tw, th;
    private final float ts;
    private final int shrink;
    // shrunk gray template, once per grid phase, see below
    private final int[][] small;
    private final int[] phaseX, phaseY;
    // block sums of each shrunk template, for the quick reject below
    private final int[][] quad;
    private final int sw, sh;
    private final int ax, ay, aw, ah;
    private final long limit;
    private final long coarseLimit;

    // reused between frames
    private byte[] img;
    private int iw, ih, ox, oy;
    private int[] gray;
    private int[] sums;
    private final int[] candX = new int[KEEP], candY = new int[KEEP];
    private final long[] candD = new long[KEEP];

    public TemplateMatcher(Step s) {
        tpl = s.tpl;
        tw = s.tplW;
        th = s.tplH;
        ts = s.tplScale > 0 && s.tplScale <= 1 ? s.tplScale : 1f;
        int d = 4;
        while (d > 1 && Math.min(tw, th) / d < 6) d /= 2;
        shrink = d;
        // the shrink grid on the frame won't line up with where the image is,
        // so keep a few shifted copies of the template (0 and half a cell)
        int half = d / 2;
        int np = half > 0 ? 4 : 1;
        phaseX = new int[np];
        phaseY = new int[np];
        for (int i = 0; i < np; i++) {
            phaseX[i] = (i & 1) * half;
            phaseY[i] = (i >> 1) * half;
        }
        sw = Math.max(1, (tw - half) / d - 1);
        sh = Math.max(1, (th - half) / d - 1);
        int[] g = toGray(tpl, tw, th);
        small = new int[np][];
        for (int i = 0; i < np; i++) {
            int w = (tw - phaseX[i]) / d, h = (th - phaseY[i]) / d;
            small[i] = blur(shrinkGray(g, tw, phaseX[i], phaseY[i], d, w, h), w, sw, sh);
        }
        quad = new int[np][4];
        for (int i = 0; i < np; i++) {
            for (int y = 0; y < sh; y++) {
                for (int x = 0; x < sw; x++) {
                    quad[i][(y < sh / 2 ? 0 : 2) + (x < sw / 2 ? 0 : 1)] += small[i][y * sw + x];
                }
            }
        }
        // area in frame px, 0 width = whole frame
        ax = Math.round(s.areaX * ScreenGrabber.SCALE);
        ay = Math.round(s.areaY * ScreenGrabber.SCALE);
        aw = Math.round(s.areaW * ScreenGrabber.SCALE);
        ah = Math.round(s.areaH * ScreenGrabber.SCALE);
        // similarity = 100 - meanDiff * 100 / 80, so 85% allows an average diff of 12
        double maxMean = (100 - Math.max(1, Math.min(100, s.match))) * 0.8;
        limit = (long) (maxMean * tw * th * 3);
        // loose cut for the shrunk gray pass, it's blurrier and not pixel aligned
        // (gray can be up to ~1.8x the rgb mean if the difference is all green)
        coarseLimit = (long) ((maxMean * 1.8 + 8) * sw * sh);
    }

    /** keeps checking new frames until it's found or the step timeout runs out */
    public static int[] waitFor(ScreenGrabber g, Step s) throws InterruptedException {
        if (g == null || !s.hasTemplate() || s.tplW < 4 || s.tplH < 4) return null;
        TemplateMatcher m = new TemplateMatcher(s);
        long end = SystemClock.uptimeMillis() + s.timeout;
        long seen = -1;
        while (true) {
            if (Thread.interrupted()) throw new InterruptedException();
            long ft = g.frameTime();
            if (ft != seen) {
                seen = ft;
                int[] p = m.find(g);
                if (p != null) return p;
            }
            long left = end - SystemClock.uptimeMillis();
            if (left <= 0 || !g.hasFrame()) return null;
            g.awaitFrame(seen, Math.min(50, left));
        }
    }

    /** screen point of the template center (no offset), or null */
    public int[] find(ScreenGrabber g) {
        Boolean ok = g.withFrame(this::grab);
        if (ok == null || !ok) return null;
        return search();
    }

    // copies the search area out of the frame so the grabber lock isn't held during the search
    private Boolean grab(byte[] rgba, int stride, int fw, int fh) {
        int x0 = 0, y0 = 0, w = fw, h = fh;
        if (aw > 0 && ah > 0) {
            x0 = clamp(ax, 0, fw - 1);
            y0 = clamp(ay, 0, fh - 1);
            w = Math.min(aw, fw - x0);
            h = Math.min(ah, fh - y0);
        }
        int nw = (int) (w * ts), nh = (int) (h * ts);
        if (nw < tw || nh < th) return false;
        if (img == null || img.length < nw * nh * 3) {
            img = new byte[nw * nh * 3];
            gray = null;
        }
        iw = nw;
        ih = nh;
        ox = x0;
        oy = y0;
        sample(rgba, stride, x0, y0, ts, img, nw, nh);
        return true;
    }

    private int[] search() {
        int gw = iw / shrink, gh = ih / shrink;
        if (gw - 1 < sw || gh - 1 < sh) return null;
        if (gray == null || gray.length < gw * gh) gray = new int[gw * gh];
        shrinkInto(img, iw, ih, shrink, gray, gw, gh);
        // blur a bit so it doesn't matter much where the shrink grid lands
        blurInPlace(gray, gw, gh);
        int stride = gw;
        gw--;
        gh--;
        // summed area table so block sums are 4 lookups
        int ss = gw + 1;
        if (sums == null || sums.length < ss * (gh + 1)) sums = new int[ss * (gh + 1)];
        for (int x = 0; x <= gw; x++) sums[x] = 0;
        for (int y = 0; y < gh; y++) {
            int row = 0, o = (y + 1) * ss;
            sums[o] = 0;
            for (int x = 0; x < gw; x++) {
                row += gray[y * stride + x];
                sums[o + x + 1] = sums[o - ss + x + 1] + row;
            }
        }
        int hx = sw / 2, hy = sh / 2;

        // coarse pass, keep the KEEP best spots that aren't right next to each other.
        // candidates are kept in template-scale px
        int n = 0;
        int nearX = Math.max(1, tw / 2), nearY = Math.max(1, th / 2);
        for (int p = 0; p < small.length; p++) {
            int[] t = small[p];
            for (int y = 0; y <= gh - sh; y++) {
                for (int x = 0; x <= gw - sw; x++) {
                    long stop = n < KEEP ? coarseLimit : Math.min(coarseLimit, candD[worst(n)]);
                    // sad can't be less than the difference of the block sums
                    int[] q = quad[p];
                    long lb = Math.abs(box(ss, x, y, hx, hy) - q[0])
                            + Math.abs(box(ss, x + hx, y, sw - hx, hy) - q[1])
                            + Math.abs(box(ss, x, y + hy, hx, sh - hy) - q[2])
                            + Math.abs(box(ss, x + hx, y + hy, sw - hx, sh - hy) - q[3]);
                    if (lb >= stop) continue;
                    long d = graySad(gray, stride, t, x, y, stop);
                    if (d >= stop) continue;
                    int fx = x * shrink - phaseX[p], fy = y * shrink - phaseY[p];
                    int near = -1;
                    for (int i = 0; i < n; i++) {
                        if (Math.abs(candX[i] - fx) <= nearX && Math.abs(candY[i] - fy) <= nearY) {
                            near = i;
                            break;
                        }
                    }
                    int slot;
                    if (near >= 0) {
                        if (d >= candD[near]) continue;
                        slot = near;
                    } else {
                        slot = n < KEEP ? n++ : worst(n);
                    }
                    candX[slot] = fx;
                    candY[slot] = fy;
                    candD[slot] = d;
                }
            }
        }

        // fine pass around each spot, in color
        long best = Long.MAX_VALUE;
        int bx = 0, by = 0;
        int r = shrink / 2 + 1;
        for (int i = 0; i < n; i++) {
            int cx = candX[i], cy = candY[i];
            for (int y = cy - r; y <= cy + r; y++) {
                if (y < 0 || y > ih - th) continue;
                for (int x = cx - r; x <= cx + r; x++) {
                    if (x < 0 || x > iw - tw) continue;
                    long d = rgbSad(x, y, best);
                    if (d < best) {
                        best = d;
                        bx = x;
                        by = y;
                    }
                }
            }
        }
        if (best > limit) return null;
        float fx = ox + (bx + tw / 2f) / ts;
        float fy = oy + (by + th / 2f) / ts;
        return new int[]{Math.round(fx / ScreenGrabber.SCALE), Math.round(fy / ScreenGrabber.SCALE)};
    }

    private int box(int ss, int x, int y, int w, int h) {
        int a = y * ss + x, b = (y + h) * ss + x;
        return sums[b + w] - sums[b] - sums[a + w] + sums[a];
    }

    private int worst(int n) {
        int w = 0;
        for (int i = 1; i < n; i++) if (candD[i] > candD[w]) w = i;
        return w;
    }

    private long graySad(int[] g, int stride, int[] small, int x0, int y0, long stop) {
        long sum = 0;
        int p = 0;
        for (int y = 0; y < sh; y++) {
            int row = (y0 + y) * stride + x0;
            for (int x = 0; x < sw; x++) sum += Math.abs(g[row + x] - small[p++]);
            if (sum >= stop) return sum;
        }
        return sum;
    }

    private long rgbSad(int x0, int y0, long stop) {
        long sum = 0;
        int p = 0;
        for (int y = 0; y < th; y++) {
            int i = ((y0 + y) * iw + x0) * 3;
            for (int x = 0; x < tw * 3; x++) sum += Math.abs((img[i + x] & 0xFF) - (tpl[p++] & 0xFF));
            if (sum >= stop) return sum;
        }
        return sum;
    }

    // ---- cutting a template out of a screenshot ----

    /**
     * Cuts the template from a frame (rect in frame px) and stores it on the step.
     * Returns false if the rect is too small.
     */
    public static boolean cut(byte[] rgba, int stride, int fw, int fh,
                              int x0, int y0, int w, int h, Step out) {
        x0 = clamp(x0, 0, fw - 1);
        y0 = clamp(y0, 0, fh - 1);
        w = Math.min(w, fw - x0);
        h = Math.min(h, fh - y0);
        if (w < MIN_SIDE || h < MIN_SIDE) return false;
        float scale = 1f;
        if (w * h > MAX_PIXELS) scale = (float) Math.sqrt(MAX_PIXELS / (double) (w * h));
        int nw = Math.max(1, (int) (w * scale)), nh = Math.max(1, (int) (h * scale));
        if (nw < MIN_SIDE || nh < MIN_SIDE) return false;
        byte[] t = new byte[nw * nh * 3];
        sample(rgba, stride, x0, y0, scale, t, nw, nh);
        out.tpl = t;
        out.tplW = nw;
        out.tplH = nh;
        out.tplScale = scale;
        return true;
    }

    /** true if the template is basically one flat color, those match everywhere */
    public static boolean isFlat(Step s) {
        if (!s.hasTemplate()) return true;
        int[] g = toGray(s.tpl, s.tplW, s.tplH);
        long sum = 0, sq = 0;
        for (int v : g) {
            sum += v;
            sq += (long) v * v;
        }
        double mean = sum / (double) g.length;
        return sq / (double) g.length - mean * mean < 16;
    }

    /** area-averaged RGB copy of a frame rect, scale <= 1 */
    private static void sample(byte[] src, int stride, int x0, int y0, float scale,
                               byte[] out, int ow, int oh) {
        int o = 0;
        if (scale >= 1f) {
            for (int y = 0; y < oh; y++) {
                int i = (y0 + y) * stride + x0 * 4;
                for (int x = 0; x < ow; x++, i += 4) {
                    out[o++] = src[i];
                    out[o++] = src[i + 1];
                    out[o++] = src[i + 2];
                }
            }
            return;
        }
        int[] xs = new int[ow + 1], ys = new int[oh + 1];
        for (int i = 0; i <= ow; i++) xs[i] = (int) (i / scale);
        for (int i = 0; i <= oh; i++) ys[i] = (int) (i / scale);
        for (int y = 0; y < oh; y++) {
            int ya = y0 + ys[y], yb = Math.max(ya + 1, y0 + ys[y + 1]);
            for (int x = 0; x < ow; x++) {
                int xa = x0 + xs[x], xb = Math.max(xa + 1, x0 + xs[x + 1]);
                int r = 0, g = 0, b = 0, n = 0;
                for (int sy = ya; sy < yb; sy++) {
                    int i = sy * stride + xa * 4;
                    for (int sx = xa; sx < xb; sx++, i += 4) {
                        r += src[i] & 0xFF;
                        g += src[i + 1] & 0xFF;
                        b += src[i + 2] & 0xFF;
                        n++;
                    }
                }
                out[o++] = (byte) (r / n);
                out[o++] = (byte) (g / n);
                out[o++] = (byte) (b / n);
            }
        }
    }

    private static int[] toGray(byte[] rgb, int w, int h) {
        int[] g = new int[w * h];
        for (int i = 0, p = 0; i < g.length; i++, p += 3) {
            g[i] = ((rgb[p] & 0xFF) * 77 + (rgb[p + 1] & 0xFF) * 150 + (rgb[p + 2] & 0xFF) * 29) >> 8;
        }
        return g;
    }

    private static int[] shrinkGray(int[] g, int w, int x0, int y0, int d, int nw, int nh) {
        int[] out = new int[nw * nh];
        int n = d * d;
        for (int y = 0; y < nh; y++) {
            for (int x = 0; x < nw; x++) {
                int sum = 0;
                for (int yy = 0; yy < d; yy++) {
                    int row = (y0 + y * d + yy) * w + x0 + x * d;
                    for (int xx = 0; xx < d; xx++) sum += g[row + xx];
                }
                out[y * nw + x] = sum / n;
            }
        }
        return out;
    }

    // 2x2 box, output is one smaller each way
    private static int[] blur(int[] g, int w, int nw, int nh) {
        int[] out = new int[nw * nh];
        for (int y = 0; y < nh; y++) {
            for (int x = 0; x < nw; x++) {
                int i = y * w + x;
                out[y * nw + x] = (g[i] + g[i + 1] + g[i + w] + g[i + w + 1]) >> 2;
            }
        }
        return out;
    }

    // same, but keeps the row stride so the result has width w and height h - 1
    private static void blurInPlace(int[] g, int w, int h) {
        for (int y = 0; y < h - 1; y++) {
            int r = y * w;
            for (int x = 0; x < w - 1; x++) {
                int i = r + x;
                g[i] = (g[i] + g[i + 1] + g[i + w] + g[i + w + 1]) >> 2;
            }
        }
    }

    // rgb image -> gray, shrunk by d
    private static void shrinkInto(byte[] rgb, int w, int h, int d, int[] out, int nw, int nh) {
        int n = d * d;
        for (int y = 0; y < nh; y++) {
            for (int x = 0; x < nw; x++) {
                int sum = 0;
                for (int yy = 0; yy < d; yy++) {
                    int p = ((y * d + yy) * w + x * d) * 3;
                    for (int xx = 0; xx < d; xx++, p += 3) {
                        sum += (rgb[p] & 0xFF) * 77 + (rgb[p + 1] & 0xFF) * 150 + (rgb[p + 2] & 0xFF) * 29;
                    }
                }
                out[y * nw + x] = (sum >> 8) / n;
            }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
