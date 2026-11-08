package os.vanks.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/** Приложения со значком на рабочем столе и их значки в форме, выбранной в «Оформлении». */
final class Apps {
    static final class App {
        ComponentName cn;
        String key;
        String label;
        Drawable icon;   // как отдаёт телефон (с темой значков прошивки)
        Drawable raw;    // свой значок приложения, без темы прошивки
        Bitmap bmp;
        int category = -1;  // ApplicationInfo.CATEGORY_*: для названия новой папки
    }

    /** Приложения AOSP, которые в Vanks OS заменяют свои. */
    private static final java.util.Set<String> OS_HIDDEN = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.android.settings", "com.android.camera2"));

    static final ArrayList<App> all = new ArrayList<>();
    static final HashMap<String, App> byKey = new HashMap<>();
    static int iconPx;

    static void load(Context c) {
        PackageManager pm = c.getPackageManager();
        int dpi = Math.max(c.getResources().getDisplayMetrics().densityDpi, 480);
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
        ArrayList<App> out = new ArrayList<>();
        boolean os = Updater.osVersion() != null;
        for (ResolveInfo ri : list) {
            if (ri.activityInfo == null) continue;
            // себя не показываем, а «Настройки» — показываем
            if (ri.activityInfo.name.equals(Home.class.getName())) continue;
            // в Vanks OS одни «Настройки» и одна «Камера» — Vanks; настройки Android открываются из них
            if (os && OS_HIDDEN.contains(ri.activityInfo.packageName)) continue;
            App a = new App();
            a.cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            a.key = a.cn.flattenToShortString();
            CharSequence l = ri.loadLabel(pm);
            a.label = l == null ? ri.activityInfo.packageName : l.toString().trim();
            try { a.icon = ri.loadIcon(pm); } catch (Exception e) { a.icon = null; }
            a.raw = original(pm, ri.activityInfo, dpi);
            a.category = ri.activityInfo.applicationInfo.category;
            out.add(a);
        }
        Collator col = Collator.getInstance(new Locale("ru"));
        out.sort((x, y) -> col.compare(x.label, y.label));
        all.clear();
        all.addAll(out);
        byKey.clear();
        for (App a : all) byKey.put(a.key, a);
    }

