package os.vanks.launcher;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/**
 * Обложка версии для «Обновления» и карточки «О лаунчере»: тёмное небо в
 * цвете акцента, светящаяся диагональная лента и большой стеклянный шар, за
 * которым лента преломляется. Поверх — крупный номер версии, «Vanks OS» и
 * модель телефона. Рисуется один раз в картинку под размер.
 */
final class UpdateArt {
    // по картинке на страницу (с текстом) и на карточку (без)
    private static final Bitmap[] cache = new Bitmap[2];
    private static final String[] cacheKey = {"", ""};

    /** Обложка w×h; text — с номером версии, «Vanks OS» и моделью (на всю страницу). */
    static Bitmap get(int w, int h, String version, String model, boolean text) {
        String key = w + "x" + h + "/" + Ui.accent() + "/" + version + "/" + model;
        int k = text ? 1 : 0;
        if (cache[k] != null && key.equals(cacheKey[k])) return cache[k];
        Bitmap b = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        paint(c, w, h);
        if (text) title(c, w, h, version, model);
        cache[k] = b;
        cacheKey[k] = key;
        return b;
    }

    /** Тёплый свет ленты: оттенок акцента, повёрнутый почти напротив (у фиолетового — оранжевый). */
    static int warm() {
        float[] a = Look.hsl(Ui.accent());
        return Look.fromHsl((a[0] + 0.38f) % 1f, Math.max(0.8f, a[1]), 0.55f);
    }

