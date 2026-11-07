package os.vanks.launcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;

/**
 * Свой экран «Недавние»: карточки приложений со снимком окна в ряд, свежее —
 * посередине, старые — левее. Листается пальцем на пружине, карточку вверх —
 * закрыть приложение, касание — открыть (карточка вырастает в окно), кнопка
 * внизу — закрыть все. Касание мимо карточек — на главный экран.
 * Открывается жестом «вверх и задержать» (служба жестов): окно приложения
 * само уменьшается в первую карточку.
 */
public final class RecentsActivity extends Activity {
    private static boolean waitFly;
    private static RecentsActivity shown;
    private boolean cameByFly;   // первая карточка — то окно, что прилетело
    private Deck deck;
    private WallView wall;

    /** Открыть; fromFly — окно приложения летит в первую карточку (служба жестов). */
    static void open(Context c, boolean fromFly) {
        waitFly = fromFly;
        try {
            c.startActivity(new Intent(c, RecentsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
        } catch (RuntimeException ignored) {
        }
    }

    /** Окно долетело до карточки — показать её саму. */
    static void landed() {
        waitFly = false;
        RecentsActivity a = shown;
        if (a != null && a.deck != null) a.deck.invalidate();
    }

    /** Где первая карточка на экране W×H. */
    static RectF firstCard(float W, float H) {
        float cw = W * 0.68f, ch = cw * H / W, cy = H * 0.47f;
        return new RectF(W / 2 - cw / 2, cy - ch / 2, W / 2 + cw / 2, cy + ch / 2);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Prefs.load(this);
        Hz.apply(this);
        Ui.init(this);
        Feel.init(this);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        FrameLayout root = new FrameLayout(this);
        wall = new WallView(this);
        wall.frost = 1;
        wall.dim = 0.2f;
        wall.setAlpha(0);
        root.addView(wall, new FrameLayout.LayoutParams(-1, -1));
        deck = new Deck(this);
        root.addView(deck, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        wall.reload();
        wall.animate().alpha(1).setDuration((long) (220 * Prefs.k())).start();
        cameByFly = waitFly;
        deck.load();
        // окно не долетело (служба выключилась и т. п.) — всё равно показать карточку
        if (waitFly) deck.postDelayed(RecentsActivity::landed, 900);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        deck.load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        shown = this;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (shown == this) shown = null;
        // ушли (открыли приложение, «Домой») — недавние не висят под ним
        if (!isFinishing()) finish();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        finish();
        overridePendingTransition(0, 0);
    }

    private void goHome() {
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
        }
        finish();
        overridePendingTransition(0, 0);
    }

    // ── колода карточек ───────────────────────────────────────────────────

    private static final class Item {
        String pkg, label;
        Bitmap icon, thumb;
        int color;
        final LaunchCard.Spring dy = new LaunchCard.Spring(0), shift = new LaunchCard.Spring(0);
        boolean gone;
    }

    private final class Deck extends View {
        private final ArrayList<Item> items = new ArrayList<>();
        private final LaunchCard.Spring scroll = new LaunchCard.Spring(0), enter = new LaunchCard.Spring(0),
                grow = new LaunchCard.Spring(0);
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF(), ir = new RectF(), clear = new RectF();
        private final Path clip = new Path();
        private VelocityTracker vt;
        private float downX, downY, scroll0;
        private int mode, hit = -1, launching = -1;   // mode: 0 — не решили, 1 — листаем, 2 — закрываем карточку
        private long last;
        private boolean launched;

        Deck(Context c) {
            super(c);
        }

        void load() {
            items.clear();
            PackageManager pm = getPackageManager();
            int ip = Ui.dp(52);
            for (String pkg : Recents.list(RecentsActivity.this)) {
                Item it = new Item();
                it.pkg = pkg;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    it.label = String.valueOf(pm.getApplicationLabel(ai));
                    it.icon = bitmap(pm.getApplicationIcon(ai), ip);
                } catch (PackageManager.NameNotFoundException e) {
                    continue;
                }
                it.thumb = Recents.thumb(RecentsActivity.this, pkg);
                it.color = Ui.mix(LaunchCard.iconColor(it.icon), 0xFF111114, 0.55f);
                items.add(it);
            }
            scroll.snap(0);
            enter.snap(0);
            enter.to(1, 210, 0.86f);
            launching = -1;
            launched = false;
            grow.snap(0);
            last = SystemClock.uptimeMillis();
            invalidate();
        }

        private Bitmap bitmap(Drawable d, int px) {
            Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, px, px);
            d.draw(new Canvas(b));
            return b;
        }

        private float gap() {
            return getWidth() * 0.68f + Ui.dpf(22);
        }

        private float maxScroll() {
            return Math.max(0, (items.size() - 1) * gap());
        }

        /** Рамка карточки i (без сдвига вверх). */
        private void card(int i, RectF out) {
            RectF f = firstCard(getWidth(), getHeight());
            Item it = items.get(i);
            float cx = getWidth() / 2f - i * gap() + scroll.v + it.shift.v;
            float d = Math.min(1f, Math.abs(cx - getWidth() / 2f) / gap());
            float s = (1 - 0.06f * d) * (i == 0 && cameByFly ? 1 : 0.9f + 0.1f * enter.v);
            float hw = f.width() / 2 * s, hh = f.height() / 2 * s;
            out.set(cx - hw, f.centerY() - hh, cx + hw, f.centerY() + hh);
        }

        private int hitCard(float x, float y) {
            for (int i = 0; i < items.size(); i++) {
                card(i, r);
                if (r.contains(x, y)) return i;
            }
            return -1;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (launching >= 0) return true;
            if (vt == null) vt = VelocityTracker.obtain();
            vt.addMovement(e);
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = x;
                    downY = y;
                    mode = 0;
                    hit = hitCard(x, y);
                    scroll.snap(scroll.v);
                    scroll0 = scroll.v;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = x - downX, dy = y - downY, slop = Ui.dpf(8);
                    if (mode == 0) {
                        if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) mode = 1;
                        else if (dy < -slop && hit >= 0 && Math.abs(dy) > Math.abs(dx)) mode = 2;
                    }
                    if (mode == 1) {
                        float s = scroll0 + dx, m = maxScroll();
                        // за краем — с сопротивлением
                        if (s < 0) s *= 0.35f;
                        else if (s > m) s = m + (s - m) * 0.35f;
                        scroll.snap(s);
                    } else if (mode == 2) {
                        items.get(hit).dy.snap(Math.min(dy, 0));
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    vt.computeCurrentVelocity(1000);
                    float vx = vt.getXVelocity(), vy = vt.getYVelocity();
                    vt.recycle();
                    vt = null;
                    boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                    if (mode == 1) {
                        float g = gap();
                        int to = Math.round((scroll.v + vx * 0.22f) / g);
                        to = Math.max(0, Math.min(items.size() - 1, to));
                        scroll.vel = vx;
                        scroll.to(to * g, 190, 0.9f);
                    } else if (mode == 2) {
                        Item it = items.get(hit);
                        if (it.dy.v < -getHeight() * 0.16f || vy < -Ui.dpf(900)) dismiss(hit, vy);
                        else it.dy.to(0, 260, 0.8f);
                    } else if (up) {
                        if (clear.contains(x, y) && !items.isEmpty()) clearAll();
                        else if (hit >= 0) launch(hit);
                        else goHome();
                    }
                    last = SystemClock.uptimeMillis();
                    invalidate();
                    return true;
                }
            }
            return true;
        }

        private void dismiss(int i, float vy) {
            Item it = items.get(i);
            it.gone = true;
            it.dy.vel = Math.min(vy, -Ui.dpf(1800));
            it.dy.to(-getHeight() * 1.1f, 120, 1f);
            Feel.sound(RecentsActivity.this, "swoosh");
            Feel.buzz(this, Feel.TICK);
            postDelayed(() -> {
                int at = items.indexOf(it);
                if (at < 0) return;
                items.remove(at);
                // старые (левее) встают на место ушедшей — плавно
                float g = gap();
                for (int k = at; k < items.size(); k++) {
                    items.get(k).shift.snap(-g);
                    items.get(k).shift.to(0, 200, 0.86f);
                }
                if (scroll.t > maxScroll()) scroll.to(maxScroll(), 190, 0.9f);
                kill(it.pkg);
                if (items.isEmpty()) postDelayed(RecentsActivity.this::goHome, 160);
                invalidate();
            }, (long) (180 * Prefs.k()));
        }

        private void clearAll() {
            Feel.sound(RecentsActivity.this, "swoosh");
            Feel.buzz(this, Feel.CLICK);
            for (int i = 0; i < items.size(); i++) {
                Item it = items.get(i);
                it.gone = true;
                int k = i;
                postDelayed(() -> {
                    it.dy.vel = -Ui.dpf(2200);
                    it.dy.to(-getHeight() * 1.1f, 120, 1f);
                    invalidate();
                }, (long) (Math.min(k, 6) * 40 * Prefs.k()));
            }
            for (Item it : items) kill(it.pkg);
            Recents.clear(RecentsActivity.this);
            postDelayed(RecentsActivity.this::goHome, (long) (420 * Prefs.k()));
        }

        private void kill(String pkg) {
            Recents.remove(RecentsActivity.this, pkg);
            try {
                getSystemService(ActivityManager.class).killBackgroundProcesses(pkg);
            } catch (RuntimeException ignored) {
            }
        }

        private void launch(int i) {
            launching = i;
            card(i, r);
            float g = gap();
            // сначала карточка встаёт в центр (если открыли соседнюю), потом растёт в окно
            scroll.to(i * g, 260, 1f);
            grow.to(1, 230, 0.92f);
            Feel.sound(RecentsActivity.this, "app-open");
            Feel.buzz(this, Feel.CLICK);
            postDelayed(this::start, (long) (520 * Prefs.k()));   // на случай, если анимация не дошла
        }

        private void start() {
            if (launched || launching < 0) return;
            launched = true;
            Item it = items.get(launching);
            Intent i = getPackageManager().getLaunchIntentForPackage(it.pkg);
            if (i == null) {
                goHome();
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try {
                startActivity(i, ActivityOptions.makeCustomAnimation(RecentsActivity.this, 0, 0).toBundle());
            } catch (RuntimeException e) {
                goHome();
                return;
            }
            Recents.touch(RecentsActivity.this, it.pkg);
            finish();
            overridePendingTransition(0, 0);
        }

        private boolean step() {
            long now = SystemClock.uptimeMillis();
            float s = Math.min(0.05f, (now - last) / 1000f) / Prefs.k();
            last = now;
            boolean moving = false;
            if (!scroll.still(0.5f)) {
                scroll.step(s);
                moving = true;
            }
            // эти — от 0 до 1: точность своя
            for (LaunchCard.Spring sp : new LaunchCard.Spring[]{enter, grow}) {
                if (!sp.still(0.002f)) {
                    sp.step(s);
                    moving = true;
                }
            }
            for (Item it : items) {
                if (!it.dy.still(0.5f)) {
                    it.dy.step(s);
                    moving = true;
                }
                if (!it.shift.still(0.5f)) {
                    it.shift.step(s);
                    moving = true;
                }
            }
            if (launching >= 0 && !launched && grow.v > 0.96f) post(this::start);
            return moving || mode != 0;
        }

        @Override
        protected void onDraw(Canvas c) {
            boolean moving = step();
            int W = getWidth(), H = getHeight();
            if (items.isEmpty()) {
                tp.setTypeface(Ui.medium);
                tp.setTextSize(Ui.dpf(17));
                tp.setColor(0xCCFFFFFF);
                tp.setTextAlign(Paint.Align.CENTER);
                c.drawText("Нет недавних приложений", W / 2f, H * 0.47f, tp);
                tp.setTypeface(Ui.regular);
                tp.setTextSize(Ui.dpf(13));
                tp.setColor(0x80FFFFFF);
                c.drawText(VanksAccess.running() ? "Они появятся, когда вы откроете приложения"
                        : "Включите службу «Vanks: жесты и вход»", W / 2f, H * 0.47f + Ui.dpf(26), tp);
            }
            // от старых к свежим: открываемая — поверх всех
            for (int i = items.size() - 1; i >= 0; i--) if (i != launching) drawItem(c, i, W, H);
            if (launching >= 0) drawItem(c, launching, W, H);
            // «Закрыть все»
            if (!items.isEmpty() && launching < 0) {
                float cx = W / 2f, cy = H - Ui.dpf(96), rr = Ui.dpf(26);
                clear.set(cx - rr * 1.6f, cy - rr * 1.6f, cx + rr * 1.6f, cy + rr * 1.6f);
                p.setShader(null);
                p.setColor(Ui.alpha(0x33FFFFFF, enter.v));
                c.drawCircle(cx, cy, rr, p);
                Glyph.close(c, cx, cy, Ui.dpf(20), Ui.alpha(0xFFFFFFFF, enter.v));
            }
            if (moving) postInvalidateOnAnimation();
        }

        private void drawItem(Canvas c, int i, int W, int H) {
            Item it = items.get(i);
            if (i == 0 && waitFly) return;   // её место пока держит летящее окно
            card(i, r);
            r.offset(0, it.dy.v);
            float a = Ui.clamp(1 - (-it.dy.v) / (H * 0.55f)) * (i == 0 && cameByFly ? 1 : enter.v);
            if (r.right < -Ui.dpf(20) || r.left > W + Ui.dpf(20) || a <= 0.01f) return;
            float g = i == launching ? grow.v : 0;
            if (g > 0) {
                // карточка растёт в окно приложения
                r.set(r.left + (0 - r.left) * g, r.top + (0 - r.top) * g, r.right + (W - r.right) * g, r.bottom + (H - r.bottom) * g);
            }
            float rad = Ui.dpf(26) - Ui.dpf(10) * g;
            int al = Math.round(255 * a);
            // подпись над карточкой
            if (g < 0.2f) {
                float hy = r.top - Ui.dpf(14), is = Ui.dpf(26);
                ir.set(r.left + Ui.dpf(4), hy - is, r.left + Ui.dpf(4) + is, hy);
                p.setAlpha(Math.round(al * (1 - g * 5)));
                c.drawBitmap(it.icon, null, ir, p);
                tp.setTypeface(Ui.medium);
                tp.setTextSize(Ui.dpf(14));
                tp.setTextAlign(Paint.Align.LEFT);
                tp.setColor(Ui.alpha(0xFFFFFFFF, a * (1 - g * 5)));
                String l = it.label;
                float maxW = r.width() - is - Ui.dpf(14);
                if (tp.measureText(l) > maxW) {
                    int n = tp.breakText(l, true, maxW - tp.measureText("…"), null);
                    l = l.substring(0, Math.max(0, n)) + "…";
                }
                c.drawText(l, ir.right + Ui.dpf(8), hy - is / 2 + Ui.dpf(5), tp);
            }
            c.save();
            clip.reset();
            clip.addRoundRect(r, rad, rad, Path.Direction.CW);
            c.clipPath(clip);
            p.setAlpha(al);
            p.setColor(Ui.alpha(it.color, a));
            c.drawRect(r, p);
            if (it.thumb != null && !it.thumb.isRecycled()) {
                p.setAlpha(al);
                c.drawBitmap(it.thumb, null, r, p);
            } else {
                // снимка нет (окно защищено или ещё не снимали) — значок посередине
                float s = Ui.dpf(64);
                ir.set(r.centerX() - s / 2, r.centerY() - s / 2, r.centerX() + s / 2, r.centerY() + s / 2);
                p.setAlpha(al);
                c.drawBitmap(it.icon, null, ir, p);
            }
            c.restore();
        }
    }
}
