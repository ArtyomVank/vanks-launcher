package os.vanks.launcher;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;

/**
 * Свой экран блокировки — отдельное окно, не часть стола: служба LockService
 * открывает его, когда гаснет экран, и он встаёт поверх всего, в том числе
 * поверх системного экрана блокировки.
 *
 * Палец читаем сами, без системного окна (FingerSensor). Если в телефоне
 * задан PIN и системный экран ещё заперт — палец читает система (она умеет
 * это и под нашим окном), а мы ждём, когда она откроется, и уходим красиво.
 */
public final class LockActivity extends Activity {
    /** Экран заперт нашим замком (пока не вошли — снова показывать). */
    static boolean locked;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private WallView wall;
    private LockView lock;
    private KeyguardManager km;
    private boolean authing, leaving, waitSystem, resumed;
    /** Лицо и палец читает Android под картинкой нашего экрана (handTried — уже пробовали за это включение). */
    private boolean handed, handTried;
    private BroadcastReceiver present, screenOn;
    private Island island;
    private PinPad pad;
    private int misses;
    /** Своё распознавание лица (FaceUnlock): остановить; null — не ищем. faceTried — уже искали за это включение. */
    private Runnable faceStop;
    private boolean faceTried;
    /** Окно действительно на переднем плане (камеру Android даёт только ему). */
    private boolean fg;

    /** Запереть: открыть экран блокировки поверх всего. */
    static void show(Context c) {
        locked = true;
        Intent i = new Intent(c, LockActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            c.startActivity(i);
        } catch (RuntimeException ignored) {
            // из фона без разрешения «поверх других окон» Android не пустит
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Prefs.load(this);
        Hz.apply(this);
        Ui.init(this);
        Feel.init(this);
        km = getSystemService(KeyguardManager.class);
        setShowWhenLocked(true);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        root = new FrameLayout(this);
        // окно прозрачное (чтобы при входе проступило то, что под ним), но пока
        // заперто — под обоями чёрное: иначе сквозь него видны обои системы
        root.setBackgroundColor(0xFF000000);
        boolean own = Ui.ownLockWall();
        wall = new WallView(this, own);
        root.addView(wall, new FrameLayout.LayoutParams(-1, -1));
        lock = new LockView(this);
        // нет датчика отпечатка (A04 и др.) — без значка: лицо или свайп
        lock.sensor = Bio.hasFingerprintSensor(this);
        lock.listener = this::tapped;
        root.addView(lock, new FrameLayout.LayoutParams(-1, -1));

        // долгое нажатие — ввод пароля (для другого человека, если палец не читается
        // или если узнавать некому: на A04 у системы нет ни лица, ни отпечатка)
        lock.onLongPress = this::askPassword;
        // динамический остров: сканирование пальца (если нет окна службы — свой, здесь)
        island = new Island(this);
        root.addView(island, new FrameLayout.LayoutParams(-1, Ui.dp(130), Gravity.TOP));
        Island.local = island;

        pad = new PinPad(this);
        pad.setVisibility(View.GONE);
        pad.listener = new PinPad.Listener() {
            @Override
            public void accepted(String pin) {
                closePad();
                passed(pin);
            }

            @Override
            public void cancelled() {
                closePad();
                ui.postDelayed(LockActivity.this::listen, 250);
            }
        };
        root.addView(pad, new FrameLayout.LayoutParams(-1, -1));

        root.setOnApplyWindowInsetsListener((v, in) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets s = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                lock.insetTop = s.top;
                lock.insetBottom = s.bottom;
            } else {
                lock.insetTop = in.getSystemWindowInsetTop();
                lock.insetBottom = in.getSystemWindowInsetBottom();
            }
            pad.insetBottom = lock.insetBottom;
            return in;
        });
        setContentView(root);
        wall.reload();
        wall.lockNow();
        Home.lockPose();

