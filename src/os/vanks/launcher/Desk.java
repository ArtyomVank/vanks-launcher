package os.vanks.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.ArrayList;

/**
 * Рабочие столы: страницы с сеткой клеток, в клетках — значки, папки и
 * виджеты (Layout). Листаются свайпом (жест ловит главный экран и двигает pos).
 *
 * Правка, как в ColorOS: значок или виджет зажали и повели — стол чуть
 * уменьшается, видна сетка, вещь едет за пальцем. Отпустили на свободное
 * место — встала туда; на значок — получилась папка (или значок лёг в папку);
 * к краю экрана — соседний стол; на док — в док. В правке у виджета и папки
 * есть уголок размера и крестик.
 */
final class Desk extends FrameLayout {
    interface Host {
        void launch(Apps.App a, RectF screenRect);
        void itemMenu(Layout.Item it, View v, float rawX, float rawY);
        void closeMenus();
        void openFolder(Layout.Item it, View v);
        void widgetTap(Layout.Item it, View v, float x, float y);
        void editChanged(boolean editing);
        boolean overDock(float rawX, float rawY);
        boolean dropToDock(Layout.Item app);
        void layoutChanged();
        void wave(float rawX, float rawY);              // поставили — стеклянная волна, как на ПК
        void widgetVariants(Layout.Item it);            // виды виджета снизу
    }

    Host host;
    float pos;
    Runnable onScroll;
    boolean editing;
    final DragLayer dragLayer;

    private final ArrayList<PageView> views = new ArrayList<>();
    private Layout.Item selected;
    /** Отмеченные в правке значки: тащатся все вместе. */
    final java.util.LinkedHashSet<Layout.Item> picked = new java.util.LinkedHashSet<>();
    private final ArrayList<Layout.Item> group = new ArrayList<>();
    private final ArrayList<Bitmap> groupBmps = new ArrayList<>();
    private final int slop;

    // перетаскивание
    private Layout.Item dragItem;
    private ItemView dragView;
    private Bitmap dragBmp;
    private float grabX, grabY, dragRawX, dragRawY;
    private int targetX = -1, targetY = -1;
    private boolean targetOk, dockHover;
    private Layout.Item folderTarget, hoverItem;
    private long hoverSince;
    private final Runnable flip = this::flipPage;
    private int flipDir;

    Desk(Context c) {
        super(c);
        setClipChildren(false);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        dragLayer = new DragLayer(c);
    }

    // ── размеры ───────────────────────────────────────────────────────────

    float padX() { return Ui.dpf(8); }

    float cellW() { return (getWidth() - 2 * padX()) / Layout.cols(); }

    float cellH() { return getHeight() / (float) Math.max(1, Layout.rows); }

