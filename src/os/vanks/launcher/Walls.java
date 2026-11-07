package os.vanks.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Обои: фото и обои Delta. Супер обои (SHADER) — издалека на экране входа, при
 * входе камера летит к ним. Живые (FLOW) — при входе доигрывают анимацию и
 * замирают. Считает их видеокарта (AGSL, Android 13+); на старых Android и
 * для стекла — готовый кадр (tools/walls.py).
 */
final class Walls {
    static final int PHOTO = 0, FLOW = 1, SHADER = 2;

    static final class W {
        final String id, group, title, frag;
        final int kind, material, still, blur;
        final int[] colors;
        int fg;   // передний план для эффекта глубины (только фото «Вершина»)

        W(String id, int kind, String group, String title, String frag, int[] colors, int material, int still, int blur) {
            this.id = id;
            this.kind = kind;
            this.group = group;
            this.title = title;
            this.frag = frag;
            this.colors = colors;
            this.material = material;
            this.still = still;
            this.blur = blur;
        }
    }

    static final W[] PHOTOS = {
            photo("peak", "Вершина", R.drawable.w_peak, R.drawable.w_peak_blur, R.drawable.w_peak_fg),
            photo("mountains", "Горы на закате", R.drawable.w_mountains, R.drawable.w_mountains_blur, 0),
            photo("violet", "Фиолетовый поток", R.drawable.w_violet, R.drawable.w_violet_blur, 0),
            photo("glass", "Ночное стекло", R.drawable.w_glass, R.drawable.w_glass_blur, 0),
            photo("silk", "Шёлк", R.drawable.w_silk, R.drawable.w_silk_blur, 0),
            photo("moon", "Луна", R.drawable.w_moon, R.drawable.w_moon_blur, 0),
    };

    private static W photo(String id, String title, int still, int blur, int fg) {
        W w = new W(id, PHOTO, "Фото", title, null, null, 0, still, blur);
        w.fg = fg;
        return w;
    }

    static List<W> all() {
        ArrayList<W> out = new ArrayList<>();
        for (W w : WallList.DELTA) if (w.kind == SHADER) out.add(w);
        for (W w : WallList.DELTA) if (w.kind == FLOW) out.add(w);
        for (W w : PHOTOS) out.add(w);
        return out;
    }

    /** По группам, в порядке списка: «Супер обои», «Круги», …, «Фото». */
    static LinkedHashMap<String, List<W>> groups() {
        LinkedHashMap<String, List<W>> g = new LinkedHashMap<>();
        for (W w : all()) g.computeIfAbsent(w.group, k -> new ArrayList<>()).add(w);
        return g;
    }

    static W find(String id) {
        for (W w : all()) if (w.id.equals(id)) return w;
        return PHOTOS[0];
    }

    static W current() {
        return find(Prefs.wallpaper);
    }

    static boolean animated(W w) {
        return w.kind != PHOTO && android.os.Build.VERSION.SDK_INT >= 33;
    }

    private static final HashMap<String, String> sources = new HashMap<>();

    /** Текст шейдера из res/raw/wall_<frag>.agsl. */
    static String source(Context c, String frag) {
        return raw(c, "wall_" + frag);
    }

    /** Текст шейдера из res/raw по имени ресурса (кэшируется). */
    static String raw(Context c, String name) {
        String s = sources.get(name);
        if (s != null) return s;
        int id = c.getResources().getIdentifier(name, "raw", c.getPackageName());
        if (id == 0) return null;
        try (InputStream in = c.getResources().openRawResource(id)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            s = b.toString("UTF-8");
            sources.put(name, s);
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    private static final HashMap<Integer, Bitmap> textures = new HashMap<>();

    static Bitmap texture(Context c, int res) {
        Bitmap b = textures.get(res);
        if (b == null) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            b = BitmapFactory.decodeResource(c.getResources(), res, o);
            textures.put(res, b);
        }
        return b;
    }

    private Walls() {}
}
