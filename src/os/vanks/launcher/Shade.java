package os.vanks.launcher;

import android.animation.ValueAnimator;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;

/**
 * Шторка Vanks OS — одна вместо системной (её раскрытие запрещает служба
 * жестов). Свайп вниз от верхнего края в любом приложении: слева и посередине —
 * уведомления карточками (нажатие открывает, смахивание в сторону убирает,
 * «Очистить» — все), справа — пункт управления с плитками.
 * Закрывается свайпом вверх, «назад» или касанием пустого места.
 *
 * Окна — службы «Vanks: жесты и вход» (поверх всех приложений): полоса
 * у верхнего края ловит свайп, сама шторка добавляется только на время.
 */
final class Shade {
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static VanksAccess svc;
    private static WindowManager wm;
    private static Edge edge;
    private static Panel panel;

    /** Vanks OS: поставить полосу у верхнего края (служба жестов запустилась). */
    static void attach(VanksAccess s) {
        detach();
        svc = s;
        wm = s.getSystemService(WindowManager.class);
        Tiles.watchTorch(s);
        edge = new Edge(s);
        WindowManager.LayoutParams lp = params(-1, Math.max(statusBar(s), Ui.dp(24)), true);
        lp.gravity = Gravity.TOP;
        lp.setTitle("Vanks shade edge");
        try {
            wm.addView(edge, lp);
        } catch (RuntimeException e) {
            edge = null;
        }
    }

    static void detach() {
        if (panel != null) remove(panel);
        if (edge != null) remove(edge);
        panel = null;
        edge = null;
        svc = null;
    }

    /** Открыть уведомления (свайп на столе). false — шторки Vanks нет (не Vanks OS или служба выключена). */
    static boolean open() {
        return open(false);
    }

    /** control — пункт управления, иначе уведомления. */
    static boolean open(boolean control) {
        Panel p = show(control);
        if (p == null) return false;
        p.animateTo(1);
        return true;
    }

    /** Уведомления пришли или ушли — открытая шторка обновляется. */
    static void changed() {
        ui.post(() -> {
            if (panel != null) panel.fill();
        });
    }

    private static Panel show(boolean control) {
        if (svc == null || wm == null) return null;
        // на экране блокировки шторки нет: уведомления и плитки — только после входа
        if (LockActivity.locked || AodActivity.active) return null;
        if (panel != null) {
            if (panel.control == control || panel.wide) return panel;
            hide();
        }
        Panel p = new Panel(svc, control);
        WindowManager.LayoutParams lp = params(-1, -1, false);
        lp.setTitle("Vanks shade");
        try {
            wm.addView(p, lp);
        } catch (RuntimeException e) {
            return null;
        }
        panel = p;
        p.opened();
        return p;
    }

    private static void hide() {
        if (panel == null) return;
        remove(panel);
        panel = null;
    }

    private static void remove(View v) {
        try {
            wm.removeView(v);
        } catch (RuntimeException ignored) {
        }
    }

