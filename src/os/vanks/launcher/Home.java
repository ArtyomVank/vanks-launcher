package os.vanks.launcher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.LauncherApps;
import android.graphics.drawable.Drawable;
import android.widget.TextView;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.RectF;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Vanks Launcher — проверочный лаунчер концепта Vanks OS Mobile.
 * Жесты как на iPhone: вниз от правого верха — пункт управления, вниз от левого
 * верха — уведомления, вниз посередине — поиск, влево — библиотека приложений.
 * Долгое нажатие на пустом месте открывает «Настройки Vanks».
 */
public final class Home extends Activity {
    private Root root;
    private WallView wall;
    private WallView lockWall;   // свои обои экрана блокировки (если выбраны)
    private LinearLayout homeLayer;
    private Desk desk;
    private Desk.Dots dots;
    private LinearLayout editBar;
    private DropMenu menu;
    private Folders.Panel folder;
    private Widgets.Picker picker;
    private Widgets.Variants variants;
    private AppGrid dockGrid;
    private Glass dock, searchPill;
    private View swipeHint;
    Control control;
    Search search;
    private LockView lock;

    private boolean locked;
    private boolean authing;
    private boolean autoPrompted;
    private boolean askedWrite;
    private boolean returning;   // открыли приложение — при возврате стол «приземляется»
    private int seenRev = -1;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private CameraManager cameras;
    private String torchId;
    private CameraManager.TorchCallback torchCb;
    private final ArrayList<BroadcastReceiver> receivers = new ArrayList<>();

    final AppGrid.Listener appListener = new AppGrid.Listener() {
        @Override
        public void tap(Apps.App a, AppGrid g, RectF r) {
            launch(a, r);
        }

        @Override
        public void longPress(Apps.App a, AppGrid g, RectF r) {
            if (a == null) {
                if (g != search.apps && g != search.hiddenGrid) openSettings(null);
                return;
            }
            appMenu(a, null, r, r.centerX(), r.centerY());
        }
    };

    /** Стол спрашивает у главного экрана то, что вне его: запуск, меню, док. */
    private final Desk.Host deskHost = new Desk.Host() {
        @Override
        public void launch(Apps.App a, RectF r) {
            Home.this.launch(a, r);
        }

        @Override
        public void itemMenu(Layout.Item it, View v, float rawX, float rawY) {
            Home.this.itemMenu(it, v, rawX, rawY);
        }

        @Override
        public void closeMenus() {
            if (menu != null) menu.dismiss();
        }

        @Override
        public void openFolder(Layout.Item it, View v) {
            Home.this.openFolder(it, v);
        }

        @Override
        public void widgetTap(Layout.Item it, View v, float x, float y) {
            Home.this.widgetTap(it, v, x, y);
        }

        @Override
        public void editChanged(boolean editing) {
            Home.this.editChanged(editing);
        }

        @Override
        public boolean overDock(float rawX, float rawY) {
            // дока нет (пустой) — кладём в полосу внизу экрана
            if (dock.getVisibility() != View.VISIBLE) return rawY > root.getHeight() - Ui.dpf(110);
            int[] l = new int[2];
            dock.getLocationOnScreen(l);
            float m = Ui.dpf(14);
            return rawX > l[0] - m && rawX < l[0] + dock.getWidth() + m && rawY > l[1] - m && rawY < l[1] + dock.getHeight() + m;
        }

        @Override
        public boolean dropToDock(Layout.Item it) {
            if (Prefs.dock.size() >= 5) {
                toast("В доке не больше пяти значков");
                return false;
            }
            Prefs.dock.add(it.key);
            Feel.sound(Home.this, "snap-in");
            ui.post(Home.this::refreshDock);
            return true;
        }

        @Override
        public void layoutChanged() {
            seenRev = Prefs.rev;
            search.reload();
        }

        @Override
        public void wave(float rawX, float rawY) {
            Home.this.wave(rawX, rawY);
        }

        @Override
        public void widgetVariants(Layout.Item it) {
            openVariants(it);
        }
    };

