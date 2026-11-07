package os.vanks.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Недавние приложения для своего экрана «Недавние». Список задач других
 * приложений Android сторонним не отдаёт, поэтому его ведёт служба жестов:
 * какое приложение вышло вперёд — то и первое. Снимок окна она берёт сама
 * (takeScreenshot): когда приложение открылось и когда с него уходят жестом.
 * Снимки уменьшены и лежат в кэше — переживают перезапуск.
 */
final class Recents {
    private static final int MAX = 16;
    private static final int THUMB_W = 360;
    private static final ArrayList<String> order = new ArrayList<>();
    private static final HashMap<String, Bitmap> thumbs = new HashMap<>();
    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static boolean loaded;

    private Recents() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("vanks_recents", 0);
    }

    private static synchronized void load(Context c) {
        if (loaded) return;
        loaded = true;
        String s = sp(c).getString("order", "");
        for (String p : s.split(",")) if (!p.isEmpty() && !order.contains(p)) order.add(p);
    }

    private static synchronized void save(Context c) {
        sp(c).edit().putString("order", String.join(",", order)).apply();
    }

    /** Приложение вышло вперёд. */
    static synchronized void touch(Context c, String pkg) {
        load(c);
        if (!order.isEmpty() && order.get(0).equals(pkg)) return;
        order.remove(pkg);
        order.add(0, pkg);
        while (order.size() > MAX) {
            String gone = order.remove(order.size() - 1);
            dropThumb(c, gone);
        }
        save(c);
    }

    /** Список: первое — самое свежее; только то, что ещё можно открыть. */
    static synchronized List<String> list(Context c) {
        load(c);
        ArrayList<String> out = new ArrayList<>();
        for (String p : order) if (c.getPackageManager().getLaunchIntentForPackage(p) != null) out.add(p);
        return out;
    }

    static synchronized void remove(Context c, String pkg) {
        load(c);
        order.remove(pkg);
        dropThumb(c, pkg);
        save(c);
    }

    static synchronized void clear(Context c) {
        load(c);
        for (String p : new ArrayList<>(order)) dropThumb(c, p);
        order.clear();
        save(c);
    }

    private static void dropThumb(Context c, String pkg) {
        thumbs.remove(pkg);
        io.execute(() -> {
            File f = file(c, pkg);
            if (f.exists() && !f.delete()) f.deleteOnExit();
        });
    }

    private static File file(Context c, String pkg) {
        File d = new File(c.getCacheDir(), "recents");
        if (!d.isDirectory() && !d.mkdirs()) return new File(c.getCacheDir(), "r_" + pkg + ".jpg");
        return new File(d, pkg + ".jpg");
    }

    /** Снимок окна (может быть «аппаратным»): уменьшить и запомнить; own — исходный потом освободить. */
    static void shot(Context c, String pkg, Bitmap src, boolean own) {
        if (src == null || pkg == null) return;
        Context app = c.getApplicationContext();
        io.execute(() -> {
            try {
                Bitmap sw = src.getConfig() == Bitmap.Config.HARDWARE ? src.copy(Bitmap.Config.ARGB_8888, false) : src;
                if (sw == null) return;
                int h = Math.max(1, Math.round(sw.getHeight() * (THUMB_W / (float) sw.getWidth())));
                Bitmap t = Bitmap.createScaledBitmap(sw, THUMB_W, h, true);
                if (sw != src && sw != t) sw.recycle();
                if (own && src != t) src.recycle();
                synchronized (Recents.class) {
                    thumbs.put(pkg, t);
                }
                try (FileOutputStream o = new FileOutputStream(file(app, pkg))) {
                    t.compress(Bitmap.CompressFormat.JPEG, 82, o);
                }
            } catch (Throwable ignored) {
                // снимок уже освободили или памяти мало — останется старый
            }
        });
    }

    /** Снимок приложения или null (не снимали, окно защищено). */
    static Bitmap thumb(Context c, String pkg) {
        synchronized (Recents.class) {
            Bitmap b = thumbs.get(pkg);
            if (b != null && !b.isRecycled()) return b;
        }
        File f = file(c, pkg);
        if (!f.exists()) return null;
        Bitmap b = BitmapFactory.decodeFile(f.getPath());
        if (b != null) synchronized (Recents.class) {
            thumbs.put(pkg, b);
        }
        return b;
    }
}
