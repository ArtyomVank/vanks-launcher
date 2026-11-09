package os.vanks.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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
    /** Что делают свои обои: стоят; идут по кругу; доигрывают при входе; камера летит к ним при входе. */
    static final int STILL = 0, LOOP = 1, LIVE = 2, SUPER = 3;

    static final class W {
        final String id, group, title, frag;
        final int kind, material, still, blur;
        final int[] colors;
        int fg;   // передний план для эффекта глубины (только фото «Вершина»)
        int effect;   // свои обои: STILL, LOOP, LIVE, SUPER
        int poster;   // живые из видео: первый кадр для экрана входа (still — последний)
        String clip;  // файл видео или GIF
        int mineId;   // номер своих обоев, 0 — встроенные

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
        ArrayList<W> out;
        synchronized (Walls.class) {
            out = new ArrayList<>(mine);
        }
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

    // ── свои обои ──────────────────────────────────────────────────────────

    static final String MINE_GROUP = "Мои обои";
    private static final long MAX_CLIP = 300L * 1024 * 1024;
    private static final ArrayList<W> mine = new ArrayList<>();
    /** Отрицательные «номера ресурсов» — файлы своих обоев. */
    private static final HashMap<Integer, File> files = new HashMap<>();
    private static boolean mineLoaded;

    private static File dir(Context c) {
        return new File(c.getFilesDir(), "walls");
    }

    private static String effectName(int effect) {
        return effect == LOOP ? "Анимация" : effect == LIVE ? "Живые" : effect == SUPER ? "Супер" : "Фото";
    }

    /** Подпись вида обоев для «Настроек». */
    static String kindName(W w) {
        if (w.mineId != 0) return effect(w) + " · мои";
        return w.kind == SHADER ? "Супер обои" : w.kind == FLOW ? "Живые обои" : "Фото";
    }

    private static String effect(W w) {
        return w.effect == LOOP ? "Анимированные" : w.effect == LIVE ? "Живые" : w.effect == SUPER ? "Супер" : "Фото";
    }

    /** Картинка обоев: из ресурсов или из файла своих. */
    static Bitmap decode(Context c, int res, BitmapFactory.Options o) {
        if (res >= 0) return BitmapFactory.decodeResource(c.getResources(), res, o);
        File f = files.get(res);
        return f == null ? null : BitmapFactory.decodeFile(f.getPath(), o);
    }

    private static int resId(File f, int n, int slot) {
        int id = -(n * 3 + slot);
        files.put(id, f);
        return id;
    }

    /** Запись в prefs → W; null, если файлов уже нет. */
    private static W make(Context c, JSONObject j) {
        int n = j.optInt("n"), effect = j.optInt("effect");
        String ext = j.optString("ext");
        File d = dir(c), still = new File(d, "w" + n + ".jpg"), blur = new File(d, "w" + n + "_b.jpg"), poster = new File(d, "w" + n + "_p.jpg");
        File clip = ext.isEmpty() ? null : new File(d, "w" + n + "." + ext);
        if (!still.exists() || !blur.exists() || (clip != null && !clip.exists())) return null;
        W w = new W("my" + n, PHOTO, MINE_GROUP, j.optString("title"), null, null, 0, resId(still, n, 0), resId(blur, n, 1));
        w.effect = effect;
        w.mineId = n;
        if (clip != null) w.clip = clip.getPath();
        if (poster.exists()) w.poster = resId(poster, n, 2);
        return w;
    }

    static synchronized void loadMine(Context c) {
        if (mineLoaded) return;
        mineLoaded = true;
        try {
            JSONArray a = new JSONArray(Prefs.raw().getString("myWalls", "[]"));
            for (int i = 0; i < a.length(); i++) {
                W w = make(c, a.getJSONObject(i));
                if (w != null) mine.add(w);
            }
        } catch (Exception ignored) {
        }
    }

    private static void saveMine() {
        JSONArray a = new JSONArray();
        try {
            for (W w : mine) {
                String ext = w.clip == null ? "" : w.clip.substring(w.clip.lastIndexOf('.') + 1);
                a.put(new JSONObject().put("n", w.mineId).put("effect", w.effect).put("ext", ext).put("title", w.title));
            }
        } catch (Exception ignored) {
        }
        Prefs.raw().edit().putString("myWalls", a.toString()).apply();
    }

    /**
     * Добавить свои обои из галереи (фоном, файл может быть большим): копия
     * видео или GIF, кадр на экран и размытая копия для стекла. Для живых из
     * видео и GIF кадров два: на экране входа — первый, на столе — последний.
     */
    static W importWall(Context c, Uri uri, String mime, int effect) throws IOException {
        File d = dir(c);
        d.mkdirs();
        int n;
        synchronized (Walls.class) {
            n = Prefs.raw().getInt("myWallsNext", 1);
            Prefs.raw().edit().putInt("myWallsNext", n + 1).apply();
        }
        String ext = mime.startsWith("video/") ? "mp4" : mime.equals("image/gif") && effect != STILL ? "gif" : "";
        File clip = ext.isEmpty() ? null : new File(d, "w" + n + "." + ext);
        Bitmap first = null, last = null;
        try {
            if (clip != null) copy(c, uri, clip);
            if (mime.startsWith("video/")) {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(clip.getPath());
                    first = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (effect == LIVE) {
                        String ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                        long end = Math.max(0, (ms == null ? 0 : Long.parseLong(ms)) - 60) * 1000;
                        last = r.getFrameAtTime(end, MediaMetadataRetriever.OPTION_CLOSEST);
                    }
                } finally {
                    r.release();
                }
                first = fit(c, first);
                last = fit(c, last);
            } else {
                first = picture(c, uri);
                if (clip != null && effect == LIVE) last = lastGifFrame(c, clip);
            }
            if (first == null) throw new IOException("нет кадра");
            Bitmap still = last != null ? last : first;
            write(still, new File(d, "w" + n + ".jpg"), 90);
            write(blur(still), new File(d, "w" + n + "_b.jpg"), 85);
            if (last != null) write(first, new File(d, "w" + n + "_p.jpg"), 90);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            forget(c, n);
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException(e);
        }
        synchronized (Walls.class) {
            int same = 1;
            for (W w : mine) if (w.effect == effect) same++;
            JSONObject j;
            try {
                j = new JSONObject().put("n", n).put("effect", effect).put("ext", ext).put("title", effectName(effect) + " " + same);
            } catch (Exception e) {
                throw new IOException(e);
            }
            W w = make(c, j);
            if (w == null) throw new IOException("файл не записан");
            mine.add(0, w);
            saveMine();
            return w;
        }
    }

    static synchronized void remove(Context c, W w) {
        mine.remove(w);
        saveMine();
        forget(c, w.mineId);
    }

    private static void forget(Context c, int n) {
        File[] all = dir(c).listFiles();
        if (all == null) return;
        String a = "w" + n + ".", b = "w" + n + "_";
        for (File f : all) if (f.getName().startsWith(a) || f.getName().startsWith(b)) f.delete();
    }

    private static void copy(Context c, Uri uri, File to) throws IOException {
        long total = 0;
        try (InputStream in = c.getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(to)) {
            if (in == null) throw new IOException("не открылось");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_CLIP) throw new TooBig();
                out.write(buf, 0, n);
            }
        }
    }

    static final class TooBig extends IOException {}

    private static int[] screen(Context c) {
        android.util.DisplayMetrics m = c.getResources().getDisplayMetrics();
        return new int[]{Math.min(m.widthPixels, m.heightPixels), Math.max(m.widthPixels, m.heightPixels)};
    }

    /** Снимок по размеру экрана: с учётом поворота из EXIF и с запасом на приближение. */
    private static Bitmap picture(Context c, Uri uri) throws IOException {
        int[] s = screen(c);
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.getContentResolver(), uri), (d, info, src) -> {
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            float k = Math.min(1f, Math.max(s[0] / (float) w, s[1] / (float) h) * 1.25f);
            d.setTargetSize(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)));
            d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
    }

    private static Bitmap fit(Context c, Bitmap b) {
        if (b == null) return null;
        int[] s = screen(c);
        float k = Math.max(s[0] / (float) b.getWidth(), s[1] / (float) b.getHeight()) * 1.25f;
        if (k >= 1f) return b;
        return Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true);
    }

    @SuppressWarnings("deprecation")
    private static Bitmap lastGifFrame(Context c, File gif) {
        android.graphics.Movie m = android.graphics.Movie.decodeFile(gif.getPath());
        if (m == null || m.width() <= 0 || m.height() <= 0) return null;
        Bitmap b = Bitmap.createBitmap(m.width(), m.height(), Bitmap.Config.ARGB_8888);
        m.setTime(Math.max(0, m.duration() - 1));
        m.draw(new Canvas(b), 0, 0);
        return fit(c, b);
    }

    private static void write(Bitmap b, File f, int q) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.JPEG, q, out);
        }
    }

    /** Матовая копия для стекла: маленькая, три прохода размытия. */
    private static Bitmap blur(Bitmap src) {
        int w = 240, h = Math.max(1, Math.round(240f * src.getHeight() / src.getWidth()));
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        android.graphics.Rect dst = new android.graphics.Rect(0, 0, w, h);
        new Canvas(out).drawBitmap(src, null, dst, p);
        int[] a = new int[w * h], t = new int[w * h];
        out.getPixels(a, 0, w, 0, 0, w, h);
        for (int i = 0; i < 3; i++) {
            box(a, t, w, h, 1, w, 14);
            box(t, a, h, w, w, 1, 14);
        }
        out.setPixels(a, 0, w, 0, 0, w, h);
        return out;
    }

    /** Один проход скользящего среднего вдоль строк (step = 1) или столбцов (step = ширина). */
    private static void box(int[] from, int[] to, int len, int lines, int step, int lineStep, int r) {
        int div = r * 2 + 1;
        for (int l = 0; l < lines; l++) {
            int base = l * lineStep, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = from[base + Math.max(0, Math.min(len - 1, i)) * step];
                sr += (c >> 16) & 255;
                sg += (c >> 8) & 255;
                sb += c & 255;
            }
            for (int i = 0; i < len; i++) {
                to[base + i * step] = 0xFF000000 | (sr / div) << 16 | (sg / div) << 8 | (sb / div);
                int in = from[base + Math.min(len - 1, i + r + 1) * step], out = from[base + Math.max(0, i - r) * step];
                sr += ((in >> 16) & 255) - ((out >> 16) & 255);
                sg += ((in >> 8) & 255) - ((out >> 8) & 255);
                sb += (in & 255) - (out & 255);
            }
        }
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