        // системный экран открыли пальцем или PIN-кодом — значит, и наш
        present = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (locked && !leaving) leave();
            }
        };
        // экран включился — слушать палец сразу, раньше системного экрана
        screenOn = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                // экран включил AOD — это ещё не вход; слушать палец, когда AOD уйдёт
                if (!locked || leaving || AodActivity.active) return;
                authing = false;
                grabbed = 0;
                handTried = false;
                faceTried = false;
                FingerSensor.cancel();
                listenNow();
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_USER_PRESENT);
        IntentFilter on = new IntentFilter(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(present, f, Context.RECEIVER_EXPORTED);
            registerReceiver(screenOn, on, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(present, f);
            registerReceiver(screenOn, on);
        }

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, () -> {});
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        // сами вернули свой экран поверх системного (лицо не узнали) — не сбрасывать
        if (i != null && i.getBooleanExtra("back", false)) return;
        // заперли снова, а окно ещё было открыто
        leaving = false;
        locked = true;
        reset();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        fg = true;
        if (!locked) {
            finish();
            return;
        }
        Prefs.load(this);
        if (wall.stale()) wall.reload();
        wall.setPaused(false);
        ui.removeCallbacks(tick);
        ui.post(tick);
        // сканер — сразу, как включился экран: чтобы палец достался нам, а не системному экрану
        ui.post(this::listen);
        if (Ui.loadWall(this) | Ui.loadLockWall(this)) wall.reload();
        wall.invalidate();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        fg = false;
        ui.removeCallbacks(tick);
        wall.setPaused(true);
        if (!leaving) {
            FingerSensor.cancel();
            faceStop();
            authing = false;
            scan(-1);
        }
    }

    /** Остров: −1 — убрать, иначе Island.SCANNING / SCAN_OK / SCAN_MISS. */
    private void scan(int state) {
        if (state < 0) {
            Island.remove("scan");
            return;
        }
        Island.Item it = new Island.Item("scan", Island.SCAN);
        it.state = state;
        // без датчика отпечатка сканирует лицо (своё или системное под занавесом)
        it.face = !lock.sensor;
        it.title = it.face ? "Лицо" : "Отпечаток";
        Island.put(it);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (Island.local == island) Island.local = null;
        Island.remove("scan");
        try {
            unregisterReceiver(present);
            unregisterReceiver(screenOn);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        // «Назад» не уводит с экрана блокировки
        if (code == KeyEvent.KEYCODE_BACK) return true;
        return super.onKeyDown(code, e);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            VWidgets.sample(LockActivity.this);
            lock.invalidate();
            ui.postDelayed(this, 1000);
        }
    };

    private void reset() {
        ui.removeCallbacks(gone);
        ui.removeCallbacks(handBack);
        handed = false;
        handTried = false;
        faceTried = false;
        faceStop();
        VanksAccess.uncover(true);
        FingerSensor.cancel();
        authing = false;
        lock.reset();
        root.setBackgroundColor(0xFF000000);
        misses = 0;
        pad.setVisibility(View.GONE);
        if (Build.VERSION.SDK_INT >= 31) wall.setRenderEffect(null);
        lock.error = false;
        lock.msg = idle();
        lock.animate().cancel();
        lock.setAlpha(1);
        wall.animate().cancel();
        wall.setAlpha(1);
        wall.setVisibility(View.VISIBLE);
        wall.lockNow();
        Home.lockPose();
        lock.invalidate();
    }

    /** Системный экран заперт PIN-кодом: палец читает он. */
    private boolean systemLocked() {
        // заперт именно паролем: без PIN-кода система не откроется
        return km != null && km.isDeviceLocked();
    }

    /** Слушать палец, даже если окно ещё не на переднем плане (экран только включился). */
    private void listenNow() {
        boolean was = resumed;
        resumed = true;
        listen();
        resumed = was;
    }

    /** Подсказка, пока ждём: палец, лицо или свайп — смотря что есть у телефона. */
    private String idle() {
        if (lock.sensor) return "Приложите палец к датчику";
        if (Bio.face(this) || (needsPassword() && FaceUnlock.ready(this))) return "Посмотрите на экран";
        return needsPassword() ? HOLD : "Проведите вверх, чтобы войти";
    }

    private static final String HOLD = "Зажмите экран, чтобы ввести пароль";

    /** Узнать человека некому (нет ни лица, ни отпечатка), а войти можно только паролем. */
    private boolean needsPassword() {
        return !Bio.enrolled(this) && (systemLocked() || Secret.has(this));
    }

    /** Подсказка после отмены или ошибки. */
    private String tapHint() {
        if (lock.sensor) return "Коснитесь, чтобы войти по отпечатку";
        if (Bio.face(this)) return "Коснитесь, чтобы войти по лицу";
        return needsPassword() ? HOLD : "Проведите вверх, чтобы войти";
    }

    /** Начать слушать палец (без системного окна). */
    private void listen() {
        if (!locked || leaving || authing || !resumed || pad.getVisibility() == View.VISIBLE) return;
        if (needsPassword()) {
            // у Android ни лица, ни отпечатка: лицо узнаёт сам Vanks (если записано),
            // системный ввод не зовём — пароль только по долгому нажатию
            if (FaceUnlock.ready(this) && !faceTried && fg) {
                faceScan();
                return;
            }
            if (faceStop != null) return;
            scan(-1);
            lock.error = false;
            lock.msg = HOLD;
            lock.invalidate();
            return;
        }
        if (handOver()) return;
        // палец читаем сами и при запертом системном экране; если его откроет
        // сама система — тоже уходим (USER_PRESENT)
        waitSystem = systemLocked();
        if (waitSystem && !FingerSensor.silent(this)) {
            lock.error = false;
            lock.msg = idle();
            lock.invalidate();
            return;
        }
        if (!lock.sensor) {
            // лицо читаем сразу, без касания; нет лица — вход свайпом
            if (Bio.face(this)) auth();
            else {
                lock.msg = idle();
                lock.invalidate();
            }
            return;
        }
        if (!FingerSensor.silent(this) && !Bio.available(this)) {
            // ни отпечатка, ни PIN — вход свайпом
            lock.msg = "Проведите вверх, чтобы войти";
            lock.invalidate();
            return;
        }
        if (!FingerSensor.silent(this)) {
            // тихо нельзя — окно отпечатка только по касанию
            lock.msg = tapHint();
            lock.invalidate();
            return;
        }
        auth();
    }

    /**
     * Android заперт PIN-кодом, а лицо приложениям Xiaomi не отдаёт: тогда
     * открываем системный экран, но поверх него служба держит картинку
     * экрана Vanks. Лицо (и палец) читает система, человек видит свой экран;
     * открылось — уходим как обычно. Не узнала за 8 с — свой экран снова
     * сверху, палец читаем сами.
     */
    private boolean handOver() {
        if (handed) return true;
        if (handTried || !Prefs.lockSystemBio || !Bio.enrolled(this) || km == null || !VanksAccess.running() || !systemLocked()) return false;
        handed = true;
        handTried = true;
        authGen++;
        FingerSensor.cancel();
        authing = false;
        lock.error = false;
        lock.msg = "Посмотрите на экран или приложите палец";
        lock.invalidate();
        scan(Island.SCANNING);
        ui.postDelayed(() -> snap(b -> {
            if (!handed || leaving) return;
            if (!VanksAccess.cover(b, 9000)) {
                handed = false;
                listen();
                return;
            }
            // занавес встал — под ним открыть системный экран
            ui.postDelayed(this::dismissSystem, 80);
        }), 50);
        return true;
    }

    private void dismissSystem() {
        if (!handed || leaving) return;
        ui.removeCallbacks(handBack);
        ui.postDelayed(handBack, 8000);
        km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
            @Override
            public void onDismissSucceeded() {
                if (!leaving) leave();
            }

            @Override
            public void onDismissCancelled() {
                handBackNow();
            }

            @Override
            public void onDismissError() {
                handBackNow();
            }
        });
    }

    private final Runnable handBack = this::handBackNow;

    /** Система не открылась — свой экран снова сверху (системный уходит под него). */
    private void handBackNow() {
        ui.removeCallbacks(handBack);
        if (!handed) return;
        handed = false;
        if (leaving || !locked) return;
        bringBack();
        ui.postDelayed(() -> VanksAccess.uncover(false), 300);
        scan(-1);
        lock.msg = idle();
        lock.invalidate();
        ui.postDelayed(this::listen, 350);
    }

    /** Снимок своего окна (с живыми обоями), для занавеса. */
    private void snap(java.util.function.Consumer<Bitmap> done) {
        int w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0) {
            done.accept(null);
            return;
        }
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(getWindow(), b, r -> done.accept(r == PixelCopy.SUCCESS ? b : drawn(b)), ui);
        } catch (RuntimeException e) {
            done.accept(drawn(b));
        }
    }

    private Bitmap drawn(Bitmap b) {
        Canvas c = new Canvas(b);
        c.drawColor(0xFF000000);
        root.draw(c);
        return b;
    }

    /** Номер чтения пальца: ответы старого (отменённого) не трогают новое. */
    private int authGen;
    /** Сколько раз подряд система отбирала датчик (повторяем, но не бесконечно). */
    private int grabbed;

    private void auth() {
        final int gen = ++authGen;
        authing = true;
        lock.error = false;
        lock.msg = idle();
        lock.invalidate();
        scan(Island.SCANNING);
        FingerSensor.auth(this, new FingerSensor.Callback() {
            @Override
            public void ok() {
                if (gen != authGen) return;
                authing = false;
                grabbed = 0;
                misses = 0;
                scan(Island.SCAN_OK);
                passed(null);
            }

            @Override
            public void down() {
                if (gen != authGen) return;
                lock.fingerDown();
            }

            @Override
            public void up() {
                if (gen != authGen) return;
                lock.fingerUp();
            }

            @Override
            public void miss(String msg) {
                if (gen != authGen) return;
                lock.fingerUp();
                lock.error = true;
                lock.msg = ++misses >= 3 ? "Не получается — зажмите экран для пароля" : msg;
                lock.invalidate();
                scan(Island.SCAN_MISS);
                ui.postDelayed(() -> {
                    if (gen == authGen && authing) scan(Island.SCANNING);
                }, 1100);
            }

            @Override
            public void fail(String msg) {
                if (gen != authGen) return;
                authing = false;
                // датчик забрал системный экран (он слушает палец сам) или он
                // занят — тихо берём снова: кто попросил последним, того и датчик
                int code = FingerSensor.lastCode;
                boolean busy = code == 1 || code == 2 || code == 3 || code == 5 || code == 8;
                if (busy && locked && !leaving && resumed && grabbed < 40 && pad.getVisibility() != View.VISIBLE) {
                    grabbed++;
                    ui.postDelayed(LockActivity.this::listen, grabbed < 6 ? 250 : 900);
                    return;
                }
                scan(-1);
                lock.error = msg != null;
                lock.msg = msg != null ? msg : tapHint();
                lock.invalidate();
            }
        });
    }

    /** Узнать лицо своей камерой (FaceUnlock): 5 секунд, в острове — «Лицо». */
    private void faceScan() {
        faceTried = true;
        faceStop();
        lock.error = false;
        lock.msg = "Посмотрите на экран";
        lock.invalidate();
        scan(Island.SCANNING);
        faceStop = FaceUnlock.scan(this, 5000, new FaceUnlock.Result() {
            @Override
            public void ok() {
                faceStop = null;
                if (leaving || !locked) return;
                scan(Island.SCAN_OK);
                passed(null);
            }

            @Override
            public void fail(String msg) {
                faceStop = null;
                if (leaving || !locked) return;
                scan(Island.SCAN_MISS);
                ui.postDelayed(() -> {
                    if (faceStop == null) scan(-1);
                }, 1200);
                lock.error = true;
                lock.msg = msg + " — коснитесь ещё раз или зажмите для пароля";
                lock.invalidate();
            }
        });
    }

    private void faceStop() {
        if (faceStop != null) faceStop.run();
        faceStop = null;
    }

    /** Касание или свайп вверх. */
    private void tapped() {
        if (leaving) return;
        if (needsPassword() && FaceUnlock.ready(this)) {
            // касание — поискать лицо ещё раз
            if (faceStop == null) faceScan();
            return;
        }
        if (needsPassword()) {
            // касание и свайп пароль не открывают — подсказать, что зажать
            lock.error = true;
            lock.msg = HOLD;
            lock.invalidate();
            Feel.buzz(lock, Feel.THUD);
            ui.postDelayed(() -> {
                lock.error = false;
                lock.invalidate();
            }, 900);
            return;
        }
        // касание датчика — читаем палец здесь, системный экран не зовём
        if (FingerSensor.silent(this)) {
            if (!authing) auth();
            return;
        }
        if (systemLocked()) {
            // читать палец сами не можем — остаётся система
            confirm();
            return;
        }
        if (!Bio.available(this)) {
            leave();
            return;
        }
        if (!authing) auth();
    }

    /**
     * Палец наш экран принял, но Android заперт PIN-кодом: открыть его
     * приложение не может — система спросит сама (обычно тот же палец).
     */
    private void confirm() {
        confirm(null);
    }

    /** typed — PIN, набранный на своей панели: его и набрать на системном экране. */
    private void confirm(String typed) {
        lock.error = false;
        // PIN набран или указан в «Отпечаток и вход», служба включена — она наберёт его сама
        String p = typed != null ? typed : Secret.get(this);
        boolean auto = p != null && VanksAccess.running();
        lock.msg = auto ? "Открываю…" : "Android просит подтвердить ещё раз";
        lock.invalidate();
        Runnable ask = () -> {
            if (auto) VanksAccess.typePin(p);
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() {
                    if (!leaving) leave();
                }

                @Override
                public void onDismissCancelled() {
                    VanksAccess.uncover(false);
                    lock.msg = idle();
                    lock.invalidate();
                    ui.postDelayed(LockActivity.this::listen, 300);
                }
            });
        };
        // PIN наберёт служба — системный экран открывается под картинкой
        // экрана Vanks, человек его не видит
        if (auto) ui.postDelayed(() -> snap(b -> {
            VanksAccess.cover(b, 5000);
            ui.postDelayed(ask, 80);
            // PIN не подошёл (набран на своей панели, без проверки) — свой экран
            // снова сверху с панелью, системный ввод так и не показываем
            if (typed != null && !Secret.has(this)) ui.postDelayed(() -> {
                if (leaving || !locked || !systemLocked()) return;
                bringBack();
                VanksAccess.uncover(false);
                openPad("Неверный пароль");
            }, 2500);
        }), 50);
        else ask.run();
    }

    /** Свой экран снова над системным. */
    private void bringBack() {
        try {
            startActivity(new Intent(this, LockActivity.class).putExtra("back", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (RuntimeException ignored) {
        }
    }

    /** Панель пароля: обои размываются, часы и подсказка уходят (как в ColorOS). */
    private void openPad(String note) {
        // PIN не сохранён — проверить его может только система: панель отдаёт любой от 4 цифр
        pad.open(note, !Secret.has(this));
        lock.animate().alpha(0).setDuration(200).start();
        if (Build.VERSION.SDK_INT >= 31) wall.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                Ui.dpf(28), Ui.dpf(28), android.graphics.Shader.TileMode.CLAMP));
    }

    private void closePad() {
        pad.close();
        lock.animate().alpha(1).setDuration(220).start();
        if (Build.VERSION.SDK_INT >= 31) wall.setRenderEffect(null);
    }

    /** Палец, лицо или пароль приняты: открыть (и системный замок, если он заперт). */
    private void passed(String typedPin) {
        if (systemLocked()) confirm(typedPin);
        else leave();
    }

    /** Долгое нажатие: свой ввод пароля, если PIN указан или телефон заперт паролем; иначе — окно Android. */
    private void askPassword() {
        if (leaving || pad.getVisibility() == View.VISIBLE) return;
        FingerSensor.cancel();
        faceStop();
        authing = false;
        scan(-1);
        if (Secret.has(this) || (systemLocked() && VanksAccess.running())) openPad(null);
        else if (systemLocked()) confirm();
        else if (Bio.available(this)) Bio.auth(this, "Вход в Vanks", null, () -> passed(null), err -> {
            if (err != null) {
                lock.error = true;
                lock.msg = err;
                lock.invalidate();
            }
        });
        else leave();
    }

    /** Вход: часы улетают, волна, обои тают — под ними то, что было открыто. */
    private void leave() {
        if (leaving) return;
        leaving = true;
        locked = false;
        handed = false;
        faceStop();
        AodActivity.dismiss();
        ui.removeCallbacks(handBack);
        // занавес над системным экраном — прочь: дальше видно наш уход
        VanksAccess.uncover(true);
        FingerSensor.cancel();
        // «Готово» в острове ещё чуть видно, потом он уходит
        ui.postDelayed(() -> scan(-1), 650);
        lock.msg = "Добро пожаловать";
        Feel.sound(this, "unlock");
        Feel.buzz(root, Feel.RISE);
        ui.postDelayed(() -> Feel.sound(this, "glass-wave"), 140);
        // под нами стол с часами — часы летят в них; иначе вверх, как на ПК
        android.graphics.RectF to = Home.lockLanding();
        if (to != null) {
            int[] l = new int[2];
            lock.getLocationOnScreen(l);
            to.offset(-l[0], -l[1]);
        }
        lock.flyTo = to;
        // системный экран без PIN-кода — убрать сразу, чтобы под тающими обоями был стол, а не он
        if (km != null && km.isKeyguardLocked() && !km.isDeviceSecure()) km.requestDismissKeyguard(this, null);
        root.setBackground(null);
        if (pad.getVisibility() == View.VISIBLE) closePad();
        lock.playExit(() -> {});
        // волне нужны свои обои под ней — без волны их можно спрятать
        if (!Prefs.unlockWave && Home.wallUnder(wall.spec())) {
            // под нами стол с теми же обоями в той же позе — анимирует он
            wall.setPaused(true);
            wall.setVisibility(View.INVISIBLE);
        } else {
            wall.unlock();
            long k = (long) (700 * Prefs.k());
            wall.animate().alpha(0).setStartDelay(160).setDuration(k).setInterpolator(Anim.STD).start();
        }
        if (Prefs.unlockWave) GlassWave.play(root, root.getWidth() / 2f, root.getHeight() / 2f, 1f, 1700);
        ui.postDelayed(gone, (long) (900 * Prefs.k()));
    }

    /** Окно закрывается после входа; заперли раньше — отменяется в reset(). */
    private final Runnable gone = () -> {
        finish();
        overridePendingTransition(0, 0);
    };

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        // системный экран открыли, пока мы ждали
        if (has && waitSystem && locked && !leaving && !systemLocked()) leave();
        else if (has && locked && !leaving && !authing) ui.postDelayed(this::listen, 150);
    }
}
