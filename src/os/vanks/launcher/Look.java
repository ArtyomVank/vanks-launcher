package os.vanks.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/**
 * Три уровня оформления, как в Delta (Настройки → Стили и темы):
 *   стиль — устройство интерфейса: «Акрил» (круглые кнопки, ползунки-капсулы,
 *           моноширинные цифры) или Delta (плитки с подписями);
 *   тема  — материал панелей: акриловое стекло, Delta, жидкое стекло, GNOME,
 *           Material You, Nothing;
 *   цвет  — цветокор: оттенок поверхностей и акцент.
 */
final class Look {
    // ── стиль ─────────────────────────────────────────────────────────────
    static final String[] UI_IDS = {"acrylic", "delta"};
    static final String[] UI_NAMES = {"Акрил", "Delta"};
    static final String[] UI_NOTES = {"Круглые кнопки, ползунки-капсулы, моноширинные цифры", "Плитки с подписями, как на компьютере"};

    // ── тема ──────────────────────────────────────────────────────────────
    static final String[] MAT_IDS = {"acrylic", "delta", "liquid", "gnome", "material", "nothing"};
    static final String[] MAT_NAMES = {"Акриловое стекло", "Delta", "Жидкое стекло", "GNOME", "Material You", "Nothing"};
    static final String[] MAT_NOTES = {"Дымка и светящийся контур", "Тонированное стекло", "Стекло-линза с бликом по краю",
            "Сплошные панели без прозрачности", "Плотные панели, цвета от темы", "Монохром, тонкие рамки, красный акцент"};

    // ── цвет ──────────────────────────────────────────────────────────────
    static final class Tone {
        final String id, name;
        final int surface, accent;   // accent 0 — свой не задан
        final boolean light, wall;

        Tone(String id, String name, int r, int g, int b, int accent, boolean light, boolean wall) {
            this.id = id;
            this.name = name;
            this.surface = Color.rgb(r, g, b);
            this.accent = accent;
            this.light = light;
            this.wall = wall;
        }
    }

    static final Tone[] TONES = {
            new Tone("midnight", "Полночь", 20, 15, 38, 0, false, false),
            new Tone("black", "Чёрная", 0, 0, 0, 0, false, false),
            new Tone("graphite", "Графит", 24, 25, 30, 0, false, false),
            new Tone("storm", "Шторм", 28, 34, 44, 0, false, false),
            new Tone("ocean", "Океан", 10, 28, 46, 0, false, false),
            new Tone("sapphire", "Сапфир", 10, 24, 66, 0, false, false),
            new Tone("indigo", "Индиго", 24, 20, 64, 0, false, false),
            new Tone("lilac", "Сирень", 44, 26, 60, 0, false, false),
            new Tone("wine", "Вино", 44, 12, 28, 0, false, false),
            new Tone("bordeaux", "Бордо", 54, 10, 16, 0, false, false),
            new Tone("cocoa", "Какао", 38, 26, 18, 0, false, false),
            new Tone("forest", "Лес", 12, 32, 25, 0, false, false),
            new Tone("emerald", "Изумруд", 6, 42, 36, 0, false, false),
            new Tone("pine", "Хвоя", 18, 36, 14, 0, false, false),
            new Tone("aurora", "Сияние", 8, 30, 38, 0xFF3EE6B0, false, false),
            new Tone("sunset", "Закат", 46, 18, 34, 0xFFFF8A4C, false, false),
            new Tone("neon", "Неон", 6, 6, 14, 0xFF00E5FF, false, false),
            new Tone("amber", "Янтарь", 34, 24, 10, 0xFFFFB020, false, false),
            new Tone("cosmos", "Космос", 14, 10, 30, 0xFFB07CFF, false, false),
            new Tone("matrix", "Терминал", 4, 14, 6, 0xFF39D353, false, false),
            new Tone("rose", "Роза", 40, 14, 24, 0xFFFF5C8A, false, false),
            new Tone("slate", "Сланец", 30, 34, 40, 0xFF7AA2F7, false, false),
            new Tone("white", "Белая", 247, 246, 251, 0, true, false),
            new Tone("sand", "Песок", 245, 238, 226, 0, true, false),
            new Tone("mint", "Мята", 228, 245, 238, 0xFF1FA67A, true, false),
            new Tone("lavender", "Лаванда", 238, 234, 252, 0xFF7C5CFF, true, false),
            new Tone("sakura", "Сакура", 252, 236, 242, 0xFFE0567F, true, false),
            new Tone("sky", "Небо", 230, 241, 252, 0xFF2F80ED, true, false),
            new Tone("wallpaper", "Цвет обоев", 0, 0, 0, 0, false, true),
            new Tone("wallpaper-light", "Цвет обоев, светлая", 0, 0, 0, 0, true, true),
    };

