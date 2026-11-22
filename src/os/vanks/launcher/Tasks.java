package os.vanks.launcher;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Rect;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Окна произвольной формы напрямую через диспетчер задач Android — на Vanks OS.
 * Там лаунчер — системные «Недавние» (config_recentsComponentName), поэтому
 * Android сам выдаёт ему MANAGE_ACTIVITY_TASKS и REMOVE_TASKS (signature|recents),
 * а скрытые вызовы открыты (hidden-api-whitelisted-app в sysconfig/vanks.xml).
 * Так окно двигается, меняет размер, сворачивается и закрывается как в режиме
 * рабочего стола Android: WindowContainerTransaction → applyTransaction.
 * На других телефонах ok() — false, и рамки ведут себя по-старому (жестами).
 */
final class Tasks {
    static final int UNDEFINED = 0, FREEFORM = 5;

    /** Задача: номер, приложение, место на экране, режим окна и ключ для перестановок. */
    static final class Task {
        int id, mode;
        String pkg;
        final Rect bounds = new Rect();
        Object token;
        /** С чего задача начиналась — открыть её снова (без MANAGE_ACTIVITY_TASKS). */
        android.content.Intent intent;
        boolean visible = true;
    }

    private static Boolean ready;
    private static Method getService, organizer, apply, removeTask, setBounds, reorder, setMode;
    private static Class<?> wctClass;

    /** Можно ли управлять окнами напрямую (разрешение есть и скрытые вызовы доступны). */
    static boolean ok(Context c) {
        if (ready != null) return ready;
        boolean r = false;
        if (c.checkSelfPermission("android.permission.MANAGE_ACTIVITY_TASKS") == PackageManager.PERMISSION_GRANTED) {
            try {
                Class<?> atm = Class.forName("android.app.ActivityTaskManager");
                Class<?> iatm = Class.forName("android.app.IActivityTaskManager");
                Class<?> iwoc = Class.forName("android.window.IWindowOrganizerController");
                Class<?> token = Class.forName("android.window.WindowContainerToken");
                wctClass = Class.forName("android.window.WindowContainerTransaction");
                getService = atm.getMethod("getService");
                organizer = iatm.getMethod("getWindowOrganizerController");
                apply = iwoc.getMethod("applyTransaction", wctClass);
                removeTask = iatm.getMethod("removeTask", int.class);
                setBounds = wctClass.getMethod("setBounds", token, Rect.class);
                reorder = wctClass.getMethod("reorder", token, boolean.class);
                setMode = wctClass.getMethod("setWindowingMode", token, int.class);
                r = true;
            } catch (Throwable e) {
                android.util.Log.w("Vanks", "окна напрямую недоступны: " + e);
            }
        }
        ready = r;
        return r;
    }

