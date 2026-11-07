package os.vanks.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.Drawable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Паки значков из Google Play в общем формате лаунчеров (ADW, Nova и др.):
 * у пака есть appfilter.xml — «ComponentInfo{пакет/активность} → картинка».
 * Для приложений, которых в паке нет, пак может дать подложку (iconback),
 * маску (iconmask), накладку (iconupon) и масштаб (scale) — тогда значок
 * собирается из них, иначе рисуется как обычно.
 */
final class IconPacks {
    private static final String[] ACTIONS = {
            "org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.teslacoilsw.launcher.THEME",
            "com.gau.go.launcherex.theme", "org.adw.launcher.icons.ACTION_PICK_ICON",
    };

    /** Установленные паки: пакет → название. */
    static LinkedHashMap<String, String> installed(Context c) {
        PackageManager pm = c.getPackageManager();
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (String a : ACTIONS) {
            List<ResolveInfo> l;
            try {
                l = pm.queryIntentActivities(new Intent(a), PackageManager.GET_META_DATA);
            } catch (RuntimeException e) {
                continue;
            }
            for (ResolveInfo ri : l) {
                String pkg = ri.activityInfo.packageName;
                if (!out.containsKey(pkg)) out.put(pkg, String.valueOf(ri.loadLabel(pm)));
            }
        }
        return out;
    }

    // ── загруженный пак ───────────────────────────────────────────────────

    private static String loaded;
    private static Resources res;
    private static final HashMap<String, String> map = new HashMap<>();
    private static final ArrayList<String> backs = new ArrayList<>();
    private static String maskName, uponName;
    private static float scale = 1f;

    /** Подготовить пак из Prefs.icons; false — пака нет (удалили). */
    static boolean prepare(Context c) {
        if (!Prefs.icons.startsWith("pack:")) return false;
        String pkg = Prefs.icons.substring(5);
        if (pkg.equals(loaded)) return res != null;
        loaded = pkg;
        res = null;
        map.clear();
        backs.clear();
        maskName = uponName = null;
        scale = 1f;
        try {
            res = c.getPackageManager().getResourcesForApplication(pkg);
            XmlPullParser p;
            int id = res.getIdentifier("appfilter", "xml", pkg);
            InputStream in = null;
            if (id != 0) p = res.getXml(id);
            else {
                in = res.getAssets().open("appfilter.xml");
                p = XmlPullParserFactory.newInstance().newPullParser();
                p.setInput(in, "UTF-8");
            }
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev != XmlPullParser.START_TAG) continue;
                switch (p.getName()) {
                    case "item": {
                        String comp = p.getAttributeValue(null, "component");
                        String dr = p.getAttributeValue(null, "drawable");
                        if (comp == null || dr == null) break;
                        // ComponentInfo{пакет/активность}
                        int a = comp.indexOf('{'), b = comp.lastIndexOf('}');
                        if (a >= 0 && b > a) comp = comp.substring(a + 1, b);
                        ComponentName cn = ComponentName.unflattenFromString(comp);
                        if (cn != null && !map.containsKey(cn.flattenToShortString())) map.put(cn.flattenToShortString(), dr);
                        break;
                    }
                    case "iconback":
                        for (int i = 0; i < p.getAttributeCount(); i++) backs.add(p.getAttributeValue(i));
                        break;
                    case "iconmask":
                        if (p.getAttributeCount() > 0) maskName = p.getAttributeValue(0);
                        break;
                    case "iconupon":
                        if (p.getAttributeCount() > 0) uponName = p.getAttributeValue(0);
                        break;
                    case "scale":
                        try { scale = Float.parseFloat(p.getAttributeValue(null, "factor")); } catch (Exception ignored) {}
                        break;
                }
            }
            if (in != null) in.close();
        } catch (Exception e) {
            res = null;
        }
        return res != null;
    }

    private static Drawable drawable(String name) {
        if (res == null || name == null) return null;
        int id = res.getIdentifier(name, "drawable", loaded);
        if (id == 0) id = res.getIdentifier(name, "mipmap", loaded);
        if (id == 0) return null;
        try {
            return res.getDrawableForDensity(id, Math.max(res.getDisplayMetrics().densityDpi, 480), null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Значок приложения из пака; null — в паке его нет и собрать не из чего. */
    static Bitmap icon(Apps.App a, int size) {
        if (res == null) return null;
        String name = map.get(a.key);
        if (name == null) {
            // запасной ключ: та же активность, записанная полным именем
            name = map.get(a.cn.getPackageName() + "/" + a.cn.getClassName());
        }
        Drawable d = drawable(name);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        if (d != null) {
            d.setBounds(0, 0, size, size);
            d.draw(c);
            return b;
        }
        if (backs.isEmpty() || Apps.own(a) == null) return null;
        // подложка пака + сам значок в масштабе пака, маска и накладка
        Drawable back = drawable(backs.get(Math.abs(a.key.hashCode()) % backs.size()));
        if (back != null) {
            back.setBounds(0, 0, size, size);
            back.draw(c);
        }
        Bitmap inner = Apps.plain(Apps.own(a), Math.round(size * scale));
        Bitmap layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas lc = new Canvas(layer);
        lc.drawBitmap(inner, (size - inner.getWidth()) / 2f, (size - inner.getHeight()) / 2f, new Paint(Paint.FILTER_BITMAP_FLAG));
        Drawable mask = drawable(maskName);
        if (mask != null) {
            Bitmap mb = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            mask.setBounds(0, 0, size, size);
            mask.draw(new Canvas(mb));
            Paint mp = new Paint(Paint.FILTER_BITMAP_FLAG);
            mp.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
            lc.drawBitmap(mb, 0, 0, mp);
        }
        c.drawBitmap(layer, 0, 0, null);
        Drawable upon = drawable(uponName);
        if (upon != null) {
            upon.setBounds(0, 0, size, size);
            upon.draw(c);
        }
        return b;
    }

    private IconPacks() {}
}