    /** Главный цвет обоев (для тем «Цвет обоев»), считается при смене обоев. */
    static int wallSeed = 0xFF7C5CFF;

    static Tone tone() {
        for (Tone t : TONES) if (t.id.equals(Prefs.tone)) return t;
        return TONES[2];
    }

    static boolean light() { return tone().light; }

    static boolean acrylicUi() { return !"delta".equals(Prefs.ui); }

    static String mat() {
        for (String m : MAT_IDS) if (m.equals(Prefs.material)) return m;
        return "acrylic";
    }

    static boolean is(String material) { return mat().equals(material); }

    /** Цвет поверхностей: у «Цвета обоев» — из главного цвета обоев. */
    static int surface() {
        Tone t = tone();
        if (!t.wall) return t.surface;
        float[] h = hsl(wallSeed);
        float sat = Math.max(0.35f, Math.min(0.9f, h[1]));
        return t.light ? fromHsl(h[0], Math.min(sat, 0.6f) * 0.5f, 0.94f) : fromHsl(h[0], Math.min(sat, 0.5f) * 0.42f, 0.13f);
    }

    /** Акцент, который тема цвета ставит при выборе (0 — не ставит). */
    static int toneAccent(Tone t) {
        if (!t.wall) return t.accent;
        float[] h = hsl(wallSeed);
        float sat = Math.max(0.35f, Math.min(0.9f, h[1]));
        return t.light ? fromHsl(h[0], Math.max(0.5f, sat * 0.8f), 0.40f) : fromHsl(h[0], Math.max(0.55f, sat * 0.85f), 0.76f);
    }

    static int fg() { return light() ? 0xFF16171D : 0xFFFFFFFF; }

    static int fg2() { return light() ? 0x9916171D : 0xB3FFFFFF; }

    /** Скругление панелей у темы (база — как у Delta: 18). */
    static float radius(float baseDp) {
        float k;
        switch (mat()) {
            case "acrylic": k = 26f / 18f; break;
            case "liquid": k = 30f / 18f; break;
            case "gnome": k = 12f / 18f; break;
            case "material": k = 28f / 18f; break;
            case "nothing": k = 20f / 18f; break;
            default: k = 1f;
        }
        return Ui.dpf(baseDp * k);
    }

    /**
     * Часы по умолчанию. Стиль и тема меняют интерфейс системы (настройки,
     * пункт управления, меню, папки, поиск), а часы и док — нет: их вид
     * выбирается сам по себе.
     */
    static String clock() {
        return "tall";
    }

    private static Typeface mono;

    /** Цифры: у «Акрила» моноширинные. */
    static Typeface digits(Typeface normal) {
        if (!acrylicUi()) return normal;
        if (mono == null) mono = Typeface.create(Typeface.MONOSPACE, 500, false);
        return mono;
    }

    /** Выбрать тему: у Nothing — красный акцент, как в Delta. */
    static void applyMaterial(String id) {
        Prefs.material = id;
        if (id.equals("nothing")) Prefs.accent = 0xFFD71921;
    }