    static void paint(Canvas c, float w, float h) {
        int acc = Ui.accent(), warm = warm();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // небо: почти чёрное сверху, акцент в середине, светлее к низу
        p.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{Ui.mix(acc, 0xFF05050C, 0.9f), Ui.mix(acc, 0xFF0A0818, 0.62f), Ui.mix(acc, 0xFF201640, 0.45f), Ui.mix(acc, 0xFF0A0818, 0.6f)},
                new float[]{0f, 0.38f, 0.66f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        // мягкое свечение слева у ленты
        p.setShader(new RadialGradient(w * 0.05f, h * 0.62f, w * 0.9f, Ui.alpha(warm, 0.35f), 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);

        Path band = band(w, h, 0);
        // ореол ленты, потом сама лента: от тёплого слева к акценту справа
        p.setShader(new LinearGradient(0, h * 0.7f, w, h * 0.4f, Ui.alpha(warm, 0.9f), Ui.alpha(Ui.accentLight(), 0.6f), Shader.TileMode.CLAMP));
        p.setMaskFilter(new BlurMaskFilter(Math.max(1, w * 0.06f), BlurMaskFilter.Blur.NORMAL));
        c.drawPath(band, p);
        p.setMaskFilter(null);
        p.setShader(new LinearGradient(0, h * 0.58f, 0, h * 0.74f,
                new int[]{Ui.mix(warm, 0xFFFFFFFF, 0.25f), warm, Ui.mix(warm, acc, 0.6f)}, null, Shader.TileMode.CLAMP));
        c.drawPath(band, p);
        // тёмная грань поверх ленты — как сгиб, даёт объём
        Path fold = new Path();
        fold.moveTo(0, h * 0.5f);
        fold.lineTo(w * 0.62f, h * 0.6f);
        fold.lineTo(0, h * 0.64f);
        fold.close();
        p.setShader(new LinearGradient(0, h * 0.5f, w * 0.6f, h * 0.62f, Ui.mix(acc, 0xFF0A0818, 0.55f), Ui.alpha(Ui.mix(acc, 0xFF0A0818, 0.55f), 0f), Shader.TileMode.CLAMP));
        c.drawPath(fold, p);

        // стеклянный шар справа
        float cx = w * 0.9f, cy = h * 0.6f, r = Math.min(w * 0.42f, h * 0.24f);
        p.setShader(new RadialGradient(cx - r * 0.1f, cy + r * 0.1f, r * 1.25f, Ui.alpha(0xFF000000, 0.3f), 0x00000000, Shader.TileMode.CLAMP));
        c.drawCircle(cx - r * 0.06f, cy + r * 0.08f, r * 1.1f, p);
        p.setShader(new LinearGradient(cx - r, cy - r, cx + r * 0.4f, cy + r,
                new int[]{Ui.mix(Ui.accentLight(), 0xFFFFFFFF, 0.25f), Ui.accent(), Ui.mix(acc, 0xFF120C26, 0.4f)},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
        // лента сквозь шар — ниже и шире, как в толстом стекле
        c.save();
        Path ball = new Path();
        ball.addCircle(cx, cy, r, Path.Direction.CW);
        c.clipPath(ball);
        p.setShader(new LinearGradient(cx - r, 0, cx + r, 0, Ui.mix(warm, 0xFFFFFFFF, 0.35f), warm, Shader.TileMode.CLAMP));
        p.setMaskFilter(new BlurMaskFilter(Math.max(1, r * 0.08f), BlurMaskFilter.Blur.NORMAL));
        c.drawPath(band(w, h, h * 0.05f), p);
        p.setMaskFilter(null);
        // блик сверху слева и светлая кромка по краю
        p.setShader(new RadialGradient(cx - r * 0.45f, cy - r * 0.5f, r * 0.9f, 0x80FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
        p.setShader(new RadialGradient(cx, cy, r, new int[]{0x00FFFFFF, 0x00FFFFFF, 0x55FFFFFF}, new float[]{0f, 0.86f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
        c.restore();
        p.setShader(null);
    }

    /** Лента: снизу слева вверх направо; dy — сдвиг вниз (преломление в шаре). */
    private static Path band(float w, float h, float dy) {
        Path b = new Path();
        b.moveTo(-w * 0.1f, h * 0.6f + dy);
        b.cubicTo(w * 0.3f, h * 0.56f + dy, w * 0.7f, h * 0.44f + dy, w * 1.1f, h * 0.36f + dy);
        b.lineTo(w * 1.1f, h * 0.47f + dy);
        b.cubicTo(w * 0.7f, h * 0.56f + dy, w * 0.3f, h * 0.7f + dy, -w * 0.1f, h * 0.76f + dy);
        b.close();
        return b;
    }

    /** Крупный номер версии, под ним «Vanks OS» и модель телефона. */
    private static void title(Canvas c, float w, float h, String version, String model) {
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTextAlign(Paint.Align.CENTER);
        t.setTypeface(Ui.bold);
        float cy = h * 0.34f;
        // номер — во всю ширину, но не выше 0,2 экрана
        t.setTextSize(100);
        float size = Math.min(w * 0.7f / Math.max(1, t.measureText(version)) * 100, h * 0.2f);
        t.setTextSize(size);
        t.setLetterSpacing(-0.03f);
        t.setShader(new LinearGradient(0, cy - size * 0.75f, 0, cy, 0xFFFFFFFF, Ui.mix(Ui.accentLight(), 0xFFFFFFFF, 0.55f), Shader.TileMode.CLAMP));
        t.setShadowLayer(size * 0.12f, 0, size * 0.03f, 0x40000000);
        c.drawText(version, w / 2, cy, t);
        t.setShadowLayer(0, 0, 0, 0);
        t.setLetterSpacing(0.02f);
        t.setTextSize(Ui.dpf(30));
        t.setShader(new LinearGradient(w * 0.3f, 0, w * 0.7f, 0, 0xFFFFFFFF, Ui.mix(Ui.accentLight(), 0xFFFFFFFF, 0.4f), Shader.TileMode.CLAMP));
        float y2 = cy + Ui.dpf(42);
        c.drawText("Vanks OS", w / 2, y2, t);
        t.setShader(null);
        t.setLetterSpacing(0);
        t.setTypeface(Ui.medium);
        t.setTextSize(Ui.dpf(15));
        t.setColor(0xCCFFFFFF);
        c.drawText(model, w / 2, y2 + Ui.dpf(24), t);
    }

    private UpdateArt() {}
}