    /** Задачи в окнах произвольной формы, сверху вниз. */
    @SuppressWarnings("deprecation")
    static List<Task> freeform(Context c) {
        ArrayList<Task> out = new ArrayList<>();
        if (!ok(c)) return out;
        List<ActivityManager.RunningTaskInfo> ts;
        try {
            // с REAL_GET_TASKS — задачи всех приложений, а не только свои
            ts = c.getSystemService(ActivityManager.class).getRunningTasks(64);
        } catch (RuntimeException e) {
            return out;
        }
        for (ActivityManager.RunningTaskInfo ti : ts) {
            try {
                Object cfg = field(ti, "configuration");
                Object wc = cfg.getClass().getField("windowConfiguration").get(cfg);
                int mode = (int) wc.getClass().getMethod("getWindowingMode").invoke(wc);
                if (mode != FREEFORM) continue;
                Task t = new Task();
                t.id = ti.taskId;
                t.mode = mode;
                t.bounds.set((Rect) wc.getClass().getMethod("getBounds").invoke(wc));
                t.token = field(ti, "token");
                t.pkg = ti.topActivity != null ? ti.topActivity.getPackageName()
                        : ti.baseActivity != null ? ti.baseActivity.getPackageName() : "";
                try {
                    t.visible = (boolean) field(ti, "isVisible");
                } catch (ReflectiveOperationException ignored) {
                }
                if (!t.pkg.isEmpty() && t.token != null && !t.bounds.isEmpty()) out.add(t);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return out;
    }

    private static Object field(Object o, String name) throws ReflectiveOperationException {
        Class<?> k = o.getClass();
        while (k != null) {
            try {
                java.lang.reflect.Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                k = k.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** Одна перестановка: каждая пара — метод WindowContainerTransaction и его значение. */
    private static boolean run(Task t, Object... ops) {
        try {
            Object wct = wctClass.getConstructor().newInstance();
            for (int i = 0; i < ops.length; i += 2) ((Method) ops[i]).invoke(wct, t.token, ops[i + 1]);
            apply.invoke(organizer.invoke(getService.invoke(null)), wct);
            return true;
        } catch (Throwable e) {
            android.util.Log.w("Vanks", "окно " + t.pkg + ": " + e);
            return false;
        }
    }

    /** Новое место и размер окна. */
    static boolean place(Task t, Rect r) {
        return run(t, setBounds, new Rect(r));
    }

    /** Свернуть: окно уходит под стол (как «свернуть» в режиме рабочего стола Android). */
    static boolean minimize(Task t) {
        return run(t, reorder, false);
    }

    /** На весь экран: режим окна наследует экран, размер — весь. */
    static boolean maximize(Task t) {
        return run(t, setMode, UNDEFINED, setBounds, new Rect());
    }

    /**
     * Последнее открытое приложение — верхняя задача не Vanks (стол, «Недавние»
     * и «В окно» — это Vanks). null — открытых нет или задачи не видны.
     * Хватает REAL_GET_TASKS; ключ окна (token) — только при ok().
     */
    @SuppressWarnings("deprecation")
    static Task last(Context c) {
        List<ActivityManager.RunningTaskInfo> ts;
        try {
            ts = c.getSystemService(ActivityManager.class).getRunningTasks(16);
        } catch (RuntimeException e) {
            return null;
        }
        for (ActivityManager.RunningTaskInfo ti : ts) {
            String pkg = ti.topActivity != null ? ti.topActivity.getPackageName()
                    : ti.baseActivity != null ? ti.baseActivity.getPackageName() : "";
            if (pkg.isEmpty() || pkg.equals(c.getPackageName()) || pkg.equals("com.android.systemui")) continue;
            try {
                Object cfg = field(ti, "configuration");
                Object wc = cfg.getClass().getField("windowConfiguration").get(cfg);
                // только обычные приложения: не стол и не «Недавние» системы
                int type = (int) wc.getClass().getMethod("getActivityType").invoke(wc);
                if (type != 1) continue;
                Task t = new Task();
                t.id = ti.taskId;
                t.pkg = pkg;
                t.mode = (int) wc.getClass().getMethod("getWindowingMode").invoke(wc);
                t.intent = ti.baseIntent;
                if (ok(c)) t.token = field(ti, "token");
                if (t.token != null || t.intent != null) return t;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return null;
    }

    /** Режим окна задачи id сейчас; -1 — задачи нет или не видно. */
    @SuppressWarnings("deprecation")
    static int mode(Context c, int id) {
        try {
            for (ActivityManager.RunningTaskInfo ti : c.getSystemService(ActivityManager.class).getRunningTasks(16)) {
                if (ti.taskId != id) continue;
                Object cfg = field(ti, "configuration");
                Object wc = cfg.getClass().getField("windowConfiguration").get(cfg);
                return (int) wc.getClass().getMethod("getWindowingMode").invoke(wc);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return -1;
    }

    /** Сделать окно плавающим на месте r и поднять наверх. */
    static boolean floating(Task t, Rect r) {
        return run(t, setMode, FREEFORM, setBounds, new Rect(r), reorder, true);
    }

    /** Закрыть окно, как смахивание в «Недавних»: задача убирается, приложение выгружается. */
    static boolean close(Task t) {
        try {
            return (boolean) removeTask.invoke(getService.invoke(null), t.id);
        } catch (Throwable e) {
            android.util.Log.w("Vanks", "закрыть " + t.pkg + ": " + e);
            return false;
        }
    }

    private Tasks() {}
}
