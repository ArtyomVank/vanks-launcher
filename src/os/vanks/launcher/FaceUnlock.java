package os.vanks.launcher;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.PointF;
import android.graphics.RectF;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.FaceDetector;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.util.Size;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Вход по лицу силами Vanks — для телефонов, где у Android своего нет (Vanks OS
 * на Galaxy A04: лицо у Samsung — часть One UI). Передняя камера, лицо ищет
 * android.media.FaceDetector, а узнаёт Vanks: лицо приводится к 64×64, яркость
 * выравнивается, и по сетке 6×6 считаются гистограммы локальных узоров (LBP).
 * Такой образ сравнивается с записанными в «Отпечаток и вход» (хи-квадрат,
 * ближайший образец). Узнал два кадра подряд — экран открыт, а системный
 * замок служба открывает сохранённым PIN-кодом.
 *
 * Защита слабая: датчика глубины нет, телефон может открыться по фото или для
 * похожего человека. Поэтому выключается в настройках, а образцы (числа, не
 * снимки) лежат только в данных Vanks.
 */
final class FaceUnlock {
    private static final String FILE = "face";
    private static final int N = 64, GRID = 6, BINS = 59;
    /** Узоры LBP: 58 «равномерных» (не больше двух переходов 0↔1 по кругу) и все прочие — 59-й. */
    private static final byte[] UNIFORM = new byte[256];

    static {
        int k = 0;
        for (int i = 0; i < 256; i++) {
            int t = 0;
            for (int b = 0; b < 8; b++) if (((i >> b) & 1) != ((i >> ((b + 1) & 7)) & 1)) t++;
            UNIFORM[i] = (byte) (t <= 2 ? k++ : 58);
        }
    }

    private FaceUnlock() {}

