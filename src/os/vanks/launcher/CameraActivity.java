package os.vanks.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.params.TonemapCurve;
import android.media.CamcorderProfile;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaActionSound;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Камера Vanks (только в Vanks OS). Режимы — «Ночь», «Про», «Фото», «Видео»,
 * «Таймлапс»: касание подписи или свайп по превью вбок.
 *
 * Снимок проходит свою обработку (Fusion): HDR из трёх экспозиций (сам, когда
 * в кадре и пересвет, и тени), «Ночь» — сложение серии из восьми кадров,
 * мягкая резкость вместо пересушенной резкости камеры, тоновая кривая.
 * Превью и видео — с «HDR-тоном»: кривая камеры поднимает тени и бережёт свет.
 *
 * Объективы задней стороны ищутся по фокусному расстоянию и размеру матрицы,
 * включая скрытые от списка камер; зум фото — до 10x (сверх камеры —
 * обрезкой). Форматы кадра 3:4, 9:16, 1:1 и во весь экран, сетка, таймер,
 * вспышка экраном для селфи и зеркальное селфи. «Про»: ISO, выдержка, фокус,
 * баланс белого, экспокоррекция, гистограмма и RAW (DNG). Видео — со звуком,
 * паузой, фонариком, стабилизацией и повышенным битрейтом; таймлапс ×5…×30.
 *
 * Снимки и ролики — в «DCIM/Vanks». Отвечает и другим приложениям:
 * IMAGE_CAPTURE и VIDEO_CAPTURE.
 */
