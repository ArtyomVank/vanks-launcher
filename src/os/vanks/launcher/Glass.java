package os.vanks.launcher;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Панель-стекло. Обычная — материалом темы (Look.panel). «Полка» (док и
 * «Поиск») — своя, от темы не зависит: матовое стекло из обоев, лёгкая
 * дымка, светлая кромка сверху и мягкая тень под ней.
 */
final class Glass extends FrameLayout {
    private final float radiusDp;
    private final boolean shelf;
    private final RectF r = new RectF();
    private final int[] loc = new int[2];
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    Glass(Context c, float radiusDp) {
        this(c, radiusDp, false);
    }

    Glass(Context c, float radiusDp, boolean shelf) {
        super(c);
        this.radiusDp = radiusDp;
        this.shelf = shelf;
        setWillNotDraw(false);
        setClipChildren(false);
    }

    @Override
    protected void onDraw(Canvas c) {
        View root = getRootView();
        getLocationInWindow(loc);
        float rad = Math.min(shelf ? Ui.dpf(radiusDp) : Look.radius(radiusDp), getHeight() / 2f);
        if (!shelf) {
            r.set(0, 0, getWidth(), getHeight());
            Look.panel(c, r, rad, loc[0], loc[1], root.getWidth(), root.getHeight());
            return;
        }
        // тень
        r.set(Ui.dpf(4), Ui.dpf(8), getWidth() - Ui.dpf(4), getHeight() + Ui.dpf(2));
        p.reset();
        p.setAntiAlias(true);
        p.setColor(0x38000000);
        p.setMaskFilter(new BlurMaskFilter(Ui.dpf(12), BlurMaskFilter.Blur.NORMAL));
        c.drawRoundRect(r, rad, rad, p);
        p.setMaskFilter(null);
        // стекло
        r.set(0, 0, getWidth(), getHeight());
        Ui.glass(c, r, rad, loc[0], loc[1], root.getWidth(), root.getHeight(), Look.light() ? 0x40FFFFFF : 0x2E10131C);
        // дымка сверху
        p.setShader(new LinearGradient(0, 0, 0, getHeight() * 0.5f, 0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, p);
        p.setShader(null);
        // кромка: сверху светлее
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.dpf(1.1f));
        p.setShader(new LinearGradient(0, 0, 0, getHeight(), 0x8CFFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
        r.inset(Ui.dpf(0.55f), Ui.dpf(0.55f));
        c.drawRoundRect(r, rad, rad, p);
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
    }
}