    /**
     * Значок прямо из ресурсов приложения. Темы значков прошивок (HyperOS,
     * ColorOS) подменяют то, что отдаёт loadIcon, а сами ресурсы не трогают.
     */
    private static Drawable original(PackageManager pm, android.content.pm.ActivityInfo ai, int dpi) {
        try {
            android.content.res.Resources r = pm.getResourcesForApplication(ai.applicationInfo);
            int id = ai.getIconResource();
            if (id == 0) id = ai.applicationInfo.icon;
            if (id != 0) return r.getDrawableForDensity(id, dpi, null);
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Значок для своего вида (Vanks, подложка пака): без темы прошивки, если есть. */
    static Drawable own(App a) {
        return a.raw != null ? a.raw : a.icon;
    }

    static List<App> visible() {
        ArrayList<App> out = new ArrayList<>();
        for (App a : all) if (!Prefs.hidden.contains(a.key)) out.add(a);
        return out;
    }

    static List<App> hiddenApps() {
        ArrayList<App> out = new ArrayList<>();
        for (App a : all) if (Prefs.hidden.contains(a.key)) out.add(a);
        return out;
    }

    static List<App> resolve(List<String> keys) {
        ArrayList<App> out = new ArrayList<>();
        for (String k : keys) {
            App a = byKey.get(k);
            if (a != null && !Prefs.hidden.contains(k)) out.add(a);
        }
        return out;
    }

    /** Первый запуск: в док — звонки, сообщения, браузер, камера; на экран — привычные приложения. */
    static void pickDefaults(Context c) {
        Prefs.dock.clear();
        Prefs.home.clear();
        Intent[] dock = {
                new Intent(Intent.ACTION_DIAL),
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
                new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
        };
        Intent[] home = {
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY),
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC),
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR),
                new Intent(AlarmClock.ACTION_SHOW_ALARMS),
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS),
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL),
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS),
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR),
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_FILES),
                new Intent(Settings.ACTION_SETTINGS),
        };
        for (Intent i : dock) addByIntent(c, i, Prefs.dock);
        for (Intent i : home) addByIntent(c, i, Prefs.home);
        for (String pkg : new String[]{"com.android.vending", "com.google.android.youtube"}) addByPackage(pkg, Prefs.home);
        for (App a : all) {
            if (Prefs.home.size() >= 12) break;
            if (!Prefs.home.contains(a.key) && !Prefs.dock.contains(a.key)) Prefs.home.add(a.key);
        }
        Prefs.save();
    }

    private static void addByIntent(Context c, Intent i, ArrayList<String> to) {
        try {
            ResolveInfo ri = c.getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri == null || ri.activityInfo == null) return;
            addByPackage(ri.activityInfo.packageName, to);
        } catch (Exception ignored) {
        }
    }

    private static void addByPackage(String pkg, ArrayList<String> to) {
        for (App a : all) {
            if (a.cn.getPackageName().equals(pkg)) {
                if (!to.contains(a.key) && !Prefs.dock.contains(a.key) && !Prefs.home.contains(a.key)) to.add(a.key);
                return;
            }
        }
    }

    // ── значки ────────────────────────────────────────────────────────────

    static void renderIcons(Context c, int px) {
        iconPx = px;
        for (App a : all) a.bmp = render(c, a, px);
    }

    /**
     * Значок в выбранном виде: «Vanks» — в форме из «Оформления», «Как в
     * системе» — как его отдаёт телефон (с темой значков прошивки), из пака.
     */
    static Bitmap render(Context c, App a, int size) {
        // форма из «Оформления» — у любых значков: и системных (HyperOS отдаёт
        // квадраты во весь размер), и из пака
        if (Prefs.icons.equals("system")) return render(a.icon, size);
        if (Prefs.icons.startsWith("pack:") && IconPacks.prepare(c)) {
            Bitmap b = IconPacks.icon(a, size);
            if (b != null) return shaped(b);
        }
        return render(own(a), size);
    }

    /**
     * Обрезать готовый значок по форме: в пустую прозрачную картинку
     * заливаем только форму, а краской служит сам значок. Всё, что вне
     * формы, остаётся прозрачным при любом устройстве (маска DST_IN на
     * HyperOS оставляла квадрат).
     */
    static Bitmap shaped(Bitmap b) {
        int size = b.getWidth();
        if (b.getConfig() == Bitmap.Config.HARDWARE) b = b.copy(Bitmap.Config.ARGB_8888, false);
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(new android.graphics.BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        new Canvas(out).drawPath(shapePath(size), p);
        return out;
    }

    /** Значок как есть: адаптивный — в форме, которую задаёт телефон. */
    static Bitmap plain(Drawable d, int size) {
        Bitmap b = Bitmap.createBitmap(Math.max(1, size), Math.max(1, size), Bitmap.Config.ARGB_8888);
        if (d != null) {
            d.setBounds(0, 0, size, size);
            d.draw(new Canvas(b));
        }
        return b;
    }

    /**
     * Форма значка: скруглённый квадрат с ползунком скругления из
     * «Оформления» — от квадрата (0) до круга (1).
     */
    static Path shapePath(float size) {
        Path p = new Path();
        float f = Math.max(0f, Math.min(1f, Prefs.iconRound));
        if (f >= 0.995f) p.addCircle(size / 2, size / 2, size / 2, Path.Direction.CW);
        else {
            float r = size / 2 * Math.max(0.04f, f);
            p.addRoundRect(new RectF(0, 0, size, size), r, r, Path.Direction.CW);
        }
        return p;
    }

    static Bitmap render(Drawable d, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        boolean themed = Prefs.themed;
        int dark = Ui.accentDark();
        int light = Ui.accentLight();
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        int ext = size / 4;
        if (d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) d;
            Drawable mono = Build.VERSION.SDK_INT >= 33 ? a.getMonochrome() : null;
            if (themed && mono != null) {
                bg.setShader(new LinearGradient(0, 0, 0, size, Ui.mix(dark, Color.WHITE, 0.08f), dark, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, size, size, bg);
                mono = mono.mutate();
                mono.setTint(light);
                mono.setBounds(-ext, -ext, size + ext, size + ext);
                mono.draw(c);
            } else {
                Drawable back = a.getBackground(), fore = a.getForeground();
                if (back != null) { back.setBounds(-ext, -ext, size + ext, size + ext); back.draw(c); }
                if (fore != null) { fore.setBounds(-ext, -ext, size + ext, size + ext); fore.draw(c); }
                if (themed) tint(c, size);
            }
        } else if (d != null) {
            legacy(c, d, size);
            if (themed) tint(c, size);
        }
        // блик сверху
        Paint hl = new Paint(Paint.ANTI_ALIAS_FLAG);
        hl.setShader(new LinearGradient(0, 0, 0, size * 0.55f, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, size, size, hl);
        // форма: всё вне её — прозрачно
        Bitmap out = shaped(b);
        b.recycle();
        b = out;
        c = new Canvas(b);
        // кромка
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, size / 96f));
        rim.setColor(0x30FFFFFF);
        c.drawPath(shapePath(size), rim);
        return b;
    }

    /** Нет одноцветного значка — хотя бы окрашиваем в тон. */
    private static void tint(Canvas c, int size) {
        Paint t = new Paint();
        t.setColor(Ui.alpha(Ui.accent(), 0.45f));
        t.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        c.drawRect(0, 0, size, size, t);
    }

    /**
     * Обычный (не адаптивный) значок — часто уже со своей формой и полями (так
     * рисуют значки темы телефона). Поля обрезаем, значок растягиваем на всю
     * форму, а то, что осталось прозрачным по краям, заливаем цветом его же края.
     * Если у значка своя форма (скруглённый квадрат во весь размер) и её углы
     * круглее нашей формы, значок ещё чуть увеличиваем: его углы и рамка уходят
     * за край, и формы не вкладываются одна в другую.
     */
    private static void legacy(Canvas c, Drawable d, int size) {
        Bitmap raw = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, size, size);
        d.draw(new Canvas(raw));
        int[] px = new int[size * size];
        raw.getPixels(px, 0, size, 0, 0, size, size);
        int l = size, t = size, r = -1, btm = -1;
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
                if ((px[y * size + x] >>> 24) > 24) {
                    if (x < l) l = x;
                    if (x > r) r = x;
                    if (y < t) t = y;
                    if (y > btm) btm = y;
                }
        if (r < 0) return;
        // цвет края: среднее непрозрачных точек в полосе у границы содержимого
        long sr = 0, sg = 0, sb = 0, n = 0;
        int band = Math.max(2, (r - l) / 10);
        for (int y = t; y <= btm; y++)
            for (int x = l; x <= r; x++) {
                if (x - l > band && r - x > band && y - t > band && btm - y > band) continue;
                int v = px[y * size + x];
                if ((v >>> 24) < 200) continue;
                sr += (v >> 16) & 255;
                sg += (v >> 8) & 255;
                sb += v & 255;
                n++;
            }
        int edge = n > 0 ? Color.rgb((int) (sr / n), (int) (sg / n), (int) (sb / n)) : 0xFFE9EBF2;
        float w = r - l + 1, h = btm - t + 1;
        float s = size / Math.max(w, h);
        float grow = 1f;
        if (w >= size * 0.8f && h >= size * 0.8f && Math.abs(w - h) < size * 0.06f) {
            // свой скруглённый квадрат: где по диагонали от угла начинается значок
            int k = 0;
            while (k < w / 3 && (px[(t + k) * size + l + k] >>> 24) < 200) k++;
            // цвет подложки — у самого угла внутри формы, а не средний по краю (там рамка)
            int in = Math.min((int) (w / 2) - 1, k + Math.max(2, (int) (w * 0.04f)));
            int[] probe = {px[(t + in) * size + l + in], px[(t + in) * size + r - in], px[(btm - in) * size + l + in], px[(btm - in) * size + r - in]};
            long pr = 0, pg = 0, pb = 0;
            for (int v : probe) { pr += (v >> 16) & 255; pg += (v >> 8) & 255; pb += v & 255; }
            edge = Color.rgb((int) (pr / 4), (int) (pg / 4), (int) (pb / 4));
            float half = size / 2f;
            float ours = cornerGap(size);
            float theirs = k * s + size * 0.035f;   // + рамка темы
            if (theirs > ours) grow = Math.min(1.3f, (half - ours) / (half - theirs));
        }
        c.drawColor(edge);
        RectF dst = new RectF(0, 0, w * s * grow, h * s * grow);
        dst.offset((size - dst.width()) / 2f, (size - dst.height()) / 2f);
        c.drawBitmap(raw, new android.graphics.Rect(l, t, r + 1, btm + 1), dst, new Paint(Paint.FILTER_BITMAP_FLAG));
        raw.recycle();
    }

    /** На сколько по диагонали от угла квадрата отступает наша форма. */
    private static float cornerGap(int size) {
        return size / 2f * Math.max(0.04f, Math.min(1f, Prefs.iconRound)) * 0.2929f;
    }

    private Apps() {}
}