    private static WindowManager.LayoutParams params(int w, int h, boolean noFocus) {
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        // шторка берёт фокус, чтобы закрываться кнопкой «назад»; полоса — нет
        if (noFocus) flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= 30) {
            lp.setFitInsetsTypes(0);
            lp.setFitInsetsSides(0);
        }
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        return lp;
    }

    static int statusBar(Context c) {
        int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? c.getResources().getDimensionPixelSize(id) : Ui.dp(24);
    }

    /**
     * Полоса у верхнего края: потянули вниз — шторка выезжает целиком.
     * За пальцем она не идёт: как только поверх полосы встаёт окно шторки,
     * Android отменяет касание полосы (ACTION_CANCEL).
     */
    private static final class Edge extends View {
        private final int slop;
        private float y0;
        private boolean opened, control;

        Edge(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    y0 = e.getRawY();
                    opened = false;
                    // правая треть — пункт управления
                    control = e.getRawX() > getWidth() * 0.66f;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!opened && e.getRawY() - y0 > slop) {
                        opened = open(control);
                        if (opened) Feel.buzz(this, Feel.CLICK);
                    }
                    break;
            }
            return true;
        }
    }

    /** Сама шторка: фон темы (матовые обои или сплошной), время; ниже уведомления или плитки. */
    private static final class Panel extends FrameLayout {
        final boolean control, wide;
        private float progress;
        private final ScrollView scroll, scroll2;
        private final LinearLayout col, col2, list;
        private final View body;
        private final TextView time, date, clear, empty;
        private TileGrid tiles;   // не final: на него ссылаются лямбды в конструкторе
        private final int slop;
        private ValueAnimator anim;
        private float downX, downY, startP;
        private boolean closing;
        private VelocityTracker vt;
        private final android.graphics.Matrix m = new android.graphics.Matrix();
        private final android.graphics.Paint bp = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        private TextView editBtn;
        /** Открытый выбор плитки (поверх плиток). */
        private View picker;

        Panel(Context c, boolean control) {
            super(c);
            this.control = control;
            setWillNotDraw(false);
            setFocusableInTouchMode(true);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            // телефон лёжа: сетка на всю ширину раздулась бы — уведомления и плитки рядом, в одной шторке
            wide = c.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            scroll = column(c);
            col = (LinearLayout) scroll.getChildAt(0);
            if (wide) {
                scroll2 = column(c);
                col2 = (LinearLayout) scroll2.getChildAt(0);
                LinearLayout twin = new LinearLayout(c);
                twin.addView(scroll, new LinearLayout.LayoutParams(0, -1, 1));
                twin.addView(scroll2, new LinearLayout.LayoutParams(0, -1, 1));
                addView(twin, new LayoutParams(-1, -1));
                body = twin;
            } else {
                scroll2 = null;
                col2 = col;
                addView(scroll, new LayoutParams(-1, -1));
                body = scroll;
            }
            // матовая подложка — те же размытые обои, что у пункта управления на столе
            if (Ui.wallBlur == null) Ui.loadWall(c);

            // время и дата слева, «Настройки» справа
            LinearLayout head = new LinearLayout(c);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(Ui.dp(8), 0, 0, Ui.dp(10));
            LinearLayout td = new LinearLayout(c);
            td.setOrientation(LinearLayout.VERTICAL);
            time = Views.label(c, "", wide ? 30 : 40, Look.digits(Ui.light), Look.fg());
            date = Views.label(c, "", 14, Ui.medium, Look.fg2());
            td.addView(time);
            td.addView(date);
            head.addView(td, new LinearLayout.LayoutParams(0, -2, 1));
            if (control || wide) {
                // правка и добавление плиток — здесь же
                TextView edit = editBtn = Views.pill(c, "Изменить");
                edit.setTextColor(Look.fg());
                edit.setBackground(Views.round(Ui.alpha(Look.fg(), 0.1f), 18));
                edit.setOnClickListener(v -> tiles.setEdit(!tiles.edit));
                LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-2, -2);
                ep.rightMargin = Ui.dp(8);
                head.addView(edit, ep);
            }
            View gear = Views.drawn(c, (cv, w, h) -> Glyph.gear(cv, w / 2, h / 2, Ui.dpf(22), Look.fg()));
            gear.setBackground(Views.round(Ui.alpha(Look.fg(), 0.1f), 20));
            gear.setOnClickListener(v -> {
                if (Tiles.start(c, new android.content.Intent(c, SettingsActivity.class))) animateTo(0);
            });
            head.addView(gear, new LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(40)));
            col.addView(head);

            if (!control || wide) {
                buildNotes(c);
                list = this.list0;
                clear = this.clear0;
                empty = this.empty0;
            } else {
                list = null;
                clear = null;
                empty = null;
            }
            if (!control && !wide) {
                tiles = null;
                setProgress(0);
                return;
            }
            tiles = new TileGrid(c);
            tiles.host = new TileGrid.Host() {
                @Override
                public void editChanged(boolean edit) {
                    editBtn.setText(edit ? "Готово" : "Изменить");
                    // на светлом акценте — тёмный текст
                    int ac = Ui.accent();
                    float l = (android.graphics.Color.red(ac) + android.graphics.Color.green(ac) + android.graphics.Color.blue(ac)) / 765f;
                    editBtn.setTextColor(!edit ? Look.fg() : l > 0.6f ? 0xFF1A1D2A : 0xFFFFFFFF);
                    editBtn.setBackground(Views.round(edit ? Ui.accent() : Ui.alpha(Look.fg(), 0.1f), 18));
                }

                @Override
                public void addTile() {
                    openPicker();
                }

                @Override
                public void tileTap(String type) {
                    if (type.isEmpty() && tiles.edit) {
                        tiles.setEdit(false);
                        return;
                    }
                    Feel.buzz(tiles, Feel.CLICK);
                    if (type.isEmpty() || Tiles.tap(c, type, tiles)) animateTo(0);
                }

                @Override
                public void mediaKey(int which) {
                    Tiles.media(c, which, tiles);
                }

                @Override
                public void slider(String type, float value, boolean done) {
                    Tiles.slider(c, type, value);
                }
            };
            col2.addView(tiles, new LinearLayout.LayoutParams(-1, -2));
            setProgress(0);
        }

        /** Колонка шторки: касание мимо карточек и плиток закрывает (в правке — заканчивает правку). */
        private ScrollView column(Context c) {
            ScrollView sv = new ScrollView(c);
            sv.setVerticalScrollBarEnabled(false);
            sv.setOverScrollMode(OVER_SCROLL_NEVER);
            LinearLayout box = new LinearLayout(c);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(Ui.dp(14), statusBar(c) + Ui.dp(10), Ui.dp(14), Ui.dp(48));
            box.setOnClickListener(v -> {
                if (tiles != null && tiles.edit) tiles.setEdit(false);
                else animateTo(0);
            });
            sv.addView(box, new LayoutParams(-1, -2));
            return sv;
        }

        /** Вбок от выреза и кнопок навигации: лёжа они стоят по краям. */
        @Override
        public android.view.WindowInsets onApplyWindowInsets(android.view.WindowInsets in) {
            if (wide) {
                android.view.DisplayCutout dc = in.getDisplayCutout();
                body.setPadding(Math.max(dc == null ? 0 : dc.getSafeInsetLeft(), in.getSystemWindowInsetLeft()), 0,
                        Math.max(dc == null ? 0 : dc.getSafeInsetRight(), in.getSystemWindowInsetRight()), 0);
            }
            return super.onApplyWindowInsets(in);
        }

        /** Телефон повернули при открытой шторке: другая раскладка, откроют заново. */
        @Override
        protected void onConfigurationChanged(android.content.res.Configuration n) {
            super.onConfigurationChanged(n);
            if ((n.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) != wide) post(() -> {
                if (panel == this) hide();
            });
        }

        /** Выбор плитки — на всю шторку, сплошной; выбрали или «назад» — обратно к плиткам. */
        private void openPicker() {
            View v = TilePicker.build(getContext(), tiles, t -> {
                tiles.add(t);
                Feel.buzz(tiles, Feel.CLICK);
                closePicker();
            });
            if (v == null) {
                android.widget.Toast.makeText(getContext(), "Все плитки уже добавлены", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            FrameLayout box = new FrameLayout(getContext());
            box.setBackgroundColor(Look.surface());
            box.setPadding(0, statusBar(getContext()), 0, 0);
            box.addView(v, new LayoutParams(-1, -1));
            box.setClickable(true);
            picker = box;
            addView(box, new LayoutParams(-1, -1));
            box.setAlpha(0);
            box.setTranslationY(Ui.dpf(40));
            box.animate().alpha(1).translationY(0).setDuration(200).start();
        }

        private void closePicker() {
            View v = picker;
            picker = null;
            if (v != null) v.animate().alpha(0).translationY(Ui.dpf(40)).setDuration(160).withEndAction(() -> removeView(v)).start();
        }

        private LinearLayout list0;
        private TextView clear0, empty0;

        private void buildNotes(Context c) {
            // «Уведомления» и «Очистить»
            LinearLayout nh = new LinearLayout(c);
            nh.setGravity(Gravity.CENTER_VERTICAL);
            nh.setPadding(Ui.dp(8), Ui.dp(14), 0, Ui.dp(8));
            nh.addView(Views.label(c, "Уведомления", 17, Ui.bold, Look.fg()), new LinearLayout.LayoutParams(0, -2, 1));
            TextView clear = clear0 = Views.pill(c, "Очистить");
            clear.setTextColor(Look.fg());
            clear.setBackground(Views.round(Ui.alpha(Look.fg(), 0.1f), 18));
            clear.setOnClickListener(v -> {
                VanksNotes n = VanksNotes.on;
                if (n != null) n.cancelAllNotifications();
                Feel.buzz(clear, Feel.CLICK);
            });
            nh.addView(clear);
            col.addView(nh);
            list0 = new LinearLayout(c);
            list0.setOrientation(LinearLayout.VERTICAL);
            col.addView(list0, new LinearLayout.LayoutParams(-1, -2));
            empty0 = Views.label(c, "Нет уведомлений", 15, Ui.medium, Look.fg2());
            empty0.setGravity(Gravity.CENTER);
            empty0.setPadding(0, Ui.dp(28), 0, Ui.dp(28));
            col.addView(empty0, new LinearLayout.LayoutParams(-1, -2));
        }

        /** Только что добавлено окно: состояние плиток, время, уведомления. */
        void opened() {
            time.setText(ClockView.timeText(getContext()));
            date.setText(ClockView.dateText());
            if (tiles != null) Tiles.read(getContext(), tiles);
            fill();
            requestFocus();
        }

        /** Карточки уведомлений: новые сверху, без сводок групп, без служебных Vanks. */
        void fill() {
            if (list == null) return;
            list.removeAllViews();
            VanksNotes n = VanksNotes.on;
            StatusBarNotification[] all = null;
            try {
                if (n != null) all = n.getActiveNotifications();
            } catch (RuntimeException ignored) {
            }
            ArrayList<StatusBarNotification> show = new ArrayList<>();
            if (all != null) {
                HashSet<String> groups = new HashSet<>();
                for (StatusBarNotification s : all)
                    if ((s.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) == 0) groups.add(s.getGroupKey());
                for (StatusBarNotification s : all) {
                    Notification x = s.getNotification();
                    if (s.getPackageName().equals(getContext().getPackageName()) && s.isOngoing()) continue;
                    if ((x.flags & Notification.FLAG_GROUP_SUMMARY) != 0 && groups.contains(s.getGroupKey())) continue;
                    if (title(x) == null && text(x) == null) continue;
                    show.add(s);
                }
            }
            show.sort((a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
            boolean clearable = false;
            for (StatusBarNotification s : show) {
                clearable |= s.isClearable();
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = Ui.dp(8);
                list.addView(new Card(getContext(), s, this), lp);
            }
            empty.setVisibility(show.isEmpty() ? VISIBLE : GONE);
            clear.setVisibility(clearable ? VISIBLE : GONE);
        }

        void setProgress(float p) {
            progress = p;
            float k = Ui.clamp(p);
            body.setAlpha(k);
            body.setTranslationY(-Ui.dpf(48) * (1 - k));
            invalidate();
        }

        void animateTo(float to) {
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(progress, to);
            anim.setDuration((long) (Math.abs(to - progress) * 320 * Prefs.k()) + 60);
            anim.setInterpolator(Anim.STD);
            anim.addUpdateListener(a -> setProgress((float) a.getAnimatedValue()));
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                private boolean cancelled;

                @Override
                public void onAnimationCancel(android.animation.Animator a) {
                    cancelled = true;
                }

                @Override
                public void onAnimationEnd(android.animation.Animator a) {
                    if (!cancelled && to <= 0) hide();
                }
            });
            anim.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            // фон темы: у стеклянных — размытые обои под дымкой, у сплошных — сплошной
            if (Ui.wallBlur != null) Look.backdrop(c, getWidth(), getHeight(), Ui.clamp(progress), m, bp);
            else c.drawColor(Ui.alpha(Look.surface(), Ui.clamp(progress)));
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() != KeyEvent.ACTION_UP) return true;
                if (picker != null) closePicker();
                else if (tiles != null && tiles.edit) tiles.setEdit(false);
                else animateTo(0);
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        /** Свайп вверх закрывает, когда листать ниже уже некуда. */
        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    closing = false;
                    break;
                case MotionEvent.ACTION_MOVE: {
                    float dy = e.getY() - downY, dx = e.getX() - downX;
                    boolean busy = picker != null || tiles != null && tiles.edit;
                    ScrollView under = wide && downX > getWidth() / 2f ? scroll2 : scroll;
                    if (!busy && dy < -slop && Math.abs(dy) > Math.abs(dx) * 1.3f && !under.canScrollVertically(1)) {
                        closing = true;
                        startP = progress;
                        downY = e.getY();
                        vt = VelocityTracker.obtain();
                        return true;
                    }
                    break;
                }
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!closing) return super.onTouchEvent(e);
            if (vt != null) vt.addMovement(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    setProgress(Ui.clamp(startP + (e.getY() - downY) / (getHeight() * 0.5f)));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float v = 0;
                    if (vt != null) {
                        vt.computeCurrentVelocity(1000);
                        v = vt.getYVelocity();
                        vt.recycle();
                        vt = null;
                    }
                    closing = false;
                    animateTo(v < -Ui.dpf(400) || progress < 0.75f ? 0 : 1);
                    return true;
                }
            }
            return true;
        }
    }

    /** Карточка уведомления: значок, приложение и время, заголовок, текст. */
    private static final class Card extends LinearLayout {
        private final StatusBarNotification sbn;
        private final Panel panel;
        private final int slop;
        private float downX, downY;
        private boolean swiping, moved;

        Card(Context c, StatusBarNotification s, Panel p) {
            super(c);
            sbn = s;
            panel = p;
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            Notification n = s.getNotification();
            setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12));
            setBackground(Views.round(Ui.alpha(Look.fg(), 0.07f), Look.radius(16)));

            ImageView icon = new ImageView(c);
            icon.setImageDrawable(icon(c, s));
            LayoutParams il = new LayoutParams(Ui.dp(38), Ui.dp(38));
            il.rightMargin = Ui.dp(12);
            il.topMargin = Ui.dp(2);
            addView(icon, il);

            LinearLayout tx = new LinearLayout(c);
            tx.setOrientation(VERTICAL);
            TextView app = Views.label(c, appName(c, s.getPackageName()) + " · " + when(c, s.getPostTime()), 12, Ui.medium, Look.fg2());
            app.setSingleLine(true);
            app.setEllipsize(TextUtils.TruncateAt.END);
            tx.addView(app);
            CharSequence t = title(n), x = text(n);
            if (t != null) {
                TextView tv = Views.label(c, t.toString(), 15, Ui.bold, Look.fg());
                tv.setSingleLine(true);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                tx.addView(tv);
            }
            if (x != null) {
                TextView xv = Views.label(c, x.toString(), 14, Ui.regular, Look.fg2());
                xv.setMaxLines(3);
                xv.setEllipsize(TextUtils.TruncateAt.END);
                tx.addView(xv);
            }
            addView(tx, new LayoutParams(0, -2, 1));
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    swiping = moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!moved && Math.hypot(dx, dy) > slop) {
                        moved = true;
                        swiping = Math.abs(dx) > Math.abs(dy) && sbn.isClearable();
                        // вертикально — листает шторка
                        if (!swiping) return false;
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (swiping) {
                        setTranslationX(dx);
                        setAlpha(1 - Ui.clamp(Math.abs(dx) / getWidth()));
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    float dx = e.getRawX() - downX;
                    if (swiping && Math.abs(dx) > getWidth() * 0.35f) dismiss(dx > 0 ? 1 : -1);
                    else if (swiping) animate().translationX(0).alpha(1).setDuration(180).start();
                    else if (!moved) open();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    animate().translationX(0).alpha(1).setDuration(180).start();
                    return true;
            }
            return true;
        }

        private void dismiss(int dir) {
            Feel.buzz(this, Feel.CLICK);
            animate().translationX(dir * getWidth()).alpha(0).setDuration(160).withEndAction(() -> {
                VanksNotes n = VanksNotes.on;
                if (n != null) n.cancelNotification(sbn.getKey());
            }).start();
        }

        /** Открыть то, что предлагает уведомление (и убрать его, если оно само так просит). */
        private void open() {
            Notification n = sbn.getNotification();
            PendingIntent pi = n.contentIntent;
            if (pi != null) {
                try {
                    Bundle o = null;
                    if (Build.VERSION.SDK_INT >= 34) {
                        // уведомление открывается из фона — Vanks OS это разрешено
                        o = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(Build.VERSION.SDK_INT >= 36
                                ? ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                                : ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle();
                    }
                    pi.send(getContext(), 0, null, null, null, null, o);
                } catch (PendingIntent.CanceledException | RuntimeException ignored) {
                }
            }
            if ((n.flags & Notification.FLAG_AUTO_CANCEL) != 0 && VanksNotes.on != null) VanksNotes.on.cancelNotification(sbn.getKey());
            panel.animateTo(0);
        }
    }

    private static CharSequence title(Notification n) {
        CharSequence t = n.extras == null ? null : n.extras.getCharSequence(Notification.EXTRA_TITLE);
        return TextUtils.isEmpty(t) ? null : t;
    }

    private static CharSequence text(Notification n) {
        if (n.extras == null) return null;
        CharSequence x = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (TextUtils.isEmpty(x)) x = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (TextUtils.isEmpty(x)) {
            CharSequence[] lines = n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null && lines.length > 0) x = TextUtils.join("\n", Arrays.asList(lines));
        }
        return TextUtils.isEmpty(x) ? null : x;
    }

    /** Картинка уведомления (аватар) или значок приложения. */
    private static Drawable icon(Context c, StatusBarNotification s) {
        Icon big = s.getNotification().getLargeIcon();
        Drawable d = null;
        try {
            if (big != null) d = big.loadDrawable(c);
            if (d == null) d = c.getPackageManager().getApplicationIcon(s.getPackageName());
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        return d;
    }

    private static String appName(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** «сейчас», «5 мин», «14:20» или «8 нояб.». */
    private static String when(Context c, long t) {
        long ago = System.currentTimeMillis() - t;
        if (ago < 60_000) return "сейчас";
        if (ago < 3_600_000) return (ago / 60_000) + " мин";
        if (ago < 86_400_000) return DateFormat.getTimeFormat(c).format(new Date(t));
        return new java.text.SimpleDateFormat("d MMM", new java.util.Locale("ru")).format(new Date(t));
    }

    private Shade() {}
}