    float labelH() { return Prefs.labels ? Ui.dpf(30) : Ui.dpf(10); }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (h <= 0) return;
        int rows = Math.max(3, (int) (h / (Apps.iconPx + labelH() + Ui.dpf(12))));
        if (rows != Layout.rows) {
            Layout.rows = rows;
            if (Layout.sync()) Layout.save();
        }
        Widgets.cellW = cellW();
        Widgets.cellH = cellH();
        post(this::rebuild);
    }

    /** Пересобрать страницы по Layout. */
    void rebuild() {
        removeAllViews();
        views.clear();
        int n = Layout.pages + (editing ? 1 : 0);   // в правке — пустая страница в конце
        for (int p = 0; p < n; p++) {
            PageView pv = new PageView(getContext(), p);
            for (Layout.Item it : Layout.onPage(p)) {
                if (it == dragItem) continue;
                ItemView iv = new ItemView(getContext(), it);
                pv.addView(iv);
            }
            addView(pv, new LayoutParams(-1, -1));
            views.add(pv);
        }
        pos = Math.max(0, Math.min(n - 1, pos));
        apply();
    }

    int count() {
        return Math.max(1, views.size());
    }

    void setPos(float p) {
        pos = p;
        apply();
    }

    private void apply() {
        int w = getWidth();
        for (int i = 0; i < views.size(); i++) {
            float x = (i - pos) * w;
            View v = views.get(i);
            v.setTranslationX(x);
            // соседние столы тоже нарисованы заранее: в начале свайпа не рисовать их с нуля
            v.setVisibility(Math.abs(i - pos) < 1.99f ? VISIBLE : INVISIBLE);
        }
        if (onScroll != null) onScroll.run();
    }

    /**
     * Листают столы: видимые столы — готовыми картинками в видеокарте (слой),
     * при движении сдвигается картинка, а не перерисовываются значки и стекло.
     */
    void setPaging(boolean on) {
        if (paging == on) return;
        paging = on;
        for (int i = 0; i < views.size(); i++) {
            View v = views.get(i);
            boolean near = Math.abs(i - pos) < 1.99f;
            v.setLayerType(on && near ? LAYER_TYPE_HARDWARE : LAYER_TYPE_NONE, null);
        }
    }

    boolean paging;

    /** Раз в секунду: перерисовать только живые виджеты видимого стола, а не все значки. */
    void invalidateWidgets() {
        if (paging) return;
        int cur = Math.round(pos);
        if (cur < 0 || cur >= views.size()) return;
        PageView pv = views.get(cur);
        for (int i = 0; i < pv.getChildCount(); i++) {
            View v = pv.getChildAt(i);
            if (v instanceof ItemView && Layout.WIDGET.equals(((ItemView) v).item.type)) v.invalidate();
        }
    }

    void invalidateAll() {
        for (PageView pv : views) {
            pv.invalidate();
            for (int i = 0; i < pv.getChildCount(); i++) pv.getChildAt(i).invalidate();
        }
    }

    /** Значки текущей страницы выскакивают по очереди. */
    void playEnter() {
        int i = Math.round(pos);
        if (i < 0 || i >= views.size()) return;
        PageView pv = views.get(i);
        for (int k = 0; k < pv.getChildCount(); k++) {
            View v = pv.getChildAt(k);
            v.setScaleX(0.6f);
            v.setScaleY(0.6f);
            v.setAlpha(0);
            v.animate().scaleX(1).scaleY(1).alpha(1).setStartDelay((long) (k * 22 * Prefs.k()))
                    .setDuration((long) (420 * Prefs.k())).setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
        }
    }

    // ── правка ────────────────────────────────────────────────────────────

    void setEditing(boolean on) {
        if (editing == on) return;
        editing = on;
        if (!on) {
            selected = null;
            picked.clear();
        }
        float s = on ? 0.9f : 1f;
        animate().scaleX(s).scaleY(s).setDuration((long) (360 * Prefs.k())).setInterpolator(Anim.EMPH).start();
        if (on) {
            // не пересобирать: палец, может быть, уже тащит значок — его вид должен остаться
            PageView extra = new PageView(getContext(), views.size());
            addView(extra, new LayoutParams(-1, -1));
            views.add(extra);
            apply();
            invalidateAll();
        } else rebuild();
        if (host != null) host.editChanged(on);
    }

    void tapEmpty() {
        if (!editing) return;
        if (selected != null || !picked.isEmpty()) {
            selected = null;
            picked.clear();
            invalidateAll();
        } else setEditing(false);
    }

    /** Правка с уже выбранной вещью — у неё сразу уголок размера. */
    void selectItem(Layout.Item it) {
        setEditing(true);
        select(it);
    }

    private void select(Layout.Item it) {
        selected = it;
        invalidateAll();
    }

    // ── перетаскивание ────────────────────────────────────────────────────

    boolean dragging() {
        return dragItem != null;
    }

    private void startDrag(ItemView v, float rawX, float rawY) {
        if (host != null) host.closeMenus();
        if (!editing) setEditing(true);
        dragItem = v.item;
        dragView = v;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        grabX = rawX - loc[0];
        grabY = rawY - loc[1];
        // снимок вещи (с учётом уменьшения стола в правке)
        float sc = getScaleX();
        int bw = Math.max(1, Math.round(v.getWidth() * sc)), bh = Math.max(1, Math.round(v.getHeight() * sc));
        dragBmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(dragBmp);
        c.scale(sc, sc);
        v.drawForDrag(c);
        v.setVisibility(INVISIBLE);
        // отмеченные значки едут вместе — стопкой под пальцем
        group.clear();
        groupBmps.clear();
        if (picked.contains(v.item) && picked.size() > 1) {
            for (Layout.Item it : picked) {
                if (it == v.item || !Layout.APP.equals(it.type)) continue;
                group.add(it);
                ItemView gv = viewOf(it);
                if (gv != null) {
                    Bitmap gb = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                    Canvas gc = new Canvas(gb);
                    gc.scale(sc, sc);
                    gv.drawForDrag(gc);
                    groupBmps.add(gb);
                    gv.setVisibility(INVISIBLE);
                }
            }
        } else picked.clear();
        Feel.buzz(this, Feel.PRESS);
        Feel.sound(getContext(), "pop");
        dragRawX = rawX;
        dragRawY = rawY;
        dragLayer.startLift();
        dragMove(rawX, rawY);
    }

    private ItemView viewOf(Layout.Item it) {
        for (PageView pv : views)
            for (int i = 0; i < pv.getChildCount(); i++) {
                View v = pv.getChildAt(i);
                if (v instanceof ItemView && ((ItemView) v).item == it) return (ItemView) v;
            }
        return null;
    }

    /** Первая свободная клетка на странице, начиная с (x, y) по порядку чтения; нет — где угодно дальше. */
    private int[] freeFrom(int page, int x, int y) {
        int cols = Layout.cols();
        for (int i = Math.max(0, y * cols + x); i < cols * Layout.rows; i++)
            if (Layout.free(page, i % cols, i / cols, 1, 1, null)) return new int[]{page, i % cols, i / cols};
        return Layout.firstFree(page, 1, 1);
    }

    private void dragMove(float rawX, float rawY) {
        dragRawX = rawX;
        dragRawY = rawY;
        dragLayer.invalidate();
        int page = Math.round(pos);
        if (page < 0 || page >= views.size()) return;
        PageView pv = views.get(page);
        int[] loc = new int[2];
        pv.getLocationOnScreen(loc);
        float sc = getScaleX();
        // точка на странице (в её координатах, без уменьшения)
        float lx = (rawX - loc[0]) / sc, ly = (rawY - loc[1]) / sc;
        dockHover = dragItem.type.equals(Layout.APP) && host != null && host.overDock(rawX, rawY);
        // над чем палец
        int cx = (int) Math.floor((lx - padX()) / cellW()), cy = (int) Math.floor(ly / cellH());
        Layout.Item under = Layout.at(page, cx, cy);
        if (under == dragItem) under = null;
        Layout.Item ft = null;
        if (!dockHover && under != null && dragItem.type.equals(Layout.APP)
                && (under.type.equals(Layout.APP) || under.type.equals(Layout.FOLDER))) {
            // в середину значка — папка
            RectF r = pv.rectFor(under);
            float ix = r.width() * 0.22f, iy = r.height() * 0.22f;
            if (lx > r.left + ix && lx < r.right - ix && ly > r.top + iy && ly < r.bottom - iy) {
                if (hoverItem != under) {
                    hoverItem = under;
                    hoverSince = SystemClock.uptimeMillis();
                } else if (SystemClock.uptimeMillis() - hoverSince > 260) ft = under;
            } else hoverItem = null;
        } else hoverItem = null;
        if (ft != folderTarget) {
            folderTarget = ft;
            if (ft != null) Feel.buzz(this, Feel.TICK);
            pv.invalidateItems();
        }
        // куда встанет левый верхний угол
        float sx = (rawX - grabX * sc - loc[0]) / sc, sy = (rawY - grabY * sc - loc[1]) / sc;
        targetX = Math.round((sx - padX()) / cellW());
        targetY = Math.round(sy / cellH());
        targetOk = !dockHover && folderTarget == null && Layout.free(page, targetX, targetY, dragItem.w, dragItem.h, dragItem);
        pv.invalidate();
        // у края экрана — соседний стол
        int dir = rawX < Ui.dpf(22) ? -1 : rawX > getRootView().getWidth() - Ui.dpf(22) ? 1 : 0;
        if (dir != flipDir) {
            flipDir = dir;
            removeCallbacks(flip);
            if (dir != 0) postDelayed(flip, 520);
        }
        if (dragItem != null) postInvalidateOnAnimation();
    }

    private void flipPage() {
        if (dragItem == null || flipDir == 0) return;
        int to = Math.max(0, Math.min(views.size() - 1, Math.round(pos) + flipDir));
        if (to == Math.round(pos)) return;
        Feel.buzz(this, Feel.TICK);
        Anim.spring(pos, to, 0, 260, 0.9f, this::setPos, () -> dragMove(dragRawX, dragRawY));
        postDelayed(flip, 900);
    }

    private void dragEnd() {
        removeCallbacks(flip);
        flipDir = 0;
        Layout.Item d = dragItem;
        int page = Math.round(pos);
        boolean moved = true;
        boolean waved = false;
        if (dockHover && host != null && host.dropToDock(d)) {
            Layout.items.remove(d);
            for (Layout.Item g : group) if (host.dropToDock(g)) Layout.items.remove(g);
        } else if (folderTarget != null) {
            Layout.Item t = folderTarget;
            if (t.type.equals(Layout.FOLDER)) {
                t.apps.add(d.key);
                for (Layout.Item g : group) {
                    t.apps.add(g.key);
                    Layout.items.remove(g);
                }
            } else {
                // значок на значок — новая папка на месте того, на который положили
                t.type = Layout.FOLDER;
                t.apps.clear();
                t.apps.add(t.key);
                t.apps.add(d.key);
                for (Layout.Item g : group) {
                    t.apps.add(g.key);
                    Layout.items.remove(g);
                }
                t.key = null;
                t.id = Layout.newId("f");
                t.name = Folders.guessName(t.apps);
                t.style = "grid";
            }
            Layout.items.remove(d);
            Feel.sound(getContext(), "drop");
        } else if (targetOk) {
            d.page = page;
            d.x = targetX;
            d.y = targetY;
            // остальные отмеченные — следом, в ближайшие свободные клетки
            for (Layout.Item g : group) {
                g.page = -1;   // своё старое место не считается занятым
                int[] f = freeFrom(page, targetX + 1, targetY);
                g.page = f[0];
                g.x = f[1];
                g.y = f[2];
            }
            Feel.sound(getContext(), "snap-in");
        } else {
            // занято значком того же размера — поменяться местами
            Layout.Item o = Layout.at(page, targetX, targetY);
            if (o != null && o != d && o.w == d.w && o.h == d.h && o.x == targetX && o.y == targetY) {
                o.page = d.page;
                o.x = d.x;
                o.y = d.y;
                d.page = page;
                d.x = targetX;
                d.y = targetY;
                Feel.sound(getContext(), "snap-in");
            } else moved = false;
        }
        if (moved && host != null) host.wave(dragRawX, dragRawY);
        if (moved) Feel.buzz(this, Feel.CONFIRM);
        boolean wasGroup = !group.isEmpty();
        group.clear();
        groupBmps.clear();
        if (wasGroup) picked.clear();
        dragItem = null;
        dragView = null;
        folderTarget = null;
        hoverItem = null;
        dockHover = false;
        float fx = dragRawX - grabX * getScaleX(), fy = dragRawY - grabY * getScaleY();
        dragBmp = null;
        dragLayer.invalidate();
        Layout.sync();
        Layout.save();
        // пересобрать после касания: вид, который его получает, сейчас уберут
        post(() -> {
            rebuild();
            if (host != null) host.layoutChanged();
            // вещь «прилетает» на место от пальца
            land(d, fx, fy);
        });
    }

    private void land(Layout.Item d, float fromRawX, float fromRawY) {
        for (PageView pv : views) {
            for (int i = 0; i < pv.getChildCount(); i++) {
                View v = pv.getChildAt(i);
                if (!(v instanceof ItemView) || ((ItemView) v).item != d) continue;
                v.post(() -> {
                    int[] loc = new int[2];
                    v.getLocationOnScreen(loc);
                    float dx = (fromRawX - loc[0]) / getScaleX(), dy = (fromRawY - loc[1]) / getScaleY();
                    v.setTranslationX(dx);
                    v.setTranslationY(dy);
                    Anim.spring(1, 0, 0, 380, 0.78f, k -> {
                        v.setTranslationX(dx * k);
                        v.setTranslationY(dy * k);
                    }, null);
                    // встал — дрогнул, как желе
                    Anim.spring(1.16f, 1f, 0, 300, 0.32f, k -> {
                        v.setScaleX(k);
                        v.setScaleY(2 - k);
                    }, null);
                });
                return;
            }
        }
    }

    /**
     * Снимок вещи над всем экраном, пока её тащат. Он как желе: тянется по
     * ходу движения и сплющивается поперёк, а на остановке дрожит пружиной.
     * Отмеченные значки едут стопкой позади, с числом в уголке.
     */
    final class DragLayer extends View {
        private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float lift;
        private float lastX, lastY;
        private long lastT;
        private float vx, vy;          // сглаженная скорость, px/с
        private float jx, jy, jvx, jvy; // деформация желе и её скорость

        DragLayer(Context c) {
            super(c);
            sh.setColor(0x55000000);
        }

        void startLift() {
            lift = 0;
            lastT = 0;
            jx = jy = jvx = jvy = vx = vy = 0;
            Anim.spring(0, 1, 0, 420, 0.7f, k -> {
                lift = k;
                invalidate();
            }, null);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (dragBmp == null) return;
            long now = SystemClock.uptimeMillis();
            float dt = lastT == 0 ? 0.016f : Math.max(0.004f, Math.min(0.05f, (now - lastT) / 1000f));
            if (lastT != 0) {
                float ivx = (dragRawX - lastX) / dt, ivy = (dragRawY - lastY) / dt;
                vx += (ivx - vx) * 0.35f;
                vy += (ivy - vy) * 0.35f;
            }
            lastX = dragRawX;
            lastY = dragRawY;
            lastT = now;
            // пружина желе: тянется за скоростью, отпустили — дрожит
            float tx = Math.max(-1, Math.min(1, vx / 4000f)), ty = Math.max(-1, Math.min(1, vy / 4000f));
            jvx += ((tx - jx) * 340f - jvx * 14f) * dt;
            jvy += ((ty - jy) * 340f - jvy * 14f) * dt;
            jx += jvx * dt;
            jy += jvy * dt;
            vx *= 0.82f;
            vy *= 0.82f;

            int[] loc = new int[2];
            getLocationOnScreen(loc);
            float px = dragRawX - loc[0], py = dragRawY - loc[1];
            float x = px - grabX * Desk.this.getScaleX();
            float y = py - grabY * Desk.this.getScaleY();
            float cx = x + dragBmp.getWidth() / 2f, cy = y + dragBmp.getHeight() / 2f;
            float s = 1 + 0.08f * lift;
            // стопка отмеченных позади
            for (int i = Math.min(groupBmps.size(), 3) - 1; i >= 0; i--) {
                c.save();
                c.translate(-jx * Ui.dpf(10) * (i + 1), -jy * Ui.dpf(10) * (i + 1));
                c.rotate((i % 2 == 0 ? 7 : -7) * (i + 1) * 0.7f, cx, cy);
                c.scale(s * (0.94f - i * 0.04f), s * (0.94f - i * 0.04f), cx, cy);
                p.setAlpha(200);
                c.drawBitmap(groupBmps.get(i), x, y, p);
                c.restore();
            }
            c.save();
            // растянуть по ходу движения и сплющить поперёк
            float amt = (float) Math.min(0.22, Math.hypot(jx, jy) * 0.35);
            float ang = (float) Math.toDegrees(Math.atan2(jy, jx));
            c.scale(s, s, px, py);
            c.rotate(ang, cx, cy);
            c.scale(1 + amt, 1 - amt * 0.7f, cx, cy);
            c.rotate(-ang, cx, cy);
            sh.setMaskFilter(new android.graphics.BlurMaskFilter(Ui.dpf(14), android.graphics.BlurMaskFilter.Blur.NORMAL));
            sh.setAlpha(Math.round(110 * lift));
            c.drawRoundRect(x + Ui.dpf(6), y + Ui.dpf(12), x + dragBmp.getWidth() - Ui.dpf(6), y + dragBmp.getHeight(), Ui.dpf(18), Ui.dpf(18), sh);
            p.setAlpha(folderTarget != null ? 200 : 255);
            c.drawBitmap(dragBmp, x, y, p);
            c.restore();
            if (!group.isEmpty()) {
                // сколько всего едет
                float bx = x + dragBmp.getWidth() * s - Ui.dpf(8), by = y + Ui.dpf(4);
                p.setColor(Ui.accent());
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(bx, by, Ui.dpf(12), p);
                Paint t = Ui.text(Ui.bold, 12, 0xFFFFFFFF);
                t.setTextAlign(Paint.Align.CENTER);
                c.drawText(String.valueOf(group.size() + 1), bx, by + Ui.dpf(4.2f), t);
            }
            boolean moving = Math.abs(jx) + Math.abs(jy) + Math.abs(jvx) + Math.abs(jvy) > 0.002f;
            if (moving) postInvalidateOnAnimation();
        }
    }

    // ── страница ──────────────────────────────────────────────────────────

    final class PageView extends ViewGroup {
        final int page;
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        PageView(Context c, int page) {
            super(c);
            this.page = page;
            setWillNotDraw(false);
            setClipChildren(false);
            grid.setStyle(Paint.Style.STROKE);
            grid.setStrokeWidth(Ui.dpf(1.2f));
        }

        RectF rectFor(Layout.Item it) {
            RectF o = new RectF();
            o.set(padX() + it.x * cellW(), it.y * cellH(), padX() + (it.x + it.w) * cellW(), (it.y + it.h) * cellH());
            return o;
        }

        void invalidateItems() {
            for (int i = 0; i < getChildCount(); i++) getChildAt(i).invalidate();
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
            setMeasuredDimension(w, h);
            for (int i = 0; i < getChildCount(); i++) {
                ItemView v = (ItemView) getChildAt(i);
                RectF rr = rectFor(v.item);
                v.measure(MeasureSpec.makeMeasureSpec(Math.round(rr.width()), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(Math.round(rr.height()), MeasureSpec.EXACTLY));
            }
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int rr, int b) {
            for (int i = 0; i < getChildCount(); i++) {
                ItemView v = (ItemView) getChildAt(i);
                RectF q = rectFor(v.item);
                v.layout(Math.round(q.left), Math.round(q.top), Math.round(q.right), Math.round(q.bottom));
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            if (!editing) return;
            // сетка клеток пунктиром
            grid.setPathEffect(new DashPathEffect(new float[]{Ui.dpf(4), Ui.dpf(5)}, 0));
            grid.setColor(0x33FFFFFF);
            for (int y = 0; y < Layout.rows; y++)
                for (int x = 0; x < Layout.cols(); x++) {
                    r.set(padX() + x * cellW(), y * cellH(), padX() + (x + 1) * cellW(), (y + 1) * cellH());
                    r.inset(Ui.dpf(5), Ui.dpf(5));
                    c.drawRoundRect(r, Ui.dpf(12), Ui.dpf(12), grid);
                }
            // куда встанет то, что тащат
            if (dragItem != null && page == Math.round(pos) && folderTarget == null && !dockHover && targetX >= 0) {
                r.set(padX() + targetX * cellW(), targetY * cellH(), padX() + (targetX + dragItem.w) * cellW(), (targetY + dragItem.h) * cellH());
                r.inset(Ui.dpf(4), Ui.dpf(4));
                Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
                f.setColor(targetOk ? Ui.alpha(Ui.accentLight(), 0.28f) : 0x22FF5050);
                c.drawRoundRect(r, Ui.dpf(16), Ui.dpf(16), f);
                grid.setPathEffect(null);
                grid.setColor(targetOk ? Ui.accentLight() : 0x88FF6B6B);
                c.drawRoundRect(r, Ui.dpf(16), Ui.dpf(16), grid);
            }
        }
    }

    // ── вещь в клетках ────────────────────────────────────────────────────

    final class ItemView extends FrameLayout {
        final Layout.Item item;
        private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint ui = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float x0, y0, rawX0, rawY0;
        private boolean down, longFired, movedFar, resizing;
        private int resizeW0, resizeH0;
        private float press, pressV;
        private long pressLast;
        private final Runnable longPress = this::onLong;

        ItemView(Context c, Layout.Item item) {
            super(c);
            this.item = item;
            setWillNotDraw(false);
            setClipChildren(false);
            label.setTypeface(Ui.medium);
            label.setTextSize(Ui.dpf(12));
            label.setColor(0xFFF4F6FF);
            label.setShadowLayer(Ui.dpf(3), 0, Ui.dpf(1), 0x88000000);
            label.setTextAlign(Paint.Align.CENTER);
            if (Layout.WIDGET.equals(item.type) && "app".equals(item.kind)) {
                View hv = Widgets.view(c, item);
                if (hv != null) {
                    LayoutParams lp = new LayoutParams(-1, -1);
                    int m = Ui.dp(4);
                    lp.setMargins(m, m, m, m);
                    addView(hv, lp);
                }
            }
        }

        /** Где значок приложения (и папки 1×1) внутри клетки. */
        RectF iconRect(RectF out) {
            // крупные значки — не шире клетки
            float s = Math.min(Apps.iconPx, Math.max(Ui.dpf(24), getWidth() - Ui.dpf(6)));
            float top = Math.max(Ui.dpf(2), (getHeight() - s - (Prefs.labels ? Ui.dpf(22) : 0)) / 2f);
            if (item.h > 1 && !item.type.equals(Layout.APP)) top = Ui.dpf(4);
            out.set(getWidth() / 2f - s / 2, top, getWidth() / 2f + s / 2, top + s);
            return out;
        }

        RectF screenRect(RectF local) {
            return Ui.toWindow(this, local);
        }

        /** Для снимка при перетаскивании: без нажатия и рамок правки. */
        private boolean snapshot;

        void drawForDrag(Canvas c) {
            float p = press;
            Layout.Item s = selected;
            press = 0;
            selected = null;
            snapshot = true;
            draw(c);
            snapshot = false;
            selected = s;
            press = p;
        }

        @Override
        protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.032f, (now - pressLast) / 1000f);
            pressLast = now;
            float target = down && !longFired && !movedFar && !editing ? 1f : 0f;
            pressV += ((target - press) * (target > 0 ? 900f : 420f) - pressV * (target > 0 ? 60f : 22f)) * dt;
            press += pressV * dt;
            boolean anim = Math.abs(target - press) > 0.002f || Math.abs(pressV) > 0.01f;
            if (!anim) { press = target; pressV = 0; }
            float sc = 1 - 0.1f * press;
            c.save();
            c.scale(sc, sc, getWidth() / 2f, getHeight() / 2f);
            switch (item.type) {
                case Layout.APP: {
                    Apps.App a = Apps.byKey.get(item.key);
                    if (a == null) break;
                    iconRect(r);
                    if (a.bmp != null) c.drawBitmap(a.bmp, null, r, bmp);
                    drawLabel(c, a.label, r.bottom);
                    break;
                }
                case Layout.FOLDER: {
                    RectF body = folderBody(r);
                    Folders.draw(c, item, body, this);
                    drawLabel(c, item.name, body.bottom);
                    break;
                }
                default:
                    if (!"app".equals(item.kind)) {
                        r.set(0, 0, getWidth(), getHeight());
                        r.inset(Ui.dpf(5), Ui.dpf(5));
                        VWidgets.draw(c, item, r, this);
                    }
            }
            c.restore();
            if (folderTarget == item) {
                RectF ring = item.type.equals(Layout.APP) ? iconRect(new RectF()) : folderBody(new RectF());
                ring.inset(-Ui.dpf(6), -Ui.dpf(6));
                ui.setStyle(Paint.Style.FILL);
                ui.setColor(Ui.alpha(Ui.accentLight(), 0.25f));
                c.drawRoundRect(ring, Ui.dpf(20), Ui.dpf(20), ui);
            }
            if (anim) postInvalidateOnAnimation();
        }

        RectF folderBody(RectF out) {
            if (item.w == 1 && item.h == 1) return iconRect(out);
            out.set(Ui.dpf(6), Ui.dpf(6), getWidth() - Ui.dpf(6), getHeight() - (Prefs.labels ? Ui.dpf(24) : Ui.dpf(6)));
            return out;
        }

        private void drawLabel(Canvas c, String s, float top) {
            if (!Prefs.labels || s == null) return;
            CharSequence t = TextUtils.ellipsize(s, label, getWidth() - Ui.dpf(6), TextUtils.TruncateAt.END);
            c.drawText(t, 0, t.length(), getWidth() / 2f, top + Ui.dpf(16), label);
        }

        @Override
        protected void dispatchDraw(Canvas c) {
            super.dispatchDraw(c);
            if (editing && !snapshot && item.type.equals(Layout.APP)) {
                // кружок отметки у значка
                RectF ir = iconRect(new RectF());
                float bx = ir.right - Ui.dpf(2), by = ir.top + Ui.dpf(2), br = Ui.dpf(10);
                boolean on = picked.contains(item);
                ui.setStyle(Paint.Style.FILL);
                ui.setColor(on ? Ui.accent() : 0x66000000);
                c.drawCircle(bx, by, br, ui);
                ui.setStyle(Paint.Style.STROKE);
                ui.setStrokeWidth(Ui.dpf(1.6f));
                ui.setColor(0xFFFFFFFF);
                if (on) {
                    android.graphics.Path ck = new android.graphics.Path();
                    ck.moveTo(bx - br * 0.45f, by);
                    ck.lineTo(bx - br * 0.1f, by + br * 0.35f);
                    ck.lineTo(bx + br * 0.45f, by - br * 0.35f);
                    ui.setStrokeCap(Paint.Cap.ROUND);
                    c.drawPath(ck, ui);
                } else c.drawCircle(bx, by, br - Ui.dpf(1), ui);
                ui.setStyle(Paint.Style.FILL);
            }
            if (!editing || selected != item || item.type.equals(Layout.APP)) return;
            // рамка, крестик и уголок размера
            r.set(Ui.dpf(3), Ui.dpf(3), getWidth() - Ui.dpf(3), getHeight() - Ui.dpf(3));
            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(Ui.dpf(2));
            ui.setColor(Ui.accentLight());
            c.drawRoundRect(r, Ui.dpf(18), Ui.dpf(18), ui);
            ui.setStyle(Paint.Style.FILL);
            ui.setColor(0xF0222228);
            c.drawCircle(Ui.dpf(12), Ui.dpf(12), Ui.dpf(11), ui);
            Glyph.close(c, Ui.dpf(12), Ui.dpf(12), Ui.dpf(13), 0xFFFFFFFF);
            ui.setColor(Ui.accent());
            float hx = getWidth() - Ui.dpf(12), hy = getHeight() - Ui.dpf(12);
            c.drawCircle(hx, hy, Ui.dpf(11), ui);
            Glyph.resize(c, hx, hy, Ui.dpf(14), 0xFFFFFFFF);
        }

        // касания: нажатие, удержание (меню), перетаскивание, уголок размера

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            // у виджета приложения касания идут ему самому; удержание ловим здесь
            if (getChildCount() > 0) track(e);
            return longFired || movedFar && editing || resizing;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            track(e);
            return true;
        }

        private long lastT = -1;
        private int lastA = -1;

        private void track(MotionEvent e) {
            // одно и то же событие может прийти и в перехват, и сюда
            if (e.getEventTime() == lastT && e.getActionMasked() == lastA && e.getActionMasked() != MotionEvent.ACTION_MOVE) return;
            lastT = e.getEventTime();
            lastA = e.getActionMasked();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down = true;
                    longFired = movedFar = resizing = false;
                    x0 = e.getX();
                    y0 = e.getY();
                    rawX0 = e.getRawX();
                    rawY0 = e.getRawY();
                    pressLast = SystemClock.uptimeMillis();
                    if (editing && selected == item && !item.type.equals(Layout.APP)) {
                        if (Math.hypot(x0 - (getWidth() - Ui.dpf(12)), y0 - (getHeight() - Ui.dpf(12))) < Ui.dpf(26)) {
                            resizing = true;
                            resizeW0 = item.w;
                            resizeH0 = item.h;
                            getParent().requestDisallowInterceptTouchEvent(true);
                            break;
                        }
                    }
                    if (!editing) postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    else getParent().requestDisallowInterceptTouchEvent(true);
                    invalidate();
                    break;
                case MotionEvent.ACTION_MOVE: {
                    if (dragItem == item) {
                        dragMove(e.getRawX(), e.getRawY());
                        break;
                    }
                    float dx = e.getRawX() - rawX0, dy = e.getRawY() - rawY0;
                    if (resizing) {
                        resize(dx, dy);
                        break;
                    }
                    if (!movedFar && Math.hypot(dx, dy) > slop) {
                        movedFar = true;
                        removeCallbacks(longPress);
                        invalidate();
                        // после удержания или в правке — тащим
                        if (longFired || editing) Desk.this.startDrag(this, e.getRawX(), e.getRawY());
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                    removeCallbacks(longPress);
                    down = false;
                    if (dragItem == item) dragEnd();
                    else if (resizing) endResize();
                    else if (!movedFar && !longFired) tap(e.getX(), e.getY());
                    resizing = false;
                    invalidate();
                    break;
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(longPress);
                    down = false;
                    if (dragItem == item) dragEnd();
                    else if (resizing) endResize();
                    resizing = false;
                    invalidate();
                    break;
            }
        }

        private void onLong() {
            if (!down || movedFar || longFired) return;
            longFired = true;
            getParent().requestDisallowInterceptTouchEvent(true);
            Feel.buzz(this, Feel.PRESS);
            invalidate();
            if (host != null) host.itemMenu(item, this, rawX0, rawY0);
        }

        private void tap(float x, float y) {
            if (editing) {
                // крестик у выбранного виджета или папки — убрать
                if (selected == item && !item.type.equals(Layout.APP) && Math.hypot(x - Ui.dpf(12), y - Ui.dpf(12)) < Ui.dpf(22)) {
                    remove(item);
                    return;
                }
                if (Layout.APP.equals(item.type)) {
                    // значки отмечаются — потом их можно тащить вместе
                    if (!picked.remove(item)) picked.add(item);
                    Feel.buzz(this, Feel.TICK);
                    invalidateAll();
                    return;
                }
                select(item);
                if (Layout.WIDGET.equals(item.type) && host != null) host.widgetVariants(item);
                return;
            }
            if (host == null) return;
            switch (item.type) {
                case Layout.APP: {
                    Apps.App a = Apps.byKey.get(item.key);
                    if (a != null) host.launch(a, screenRect(iconRect(new RectF())));
                    break;
                }
                case Layout.FOLDER: {
                    // у «Крупной» значки нажимаются прямо на столе
                    String k = Folders.hit(item, folderBody(new RectF()), x, y);
                    Apps.App a = k == null ? null : Apps.byKey.get(k);
                    if (a != null) host.launch(a, screenRect(Folders.slotRect(item, folderBody(new RectF()), k)));
                    else host.openFolder(item, this);
                    break;
                }
                default:
                    if (!"app".equals(item.kind)) host.widgetTap(item, this, x, y);
            }
        }

        private void resize(float dx, float dy) {
            float sc = Desk.this.getScaleX();
            int nw = Math.max(1, Math.round(resizeW0 + dx / sc / cellW()));
            int nh = Math.max(1, Math.round(resizeH0 + dy / sc / cellH()));
            int[] lim = Widgets.limits(getContext(), item);
            nw = Math.max(lim[0], Math.min(lim[2], nw));
            nh = Math.max(lim[1], Math.min(lim[3], nh));
            if ((nw != item.w || nh != item.h) && Layout.free(item.page, item.x, item.y, nw, nh, item)) {
                item.w = nw;
                item.h = nh;
                Feel.buzz(this, Feel.TICK);
                getParent().requestLayout();
                invalidate();
            }
        }

        private void endResize() {
            Layout.save();
            Widgets.sizeChanged(getContext(), item, Math.round(item.w * cellW()), Math.round(item.h * cellH()));
            if (host != null) host.layoutChanged();
        }
    }

    /** Убрать виджет или разобрать папку (значки — в свободные клетки). */
    void remove(Layout.Item it) {
        Layout.items.remove(it);
        if (it.type.equals(Layout.WIDGET)) Widgets.delete(it);
        else if (it.type.equals(Layout.FOLDER)) for (String k : it.apps) Layout.placeApp(k, it.page);
        if (selected == it) selected = null;
        Feel.sound(getContext(), "drop");
        Layout.sync();
        Layout.save();
        rebuild();
        if (host != null) host.layoutChanged();
    }

    /** Новая вещь (виджет) — в первую свободную клетку с текущей страницы. */
    void add(Layout.Item it) {
        int[] f = Layout.firstFree(Math.round(pos), it.w, it.h);
        it.page = f[0];
        it.x = f[1];
        it.y = f[2];
        Layout.items.add(it);
        Layout.save();
        if (!editing) setEditing(true);
        else rebuild();
        selected = it;
        if (f[0] != Math.round(pos)) Anim.spring(pos, f[0], 0, 260, 0.9f, this::setPos, null);
        invalidateAll();
        if (host != null) host.layoutChanged();
    }

    /** Где вещь на экране (после пересборки); null — её вида нет. */
    RectF itemOnScreen(Layout.Item it) {
        ItemView v = viewOf(it);
        if (v == null || v.getWidth() == 0) return null;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return new RectF(loc[0], loc[1], loc[0] + v.getWidth() * getScaleX(), loc[1] + v.getHeight() * getScaleY());
    }

    /** Рамка цифр часов на текущем столе (на экране); null — часов здесь нет. */
    RectF clockOnScreen() {
        int i = Math.round(pos);
        if (i < 0 || i >= views.size()) return null;
        PageView pv = views.get(i);
        for (int k = 0; k < pv.getChildCount(); k++) {
            View v = pv.getChildAt(k);
            if (!(v instanceof ItemView)) continue;
            Layout.Item it = ((ItemView) v).item;
            if (!Layout.WIDGET.equals(it.type) || !"clock".equals(it.kind)) continue;
            RectF r = new RectF(0, 0, v.getWidth(), v.getHeight());
            r.inset(Ui.dpf(5), Ui.dpf(5));
            RectF b = VWidgets.clockBox(it, r);
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            float sc = getScaleX();
            return new RectF(loc[0] + b.left * sc, loc[1] + b.top * sc, loc[0] + b.right * sc, loc[1] + b.bottom * sc);
        }
        return null;
    }

    /** Точки страниц под значками. */
    static final class Dots extends View {
        private final Desk desk;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Dots(Context c, Desk desk) {
            super(c);
            this.desk = desk;
        }

        @Override
        protected void onDraw(Canvas c) {
            int n = desk.count();
            if (n <= 1) return;
            float r = Ui.dpf(3.5f), step = Ui.dpf(14);
            float x0 = getWidth() / 2f - (n - 1) * step / 2f, cy = getHeight() / 2f;
            for (int i = 0; i < n; i++) {
                float k = Math.max(0, 1 - Math.abs(desk.pos - i));
                p.setColor(Ui.mix(0x66FFFFFF, 0xFFFFFFFF, k));
                p.setShadowLayer(Ui.dpf(2), 0, 0, 0x55000000);
                // в правке последняя (пустая) страница — плюсиком
                if (desk.editing && i == n - 1) {
                    p.setStrokeWidth(Ui.dpf(1.6f));
                    c.drawLine(x0 + i * step - r, cy, x0 + i * step + r, cy, p);
                    c.drawLine(x0 + i * step, cy - r, x0 + i * step, cy + r, p);
                } else c.drawCircle(x0 + i * step, cy, r, p);
            }
        }
    }
}