    // ── жизненный цикл ────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        alive = this;
        Prefs.load(this);
        Hz.apply(this);
        Ui.init(this);
        Feel.init(this);
        Apps.load(this);
        if (Prefs.firstRun || (Prefs.home.isEmpty() && Prefs.dock.isEmpty())) Apps.pickDefaults(this);
        Apps.renderIcons(this, iconPx());
        Layout.load(this);
        Widgets.init(this);
        VWidgets.onTimer = () -> desk.invalidateWidgets();
        ui.postDelayed(() -> Widgets.Picker.preload(this), 3000);

        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().setNavigationBarContrastEnforced(false);
            getWindow().setStatusBarContrastEnforced(false);
        }

        build();
        setContentView(root);
        seenRev = Prefs.rev;
        refreshApps();
        registerAll();
        setupTorch();
        EdgeService.sync(this);
        LockService.sync(this);
        Nav.keep(this);
        SysWall.sync(this);

        // свой экран блокировки основной — у стола своего входа нет
        locked = !Prefs.lockMain && Prefs.fingerprint && Bio.available(this);
        lock.setVisibility(locked ? View.VISIBLE : View.GONE);
        // под экраном входа стола не видно: он проступит при входе
        homeLayer.setAlpha(locked ? 0 : 1);
        wall.reload();
        if (locked) {
            wall.lockNow();
            showLockWall();
        } else {
            wall.playIntro();
            // сверху свой экран блокировки — появление сыграет после входа
            if (Prefs.lockMain && LockActivity.locked) wall.lockNow();
            desk.post(desk::playEnter);
        }

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Widgets.start();
        shown = this;
    }

    /** Стол, даже если его сейчас не видно (под приложением). */
    private static Home alive;

    /**
     * Встал свой экран блокировки: обои стола — в позу входа, чтобы после
     * входа появление живых обоев сыграло на глазах, а не тайком под замком.
     */
    static void lockPose() {
        Home h = alive;
        if (h == null || h.locked || h.wall == null) return;
        h.wall.lockNow();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Widgets.stop();
        if (shown == this) shown = null;
    }

    /** Стол, который сейчас виден (в том числе под прозрачным экраном блокировки). */
    private static Home shown;

    /**
     * Свой экран блокировки уходит, а под ним — стол: стол проступает так же,
     * как после входа здесь. Вернёт рамку часов стола на экране, куда лететь
     * часам блокировки; null — стола не видно или часов на этом столе нет
     * (тогда часы блокировки уходят вверх, как на ПК).
     */
    static RectF lockLanding() {
        Home h = shown;
        if (h == null || h.locked) return null;
        h.homeLayer.animate().cancel();
        h.homeLayer.setScaleX(1);
        h.homeLayer.setScaleY(1);
        RectF to = Prefs.clockToHome ? h.desk.clockOnScreen() : null;
        if (to != null) {
            VWidgets.hideClock = true;
            h.desk.invalidateAll();
            h.root.postDelayed(() -> {
                VWidgets.hideClock = false;
                h.desk.invalidateAll();
            }, (long) (700 * Prefs.k()));
        }
        h.homeLayer.setAlpha(0);
        h.homeLayer.setScaleX(1.14f);
        h.homeLayer.setScaleY(1.14f);
        h.homeLayer.animate().alpha(1).setStartDelay(240).setDuration(560).setInterpolator(Anim.STD).start();
        // обои доигрывают появление под тающим экраном блокировки (стол ещё
        // на паузе — окно блокировки сверху, поэтому снять паузу самим)
        h.wall.setPaused(false);
        if (!h.wall.lockPose()) h.wall.lockNow();
        h.wall.unlock();
        h.root.postDelayed(() -> {
            Anim.spring(h.homeLayer.getScaleX(), 1f, 0, 160, 0.78f, k -> {
                h.homeLayer.setScaleX(k);
                h.homeLayer.setScaleY(k);
            }, null);
            h.desk.playEnter();
            h.dockGrid.playEnter();
        }, 220);
        return to;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (!Widgets.onResult(this, req, res)) super.onActivityResult(req, res, data);
    }

    /** Стол на экране (между onResume и onPause). */
    private boolean resumedNow;

    @Override
    protected void onResume() {
        super.onResume();
        resumedNow = true;
        // ушли в «Недавние» жестом со стола — вернуться не вглубь
        if (pulled) {
            pulled = false;
            setDepth(0, 200, 0.86f);
        }
        // в «Настройках Vanks» что-то поменяли — применить
        if (Prefs.rev != seenRev) {
            seenRev = Prefs.rev;
            Ui.loadWall(this);
            if (wall.stale()) {
                wall.reload();
                wall.playIntro();
            }
            wall.invalidate();
            if (locked) showLockWall();
            lock.invalidate();
            swipeHint.setVisibility(Prefs.swipeHintSeen ? View.GONE : View.VISIBLE);
            control.tiles.load(Prefs.tiles);
            applyLook(true);
            refreshApps();
            EdgeService.sync(this);
            LockService.sync(this);
            Hz.apply(this);
        }
        // «домой» с экрана блокировки — он снова сверху
        if (Prefs.lockMain && LockActivity.locked) LockActivity.show(this);
        // Vanks OS: первый запуск — сначала мастер (после входа с экрана блокировки)
        else if (Updater.osVersion() != null && !Prefs.setupDone)
            startActivity(new Intent(this, SetupActivity.class));
        readState();
        desk.invalidateAll();
        wall.setPaused(false);
        Updater.maybeCheck(this);
        // жесты Vanks включены, а служба нет — включить её самим, если есть право
        // (Vanks OS даёт его системному лаунчеру, иначе — adb pm grant); без
        // права не дёргаем: страницу служб открывает переключатель в настройках
        if (Prefs.navGestures && !VanksAccess.running() && Nav.canWrite(this)) VanksAccess.turnOn(this);
        // Vanks OS: шторка одна — Vanks, ей нужны уведомления
        if (Updater.osVersion() != null) VanksNotes.enable(this);
        // вошли поверх другого приложения, а стол открыли сейчас — появление обоев здесь
        if (!locked && wall.lockPose() && !(Prefs.lockMain && LockActivity.locked)) wall.unlock();
        if (returning) {
            returning = false;
            if (!locked) land();
            else {
                if (launchCard != null) {
                    root.removeView(launchCard);
                    launchCard = null;
                }
                homeLayer.animate().cancel();
                setDepth(0, 400, 1f);
                homeLayer.setScaleX(1);
                homeLayer.setScaleY(1);
            }
        }
        // окно отпечатка само — один раз за блокировку; дальше по касанию
        if (locked && !autoPrompted) {
            autoPrompted = true;
            ui.postDelayed(this::authLock, 350);
        }
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    /** Раз в секунду, пока виден стол: таймер, скорость сети, память, музыка. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            VWidgets.sample(Home.this);
            if (VWidgets.live()) desk.invalidateWidgets();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onPause() {
        super.onPause();
        resumedNow = false;
        ui.removeCallbacksAndMessages(null);
        wall.setPaused(true);
        // тихое чтение пальца в фоне не работает — при возврате начать заново
        if (authing && FingerSensor.silent(this)) {
            FingerSensor.cancel();
            authing = false;
            autoPrompted = false;
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        boolean here = resumedNow;
        // кнопка «домой», когда лаунчер уже открыт: всё закрыть
        boolean open = control.isOpen() || search.isOpen() || menu != null || folder != null || picker != null || desk.editing;
        if (menu != null) menu.dismiss();
        if (folder != null) folder.close();
        if (picker != null) picker.close();
        if (desk.editing) desk.setEditing(false);
        if (control.isOpen()) closeControl();
        if (search.isOpen()) search.animateTo(0, null);
        // а если ничего не открыто и стол уже был на экране — на первый стол, как на iPhone.
        // Из приложения «Домой» приходит так же — тогда остаёмся на том столе, где были
        if (here && !open && !locked && desk.pos > 0) Anim.spring(desk.pos, 0, 0, 220, 0.9f, desk::setPos, null);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        back();
    }

    private void back() {
        if (locked) return;
        if (menu != null) menu.dismiss();
        else if (variants != null) variants.close();
        else if (folder != null) folder.close();
        else if (picker != null) picker.close();
        else if (desk.editing) desk.setEditing(false);
        else if (control.tiles.edit) control.tiles.setEdit(false);
        else if (control.isOpen()) closeControl();
        else if (search.hiddenOpen) search.setHiddenOpen(false);
        else if (search.isOpen()) search.animateTo(0, null);
    }

    @Override
    protected void onDestroy() {
        if (alive == this) alive = null;
        for (BroadcastReceiver r : receivers) {
            try { unregisterReceiver(r); } catch (Exception ignored) {}
        }
        if (cameras != null && torchCb != null) cameras.unregisterTorchCallback(torchCb);
        super.onDestroy();
    }

    // ── построение ────────────────────────────────────────────────────────

    private int iconPx() {
        float base = Prefs.columns >= 5 ? 50 : 56;
        return Math.round(Ui.dpf(base) * Prefs.iconScale);
    }

    private void build() {
        root = new Root(this);
        wall = new WallView(this);
        root.addView(wall, new FrameLayout.LayoutParams(-1, -1));

        homeLayer = new LinearLayout(this);
        homeLayer.setOrientation(LinearLayout.VERTICAL);
        // тень полок (док, «Поиск») выходит за их края — не обрезать её прямоугольником
        homeLayer.setClipChildren(false);
        homeLayer.setClipToPadding(false);
        desk = new Desk(this);
        desk.host = deskHost;
        homeLayer.addView(desk, new LinearLayout.LayoutParams(-1, 0, 1));
        dots = new Desk.Dots(this, desk);
        desk.onScroll = dots::invalidate;
        homeLayer.addView(dots, new LinearLayout.LayoutParams(-1, Ui.dp(16)));

        swipeHint = new View(this) {
            @Override
            protected void onDraw(Canvas c) {
                float cx = getWidth() / 2f;
                android.graphics.Paint p = Ui.text(Ui.medium, 12, 0xCCFFFFFF);
                p.setTextAlign(android.graphics.Paint.Align.CENTER);
                p.setShadowLayer(Ui.dpf(4), 0, 0, 0x88000000);
                c.drawText("Вниз справа сверху — пункт управления · слева — уведомления", cx, Ui.dpf(14), p);
                c.drawText("Вниз посередине — поиск · влево и вправо — страницы", cx, Ui.dpf(32), p);
            }
        };
        swipeHint.setVisibility(Prefs.swipeHintSeen ? View.GONE : View.VISIBLE);
        homeLayer.addView(swipeHint, new LinearLayout.LayoutParams(-1, Ui.dp(40)));

        // «Поиск» над доком — то же стекло, что у дока (от темы не зависит)
        searchPill = new Glass(this, 20, true);
        View sIcon = Views.drawn(this, (c, w, h) -> {
            android.graphics.Paint p = Ui.text(Ui.medium, 14, 0xF2FFFFFF);
            p.setShadowLayer(Ui.dpf(3), 0, Ui.dpf(1), 0x40000000);
            float tw = p.measureText("Поиск");
            float x0 = w / 2 - (tw + Ui.dpf(26)) / 2;
            Glyph.search(c, x0 + Ui.dpf(9), h / 2, Ui.dpf(17), 0xF2FFFFFF);
            c.drawText("Поиск", x0 + Ui.dpf(26), h / 2 + Ui.dpf(5), p);
        });
        searchPill.addView(sIcon, new FrameLayout.LayoutParams(-1, -1));
        searchPill.setOnClickListener(v -> openSearch());
        LinearLayout.LayoutParams spp = new LinearLayout.LayoutParams(Ui.dp(132), Ui.dp(38));
        spp.gravity = Gravity.CENTER_HORIZONTAL;
        spp.topMargin = Ui.dp(6);
        homeLayer.addView(searchPill, spp);

        // док — стеклянная полка по ширине значков
        dock = new Glass(this, 30, true);
        dockGrid = new AppGrid(this);
        dockGrid.labels = false;
        dockGrid.minRows = 1;
        // сверху 6 dp: значок (в сетке он на 4 dp ниже верха) встаёт ровно посередине
        dockGrid.setPadding(0, Ui.dp(6), 0, 0);
        dockGrid.listener = appListener;
        dock.addView(dockGrid, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-2, -2);
        dp.gravity = Gravity.CENTER_HORIZONTAL;
        dp.topMargin = Ui.dp(12);
        homeLayer.addView(dock, dp);
        root.addView(homeLayer, new FrameLayout.LayoutParams(-1, -1));

        // правка стола: сверху «Виджеты», «Обои», «Готово»
        editBar = new LinearLayout(this);
        editBar.setGravity(Gravity.CENTER_VERTICAL);
        editBar.setPadding(Ui.dp(16), 0, Ui.dp(16), 0);
        editBar.addView(editButton("+  Виджеты", false, v -> openPicker()));
        editBar.addView(editButton("Обои", false, v -> openSettings("lockwall")));
        View gap = new View(this);
        editBar.addView(gap, new LinearLayout.LayoutParams(0, 1, 1));
        editBar.addView(editButton("Готово", true, v -> desk.setEditing(false)));
        editBar.setVisibility(View.GONE);
        root.addView(editBar, new FrameLayout.LayoutParams(-1, Ui.dp(52), Gravity.TOP));
        root.addView(desk.dragLayer, new FrameLayout.LayoutParams(-1, -1));

        search = new Search(this);
        root.addView(search, new FrameLayout.LayoutParams(-1, -1));

        control = new Control(this);
        root.addView(control, new FrameLayout.LayoutParams(-1, -1));

        lockWall = new WallView(this, true);
        lockWall.setVisibility(View.GONE);
        root.addView(lockWall, new FrameLayout.LayoutParams(-1, -1));

        lock = new LockView(this);
        lock.listener = this::authLock;
        root.addView(lock, new FrameLayout.LayoutParams(-1, -1));

        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != lastRootW) {
                lastRootW = r - l;
                root.post(this::layoutDock);
            }
        });
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets s = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = s.top;
                bottom = s.bottom;
            } else {
                top = in.getSystemWindowInsetTop();
                bottom = in.getSystemWindowInsetBottom();
            }
            homeLayer.setPadding(0, top, 0, bottom + Ui.dp(10));
            FrameLayout.LayoutParams ep = (FrameLayout.LayoutParams) editBar.getLayoutParams();
            ep.topMargin = top;
            editBar.setLayoutParams(ep);
            control.setInsets(top, bottom);
            search.setInsets(top, bottom);
            lock.insetTop = top;
            lock.insetBottom = bottom;
            return in;
        });
    }

    /**
     * На рабочих столах — все приложения (Layout): новые встают в свободные
     * клетки, удалённые и скрытые убираются, в том числе из папок.
     */
    void refreshApps() {
        if (Layout.sync()) {
            Layout.save();
            seenRev = Prefs.rev;
        }
        Widgets.prune();
        desk.rebuild();
        refreshDock();
        search.reload();
    }

    private void refreshDock() {
        List<Apps.App> inDock = Apps.resolve(Prefs.dock);
        dockGrid.cols = Math.max(1, inDock.size());
        dockGrid.setApps(inDock);
        layoutDock();
    }

    /**
     * Размер дока: ячейка на значок плюс поля; без значков дока нет. Пока
     * ширина экрана неизвестна (запуск, смена настроек), док меряется позже —
     * иначе крупные значки делали полку шире экрана и крайние обрезались.
     * Не влезают — весь ряд ужимается, чтобы встали все.
     */
    private void layoutDock() {
        int n = dockGrid.apps.size();
        dock.setVisibility(n == 0 ? View.GONE : View.VISIBLE);
        if (root.getWidth() == 0) {
            root.post(this::layoutDock);
            return;
        }
        float cell = Apps.iconPx + Ui.dpf(30);
        int full = root.getWidth() - Ui.dp(28);
        int need = Math.round(n * cell + Ui.dpf(20));
        float s = need > full ? full / (float) need : 1f;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) dock.getLayoutParams();
        lp.width = Math.min(full, need);
        lp.height = Math.round((Apps.iconPx + Ui.dpf(30)) * s);
        dock.setLayoutParams(lp);
        // ряд значков — в настоящую ширину, ужатый посередине
        FrameLayout.LayoutParams gp = (FrameLayout.LayoutParams) dockGrid.getLayoutParams();
        gp.width = s < 1f ? need : -1;
        gp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
        dockGrid.setLayoutParams(gp);
        dockGrid.setPivotX(need / 2f);
        dockGrid.setPivotY(0);
        dockGrid.setScaleX(s);
        dockGrid.setScaleY(s);
        dockGrid.setPadding(Ui.dp(10), Ui.dp(8), Ui.dp(10), 0);
    }

    private int lastRootW;

    /** После «Настроек»: перерисовать значки (форма, тон, цвет, размер). */
    void applyLook(boolean render) {
        if (render) Apps.renderIcons(this, iconPx());
        else Apps.iconPx = iconPx();
        for (AppGrid g : new AppGrid[]{dockGrid, search.apps, search.hiddenGrid}) {
            g.requestLayout();
            g.invalidate();
        }
        desk.rebuild();
        layoutDock();
        dock.invalidate();
        searchPill.invalidate();
        control.refreshClock();
        control.tiles.invalidate();
        control.tiles.host.editChanged(control.tiles.edit);
    }

    private void hintSeen() {
        if (Prefs.swipeHintSeen) return;
        Prefs.swipeHintSeen = true;
        Prefs.save();
        seenRev = Prefs.rev;
        swipeHint.setVisibility(View.GONE);
    }

    // ── пункт управления, библиотека, поиск ───────────────────────────────

    void openControl() {
        Feel.sound(this, "swoosh");
        Feel.buzz(root, Feel.TICK);
        readState();
        hintSeen();
        control.animateTo(1, null);
    }

    void closeControl() {
        control.animateTo(0, null);
    }

    /** Поиск сдвинулся: главный экран тускнеет и чуть отъезжает. */
    void searchMoved() {
        float p = Ui.clamp(search.progress);
        homeLayer.setAlpha(1 - p);
        float s = 1 - 0.05f * p;
        homeLayer.setScaleX(s);
        homeLayer.setScaleY(s);
        if (p > 0.5f) hintSeen();
    }

    void openSearch() {
        Feel.sound(this, "swoosh");
        search.prepare();
        search.animateTo(1, this::focusSearch);
    }

    private void focusSearch() {
        search.search.requestFocus();
        InputMethodManager im = getSystemService(InputMethodManager.class);
        if (im != null) im.showSoftInput(search.search, 0);
    }

    void hideKeyboard() {
        InputMethodManager im = getSystemService(InputMethodManager.class);
        if (im != null) im.hideSoftInputFromWindow(root.getWindowToken(), 0);
    }

    void webSearch(String q) {
        Intent i = new Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, q);
        if (!safeStartQuiet(i)) safeStart(new Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=" + Uri.encode(q))), null);
        closeAllLater();
    }

    void openSettings(String page) {
        Intent i = new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (page != null) i.putExtra("page", page);
        try {
            startActivity(i);
        } catch (Exception e) {
            toast("Не удалось открыть настройки");
        }
    }

    private void closeAllLater() {
        ui.postDelayed(() -> {
            if (control.isOpen()) { control.setProgress(0); control.tiles.setEdit(false); }
            if (search.isOpen()) { search.setProgress(0); search.onClosed(); searchMoved(); }
        }, 400);
    }

    // ── вход по отпечатку ─────────────────────────────────────────────────

    void authLock() {
        if (!locked || authing) return;
        if (!Bio.available(this)) {
            // нечем проверить (отпечаток и PIN не заданы) — просто войти
            unlock();
            return;
        }
        authing = true;
        lock.error = false;
        lock.msg = "Приложите палец к датчику";
        lock.invalidate();
        // без системного окна, если телефон даёт; иначе — окно BiometricPrompt
        FingerSensor.auth(this, new FingerSensor.Callback() {
            @Override
            public void ok() {
                authing = false;
                unlock();
            }

            @Override
            public void down() {
                lock.fingerDown();
            }

            @Override
            public void up() {
                lock.fingerUp();
            }

            @Override
            public void miss(String msg) {
                lock.fingerUp();
                lock.error = true;
                lock.msg = msg;
                Feel.buzz(lock, Feel.THUD);
                lock.invalidate();
            }

            @Override
            public void fail(String err) {
                authing = false;
                lock.error = err != null;
                lock.msg = err != null ? err : "Коснитесь, чтобы войти по отпечатку";
                lock.invalidate();
            }
        });
    }

    /**
     * Вход — как на компьютере: звук, волна из середины экрана, часы улетают
     * вверх, обои доигрывают (супер — камера летит), стол проступает из-под
     * волны чуть крупнее и пружинкой встаёт на место, значки выскакивают.
     */
    private void unlock() {
        locked = false;
        lock.msg = "Добро пожаловать";
        Feel.sound(this, "unlock");
        Feel.buzz(root, Feel.RISE);
        ui.postDelayed(() -> Feel.sound(this, "glass-wave"), 140);
        // часы переезжают с экрана блокировки в часы на столе (если они на этом столе)
        lock.flyTo = null;
        if (Prefs.clockToHome) {
            homeLayer.animate().cancel();
            homeLayer.setScaleX(1);
            homeLayer.setScaleY(1);
            RectF to = desk.clockOnScreen();
            if (to != null) {
                int[] l = new int[2];
                lock.getLocationOnScreen(l);
                to.offset(-l[0], -l[1]);
                lock.flyTo = to;
                VWidgets.hideClock = true;
                desk.invalidateAll();
                ui.postDelayed(() -> {
                    VWidgets.hideClock = false;
                    desk.invalidateAll();
                }, (long) (700 * Prefs.k()));
            }
        }
        lock.playExit(() -> lock.setVisibility(View.GONE));
        wall.unlock();
        if (lockWall.getVisibility() == View.VISIBLE) {
            // свои обои входа тают, под ними — обои стола
            lockWall.unlock();
            lockWall.animate().alpha(0).setStartDelay(120).setDuration((long) (700 * Prefs.k())).setInterpolator(Anim.STD)
                    .withEndAction(() -> lockWall.setVisibility(View.GONE)).start();
        }
        if (Prefs.unlockWave) GlassWave.play(root, root.getWidth() / 2f, root.getHeight() / 2f, 1f, 1700);
        homeLayer.animate().cancel();
        homeLayer.setAlpha(0);
        homeLayer.setScaleX(1.14f);
        homeLayer.setScaleY(1.14f);
        homeLayer.animate().alpha(1).setStartDelay(240).setDuration(560).setInterpolator(Anim.STD).start();
        ui.postDelayed(() -> {
            Anim.spring(homeLayer.getScaleX(), 1f, 0, 160, 0.78f, k -> {
                homeLayer.setScaleX(k);
                homeLayer.setScaleY(k);
            }, null);
            desk.playEnter();
            dockGrid.playEnter();
        }, 220);
    }

    /** Вернулись из приложения: окно сжимается в значок, стол выходит из глубины. */
    private void land() {
        homeLayer.animate().cancel();
        if (launchCard != null) {
            // жестом домой окно в значок уносит служба жестов — карточка не нужна
            if (VanksAccess.flying()) launchCard.drop();
            else launchCard.close(null);
            launchCard = null;
        }
        if (depth.v < 0.05f) depth.snap(1);
        setDepth(0, 150, 0.82f);
    }

    // глубина стола: 0 — на месте, 1 — ушёл вглубь (меньше, темнее, размыт)
    private final LaunchCard.Spring depth = new LaunchCard.Spring(0);
    private android.animation.TimeAnimator depthClock;

    private void setDepth(float to, float k, float z) {
        depth.to(to, k, z);
        if (depthClock != null) return;
        depthClock = new android.animation.TimeAnimator();
        depthClock.setTimeListener((a, total, dt) -> {
            depth.step(Math.min(0.05f, dt / 1000f) / Prefs.k());
            float d = Math.max(0, depth.v);
            homeLayer.setScaleX(1 - 0.1f * d);
            homeLayer.setScaleY(1 - 0.1f * d);
            homeLayer.setAlpha(Ui.clamp(1 - 0.4f * d));
            if (Build.VERSION.SDK_INT >= 31) {
                float b = Ui.dpf(14) * Ui.clamp(d);
                homeLayer.setRenderEffect(b < 0.5f ? null : android.graphics.RenderEffect.createBlurEffect(b, b, android.graphics.Shader.TileMode.CLAMP));
            }
            if (depth.still(0.002f)) {
                a.end();
                depthClock = null;
                if (depth.t == 0) {
                    homeLayer.setScaleX(1);
                    homeLayer.setScaleY(1);
                    homeLayer.setAlpha(1);
                    if (Build.VERSION.SDK_INT >= 31) homeLayer.setRenderEffect(null);
                }
            }
        });
        depthClock.start();
    }

    /** Откуда открыли приложение: пакет, место значка на экране и сам значок — сюда окно и вернётся. */
    static String lastPkg;
    static RectF lastRect;
    static android.graphics.Bitmap lastIcon;

    /**
     * Жест снизу на столе (служба жестов): стол уходит вглубь за пальцем,
     * k — 0…1; меньше нуля — отпустили, вернуть на место.
     */
    static void gesturePull(float k) {
        Home h = shown;
        if (h == null || !h.resumedNow || h.locked) return;
        h.pulled = k > 0;
        if (k < 0) h.setDepth(0, 200, 0.86f);
        else h.setDepth(0.75f * k, 900, 1f);
    }

    private boolean pulled;

    /** Стол на экране и сверху. */
    static boolean onTop() {
        Home h = shown;
        return h != null && h.resumedNow;
    }

    /** Свои обои на экране блокировки — показать слой над обоями стола. */
    private void showLockWall() {
        lockWall.animate().cancel();
        if (!Ui.ownLockWall()) {
            lockWall.setVisibility(View.GONE);
            return;
        }
        if (Ui.loadLockWall(this) || lockWall.stale()) lockWall.reload();
        lockWall.setAlpha(1);
        lockWall.setVisibility(View.VISIBLE);
        lockWall.lockNow();
    }

    private void relock() {
        if (Prefs.lockMain || !Prefs.fingerprint || !Bio.available(this)) return;
        FingerSensor.cancel();
        authing = false;
        locked = true;
        control.tiles.setEdit(false);
        control.setProgress(0);
        if (search.isOpen()) {
            search.setProgress(0);
            search.onClosed();
            searchMoved();
        }
        wall.lockNow();
        showLockWall();
        if (menu != null) menu.dismiss();
        if (folder != null) folder.close();
        if (picker != null) picker.close();
        if (desk.editing) desk.setEditing(false);
        homeLayer.animate().cancel();
        homeLayer.setAlpha(0);
        lock.reset();
        lock.error = false;
        lock.msg = "Коснитесь, чтобы войти по отпечатку";
        lock.animate().cancel();
        lock.setAlpha(1);
        lock.setScaleX(1);
        lock.setScaleY(1);
        lock.setVisibility(View.VISIBLE);
        autoPrompted = false;
    }

    void unlockHidden() {
        if (!Bio.available(this)) {
            toast("Отпечаток и PIN не заданы — скрытые открыты без проверки");
            search.setHiddenOpen(true);
            return;
        }
        Bio.auth(this, "Скрытые приложения", "Подтвердите отпечатком", () -> search.setHiddenOpen(true), err -> {
            if (err != null) toast(err);
        });
    }

    // ── приложения ────────────────────────────────────────────────────────

    void launch(Apps.App a, RectF r) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(a.cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        // нажали другой значок, пока окно ещё растёт: то, что открывалось,
        // не запускается и сжимается обратно в свой значок, а растёт новое
        if (pendingGo != null) {
            ui.removeCallbacks(pendingGo);
            pendingGone[0] = true;
            pendingGo = null;
        }
        if (launchCard != null) {
            LaunchCard old = launchCard;
            launchCard = null;
            old.close(null);
        }
        if (r == null) {
            if (safeStart(i, null)) launched();
            return;
        }
        // как в ColorOS: значок вырастает в карточку на весь экран, и только
        // потом открывается окно. Свои анимации запуска чужих приложений
        // Android 13+ не берёт — поэтому рост рисуем сами, до запуска.
        int[] loc = new int[2];
        root.getLocationInWindow(loc);
        RectF from = new RectF(r);
        from.offset(-loc[0], -loc[1]);
        LaunchCard card = new LaunchCard(this, from, a.bmp);
        // нажали, пока стол ещё в глубине (уменьшен): растём из того места,
        // где значок виден, а садиться при возврате — туда, где он будет
        RectF flat = new RectF(r);
        float sc = homeLayer.getScaleX();
        if (Math.abs(sc - 1) > 0.002f) {
            int[] hl = new int[2];
            homeLayer.getLocationOnScreen(hl);
            // getLocationOnScreen уже с учётом масштаба: опорная точка — центр слоя
            float px = hl[0] + homeLayer.getPivotX() * sc, py = hl[1] + homeLayer.getPivotY() * sc;
            flat.set(px + (r.left - px) / sc, py + (r.top - py) / sc, px + (r.right - px) / sc, py + (r.bottom - py) / sc);
            RectF fl = new RectF(flat);
            fl.offset(-loc[0], -loc[1]);
            card.landAt(fl);
        }
        root.addView(card, new FrameLayout.LayoutParams(-1, -1));
        launchCard = card;
        lastPkg = a.cn.getPackageName();
        lastRect = flat;
        lastIcon = a.bmp;
        Feel.sound(this, "app-open");
        Feel.buzz(root, Feel.CLICK);
        homeLayer.animate().cancel();
        // стол уходит вглубь вместе с ростом окна
        setDepth(1, 170, 0.92f);
        Bundle opts = ActivityOptions.makeCustomAnimation(this, R.anim.app_in, R.anim.app_hold).toBundle();
        boolean[] gone = {false};
        Runnable go = () -> {
            if (gone[0]) return;
            gone[0] = true;
            if (pendingGo != null && pendingGone == gone) pendingGo = null;
            if (safeStart(i, opts)) launched();
            else land();
        };
        pendingGo = go;
        pendingGone = gone;
        card.open(go);
        ui.postDelayed(go, (long) (700 * Prefs.k()));   // на случай, если анимация не дошла
    }

    // запуск, который ждёт конца роста окна (отменяется другим значком)
    private Runnable pendingGo;
    private boolean[] pendingGone = {true};

    /** Приложение открыто: при возврате стол «приземлится», карточка сожмётся в значок. */
    private void launched() {
        hideKeyboard();
        closeAllLater();
        returning = true;
    }

    private LaunchCard launchCard;

    // ── меню-капли, папки, виджеты ────────────────────────────────────────

    private DropMenu newMenu(String title, float rawX, float rawY, RectF anchor) {
        if (menu != null) menu.dismiss();
        DropMenu m = new DropMenu(this, title, rawX, rawY, anchor);
        m.onClosed(() -> {
            if (menu == m) menu = null;
        });
        menu = m;
        return m;
    }

    private static RectF screenRect(View v) {
        int[] l = new int[2];
        v.getLocationOnScreen(l);
        return new RectF(l[0], l[1], l[0] + v.getWidth() * v.getScaleX(), l[1] + v.getHeight() * v.getScaleY());
    }

    /** Зажали пустое место стола. */
    private void homeMenu(float rawX, float rawY) {
        Feel.buzz(root, Feel.PRESS);
        newMenu(null, rawX, rawY, null)
                .add("Изменить стол", Glyph::grid, () -> desk.setEditing(true))
                .add("Виджеты", Glyph::plus, this::openPicker)
                .add("Обои и экран блокировки", Glyph::image, () -> openSettings("lockwall"))
                .add("Главный экран", Glyph::sliders, () -> openSettings("home"))
                .add("Настройки Vanks", Glyph::gear, () -> openSettings(null))
                .show(root);
    }

    /** Зажали вещь на столе: значок, папку или виджет. */
    private void itemMenu(Layout.Item it, View v, float rawX, float rawY) {
        RectF r = screenRect(v);
        switch (it.type) {
            case Layout.APP: {
                Apps.App a = Apps.byKey.get(it.key);
                if (a != null) appMenu(a, it, r, rawX, rawY);
                break;
            }
            case Layout.FOLDER: {
                int si = Math.max(0, java.util.Arrays.asList(Layout.FOLDER_STYLES).indexOf(it.style));
                int next = (si + 1) % Layout.FOLDER_STYLES.length;
                newMenu(it.name, rawX, rawY, r)
                        .add("Открыть", Glyph::grid, () -> openFolder(it, v))
                        .add("Вид: " + Layout.FOLDER_NAMES[next], Glyph::palette, () -> {
                            it.style = Layout.FOLDER_STYLES[next];
                            Layout.save();
                            deskChanged();
                        })
                        .add("Изменить размер", Glyph::resize, () -> desk.selectItem(it))
                        .add("Изменить стол", Glyph::sliders, () -> desk.setEditing(true))
                        .danger("Разобрать папку", Glyph::close, () -> desk.remove(it))
                        .show(root);
                break;
            }
            default: {
                String name = "app".equals(it.kind) ? "Виджет" : VWidgets.type(it.kind).name;
                DropMenu m = newMenu(name, rawX, rawY, r);
                if ("clock".equals(it.kind)) {
                    int si = java.util.Arrays.asList(ClockView.STYLES).indexOf(it.style);
                    int next = si + 1 >= ClockView.STYLES.length ? -1 : si + 1;   // -1 — часы темы
                    m.add("Вид: " + (next < 0 ? "обычные" : ClockView.STYLE_NAMES[next]), Glyph::palette, () -> {
                        it.style = next < 0 ? "" : ClockView.STYLES[next];
                        Layout.save();
                        desk.invalidateAll();
                    });
                }
                m.add("Изменить размер", Glyph::resize, () -> desk.selectItem(it))
                        .add("Изменить стол", Glyph::sliders, () -> desk.setEditing(true))
                        .add("Добавить виджет", Glyph::plus, this::openPicker)
                        .danger("Убрать виджет", Glyph::close, () -> desk.remove(it))
                        .show(root);
            }
        }
    }

    /** Меню приложения: со стола (it), из дока или поиска (it == null). */
    private void appMenu(Apps.App a, Layout.Item it, RectF r, float rawX, float rawY) {
        boolean inDock = Prefs.dock.contains(a.key);
        boolean hidden = Prefs.hidden.contains(a.key);
        DropMenu m = newMenu(a.label, rawX, rawY, r);
        // быстрые действия приложения (ярлыки) — если Android их даёт лаунчеру
        for (android.content.pm.ShortcutInfo si : shortcuts(a)) {
            CharSequence l = si.getShortLabel();
            Drawable ic = null;
            try { ic = getSystemService(LauncherApps.class).getShortcutIconDrawable(si, 0); } catch (Exception ignored) {}
            m.shortcut(l == null ? "" : l.toString(), ic, () -> {
                try {
                    getSystemService(LauncherApps.class).startShortcut(si, null, null);
                    Feel.sound(this, "app-open");
                } catch (Exception e) {
                    toast("Не удалось открыть");
                }
            });
        }
        if (it != null) m.add("Изменить стол", Glyph::grid, () -> desk.setEditing(true));
        if (!hidden) {
            m.add(inDock ? "Убрать из дока" : "В док", Glyph::chevronUp, () -> {
                if (inDock) Prefs.dock.remove(a.key);
                else if (Prefs.dock.size() < 5) Prefs.dock.add(a.key);
                else { toast("В доке не больше пяти значков"); return; }
                appsChanged();
            });
            m.add("Открыть в окне", Glyph::rotate, () -> {
                Feel.sound(this, "app-open");
                Windows.open(this, a);
            });
            boolean edge = Prefs.edgeApps.contains(a.key);
            m.add(edge ? "Убрать из боковой панели" : "В боковую панель", Glyph::swipe, () -> {
                if (edge) Prefs.edgeApps.remove(a.key);
                else if (Prefs.edgeApps.size() < EdgeService.MAX) Prefs.edgeApps.add(a.key);
                else { toast("В боковой панели не больше " + EdgeService.MAX + " приложений"); return; }
                if (!Prefs.edge) toast("Включите панель: «Настройки Vanks» → «Боковая панель»");
                appsChanged();
            });
        }
        m.add(hidden ? "Показать" : "Скрыть (по отпечатку)", (c, x, y, s2, col) -> Glyph.lock(c, x, y, s2, col, !hidden), () -> {
            if (hidden) Prefs.hidden.remove(a.key);
            else {
                Prefs.hidden.add(a.key);
                Prefs.dock.remove(a.key);
            }
            appsChanged();
        });
        m.add("О приложении", Glyph::info, () -> safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", a.cn.getPackageName(), null)), null));
        m.danger("Удалить", Glyph::close, () -> safeStart(new Intent(Intent.ACTION_DELETE,
                Uri.fromParts("package", a.cn.getPackageName(), null)), null));
        m.show(root);
    }

    private List<android.content.pm.ShortcutInfo> shortcuts(Apps.App a) {
        ArrayList<android.content.pm.ShortcutInfo> out = new ArrayList<>();
        try {
            LauncherApps la = getSystemService(LauncherApps.class);
            if (la == null || !la.hasShortcutHostPermission()) return out;
            LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
            q.setPackage(a.cn.getPackageName());
            q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST);
            List<android.content.pm.ShortcutInfo> l = la.getShortcuts(q, android.os.Process.myUserHandle());
            if (l != null) for (android.content.pm.ShortcutInfo si : l) {
                if (si.isEnabled() && out.size() < 4) out.add(si);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void appsChanged() {
        Prefs.save();
        seenRev = Prefs.rev;
        refreshApps();
    }

    /** Папку изменили (название, вид, состав) — перерисовать стол. */
    void deskChanged() {
        seenRev = Prefs.rev;
        desk.rebuild();
        search.reload();
    }

    private void openFolder(Layout.Item it, View v) {
        if (folder != null) return;
        int[] rl = new int[2];
        root.getLocationOnScreen(rl);
        RectF r = screenRect(v);
        r.offset(-rl[0], -rl[1]);
        folder = new Folders.Panel(this, it, r);
        root.addView(folder, root.indexOfChild(desk.dragLayer), new FrameLayout.LayoutParams(-1, -1));
        folder.open();
    }

    void folderClosed(Folders.Panel p) {
        if (folder == p) folder = null;
    }

    /** Зажали значок в открытой папке. */
    /** Закрыть меню-каплю (например, начали тащить значок в папке). */
    void closeMenus() {
        if (menu != null) menu.dismiss();
    }

    void folderAppMenu(Folders.Panel p, Apps.App a, RectF r) {
        newMenu(a.label, r.centerX(), r.centerY(), r)
                .add("Вынуть из папки", Glyph::chevronUp, () -> {
                    p.item.apps.remove(a.key);
                    Layout.placeApp(a.key, p.item.page);
                    Layout.sync();
                    Layout.save();
                    p.close();
                })
                .add("Открыть в окне", Glyph::rotate, () -> Windows.open(this, a))
                .add("О приложении", Glyph::info, () -> safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", a.cn.getPackageName(), null)), null))
                .danger("Удалить", Glyph::close, () -> safeStart(new Intent(Intent.ACTION_DELETE,
                        Uri.fromParts("package", a.cn.getPackageName(), null)), null))
                .show(root);
    }

    void openPicker() {
        if (picker != null) return;
        if (menu != null) menu.dismiss();
        picker = new Widgets.Picker(this);
        root.addView(picker, new FrameLayout.LayoutParams(-1, -1));
        picker.open();
    }

    /** Поставили значок или виджет — по экрану расходится стеклянная волна, как на ПК. */
    void wave(float rawX, float rawY) {
        int[] l = new int[2];
        root.getLocationOnScreen(l);
        GlassWave.play(root, rawX - l[0], rawY - l[1], 0.55f, 1100);
        Feel.sound(this, "glass-wave");
    }

    void openVariants(Layout.Item it) {
        if (variants != null) return;
        variants = new Widgets.Variants(this, it);
        root.addView(variants, new FrameLayout.LayoutParams(-1, -1));
        variants.open();
    }

    void variantsClosed(Widgets.Variants v) {
        if (variants == v) variants = null;
    }

    /** Выбрали вид виджета: оформление и размер (если не влезает — на свободное место). */
    void applyVariant(Layout.Item it, Widgets.Variant v) {
        if ("clock".equals(it.kind) && Prefs.clockToHome) {
            // часы на столе — те же, что на экране блокировки
            Prefs.lockClock = v.style;
            Prefs.save();
            seenRev = Prefs.rev;
        }
        it.style = v.style;
        if (v.w != it.w || v.h != it.h) {
            if (!Layout.free(it.page, it.x, it.y, v.w, v.h, it)) {
                Layout.items.remove(it);
                int[] f = Layout.firstFree(it.page, v.w, v.h);
                it.page = f[0];
                it.x = f[1];
                it.y = f[2];
                Layout.items.add(it);
            }
            it.w = v.w;
            it.h = v.h;
        }
        Layout.save();
        seenRev = Prefs.rev;
        desk.rebuild();
        desk.post(() -> {
            Widgets.sizeChanged(this, it, Math.round(it.w * Widgets.cellW), Math.round(it.h * Widgets.cellH));
            RectF r = desk.itemOnScreen(it);
            if (r != null) wave(r.centerX(), r.centerY());
        });
    }

    void pickerClosed(Widgets.Picker p) {
        if (picker == p) picker = null;
    }

    /** Новый виджет — на стол, в первую свободную клетку. */
    void addWidget(Layout.Item it) {
        desk.add(it);
        Feel.sound(this, "snap-in");
        Feel.buzz(root, Feel.CONFIRM);
        desk.post(() -> {
            Widgets.sizeChanged(this, it, Math.round(it.w * Widgets.cellW), Math.round(it.h * Widgets.cellH));
            RectF r = desk.itemOnScreen(it);
            if (r != null) wave(r.centerX(), r.centerY());
        });
    }

    private void widgetTap(Layout.Item it, View v, float x, float y) {
        // свои кнопки у музыки и таймера
        RectF r = new RectF(0, 0, v.getWidth(), v.getHeight());
        r.inset(Ui.dpf(5), Ui.dpf(5));
        String act = VWidgets.tap(this, it, r, x, y);
        if (act != null) {
            Feel.buzz(root, Feel.CLICK);
            if (!act.equals("done")) mediaKey(act.equals("prev") ? -1 : act.equals("next") ? 1 : 0);
            if (act.equals("play")) VWidgets.playing = !VWidgets.playing;
            desk.invalidateAll();
            return;
        }
        Intent i;
        switch (it.kind == null ? "" : it.kind) {
            case "music":
                i = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC);
                break;
            case "net":
                i = new Intent(android.provider.Settings.ACTION_DATA_USAGE_SETTINGS);
                break;
            case "memory":
                i = new Intent(android.provider.Settings.ACTION_APPLICATION_SETTINGS);
                break;
            case "storage":
                i = new Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
                break;
            case "calendar":
                i = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR);
                break;
            case "battery":
                i = new Intent(Intent.ACTION_POWER_USAGE_SUMMARY);
                break;
            default:
                i = new Intent(AlarmClock.ACTION_SHOW_ALARMS);
        }
        Feel.buzz(root, Feel.CLICK);
        safeStart(i, null);
    }

    private void editChanged(boolean on) {
        if (menu != null) menu.dismiss();
        Feel.sound(this, on ? "toggle-on" : "toggle-off");
        editBar.animate().cancel();
        if (on) {
            editBar.setVisibility(View.VISIBLE);
            editBar.setAlpha(0);
            editBar.setTranslationY(-Ui.dpf(16));
        }
        editBar.animate().alpha(on ? 1 : 0).translationY(on ? 0 : -Ui.dpf(16)).setDuration((long) (300 * Prefs.k()))
                .setInterpolator(Anim.EMPH).withEndAction(() -> {
                    if (!desk.editing) editBar.setVisibility(View.GONE);
                }).start();
        searchPill.animate().alpha(on ? 0 : 1).setDuration(200).start();
        swipeHint.animate().alpha(on ? 0 : 1).setDuration(200).start();
    }

    private View editButton(String text, boolean accent, View.OnClickListener l) {
        TextView t = Views.label(this, text, 14, Ui.medium, 0xFFFFFFFF);
        t.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(8));
        t.setBackground(Views.round(accent ? Ui.accent() : 0x40FFFFFF, 18));
        t.setOnClickListener(v -> {
            Feel.buzz(v, Feel.CLICK);
            l.onClick(v);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = Ui.dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    // ── плитки пункта управления ──────────────────────────────────────────

    /** Выбор плитки (всё, чего ещё нет) — сплошное окно со значками, по разделам. */
    void chooseTile() {
        AlertDialog[] dlg = new AlertDialog[1];
        View v = TilePicker.build(this, control.tiles, t -> {
            control.tiles.add(t);
            seenRev = Prefs.rev;
            Feel.buzz(root, Feel.CLICK);
            dlg[0].dismiss();
        });
        if (v == null) {
            toast("Все плитки уже добавлены");
            return;
        }
        dlg[0] = new AlertDialog.Builder(this).setView(v).create();
        dlg[0].show();
        if (dlg[0].getWindow() != null) dlg[0].getWindow().setBackgroundDrawable(Views.round(Look.surface(), 24));
    }

    void tileTap(String type) {
        boolean opened = false;
        Feel.buzz(root, Feel.CLICK);
        Feel.sound(this, "tick");
        switch (type) {
            case "wifi":
                opened = Build.VERSION.SDK_INT >= 29
                        ? safeStart(new Intent(Settings.Panel.ACTION_WIFI), null)
                        : safeStart(new Intent(Settings.ACTION_WIFI_SETTINGS), null);
                break;
            case "bt":
                opened = safeStart(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS), null);
                break;
            case "torch":
                toggleTorch();
                break;
            case "dnd":
                toggleDnd();
                break;
            case "airplane":
                opened = safeStart(new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS), null);
                break;
            case "rotate":
                toggleRotate();
                break;
            case "camera":
                opened = safeStart(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), null);
                break;
            case "calc":
                opened = safeStart(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR), null);
                break;
            case "alarm":
                opened = safeStart(new Intent(AlarmClock.ACTION_SHOW_ALARMS), null);
                break;
            case "timer":
                opened = safeStart(new Intent(AlarmClock.ACTION_SHOW_TIMERS), null);
                break;
            case "battery":
                opened = safeStart(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY), null);
                break;
            case "media":
                opened = safeStart(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC), null);
                break;
            case "settings":
                openSettings(null);
                opened = true;
                break;
            default:
                // VPN, геолокация, экономия заряда, точка доступа, плитки приложений
                opened = Tiles.tap(this, type, control.tiles);
        }
        if (opened) closeAllLater();
    }

    void mediaKey(int which) {
        AudioManager am = getSystemService(AudioManager.class);
        if (am == null) return;
        int code = which < 0 ? KeyEvent.KEYCODE_MEDIA_PREVIOUS : which > 0 ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
        if (which == 0) {
            control.tiles.playing = !control.tiles.playing;
            control.tiles.invalidate();
        }
        ui.postDelayed(this::readState, 600);
    }

    void slider(String type, float v, boolean done) {
        if (type.equals("volume")) {
            AudioManager am = getSystemService(AudioManager.class);
            if (am == null) return;
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(v * max), 0);
            } catch (SecurityException e) {
                if (done) toast("Громкость сейчас менять нельзя (режим «Не беспокоить»)");
            }
            return;
        }
        // яркость: сразу в окне лаунчера, а если разрешено — и во всей системе
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = Math.max(0.02f, v);
        getWindow().setAttributes(lp);
        if (Settings.System.canWrite(this)) {
            try {
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, Math.max(1, Math.round(v * 255)));
            } catch (Exception ignored) {
            }
            if (done) {
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                getWindow().setAttributes(lp);
            }
        } else if (done && !askedWrite) {
            askedWrite = true;
            askWriteSettings("Чтобы яркость менялась во всей системе, разрешите Vanks изменять системные настройки.");
        }
    }

    private void askWriteSettings(String why) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Нужно разрешение")
                .setMessage(why)
                .setPositiveButton("Открыть", (d, w) -> safeStart(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + getPackageName())), null))
                .setNegativeButton("Не сейчас", null)
                .show();
    }

    private void toggleDnd() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (!nm.isNotificationPolicyAccessGranted()) {
            toast("Разрешите Vanks доступ к режиму «Не беспокоить»");
            safeStart(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS), null);
            return;
        }
        boolean on = nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
        nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_ALL : NotificationManager.INTERRUPTION_FILTER_PRIORITY);
        control.tiles.dnd = !on;
        control.tiles.invalidate();
    }

    private void toggleRotate() {
        if (!Settings.System.canWrite(this)) {
            askWriteSettings("Чтобы включать и выключать автоповорот, разрешите Vanks изменять системные настройки.");
            return;
        }
        boolean on = !control.tiles.rotate;
        Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, on ? 1 : 0);
        control.tiles.rotate = on;
        control.tiles.invalidate();
    }

    private void setupTorch() {
        cameras = getSystemService(CameraManager.class);
        if (cameras == null) return;
        try {
            for (String id : cameras.getCameraIdList()) {
                Boolean f = cameras.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(f)) { torchId = id; break; }
            }
        } catch (Exception ignored) {
        }
        torchCb = new CameraManager.TorchCallback() {
            @Override
            public void onTorchModeChanged(String id, boolean on) {
                if (id.equals(torchId)) {
                    control.tiles.torch = on;
                    control.tiles.invalidate();
                }
            }
        };
        cameras.registerTorchCallback(torchCb, ui);
    }

    private void toggleTorch() {
        if (cameras == null || torchId == null) {
            toast("Фонарика нет");
            return;
        }
        try {
            cameras.setTorchMode(torchId, !control.tiles.torch);
        } catch (Exception e) {
            toast("Фонарик занят");
        }
    }

    /** Прочитать состояние для плиток: сеть, Bluetooth, режимы, громкость, яркость. */
    private void readState() {
        TileGrid t = control.tiles;
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            Network n = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            t.wifi = nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception ignored) {
        }
        try {
            BluetoothManager bm = getSystemService(BluetoothManager.class);
            BluetoothAdapter ba = bm == null ? null : bm.getAdapter();
            t.bt = ba != null && ba.isEnabled();
        } catch (Exception ignored) {
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) t.dnd = nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
        t.airplane = Settings.Global.getInt(getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        t.rotate = Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) != 0;
        t.bright = Ui.clamp(Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128) / 255f);
        AudioManager am = getSystemService(AudioManager.class);
        if (am != null) {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            t.volume = max > 0 ? am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max : 0;
            t.playing = am.isMusicActive();
        }
        Tiles.readMore(this, t);
        t.invalidate();
    }

    // ── системные события ─────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private void registerAll() {
        IntentFilter time = new IntentFilter();
        time.addAction(Intent.ACTION_TIME_TICK);
        time.addAction(Intent.ACTION_TIME_CHANGED);
        time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        time.addAction("android.app.action.NEXT_ALARM_CLOCK_CHANGED");
        reg(time, i -> {
            desk.invalidateAll();
            control.refreshClock();
            control.tiles.invalidate();
            lock.invalidate();
        });
        reg(new IntentFilter(Intent.ACTION_BATTERY_CHANGED), i -> {
            int lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            control.tiles.battery = lvl < 0 ? -1 : Math.round(lvl * 100f / Math.max(1, scale));
            control.tiles.charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
            control.tiles.invalidate();
            if (VWidgets.battery != control.tiles.battery || VWidgets.charging != control.tiles.charging) {
                VWidgets.battery = control.tiles.battery;
                VWidgets.charging = control.tiles.charging;
                desk.invalidateAll();
            }
        });
        reg(new IntentFilter(Intent.ACTION_SCREEN_OFF), i -> relock());
        IntentFilter st = new IntentFilter();
        st.addAction("android.media.VOLUME_CHANGED_ACTION");
        st.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        st.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        st.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
        st.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        reg(st, i -> readState());
        IntentFilter pk = new IntentFilter();
        pk.addAction(Intent.ACTION_PACKAGE_ADDED);
        pk.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pk.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pk.addDataScheme("package");
        reg(pk, i -> {
            Widgets.Picker.forget();
            Widgets.Picker.preload(this);
            Apps.load(this);
            Apps.renderIcons(this, iconPx());
            refreshApps();
        });
    }

    private void reg(IntentFilter f, java.util.function.Consumer<Intent> on) {
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                on.accept(i);
            }
        };
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(r, f);
        receivers.add(r);
    }

    // ── мелочи ────────────────────────────────────────────────────────────

    boolean safeStart(Intent i, Bundle opts) {
        if (safeStartQuiet(i, opts)) return true;
        toast("Не удалось открыть");
        return false;
    }

    private boolean safeStartQuiet(Intent i) {
        return safeStartQuiet(i, null);
    }

    private boolean safeStartQuiet(Intent i, Bundle opts) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i, opts);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void expandNotifications() {
        // Vanks OS: своя шторка вместо системной
        if (Shade.open()) return;
        try {
            @SuppressWarnings("WrongConstant") Object sb = getSystemService("statusbar");
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Корень: жесты главного экрана. Вниз в верхней трети справа — пункт управления,
     * слева — уведомления; вниз ниже — поиск; влево и вправо — рабочие столы.
     */
    private final class Root extends FrameLayout {
        private static final int NONE = 0, CONTROL = 1, PAGE = 2, SEARCH = 3;
        private final int slop;
        private float x0, y0, startPos;
        private android.animation.ValueAnimator pageAnim;
        private int drag = NONE;
        private boolean decided;
        private VelocityTracker vt;
        private final GestureDetector gd;

        Root(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public void onLongPress(MotionEvent e) {
                    if (!blocked() && drag == NONE) homeMenu(e.getRawX(), e.getRawY());
                }

                @Override
                public boolean onSingleTapUp(MotionEvent e) {
                    if (!blocked()) desk.tapEmpty();
                    return false;
                }
            });
        }

        private boolean blocked() {
            return locked || control.isOpen() || search.isOpen() || desk.dragging()
                    || menu != null || folder != null || picker != null || variants != null;
        }

        private void track(MotionEvent e) {
            if (vt == null) vt = VelocityTracker.obtain();
            vt.addMovement(e);
        }

        /** Какой жест начался на этом движении (NONE — пока непонятно или ничего). */
        private int decide(MotionEvent e) {
            float dy = e.getY() - y0, dx = e.getX() - x0;
            // чувствительность из «Жесты и плавность»: выше — жест узнаётся раньше
            float start = slop * 1.2f / Prefs.gestSens;
            if (Math.abs(dx) < start && Math.abs(dy) < start) return NONE;
            decided = true;
            if (Math.abs(dx) > Math.abs(dy) * 1.3f) return desk.count() > 1 ? PAGE : NONE;
            if (desk.editing) return NONE;   // в правке — только листать
            if (dy > 0 && dy > Math.abs(dx) * 1.3f) {
                boolean top = y0 < getHeight() * Prefs.gestTop;
                boolean right = x0 >= getWidth() * 0.5f;
                if (top && right == Prefs.ctrlRight) {
                    // Vanks OS: пункт управления один — тот, что открывается и поверх приложений
                    if (Updater.osVersion() != null && Shade.open(true)) return NONE;
                    return CONTROL;
                }
                if (top) {
                    expandNotifications();
                    return NONE;
                }
                return Prefs.swipeSearch ? SEARCH : NONE;
            }
            return NONE;
        }

        private void start(int kind) {
            drag = kind;
            if (kind == PAGE) {
                if (pageAnim != null) pageAnim.cancel();
                startPos = desk.pos;
                desk.setPaging(true);
            } else if (kind == SEARCH) search.prepare();
            else if (kind == CONTROL) {
                readState();
                control.refreshClock();
            }
        }

        private void move(MotionEvent e) {
            float dy = e.getY() - y0, dx = e.getX() - x0;
            switch (drag) {
                case CONTROL:
                    control.setProgress(Ui.clamp(dy * Prefs.gestSens / (getHeight() * 0.4f)));
                    break;
                case PAGE: {
                    float p = startPos - dx / Math.max(1, getWidth());
                    float max = desk.count() - 1;
                    if (p < 0) p *= 0.3f;                    // резинка у краёв
                    else if (p > max) p = max + (p - max) * 0.3f;
                    desk.setPos(p);
                    break;
                }
                case SEARCH:
                    search.setProgress(Ui.clamp(dy * Prefs.gestSens / (getHeight() * 0.3f)));
                    searchMoved();
                    break;
            }
        }

        private void finish() {
            float vx = 0, vy = 0;
            if (vt != null) {
                vt.computeCurrentVelocity(1000);
                vx = vt.getXVelocity();
                vy = vt.getYVelocity();
            }
            float fast = Ui.dpf(500) / Prefs.gestSens;
            switch (drag) {
                case CONTROL: {
                    boolean open = vy > fast || (vy > -fast && control.progress > 0.35f);
                    if (open) openControl(); else control.animateTo(0, null);
                    break;
                }
                case PAGE: {
                    int to;
                    if (vx < -fast) to = (int) Math.floor(startPos) + 1;
                    else if (vx > fast) to = (int) Math.ceil(startPos) - 1;
                    else to = Math.round(desk.pos);
                    to = Math.max(0, Math.min(desk.count() - 1, to));
                    if (to != Math.round(startPos)) Feel.buzz(this, Feel.TICK);
                    float v = -vx / Math.max(1, getWidth());
                    pageAnim = Anim.spring(desk.pos, to, v, 260, 0.86f, desk::setPos, () -> desk.setPaging(false));
                    break;
                }
                case SEARCH: {
                    boolean open = vy > fast || (vy > -fast && search.progress > 0.35f);
                    if (open) Feel.sound(Home.this, "swoosh");
                    search.animateTo(open ? 1 : 0, open ? Home.this::focusSearch : null);
                    break;
                }
            }
            drag = NONE;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            if (blocked()) return false;
            track(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x0 = e.getX();
                    y0 = e.getY();
                    drag = NONE;
                    decided = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (drag == NONE && !decided) {
                        int k = decide(e);
                        if (k != NONE) {
                            start(k);
                            return true;
                        }
                    }
                    return drag != NONE;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (vt != null) { vt.recycle(); vt = null; }
                    return false;
            }
            return drag != NONE;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (locked) return true;
            if (blocked() && drag == NONE) return false;
            track(e);
            if (drag == NONE) gd.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x0 = e.getX();
                    y0 = e.getY();
                    decided = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (drag == NONE && !decided) {
                        int k = decide(e);
                        if (k != NONE) start(k);
                    }
                    if (drag != NONE) move(e);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (drag != NONE) finish();
                    if (vt != null) { vt.recycle(); vt = null; }
                    return true;
            }
            return true;
        }
    }
}