public final class CameraActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final String TAG = "VanksCamera";
    private static final int ASK = 7, ASK_MIC = 8;
    static final int NIGHT = 0, PRO = 1, PHOTO = 2, VIDEO = 3, LAPSE = 4;
    private static final String[] MODES = {"НОЧЬ", "ПРО", "ФОТО", "ВИДЕО", "ТАЙМЛАПС"};
    private static final String[] FLASH = {"Вспышка выкл", "Вспышка авто", "Вспышка вкл"};
    private static final String[] HDR = {"HDR авто", "HDR вкл", "HDR выкл"};
    private static final String[] ASPECT = {"3:4", "9:16", "1:1", "Весь"};
    private static final String[] SHARP = {"Мягкая", "Естественная", "Чёткая"};
    private static final int[] LAPSE_SPEED = {5, 10, 30};
    private static final String DIR = "DCIM/Vanks";
    /** Предел зума фото — дальше камеры снимок обрезается сам. */
    private static final float MAX_ZOOM = 10f;
    /** Дольше этой выдержки кадры серии смажутся в руке — дальше поднимаем ISO. */
    private static final long HAND_EXP = 83_000_000L;
    private static final int NIGHT_FRAMES = 8;

    /** Объектив: id камеры, угол относительно основной (0,6 — сверхширик), свой зум. */
    private static final class Lens {
        final String id;
        final float rel, zMin, zMax;
        final boolean ratio;     // зум через CONTROL_ZOOM_RATIO (иначе — обрезкой SCALER_CROP_REGION)
        final boolean hidden;    // нет в списке камер, но открывается по id

        Lens(String id, float rel, float zMin, float zMax, boolean ratio, boolean hidden) {
            this.id = id;
            this.rel = rel;
            this.zMin = zMin;
            this.zMax = zMax;
            this.ratio = ratio;
            this.hidden = hidden;
        }
    }

    /** Качество видео из профилей телефона. */
    private static final class Quality {
        String name;
        int w, h, fps, bitrate, aRate, aBits, aCh;
    }

    /** Снимок в работе: кадры серии приходят по одному, потом обработка. */
    private static final class Shot {
        int mode, need, got, ref, deg;
        boolean hdr, mirror, reply;
        float fw = 1, fh = 1;          // какая доля кадра видна (зум, формат), по осям матрицы
        float sharpen;
        int w, h, iso;
        long exp;
        byte[][] frames;
        float[] gains;
        Fusion.Stack stack;
    }

    private CameraManager cm;
    private SharedPreferences prefs;
    private final ArrayList<Lens> back = new ArrayList<>();
    private Lens frontLens, lens;
    private boolean front;
    private int mode = PHOTO;
    private float zoom = 1;      // общий зум: относительно основного объектива
    private float sw = 1;        // программная часть зума (сверх того, что даёт камера)
    private String lensInfo = "";

    private CameraCharacteristics chars;
    private int sensor;          // поворот матрицы
    private int deviceDeg;       // как держат телефон (0, 90, 180, 270)
    private Rect active;         // область матрицы
    private boolean hasFlash, hasRaw, manualSensor, curveOk;
    private TonemapCurve curve;
    private CameraDevice dev;
    private CameraCaptureSession ses;
    private CaptureRequest.Builder preview;
    private Surface view;
    private Size pvSize;
    private ImageReader yuv, raw, look;
    private HandlerThread thread;
    private Handler bg;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger pending = new AtomicInteger();

    // видео
    private final ArrayList<Quality> qualities = new ArrayList<>();
    private int qi;
    private Surface recSurface;   // постоянная поверхность записи: сессия не пересоздаётся на каждый ролик
    private MediaRecorder rec;
    private ParcelFileDescriptor recFd;
    private Uri recUri;
    private boolean recording, paused, torch;
    private long recStart, recPausedAt, recPausedTotal;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // профи-режим: индекс 0 — «авто»
    private int[] isoList = {};
    private long[] expList = {};
    private float[] focusList = {};
    private int[] wbList = {}, evList = {};
    private String[] isoNames, expNames, focusNames, wbNames, evNames;
    private int isoI, expI, focusI, wbI, evI;
    private boolean rawOn;
    private volatile int autoIso = 100;
    private volatile long autoExp = 16_666_666L;
    private volatile float autoFocus;
    private long proUiAt;
    private int proSel = -1;       // открытая шкала: 0 ISO, 1 выдержка, 2 фокус, 3 ББ, 4 EV

    // сцена (по маленькому кадру): гистограмма, пересвет, тени
    private final int[] hist = new int[64];
    private volatile boolean sceneHdr, dark;
    private long lookAt;
    private boolean nightHinted;

    // снимок и настройки
    private volatile Shot shot;
    private boolean busy;
    private Image rawImage;
    private TotalCaptureResult rawResult;
    private int rawDeg;
    private int flash, hdr, aspect, timer, sharp, lapse;
    private boolean grid, tone, mirror, shutterSound, stab, hiBitrate;
    private Runnable countdown;
    private Bitmap last;
    private Uri lastUri;
    private String lastMime = "image/jpeg";
    private Uri replyTo;         // IMAGE_CAPTURE / VIDEO_CAPTURE: куда положить
    private boolean forResult, forVideo;
    private MediaActionSound sound;
    private OrientationEventListener tilt;

    // вид
    private FrameLayout root, frame;
    private TextureView tv;
    private View shutter, thumb, flip, flashBtn, hdrBtn, timerBtn, moreBtn, ring, gridLines, histView, glow, mask;
    private TextView hint, aspectBtn, qualityBtn, recTime, count, nightTip;
    private LinearLayout modeStrip, zoomBar, topBar, proChips, sheet;
    private Dial dial;
    private int topInset;
    private float downX, downY;
    private boolean multi;
    private ScaleGestureDetector pinch;
    private float[] shownStops;
    private float spin;          // крутилка «обработка» на миниатюре

    /** Камера Vanks — только в Vanks OS; на обычном телефоне значка нет, снимает его камера. */
    static void sync(Context c) {
        int want = Updater.osVersion() != null ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        ComponentName cn = new ComponentName(c, CameraActivity.class);
        PackageManager pm = c.getPackageManager();
        if (pm.getComponentEnabledSetting(cn) != want)
            pm.setComponentEnabledSetting(cn, want, PackageManager.DONT_KILL_APP);
    }

    private static boolean isVideo(int m) {
        return m == VIDEO || m == LAPSE;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Prefs.load(this);
        Ui.base(this);
        Feel.init(this);
        cm = getSystemService(CameraManager.class);
        prefs = getPreferences(MODE_PRIVATE);
        Intent in = getIntent();
        forResult = MediaStore.ACTION_IMAGE_CAPTURE.equals(in.getAction());
        forVideo = MediaStore.ACTION_VIDEO_CAPTURE.equals(in.getAction());
        if (forResult || forVideo) replyTo = output(in);
        flash = prefs.getInt("flash", 0);
        front = prefs.getBoolean("front", false);
        timer = prefs.getInt("timer", 0);
        grid = prefs.getBoolean("grid", false);
        rawOn = prefs.getBoolean("raw", false);
        hdr = prefs.getInt("hdr", 0);
        aspect = prefs.getInt("aspect", 0);
        sharp = prefs.getInt("sharp", 1);
        lapse = prefs.getInt("lapse", 1);
        tone = prefs.getBoolean("tone", true);
        mirror = prefs.getBoolean("mirror", true);
        shutterSound = prefs.getBoolean("sound", true);
        stab = prefs.getBoolean("stab", true);
        hiBitrate = prefs.getBoolean("bitrate", true);
        mode = forResult ? PHOTO : forVideo ? VIDEO
                : MediaStore.INTENT_ACTION_VIDEO_CAMERA.equals(in.getAction()) ? VIDEO : prefs.getInt("mode2", PHOTO);
        findLenses();
        getWindow().setDecorFitsSystemWindows(false);
        build();
        sound = new MediaActionSound();
        sound.load(MediaActionSound.SHUTTER_CLICK);
        sound.load(MediaActionSound.START_VIDEO_RECORDING);
        sound.load(MediaActionSound.STOP_VIDEO_RECORDING);
        tilt = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int o) {
                if (o == ORIENTATION_UNKNOWN) return;
                int d = ((o + 45) / 90 * 90) % 360;
                if (d != deviceDeg) {
                    deviceDeg = d;
                    // значки поворачиваются вслед за телефоном, сам экран — нет
                    float r = -d;
                    for (View v : new View[]{thumb, flip, flashBtn, hdrBtn, timerBtn, moreBtn, aspectBtn, qualityBtn})
                        v.animate().rotation(r < -180 ? r + 360 : r).setDuration(220).start();
                }
            }
        };
        ArrayList<String> ask = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            ask.add(Manifest.permission.CAMERA);
        if (mode == VIDEO && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            ask.add(Manifest.permission.RECORD_AUDIO);
        if (!ask.isEmpty()) requestPermissions(ask.toArray(new String[0]), ASK);
        if (!forResult && !forVideo) loadLast();
    }

    @SuppressWarnings("deprecation")
    private static Uri output(Intent in) {
        Object o = in.getParcelableExtra(MediaStore.EXTRA_OUTPUT);
        return o instanceof Uri ? (Uri) o : null;
    }

    private void toast(String s) {
        runOnUiThread(() -> android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show());
    }

    /** Короткая подпись посреди превью: «Вспышка авто», «Таймер 3 с». */
    private void say(String s) {
        hint.setText(s);
        hint.animate().cancel();
        hint.setAlpha(1);
        hint.animate().alpha(0).setStartDelay(1100).setDuration(300).start();
    }

    // ── объективы ─────────────────────────────────────────────────────────

    /**
     * Задние объективы по углу обзора: фокусное / диагональ матрицы. Первая
     * задняя камера — основная (1x). Кроме списка камер пробуем id до 15:
     * часть телефонов прячет сверхширик и телевик от списка, но открывает по
     * id. Сверхширик внутри логической камеры (зум ниже 1x) виден по её зуму.
     */
    private void findLenses() {
        back.clear();
        frontLens = null;
        float main = 0;
        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> listed = new ArrayList<>();
        try {
            listed.addAll(Arrays.asList(cm.getCameraIdList()));
        } catch (CameraAccessException | RuntimeException ignored) {
        }
        ids.addAll(listed);
        for (int i = 0; i < 16; i++) if (!ids.contains(String.valueOf(i))) ids.add(String.valueOf(i));
        StringBuilder info = new StringBuilder();
        for (String id : ids) {
            CameraCharacteristics ch;
            try {
                ch = cm.getCameraCharacteristics(id);
            } catch (CameraAccessException | RuntimeException e) {
                continue;
            }
            boolean hidden = !listed.contains(id);
            Integer f = ch.get(CameraCharacteristics.LENS_FACING);
            int[] caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            boolean ok = false, logical = false;
            if (caps != null) for (int c : caps) {
                ok |= c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE;
                logical |= c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA;
            }
            float[] fl = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            SizeF ss = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            float eq = fl != null && fl.length > 0 && ss != null
                    ? fl[0] / (float) Math.hypot(ss.getWidth(), ss.getHeight()) : 1;
            float[] zr = zoomRange(ch);
            Log.i(TAG, "камера " + id + (hidden ? " (скрытая)" : "") + ": сторона " + f + ", фокус " + (fl != null && fl.length > 0 ? fl[0] : 0)
                    + " мм, зум " + zr[0] + "–" + zr[1] + (logical ? ", логическая " + ch.getPhysicalCameraIds() : "") + (ok ? "" : ", не для съёмки"));
            if (f == null || !ok) continue;
            boolean ratio = Build.VERSION.SDK_INT >= 30 && ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null;
            if (f == CameraCharacteristics.LENS_FACING_FRONT) {
                if (frontLens == null) frontLens = new Lens(id, 1, zr[0], zr[1], ratio, hidden);
                continue;
            }
            if (f != CameraCharacteristics.LENS_FACING_BACK) continue;
            if (main == 0) main = eq;
            float rel = eq / main;
            boolean dup = false;
            for (Lens l : back) dup |= Math.abs(l.rel - rel) < 0.15f * Math.max(l.rel, rel);
            if (!dup) back.add(new Lens(id, rel, zr[0], zr[1], ratio, hidden));
        }
        back.sort((a, b) -> Float.compare(a.rel, b.rel));
        if (back.isEmpty() && frontLens != null) front = true;
        if (frontLens == null) front = false;
        boolean wide = false;
        for (Lens l : back) {
            if (l.rel * l.zMin < 0.9f) wide = true;
            info.append(info.length() > 0 ? " · " : "").append(l.rel < 0.9f ? "сверхширик " : l.rel > 1.5f ? "телевик " : "основная ")
                    .append(fmt(l.rel)).append("×").append(l.hidden ? " (скрытая)" : "");
        }
        if (frontLens != null) info.append(info.length() > 0 ? " · " : "").append("фронтальная");
        if (!wide && !back.isEmpty()) info.append("\nСверхширика нет: телефон не отдаёт такую камеру");
        lensInfo = info.toString();
    }

    private static float[] zoomRange(CameraCharacteristics ch) {
        if (Build.VERSION.SDK_INT >= 30) {
            Range<Float> r = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (r != null) return new float[]{r.getLower(), r.getUpper()};
        }
        Float m = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        return new float[]{1, m == null ? 1 : m};
    }

    /** Объектив для общего зума: с самым длинным фокусом, что ещё не уже кадра. */
    private Lens lensFor(float z) {
        if (front) return frontLens;
        Lens pick = back.get(0);
        for (Lens l : back) if (l.rel <= z * 1.001f) pick = l;
        return pick;
    }

    private float zoomMin() {
        if (front) return frontLens.zMin;
        float m = Float.MAX_VALUE;
        for (Lens l : back) m = Math.min(m, l.rel * l.zMin);
        return m;
    }

    private float zoomMax() {
        if (front) return frontLens.zMax;
        float m = 1;
        for (Lens l : back) m = Math.max(m, l.rel * l.zMax);
        // видео идёт с камеры напрямую — обрезать его нечем
        return isVideo(mode) ? m : Math.max(m, MAX_ZOOM);
    }

    /** Остановки зума на кнопках: объективы, затем 2, 4, 10 (сколько позволяет режим). */
    private float[] stops() {
        if (front) return new float[0];
        ArrayList<Float> s = new ArrayList<>();
        Lens first = back.get(0);
        if (first.zMin < 0.95f) s.add(first.rel * first.zMin);
        for (Lens l : back) s.add(l.rel);
        float max = zoomMax();
        for (float v : new float[]{2, 4, 10}) {
            if (v > max + 0.01f) continue;
            boolean near = false;
            for (float o : s) near |= Math.abs(o - v) < 0.2f * v;
            if (!near) s.add(v);
        }
        s.sort(Float::compare);
        float[] a = new float[s.size()];
        for (int i = 0; i < a.length; i++) a[i] = s.get(i);
        return a;
    }

    private static String fmt(float z) {
        if (Math.abs(z - Math.round(z)) < 0.05f && z >= 1) return String.valueOf(Math.round(z));
        return String.format(Locale.ROOT, "%.1f", z);
    }

    /**
     * Новый зум. Нужен другой объектив — переходим на него, но во время щипка
     * (settle == false) ждём, пока отпустят: переоткрывать камеру на каждый кадр нельзя.
     */
    private void setZoom(float z, boolean settle) {
        if (lens == null) return;
        zoom = Math.max(zoomMin(), Math.min(zoomMax(), z));
        Lens want = lensFor(zoom);
        if (want != lens && settle) {
            Feel.buzz(tv, Feel.CLICK);
            reopen();
        } else {
            if (preview != null) {
                applyZoom(preview);
                repeat();
            }
            fitPreview();
        }
        zoomUi();
    }

    private void applyZoom(CaptureRequest.Builder r) {
        float local = zoom / lens.rel;
        float hw = Math.max(lens.zMin, Math.min(lens.zMax, local));
        sw = Math.max(1, local / hw);
        if (lens.ratio && Build.VERSION.SDK_INT >= 30) {
            r.set(CaptureRequest.CONTROL_ZOOM_RATIO, hw);
        } else if (active != null) {
            int w = Math.round(active.width() / hw), h = Math.round(active.height() / hw);
            int cx = active.centerX(), cy = active.centerY();
            r.set(CaptureRequest.SCALER_CROP_REGION, new Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2));
        }
    }

    /**
     * Превью «накрывает» рамку кадра (лишнее по краям уходит, как в снимке) и
     * увеличено на программную часть зума. Возвращает видимую долю кадра
     * камеры по ширине и высоте экрана.
     */
    private float[] fitPreview() {
        float[] vis = {1, 1};
        if (pvSize == null || tv.getWidth() == 0) return vis;
        float W = tv.getWidth(), H = tv.getHeight();
        float bw = Math.min(pvSize.getWidth(), pvSize.getHeight()), bh = Math.max(pvSize.getWidth(), pvSize.getHeight());
        float s = Math.max(W / bw, H / bh);
        vis[0] = W / (bw * s) / sw;
        vis[1] = H / (bh * s) / sw;
        float kx = bw * s / W * sw, ky = bh * s / H * sw;
        runOnUiThread(() -> {
            Matrix m = new Matrix();
            m.setScale(kx, ky, W / 2f, H / 2f);
            tv.setTransform(m);
        });
        return vis;
    }

    // ── вид ───────────────────────────────────────────────────────────────

    private View round(Views.Painter p) {
        View v = Views.drawn(this, p);
        v.setBackground(Views.round(0x26FFFFFF, 20));
        return v;
    }

    private TextView chip(String s) {
        TextView t = Views.label(this, s, 12, Ui.bold, 0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(10), 0, Ui.dp(10), 0);
        t.setBackground(Views.round(0x26FFFFFF, 20));
        return t;
    }

    private void build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        // превью со скруглёнными углами: сетка, гистограмма, кольцо фокуса, отсчёт
        frame = new FrameLayout(this);
        frame.setClipToOutline(true);
        frame.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), aspect == 3 && !isVideo(mode) ? 0 : Ui.dpf(18));
            }
        });
        tv = new TextureView(this);
        tv.setSurfaceTextureListener(this);
        frame.addView(tv, new FrameLayout.LayoutParams(-1, -1));
        gridLines = Views.drawn(this, (c, w, h) -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0x55FFFFFF);
            p.setStrokeWidth(Ui.dpf(0.8f));
            for (int i = 1; i < 3; i++) {
                c.drawLine(w * i / 3f, 0, w * i / 3f, h, p);
                c.drawLine(0, h * i / 3f, w, h * i / 3f, p);
            }
        });
        gridLines.setVisibility(grid ? View.VISIBLE : View.GONE);
        frame.addView(gridLines, new FrameLayout.LayoutParams(-1, -1));
        histView = Views.drawn(this, (c, w, h) -> drawHist(c, w, h));
        histView.setBackground(Views.round(0x55000000, 10));
        FrameLayout.LayoutParams hvl = new FrameLayout.LayoutParams(Ui.dp(112), Ui.dp(52), Gravity.TOP | Gravity.START);
        hvl.leftMargin = hvl.topMargin = Ui.dp(12);
        frame.addView(histView, hvl);
        ring = Views.drawn(this, (c, w, h) -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dpf(1.6f));
            p.setColor(0xFFFFD54A);
            c.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f - Ui.dpf(2), p);
        });
        ring.setAlpha(0);
        frame.addView(ring, new FrameLayout.LayoutParams(Ui.dp(72), Ui.dp(72)));
        count = Views.label(this, "", 96, Ui.light, 0xFFFFFFFF);
        count.setShadowLayer(Ui.dpf(8), 0, 0, 0x88000000);
        count.setAlpha(0);
        frame.addView(count, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        hint = Views.label(this, "", 14, Ui.medium, 0xFFFFFFFF);
        hint.setPadding(Ui.dp(14), Ui.dp(7), Ui.dp(14), Ui.dp(7));
        hint.setBackground(Views.round(0x66000000, 16));
        hint.setAlpha(0);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hl.topMargin = Ui.dp(16);
        frame.addView(hint, hl);
        root.addView(frame, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        // формат 1:1 — затемнение над и под квадратом
        mask = Views.drawn(this, (c, w, h) -> {
            if (aspect != 2 || isVideo(mode)) return;
            Paint p = new Paint();
            p.setColor(0xFF000000);
            float top = frame.getY(), side = frame.getWidth(), fh = frame.getHeight();
            float pad = (fh - side) / 2;
            c.drawRect(0, top, w, top + pad, p);
            c.drawRect(0, top + fh - pad, w, top + fh, p);
        });
        root.addView(mask, new FrameLayout.LayoutParams(-1, -1));
        pinch = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                setZoom(zoom * d.getScaleFactor(), false);
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                setZoom(zoom, true);
            }
        });
        tv.setOnTouchListener((v, e) -> touch(e));

        // сверху: вспышка (фонарик), HDR, формат, таймер, качество/скорость, ещё
        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(Ui.dp(10), 0, Ui.dp(10), 0);
        flashBtn = round((c, w, h) -> {
            int col = isVideo(mode) ? (torch ? 0xFFFFD54A : 0xCCFFFFFF) : flash == 0 ? 0x99FFFFFF : 0xFFFFD54A;
            if (isVideo(mode)) Glyph.torch(c, w / 2f, h / 2f, Ui.dpf(22), col, torch);
            else Glyph.bolt(c, w / 2f, h / 2f, Ui.dpf(20), col);
            if (!isVideo(mode) && flash == 1) badge(c, w, h, "A", col);
        });
        flashBtn.setOnClickListener(v -> {
            Feel.buzz(v, Feel.TICK);
            if (isVideo(mode)) {
                torch = !torch;
                say(torch ? "Фонарик вкл" : "Фонарик выкл");
            } else {
                flash = (flash + 1) % 3;
                prefs.edit().putInt("flash", flash).apply();
                say(front && flash != 0 ? FLASH[flash] + " (экраном)" : FLASH[flash]);
            }
            v.invalidate();
            requestUpdate();
        });
        hdrBtn = round((c, w, h) -> {
            boolean on = hdr == 1 || hdr == 0 && sceneHdr;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setTypeface(Ui.bold);
            p.setTextSize(Ui.dpf(11));
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(hdr == 2 ? 0x88FFFFFF : on ? 0xFFFFD54A : 0xFFFFFFFF);
            c.drawText("HDR", w / 2f, h / 2f + Ui.dpf(4), p);
            if (hdr == 0) badge(c, w, h, "A", p.getColor());
            if (hdr == 2) {
                p.setStrokeWidth(Ui.dpf(1.6f));
                c.drawLine(w * 0.24f, h * 0.74f, w * 0.76f, h * 0.26f, p);
            }
        });
        hdrBtn.setOnClickListener(v -> {
            hdr = (hdr + 1) % 3;
            prefs.edit().putInt("hdr", hdr).apply();
            say(HDR[hdr]);
            Feel.buzz(v, Feel.TICK);
            v.invalidate();
        });
        aspectBtn = chip(ASPECT[aspect]);
        aspectBtn.setOnClickListener(v -> {
            aspect = (aspect + 1) % ASPECT.length;
            prefs.edit().putInt("aspect", aspect).apply();
            aspectBtn.setText(ASPECT[aspect]);
            Feel.buzz(v, Feel.TICK);
            say("Кадр " + ASPECT[aspect]);
            layoutPreview(true);
        });
        timerBtn = round((c, w, h) -> {
            int col = timer == 0 ? 0x99FFFFFF : 0xFFFFD54A;
            Glyph.alarm(c, w / 2f, h / 2f, Ui.dpf(20), col);
            if (timer > 0) badge(c, w, h, String.valueOf(timer), col);
        });
        timerBtn.setOnClickListener(v -> {
            timer = timer == 0 ? 3 : timer == 3 ? 10 : 0;
            prefs.edit().putInt("timer", timer).apply();
            say(timer == 0 ? "Таймер выкл" : "Таймер " + timer + " с");
            Feel.buzz(v, Feel.TICK);
            v.invalidate();
        });
        qualityBtn = chip("");
        qualityBtn.setOnClickListener(v -> {
            if (recording) return;
            Feel.buzz(v, Feel.TICK);
            if (mode == LAPSE) {
                lapse = (lapse + 1) % LAPSE_SPEED.length;
                prefs.edit().putInt("lapse", lapse).apply();
                say("Таймлапс ×" + LAPSE_SPEED[lapse]);
                qualityUi();
                return;
            }
            if (qualities.size() < 2) return;
            qi = (qi + 1) % qualities.size();
            prefs.edit().putString("quality", qualities.get(qi).name).apply();
            say("Видео " + qualityText(qualities.get(qi)));
            qualityUi();
            reconfigure();
        });
        moreBtn = round((c, w, h) -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(sheet != null && sheet.getVisibility() == View.VISIBLE ? 0xFFFFD54A : 0xFFFFFFFF);
            for (int i = -1; i <= 1; i++) c.drawCircle(w / 2f + i * Ui.dpf(6), h / 2f, Ui.dpf(2), p);
        });
        moreBtn.setOnClickListener(v -> toggleSheet());
        for (View v : new View[]{flashBtn, hdrBtn, aspectBtn, timerBtn, qualityBtn, moreBtn}) {
            int w = v instanceof TextView ? -2 : Ui.dp(40);
            FrameLayout cell = new FrameLayout(this);
            cell.addView(v, new FrameLayout.LayoutParams(w, Ui.dp(40), Gravity.CENTER));
            topBar.addView(cell, new LinearLayout.LayoutParams(0, Ui.dp(44), 1));
        }
        root.addView(topBar, new FrameLayout.LayoutParams(-1, Ui.dp(44), Gravity.TOP));

        // время записи
        recTime = Views.label(this, "", 15, Ui.medium, 0xFFFFFFFF);
        recTime.setGravity(Gravity.CENTER);
        recTime.setPadding(Ui.dp(14), 0, Ui.dp(14), 0);
        recTime.setBackground(Views.round(0xCCE53935, 18));
        recTime.setVisibility(View.GONE);
        root.addView(recTime, new FrameLayout.LayoutParams(-2, Ui.dp(36), Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        // профи: шкала и плашки значений
        dial = new Dial(this);
        dial.setBackground(Views.round(0x99000000, 18));
        dial.setVisibility(View.GONE);
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-1, Ui.dp(62), Gravity.BOTTOM);
        dl.leftMargin = dl.rightMargin = Ui.dp(12);
        dl.bottomMargin = Ui.dp(282);
        root.addView(dial, dl);
        proChips = new LinearLayout(this);
        proChips.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-1, Ui.dp(50), Gravity.BOTTOM);
        pl.leftMargin = pl.rightMargin = Ui.dp(8);
        pl.bottomMargin = Ui.dp(172);
        root.addView(proChips, pl);

        // подсказка «Ночь», когда темно
        nightTip = chip("☾  Темно — снять в режиме «Ночь»");
        nightTip.setTextSize(13);
        nightTip.setBackground(Views.round(0xCC000000, 18));
        nightTip.setVisibility(View.GONE);
        nightTip.setOnClickListener(v -> {
            nightTip.setVisibility(View.GONE);
            setMode(NIGHT);
        });
        FrameLayout.LayoutParams ntl = new FrameLayout.LayoutParams(-2, Ui.dp(36), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ntl.bottomMargin = Ui.dp(228);
        root.addView(nightTip, ntl);

        // зум
        zoomBar = new LinearLayout(this);
        zoomBar.setGravity(Gravity.CENTER);
        zoomBar.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4));
        zoomBar.setBackground(Views.round(0x55000000, 24));
        FrameLayout.LayoutParams zl = new FrameLayout.LayoutParams(-2, Ui.dp(46), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        zl.bottomMargin = Ui.dp(174);
        root.addView(zoomBar, zl);

        // режимы
        modeStrip = new LinearLayout(this);
        for (int i = 0; i < MODES.length; i++) {
            TextView t = Views.label(this, MODES[i], 13, Ui.bold, 0xCCFFFFFF);
            t.setLetterSpacing(0.1f);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(13), Ui.dp(8), Ui.dp(13), Ui.dp(8));
            int m = i;
            t.setOnClickListener(v -> setMode(m));
            modeStrip.addView(t);
        }
        FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        ml.bottomMargin = Ui.dp(136);
        root.addView(modeStrip, ml);
        modeStrip.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> placeStrip(false));
        modeStrip.setVisibility(forResult || forVideo ? View.INVISIBLE : View.VISIBLE);

        shutter = new View(this) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas c) {
                float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.dpf(4));
                p.setColor(0xFFFFFFFF);
                c.drawCircle(cx, cy, r - Ui.dpf(2), p);
                Shot s = shot;
                if (s != null && s.need > 1) {
                    // серия «Ночи» или HDR: дуга по кадрам
                    p.setColor(0xFFFFD54A);
                    float k = s.got / (float) s.need;
                    c.drawArc(Ui.dpf(2), Ui.dpf(2), getWidth() - Ui.dpf(2), getHeight() - Ui.dpf(2), -90, 360 * k, false, p);
                }
                p.setStyle(Paint.Style.FILL);
                if (isVideo(mode)) {
                    p.setColor(isPressed() ? 0xFFB71C1C : 0xFFE53935);
                    if (recording) {
                        float q = r * 0.42f;
                        c.drawRoundRect(cx - q, cy - q, cx + q, cy + q, Ui.dpf(6), Ui.dpf(6), p);
                    } else {
                        c.drawCircle(cx, cy, r - Ui.dpf(9), p);
                        if (mode == LAPSE) Glyph.alarm(c, cx, cy, r * 0.6f, 0xFFFFFFFF);
                    }
                } else {
                    p.setColor(isPressed() ? 0xFFCCCCCC : 0xFFFFFFFF);
                    c.drawCircle(cx, cy, r - Ui.dpf(8), p);
                    if (mode == NIGHT) Glyph.moon(c, cx, cy, r * 0.55f, 0xFF1D2340, true);
                    if (countdown != null) {
                        // идёт отсчёт: квадрат «стоп»
                        p.setColor(0xFF222222);
                        float q = r * 0.3f;
                        c.drawRoundRect(cx - q, cy - q, cx + q, cy + q, Ui.dpf(4), Ui.dpf(4), p);
                    }
                }
            }
        };
        shutter.setOnClickListener(v -> press());
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(Ui.dp(78), Ui.dp(78), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sl.bottomMargin = Ui.dp(48);
        root.addView(shutter, sl);
        thumb = new View(this) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            final Path clip = new Path();

            @Override
            protected void onDraw(Canvas c) {
                float w = getWidth(), h = getHeight(), r = w / 2;
                clip.reset();
                clip.addCircle(w / 2, h / 2, r, Path.Direction.CW);
                c.save();
                c.clipPath(clip);
                if (last != null) {
                    // по центру, с обрезкой до круга
                    float s = Math.max(w / last.getWidth(), h / last.getHeight());
                    float dw = last.getWidth() * s, dh = last.getHeight() * s;
                    c.drawBitmap(last, null, new RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), p);
                    if (lastMime.startsWith("video")) Glyph.play(c, w / 2, h / 2, Ui.dpf(24), 0xEEFFFFFF, false);
                } else {
                    p.setColor(0x33FFFFFF);
                    c.drawRect(0, 0, w, h, p);
                    Glyph.image(c, w / 2, h / 2, Ui.dpf(20), 0xCCFFFFFF);
                }
                c.restore();
                if (pending.get() > 0) {
                    // обработка: бегущая дуга
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(Ui.dpf(3));
                    p.setColor(0xFFFFD54A);
                    spin = (spin + 9) % 360;
                    c.drawArc(Ui.dpf(1.5f), Ui.dpf(1.5f), w - Ui.dpf(1.5f), h - Ui.dpf(1.5f), spin, 100, false, p);
                    p.setStyle(Paint.Style.FILL);
                    postInvalidateOnAnimation();
                }
            }
        };
        thumb.setOnClickListener(v -> openLast());
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(Ui.dp(50), Ui.dp(50), Gravity.BOTTOM | Gravity.START);
        tl.leftMargin = Ui.dp(38);
        tl.bottomMargin = Ui.dp(62);
        root.addView(thumb, tl);
        thumb.setVisibility(forResult || forVideo ? View.INVISIBLE : View.VISIBLE);
        // смена камеры; во время записи — пауза
        flip = Views.drawn(this, (c, w, h) -> {
            if (recording) Glyph.play(c, w / 2f, h / 2f, Ui.dpf(28), 0xFFFFFFFF, !paused);
            else Glyph.rotate(c, w / 2f, h / 2f, Ui.dpf(24), 0xFFFFFFFF);
        });
        flip.setBackground(Views.round(0x26FFFFFF, 26));
        flip.setOnClickListener(v -> {
            if (recording) {
                if (mode == VIDEO) pauseRec();
                return;
            }
            if (frontLens == null || back.isEmpty() || countdown != null) return;
            front = !front;
            prefs.edit().putBoolean("front", front).apply();
            Feel.buzz(v, Feel.CLICK);
            v.animate().rotationBy(-180).setDuration(300).start();
            zoom = 1;
            reopen();
        });
        FrameLayout.LayoutParams xl = new FrameLayout.LayoutParams(Ui.dp(50), Ui.dp(50), Gravity.BOTTOM | Gravity.END);
        xl.rightMargin = Ui.dp(38);
        xl.bottomMargin = Ui.dp(62);
        root.addView(flip, xl);

        // вспышка экраном для селфи
        glow = new View(this);
        glow.setBackgroundColor(0xFFFFF6E8);
        glow.setVisibility(View.GONE);
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));

        root.setOnApplyWindowInsetsListener((v, in) -> {
            topInset = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()).top;
            ((FrameLayout.LayoutParams) topBar.getLayoutParams()).topMargin = topInset + Ui.dp(6);
            ((FrameLayout.LayoutParams) recTime.getLayoutParams()).topMargin = topInset + Ui.dp(10);
            if (sheet != null) ((FrameLayout.LayoutParams) sheet.getLayoutParams()).topMargin = topInset + Ui.dp(56);
            layoutPreview(false);
            return in;
        });
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            if (r - l != or - ol || bt - t != ob - ot) layoutPreview(false);
        });
        setContentView(root);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.navigationBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        modeUi(false);
    }

    /** Значок-уточнение в углу кнопки: «A» у авто, «3» у таймера. */
    private static void badge(Canvas c, float w, float h, String s, int col) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(col);
        p.setTypeface(Ui.bold);
        p.setTextSize(Ui.dpf(9));
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText(s, w - Ui.dpf(4), h - Ui.dpf(6), p);
    }

    /** Гистограмма яркости «Про» (по маленькому кадру, в логарифме — видны и редкие тона). */
    private void drawHist(Canvas c, float w, float h) {
        int max = 1;
        for (int v : hist) max = Math.max(max, v);
        double lm = Math.log1p(max);
        Path p = new Path();
        float pad = Ui.dpf(6), bw = (w - 2 * pad) / hist.length;
        p.moveTo(pad, h - pad);
        for (int i = 0; i < hist.length; i++) {
            float y = (float) (Math.log1p(hist[i]) / lm) * (h - 2 * pad);
            p.lineTo(pad + i * bw + bw / 2, h - pad - y);
        }
        p.lineTo(w - pad, h - pad);
        p.close();
        Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
        f.setColor(0xCCFFFFFF);
        c.drawPath(p, f);
    }

    /** Рамка превью под формат кадра: 3:4, 9:16, 1:1 (в рамке 3:4), во весь экран; видео — 9:16. */
    private void layoutPreview(boolean anim) {
        if (root == null || root.getWidth() == 0) return;
        FrameLayout.LayoutParams l = (FrameLayout.LayoutParams) frame.getLayoutParams();
        int W = root.getWidth(), Hs = root.getHeight();
        int h, top;
        int a = isVideo(mode) ? 1 : aspect;
        switch (a) {
            case 1: h = Math.round(W * 16 / 9f); top = topInset + Ui.dp(50); break;
            case 3: h = Hs; top = 0; break;
            default: h = Math.round(W * 4 / 3f); top = topInset + Ui.dp(56);
        }
        if (top + h > Hs) top = Math.max(0, Hs - h);
        if (l.height != h || l.topMargin != top) {
            l.height = h;
            l.topMargin = top;
            frame.setLayoutParams(l);
            if (anim) {
                frame.setAlpha(0.4f);
                frame.animate().alpha(1).setDuration(220).start();
            }
        }
        frame.invalidateOutline();
        mask.invalidate();
        tv.post(() -> {
            fitPreview();
            mask.invalidate();
            frame.invalidateOutline();
        });
    }

    /** Подписи режимов: выбранная — по центру экрана, жёлтая. */
    private void placeStrip(boolean anim) {
        if (modeStrip.getWidth() == 0) return;
        View sel = modeStrip.getChildAt(mode);
        float x = root.getWidth() / 2f - (sel.getLeft() + sel.getWidth() / 2f);
        for (int i = 0; i < modeStrip.getChildCount(); i++)
            ((TextView) modeStrip.getChildAt(i)).setTextColor(i == mode ? 0xFFFFD54A : 0xB3FFFFFF);
        if (anim) modeStrip.animate().translationX(x).setDuration(260).setInterpolator(Anim.EMPH).start();
        else modeStrip.setTranslationX(x);
    }

    /** Что видно в режиме. */
    private void modeUi(boolean anim) {
        placeStrip(anim);
        boolean v = isVideo(mode);
        ((View) hdrBtn.getParent()).setVisibility(mode == PHOTO ? View.VISIBLE : View.GONE);
        ((View) aspectBtn.getParent()).setVisibility(v ? View.GONE : View.VISIBLE);
        ((View) timerBtn.getParent()).setVisibility(v ? View.GONE : View.VISIBLE);
        ((View) qualityBtn.getParent()).setVisibility(v ? View.VISIBLE : View.GONE);
        boolean fl = mode != NIGHT && (hasFlash || front && !v);
        ((View) flashBtn.getParent()).setVisibility(fl ? View.VISIBLE : View.GONE);
        flashBtn.invalidate();
        shutter.invalidate();
        proChips.setVisibility(mode == PRO ? View.VISIBLE : View.GONE);
        histView.setVisibility(mode == PRO ? View.VISIBLE : View.GONE);
        // в «Про» под превью — плашки значений, зум поднимается на превью
        FrameLayout.LayoutParams zl = (FrameLayout.LayoutParams) zoomBar.getLayoutParams();
        int zb = Ui.dp(mode == PRO ? 236 : 174);
        if (zl.bottomMargin != zb) {
            zl.bottomMargin = zb;
            zoomBar.setLayoutParams(zl);
        }
        nightTip.setVisibility(View.GONE);
        if (mode != PRO) {
            proSel = -1;
            dial.setVisibility(View.GONE);
        }
        qualityUi();
        zoomUi();
        if (sheet != null && sheet.getVisibility() == View.VISIBLE) fillSheet();
    }

    private void qualityUi() {
        if (mode == LAPSE) qualityBtn.setText("×" + LAPSE_SPEED[lapse]);
        else if (!qualities.isEmpty()) qualityBtn.setText(qualityText(qualities.get(qi)));
    }

    private void setMode(int m) {
        if (m < 0 || m >= MODES.length || m == mode || recording || countdown != null || forResult || forVideo) return;
        boolean wasVideo = isVideo(mode);
        boolean hadRaw = raw != null;
        mode = m;
        prefs.edit().putInt("mode2", m).apply();
        Feel.buzz(modeStrip, Feel.TICK);
        if (m == VIDEO && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, ASK_MIC);
        torch = false;
        modeUi(true);
        layoutPreview(true);
        if (m == NIGHT) say("Держите телефон неподвижно при съёмке");
        // у видео свой предел зума и свои объективы
        float z = Math.max(zoomMin(), Math.min(zoomMax(), zoom));
        zoom = z;
        if (lens != null && lensFor(z) != lens) {
            reopen();
        } else {
            // «Ночь», «Про» и «Фото» — одна сессия: менять её не нужно, кроме RAW
            boolean wantRaw = m == PRO && rawOn && hasRaw;
            if (wasVideo != isVideo(m) || wantRaw != hadRaw) reconfigure();
            else requestUpdate();
        }
    }

    /** Кнопки зума: выбранная показывает точное значение. */
    private void zoomUi() {
        if (zoomBar == null) return;
        float[] st = stops();
        zoomBar.setVisibility(st.length < 2 ? View.GONE : View.VISIBLE);
        int sel = 0;
        for (int i = 0; i < st.length; i++) if (st[i] <= zoom + 0.01f) sel = i;
        if (!Arrays.equals(st, shownStops)) {
            shownStops = st;
            zoomBar.removeAllViews();
            for (float v : st) {
                TextView t = Views.label(this, "", 12, Ui.bold, 0xFFFFFFFF);
                t.setGravity(Gravity.CENTER);
                t.setOnClickListener(x -> {
                    Feel.buzz(x, Feel.TICK);
                    setZoom(v, true);
                });
                LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(Ui.dp(38), Ui.dp(38));
                l.leftMargin = l.rightMargin = Ui.dp(2);
                zoomBar.addView(t, l);
            }
        }
        for (int i = 0; i < st.length; i++) {
            TextView t = (TextView) zoomBar.getChildAt(i);
            boolean on = i == sel;
            t.setText(on ? fmt(zoom) + "×" : fmt(st[i]));
            t.setTextColor(on ? 0xFFFFD54A : 0xFFFFFFFF);
            t.setTextSize(on ? 12 : 11);
            t.setBackground(on ? Views.round(0x55000000, 19) : null);
        }
    }

    private boolean touch(MotionEvent e) {
        pinch.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                multi = false;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                multi = true;
                break;
            case MotionEvent.ACTION_UP:
                if (multi || pinch.isInProgress()) break;
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (Math.abs(dx) > Ui.dp(70) && Math.abs(dx) > 2 * Math.abs(dy)) {
                    // свайп влево — следующий режим (к «Таймлапсу»)
                    setMode(mode + (dx < 0 ? 1 : -1));
                } else if (Math.abs(dx) < Ui.dp(12) && Math.abs(dy) < Ui.dp(12)) {
                    if (sheet != null && sheet.getVisibility() == View.VISIBLE) toggleSheet();
                    else if (proSel >= 0) closeDial();
                    else focusAt(e.getX(), e.getY());
                }
                break;
        }
        return true;
    }

    // ── настройки камеры ──────────────────────────────────────────────────

    private void toggleSheet() {
        Feel.buzz(moreBtn, Feel.TICK);
        if (sheet == null) {
            sheet = new LinearLayout(this);
            sheet.setOrientation(LinearLayout.VERTICAL);
            sheet.setBackground(Views.round(0xF2202124, 22));
            sheet.setPadding(0, Ui.dp(8), 0, Ui.dp(8));
            sheet.setVisibility(View.GONE);
            FrameLayout.LayoutParams l = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
            l.leftMargin = l.rightMargin = Ui.dp(12);
            l.topMargin = topInset + Ui.dp(56);
            root.addView(sheet, l);
        }
        boolean show = sheet.getVisibility() != View.VISIBLE;
        if (show) {
            fillSheet();
            sheet.setVisibility(View.VISIBLE);
            sheet.setAlpha(0);
            sheet.setTranslationY(-Ui.dp(16));
            sheet.animate().alpha(1).translationY(0).setDuration(200).setInterpolator(Anim.EMPH).withEndAction(null).start();
        } else {
            sheet.animate().alpha(0).translationY(-Ui.dp(16)).setDuration(160).withEndAction(() -> sheet.setVisibility(View.GONE)).start();
        }
        moreBtn.post(moreBtn::invalidate);
    }

    private void fillSheet() {
        sheet.removeAllViews();
        TextView t = Views.label(this, "Настройки камеры", 17, Ui.bold, 0xFFFFFFFF);
        t.setPadding(Ui.dp(18), Ui.dp(8), Ui.dp(18), Ui.dp(6));
        sheet.addView(t);
        row("Сетка", grid ? "Вкл" : "Выкл", () -> {
            grid = !grid;
            prefs.edit().putBoolean("grid", grid).apply();
            gridLines.setVisibility(grid ? View.VISIBLE : View.GONE);
        });
        row("Резкость", SHARP[sharp], () -> {
            sharp = (sharp + 1) % SHARP.length;
            prefs.edit().putInt("sharp", sharp).apply();
            requestUpdate();
        });
        row("HDR-тон превью и видео", tone ? "Вкл" : "Выкл", () -> {
            tone = !tone;
            prefs.edit().putBoolean("tone", tone).apply();
            requestUpdate();
        });
        row("Зеркальное селфи", mirror ? "Вкл" : "Выкл", () -> {
            mirror = !mirror;
            prefs.edit().putBoolean("mirror", mirror).apply();
        });
        row("Звук затвора", shutterSound ? "Вкл" : "Выкл", () -> {
            shutterSound = !shutterSound;
            prefs.edit().putBoolean("sound", shutterSound).apply();
        });
        row("Стабилизация видео", stab ? "Вкл" : "Выкл", () -> {
            stab = !stab;
            prefs.edit().putBoolean("stab", stab).apply();
            requestUpdate();
        });
        row("Высокий битрейт видео", hiBitrate ? "Вкл" : "Выкл", () -> {
            hiBitrate = !hiBitrate;
            prefs.edit().putBoolean("bitrate", hiBitrate).apply();
        });
        TextView info = Views.label(this, "Камеры: " + lensInfo, 12, Ui.regular, 0x99FFFFFF);
        info.setPadding(Ui.dp(18), Ui.dp(10), Ui.dp(18), Ui.dp(6));
        sheet.addView(info);
    }

    private void row(String name, String value, Runnable tap) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(18), Ui.dp(11), Ui.dp(14), Ui.dp(11));
        r.addView(Views.label(this, name, 15, Ui.regular, 0xFFFFFFFF), new LinearLayout.LayoutParams(0, -2, 1));
        TextView v = Views.label(this, value, 13, Ui.medium, "Выкл".equals(value) ? 0x99FFFFFF : 0xFFFFD54A);
        v.setPadding(Ui.dp(12), Ui.dp(5), Ui.dp(12), Ui.dp(5));
        v.setBackground(Views.round(0x1FFFFFFF, 14));
        r.addView(v);
        r.setOnClickListener(x -> {
            Feel.buzz(x, Feel.TICK);
            tap.run();
            fillSheet();
        });
        sheet.addView(r);
    }

    // ── профи-режим ───────────────────────────────────────────────────────

    /** Списки значений из возможностей камеры. */
    private void proLists(CameraCharacteristics ch) {
        int[] caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        boolean post = false;
        manualSensor = hasRaw = false;
        if (caps != null) for (int c : caps) {
            manualSensor |= c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR;
            hasRaw |= c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW;
            post |= c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING;
        }
        int[] tm = ch.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES);
        curveOk = false;
        if (post && tm != null) for (int m : tm) curveOk |= m == CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE;
        Integer pts = ch.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS);
        if (curveOk) curve = hdrCurve(pts == null ? 32 : Math.max(2, Math.min(64, pts)));
        Range<Integer> isoR = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        Range<Long> expR = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        ArrayList<Integer> iso = new ArrayList<>();
        iso.add(0);
        if (manualSensor && isoR != null)
            for (int v : new int[]{50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400})
                if (isoR.contains(v)) iso.add(v);
        isoList = new int[iso.size()];
        isoNames = new String[iso.size()];
        for (int i = 0; i < iso.size(); i++) {
            isoList[i] = iso.get(i);
            isoNames[i] = i == 0 ? "А" : String.valueOf(iso.get(i));
        }
        ArrayList<Long> exp = new ArrayList<>();
        exp.add(0L);
        if (manualSensor && expR != null)
            for (int d : new int[]{8000, 4000, 2000, 1000, 500, 250, 125, 60, 30, 15, 8, 4, 2, 1}) {
                long ns = 1_000_000_000L / d;
                if (expR.contains(ns)) exp.add(ns);
            }
        expList = new long[exp.size()];
        expNames = new String[exp.size()];
        for (int i = 0; i < exp.size(); i++) {
            expList[i] = exp.get(i);
            expNames[i] = i == 0 ? "А" : shutterName(exp.get(i));
        }
        Float minF = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        if (manualSensor && minF != null && minF > 0) {
            // диоптрии: 0 — бесконечность, minF — ближе всего; по расстоянию — неравномерно
            int n = 16;
            focusList = new float[n + 1];
            focusNames = new String[n + 1];
            focusNames[0] = "А";
            for (int i = 1; i <= n; i++) {
                float t = (i - 1) / (float) (n - 1);
                float d = minF * t * t;
                focusList[i] = d;
                focusNames[i] = d < 0.01f ? "∞" : distName(1 / d);
            }
        } else {
            focusList = new float[]{0};
            focusNames = new String[]{"А"};
        }
        int[] awb = ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        int[] wbOrder = {CaptureRequest.CONTROL_AWB_MODE_AUTO, CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
                CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT, CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT,
                CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT, CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
                CaptureRequest.CONTROL_AWB_MODE_TWILIGHT, CaptureRequest.CONTROL_AWB_MODE_SHADE};
        String[] wbN = {"А", "Лампа", "Тёплый", "Лампа дн.", "Солнце", "Облачно", "Сумерки", "Тень"};
        ArrayList<Integer> wb = new ArrayList<>();
        ArrayList<String> wbn = new ArrayList<>();
        for (int i = 0; i < wbOrder.length; i++) {
            boolean ok = i == 0;
            if (awb != null) for (int a : awb) ok |= a == wbOrder[i];
            if (ok) {
                wb.add(wbOrder[i]);
                wbn.add(wbN[i]);
            }
        }
        wbList = new int[wb.size()];
        for (int i = 0; i < wb.size(); i++) wbList[i] = wb.get(i);
        wbNames = wbn.toArray(new String[0]);
        Range<Integer> evR = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        android.util.Rational evS = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        ArrayList<Integer> ev = new ArrayList<>();
        ArrayList<String> evn = new ArrayList<>();
        if (evR != null && evS != null && evS.floatValue() > 0) {
            // по полступени
            int per = Math.max(1, Math.round(0.5f / evS.floatValue()));
            for (int k = evR.getLower() / per * per; k <= evR.getUpper(); k += per) {
                if (k < evR.getLower()) continue;
                ev.add(k);
                float e = k * evS.floatValue();
                evn.add(k == 0 ? "0" : String.format(Locale.ROOT, "%+.1f", e));
            }
        }
        if (ev.isEmpty()) {
            ev.add(0);
            evn.add("0");
        }
        evList = new int[ev.size()];
        for (int i = 0; i < ev.size(); i++) evList[i] = ev.get(i);
        evNames = evn.toArray(new String[0]);
        isoI = expI = focusI = wbI = 0;
        evI = ev.indexOf(0);
        if (evI < 0) evI = 0;
    }

    /**
     * «HDR-тон»: кривая камеры вместо её собственной — гамма sRGB, тени
     * чуть подняты, светлое мягко подходит к белому (без жёсткого обреза).
     */
    private static TonemapCurve hdrCurve(int n) {
        float[] c = new float[n * 2];
        for (int i = 0; i < n; i++) {
            float x = i / (float) (n - 1);
            float y = x <= 0.0031308f ? 12.92f * x : (float) (1.055 * Math.pow(x, 1 / 2.4) - 0.055);
            y = y + 0.154f * y * (1 - y) * (1 - y);                 // тени
            float hi = Math.max(0, y - 0.8f) / 0.2f;                 // плечо светлого
            y = y - 0.14f * hi * hi * (1 - hi);
            c[i * 2] = x;
            c[i * 2 + 1] = Math.max(0, Math.min(1, y));
        }
        return new TonemapCurve(c, c, c);
    }

    private static String shutterName(long ns) {
        if (ns >= 1_000_000_000L) return Math.round(ns / 1e9) + "\"";
        return "1/" + Math.round(1e9 / ns);
    }

    private static String distName(float m) {
        if (m >= 10) return Math.round(m) + " м";
        if (m >= 1) return String.format(Locale.ROOT, "%.1f м", m).replace(".0 ", " ");
        return Math.round(m * 100) + " см";
    }

    private boolean manualExposure() {
        return mode == PRO && (isoI > 0 || expI > 0);
    }

    /** Плашки «Про»: название и значение; «А» — автомат, рядом — что он выбрал. */
    private void proUi() {
        if (isoNames == null) return;
        String[][] chips = {
                {"ISO", isoI > 0 ? isoNames[isoI] : "А " + autoIso},
                {"S", expI > 0 ? expNames[expI] : "А " + shutterName(autoExp)},
                {"F", focusI > 0 ? focusNames[focusI] : "АФ"},
                {"ББ", wbNames[wbI]},
                {"EV", manualExposure() ? "—" : evNames[evI]},
                {"RAW", rawOn ? "Вкл" : "Выкл"},
        };
        int n = hasRaw ? 6 : 5;
        if (proChips.getChildCount() != n) {
            proChips.removeAllViews();
            for (int i = 0; i < n; i++) {
                LinearLayout c = new LinearLayout(this);
                c.setOrientation(LinearLayout.VERTICAL);
                c.setGravity(Gravity.CENTER);
                c.addView(Views.label(this, "", 10, Ui.bold, 0x99FFFFFF));
                c.addView(Views.label(this, "", 13, Ui.medium, 0xFFFFFFFF));
                int k = i;
                c.setOnClickListener(v -> chip(k));
                LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -1, 1);
                l.leftMargin = l.rightMargin = Ui.dp(2);
                proChips.addView(c, l);
            }
        }
        for (int i = 0; i < n; i++) {
            LinearLayout c = (LinearLayout) proChips.getChildAt(i);
            boolean set = i == 5 ? rawOn : i == 3 ? wbI > 0 : i == 4 ? evList[evI] != 0 && !manualExposure()
                    : i == 0 ? isoI > 0 : i == 1 ? expI > 0 : focusI > 0;
            ((TextView) c.getChildAt(0)).setText(chips[i][0]);
            TextView v = (TextView) c.getChildAt(1);
            v.setText(chips[i][1]);
            v.setTextColor(set ? 0xFFFFD54A : 0xFFFFFFFF);
            c.setBackground(Views.round(i == proSel ? 0x66FFD54A : 0x66000000, 14));
        }
    }

    private void chip(int k) {
        Feel.buzz(proChips, Feel.TICK);
        if (k == 5) {
            rawOn = !rawOn;
            prefs.edit().putBoolean("raw", rawOn).apply();
            say(rawOn ? "RAW (DNG) + JPEG" : "Только JPEG");
            proUi();
            reconfigure();
            return;
        }
        if (proSel == k) {
            closeDial();
            return;
        }
        proSel = k;
        switch (k) {
            case 0: dial.set(isoNames, isoI, i -> { isoI = i; proChanged(); }); break;
            case 1: dial.set(expNames, expI, i -> { expI = i; proChanged(); }); break;
            case 2: dial.set(focusNames, focusI, i -> { focusI = i; proChanged(); }); break;
            case 3: dial.set(wbNames, wbI, i -> { wbI = i; proChanged(); }); break;
            default: dial.set(evNames, evI, i -> { evI = i; proChanged(); });
        }
        if (dial.getVisibility() != View.VISIBLE) {
            dial.setVisibility(View.VISIBLE);
            dial.setAlpha(0);
            dial.setTranslationY(Ui.dp(12));
            dial.animate().alpha(1).translationY(0).setDuration(180).start();
        }
        proUi();
    }

    private void closeDial() {
        proSel = -1;
        dial.animate().alpha(0).translationY(Ui.dp(12)).setDuration(160).withEndAction(() -> dial.setVisibility(View.GONE)).start();
        proUi();
    }

    private void proChanged() {
        requestUpdate();
        proUi();
    }

    /** Настройки кадра поменялись — применить к превью. */
    private void requestUpdate() {
        if (preview != null) {
            setup(preview, false);
            repeat();
        }
    }

    // ── жизненный цикл ────────────────────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        thread = new HandlerThread("vanks-camera");
        thread.start();
        bg = new Handler(thread.getLooper());
        if (tilt.canDetectOrientation()) tilt.enable();
        if (tv.isAvailable()) openCamera();
    }

    @Override
    protected void onPause() {
        cancelCountdown();
        if (recording) stopRec();
        closeCamera();
        glowOff();
        tilt.disable();
        if (thread != null) {
            thread.quitSafely();
            thread = null;
            bg = null;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (sound != null) sound.release();
        if (view != null) view.release();
        worker.shutdown();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code != ASK) return;
        for (int i = 0; i < perms.length; i++) {
            if (!Manifest.permission.CAMERA.equals(perms[i])) continue;
            if (res[i] == PackageManager.PERMISSION_GRANTED) {
                if (tv.isAvailable()) openCamera();
            } else {
                toast("Камере нужно разрешение на съёмку");
                finish();
            }
        }
    }

    @Override
    public boolean onKeyDown(int key, KeyEvent e) {
        // кнопки громкости — спуск
        if (key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_VOLUME_DOWN || key == KeyEvent.KEYCODE_CAMERA) {
            if (e.getRepeatCount() == 0) press();
            return true;
        }
        return super.onKeyDown(key, e);
    }

    @Override
    public void onBackPressed() {
        if (sheet != null && sheet.getVisibility() == View.VISIBLE) toggleSheet();
        else if (proSel >= 0) closeDial();
        else super.onBackPressed();
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { openCamera(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { fitPreview(); }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture s) {
        if (view != null) {
            view.release();
            view = null;
        }
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {}

    // ── камера ────────────────────────────────────────────────────────────

    /** Сменить камеру (объектив или сторону): с затемнением превью. */
    private void reopen() {
        tv.animate().cancel();
        tv.animate().alpha(0).setDuration(120).withEndAction(() -> {
            closeCamera();
            openCamera();
            tv.animate().alpha(1).setStartDelay(280).setDuration(200).start();
        }).start();
    }

    private void openCamera() {
        if (dev != null || bg == null || !tv.isAvailable() || (back.isEmpty() && frontLens == null)
                || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        zoom = Math.max(zoomMin(), Math.min(zoomMax(), zoom));
        lens = lensFor(zoom);
        Lens opening = lens;
        try {
            chars = cm.getCameraCharacteristics(lens.id);
            Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensor = so == null ? 90 : so;
            active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Boolean fl = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            hasFlash = fl != null && fl;
            proLists(chars);
            qualities(lens.id, chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP));
            runOnUiThread(() -> {
                modeUi(false);
                proUi();
            });
            cm.openCamera(lens.id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice d) {
                    dev = d;
                    configure();
                }

                @Override
                public void onDisconnected(CameraDevice d) {
                    d.close();
                    if (dev == d) dev = null;
                }

                @Override
                public void onError(CameraDevice d, int error) {
                    d.close();
                    if (dev == d) dev = null;
                    if (opening.hidden) {
                        // скрытая камера не открылась — больше её не предлагаем
                        Log.i(TAG, "скрытая камера " + opening.id + " не открылась: " + error);
                        runOnUiThread(() -> {
                            back.remove(opening);
                            zoom = 1;
                            reopen();
                        });
                    } else {
                        toast("Камера занята другим приложением");
                    }
                }
            }, bg);
        } catch (CameraAccessException | RuntimeException e) {
            Log.w(TAG, "открытие", e);
            toast("Не удалось открыть камеру");
        }
    }

    /** Качества видео из профилей телефона (что он умеет кодировать), с самого высокого. */
    @SuppressWarnings("deprecation")
    private void qualities(String id, StreamConfigurationMap map) {
        qualities.clear();
        int cid;
        try {
            cid = Integer.parseInt(id);
        } catch (NumberFormatException e) {
            cid = -1;
        }
        List<Size> ok = Arrays.asList(map.getOutputSizes(MediaRecorder.class));
        int[] qs = {CamcorderProfile.QUALITY_2160P, CamcorderProfile.QUALITY_1080P, CamcorderProfile.QUALITY_720P};
        String[] names = {"4K", "1080p", "720p"};
        for (int i = 0; i < qs.length && cid >= 0; i++) {
            try {
                if (!CamcorderProfile.hasProfile(cid, qs[i])) continue;
                CamcorderProfile p = CamcorderProfile.get(cid, qs[i]);
                if (!ok.contains(new Size(p.videoFrameWidth, p.videoFrameHeight))) continue;
                Quality q = new Quality();
                q.name = names[i];
                q.w = p.videoFrameWidth;
                q.h = p.videoFrameHeight;
                q.fps = p.videoFrameRate;
                q.bitrate = p.videoBitRate;
                q.aRate = p.audioSampleRate;
                q.aBits = p.audioBitRate;
                q.aCh = p.audioChannels;
                qualities.add(q);
            } catch (RuntimeException ignored) {
            }
        }
        if (qualities.isEmpty()) {
            Quality q = new Quality();
            boolean fhd = ok.contains(new Size(1920, 1080));
            q.name = fhd ? "1080p" : "720p";
            q.w = fhd ? 1920 : 1280;
            q.h = fhd ? 1080 : 720;
            q.fps = 30;
            q.bitrate = fhd ? 17_000_000 : 10_000_000;
            q.aRate = 48000;
            q.aBits = 192000;
            q.aCh = 2;
            qualities.add(q);
        }
        String want = prefs.getString("quality", "1080p");
        qi = 0;
        for (int i = 0; i < qualities.size(); i++) if (qualities.get(i).name.equals(want)) qi = i;
    }

    private static String qualityText(Quality q) {
        return q.name + " · " + q.fps;
    }

    /** Самый большой размер 4:3 не шире max; нет 4:3 — самый большой. */
    private static Size best(Size[] all, int max) {
        Size pick = null, any = null;
        for (Size s : all) {
            if (s.getWidth() > max) continue;
            if (any == null || (long) s.getWidth() * s.getHeight() > (long) any.getWidth() * any.getHeight()) any = s;
            if (s.getWidth() * 3 == s.getHeight() * 4
                    && (pick == null || s.getWidth() > pick.getWidth())) pick = s;
        }
        return pick != null ? pick : any != null ? any : all[0];
    }

    /** Превью с тем же соотношением, что у ролика, не шире max. */
    private static Size like(Size[] all, int w, int h, int max) {
        Size pick = null;
        for (Size s : all) {
            if (s.getWidth() > max || (long) s.getWidth() * h != (long) s.getHeight() * w) continue;
            if (pick == null || s.getWidth() > pick.getWidth()) pick = s;
        }
        return pick != null ? pick : best(all, max);
    }

    /** Самый маленький размер 4:3 не уже min — для разбора сцены. */
    private static Size smallest(Size[] all, int min) {
        Size pick = null;
        for (Size s : all) {
            if (s.getWidth() < min || s.getWidth() * 3 != s.getHeight() * 4) continue;
            if (pick == null || s.getWidth() < pick.getWidth()) pick = s;
        }
        return pick != null ? pick : all[all.length - 1];
    }

    /** Режим или качество поменялись — та же камера, новая сессия. */
    private void reconfigure() {
        if (bg != null && dev != null) bg.post(this::configure);
    }

    /**
     * Сессия под режим. Фото: превью + полный кадр YUV (его обрабатываем сами)
     * + маленький YUV для разбора сцены, у «Про» с RAW — RAW вместо разбора.
     * Видео: превью + поверхность записи.
     */
    private void configure() {
        if (dev == null || !tv.isAvailable()) return;
        try {
            if (ses != null) {
                try { ses.close(); } catch (RuntimeException ignored) {}
                ses = null;
            }
            closeOutputs();
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            ArrayList<OutputConfiguration> outs = new ArrayList<>();
            if (isVideo(mode)) {
                Quality q = qualities.get(qi);
                pvSize = like(map.getOutputSizes(SurfaceTexture.class), q.w, q.h, 1920);
                recSurface = MediaCodec.createPersistentInputSurface();
                // поверхность записи узнаёт размер от подготовленного рекордера
                File probe = new File(getCacheDir(), "probe.mp4");
                MediaRecorder r = recorder(q, false, 0);
                r.setOutputFile(probe.getAbsolutePath());
                r.prepare();
                r.release();
                //noinspection ResultOfMethodCallIgnored
                probe.delete();
                outs.add(new OutputConfiguration(recSurface));
            } else {
                Size[] ys = map.getOutputSizes(ImageFormat.YUV_420_888);
                pvSize = best(map.getOutputSizes(SurfaceTexture.class), 1920);
                Size full = best(ys, Integer.MAX_VALUE);
                yuv = ImageReader.newInstance(full.getWidth(), full.getHeight(), ImageFormat.YUV_420_888, 3);
                yuv.setOnImageAvailableListener(this::frame, bg);
                outs.add(new OutputConfiguration(yuv.getSurface()));
                if (mode == PRO && rawOn && hasRaw) {
                    Size[] rs = map.getOutputSizes(ImageFormat.RAW_SENSOR);
                    if (rs != null && rs.length > 0) {
                        Size big = rs[0];
                        for (Size s : rs) if ((long) s.getWidth() * s.getHeight() > (long) big.getWidth() * big.getHeight()) big = s;
                        raw = ImageReader.newInstance(big.getWidth(), big.getHeight(), ImageFormat.RAW_SENSOR, 2);
                        raw.setOnImageAvailableListener(this::savedRaw, bg);
                        outs.add(new OutputConfiguration(raw.getSurface()));
                    }
                }
                if (raw == null) {
                    Size s = smallest(ys, 320);
                    look = ImageReader.newInstance(s.getWidth(), s.getHeight(), ImageFormat.YUV_420_888, 2);
                    look.setOnImageAvailableListener(this::lookAt, bg);
                    outs.add(new OutputConfiguration(look.getSurface()));
                }
            }
            tv.getSurfaceTexture().setDefaultBufferSize(pvSize.getWidth(), pvSize.getHeight());
            if (view == null) view = new Surface(tv.getSurfaceTexture());
            outs.add(0, new OutputConfiguration(view));
            preview = dev.createCaptureRequest(isVideo(mode) ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW);
            preview.addTarget(view);
            if (look != null) preview.addTarget(look.getSurface());
            setup(preview, false);
            runOnUiThread(() -> layoutPreview(false));
            dev.createCaptureSession(new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs,
                    getMainExecutor(), new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (dev == null) return;
                            ses = session;
                            repeat();
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            toast("Камера не запустилась");
                        }
                    }));
        } catch (Exception e) {
            Log.w(TAG, "сессия", e);
            toast("Камера не запустилась");
        }
    }

    /**
     * Всё про кадр в одном месте: зум, экспозиция (авто, вспышка или ручная),
     * фокус, баланс белого, резкость и шумодав, тон; у видео — фонарик,
     * стабилизация и частота кадров. still — кадр для снимка.
     */
    private void setup(CaptureRequest.Builder r, boolean still) {
        applyZoom(r);
        // резкость камеры выключена: своя мягкая резкость после — кадр не «пересушен»
        r.set(CaptureRequest.EDGE_MODE, sharp == 2 ? (still ? CaptureRequest.EDGE_MODE_HIGH_QUALITY : CaptureRequest.EDGE_MODE_FAST)
                : CaptureRequest.EDGE_MODE_OFF);
        r.set(CaptureRequest.NOISE_REDUCTION_MODE, still ? CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY : CaptureRequest.NOISE_REDUCTION_MODE_FAST);
        if (tone && curveOk && curve != null) {
            r.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE);
            r.set(CaptureRequest.TONEMAP_CURVE, curve);
        } else {
            r.set(CaptureRequest.TONEMAP_MODE, still ? CaptureRequest.TONEMAP_MODE_HIGH_QUALITY : CaptureRequest.TONEMAP_MODE_FAST);
        }
        if (isVideo(mode)) {
            r.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            r.set(CaptureRequest.FLASH_MODE, torch ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            r.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            r.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
            r.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0);
            int[] st = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
            boolean can = false;
            if (st != null) for (int s : st) can |= s == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON;
            if (can) r.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, stab
                    ? CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON : CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
            Range<Integer> fps = fps(qualities.get(qi).fps);
            if (fps != null) r.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
            return;
        }
        if (manualExposure()) {
            long exp = expI > 0 ? expList[expI] : autoExp;
            r.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            r.set(CaptureRequest.SENSOR_SENSITIVITY, isoI > 0 ? isoList[isoI] : autoIso);
            r.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp);
            r.set(CaptureRequest.SENSOR_FRAME_DURATION, Math.max(exp, 33_333_333L));
            r.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
        } else {
            boolean lamp = hasFlash && !front && mode != NIGHT;
            r.set(CaptureRequest.CONTROL_AE_MODE, !lamp ? CaptureRequest.CONTROL_AE_MODE_ON
                    : flash == 1 ? CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                    : flash == 2 ? CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH : CaptureRequest.CONTROL_AE_MODE_ON);
            r.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            r.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, mode == PRO && evList.length > 0 ? evList[evI] : 0);
        }
        if (mode == PRO && focusI > 0) {
            r.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            r.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusList[focusI]);
        } else {
            r.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        }
        r.set(CaptureRequest.CONTROL_AWB_MODE, mode == PRO && wbList.length > 0 ? wbList[wbI] : CaptureRequest.CONTROL_AWB_MODE_AUTO);
    }

    /** Ровная частота кадров для видео: [fps, fps], иначе с наибольшим нижним пределом. */
    private Range<Integer> fps(int want) {
        Range<Integer>[] all = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        Range<Integer> pick = null;
        if (all != null) for (Range<Integer> r : all)
            if (r.getUpper() == want && (pick == null || r.getLower() > pick.getLower())) pick = r;
        return pick;
    }

    /** Что выбрал автомат: для подписей «Про», основа серий HDR и «Ночи», «темно ли». */
    private final CameraCaptureSession.CaptureCallback watch = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req, TotalCaptureResult r) {
            if (manualExposure()) return;
            Integer iso = r.get(CaptureResult.SENSOR_SENSITIVITY);
            Long exp = r.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Float fd = r.get(CaptureResult.LENS_FOCUS_DISTANCE);
            if (iso != null) autoIso = iso;
            if (exp != null) autoExp = exp;
            if (fd != null) autoFocus = fd;
            long now = SystemClock.uptimeMillis();
            if (now - proUiAt < 300) return;
            proUiAt = now;
            boolean d = autoIso >= 1600 && autoExp >= 30_000_000L || autoExp >= 60_000_000L;
            runOnUiThread(() -> {
                if (mode == PRO) proUi();
                if (d == dark) return;
                dark = d;
                if (dark && mode == PHOTO && !front && !nightHinted && shot == null && !forResult) {
                    nightHinted = true;
                    nightTip.setVisibility(View.VISIBLE);
                    nightTip.setAlpha(0);
                    nightTip.animate().alpha(1).setDuration(220).start();
                    ui.postDelayed(() -> nightTip.animate().alpha(0).setDuration(300)
                            .withEndAction(() -> nightTip.setVisibility(View.GONE)).start(), 5000);
                }
            });
        }
    };

    private void repeat() {
        if (ses == null || preview == null) return;
        try {
            ses.setRepeatingRequest(preview.build(), watch, bg);
        } catch (CameraAccessException | RuntimeException ignored) {
        }
    }

    /** Маленький кадр (поток камеры): гистограмма и «есть ли в кадре и пересвет, и тени». */
    private void lookAt(ImageReader r) {
        Image img = r.acquireLatestImage();
        if (img == null) return;
        try {
            long now = SystemClock.uptimeMillis();
            if (now - lookAt < 250) return;
            lookAt = now;
            Image.Plane p = img.getPlanes()[0];
            java.nio.ByteBuffer b = p.getBuffer();
            int w = img.getWidth(), h = img.getHeight(), rs = p.getRowStride();
            int[] hs = new int[64];
            int n = 0, hi = 0, lo = 0;
            for (int y = 0; y < h; y += 2) {
                int o = y * rs;
                for (int x = 0; x < w; x += 2) {
                    int v = b.get(o + x) & 0xFF;
                    hs[v >> 2]++;
                    if (v >= 248) hi++;
                    if (v <= 24) lo++;
                    n++;
                }
            }
            float fh = hi / (float) n, fl = lo / (float) n;
            boolean h2 = fh > 0.015f && fl > 0.04f || fh > 0.06f || fl > 0.3f && fh > 0.002f;
            runOnUiThread(() -> {
                System.arraycopy(hs, 0, hist, 0, 64);
                if (mode == PRO) histView.invalidate();
                if (h2 != sceneHdr) {
                    sceneHdr = h2;
                    hdrBtn.invalidate();
                }
            });
        } finally {
            img.close();
        }
    }

    private void closeOutputs() {
        for (ImageReader r : new ImageReader[]{yuv, raw, look}) if (r != null) r.close();
        yuv = raw = look = null;
        if (recSurface != null) {
            recSurface.release();
            recSurface = null;
        }
    }

    private void closeCamera() {
        if (ses != null) {
            try { ses.close(); } catch (RuntimeException ignored) {}
            ses = null;
        }
        if (dev != null) {
            dev.close();
            dev = null;
        }
        closeOutputs();
        preview = null;
        shot = null;
        busy = false;
    }

    /** Касание превью: фокус и замер в этой точке. */
    private void focusAt(float x, float y) {
        ring.animate().cancel();
        ring.setX(x - ring.getWidth() / 2f);
        ring.setY(y - ring.getHeight() / 2f);
        ring.setScaleX(1.4f);
        ring.setScaleY(1.4f);
        ring.setAlpha(1);
        ring.animate().scaleX(1).scaleY(1).setDuration(220).withEndAction(() -> ring.animate().alpha(0).setStartDelay(900).setDuration(300).start()).start();
        if (ses == null || preview == null || active == null) return;
        // из точки на экране — в долю кадра камеры (превью накрывает рамку, часть кадра за краем)
        float[] vis = fitPreview();
        float nx = 0.5f + (x / tv.getWidth() - 0.5f) * vis[0] * sw, ny = 0.5f + (y / tv.getHeight() - 0.5f) * vis[1] * sw;
        if (front) nx = 1 - nx;
        float sx, sy;
        switch (sensor) {
            case 90: sx = ny; sy = 1 - nx; break;
            case 270: sx = 1 - ny; sy = nx; break;
            case 180: sx = 1 - nx; sy = 1 - ny; break;
            default: sx = nx; sy = ny;
        }
        // с зумом видна только середина матрицы (у CONTROL_ZOOM_RATIO области уже в увеличенном кадре)
        float local = zoom / lens.rel;
        float k = lens.ratio && Build.VERSION.SDK_INT >= 30 ? sw : local;
        sx = 0.5f + (sx - 0.5f) / k;
        sy = 0.5f + (sy - 0.5f) / k;
        int half = Math.max(active.width(), active.height()) / (int) (16 * Math.min(k, 4));
        int cx = active.left + Math.round(sx * active.width()), cy = active.top + Math.round(sy * active.height());
        MeteringRectangle m = new MeteringRectangle(Math.max(active.left, cx - half), Math.max(active.top, cy - half),
                half * 2, half * 2, MeteringRectangle.METERING_WEIGHT_MAX - 1);
        try {
            preview.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{m});
            preview.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{m});
            if (!(mode == PRO && focusI > 0) && !isVideo(mode)) {
                preview.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                preview.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                ses.capture(preview.build(), null, bg);
                preview.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            }
            repeat();
        } catch (CameraAccessException | RuntimeException ignored) {
        }
        Feel.buzz(tv, Feel.TICK);
    }

    // ── спуск ─────────────────────────────────────────────────────────────

    private void press() {
        if (isVideo(mode)) {
            if (recording) stopRec();
            else startRec();
            return;
        }
        if (countdown != null) {
            cancelCountdown();
            return;
        }
        if (timer > 0 && !busy && ses != null) countdown(timer);
        else take();
    }

    private void countdown(int from) {
        countdown = new Runnable() {
            int left = from;

            @Override
            public void run() {
                if (left <= 0) {
                    countdown = null;
                    count.setAlpha(0);
                    shutter.invalidate();
                    take();
                    return;
                }
                count.setText(String.valueOf(left));
                count.setAlpha(1);
                count.setScaleX(1.3f);
                count.setScaleY(1.3f);
                count.animate().scaleX(1).scaleY(1).setDuration(300).start();
                Feel.buzz(count, Feel.TICK);
                left--;
                ui.postDelayed(this, 1000);
            }
        };
        shutter.invalidate();
        ui.post(countdown);
    }

    private void cancelCountdown() {
        if (countdown == null) return;
        ui.removeCallbacks(countdown);
        countdown = null;
        count.animate().cancel();
        count.setAlpha(0);
        shutter.invalidate();
    }

    /** Нужен ли HDR этому снимку: «вкл» или «авто» и в кадре и пересвет, и тени. */
    private boolean wantHdr() {
        return mode == PHOTO && !forResult && manualSensor && (hdr == 1 || hdr == 0 && sceneHdr)
                && !(flash == 2 && hasFlash && !front) && sw < 3.5f;
    }

    private void take() {
        if (busy || ses == null || dev == null || yuv == null) return;
        if (pending.get() >= 2) {
            say("Обработка снимков…");
            return;
        }
        busy = true;
        Feel.buzz(shutter, Feel.CLICK);
        boolean screen = front && (flash == 2 || flash == 1 && dark) && mode != NIGHT;
        boolean lamp = !front && hasFlash && flash != 0 && !manualExposure() && mode != NIGHT && !wantHdr();
        if (screen) {
            // вспышка экраном: белый экран на полной яркости, кадр — пока он горит
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = 1f;
            getWindow().setAttributes(lp);
            glow.animate().cancel();
            glow.setAlpha(1);
            glow.setVisibility(View.VISIBLE);
            ui.postDelayed(() -> {
                if (bg != null) bg.post(this::shoot);
                else busy = false;
            }, 380);
            return;
        }
        try {
            if (lamp) {
                // вспышке нужен замер заранее
                preview.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
                ses.capture(preview.build(), null, bg);
                preview.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
                bg.postDelayed(this::shoot, 450);
            } else {
                bg.post(this::shoot);
            }
        } catch (CameraAccessException | RuntimeException e) {
            busy = false;
        }
    }

    /** Снять: один кадр, серию HDR или серию «Ночи» (поток камеры). */
    private void shoot() {
        if (ses == null || dev == null || yuv == null) {
            busy = false;
            runOnUiThread(this::glowOff);
            return;
        }
        try {
            Shot s = new Shot();
            s.mode = mode;
            s.deg = front ? (sensor - deviceDeg + 360) % 360 : (sensor + deviceDeg) % 360;
            s.mirror = front && mirror;
            s.reply = forResult;
            // «Естественная» — лёгкая своя резкость; «Чёткая» — резкость камеры, своя не нужна
            s.sharpen = sharp == 0 ? 0.2f : sharp == 1 ? 0.45f : 0f;
            // видимая часть кадра — по осям матрицы
            float[] vis = fitPreview();
            boolean turned = sensor % 180 == 90;
            s.fw = turned ? vis[1] : vis[0];
            s.fh = turned ? vis[0] : vis[1];
            s.iso = autoIso;
            s.exp = autoExp;
            ArrayList<CaptureRequest> reqs = new ArrayList<>();
            boolean withRaw = raw != null;
            if (mode == NIGHT && manualSensor || wantHdr()) {
                Range<Integer> isoR = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                Range<Long> expR = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                float[] evs;
                if (mode == NIGHT) {
                    evs = new float[NIGHT_FRAMES];
                    s.ref = 0;
                    s.sharpen = Math.min(0.6f, s.sharpen * 1.3f);
                } else {
                    evs = new float[]{-2f, 0f, 1f};
                    s.ref = 1;
                    s.hdr = true;
                }
                s.need = evs.length;
                s.gains = new float[evs.length];
                s.frames = mode == NIGHT ? null : new byte[evs.length][];
                long baseExp = Math.max(1, autoExp);
                int baseIso = Math.max(1, autoIso);
                if (mode == NIGHT) {
                    // ночью — выдержка подольше, ISO пониже: шум кадра меньше, серия его ещё усреднит
                    long e = Math.min(HAND_EXP, baseExp * 2);
                    baseIso = Math.max(isoR != null ? isoR.getLower() : 100, Math.round(baseIso * baseExp / (float) e));
                    baseExp = e;
                }
                for (int i = 0; i < evs.length; i++) {
                    double k = Math.pow(2, evs[i]);
                    long e = Math.round(baseExp * k);
                    int iso = baseIso;
                    if (e > HAND_EXP) {
                        iso = (int) Math.round(iso * e / (double) HAND_EXP);
                        e = HAND_EXP;
                    }
                    if (expR != null) e = Math.max(expR.getLower(), Math.min(expR.getUpper(), e));
                    if (isoR != null) iso = Math.max(isoR.getLower(), Math.min(isoR.getUpper(), iso));
                    s.gains[i] = (float) ((double) e * iso / ((double) baseExp * baseIso));
                    CaptureRequest.Builder r = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                    r.addTarget(yuv.getSurface());
                    setup(r, true);
                    // серия — без кривой камеры (тон сделает слияние) и с замершим фокусом и ББ
                    r.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY);
                    r.set(CaptureRequest.NOISE_REDUCTION_MODE, mode == NIGHT ? CaptureRequest.NOISE_REDUCTION_MODE_FAST : CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
                    r.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                    r.set(CaptureRequest.SENSOR_EXPOSURE_TIME, e);
                    r.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
                    r.set(CaptureRequest.SENSOR_FRAME_DURATION, Math.max(e, 33_333_333L));
                    r.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                    r.set(CaptureRequest.CONTROL_AWB_LOCK, true);
                    if (autoFocus > 0 && !(mode == PRO && focusI > 0)) {
                        r.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                        r.set(CaptureRequest.LENS_FOCUS_DISTANCE, autoFocus);
                    }
                    if (i == s.ref) {
                        s.iso = iso;
                        s.exp = e;
                    }
                    reqs.add(r.build());
                }
            } else {
                s.need = 1;
                s.frames = new byte[1][];
                s.gains = new float[]{1};
                CaptureRequest.Builder r = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                r.addTarget(yuv.getSurface());
                if (withRaw) r.addTarget(raw.getSurface());
                setup(r, true);
                rawDeg = s.deg;
                reqs.add(r.build());
            }
            shot = s;
            CameraCaptureSession.CaptureCallback cb = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession ss, CaptureRequest q, TotalCaptureResult res) {
                    if (!withRaw) return;
                    synchronized (CameraActivity.this) {
                        rawResult = res;
                    }
                    writeDng();
                }
            };
            if (reqs.size() == 1) ses.capture(reqs.get(0), cb, bg);
            else ses.captureBurst(reqs, null, bg);
            runOnUiThread(() -> {
                if (shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK);
                // вспышка-затемнение превью
                tv.animate().cancel();
                tv.setAlpha(0.15f);
                tv.animate().alpha(1).setDuration(s.need > 1 ? 600 : 220).start();
                if (s.need > 1) say(s.mode == NIGHT ? "Не двигайте телефон…" : "HDR");
                shutter.invalidate();
            });
        } catch (CameraAccessException | RuntimeException e) {
            Log.w(TAG, "снимок", e);
            shot = null;
            busy = false;
            runOnUiThread(this::glowOff);
        }
    }

    private void glowOff() {
        if (glow.getVisibility() != View.VISIBLE) return;
        glow.animate().alpha(0).setDuration(200).withEndAction(() -> glow.setVisibility(View.GONE)).start();
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
    }

    /** Кадр снимка пришёл (поток камеры): в NV21 — и в серию или сразу в обработку. */
    private void frame(ImageReader r) {
        Image img = r.acquireNextImage();
        if (img == null) return;
        Shot s = shot;
        byte[] d;
        int w, h;
        try {
            if (s == null) return;
            d = Fusion.nv21(img);
            w = img.getWidth() & ~1;
            h = img.getHeight() & ~1;
        } finally {
            img.close();
        }
        s.w = w;
        s.h = h;
        int i = s.got++;
        if (s.mode == NIGHT && s.need > 1) {
            // «Ночь»: опорный кадр — первый, остальные складываются по приходу
            worker.execute(() -> {
                if (s.stack == null) s.stack = new Fusion.Stack(d, w, h);
                else {
                    int[] sh = Fusion.align(s.stack.ref, d, w, h);
                    s.stack.add(d, sh[0], sh[1]);
                }
            });
        } else {
            s.frames[i] = d;
        }
        runOnUiThread(shutter::invalidate);
        if (s.got < s.need) return;
        shot = null;
        pending.incrementAndGet();
        runOnUiThread(() -> {
            busy = false;
            glowOff();
            shutter.invalidate();
            thumb.invalidate();
        });
        worker.execute(() -> develop(s));
    }

    /** Обработка снимка (поток обработки): слияние, тон, резкость, зеркало, JPEG с EXIF, сохранение. */
    private void develop(Shot s) {
        long t0 = SystemClock.uptimeMillis();
        try {
            byte[] out;
            int w = s.w, h = s.h;
            if (s.stack != null) {
                out = s.stack.result();
                s.stack = null;
                Fusion.tone(out, w, h, 0.16f, 0.06f);
            } else if (s.hdr) {
                int[][] sh = new int[s.frames.length][];
                for (int i = 0; i < s.frames.length; i++)
                    sh[i] = i == s.ref ? new int[]{0, 0} : Fusion.align(s.frames[s.ref], s.frames[i], w, h);
                out = Fusion.hdr(s.frames, s.gains, s.ref, sh, w, h);
                s.frames = null;
                Fusion.tone(out, w, h, 0.10f, 0.12f);
            } else {
                out = s.frames[0];
                s.frames = null;
            }
            Fusion.sharpen(out, w, h, s.sharpen);
            if (s.mirror) {
                if (s.deg % 180 == 90) Fusion.flipRows(out, w, h);
                else Fusion.flipCols(out, w, h);
            }
            int cw = Math.min(w, Math.round(w * s.fw)) & ~1, ch = Math.min(h, Math.round(h * s.fh)) & ~1;
            int cx = (w - cw) / 2 & ~1, cy = (h - ch) / 2 & ~1;
            Rect crop = new Rect(cx, cy, cx + cw, cy + ch);
            byte[] jpg = exif(Fusion.jpeg(out, w, h, crop, 95), s);
            Log.i(TAG, (s.mode == NIGHT ? "ночь" : s.hdr ? "HDR" : "снимок") + " " + crop.width() + "×" + crop.height()
                    + " за " + (SystemClock.uptimeMillis() - t0) + " мс");
            if (s.reply) {
                reply(jpg);
                return;
            }
            Uri uri = insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Vanks_" + stamp() + ".jpg", "image/jpeg");
            if (uri != null) {
                try (OutputStream o = getContentResolver().openOutputStream(uri)) {
                    o.write(jpg);
                }
                publish(uri);
            }
            Bitmap small = thumbOf(jpg);
            runOnUiThread(() -> showLast(uri, "image/jpeg", small));
        } catch (Throwable e) {
            Log.w(TAG, "обработка", e);
            toast(e instanceof OutOfMemoryError ? "Не хватило памяти на обработку" : "Не удалось сохранить снимок");
        } finally {
            pending.decrementAndGet();
            runOnUiThread(thumb::invalidate);
        }
    }

    /** EXIF: поворот, время, камера, ISO и выдержка опорного кадра. */
    private byte[] exif(byte[] jpg, Shot s) throws java.io.IOException {
        File f = new File(getCacheDir(), "shot.jpg");
        try {
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(jpg);
            }
            ExifInterface ex = new ExifInterface(f.getAbsolutePath());
            ex.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(s.deg == 90 ? ExifInterface.ORIENTATION_ROTATE_90
                    : s.deg == 180 ? ExifInterface.ORIENTATION_ROTATE_180
                    : s.deg == 270 ? ExifInterface.ORIENTATION_ROTATE_270 : ExifInterface.ORIENTATION_NORMAL));
            String now = new java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT).format(new java.util.Date());
            ex.setAttribute(ExifInterface.TAG_DATETIME, now);
            ex.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, now);
            ex.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER);
            ex.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL);
            ex.setAttribute(ExifInterface.TAG_SOFTWARE, "Vanks Camera" + (s.mode == NIGHT ? " Night" : s.hdr ? " HDR" : ""));
            ex.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, String.valueOf(s.iso));
            ex.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, String.valueOf(s.exp / 1e9));
            ex.saveAttributes();
            return Files.readAllBytes(f.toPath());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    // ── сохранение ────────────────────────────────────────────────────────

    private static String stamp() {
        return new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new java.util.Date());
    }

    private Uri insert(Uri where, String name, String mime) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, DIR);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        return getContentResolver().insert(where, v);
    }

    private void publish(Uri u) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        getContentResolver().update(u, v, null, null);
    }

    /** RAW пришёл (поток камеры): ждём ещё результат съёмки — без него DNG не собрать. */
    private void savedRaw(ImageReader r) {
        Image img = r.acquireNextImage();
        if (img == null) return;
        synchronized (this) {
            if (rawImage != null) rawImage.close();
            rawImage = img;
        }
        writeDng();
    }

    private void writeDng() {
        Image img;
        TotalCaptureResult res;
        synchronized (this) {
            if (rawImage == null || rawResult == null) return;
            img = rawImage;
            res = rawResult;
            rawImage = null;
            rawResult = null;
        }
        try (DngCreator d = new DngCreator(chars, res)) {
            d.setOrientation(rawDeg == 90 ? ExifInterface.ORIENTATION_ROTATE_90
                    : rawDeg == 180 ? ExifInterface.ORIENTATION_ROTATE_180
                    : rawDeg == 270 ? ExifInterface.ORIENTATION_ROTATE_270 : ExifInterface.ORIENTATION_NORMAL);
            Uri u = insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Vanks_" + stamp() + ".dng", "image/x-adobe-dng");
            if (u != null) {
                try (OutputStream o = getContentResolver().openOutputStream(u)) {
                    d.writeImage(o, img);
                }
                publish(u);
            }
        } catch (Exception e) {
            toast("Не удалось сохранить RAW");
        } finally {
            img.close();
        }
    }

    private static int exifDeg(byte[] jpg) {
        try {
            ExifInterface ex = new ExifInterface(new java.io.ByteArrayInputStream(jpg));
            switch (ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /** Миниатюра снимка с учётом поворота из EXIF. */
    private static Bitmap thumbOf(byte[] jpg) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = jpg.length > 1_500_000 ? 16 : 8;
        Bitmap b = BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
        if (b == null) return null;
        int deg = exifDeg(jpg);
        if (deg == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(deg);
        return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
    }

    private void showLast(Uri u, String mime, Bitmap small) {
        if (u != null) {
            lastUri = u;
            lastMime = mime;
        }
        if (small != null) {
            last = small;
            thumb.setScaleX(0.6f);
            thumb.setScaleY(0.6f);
            thumb.animate().scaleX(1).scaleY(1).setDuration(260).setInterpolator(Anim.EMPH).start();
            thumb.invalidate();
        }
    }

    /** IMAGE_CAPTURE: снимок — приложению, которое просило. */
    private void reply(byte[] jpg) {
        Intent res = new Intent();
        boolean ok = true;
        if (replyTo != null) {
            try (OutputStream o = getContentResolver().openOutputStream(replyTo)) {
                o.write(jpg);
            } catch (Exception e) {
                ok = false;
            }
        } else {
            // без EXTRA_OUTPUT отдают маленькую картинку в data
            res.putExtra("data", thumbOf(jpg));
        }
        boolean done = ok;
        runOnUiThread(() -> {
            setResult(done ? RESULT_OK : RESULT_CANCELED, res);
            finish();
        });
    }

    // ── видео ─────────────────────────────────────────────────────────────

    /**
     * Рекордер: H.264, битрейт профиля или в полтора раза выше — меньше «каши»
     * на листве и воде. speed > 0 — таймлапс (без звука).
     */
    @SuppressWarnings("deprecation")
    private MediaRecorder recorder(Quality q, boolean audio, int speed) {
        MediaRecorder m = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
        boolean sound = audio && speed == 0;
        if (sound) m.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        m.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        m.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        m.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        m.setVideoSize(q.w, q.h);
        m.setVideoFrameRate(q.fps);
        m.setVideoEncodingBitRate(hiBitrate ? q.bitrate * 3 / 2 : q.bitrate);
        if (speed > 0) m.setCaptureRate(q.fps / (double) speed);
        if (sound) {
            m.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            m.setAudioSamplingRate(q.aRate);
            m.setAudioEncodingBitRate(q.aBits);
            m.setAudioChannels(q.aCh);
        }
        m.setInputSurface(recSurface);
        return m;
    }

    /** Рекордер в файл ролика; high — профиль H.264 High (чётче при том же битрейте). */
    private MediaRecorder prepared(boolean audio, int speed, int hint, boolean high) throws java.io.IOException {
        MediaRecorder m = recorder(qualities.get(qi), audio, speed);
        m.setOutputFile(recFd.getFileDescriptor());
        m.setOrientationHint(hint);
        if (high) m.setVideoEncodingProfileLevel(MediaCodecInfo.CodecProfileLevel.AVCProfileHigh, MediaCodecInfo.CodecProfileLevel.AVCLevel41);
        try {
            m.prepare();
        } catch (java.io.IOException | RuntimeException e) {
            m.release();
            throw e;
        }
        return m;
    }

    private void startRec() {
        if (recording || ses == null || recSurface == null || preview == null) return;
        boolean audio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        int speed = mode == LAPSE ? LAPSE_SPEED[lapse] : 0;
        try {
            recUri = replyTo != null ? replyTo
                    : insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, (speed > 0 ? "LAPSE_Vanks_" : "VID_Vanks_") + stamp() + ".mp4", "video/mp4");
            if (recUri == null) throw new IllegalStateException();
            try {
                recFd = getContentResolver().openFileDescriptor(recUri, "rw");
            } catch (Exception e) {
                recFd = getContentResolver().openFileDescriptor(recUri, "w");
            }
            int hint = front ? (sensor - deviceDeg + 360) % 360 : (sensor + deviceDeg) % 360;
            try {
                rec = prepared(audio, speed, hint, true);
            } catch (Exception e) {
                // кодер не умеет High — с профилем по умолчанию
                Log.i(TAG, "H.264 High не подошёл", e);
                rec = prepared(audio, speed, hint, false);
            }
            preview.addTarget(recSurface);
            repeat();
            rec.start();
        } catch (Exception e) {
            Log.w(TAG, "запись", e);
            toast("Не удалось начать запись");
            dropRec();
            return;
        }
        recording = true;
        paused = false;
        recPausedTotal = 0;
        recStart = SystemClock.elapsedRealtime();
        if (shutterSound) sound.play(MediaActionSound.START_VIDEO_RECORDING);
        Feel.buzz(shutter, Feel.CONFIRM);
        topBar.animate().alpha(0).setDuration(160).withEndAction(() -> topBar.setVisibility(View.INVISIBLE)).start();
        modeStrip.animate().alpha(0).setDuration(160).start();
        thumb.animate().alpha(0.3f).setDuration(160).start();
        recTime.setVisibility(View.VISIBLE);
        tick.run();
        shutter.invalidate();
        flip.invalidate();
    }

    /** Время записи раз в полсекунды; на паузе мигает. У таймлапса — и длина ролика. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!recording) return;
            long now = paused ? recPausedAt : SystemClock.elapsedRealtime();
            long s = (now - recStart - recPausedTotal) / 1000;
            String t = String.format(Locale.ROOT, "%s %02d:%02d", paused ? "Пауза" : "●", s / 60, s % 60);
            if (mode == LAPSE) {
                long o = s / LAPSE_SPEED[lapse];
                t += String.format(Locale.ROOT, "  →  %02d:%02d", o / 60, o % 60);
            }
            recTime.setText(t);
            recTime.setAlpha(paused && (SystemClock.uptimeMillis() / 500) % 2 == 0 ? 0.5f : 1f);
            ui.postDelayed(this, 500);
        }
    };

    private void pauseRec() {
        if (!recording || rec == null) return;
        try {
            if (paused) {
                rec.resume();
                recPausedTotal += SystemClock.elapsedRealtime() - recPausedAt;
                paused = false;
            } else {
                rec.pause();
                recPausedAt = SystemClock.elapsedRealtime();
                paused = true;
            }
        } catch (RuntimeException ignored) {
        }
        Feel.buzz(flip, Feel.CLICK);
        flip.invalidate();
    }

    private void stopRec() {
        if (!recording) return;
        recording = false;
        paused = false;
        ui.removeCallbacks(tick);
        if (preview != null && recSurface != null) {
            preview.removeTarget(recSurface);
            repeat();
        }
        boolean ok = true;
        try {
            rec.stop();
        } catch (RuntimeException e) {
            ok = false;   // слишком короткий ролик: кадров не было
        }
        rec.release();
        rec = null;
        try {
            recFd.close();
        } catch (Exception ignored) {
        }
        recFd = null;
        if (shutterSound) sound.play(MediaActionSound.STOP_VIDEO_RECORDING);
        Feel.buzz(shutter, Feel.CLICK);
        Uri u = recUri;
        recUri = null;
        topBar.setVisibility(View.VISIBLE);
        topBar.animate().alpha(1).setDuration(160).start();
        modeStrip.animate().alpha(1).setDuration(160).start();
        thumb.animate().alpha(1).setDuration(160).start();
        recTime.setVisibility(View.GONE);
        shutter.invalidate();
        flip.invalidate();
        if (!ok) {
            if (u != null && u != replyTo) getContentResolver().delete(u, null, null);
            toast("Ролик слишком короткий");
            return;
        }
        if (forVideo) {
            setResult(RESULT_OK, new Intent().setData(u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            finish();
            return;
        }
        new Thread(() -> {
            try {
                publish(u);
                Bitmap b = getContentResolver().loadThumbnail(u, new Size(256, 256), null);
                runOnUiThread(() -> showLast(u, "video/mp4", b));
            } catch (Exception e) {
                runOnUiThread(() -> showLast(u, "video/mp4", null));
            }
        }).start();
    }

    /** Запись не началась: убрать пустой файл. */
    private void dropRec() {
        if (rec != null) {
            rec.release();
            rec = null;
        }
        if (preview != null && recSurface != null) {
            preview.removeTarget(recSurface);
            repeat();
        }
        try {
            if (recFd != null) recFd.close();
        } catch (Exception ignored) {
        }
        recFd = null;
        if (recUri != null && recUri != replyTo) {
            try {
                getContentResolver().delete(recUri, null, null);
            } catch (RuntimeException ignored) {
            }
        }
        recUri = null;
    }

    // ── галерея ───────────────────────────────────────────────────────────

    /** Последний снимок или ролик Vanks — для миниатюры при открытии. */
    private void loadLast() {
        new Thread(() -> {
            Uri files = MediaStore.Files.getContentUri("external");
            try (Cursor c = getContentResolver().query(files,
                    new String[]{MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.Files.FileColumns.MIME_TYPE},
                    "(" + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? OR " + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?) AND "
                            + MediaStore.Files.FileColumns.MEDIA_TYPE + " IN (1, 3) AND " + MediaStore.MediaColumns.MIME_TYPE + " != 'image/x-adobe-dng'",
                    new String[]{DIR + "%", "Pictures/Vanks%"}, MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
                if (c == null || !c.moveToFirst()) return;
                boolean video = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
                Uri u = ContentUris.withAppendedId(video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0));
                String mime = c.getString(2);
                Bitmap b = getContentResolver().loadThumbnail(u, new Size(256, 256), null);
                runOnUiThread(() -> {
                    if (lastUri != null) return;
                    lastUri = u;
                    lastMime = mime == null ? (video ? "video/mp4" : "image/jpeg") : mime;
                    last = b;
                    thumb.invalidate();
                });
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void openLast() {
        if (recording) return;
        Intent i = lastUri != null
                ? new Intent(Intent.ACTION_VIEW).setDataAndType(lastUri, lastMime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                : new Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            toast("Нет приложения для просмотра");
        }
    }
}
