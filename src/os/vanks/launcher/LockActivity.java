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
        lock.listener = this::tapped;
        root.addView(lock, new FrameLayout.LayoutParams(-1, -1));

        // двойное касание — ввод пароля (для другого человека или если палец не читается)
        lock.onDoubleTap = this::askPassword;
        // динамический остров: сканирование пальца (если нет окна службы — свой, здесь)
        island = new Island(this);
        root.addView(island, new FrameLayout.LayoutParams(-1, Ui.dp(130), Gravity.TOP));
        Island.local = island;

        pad = new PinPad(this);
        pad.setVisibility(View.GONE);
        pad.listener = new PinPad.Listener() {
            @Override
            public void accepted(String pin) {
                pad.close();
                passed(pin);
            }

            @Override
            public void cancelled() {
                pad.close();
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
                if (!locked || leaving) return;
                authing = false;
                grabbed = 0;
                handTried = false;
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
        ui.removeCallbacks(tick);
        wall.setPaused(true);
        if (!leaving) {
            FingerSensor.cancel();
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
        it.title = "Отпечаток";
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
        VanksAccess.uncover(true);
        FingerSensor.cancel();
        authing = false;
        lock.reset();
        root.setBackgroundColor(0xFF000000);
        misses = 0;
        pad.setVisibility(View.GONE);
        lock.error = false;
        lock.msg = "Приложите палец к датчику";
        lock.animate().cancel();
        lock.setAlpha(1);
        wall.animate().cancel();
        wall.setAlpha(1);
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

    /** Начать слушать палец (без системного окна). */
    private void listen() {
        if (!locked || leaving || authing || !resumed || pad.getVisibility() == View.VISIBLE) return;
        if (handOver()) return;
        // палец читаем сами и при запертом системном экране; если его откроет
        // сама система — тоже уходим (USER_PRESENT)
        waitSystem = systemLocked();
        if (waitSystem && !FingerSensor.silent(this)) {
            lock.error = false;
            lock.msg = "Приложите палец к датчику";
            lock.invalidate();
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
            lock.msg = "Коснитесь, чтобы войти по отпечатку";
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
        if (handTried || !Prefs.lockSystemBio || km == null || !VanksAccess.running() || !systemLocked()) return false;
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
        try {
            startActivity(new Intent(this, LockActivity.class).putExtra("back", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (RuntimeException ignored) {
        }
        ui.postDelayed(() -> VanksAccess.uncover(false), 300);
        scan(-1);
        lock.msg = "Приложите палец к датчику";
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
        lock.msg = "Приложите палец к датчику";
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
                lock.msg = ++misses >= 3 ? "Не получается — коснитесь дважды для пароля" : msg;
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
                lock.msg = msg != null ? msg : "Коснитесь, чтобы войти по отпечатку";
                lock.invalidate();
            }
        });
    }

    /** Касание или свайп вверх. */
    private void tapped() {
        if (leaving) return;
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
        lock.error = false;
        // PIN указан в «Отпечаток и вход» и служба включена — она наберёт его сама
        String p = Secret.get(this);
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
                    lock.msg = "Приложите палец к датчику";
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
        }), 50);
        else ask.run();
    }

    /** Палец, лицо или пароль приняты: открыть (и системный замок, если он заперт). */
    private void passed(String typedPin) {
        if (systemLocked()) confirm();
        else leave();
    }

    /** Двойное касание: свой ввод пароля, если PIN указан; иначе — окно Android. */
    private void askPassword() {
        if (leaving) return;
        FingerSensor.cancel();
        authing = false;
        scan(-1);
        if (Secret.has(this)) pad.open();
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
        if (pad.getVisibility() == View.VISIBLE) pad.close();
        lock.playExit(() -> {});
        wall.unlock();
        if (Prefs.unlockWave) GlassWave.play(root, root.getWidth() / 2f, root.getHeight() / 2f, 1f, 1700);
        long k = (long) (700 * Prefs.k());
        wall.animate().alpha(0).setStartDelay(160).setDuration(k).setInterpolator(Anim.STD).start();
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
