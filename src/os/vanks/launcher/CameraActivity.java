package os.vanks.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
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
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Камера Vanks: превью 3:4, спуск (и кнопками громкости), вспышка, фронтальная
 * камера, фокус касанием, миниатюра последнего снимка. Снимки — в
 * «Изображения/Vanks». Отвечает и на «сделать снимок» из других приложений
 * (IMAGE_CAPTURE): тогда снимок уходит туда, куда просили, и камера закрывается.
 */
public final class CameraActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final int ASK = 7;
    private static final String[] FLASH = {"Выкл", "Авто", "Вкл"};

    private CameraManager cm;
    private String camId;
    private boolean front;
    private int sensor;          // поворот матрицы
    private int deviceDeg;       // как держат телефон (0, 90, 180, 270)
    private Rect active;         // область матрицы — для фокуса касанием
    private CameraDevice dev;
    private CameraCaptureSession ses;
    private CaptureRequest.Builder preview;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler bg;
    private TextureView tv;
    private View shutter, thumb, flip, flashBtn, ring;
    private TextView flashText;
    private int flash;           // 0 — выкл, 1 — авто, 2 — вкл
    private boolean busy;
    private Bitmap last;
    private Uri lastUri;
    private Uri replyTo;         // IMAGE_CAPTURE: куда положить снимок
    private boolean forResult;
    private MediaActionSound click;
    private OrientationEventListener tilt;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Prefs.load(this);
        Ui.init(this);
        Feel.init(this);
        cm = getSystemService(CameraManager.class);
        Intent in = getIntent();
        forResult = MediaStore.ACTION_IMAGE_CAPTURE.equals(in.getAction());
        if (forResult) replyTo = output(in);
        flash = getPreferences(MODE_PRIVATE).getInt("flash", 0);
        front = getPreferences(MODE_PRIVATE).getBoolean("front", false);
        getWindow().setDecorFitsSystemWindows(false);
        build();
        click = new MediaActionSound();
        click.load(MediaActionSound.SHUTTER_CLICK);
        tilt = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int o) {
                if (o == ORIENTATION_UNKNOWN) return;
                int d = ((o + 45) / 90 * 90) % 360;
                if (d != deviceDeg) {
                    deviceDeg = d;
                    // значки поворачиваются вслед за телефоном, сам экран — нет
                    float r = -d;
                    for (View v : new View[]{thumb, flip, flashBtn}) v.animate().rotation(r > 180 ? r - 360 : r).setDuration(220).start();
                }
            }
        };
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA}, ASK);
        if (!forResult) loadLast();
    }

    @SuppressWarnings("deprecation")
    private static Uri output(Intent in) {
        Object o = in.getParcelableExtra(MediaStore.EXTRA_OUTPUT);
        return o instanceof Uri ? (Uri) o : null;
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    // ── вид ───────────────────────────────────────────────────────────────

    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        tv = new TextureView(this);
        tv.setSurfaceTextureListener(this);
        tv.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) focusAt(e.getX(), e.getY());
            return true;
        });
        root.addView(tv, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        // кольцо фокуса
        ring = Views.drawn(this, (c, w, h) -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dpf(1.6f));
            p.setColor(0xFFFFD54A);
            c.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f - Ui.dpf(2), p);
        });
        ring.setAlpha(0);
        root.addView(ring, new FrameLayout.LayoutParams(Ui.dp(72), Ui.dp(72)));

        // сверху — вспышка
        flashBtn = Views.drawn(this, (c, w, h) -> Glyph.bolt(c, w / 2f, h / 2f, Ui.dpf(20), flash == 0 ? 0x99FFFFFF : 0xFFFFD54A));
        flashBtn.setBackground(Views.round(0x33FFFFFF, 20));
        flashBtn.setOnClickListener(v -> {
            flash = (flash + 1) % 3;
            getPreferences(MODE_PRIVATE).edit().putInt("flash", flash).apply();
            flashText.setText(FLASH[flash]);
            flashText.animate().cancel();
            flashText.setAlpha(1);
            flashText.animate().alpha(0).setStartDelay(900).setDuration(300).start();
            flashBtn.invalidate();
            Feel.buzz(v, Feel.TICK);
            applyFlash(preview);
            repeat();
        });
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(Ui.dp(40), Ui.dp(40), Gravity.TOP | Gravity.START);
        fl.leftMargin = Ui.dp(18);
        root.addView(flashBtn, fl);
        flashText = Views.label(this, FLASH[flash], 13, Ui.medium, 0xFFFFFFFF);
        flashText.setAlpha(0);
        FrameLayout.LayoutParams ftl = new FrameLayout.LayoutParams(-2, Ui.dp(40), Gravity.TOP | Gravity.START);
        ftl.leftMargin = Ui.dp(66);
        flashText.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(flashText, ftl);

        // снизу: режим, миниатюра, спуск, смена камеры
        TextView mode = Views.label(this, "ФОТО", 13, Ui.bold, 0xFFFFD54A);
        mode.setLetterSpacing(0.12f);
        FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ml.bottomMargin = Ui.dp(150);
        root.addView(mode, ml);
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
                p.setColor(isPressed() ? 0xFFCCCCCC : 0xFFFFFFFF);
                c.drawCircle(cx, cy, r - Ui.dpf(8), p);
            }
        };
        shutter.setOnClickListener(v -> take());
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(Ui.dp(78), Ui.dp(78), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sl.bottomMargin = Ui.dp(56);
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
        tl.bottomMargin = Ui.dp(69);
        root.addView(thumb, tl);
        thumb.setVisibility(forResult ? View.INVISIBLE : View.VISIBLE);
        flip = Views.drawn(this, (c, w, h) -> Glyph.rotate(c, w / 2f, h / 2f, Ui.dpf(24), 0xFFFFFFFF));
        flip.setBackground(Views.round(0x33FFFFFF, 26));
        flip.setOnClickListener(v -> {
            front = !front;
            getPreferences(MODE_PRIVATE).edit().putBoolean("front", front).apply();
            Feel.buzz(v, Feel.CLICK);
            v.animate().rotationBy(-180).setDuration(300).start();
            tv.animate().alpha(0).setDuration(120).withEndAction(() -> {
                closeCamera();
                openCamera();
                tv.animate().alpha(1).setStartDelay(260).setDuration(200).start();
            }).start();
        });
        FrameLayout.LayoutParams xl = new FrameLayout.LayoutParams(Ui.dp(52), Ui.dp(52), Gravity.BOTTOM | Gravity.END);
        xl.rightMargin = Ui.dp(36);
        xl.bottomMargin = Ui.dp(69);
        root.addView(flip, xl);

        root.setOnApplyWindowInsetsListener((v, in) -> {
            int top = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()).top;
            ((FrameLayout.LayoutParams) flashBtn.getLayoutParams()).topMargin = top + Ui.dp(10);
            ((FrameLayout.LayoutParams) flashText.getLayoutParams()).topMargin = top + Ui.dp(10);
            // превью 3:4 под строкой кнопок, как в камерах телефонов
            FrameLayout.LayoutParams pl = (FrameLayout.LayoutParams) tv.getLayoutParams();
            pl.topMargin = top + Ui.dp(60);
            pl.height = v.getWidth() * 4 / 3;
            v.requestLayout();
            return in;
        });
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            int h = v.getWidth() * 4 / 3;
            if (tv.getLayoutParams().height != h) {
                tv.getLayoutParams().height = h;
                tv.requestLayout();
            }
        });
        setContentView(root);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.navigationBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
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
        if (click != null) click.release();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code != ASK) return;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
            if (tv.isAvailable()) openCamera();
        } else {
            toast("Камере нужно разрешение на съёмку");
            finish();
        }
    }

    @Override
    public boolean onKeyDown(int key, KeyEvent e) {
        // кнопки громкости — спуск
        if (key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_VOLUME_DOWN || key == KeyEvent.KEYCODE_CAMERA) {
            if (e.getRepeatCount() == 0) take();
            return true;
        }
        return super.onKeyDown(key, e);
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { openCamera(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {}

    // ── камера ────────────────────────────────────────────────────────────

    private void openCamera() {
        if (dev != null || bg == null || !tv.isAvailable()
                || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            camId = null;
            for (String id : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (f != null && (f == CameraCharacteristics.LENS_FACING_FRONT) == front) {
                    camId = id;
                    break;
                }
            }
            if (camId == null) camId = cm.getCameraIdList()[0];
            CameraCharacteristics ch = cm.getCameraCharacteristics(camId);
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensor = so == null ? 90 : so;
            active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Boolean hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            flashBtn.setVisibility(hasFlash != null && hasFlash ? View.VISIBLE : View.INVISIBLE);
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size pv = best(map.getOutputSizes(SurfaceTexture.class), 1920);
            Size ph = best(map.getOutputSizes(android.graphics.ImageFormat.JPEG), Integer.MAX_VALUE);
            tv.getSurfaceTexture().setDefaultBufferSize(pv.getWidth(), pv.getHeight());
            reader = ImageReader.newInstance(ph.getWidth(), ph.getHeight(), android.graphics.ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(this::saved, bg);
            cm.openCamera(camId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice d) {
                    dev = d;
                    startPreview();
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
                    runOnUiThread(() -> toast("Камера занята другим приложением"));
                }
            }, bg);
        } catch (CameraAccessException | RuntimeException e) {
            toast("Не удалось открыть камеру");
        }
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

    private void startPreview() {
        try {
            Surface s = new Surface(tv.getSurfaceTexture());
            preview = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            preview.addTarget(s);
            preview.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            applyFlash(preview);
            dev.createCaptureSession(new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    Arrays.asList(new OutputConfiguration(s), new OutputConfiguration(reader.getSurface())),
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
        } catch (CameraAccessException | RuntimeException e) {
            toast("Камера не запустилась");
        }
    }

    private void repeat() {
        if (ses == null || preview == null) return;
        try {
            ses.setRepeatingRequest(preview.build(), null, bg);
        } catch (CameraAccessException | RuntimeException ignored) {
        }
    }

    private void applyFlash(CaptureRequest.Builder r) {
        if (r == null) return;
        r.set(CaptureRequest.CONTROL_AE_MODE, flash == 1 ? CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                : flash == 2 ? CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH : CaptureRequest.CONTROL_AE_MODE_ON);
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
        if (reader != null) {
            reader.close();
            reader = null;
        }
        busy = false;
    }

    /** Касание превью: фокус и замер в этой точке. */
    private void focusAt(float x, float y) {
        ring.animate().cancel();
        ring.setX(x - ring.getWidth() / 2f);
        ring.setY(tv.getY() + y - ring.getHeight() / 2f);
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
        int half = Math.max(active.width(), active.height()) / 16;
        int cx = active.left + Math.round(sx * active.width()), cy = active.top + Math.round(sy * active.height());
        MeteringRectangle m = new MeteringRectangle(Math.max(active.left, cx - half), Math.max(active.top, cy - half),
                half * 2, half * 2, MeteringRectangle.METERING_WEIGHT_MAX - 1);
        try {
            preview.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{m});
            preview.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{m});
            preview.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            preview.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            ses.capture(preview.build(), null, bg);
            preview.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            repeat();
        } catch (CameraAccessException | RuntimeException ignored) {
        }
        Feel.buzz(tv, Feel.TICK);
    }

    // ── снимок ────────────────────────────────────────────────────────────

    private void take() {
        if (busy || ses == null || dev == null || reader == null) return;
        busy = true;
        Feel.buzz(shutter, Feel.CLICK);
        try {
            if (flash != 0) {
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
        if (ses == null || dev == null || reader == null) {
            busy = false;
            return;
        }
        try {
            CaptureRequest.Builder r = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            r.addTarget(reader.getSurface());
            r.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            applyFlash(r);
            // как держат телефон: у фронтальной поворот в другую сторону
            int jpeg = front ? (sensor - deviceDeg + 360) % 360 : (sensor + deviceDeg) % 360;
            r.set(CaptureRequest.JPEG_ORIENTATION, jpeg);
            r.set(CaptureRequest.JPEG_QUALITY, (byte) 94);
            ses.capture(r.build(), null, bg);
            runOnUiThread(() -> {
                click.play(MediaActionSound.SHUTTER_CLICK);
                // вспышка-затемнение превью
                tv.animate().cancel();
                tv.setAlpha(0.15f);
                tv.animate().alpha(1).setDuration(220).start();
            });
        } catch (CameraAccessException | RuntimeException e) {
            busy = false;
        }
    }

    /** JPEG готов (поток камеры): сохранить, обновить миниатюру. */
    private void saved(ImageReader r) {
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
        if (forResult) {
            reply(jpg);
            return;
        }
        Uri uri = null;
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "Vanks_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT).format(new java.util.Date()) + ".jpg");
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Vanks");
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri != null) {
                try (OutputStream o = getContentResolver().openOutputStream(uri)) {
                    o.write(jpg);
                }
                v.clear();
                v.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(uri, v, null, null);
            }
        } catch (Exception e) {
            runOnUiThread(() -> toast("Не удалось сохранить снимок"));
        }
        Bitmap small = preview(jpg);
        Uri u = uri;
        runOnUiThread(() -> {
            busy = false;
            if (u != null) lastUri = u;
            if (small != null) {
                last = small;
                thumb.setScaleX(0.6f);
                thumb.setScaleY(0.6f);
                thumb.animate().scaleX(1).scaleY(1).setDuration(260).setInterpolator(Anim.EMPH).start();
                thumb.invalidate();
            }
        });
    }

    /** Миниатюра снимка с учётом поворота (JPEG_ORIENTATION пишется в EXIF). */
    private static Bitmap preview(byte[] jpg) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 16;
        Bitmap b = BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
        if (b == null) return null;
        int deg = 0;
        try {
            android.media.ExifInterface ex = new android.media.ExifInterface(new java.io.ByteArrayInputStream(jpg));
            switch (ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                case android.media.ExifInterface.ORIENTATION_ROTATE_90: deg = 90; break;
                case android.media.ExifInterface.ORIENTATION_ROTATE_180: deg = 180; break;
                case android.media.ExifInterface.ORIENTATION_ROTATE_270: deg = 270; break;
                default: deg = 0;
            }
        } catch (Exception ignored) {
        }
        if (deg == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(deg);
        return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
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
            res.putExtra("data", preview(jpg));
        }
        boolean done = ok;
        runOnUiThread(() -> {
            setResult(done ? RESULT_OK : RESULT_CANCELED, res);
            finish();
        });
    }

    /** Последний снимок Vanks — для миниатюры при открытии. */
    private void loadLast() {
        new Thread(() -> {
            try (Cursor c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media._ID}, MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?",
                    new String[]{"Pictures/Vanks%"}, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                if (c == null || !c.moveToFirst()) return;
                Uri u = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0));
                Bitmap b = getContentResolver().loadThumbnail(u, new Size(256, 256), null);
                runOnUiThread(() -> {
                    if (lastUri != null) return;
                    lastUri = u;
                    last = b;
                    thumb.invalidate();
                });
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void openLast() {
        Intent i = lastUri != null
                ? new Intent(Intent.ACTION_VIEW).setDataAndType(lastUri, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                : new Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            toast("Нет приложения для просмотра фото");
        }
    }
}
