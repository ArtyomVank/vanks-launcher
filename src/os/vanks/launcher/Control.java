package os.vanks.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Пункт управления: выезжает свайпом вниз от правого верхнего угла. Весь экран
 * становится матовым, сверху — время и дата, ниже — плитки. Закрывается свайпом
 * вверх, нажатием мимо плиток или «назад».
 */
final class Control extends FrameLayout {
    final ScrollView scroll;
    TileGrid tiles;   // не final: на него ссылается лямбда в конструкторе
    float progress;
    private final LinearLayout col;
    private final TextView time, date, editBtn;
    private View gear;
    private TextView hint;
    private final View bottomSpace;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix m = new Matrix();
    private ValueAnimator anim;
    private final int slop;
    private float downX, downY, startP;
    private boolean dragging;
    private VelocityTracker vt;

    Control(Home h) {
        super(h);
        Context c = h;
        setWillNotDraw(false);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(col, new LayoutParams(-1, -2));
        addView(scroll, new LayoutParams(-1, -1));

        // заголовок: время и дата слева, «Изменить» и шестерёнка справа
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(22), Ui.dp(8), Ui.dp(16), 0);
        LinearLayout td = new LinearLayout(c);
        td.setOrientation(LinearLayout.VERTICAL);
        time = Views.label(c, "", 44, Ui.light, 0xFFFFFFFF);
        date = Views.label(c, "", 14, Ui.medium, 0xCCFFFFFF);
        td.addView(time);
        td.addView(date);
        head.addView(td, new LinearLayout.LayoutParams(0, -2, 1));
        editBtn = Views.pill(c, "Изменить");
        editBtn.setOnClickListener(v -> this.tiles.setEdit(!this.tiles.edit));
        head.addView(editBtn);
        gear = Views.drawn(c, (cv, w, hh) -> Glyph.gear(cv, w / 2, hh / 2, Ui.dpf(22), Look.fg()));
        gear.setBackground(Views.round(0x24FFFFFF, 20));
        gear.setOnClickListener(v -> h.openSettings(null));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(40));
        gp.leftMargin = Ui.dp(8);
        head.addView(gear, gp);
        col.addView(head);

        tiles = new TileGrid(c);
        tiles.host = new TileGrid.Host() {
            @Override
            public void editChanged(boolean edit) {
                editBtn.setText(edit ? "Готово" : "Изменить");
                editBtn.setTextColor(edit ? 0xFFFFFFFF : Look.fg());
                editBtn.setBackground(Views.round(edit ? Ui.accent() : Ui.alpha(Look.fg(), 0.14f), 18));
            }

            @Override
            public void addTile() {
                h.chooseTile();
            }

            @Override
            public void tileTap(String type) {
                if (type.isEmpty()) h.closeControl();
                else h.tileTap(type);
            }

            @Override
            public void mediaKey(int which) {
                h.mediaKey(which);
            }

            @Override
            public void slider(String type, float value, boolean done) {
                h.slider(type, value, done);
            }
        };
        col.addView(tiles, new LinearLayout.LayoutParams(-1, -2));

        hint = Views.label(c, "Долгое нажатие — правка: потяните уголок, чтобы изменить размер.\nСмахните вверх, чтобы закрыть.",
                12, Ui.regular, 0x99FFFFFF);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(Ui.dp(22), Ui.dp(4), Ui.dp(22), 0);
        col.addView(hint);
        bottomSpace = new View(c);
        col.addView(bottomSpace, new LinearLayout.LayoutParams(-1, Ui.dp(40)));
        setProgress(0);
    }

    void setInsets(int top, int bottom) {
        col.setPadding(0, top + Ui.dp(6), 0, 0);
        bottomSpace.getLayoutParams().height = bottom + Ui.dp(40);
        bottomSpace.requestLayout();
    }

    void refreshClock() {
        time.setText(ClockView.timeText(getContext()));
        date.setText(ClockView.dateText());
        // цвета и цифры — от оформления
        time.setTextColor(Look.fg());
        time.setTypeface(Look.digits(Ui.light));
        date.setTextColor(Look.fg2());
        editBtn.setTextColor(tiles.edit ? 0xFFFFFFFF : Look.fg());
        if (!tiles.edit) editBtn.setBackground(Views.round(Ui.alpha(Look.fg(), 0.14f), 18));
        gear.setBackground(Views.round(Ui.alpha(Look.fg(), 0.14f), 20));
        gear.invalidate();
        hint.setTextColor(Look.fg2());
    }

    void setProgress(float p) {
        progress = p;
        setVisibility(p > 0.001f ? VISIBLE : INVISIBLE);   // не GONE: размеры нужны уже до первого открытия
        float k = Ui.clamp(p);
        scroll.setAlpha(Ui.clamp((k - 0.15f) / 0.85f));
        scroll.setTranslationY(-(1 - k) * Ui.dpf(70));
        float s = 0.92f + 0.08f * k;
        scroll.setPivotX(getWidth() * 0.75f);
        scroll.setPivotY(0);
        scroll.setScaleX(s);
        scroll.setScaleY(s);
        invalidate();
    }

    void animateTo(float to, Runnable end) {
        if (anim != null) anim.cancel();
        if (to > 0) refreshClock();
        anim = Anim.run(progress, to, Math.round(240 + 180 * Math.abs(to - progress)), Anim.EMPH, this::setProgress, () -> {
            if (to <= 0) onClosed();
            if (end != null) end.run();
        });
    }

    boolean isOpen() {
        return progress > 0.001f;
    }

    private void onClosed() {
        tiles.setEdit(false);
        scroll.scrollTo(0, 0);
    }

    @Override
    protected void onDraw(Canvas c) {
        // подложка на весь экран — от темы: матовое стекло или сплошная
        Look.backdrop(c, getWidth(), getHeight(), Ui.clamp(progress), m, paint);
    }

    // ── свайп вверх закрывает ─────────────────────────────────────────────

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                dragging = false;
                track(e, true);
                return false;
            case MotionEvent.ACTION_MOVE: {
                track(e, false);
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (!tiles.edit && dy < -slop && Math.abs(dy) > Math.abs(dx) * 1.3f && !scroll.canScrollVertically(1)) {
                    dragging = true;
                    startP = progress;
                    downY = e.getY();
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                track(e, true);
                return true;
            case MotionEvent.ACTION_MOVE:
                track(e, false);
                if (!dragging && downY - e.getY() > slop) {
                    dragging = true;
                    startP = progress;
                    downY = e.getY();
                }
                if (dragging) {
                    if (anim != null) anim.cancel();
                    setProgress(Ui.clamp(startP + (e.getY() - downY) / (getHeight() * 0.45f)));
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                track(e, false);
                float vy = 0;
                if (vt != null) {
                    vt.computeCurrentVelocity(1000);
                    vy = vt.getYVelocity();
                    vt.recycle();
                    vt = null;
                }
                if (dragging) {
                    boolean close = vy < -Ui.dpf(500) || (vy < Ui.dpf(500) && progress < 0.7f);
                    animateTo(close ? 0 : 1, null);
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP && !tiles.edit) {
                    // нажатие по пустому месту
                    animateTo(0, null);
                }
                dragging = false;
                return true;
            }
        }
        return true;
    }

    private void track(MotionEvent e, boolean reset) {
        if (reset || vt == null) {
            if (vt != null) vt.recycle();
            vt = VelocityTracker.obtain();
        }
        vt.addMovement(e);
    }
}