    // ── хранение ──────────────────────────────────────────────────────────

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, 0);
    }

    static boolean enrolled(Context c) {
        return sp(c).contains("t");
    }

    static boolean on(Context c) {
        return sp(c).getBoolean("on", true);
    }

    static void setOn(Context c, boolean v) {
        sp(c).edit().putBoolean("on", v).apply();
    }

    static boolean cameraAllowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    /** Можно входить по лицу: записано, включено, есть камера и PIN (им открывается Android). */
    static boolean ready(Context c) {
        return enrolled(c) && on(c) && cameraAllowed(c) && Secret.has(c);
    }

    static void clear(Context c) {
        sp(c).edit().remove("t").remove("thr").apply();
        cache = null;
    }

    private static List<float[]> cache;
    private static float cacheThr;

    static void save(Context c, List<float[]> t) {
        // порог: середина расстояний между своими же образцами с запасом — кадры
        // при входе (другой свет, другой наклон) должны пройти, чужие лица — нет
        ArrayList<Float> d = new ArrayList<>();
        for (int i = 0; i < t.size(); i++)
            for (int j = i + 1; j < t.size(); j++) d.add(dist(t.get(i), t.get(j)));
        Collections.sort(d);
        float thr = d.isEmpty() ? 0.5f : d.get(d.size() / 2) * 1.15f;
        ByteBuffer b = ByteBuffer.allocate(t.size() * N_FEAT * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float[] f : t) for (float v : f) b.putFloat(v);
        sp(c).edit().putString("t", Base64.encodeToString(b.array(), Base64.NO_WRAP)).putFloat("thr", thr).apply();
        cache = new ArrayList<>(t);
        cacheThr = thr;
        Log.i("VanksFace", "записано " + t.size() + " образцов, порог " + thr);
    }

    static float threshold(Context c) {
        load(c);
        return cacheThr;
    }

    private static void load(Context c) {
        if (cache != null) return;
        String s = sp(c).getString("t", null);
        cache = new ArrayList<>();
        cacheThr = sp(c).getFloat("thr", 0.5f);
        if (s == null) return;
        ByteBuffer b = ByteBuffer.wrap(Base64.decode(s, Base64.NO_WRAP)).order(ByteOrder.LITTLE_ENDIAN);
        while (b.remaining() >= N_FEAT * 4) {
            float[] f = new float[N_FEAT];
            for (int i = 0; i < N_FEAT; i++) f[i] = b.getFloat();
            cache.add(f);
        }
    }

    /** Расстояние до ближайшего записанного образца (меньше — похожее). */
    static float best(Context c, float[] f) {
        load(c);
        float m = Float.MAX_VALUE;
        for (float[] t : cache) m = Math.min(m, dist(f, t));
        return m;
    }

    // ── образ лица ───────────────────────────────────────────────────────

    static final int N_FEAT = GRID * GRID * BINS;

    /** Хи-квадрат между образами, в среднем на клетку сетки. */
    static float dist(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            float p = a[i] + b[i];
            if (p > 0) {
                float q = a[i] - b[i];
                s += q * q / p;
            }
        }
        return (float) (s / (GRID * GRID));
    }

    /** Найденное лицо: рамка в кадре (для превью) и его образ. */
    static final class Face {
        final RectF box = new RectF();
        float[] feat;
    }

    /** Найти лицо в кадре gray (w×h, лицом вверх) и посчитать образ; null — лица нет. */
    static Face find(byte[] gray, int w, int h) {
        // искать — в уменьшенном кадре (FaceDetector медленный, ширина должна быть чётной)
        int sw = 240, sh = Math.round(240f * h / w);
        float k = (float) w / sw;
        int[] px = new int[sw * sh];
        for (int y = 0; y < sh; y++) {
            int yy = Math.min(h - 1, Math.round(y * k)) * w;
            for (int x = 0; x < sw; x++) {
                int v = gray[yy + Math.min(w - 1, Math.round(x * k))] & 255;
                px[y * sw + x] = 0xFF000000 | v << 16 | v << 8 | v;
            }
        }
        Bitmap bm = Bitmap.createBitmap(px, sw, sh, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.RGB_565, false);
        FaceDetector.Face[] fs = new FaceDetector.Face[1];
        int n = new FaceDetector(sw, sh, 1).findFaces(bm, fs);
        bm.recycle();
        if (n < 1 || fs[0] == null || fs[0].confidence() < 0.4f) return null;
        PointF m = new PointF();
        fs[0].getMidPoint(m);
        float eyes = fs[0].eyesDistance() * k, cx = m.x * k, cy = m.y * k;
        // слишком далеко — мелко для узоров
        if (eyes < w * 0.12f) return null;
        Face f = new Face();
        float side = eyes * 2.2f, top = cy - eyes * 0.85f;
        f.box.set(cx - side / 2, top, cx + side / 2, top + side);
        f.feat = feature(gray, w, h, f.box);
        return f;
    }

    private static float[] feature(byte[] gray, int w, int h, RectF r) {
        // лицо — в 64×64 (билинейно), яркость выровнять гистограммой
        int[] a = new int[N * N];
        float sx = r.width() / N, sy = r.height() / N;
        for (int y = 0; y < N; y++)
            for (int x = 0; x < N; x++) {
                float fx = r.left + (x + 0.5f) * sx, fy = r.top + (y + 0.5f) * sy;
                int x0 = clamp((int) fx, w - 2), y0 = clamp((int) fy, h - 2);
                float tx = Math.max(0, Math.min(1, fx - x0)), ty = Math.max(0, Math.min(1, fy - y0));
                float v = (gray[y0 * w + x0] & 255) * (1 - tx) * (1 - ty) + (gray[y0 * w + x0 + 1] & 255) * tx * (1 - ty)
                        + (gray[(y0 + 1) * w + x0] & 255) * (1 - tx) * ty + (gray[(y0 + 1) * w + x0 + 1] & 255) * tx * ty;
                a[y * N + x] = Math.round(v);
            }
        int[] hist = new int[256];
        for (int v : a) hist[v]++;
        int[] cdf = new int[256];
        int s = 0;
        for (int i = 0; i < 256; i++) cdf[i] = s += hist[i];
        for (int i = 0; i < a.length; i++) a[i] = cdf[a[i]] * 255 / a.length;
        // узоры по клеткам
        float[] f = new float[N_FEAT];
        int inner = N - 2;
        int[] cnt = new int[GRID * GRID];
        for (int y = 1; y < N - 1; y++)
            for (int x = 1; x < N - 1; x++) {
                int c = a[y * N + x], code = 0;
                if (a[(y - 1) * N + x - 1] >= c) code |= 1;
                if (a[(y - 1) * N + x] >= c) code |= 2;
                if (a[(y - 1) * N + x + 1] >= c) code |= 4;
                if (a[y * N + x + 1] >= c) code |= 8;
                if (a[(y + 1) * N + x + 1] >= c) code |= 16;
                if (a[(y + 1) * N + x] >= c) code |= 32;
                if (a[(y + 1) * N + x - 1] >= c) code |= 64;
                if (a[y * N + x - 1] >= c) code |= 128;
                int cell = Math.min(GRID - 1, (y - 1) * GRID / inner) * GRID + Math.min(GRID - 1, (x - 1) * GRID / inner);
                f[cell * BINS + UNIFORM[code]]++;
                cnt[cell]++;
            }
        for (int cell = 0; cell < GRID * GRID; cell++)
            for (int b = 0; b < BINS; b++) f[cell * BINS + b] /= Math.max(1, cnt[cell]);
        return f;
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(max, v));
    }

    // ── камера ───────────────────────────────────────────────────────────

    interface Sink {
        /** Кадр лицом вверх (на потоке камеры): яркость w×h. */
        void frame(byte[] gray, int w, int h);

        void error(String msg);
    }

    /** Передняя камера: кадры яркости в Sink, пока не закрыли. */
    static final class Camera {
        private HandlerThread th;
        private Handler h;
        private CameraDevice dev;
        private CameraCaptureSession ses;
        private ImageReader rd;
        private volatile boolean closed;
        private int orient;

        boolean open(Context c, Sink sink) {
            if (!cameraAllowed(c)) return false;
            CameraManager cm = c.getSystemService(CameraManager.class);
            String id = null;
            Size size = null;
            try {
                for (String i : cm.getCameraIdList()) {
                    CameraCharacteristics ch = cm.getCameraCharacteristics(i);
                    Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                    if (facing == null || facing != CameraCharacteristics.LENS_FACING_FRONT) continue;
                    id = i;
                    Integer o = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    orient = o != null ? o : 270;
                    size = pick(ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP));
                    break;
                }
            } catch (Exception e) {
                return false;
            }
            if (id == null || size == null) return false;
            th = new HandlerThread("vanks-face");
            th.start();
            h = new Handler(th.getLooper());
            rd = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
            rd.setOnImageAvailableListener(r -> {
                Image im = r.acquireLatestImage();
                if (im == null) return;
                int w = im.getWidth(), hh = im.getHeight();
                byte[] y = new byte[w * hh];
                try {
                    Image.Plane p = im.getPlanes()[0];
                    ByteBuffer b = p.getBuffer();
                    int rs = p.getRowStride(), ps = p.getPixelStride();
                    for (int row = 0; row < hh; row++) {
                        if (ps == 1) {
                            b.position(row * rs);
                            b.get(y, row * w, w);
                        } else for (int x = 0; x < w; x++) y[row * w + x] = b.get(row * rs + x * ps);
                    }
                } catch (RuntimeException e) {
                    return;
                } finally {
                    im.close();
                }
                if (closed) return;
                int rot = orient % 360;
                byte[] up = rotate(y, w, hh, rot);
                boolean side = rot == 90 || rot == 270;
                sink.frame(up, side ? hh : w, side ? w : hh);
            }, h);
            try {
                cm.openCamera(id, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(CameraDevice d) {
                        if (closed) {
                            d.close();
                            return;
                        }
                        dev = d;
                        try {
                            d.createCaptureSession(Collections.singletonList(rd.getSurface()), new CameraCaptureSession.StateCallback() {
                                @Override
                                public void onConfigured(CameraCaptureSession s) {
                                    if (closed) {
                                        s.close();
                                        return;
                                    }
                                    ses = s;
                                    try {
                                        CaptureRequest.Builder b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                        b.addTarget(rd.getSurface());
                                        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                                        s.setRepeatingRequest(b.build(), null, h);
                                    } catch (Exception e) {
                                        sink.error("Камера не запустилась");
                                    }
                                }

                                @Override
                                public void onConfigureFailed(CameraCaptureSession s) {
                                    sink.error("Камера не запустилась");
                                }
                            }, h);
                        } catch (Exception e) {
                            sink.error("Камера не запустилась");
                        }
                    }

                    @Override
                    public void onDisconnected(CameraDevice d) {
                        d.close();
                    }

                    @Override
                    public void onError(CameraDevice d, int e) {
                        d.close();
                        sink.error("Камера занята");
                    }
                }, h);
            } catch (Exception e) {
                close();
                return false;
            }
            return true;
        }

        void close() {
            closed = true;
            if (h == null) return;
            h.post(() -> {
                try {
                    if (ses != null) ses.close();
                    if (dev != null) dev.close();
                    if (rd != null) rd.close();
                } catch (RuntimeException ignored) {
                }
                th.quitSafely();
            });
        }

        /** Кадр около 640×480: лицу хватает, а считать быстро. */
        private static Size pick(StreamConfigurationMap m) {
            if (m == null) return null;
            Size[] all = m.getOutputSizes(ImageFormat.YUV_420_888);
            if (all == null || all.length == 0) return null;
            Size best = null;
            for (Size s : all) {
                long px = (long) s.getWidth() * s.getHeight();
                if (px < 300_000 || px > 1_300_000) continue;
                if (best == null || Math.abs(px - 307_200) < Math.abs((long) best.getWidth() * best.getHeight() - 307_200)) best = s;
            }
            if (best == null) {
                Size[] s = Arrays.copyOf(all, all.length);
                Arrays.sort(s, (p, q) -> Long.compare((long) p.getWidth() * p.getHeight(), (long) q.getWidth() * q.getHeight()));
                best = s[s.length / 2];
            }
            return best;
        }
    }

    /** Повернуть кадр по часовой на deg (0, 90, 180, 270). */
    static byte[] rotate(byte[] in, int w, int h, int deg) {
        if (deg == 0) return in;
        byte[] out = new byte[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                byte v = in[y * w + x];
                switch (deg) {
                    case 90: out[x * h + (h - 1 - y)] = v; break;
                    case 180: out[(h - 1 - y) * w + (w - 1 - x)] = v; break;
                    default: out[(w - 1 - x) * h + y] = v; break;   // 270
                }
            }
        return out;
    }

    // ── вход по лицу на экране блокировки ────────────────────────────────

    interface Result {
        void ok();

        /** Не узнал за отведённое время (или камера не открылась). */
        void fail(String msg);
    }

    /** Искать и узнавать лицо до timeoutMs; ответ — на главном потоке. Возвращает «остановить» (без ответа). */
    static Runnable scan(Context c, long timeoutMs, Result r) {
        Handler ui = new Handler(c.getMainLooper());
        Camera cam = new Camera();
        long until = System.currentTimeMillis() + timeoutMs;
        final int[] hits = {0};
        final boolean[] done = {false};
        Runnable finish = () -> {
            if (done[0]) return;
            done[0] = true;
            cam.close();
            r.fail("Лицо не узнано");
        };
        boolean opened = cam.open(c, new Sink() {
            @Override
            public void frame(byte[] gray, int w, int h) {
                if (done[0]) return;
                if (System.currentTimeMillis() > until) {
                    ui.post(finish);
                    return;
                }
                Face f = find(gray, w, h);
                if (f == null) {
                    hits[0] = 0;
                    return;
                }
                float d = best(c, f.feat), thr = threshold(c);
                Log.i("VanksFace", "расстояние " + d + ", порог " + thr);
                hits[0] = d < thr ? hits[0] + 1 : 0;
                if (hits[0] >= 2) ui.post(() -> {
                    if (done[0]) return;
                    done[0] = true;
                    cam.close();
                    r.ok();
                });
            }

            @Override
            public void error(String msg) {
                ui.post(() -> {
                    if (done[0]) return;
                    done[0] = true;
                    cam.close();
                    r.fail(msg);
                });
            }
        });
        Runnable stop = () -> {
            done[0] = true;
            ui.removeCallbacks(finish);
            cam.close();
        };
        if (!opened) {
            done[0] = true;
            ui.post(() -> r.fail("Камера недоступна"));
            return stop;
        }
        ui.postDelayed(finish, timeoutMs + 300);
        return stop;
    }
}
