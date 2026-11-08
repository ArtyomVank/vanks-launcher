package os.vanks.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
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
import android.media.CamcorderProfile;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaActionSound;
import android.media.MediaCodec;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Камера Vanks. Режимы «Про», «Фото» и «Видео» — переключаются касанием
 * подписи или свайпом по превью вбок.
 *
 * Объективы задней стороны камера находит сама (сверхширик, основной,
 * телевик — по фокусному расстоянию и размеру матрицы) и переходит между
 * ними кнопками зума и щипком. Зум фото — до 10x: сколько даёт камера, дальше
 * снимок обрезается по центру. Видео — сколько даёт камера.
 *
 * «Про»: ISO, выдержка, ручной фокус, баланс белого, экспокоррекция и RAW
 * (DNG рядом с JPEG). Видео — со звуком, пауза, фонарик, стабилизация,
 * качество по профилям телефона. Таймер 3/10 с и сетка — для фото.
 *
 * Снимки и ролики — в «DCIM/Vanks». Отвечает и другим приложениям:
 * IMAGE_CAPTURE (снимок) и VIDEO_CAPTURE (ролик).
 */
public final class CameraActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final int ASK = 7, ASK_MIC = 8;
    static final int PRO = 0, PHOTO = 1, VIDEO = 2;
    private static final String[] MODES = {"ПРО", "ФОТО", "ВИДЕО"};
    private static final String[] FLASH = {"Вспышка выкл", "Вспышка авто", "Вспышка вкл"};
    private static final String DIR = "DCIM/Vanks";
    /** Предел зума фото — дальше снимок обрезается сам. */
    private static final float MAX_ZOOM = 10f;

    /** Объектив: id камеры, угол относительно основной (0,6 — сверхширик), свой зум. */
    private static final class Lens {
        final String id;
        final float rel, zMin, zMax;
        final boolean ratio;     // зум через CONTROL_ZOOM_RATIO (иначе — обрезкой SCALER_CROP_REGION)

        Lens(String id, float rel, float zMin, float zMax, boolean ratio) {
            this.id = id;
            this.rel = rel;
            this.zMin = zMin;
            this.zMax = zMax;
            this.ratio = ratio;
        }
    }

    /** Качество видео из профилей телефона. */
    private static final class Quality {
        String name;
        int w, h, fps, bitrate, aRate, aBits, aCh;
    }

    private CameraManager cm;
    private SharedPreferences prefs;
    private final ArrayList<Lens> back = new ArrayList<>();
    private Lens frontLens, lens;
    private boolean front;
    private int mode = PHOTO;
    private float zoom = 1;      // общий зум: относительно основного объектива
    private float sw = 1;        // программная часть зума (сверх того, что даёт камера)

    private CameraCharacteristics chars;
    private int sensor;          // поворот матрицы
    private int deviceDeg;       // как держат телефон (0, 90, 180, 270)
    private Rect active;         // область матрицы
    private boolean hasFlash, hasRaw, manualSensor;
    private CameraDevice dev;
    private CameraCaptureSession ses;
    private CaptureRequest.Builder preview;
    private Surface view;
    private Size pvSize;
    private ImageReader jpeg, raw;
    private HandlerThread thread;
    private Handler bg;

    // видео
    private final ArrayList<Quality> qualities = new ArrayList<>();
    private int qi;
    private Surface recSurface;   // постоянная поверхность записи: сессия не пересоздаётся на каждый ролик
    private MediaRecorder rec;
    private ParcelFileDescriptor recFd;
    private Uri recUri;
    private boolean recording, paused, torch;
    private long recStart, recPausedAt, recPausedTotal;
    private final Handler ui = new Handler(android.os.Looper.getMainLooper());

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
    private long proUiAt;
    private int proSel = -1;       // открытая шкала: 0 ISO, 1 выдержка, 2 фокус, 3 ББ, 4 EV

    // снимок
    private boolean busy;
    private float shotSw = 1;
    private int shotDeg;
    private Image rawImage;
    private TotalCaptureResult rawResult;
    private int flash;           // 0 — выкл, 1 — авто, 2 — вкл
    private int timer;           // 0, 3, 10 с
    private boolean grid;
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
    private View shutter, thumb, flip, flashBtn, timerBtn, gridBtn, ring, gridLines;
    private TextView hint, qualityBtn, recTime, count;
    private LinearLayout modeStrip, zoomBar, topBar, proChips;
    private Dial dial;
    private int topInset;
    private float downX, downY;
    private boolean multi;
    private ScaleGestureDetector pinch;
    private float[] shownStops;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Prefs.load(this);
        Ui.init(this);
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
        mode = forResult ? PHOTO : forVideo ? VIDEO
                : MediaStore.INTENT_ACTION_VIDEO_CAMERA.equals(in.getAction()) ? VIDEO : prefs.getInt("mode", PHOTO);
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
                    for (View v : new View[]{thumb, flip, flashBtn, timerBtn, gridBtn, qualityBtn})
                        v.animate().rotation(r > 180 ? r - 360 : r).setDuration(220).start();
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
        hint.animate().alpha(0).setStartDelay(900).setDuration(300).start();
    }

    // ── объективы ─────────────────────────────────────────────────────────

    /**
     * Задние объективы по углу обзора: фокусное / диагональ матрицы. Первая
     * задняя камера — основная (1x). Камеры только для глубины пропускаем.
     */
    private void findLenses() {
        back.clear();
        frontLens = null;
        float main = 0;
        try {
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Integer f = ch.get(CameraCharacteristics.LENS_FACING);
                int[] caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                boolean ok = false;
                if (caps != null) for (int c : caps) ok |= c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE;
                if (f == null || !ok) continue;
                float[] zr = zoomRange(ch);
                boolean ratio = Build.VERSION.SDK_INT >= 30 && ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null;
                if (f == CameraCharacteristics.LENS_FACING_FRONT) {
                    if (frontLens == null) frontLens = new Lens(id, 1, zr[0], zr[1], ratio);
                    continue;
                }
                if (f != CameraCharacteristics.LENS_FACING_BACK) continue;
                float[] fl = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                SizeF ss = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                float eq = fl != null && fl.length > 0 && ss != null
                        ? fl[0] / (float) Math.hypot(ss.getWidth(), ss.getHeight()) : 1;
                if (main == 0) main = eq;
                float rel = eq / main;
                boolean dup = false;
                for (Lens l : back) dup |= Math.abs(l.rel - rel) < 0.15f * Math.max(l.rel, rel);
                if (!dup) back.add(new Lens(id, rel, zr[0], zr[1], ratio));
            }
        } catch (CameraAccessException | RuntimeException ignored) {
        }
        back.sort((a, b) -> Float.compare(a.rel, b.rel));
        if (back.isEmpty() && frontLens != null) front = true;
        if (frontLens == null) front = false;
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
        return mode == VIDEO ? m : Math.max(m, MAX_ZOOM);
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
            softZoom();
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

    /** Зум сверх камеры — увеличением превью (и обрезкой снимка при сохранении). */
    private void softZoom() {
        runOnUiThread(() -> {
            Matrix m = new Matrix();
            m.setScale(sw, sw, tv.getWidth() / 2f, tv.getHeight() / 2f);
            tv.setTransform(m);
        });
    }

    // ── вид ───────────────────────────────────────────────────────────────

    private View round(Views.Painter p) {
        View v = Views.drawn(this, p);
        v.setBackground(Views.round(0x33FFFFFF, 20));
        return v;
    }

    private void build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        // превью с сеткой, кольцом фокуса и отсчётом таймера
        frame = new FrameLayout(this);
        tv = new TextureView(this);
        tv.setSurfaceTextureListener(this);
        frame.addView(tv, new FrameLayout.LayoutParams(-1, -1));
        gridLines = Views.drawn(this, (c, w, h) -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0x66FFFFFF);
            p.setStrokeWidth(Ui.dpf(0.8f));
            for (int i = 1; i < 3; i++) {
                c.drawLine(w * i / 3f, 0, w * i / 3f, h, p);
                c.drawLine(0, h * i / 3f, w, h * i / 3f, p);
            }
        });
        gridLines.setVisibility(grid ? View.VISIBLE : View.GONE);
        frame.addView(gridLines, new FrameLayout.LayoutParams(-1, -1));
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

        // сверху: вспышка, таймер, сетка, качество видео
        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER);
        flashBtn = round((c, w, h) -> {
            int col = mode == VIDEO ? (torch ? 0xFFFFD54A : 0xCCFFFFFF) : flash == 0 ? 0x99FFFFFF : 0xFFFFD54A;
            if (mode == VIDEO) Glyph.torch(c, w / 2f, h / 2f, Ui.dpf(22), col, torch);
            else Glyph.bolt(c, w / 2f, h / 2f, Ui.dpf(20), col);
            if (mode != VIDEO && flash == 1) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(col);
                p.setTypeface(Ui.bold);
                p.setTextSize(Ui.dpf(9));
                c.drawText("A", w * 0.66f, h * 0.36f, p);
            }
        });
        flashBtn.setOnClickListener(v -> {
            Feel.buzz(v, Feel.TICK);
            if (mode == VIDEO) {
                torch = !torch;
                say(torch ? "Фонарик вкл" : "Фонарик выкл");
            } else {
                flash = (flash + 1) % 3;
                prefs.edit().putInt("flash", flash).apply();
                say(FLASH[flash]);
            }
            v.invalidate();
            if (preview != null) {
                setup(preview);
                repeat();
            }
        });
        timerBtn = round((c, w, h) -> {
            int col = timer == 0 ? 0x99FFFFFF : 0xFFFFD54A;
            Glyph.alarm(c, w / 2f, h / 2f, Ui.dpf(20), col);
            if (timer > 0) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(col);
                p.setTypeface(Ui.bold);
                p.setTextSize(Ui.dpf(9));
                p.setTextAlign(Paint.Align.RIGHT);
                c.drawText(String.valueOf(timer), w - Ui.dpf(3), h - Ui.dpf(4), p);
            }
        });
        timerBtn.setOnClickListener(v -> {
            timer = timer == 0 ? 3 : timer == 3 ? 10 : 0;
            prefs.edit().putInt("timer", timer).apply();
            say(timer == 0 ? "Таймер выкл" : "Таймер " + timer + " с");
            Feel.buzz(v, Feel.TICK);
            v.invalidate();
        });
        gridBtn = round((c, w, h) -> Glyph.grid(c, w / 2f, h / 2f, Ui.dpf(18), grid ? 0xFFFFD54A : 0x99FFFFFF));
        gridBtn.setOnClickListener(v -> {
            grid = !grid;
            prefs.edit().putBoolean("grid", grid).apply();
            gridLines.setVisibility(grid ? View.VISIBLE : View.GONE);
            say(grid ? "Сетка вкл" : "Сетка выкл");
            Feel.buzz(v, Feel.TICK);
            v.invalidate();
        });
        qualityBtn = Views.label(this, "", 12, Ui.bold, 0xFFFFFFFF);
        qualityBtn.setGravity(Gravity.CENTER);
        qualityBtn.setPadding(Ui.dp(12), 0, Ui.dp(12), 0);
        qualityBtn.setBackground(Views.round(0x33FFFFFF, 20));
        qualityBtn.setOnClickListener(v -> {
            if (qualities.size() < 2 || recording) return;
            qi = (qi + 1) % qualities.size();
            prefs.edit().putString("quality", qualities.get(qi).name).apply();
            Feel.buzz(v, Feel.TICK);
            say("Видео " + qualityText(qualities.get(qi)));
            qualityBtn.setText(qualityText(qualities.get(qi)));
            reconfigure();
        });
        for (View v : new View[]{flashBtn, timerBtn, gridBtn}) {
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(40));
            l.leftMargin = l.rightMargin = Ui.dp(14);
            topBar.addView(v, l);
        }
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(-2, Ui.dp(40));
        ql.leftMargin = ql.rightMargin = Ui.dp(14);
        topBar.addView(qualityBtn, ql);
        root.addView(topBar, new FrameLayout.LayoutParams(-1, Ui.dp(40), Gravity.TOP));

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
        pl.bottomMargin = Ui.dp(226);
        root.addView(proChips, pl);

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
            t.setLetterSpacing(0.12f);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(8));
            int m = i;
            t.setOnClickListener(v -> setMode(m));
            modeStrip.addView(t);
        }
        FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        ml.bottomMargin = Ui.dp(138);
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
                p.setStyle(Paint.Style.FILL);
                if (mode == VIDEO) {
                    p.setColor(isPressed() ? 0xFFB71C1C : 0xFFE53935);
                    if (recording) {
                        float s = r * 0.42f;
                        c.drawRoundRect(cx - s, cy - s, cx + s, cy + s, Ui.dpf(6), Ui.dpf(6), p);
                    } else {
                        c.drawCircle(cx, cy, r - Ui.dpf(9), p);
                    }
                } else {
                    p.setColor(isPressed() ? 0xFFCCCCCC : 0xFFFFFFFF);
                    c.drawCircle(cx, cy, r - Ui.dpf(8), p);
                    if (countdown != null) {
                        // идёт отсчёт: квадрат «стоп»
                        p.setColor(0xFF222222);
                        float s = r * 0.3f;
                        c.drawRoundRect(cx - s, cy - s, cx + s, cy + s, Ui.dpf(4), Ui.dpf(4), p);
                    }
                }
            }
        };
        shutter.setOnClickListener(v -> press());
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(Ui.dp(78), Ui.dp(78), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sl.bottomMargin = Ui.dp(50);
        root.addView(shutter, sl);
        thumb = new View(this) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            final Path clip = new Path();

            @Override
            protected void onDraw(Canvas c) {
                float w = getWidth(), h = getHeight(), r = Ui.dpf(12);
                clip.reset();
                clip.addRoundRect(0, 0, w, h, r, r, Path.Direction.CW);
                c.save();
                c.clipPath(clip);
                if (last != null) {
                    // по центру, с обрезкой до квадрата
                    float s = Math.max(w / last.getWidth(), h / last.getHeight());
                    float dw = last.getWidth() * s, dh = last.getHeight() * s;
                    c.drawBitmap(last, null, new RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), p);
                    if (lastMime.startsWith("video")) Glyph.play(c, w / 2, h / 2, Ui.dpf(26), 0xEEFFFFFF, false);
                } else {
                    p.setColor(0x33FFFFFF);
                    c.drawRect(0, 0, w, h, p);
                    Glyph.image(c, w / 2, h / 2, Ui.dpf(22), 0xCCFFFFFF);
                }
                c.restore();
            }
        };
        thumb.setOnClickListener(v -> openLast());
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(Ui.dp(52), Ui.dp(52), Gravity.BOTTOM | Gravity.START);
        tl.leftMargin = Ui.dp(36);
        tl.bottomMargin = Ui.dp(63);
        root.addView(thumb, tl);
        thumb.setVisibility(forResult || forVideo ? View.INVISIBLE : View.VISIBLE);
        // смена камеры; во время записи — пауза
        flip = Views.drawn(this, (c, w, h) -> {
            if (recording) Glyph.play(c, w / 2f, h / 2f, Ui.dpf(28), 0xFFFFFFFF, !paused);
            else Glyph.rotate(c, w / 2f, h / 2f, Ui.dpf(24), 0xFFFFFFFF);
        });
        flip.setBackground(Views.round(0x33FFFFFF, 26));
        flip.setOnClickListener(v -> {
            if (recording) {
                pauseRec();
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
        FrameLayout.LayoutParams xl = new FrameLayout.LayoutParams(Ui.dp(52), Ui.dp(52), Gravity.BOTTOM | Gravity.END);
        xl.rightMargin = Ui.dp(36);
        xl.bottomMargin = Ui.dp(63);
        root.addView(flip, xl);

        root.setOnApplyWindowInsetsListener((v, in) -> {
            topInset = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()).top;
            ((FrameLayout.LayoutParams) topBar.getLayoutParams()).topMargin = topInset + Ui.dp(8);
            ((FrameLayout.LayoutParams) recTime.getLayoutParams()).topMargin = topInset + Ui.dp(10);
            layoutPreview();
            return in;
        });
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            if (r - l != or - ol || bt - t != ob - ot) layoutPreview();
        });
        setContentView(root);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.navigationBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        modeUi(false);
    }

    /** Превью под строкой кнопок, высотой по кадру: 3:4 у фото, 9:16 у видео. */
    private void layoutPreview() {
        if (root == null || root.getWidth() == 0) return;
        FrameLayout.LayoutParams l = (FrameLayout.LayoutParams) frame.getLayoutParams();
        int w = root.getWidth();
        float a = pvSize != null ? Math.max(pvSize.getWidth(), pvSize.getHeight()) / (float) Math.min(pvSize.getWidth(), pvSize.getHeight())
                : mode == VIDEO ? 16 / 9f : 4 / 3f;
        int h = Math.round(w * a);
        int top = topInset + Ui.dp(56);
        // длинное видео не влезает — пусть начинается выше, под самой строкой
        if (top + h > root.getHeight()) top = Math.max(0, root.getHeight() - h);
        if (l.height != h || l.topMargin != top) {
            l.height = h;
            l.topMargin = top;
            frame.setLayoutParams(l);
        }
        tv.post(this::softZoom);
    }

    /** Подписи режимов: выбранная — по центру экрана, жёлтая. */
    private void placeStrip(boolean anim) {
        if (modeStrip.getWidth() == 0) return;
        View sel = modeStrip.getChildAt(mode);
        float x = root.getWidth() / 2f - (sel.getLeft() + sel.getWidth() / 2f);
        for (int i = 0; i < modeStrip.getChildCount(); i++)
            ((TextView) modeStrip.getChildAt(i)).setTextColor(i == mode ? 0xFFFFD54A : 0xCCFFFFFF);
        if (anim) modeStrip.animate().translationX(x).setDuration(260).setInterpolator(Anim.EMPH).start();
        else modeStrip.setTranslationX(x);
    }

    /** Что видно в режиме: таймер и сетка — у фото, качество — у видео, плашки — у «Про». */
    private void modeUi(boolean anim) {
        placeStrip(anim);
        timerBtn.setVisibility(mode == VIDEO ? View.GONE : View.VISIBLE);
        qualityBtn.setVisibility(mode == VIDEO ? View.VISIBLE : View.GONE);
        flashBtn.invalidate();
        shutter.invalidate();
        proChips.setVisibility(mode == PRO ? View.VISIBLE : View.GONE);
        if (mode != PRO) {
            proSel = -1;
            dial.setVisibility(View.GONE);
        }
        zoomUi();
    }

    private void setMode(int m) {
        if (m < 0 || m >= MODES.length || m == mode || recording || countdown != null || forResult || forVideo) return;
        mode = m;
        prefs.edit().putInt("mode", m).apply();
        Feel.buzz(modeStrip, Feel.TICK);
        if (m == VIDEO && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, ASK_MIC);
        torch = false;
        modeUi(true);
        // у видео свой предел зума и свои объективы
        float z = Math.max(zoomMin(), Math.min(zoomMax(), zoom));
        if (lens != null && lensFor(z) != lens) {
            zoom = z;
            reopen();
        } else {
            zoom = z;
            reconfigure();
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
            for (int i = 0; i < st.length; i++) {
                TextView t = Views.label(this, "", 12, Ui.bold, 0xFFFFFFFF);
                t.setGravity(Gravity.CENTER);
                float v = st[i];
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
                    // свайп влево — следующий режим (к «Видео»)
                    setMode(mode + (dx < 0 ? 1 : -1));
                } else if (Math.abs(dx) < Ui.dp(12) && Math.abs(dy) < Ui.dp(12)) {
                    if (proSel >= 0) closeDial();
                    else focusAt(e.getX(), e.getY());
                }
                break;
        }
        return true;
    }

    // ── профи-режим ───────────────────────────────────────────────────────

    /** Списки значений из возможностей камеры. */
    private void proLists(CameraCharacteristics ch) {
        int[] caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        manualSensor = hasRaw = false;
        if (caps != null) for (int c : caps) {
            manualSensor |= c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR;
            hasRaw |= c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW;
        }
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
            boolean set = i == 5 ? rawOn : i == 3 ? wbI > 0 : i == 4 ? evI >= 0 && evList[evI] != 0 && !manualExposure()
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
        if (preview != null) {
            setup(preview);
            repeat();
        }
        proUi();
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

    @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { openCamera(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { softZoom(); }

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
        lens = lensFor(Math.max(zoomMin(), Math.min(zoomMax(), zoom)));
        zoom = Math.max(zoomMin(), Math.min(zoomMax(), zoom));
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
                flashBtn.setVisibility(hasFlash ? View.VISIBLE : View.GONE);
                proUi();
                zoomUi();
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
                    toast("Камера занята другим приложением");
                }
            }, bg);
        } catch (CameraAccessException | RuntimeException e) {
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
        runOnUiThread(() -> qualityBtn.setText(qualityText(qualities.get(qi))));
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

    /** Режим или качество поменялись — та же камера, новая сессия. */
    private void reconfigure() {
        if (bg != null && dev != null) bg.post(this::configure);
    }

    /** Сессия под режим: превью + JPEG (+ RAW) у фото, превью + запись у видео. */
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
            if (mode == VIDEO) {
                Quality q = qualities.get(qi);
                pvSize = like(map.getOutputSizes(SurfaceTexture.class), q.w, q.h, 1920);
                recSurface = MediaCodec.createPersistentInputSurface();
                // поверхность записи узнаёт размер от подготовленного рекордера
                File probe = new File(getCacheDir(), "probe.mp4");
                MediaRecorder r = recorder(q, false);
                r.setOutputFile(probe.getAbsolutePath());
                r.prepare();
                r.release();
                //noinspection ResultOfMethodCallIgnored
                probe.delete();
                outs.add(new OutputConfiguration(recSurface));
            } else {
                pvSize = best(map.getOutputSizes(SurfaceTexture.class), 1920);
                Size ph = best(map.getOutputSizes(ImageFormat.JPEG), Integer.MAX_VALUE);
                jpeg = ImageReader.newInstance(ph.getWidth(), ph.getHeight(), ImageFormat.JPEG, 2);
                jpeg.setOnImageAvailableListener(this::savedJpeg, bg);
                outs.add(new OutputConfiguration(jpeg.getSurface()));
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
            }
            tv.getSurfaceTexture().setDefaultBufferSize(pvSize.getWidth(), pvSize.getHeight());
            if (view == null) view = new Surface(tv.getSurfaceTexture());
            outs.add(0, new OutputConfiguration(view));
            preview = dev.createCaptureRequest(mode == VIDEO ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW);
            preview.addTarget(view);
            setup(preview);
            runOnUiThread(this::layoutPreview);
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
            toast("Камера не запустилась");
        }
    }

    /**
     * Всё про кадр в одном месте: зум, экспозиция (авто, вспышка или ручная),
     * фокус, баланс белого, у видео — фонарик, стабилизация и частота кадров.
     */
    private void setup(CaptureRequest.Builder r) {
        applyZoom(r);
        if (mode == VIDEO) {
            r.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            r.set(CaptureRequest.FLASH_MODE, torch ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            r.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            r.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
            r.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0);
            int[] stab = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
            boolean st = false;
            if (stab != null) for (int s : stab) st |= s == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON;
            if (st) r.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
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
            r.set(CaptureRequest.CONTROL_AE_MODE, !hasFlash ? CaptureRequest.CONTROL_AE_MODE_ON
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

    /** Что выбрал автомат: для подписей «Про» и как основа ручной экспозиции. */
    private final CameraCaptureSession.CaptureCallback watch = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req, TotalCaptureResult r) {
            if (manualExposure()) return;
            Integer iso = r.get(CaptureResult.SENSOR_SENSITIVITY);
            Long exp = r.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            if (iso != null) autoIso = iso;
            if (exp != null) autoExp = exp;
            long now = SystemClock.uptimeMillis();
            if (mode == PRO && now - proUiAt > 300) {
                proUiAt = now;
                runOnUiThread(CameraActivity.this::proUi);
            }
        }
    };

    private void repeat() {
        if (ses == null || preview == null) return;
        try {
            ses.setRepeatingRequest(preview.build(), watch, bg);
        } catch (CameraAccessException | RuntimeException ignored) {
        }
    }

    private void closeOutputs() {
        if (jpeg != null) {
            jpeg.close();
            jpeg = null;
        }
        if (raw != null) {
            raw.close();
            raw = null;
        }
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
        // из точки на экране — в координаты матрицы (она повёрнута на sensor, фронтальная — отражена)
        float nx = x / tv.getWidth(), ny = y / tv.getHeight();
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
            if (!(mode == PRO && focusI > 0)) {
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
        if (mode == VIDEO) {
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

    private void take() {
        if (busy || ses == null || dev == null || jpeg == null) return;
        busy = true;
        Feel.buzz(shutter, Feel.CLICK);
        try {
            if (flash != 0 && hasFlash && !manualExposure()) {
                // вспышке нужен замер заранее
                preview.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
                ses.capture(preview.build(), null, bg);
                preview.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
                bg.postDelayed(this::shoot, 450);
            } else {
                shoot();
            }
        } catch (CameraAccessException | RuntimeException e) {
            busy = false;
        }
    }

    private void shoot() {
        if (ses == null || dev == null || jpeg == null) {
            busy = false;
            return;
        }
        try {
            CaptureRequest.Builder r = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            r.addTarget(jpeg.getSurface());
            boolean withRaw = raw != null;
            if (withRaw) r.addTarget(raw.getSurface());
            setup(r);
            // как держат телефон: у фронтальной поворот в другую сторону
            shotDeg = front ? (sensor - deviceDeg + 360) % 360 : (sensor + deviceDeg) % 360;
            shotSw = sw;
            r.set(CaptureRequest.JPEG_ORIENTATION, shotDeg);
            r.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            ses.capture(r.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest q, TotalCaptureResult res) {
                    if (!withRaw) return;
                    synchronized (CameraActivity.this) {
                        rawResult = res;
                    }
                    writeDng();
                }
            }, bg);
            runOnUiThread(() -> {
                sound.play(MediaActionSound.SHUTTER_CLICK);
                // вспышка-затемнение превью
                tv.animate().cancel();
                tv.setAlpha(0.15f);
                tv.animate().alpha(1).setDuration(220).start();
            });
        } catch (CameraAccessException | RuntimeException e) {
            busy = false;
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

    /** JPEG готов (поток камеры): обрезать под зум сверх камеры, сохранить, обновить миниатюру. */
    private void savedJpeg(ImageReader r) {
        Image img = r.acquireNextImage();
        if (img == null) return;
        byte[] jpg;
        try {
            ByteBuffer buf = img.getPlanes()[0].getBuffer();
            jpg = new byte[buf.remaining()];
            buf.get(jpg);
        } finally {
            img.close();
        }
        if (shotSw > 1.01f) jpg = crop(jpg, shotSw);
        if (forResult) {
            reply(jpg);
            return;
        }
        Uri uri = null;
        try {
            uri = insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Vanks_" + stamp() + ".jpg", "image/jpeg");
            if (uri != null) {
                try (OutputStream o = getContentResolver().openOutputStream(uri)) {
                    o.write(jpg);
                }
                publish(uri);
            }
        } catch (Exception e) {
            toast("Не удалось сохранить снимок");
        }
        Bitmap small = thumbOf(jpg);
        Uri u = uri;
        runOnUiThread(() -> {
            busy = false;
            showLast(u, "image/jpeg", small);
        });
    }

    /** Середина снимка на 1/k: зум сверх того, что даёт камера. Поворот — сразу в пиксели. */
    @SuppressWarnings("deprecation")
    private static byte[] crop(byte[] jpg, float k) {
        try {
            int deg = exifDeg(jpg);
            BitmapRegionDecoder d = BitmapRegionDecoder.newInstance(jpg, 0, jpg.length, false);
            int w = d.getWidth(), h = d.getHeight();
            int cw = Math.round(w / k), ch = Math.round(h / k);
            Rect rc = new Rect((w - cw) / 2, (h - ch) / 2, (w + cw) / 2, (h + ch) / 2);
            Bitmap b = d.decodeRegion(rc, null);
            d.recycle();
            if (deg != 0) {
                Matrix m = new Matrix();
                m.postRotate(deg);
                Bitmap rb = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                b.recycle();
                b = rb;
            }
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 95, o);
            b.recycle();
            return o.toByteArray();
        } catch (Exception | OutOfMemoryError e) {
            return jpg;
        }
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
            d.setOrientation(shotDeg == 90 ? ExifInterface.ORIENTATION_ROTATE_90
                    : shotDeg == 180 ? ExifInterface.ORIENTATION_ROTATE_180
                    : shotDeg == 270 ? ExifInterface.ORIENTATION_ROTATE_270 : ExifInterface.ORIENTATION_NORMAL);
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

    /** Миниатюра снимка с учётом поворота (JPEG_ORIENTATION пишется в EXIF). */
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

    @SuppressWarnings("deprecation")
    private MediaRecorder recorder(Quality q, boolean audio) {
        MediaRecorder m = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
        if (audio) m.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        m.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        m.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        m.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        m.setVideoSize(q.w, q.h);
        m.setVideoFrameRate(q.fps);
        m.setVideoEncodingBitRate(q.bitrate);
        if (audio) {
            m.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            m.setAudioSamplingRate(q.aRate);
            m.setAudioEncodingBitRate(q.aBits);
            m.setAudioChannels(q.aCh);
        }
        m.setInputSurface(recSurface);
        return m;
    }

    private void startRec() {
        if (recording || ses == null || recSurface == null || preview == null) return;
        boolean audio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        try {
            recUri = replyTo != null ? replyTo
                    : insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "VID_Vanks_" + stamp() + ".mp4", "video/mp4");
            if (recUri == null) throw new IllegalStateException();
            try {
                recFd = getContentResolver().openFileDescriptor(recUri, "rw");
            } catch (Exception e) {
                recFd = getContentResolver().openFileDescriptor(recUri, "w");
            }
            FileDescriptor fd = recFd.getFileDescriptor();
            rec = recorder(qualities.get(qi), audio);
            rec.setOutputFile(fd);
            rec.setOrientationHint(front ? (sensor - deviceDeg + 360) % 360 : (sensor + deviceDeg) % 360);
            rec.prepare();
            preview.addTarget(recSurface);
            repeat();
            rec.start();
        } catch (Exception e) {
            toast("Не удалось начать запись");
            dropRec();
            return;
        }
        recording = true;
        paused = false;
        recPausedTotal = 0;
        recStart = SystemClock.elapsedRealtime();
        sound.play(MediaActionSound.START_VIDEO_RECORDING);
        Feel.buzz(shutter, Feel.CONFIRM);
        topBar.animate().alpha(0).setDuration(160).withEndAction(() -> topBar.setVisibility(View.INVISIBLE)).start();
        modeStrip.animate().alpha(0).setDuration(160).start();
        thumb.animate().alpha(0.3f).setDuration(160).start();
        recTime.setVisibility(View.VISIBLE);
        tick.run();
        shutter.invalidate();
        flip.invalidate();
    }

    /** Время записи раз в полсекунды; на паузе мигает. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!recording) return;
            long now = paused ? recPausedAt : SystemClock.elapsedRealtime();
            long s = (now - recStart - recPausedTotal) / 1000;
            recTime.setText(String.format(Locale.ROOT, "%s %02d:%02d", paused ? "Пауза" : "●", s / 60, s % 60));
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
        sound.play(MediaActionSound.STOP_VIDEO_RECORDING);
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
        if (preview != null && recSurface != null) preview.removeTarget(recSurface);
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
