package os.vanks.launcher;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Обработка снимков камеры Vanks на процессоре, над кадрами NV21 (яркость
 * w×h, затем пары V,U на каждые 2×2 пикселя).
 *
 *  • HDR — кадры с разной выдержкой сливаются так, что каждое место берётся
 *    из кадра, где оно экспонировано лучше: светлое небо — из тёмного кадра,
 *    тени — из светлого. Веса считаются на уменьшенном кадре и размываются —
 *    без швов и ореолов на границах. Что сдвинулось (люди, ветки) — из опорного.
 *  • «Ночь» — серия одинаковых кадров складывается: шум усредняется, детали
 *    остаются. Где кадр не совпал с опорным (движение) — почти не берётся.
 *  • Сдвиг руки между кадрами находится сам: грубо на уменьшенном кадре,
 *    точно — на полном.
 *  • Мягкая резкость вместо «пересушенной» резкости камеры: тонкий
 *    нерезкий маскинг с порогом — шум не усиливается, ореолов нет.
 */
final class Fusion {
    private static final int CORES = Math.max(2, Runtime.getRuntime().availableProcessors());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(CORES);

    interface Rows { void run(int from, int to); }

    /** Строки 0..n — кусками на все ядра. Звать не из самого пула. */
    static void par(int n, Rows r) {
        int k = Math.max(1, Math.min(n, CORES * 2));
        ArrayList<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            int a = (int) ((long) n * i / k), b = (int) ((long) n * (i + 1) / k);
            fs.add(POOL.submit(() -> r.run(a, b)));
        }
        try {
            for (Future<?> f : fs) f.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── кадр ──────────────────────────────────────────────────────────────

    /** YUV_420_888 с камеры → NV21 (любой шаг строк и пикселей). */
    static byte[] nv21(Image img) {
        int w = img.getWidth() & ~1, h = img.getHeight() & ~1;
        byte[] out = new byte[w * h * 3 / 2];
        Image.Plane[] p = img.getPlanes();
        ByteBuffer y = p[0].getBuffer();
        int ys = p[0].getRowStride();
        for (int r = 0; r < h; r++) {
            y.position(r * ys);
            y.get(out, r * w, w);
        }
        ByteBuffer u = p[1].getBuffer(), v = p[2].getBuffer();
        int us = p[1].getRowStride(), up = p[1].getPixelStride();
        int vs = p[2].getRowStride(), vp = p[2].getPixelStride();
        int cw = w / 2, ch = h / 2;
        byte[] ru = new byte[us], rv = new byte[vs];
        int o = w * h;
        for (int r = 0; r < ch; r++) {
            u.position(r * us);
            u.get(ru, 0, Math.min(us, u.limit() - r * us));
            v.position(r * vs);
            v.get(rv, 0, Math.min(vs, v.limit() - r * vs));
            for (int c = 0; c < cw; c++) {
                out[o++] = rv[c * vp];
                out[o++] = ru[c * up];
            }
        }
        return out;
    }

    /** NV21 → JPEG, с обрезкой (зум, формат кадра). */
    static byte[] jpeg(byte[] d, int w, int h, Rect crop, int quality) {
        Rect r = new Rect(crop.left & ~1, crop.top & ~1, crop.right & ~1, crop.bottom & ~1);
        ByteArrayOutputStream o = new ByteArrayOutputStream(w * h / 3);
        new YuvImage(d, ImageFormat.NV21, w, h, null).compressToJpeg(r, quality, o);
        return o.toByteArray();
    }

    /** Зеркало сверху вниз (селфи с датчиком, повёрнутым на 90/270). */
    static void flipRows(byte[] d, int w, int h) {
        byte[] t = new byte[w];
        for (int a = 0, b = h - 1; a < b; a++, b--) swapRow(d, a * w, b * w, w, t);
        int o = w * h;
        for (int a = 0, b = h / 2 - 1; a < b; a++, b--) swapRow(d, o + a * w, o + b * w, w, t);
    }

    /** Зеркало слева направо. */
    static void flipCols(byte[] d, int w, int h) {
        par(h, (from, to) -> {
            for (int r = from; r < to; r++) {
                int o = r * w;
                for (int a = 0, b = w - 1; a < b; a++, b--) {
                    byte x = d[o + a]; d[o + a] = d[o + b]; d[o + b] = x;
                }
            }
        });
        int o0 = w * h;
        for (int r = 0; r < h / 2; r++) {
            int o = o0 + r * w;
            // пары V,U меняются местами целиком
            for (int a = 0, b = w - 2; a < b; a += 2, b -= 2) {
                byte v = d[o + a], u = d[o + a + 1];
                d[o + a] = d[o + b]; d[o + a + 1] = d[o + b + 1];
                d[o + b] = v; d[o + b + 1] = u;
            }
        }
    }

    private static void swapRow(byte[] d, int a, int b, int n, byte[] t) {
        System.arraycopy(d, a, t, 0, n);
        System.arraycopy(d, b, d, a, n);
        System.arraycopy(t, 0, d, b, n);
    }

    /** Яркость, уменьшенная в f раз (среднее по квадрату f×f). */
    static int[] small(byte[] d, int w, int h, int f) {
        int sw = w / f, sh = h / f;
        int[] s = new int[sw * sh];
        int ff = f * f;
        par(sh, (from, to) -> {
            for (int y = from; y < to; y++) {
                for (int x = 0; x < sw; x++) {
                    int sum = 0;
                    for (int j = 0; j < f; j++) {
                        int o = (y * f + j) * w + x * f;
                        for (int i = 0; i < f; i++) sum += d[o + i] & 0xFF;
                    }
                    s[y * sw + x] = sum / ff;
                }
            }
        });
        return s;
    }

    private static double mean(int[] s, int sw, int sh) {
        long sum = 0;
        int n = 0;
        for (int y = sh / 4; y < sh * 3 / 4; y++)
            for (int x = sw / 4; x < sw * 3 / 4; x++) {
                sum += s[y * sw + x];
                n++;
            }
        return n == 0 ? 1 : Math.max(1, sum / (double) n);
    }

    // ── выравнивание ──────────────────────────────────────────────────────

    /**
     * Сдвиг кадра img относительно опорного ref (dx, dy в пикселях полного
     * кадра): img[x + dx] ≈ ref[x]. Яркость кадров может отличаться (HDR) —
     * её подгоняем по средней середины, пересвеченное не сравниваем.
     */
    static int[] align(byte[] ref, byte[] img, int w, int h) {
        final int f = 8, R = 8;
        int sw = w / f, sh = h / f;
        int[] a = small(ref, w, h, f), b = small(img, w, h, f);
        float k = (float) (mean(a, sw, sh) / mean(b, sw, sh));
        int n = 2 * R + 1;
        long[] cost = new long[n * n];
        par(n, (from, to) -> {
            for (int jy = from; jy < to; jy++) {
                int dy = jy - R;
                for (int jx = 0; jx < n; jx++) {
                    int dx = jx - R;
                    long c = 0;
                    for (int y = R; y < sh - R; y += 2) {
                        int ra = y * sw, rb = (y + dy) * sw + dx;
                        for (int x = R; x < sw - R; x += 2) {
                            int p = a[ra + x], q = Math.min(255, Math.round(b[rb + x] * k));
                            if (p > 245 || q > 245) continue;
                            c += Math.abs(p - q);
                        }
                    }
                    cost[jy * n + jx] = c;
                }
            }
        });
        int best = 0;
        for (int i = 1; i < cost.length; i++) if (cost[i] < cost[best]) best = i;
        int cx = (best % n - R) * f, cy = (best / n - R) * f;
        // точнее — на полном кадре, вокруг грубого ответа, по сетке через 4 пикселя
        final int Q = f - 1;
        int m = 2 * Q + 1;
        long[] fine = new long[m * m];
        int x0 = w / 4, x1 = w * 3 / 4, y0 = h / 4, y1 = h * 3 / 4;
        par(m, (from, to) -> {
            for (int jy = from; jy < to; jy++) {
                int dy = cy + jy - Q;
                for (int jx = 0; jx < m; jx++) {
                    int dx = cx + jx - Q;
                    long c = 0;
                    for (int y = y0; y < y1; y += 4) {
                        int yy = y + dy;
                        if (yy < 0 || yy >= h) continue;
                        for (int x = x0; x < x1; x += 4) {
                            int xx = x + dx;
                            if (xx < 0 || xx >= w) continue;
                            int p = ref[y * w + x] & 0xFF, q = Math.min(255, Math.round((img[yy * w + xx] & 0xFF) * k));
                            if (p > 245 || q > 245) continue;
                            c += Math.abs(p - q);
                        }
                    }
                    fine[jy * m + jx] = c;
                }
            }
        });
        int fb = 0;
        for (int i = 1; i < fine.length; i++) if (fine[i] < fine[fb]) fb = i;
        return new int[]{cx + fb % m - Q, cy + fb / m - Q};
    }

    private static int clampI(int v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    // ── HDR ───────────────────────────────────────────────────────────────

    /**
     * Слить кадры с разной экспозицией. gain[i] — во сколько раз кадр i
     * светлее опорного (по выдержке × ISO), sh[i] — его сдвиг (align).
     */
    static byte[] hdr(byte[][] fr, float[] gain, int ref, int[][] sh, int w, int h) {
        final int f = 8;
        int n = fr.length, mw = w / f + 1, mh = h / f + 1;
        float[][] wt = new float[n][mw * mh];
        int[][] sm = new int[n][];
        for (int i = 0; i < n; i++) sm[i] = small(fr[i], w, h, f);
        int sw = w / f, sh8 = h / f;
        // веса на уменьшенном кадре: «хорошо экспонировано» — около середины
        for (int i = 0; i < n; i++) {
            float g = (float) Math.pow(gain[i], 1 / 2.2);   // во сколько светлее в гамме
            int ox = Math.round(sh[i][0] / (float) f), oy = Math.round(sh[i][1] / (float) f);
            boolean darkest = true, brightest = true;
            for (float o : gain) {
                darkest &= o >= gain[i];
                brightest &= o <= gain[i];
            }
            for (int y = 0; y < mh; y++) {
                for (int x = 0; x < mw; x++) {
                    int sx = Math.min(sw - 1, x), sy = Math.min(sh8 - 1, y);
                    float v = sm[i][clampI(sy + oy, 0, sh8 - 1) * sw + clampI(sx + ox, 0, sw - 1)] / 255f;
                    float r = sm[ref][sy * sw + sx] / 255f;
                    float d = v - 0.5f;
                    float e = (float) Math.exp(-d * d / 0.08f);
                    if (v > 0.95f && !darkest) e *= 0.02f;      // пересвечено — берём из более тёмного
                    if (v < 0.04f && !brightest) e *= 0.1f;     // провал в тень — из более светлого
                    if (i != ref) {
                        // движение: кадр не похож на опорный с поправкой на яркость
                        float want = r * g;
                        if (want > 0.06f && want < 0.92f && v > 0.06f && v < 0.92f && Math.abs(v - want) > 0.12f) e *= 0.04f;
                    } else {
                        e += 0.03f;
                    }
                    wt[i][y * mw + x] = e;
                }
            }
            blur(wt[i], mw, mh, 2);
            blur(wt[i], mw, mh, 2);
        }
        for (int p = 0; p < mw * mh; p++) {
            float s = 0;
            for (int i = 0; i < n; i++) s += wt[i][p];
            for (int i = 0; i < n; i++) wt[i][p] /= s;
        }
        byte[] out = new byte[w * h * 3 / 2];
        int co = w * h;
        // яркость
        par(h, (from, to) -> {
            float[] row = new float[n * w];
            for (int y = from; y < to; y++) {
                weightRow(wt, n, mw, mh, f, w, y, row);
                for (int x = 0; x < w; x++) {
                    float v = 0;
                    for (int i = 0; i < n; i++) {
                        int yy = clampI(y + sh[i][1], 0, h - 1), xx = clampI(x + sh[i][0], 0, w - 1);
                        v += row[i * w + x] * (fr[i][yy * w + xx] & 0xFF);
                    }
                    out[y * w + x] = (byte) clampI(Math.round(v), 0, 255);
                }
            }
        });
        // цвет: те же веса в точке 2x,2y; чуть насыщеннее — слияние его приглушает
        par(h / 2, (from, to) -> {
            float[] row = new float[n * w];
            for (int cy = from; cy < to; cy++) {
                weightRow(wt, n, mw, mh, f, w, cy * 2, row);
                for (int cx = 0; cx < w / 2; cx++) {
                    float vv = 0, uu = 0;
                    for (int i = 0; i < n; i++) {
                        int yy = clampI(cy + (sh[i][1] >> 1), 0, h / 2 - 1), xx = clampI(cx + (sh[i][0] >> 1), 0, w / 2 - 1);
                        int o = co + yy * w + xx * 2;
                        float k = row[i * w + cx * 2];
                        vv += k * ((fr[i][o] & 0xFF) - 128);
                        uu += k * ((fr[i][o + 1] & 0xFF) - 128);
                    }
                    int o = co + cy * w + cx * 2;
                    out[o] = (byte) clampI(Math.round(128 + vv * 1.08f), 0, 255);
                    out[o + 1] = (byte) clampI(Math.round(128 + uu * 1.08f), 0, 255);
                }
            }
        });
        return out;
    }

    /** Веса всех кадров для строки y (билинейно из уменьшенной карты). */
    private static void weightRow(float[][] wt, int n, int mw, int mh, int f, int w, int y, float[] row) {
        float fy = Math.max(0, (y + 0.5f) / f - 0.5f);
        int y0 = Math.min(mh - 2, (int) fy);
        float ty = Math.min(1, fy - y0);
        for (int i = 0; i < n; i++) {
            float[] m = wt[i];
            int a = y0 * mw, b = (y0 + 1) * mw, o = i * w;
            for (int x = 0; x < w; x++) {
                float fx = Math.max(0, (x + 0.5f) / f - 0.5f);
                int x0 = Math.min(mw - 2, (int) fx);
                float tx = Math.min(1, fx - x0);
                float top = m[a + x0] + (m[a + x0 + 1] - m[a + x0]) * tx;
                float bot = m[b + x0] + (m[b + x0 + 1] - m[b + x0]) * tx;
                row[o + x] = top + (bot - top) * ty;
            }
        }
    }

    /** Размытие квадратом радиуса r (по строкам, затем по столбцам). */
    private static void blur(float[] m, int w, int h, int r) {
        float[] t = new float[Math.max(w, h)];
        float k = 1f / (2 * r + 1);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float s = 0;
                for (int j = -r; j <= r; j++) s += m[y * w + clampI(x + j, 0, w - 1)];
                t[x] = s * k;
            }
            System.arraycopy(t, 0, m, y * w, w);
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                float s = 0;
                for (int j = -r; j <= r; j++) s += m[clampI(y + j, 0, h - 1) * w + x];
                t[y] = s * k;
            }
            for (int y = 0; y < h; y++) m[y * w + x] = t[y];
        }
    }

    // ── «Ночь»: сложение серии ────────────────────────────────────────────

    /** Складывает кадры по мере прихода: память — опорный кадр и суммы, а не вся серия. */
    static final class Stack {
        final int w, h;
        final byte[] ref;
        final char[] sum;      // яркость и цвет: Σ значение × вес
        final byte[] wsum;     // Σ весов яркости; у цвета — в своём массиве
        final byte[] csumW;
        private static final int REF = 4, LOW = 1;

        Stack(byte[] first, int w, int h) {
            this.w = w;
            this.h = h;
            ref = first;
            sum = new char[first.length];
            wsum = new byte[w * h];
            csumW = new byte[w * h / 4];
            for (int i = 0; i < first.length; i++) sum[i] = (char) ((first[i] & 0xFF) * REF);
            Arrays.fill(wsum, (byte) REF);
            Arrays.fill(csumW, (byte) REF);
        }

        /** Ещё кадр со сдвигом (dx, dy) относительно опорного. */
        void add(byte[] f, int dx, int dy) {
            par(h, (from, to) -> {
                for (int y = from; y < to; y++) {
                    int yy = clampI(y + dy, 0, h - 1);
                    for (int x = 0; x < w; x++) {
                        int xx = clampI(x + dx, 0, w - 1);
                        int v = f[yy * w + xx] & 0xFF, r = ref[y * w + x] & 0xFF;
                        // порог растёт в тенях: там шума больше
                        int k = Math.abs(v - r) < 14 + (255 - r) / 16 ? REF : LOW;
                        int p = y * w + x;
                        sum[p] += (char) (v * k);
                        wsum[p] += (byte) k;
                    }
                }
            });
            int co = w * h;
            par(h / 2, (from, to) -> {
                for (int cy = from; cy < to; cy++) {
                    int yy = clampI(cy + (dy >> 1), 0, h / 2 - 1);
                    for (int cx = 0; cx < w / 2; cx++) {
                        int xx = clampI(cx + (dx >> 1), 0, w / 2 - 1);
                        int ly = cy * 2, lx = cx * 2;
                        int v = f[clampI(ly + dy, 0, h - 1) * w + clampI(lx + dx, 0, w - 1)] & 0xFF, r = ref[ly * w + lx] & 0xFF;
                        int k = Math.abs(v - r) < 14 + (255 - r) / 16 ? REF : LOW;
                        int o = co + cy * w + cx * 2, s = co + yy * w + xx * 2;
                        sum[o] += (char) ((f[s] & 0xFF) * k);
                        sum[o + 1] += (char) ((f[s + 1] & 0xFF) * k);
                        csumW[cy * (w / 2) + cx] += (byte) k;
                    }
                }
            });
        }

        byte[] result() {
            byte[] out = new byte[ref.length];
            int co = w * h;
            par(h, (from, to) -> {
                for (int p = from * w; p < to * w; p++) out[p] = (byte) ((sum[p] + wsum[p] / 2) / wsum[p]);
            });
            par(h / 2, (from, to) -> {
                for (int cy = from; cy < to; cy++)
                    for (int cx = 0; cx < w / 2; cx++) {
                        int o = co + cy * w + cx * 2, k = csumW[cy * (w / 2) + cx];
                        out[o] = (byte) ((sum[o] + k / 2) / k);
                        out[o + 1] = (byte) ((sum[o + 1] + k / 2) / k);
                    }
            });
            return out;
        }
    }

    // ── тон и резкость ────────────────────────────────────────────────────

    /**
     * Тоновая кривая яркости: lift приподнимает тени (0…0,3), contrast —
     * мягкая S-кривая в середине (0…0,3). Светлое не обрезается.
     */
    static void tone(byte[] d, int w, int h, float lift, float contrast) {
        byte[] lut = new byte[256];
        for (int i = 0; i < 256; i++) {
            float x = i / 255f;
            float y = x + lift * x * (1 - x) * (1 - x) * 2.2f;
            float s = y * y * (3 - 2 * y);                       // гладкая S
            y = y + (s - y) * contrast;
            lut[i] = (byte) clampI(Math.round(y * 255), 0, 255);
        }
        par(h, (from, to) -> {
            for (int p = from * w; p < to * w; p++) d[p] = lut[d[p] & 0xFF];
        });
    }

    /**
     * Мягкая резкость: нерезкое маскирование 3×3 с порогом — мелкий шум
     * не трогаем, сильные края не пересушиваем (прибавка ограничена).
     */
    static void sharpen(byte[] d, int w, int h, float amount) {
        if (amount <= 0) return;
        byte[] s = Arrays.copyOf(d, w * h);
        par(h, (from, to) -> {
            for (int y = Math.max(1, from); y < Math.min(h - 1, to); y++) {
                int o = y * w;
                for (int x = 1; x < w - 1; x++) {
                    int p = o + x;
                    int sum = (s[p - w - 1] & 0xFF) + (s[p - w] & 0xFF) + (s[p - w + 1] & 0xFF)
                            + (s[p - 1] & 0xFF) + (s[p] & 0xFF) + (s[p + 1] & 0xFF)
                            + (s[p + w - 1] & 0xFF) + (s[p + w] & 0xFF) + (s[p + w + 1] & 0xFF);
                    int v = s[p] & 0xFF;
                    int diff = v * 9 - sum;          // ×9 к разнице с размытым
                    if (diff > -27 && diff < 27) continue;
                    int add = Math.round(diff * amount / 9f);
                    add = clampI(add, -24, 24);
                    d[p] = (byte) clampI(v + add, 0, 255);
                }
            }
        });
    }

    private Fusion() {}
}
