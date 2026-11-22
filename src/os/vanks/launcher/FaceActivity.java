package os.vanks.launcher;

import android.Manifest;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Запись лица для входа (FaceUnlock): круг с передней камерой, вокруг кольцо —
 * сколько образцов набрано. Человек смотрит в камеру и медленно поворачивает
 * голову; нужно 15 кадров с лицом не чаще трёх в секунду. С extra "test" —
 * проверка: показывает, узнано ли лицо и насколько похоже.
 */
public final class FaceActivity extends Activity {
    private static final int NEED = 15;
    private final ArrayList<float[]> got = new ArrayList<>();
    private FaceUnlock.Camera cam;
    private Ring ring;
    private TextView title, hint;
    private boolean test, finished;
    private long lastAt;
    private Bitmap shown;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Prefs.load(this);
        Ui.init(this);
        test = getIntent().getBooleanExtra("test", false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setBackgroundColor(0xFF000000);
        col.setPadding(Ui.dp(24), Ui.dp(72), Ui.dp(24), Ui.dp(40));
        title = Views.label(this, test ? "Проверка лица" : "Запись лица", 26, Ui.bold, 0xFFFFFFFF);
        title.setGravity(Gravity.CENTER);
        col.addView(title, new LinearLayout.LayoutParams(-1, -2));
        hint = Views.label(this, "Смотрите в камеру", 15, Ui.regular, 0xB3FFFFFF);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = Ui.dp(8);
        col.addView(hint, hl);
        ring = new Ring();
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(Ui.dp(280), Ui.dp(280));
        rl.topMargin = Ui.dp(48);
        col.addView(ring, rl);
        TextView note = Views.label(this, test ? "Расстояние меньше порога — лицо узнано."
                : "Медленно поверните голову влево, вправо, вверх и вниз. Хорошо, если свет как обычно, когда берёте телефон.", 13, Ui.regular, 0x80FFFFFF);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2);
        nl.topMargin = Ui.dp(36);
        col.addView(note, nl);
        View sp = new View(this);
        col.addView(sp, new LinearLayout.LayoutParams(1, 0, 1));
        TextView cancel = Views.label(this, test ? "Готово" : "Отмена", 16, Ui.medium, 0xFFFFFFFF);
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackground(Views.round(0x3D767680, 22));
        cancel.setOnClickListener(v -> finish());
        col.addView(cancel, new LinearLayout.LayoutParams(Ui.dp(200), Ui.dp(46)));
        setContentView(col);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!FaceUnlock.cameraAllowed(this)) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
            return;
        }
        start();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (FaceUnlock.cameraAllowed(this)) return;   // onResume запустит
        hint.setText("Нужен доступ к камере");
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (cam != null) cam.close();
        cam = null;
    }

    private void start() {
        if (cam != null || finished) return;
        cam = new FaceUnlock.Camera();
        if (!cam.open(this, new FaceUnlock.Sink() {
            @Override
            public void frame(byte[] gray, int w, int h) {
                FaceUnlock.Face f = FaceUnlock.find(gray, w, h);
                Bitmap pv = preview(gray, w, h);
                runOnUiThread(() -> show(pv, f));
            }

            @Override
            public void error(String msg) {
                runOnUiThread(() -> hint.setText(msg));
            }
        })) {
            cam = null;
            hint.setText("Передняя камера недоступна");
        }
    }

    /** Кадр для круга: уменьшенный, зеркальный (как в зеркале), квадрат по центру. */
    private static Bitmap preview(byte[] g, int w, int h) {
        int s = 240, side = Math.min(w, h), x0 = (w - side) / 2, y0 = (h - side) / 2;
        float k = side / (float) s;
        int[] px = new int[s * s];
        for (int y = 0; y < s; y++)
            for (int x = 0; x < s; x++) {
                int v = g[(y0 + (int) (y * k)) * w + x0 + side - 1 - (int) (x * k)] & 255;
                px[y * s + x] = 0xFF000000 | v << 16 | v << 8 | v;
            }
        return Bitmap.createBitmap(px, s, s, Bitmap.Config.ARGB_8888);
    }

    private void show(Bitmap pv, FaceUnlock.Face f) {
        if (finished) return;
        shown = pv;
        ring.face = f != null;
        if (f != null) {
            long now = System.currentTimeMillis();
            if (test) {
                float d = FaceUnlock.best(this, f.feat), thr = FaceUnlock.threshold(this);
                boolean ok = d < thr;
                hint.setText((ok ? "Узнано" : "Не узнано") + String.format(java.util.Locale.ROOT, " · %.2f из %.2f", d, thr));
                hint.setTextColor(ok ? 0xFF6EE7A0 : 0xFFFF8A80);
                ring.progress = ok ? 1 : 0;
            } else if (now - lastAt > 330) {
                lastAt = now;
                got.add(f.feat);
                ring.progress = got.size() / (float) NEED;
                hint.setText(got.size() < NEED / 2 ? "Медленно поворачивайте голову" : "Ещё немного");
                Feel.buzz(ring, Feel.TICK);
                if (got.size() >= NEED) done();
            }
        } else if (!test) hint.setText("Лица не видно — поднесите телефон ближе");
        else {
            hint.setText("Лица не видно");
            hint.setTextColor(0xB3FFFFFF);
        }
        ring.invalidate();
    }

    private void done() {
        finished = true;
        if (cam != null) cam.close();
        cam = null;
        FaceUnlock.save(this, got);
        FaceUnlock.setOn(this, true);
        title.setText("Лицо записано");
        hint.setText(Secret.has(this) ? "Теперь экран блокировки узнаёт вас" : "Задайте PIN-код Android в «Отпечаток и вход» — им откроется телефон");
        Feel.buzz(ring, Feel.CONFIRM);
        ring.postDelayed(this::finish, 1400);
    }

    /** Круг с кадром камеры и кольцом прогресса. */
    private final class Ring extends View {
        float progress;
        boolean face;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Path clip = new Path();
        private final RectF r = new RectF();

        Ring() {
            super(FaceActivity.this);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), pad = Ui.dpf(14);
            r.set(pad, pad, w - pad, h - pad);
            if (shown != null) {
                c.save();
                clip.reset();
                clip.addOval(r, Path.Direction.CW);
                c.clipPath(clip);
                c.drawBitmap(shown, null, r, p);
                c.restore();
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dpf(5));
            p.setStrokeCap(Paint.Cap.ROUND);
            r.inset(-Ui.dpf(8), -Ui.dpf(8));
            p.setColor(face ? 0x40FFFFFF : 0x26FFFFFF);
            c.drawOval(r, p);
            if (progress > 0) {
                p.setColor(test ? (progress >= 1 ? 0xFF6EE7A0 : 0xFFFF8A80) : Ui.accent());
                c.drawArc(r, -90, 360 * Math.min(1, progress), false, p);
            }
            p.setStyle(Paint.Style.FILL);
        }
    }
}