    static void applyTone(Tone t) {
        Prefs.tone = t.id;
        int a = toneAccent(t);
        if (a != 0) Prefs.accent = a;
    }

    // ── рисование панелей ─────────────────────────────────────────────────

    private static final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF tmp = new RectF();

    /**
     * Панель (док, «Поиск», папки): под ней — то, что даёт тема. sx, sy — её
     * левый верхний угол на экране (для стекла).
     */
    static void panel(Canvas c, RectF r, float radius, float sx, float sy, int sw, int sh) {
        int s = surface();
        boolean lt = light();
        p.reset();
        p.setAntiAlias(true);
        switch (mat()) {
            case "gnome":
                p.setColor(lt ? Ui.mix(s, Color.BLACK, 0.04f) : Ui.mix(s, Color.WHITE, 0.07f));
                c.drawRoundRect(r, radius, radius, p);
                return;
            case "material":
                p.setColor(Ui.alpha(Ui.mix(s, Ui.accent(), lt ? 0.16f : 0.12f), 0.97f));
                c.drawRoundRect(r, radius, radius, p);
                return;
            case "nothing":
                // Nothing: дымчатое чёрное стекло с тонкой белой кромкой, без цвета
                Ui.glass(c, r, radius, sx, sy, sw, sh, lt ? 0xCCFFFFFF : 0xA6000000);
                stroke(c, r, radius, lt ? 0x33000000 : 0x40FFFFFF, Ui.dpf(1));
                return;
            case "liquid":
                Ui.glass(c, r, radius, sx, sy, sw, sh, Ui.alpha(s, lt ? 0.30f : 0.18f));
                // блик по краю линзы: сверху ярче, снизу едва
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.dpf(1.4f));
                p.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xB3FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
                tmp.set(r);
                tmp.inset(Ui.dpf(0.7f), Ui.dpf(0.7f));
                c.drawRoundRect(tmp, radius, radius, p);
                p.setShader(null);
                p.setStyle(Paint.Style.FILL);
                p.setShader(new LinearGradient(0, r.top, 0, r.top + r.height() * 0.5f, 0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                c.drawRoundRect(r, radius, radius, p);
                p.setShader(null);
                return;
            case "acrylic":
                Ui.glass(c, r, radius, sx, sy, sw, sh, Ui.alpha(s, lt ? 0.55f : 0.42f));
                // дымка
                p.setColor(lt ? 0x1AFFFFFF : 0x12FFFFFF);
                c.drawRoundRect(r, radius, radius, p);
                contour(c, r, radius);
                return;
            default:
                Ui.glass(c, r, radius, sx, sy, sw, sh, Ui.alpha(s, lt ? 0.62f : 0.5f));
                stroke(c, r, radius, lt ? 0x26000000 : 0x33FFFFFF, Ui.dpf(1));
        }
    }

    /** Светящийся контур акрила: свечение акцентом и тонкая кромка-градиент. */
    static void contour(Canvas c, RectF r, float radius) {
        int a = Ui.accent();
        p.reset();
        p.setAntiAlias(true);
        p.setStyle(Paint.Style.STROKE);
        for (int i = 3; i >= 1; i--) {
            p.setStrokeWidth(Ui.dpf(1.2f + i * 1.6f));
            p.setColor(Ui.alpha(a, 0.05f * (4 - i)));
            c.drawRoundRect(r, radius, radius, p);
        }
        p.setStrokeWidth(Ui.dpf(1.2f));
        p.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                new int[]{Ui.alpha(Ui.accentLight(), 0.95f), 0x40FFFFFF, Ui.alpha(a, 0.7f)}, null, Shader.TileMode.CLAMP));
        tmp.set(r);
        tmp.inset(Ui.dpf(0.6f), Ui.dpf(0.6f));
        c.drawRoundRect(tmp, radius, radius, p);
        p.setShader(null);
    }

    private static void stroke(Canvas c, RectF r, float radius, int color, float w) {
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
        p.setColor(color);
        tmp.set(r);
        tmp.inset(w / 2, w / 2);
        c.drawRoundRect(tmp, radius, radius, p);
        p.setStyle(Paint.Style.FILL);
    }

    /** Подложка во весь экран под пунктом управления и поиском. k — 0…1. */
    static void backdrop(Canvas c, int w, int h, float k, android.graphics.Matrix m, Paint bp) {
        int s = surface();
        boolean solid = is("gnome") || is("material") || is("nothing");
        if (!solid) {
            bp.setAlpha(Math.round(255 * k));
            c.drawBitmap(Ui.wallBlur, Ui.wallMatrix(Ui.wallBlur, w, h, 1f, m), bp);
            c.drawColor(Ui.alpha(light() ? Ui.mix(s, Color.WHITE, 0.5f) : Ui.mix(s, 0xFF070A18, 0.5f), (light() ? 0.5f : 0.45f) * k));
        } else {
            int col = is("nothing") ? (light() ? 0xFFF4F4F4 : 0xFF000000) : is("material") ? Ui.mix(s, Ui.accent(), 0.06f) : s;
            c.drawColor(Ui.alpha(col, 0.96f * k));
        }
    }

    /** Плитка пункта управления (фон уже матовый). */
    static void tile(Canvas c, RectF r, float radius, boolean on, int onColor, boolean circle) {
        boolean lt = light();
        int fill;
        switch (mat()) {
            case "gnome": fill = lt ? Ui.mix(surface(), Color.BLACK, 0.06f) : Ui.mix(surface(), Color.WHITE, 0.1f); break;
            case "material": fill = Ui.mix(surface(), Ui.accent(), lt ? 0.22f : 0.18f); break;
            case "nothing": fill = lt ? 0xFFFFFFFF : 0xFF111111; break;
            default: fill = lt ? 0x66FFFFFF : 0x29FFFFFF;
        }
        p.reset();
        p.setAntiAlias(true);
        p.setColor(on ? onColor : fill);
        if (circle) c.drawCircle(r.centerX(), r.centerY(), r.width() / 2, p);
        else c.drawRoundRect(r, radius, radius, p);
        p.setStyle(Paint.Style.STROKE);
        if (is("acrylic") && on) {
            // включённое светится
            p.setStrokeWidth(Ui.dpf(5));
            p.setColor(Ui.alpha(onColor, 0.28f));
        } else if (is("nothing")) {
            p.setStrokeWidth(Ui.dpf(1));
            p.setColor(lt ? 0x33000000 : 0x40FFFFFF);
        } else {
            p.setStrokeWidth(Ui.dpf(1));
            p.setColor(is("gnome") || is("material") ? 0 : (lt ? 0x1F000000 : 0x26FFFFFF));
        }
        if (circle) c.drawCircle(r.centerX(), r.centerY(), r.width() / 2, p);
        else c.drawRoundRect(r, radius, radius, p);
        p.setStyle(Paint.Style.FILL);
    }

    // ── «системные приложения»: фон страниц и коробки групп ───────────────

    /** Тема со стеклом: под страницами — размытые обои. */
    static boolean glassy() {
        return is("acrylic") || is("liquid") || is("delta");
    }

    /** Фон страницы «Настроек»: стекло из обоев или сплошной цвет темы. */
    static android.graphics.drawable.Drawable pageBackground(int solid) {
        return new android.graphics.drawable.Drawable() {
            private final android.graphics.Matrix m = new android.graphics.Matrix();
            private final Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

            @Override
            public void draw(Canvas c) {
                android.graphics.Rect b = getBounds();
                if (glassy() && Ui.wallBlur != null) {
                    android.util.DisplayMetrics dm = android.content.res.Resources.getSystem().getDisplayMetrics();
                    c.save();
                    c.clipRect(b);
                    c.drawBitmap(Ui.wallBlur, Ui.wallMatrix(Ui.wallBlur, Math.max(b.width(), dm.widthPixels), Math.max(b.height(), dm.heightPixels), 1f, m), bp);
                    switch (mat()) {
                        case "liquid":
                            // жидкое стекло: обои почти не затемнены, сверху — блик
                            c.drawColor(Ui.alpha(Ui.mix(surface(), 0xFF05060C, 0.5f), 0.3f));
                            bp.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * 0.35f, 0x26FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                            c.drawRect(b, bp);
                            bp.setShader(null);
                            break;
                        case "acrylic":
                            // акрил: густая дымка и свечение акцентом сверху
                            c.drawColor(Ui.alpha(Ui.mix(surface(), 0xFF05060C, 0.6f), 0.6f));
                            bp.setShader(new android.graphics.RadialGradient(b.width() * 0.5f, b.top, b.width() * 0.9f,
                                    Ui.alpha(Ui.accent(), 0.30f), 0x00000000, Shader.TileMode.CLAMP));
                            c.drawRect(b, bp);
                            bp.setShader(null);
                            c.drawColor(0x0FFFFFFF);
                            break;
                        default:   // delta: тонированное стекло цвета темы
                            c.drawColor(Ui.alpha(Ui.mix(surface(), 0xFF05060C, 0.35f), 0.72f));
                    }
                    c.restore();
                } else if (is("nothing")) {
                    c.drawColor(0xFF000000);
                    // точечная сетка Nothing
                    bp.setColor(0x1AFFFFFF);
                    float st = Ui.dpf(18);
                    for (float y = b.top + st / 2; y < b.bottom; y += st)
                        for (float x = b.left + st / 2; x < b.right; x += st) c.drawCircle(x, y, Ui.dpf(0.9f), bp);
                } else if (is("material")) c.drawColor(Ui.mix(solid, Ui.accent(), 0.08f));
                else c.drawColor(solid);
            }

            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(android.graphics.ColorFilter f) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
        };
    }

    /** Коробка группы строк в «Настройках» — материалом темы. */
    static android.graphics.drawable.Drawable box(int solid) {
        return new android.graphics.drawable.Drawable() {
            private final RectF r = new RectF();
            private final Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            public void draw(Canvas c) {
                r.set(getBounds());
                float rad = Math.min(radius(12), r.height() / 2);
                q.reset();
                q.setAntiAlias(true);
                switch (mat()) {
                    case "acrylic":
                        q.setColor(Ui.alpha(Ui.mix(surface(), 0xFFFFFFFF, 0.06f), 0.62f));
                        c.drawRoundRect(r, rad, rad, q);
                        contour(c, r, rad);
                        break;
                    case "liquid":
                        // линза: почти прозрачная, с бликом сверху и яркой кромкой
                        q.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0x2EFFFFFF, 0x0AFFFFFF, Shader.TileMode.CLAMP));
                        c.drawRoundRect(r, rad, rad, q);
                        q.setStyle(Paint.Style.STROKE);
                        q.setStrokeWidth(Ui.dpf(1.5f));
                        q.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, new int[]{0xD9FFFFFF, 0x1AFFFFFF, 0x66FFFFFF}, null, Shader.TileMode.CLAMP));
                        r.inset(Ui.dpf(0.75f), Ui.dpf(0.75f));
                        c.drawRoundRect(r, rad, rad, q);
                        break;
                    case "material":
                        q.setColor(Ui.mix(solid, Ui.accent(), 0.14f));
                        c.drawRoundRect(r, rad, rad, q);
                        break;
                    case "nothing":
                        q.setColor(0xFF0E0E0E);
                        c.drawRoundRect(r, rad, rad, q);
                        q.setStyle(Paint.Style.STROKE);
                        q.setStrokeWidth(Ui.dpf(1));
                        q.setColor(0x40FFFFFF);
                        r.inset(Ui.dpf(0.5f), Ui.dpf(0.5f));
                        c.drawRoundRect(r, rad, rad, q);
                        break;
                    case "gnome":
                        q.setColor(solid);
                        c.drawRoundRect(r, rad, rad, q);
                        break;
                    default:   // delta: тонированное стекло с кромкой
                        q.setColor(Ui.alpha(solid, 0.72f));
                        c.drawRoundRect(r, rad, rad, q);
                        q.setStyle(Paint.Style.STROKE);
                        q.setStrokeWidth(Ui.dpf(1));
                        q.setColor(0x30FFFFFF);
                        r.inset(Ui.dpf(0.5f), Ui.dpf(0.5f));
                        c.drawRoundRect(r, rad, rad, q);
                }
            }

            @Override
            public void getOutline(android.graphics.Outline o) {
                o.setRoundRect(getBounds(), radius(12));
            }

            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(android.graphics.ColorFilter f) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

    /**
     * Значок строки «Настроек». Стиль задаёт форму (Акрил — круг, Delta —
     * скруглённый квадрат), тема — материал: акрил светится, жидкое стекло —
     * прозрачная линза, GNOME — плоский значок без подложки, Material —
     * тональная подложка, Nothing — белый контур.
     */
    static void rowIcon(Canvas c, float cx, float cy, float s, int color, SettingsIcon icon) {
        boolean circle = acrylicUi();
        RectF r = new RectF(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
        float rad = circle ? s / 2 : s * 0.25f;
        p.reset();
        p.setAntiAlias(true);
        int fg = 0xFFFFFFFF;
        switch (mat()) {
            case "acrylic":
                p.setColor(Ui.alpha(color, 0.30f));
                c.drawRoundRect(grow(r, Ui.dpf(3)), rad + Ui.dpf(3), rad + Ui.dpf(3), p);
                p.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, Ui.mix(color, Color.WHITE, 0.25f), Ui.mix(color, Color.BLACK, 0.15f), Shader.TileMode.CLAMP));
                c.drawRoundRect(r, rad, rad, p);
                p.setShader(null);
                break;
            case "liquid":
                p.setColor(Ui.alpha(color, 0.35f));
                c.drawRoundRect(r, rad, rad, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.dpf(1.2f));
                p.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xCCFFFFFF, 0x1AFFFFFF, Shader.TileMode.CLAMP));
                c.drawRoundRect(r, rad, rad, p);
                p.setShader(null);
                p.setStyle(Paint.Style.FILL);
                break;
            case "gnome":
                fg = Ui.mix(color, Color.WHITE, 0.2f);
                break;
            case "material":
                p.setColor(Ui.mix(surface(), color, 0.38f));
                c.drawRoundRect(r, circle ? rad : s * 0.32f, circle ? rad : s * 0.32f, p);
                fg = Ui.mix(color, Color.WHITE, 0.55f);
                break;
            case "nothing":
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.dpf(1.2f));
                p.setColor(0xB3FFFFFF);
                c.drawRoundRect(r, rad, rad, p);
                p.setStyle(Paint.Style.FILL);
                break;
            default:
                p.setColor(color);
                c.drawRoundRect(r, rad, rad, p);
        }
        icon.draw(c, cx, cy, s * (is("gnome") ? 0.8f : 0.72f), fg);
    }

    interface SettingsIcon { void draw(Canvas c, float cx, float cy, float s, int color); }

    private static RectF grow(RectF r, float d) {
        return new RectF(r.left - d, r.top - d, r.right + d, r.bottom + d);
    }

    /** Переключатель: у каждой темы свой. k — 0…1 (выключен…включён). */
    static void toggle(Canvas c, float x, float y, float w, float h, float k) {
        int a = Ui.accent();
        p.reset();
        p.setAntiAlias(true);
        float kx = x + h / 2 + (w - h) * k, ky = y + h / 2, kr = h / 2 - Ui.dpf(2);
        switch (mat()) {
            case "nothing":
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.dpf(1.2f));
                p.setColor(0x99FFFFFF);
                c.drawRoundRect(x, y, x + w, y + h, h / 2, h / 2, p);
                p.setStyle(Paint.Style.FILL);
                if (k > 0.01f) {
                    p.setColor(Ui.alpha(0xFFFFFFFF, k));
                    c.drawRoundRect(x, y, x + w, y + h, h / 2, h / 2, p);
                }
                p.setColor(Ui.mix(0xFFFFFFFF, 0xFFD71921, k));
                c.drawCircle(kx, ky, kr - Ui.dpf(2), p);
                return;
            case "material":
                p.setColor(Ui.mix(Ui.mix(surface(), Color.WHITE, 0.15f), a, k));
                c.drawRoundRect(x, y, x + w, y + h, h / 2, h / 2, p);
                if (k < 0.99f) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(Ui.dpf(1.5f));
                    p.setColor(Ui.alpha(0xFFB0B0B8, 1 - k));
                    c.drawRoundRect(x + Ui.dpf(0.75f), y + Ui.dpf(0.75f), x + w - Ui.dpf(0.75f), y + h - Ui.dpf(0.75f), h / 2, h / 2, p);
                    p.setStyle(Paint.Style.FILL);
                }
                p.setColor(Ui.mix(0xFFB0B0B8, Ui.mix(a, Color.BLACK, 0.6f), k));
                c.drawCircle(kx, ky, kr * (0.62f + 0.38f * k), p);
                return;
            case "gnome":
                p.setColor(Ui.mix(0x33FFFFFF, a, k));
                c.drawRoundRect(x, y, x + w, y + h, h / 2, h / 2, p);
                p.setColor(0xFFFFFFFF);
                c.drawCircle(kx, ky, kr, p);
                return;
            case "liquid":
                p.setColor(Ui.mix(0x24FFFFFF, Ui.alpha(a, 0.8f), k));
                c.drawRoundRect(x, y, x + w, y + h, h / 2, h / 2, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.dpf(1));
                p.setColor(0x66FFFFFF);
                c.drawRoundRect(x, y, x + w, y + h, h / 2, h / 2, p);
                p.setStyle(Paint.Style.FILL);
                // ручка — капля-линза пошире
                p.setColor(0xF2FFFFFF);
                c.drawRoundRect(kx - kr * 1.25f, ky - kr, kx + kr * 1.25f, ky + kr, kr, kr, p);
                return;
            case "acrylic":
                if (k > 0.01f) {
                    p.setColor(Ui.alpha(a, 0.35f * k));
                    c.drawRoundRect(x - Ui.dpf(3), y - Ui.dpf(3), x + w + Ui.dpf(3), y + h + Ui.dpf(3), h / 2 + Ui.dpf(3), h / 2 + Ui.dpf(3), p);
                }
                // fall through
            default:
                p.setColor(Ui.mix(0x3DFFFFFF, a, k));
                c.drawRoundRect(x, y, x + w, y + h, h / 2, h / 2, p);
                p.setColor(0xFFFFFFFF);
                c.drawCircle(kx, ky, kr, p);
        }
    }

    /** Полоска сегментов и выбранный сегмент. */
    static android.graphics.drawable.Drawable segBack() {
        switch (mat()) {
            case "nothing": {
                android.graphics.drawable.GradientDrawable d = Views.round(0x00000000, 9);
                d.setStroke(Ui.dp(1), 0x66FFFFFF);
                return d;
            }
            case "liquid": {
                android.graphics.drawable.GradientDrawable d = Views.round(0x1AFFFFFF, 12);
                d.setStroke(Ui.dp(1), 0x40FFFFFF);
                return d;
            }
            case "material": return Views.round(Ui.mix(surface(), Color.WHITE, 0.1f), 18);
            case "gnome": return Views.round(0x26FFFFFF, 6);
            default: return Views.round(0x3D767680, 9);
        }
    }

    static android.graphics.drawable.Drawable segPick() {
        switch (mat()) {
            case "nothing": return Views.round(0xFFFFFFFF, 7);
            case "liquid": return Views.round(0x40FFFFFF, 10);
            case "material": return Views.round(Ui.mix(surface(), Ui.accent(), 0.45f), 16);
            case "gnome": return Views.round(Ui.accent(), 5);
            case "acrylic": {
                android.graphics.drawable.GradientDrawable d = Views.round(Ui.alpha(Ui.accent(), 0.55f), 7);
                d.setStroke(Ui.dp(1), Ui.alpha(Ui.accentLight(), 0.9f));
                return d;
            }
            default: return Views.round(0xFF636366, 7);
        }
    }

    /** Цвет надписи на выбранном сегменте. */
    static int segPickText() {
        return is("nothing") ? 0xFF000000 : 0xFFFFFFFF;
    }

    /** Шрифт заголовков страниц: у Nothing — моноширинный, у GNOME — потоньше. */
    static Typeface titleFont(Typeface bold) {
        if (is("nothing")) return Typeface.create(Typeface.MONOSPACE, 700, false);
        if (is("gnome")) return Typeface.create(bold, 600, false);
        return bold;
    }

    // ── цвет из обоев ─────────────────────────────────────────────────────

    /** Самый «характерный» цвет картинки: насыщенный, не слишком тёмный и светлый. */
    static void seedFrom(Bitmap b) {
        if (b == null) return;
        int w = b.getWidth(), h = b.getHeight();
        float[] score = new float[24];
        float[][] sum = new float[24][3];
        float[] hsv = new float[3];
        for (int y = 0; y < h; y += Math.max(1, h / 48))
            for (int x = 0; x < w; x += Math.max(1, w / 32)) {
                int c = b.getPixel(x, y);
                Color.colorToHSV(c, hsv);
                float l = (Color.red(c) + Color.green(c) + Color.blue(c)) / 765f;
                float sc = hsv[1] * (0.35f + hsv[2]) * (l > 0.08f && l < 0.92f ? 1 : 0.2f);
                int bin = Math.min(23, (int) (hsv[0] / 15));
                score[bin] += sc;
                sum[bin][0] += Color.red(c) * sc;
                sum[bin][1] += Color.green(c) * sc;
                sum[bin][2] += Color.blue(c) * sc;
            }
        int best = 0;
        for (int i = 1; i < 24; i++) if (score[i] > score[best]) best = i;
        if (score[best] <= 0) return;
        wallSeed = Color.rgb(Math.round(sum[best][0] / score[best]), Math.round(sum[best][1] / score[best]), Math.round(sum[best][2] / score[best]));
    }

    static float[] hsl(int c) {
        float r = Color.red(c) / 255f, g = Color.green(c) / 255f, b = Color.blue(c) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float l = (max + min) / 2, h = 0, s = 0;
        if (max != min) {
            float d = max - min;
            s = l > 0.5f ? d / (2 - max - min) : d / (max + min);
            if (max == r) h = (g - b) / d + (g < b ? 6 : 0);
            else if (max == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h /= 6;
        }
        return new float[]{h, s, l};
    }

    static int fromHsl(float h, float s, float l) {
        float q = l < 0.5f ? l * (1 + s) : l + s - l * s, p2 = 2 * l - q;
        return Color.rgb(Math.round(255 * hue(p2, q, h + 1f / 3)), Math.round(255 * hue(p2, q, h)), Math.round(255 * hue(p2, q, h - 1f / 3)));
    }

    private static float hue(float p, float q, float t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1f / 6) return p + (q - p) * 6 * t;
        if (t < 1f / 2) return q;
        if (t < 2f / 3) return p + (q - p) * (2f / 3 - t) * 6;
        return p;
    }

    private Look() {}
}
