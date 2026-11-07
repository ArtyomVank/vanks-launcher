package os.vanks.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Поиск — свайп вниз посередине экрана: поле с клавиатурой, предложения,
 * приложения по названию, поиск в интернете и скрытые приложения (по
 * отпечатку). Закрывается свайпом вверх, нажатием мимо или «назад».
 */
final class Search extends FrameLayout {
    float progress;
    final ScrollView scroll;
    final EditText search;
    AppGrid apps;   // не final: на него ссылается лямбда в конструкторе
    final AppGrid hiddenGrid;
    boolean hiddenOpen;
    private final Home home;
    private final LinearLayout col;
    private final TextView section, hiddenBtn, webRow;
    private final View bottomSpace;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix m = new Matrix();
    private ValueAnimator anim;
    private final int slop;
    private float downX, downY, startP;
    private boolean dragging;
    private VelocityTracker vt;

    Search(Home h) {
        super(h);
        home = h;
        Context c = h;
        setWillNotDraw(false);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(col, new LayoutParams(-1, -2));
        addView(scroll, new LayoutParams(-1, -1));
        // нажатие мимо закрывает
        col.setOnClickListener(v -> animateTo(0, null));

        search = new EditText(c);
        search.setHint("Поиск");
        search.setTextSize(17);
        search.setTypeface(Ui.regular);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_GO);
        search.setPadding(Ui.dp(44), 0, Ui.dp(16), 0);
        FrameLayout sf = new FrameLayout(c);
        sf.addView(search, new LayoutParams(-1, Ui.dp(46)));
        View sg = Views.drawn(c, (cv, w, hh) -> Glyph.search(cv, w / 2, hh / 2, Ui.dpf(20), Look.fg2()));
        sf.addView(sg, new LayoutParams(Ui.dp(44), Ui.dp(46)));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, Ui.dp(46));
        sp.leftMargin = sp.rightMargin = Ui.dp(16);
        sp.topMargin = Ui.dp(10);
        sp.bottomMargin = Ui.dp(8);
        col.addView(sf, sp);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
            public void onTextChanged(CharSequence s, int a, int b, int d) {}
            public void afterTextChanged(Editable s) { filter(); }
        });
        search.setOnEditorActionListener((v, id, ev) -> {
            if (!this.apps.apps.isEmpty()) home.launch(this.apps.apps.get(0), null);
            else if (query().length() > 0) home.webSearch(query());
            return true;
        });

        section = Views.label(c, "", 13, Ui.bold, 0x99FFFFFF);
        section.setLetterSpacing(0.06f);
        section.setPadding(Ui.dp(22), Ui.dp(10), Ui.dp(22), Ui.dp(2));
        col.addView(section);

        apps = new AppGrid(c);
        apps.setPadding(Ui.dp(8), Ui.dp(6), Ui.dp(8), 0);
        apps.listener = h.appListener;
        col.addView(apps, new LinearLayout.LayoutParams(-1, -2));

        webRow = Views.pill(c, "");
        webRow.setGravity(Gravity.CENTER_VERTICAL);
        webRow.setPadding(Ui.dp(18), 0, Ui.dp(18), 0);
        webRow.setOnClickListener(v -> home.webSearch(query()));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-1, Ui.dp(50));
        wp.leftMargin = wp.rightMargin = Ui.dp(16);
        wp.topMargin = Ui.dp(10);
        col.addView(webRow, wp);

        hiddenBtn = Views.pill(c, "");
        hiddenBtn.setGravity(Gravity.CENTER);
        hiddenBtn.setOnClickListener(v -> {
            if (hiddenOpen) setHiddenOpen(false);
            else home.unlockHidden();
        });
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, Ui.dp(48));
        hp.leftMargin = hp.rightMargin = Ui.dp(16);
        hp.topMargin = Ui.dp(14);
        col.addView(hiddenBtn, hp);

        hiddenGrid = new AppGrid(c);
        hiddenGrid.setPadding(Ui.dp(8), Ui.dp(12), Ui.dp(8), 0);
        hiddenGrid.listener = h.appListener;
        hiddenGrid.setVisibility(GONE);
        col.addView(hiddenGrid, new LinearLayout.LayoutParams(-1, -2));

        bottomSpace = new View(c);
        col.addView(bottomSpace, new LinearLayout.LayoutParams(-1, Ui.dp(40)));
        setProgress(0);
    }

    void setInsets(int top, int bottom) {
        col.setPadding(0, top + Ui.dp(4), 0, 0);
        bottomSpace.getLayoutParams().height = bottom + Ui.dp(40);
        bottomSpace.requestLayout();
    }

    /** Подготовить к открытию: пустое поле, цвета от оформления. */
    void prepare() {
        if (search.length() > 0) search.setText("");
        int fg = Look.fg();
        search.setTextColor(fg);
        search.setHintTextColor(Look.fg2());
        search.setBackground(Views.round(Ui.alpha(fg, 0.16f), Look.radius(10) / Ui.density));
        section.setTextColor(Look.fg2());
        apps.setLabelColor(Look.light() ? fg : 0xFFF4F6FF);
        hiddenGrid.setLabelColor(Look.light() ? fg : 0xFFF4F6FF);
        for (TextView t : new TextView[]{webRow, hiddenBtn}) {
            t.setTextColor(fg);
            t.setBackground(Views.round(Ui.alpha(fg, 0.14f), 18));
        }
        reload();
    }

    void reload() {
        filter();
        List<Apps.App> hid = Apps.hiddenApps();
        hiddenGrid.setApps(hid);
        hiddenBtn.setText(hiddenOpen ? "Спрятать скрытые" : "Скрытые приложения · " + hid.size() + "  (по отпечатку)");
        hiddenBtn.setVisibility(!hid.isEmpty() || hiddenOpen ? VISIBLE : GONE);
    }

    void setHiddenOpen(boolean open) {
        hiddenOpen = open;
        hiddenGrid.setVisibility(open ? VISIBLE : GONE);
        if (open) hiddenGrid.playEnter();
        reload();
    }

    private String query() {
        return search.getText().toString().trim();
    }

    private void filter() {
        String q = query().toLowerCase(new Locale("ru"));
        List<Apps.App> v = Apps.visible();
        webRow.setVisibility(!q.isEmpty() ? VISIBLE : GONE);
        webRow.setText("Искать «" + query() + "» в интернете");
        if (q.isEmpty()) {
            // предложения: док и первые значки главного экрана
            ArrayList<String> keys = new ArrayList<>(Prefs.dock);
            keys.addAll(Prefs.home);
            List<Apps.App> s = Apps.resolve(keys);
            section.setText("ПРЕДЛОЖЕНИЯ");
            apps.setApps(s.subList(0, Math.min(8, s.size())));
            return;
        }
        ArrayList<Apps.App> starts = new ArrayList<>(), contains = new ArrayList<>();
        for (Apps.App a : v) {
            String l = a.label.toLowerCase(new Locale("ru"));
            if (l.startsWith(q)) starts.add(a);
            else if (l.contains(q) || a.cn.getPackageName().contains(q)) contains.add(a);
        }
        starts.addAll(contains);
        section.setText(starts.isEmpty() ? "НИЧЕГО НЕ НАЙДЕНО" : "ПРИЛОЖЕНИЯ");
        apps.setApps(starts);
    }

    void setProgress(float p) {
        progress = p;
        setVisibility(p > 0.001f ? VISIBLE : INVISIBLE);   // не GONE: размеры нужны уже до первого открытия
        float k = Ui.clamp(p);
        scroll.setAlpha(Ui.clamp((k - 0.1f) / 0.9f));
        scroll.setTranslationY(-(1 - k) * Ui.dpf(60));
        invalidate();
    }

    void animateTo(float to, Runnable end) {
        if (anim != null) anim.cancel();
        anim = Anim.run(progress, to, Math.round(240 + 160 * Math.abs(to - progress)), Anim.EMPH, p -> {
            setProgress(p);
            home.searchMoved();
        }, () -> {
            if (to <= 0) onClosed();
            if (end != null) end.run();
        });
    }

    boolean isOpen() {
        return progress > 0.001f;
    }

    void onClosed() {
        if (search.length() > 0) search.setText("");
        search.clearFocus();
        if (hiddenOpen) setHiddenOpen(false);
        scroll.scrollTo(0, 0);
        home.hideKeyboard();
    }

    @Override
    protected void onDraw(Canvas c) {
        Look.backdrop(c, getWidth(), getHeight(), Ui.clamp(progress), m, paint);
    }

    // ── закрытие свайпом вверх ────────────────────────────────────────────

    private boolean closing(float dx, float dy) {
        return dy < -slop && -dy > Math.abs(dx) * 1.3f && !scroll.canScrollVertically(1);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                dragging = false;
                track(e, true);
                return false;
            case MotionEvent.ACTION_MOVE:
                track(e, false);
                if (closing(e.getX() - downX, e.getY() - downY)) {
                    dragging = true;
                    startP = progress;
                    downX = e.getX();
                    downY = e.getY();
                    return true;
                }
                return false;
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
                if (!dragging && closing(e.getX() - downX, e.getY() - downY)) {
                    dragging = true;
                    startP = progress;
                    downX = e.getX();
                    downY = e.getY();
                }
                if (dragging) {
                    if (anim != null) anim.cancel();
                    setProgress(Ui.clamp(startP + (e.getY() - downY) / (getHeight() * 0.4f)));
                    home.searchMoved();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                track(e, false);
                float v = 0;
                if (vt != null) {
                    vt.computeCurrentVelocity(1000);
                    v = vt.getYVelocity();
                    vt.recycle();
                    vt = null;
                }
                if (dragging) {
                    boolean close = v < -Ui.dpf(500) || (v < Ui.dpf(500) && progress < 0.65f);
                    animateTo(close ? 0 : 1, null);
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
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
