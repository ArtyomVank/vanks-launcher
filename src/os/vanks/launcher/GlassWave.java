package os.vanks.launcher;

import android.animation.ValueAnimator;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Стеклянная волна Delta (common/ui/GlassWave.qml) поверх всего экрана:
 * кольцо из точки расходится до краёв и преломляет то, что под ним. Тяжёлая:
 * толчок в центре, медленный разгон гребня, широкое тело, долгое затухание.
 * Шейдер — res/raw/fx_glasswave.agsl, нужен Android 13; на старых волны нет.
 *     GlassWave.play(root, x, y, 1f, 1700);   // разблокировка
 */
final class GlassWave {
    private static RuntimeShader shader;
    private static ValueAnimator running;
    private static boolean broken;

    static boolean supported() {
        return Build.VERSION.SDK_INT >= 33 && !broken;
    }

    static void play(View target, float cx, float cy, float power, int ms) {
        if (!supported() || target.getWidth() == 0) return;
        try {
            if (shader == null) {
                String src = Walls.raw(target.getContext(), "fx_glasswave");
                if (src == null) { broken = true; return; }
                shader = new RuntimeShader(src);
            }
        } catch (RuntimeException e) {
            broken = true;
            return;
        }
        if (running != null) running.cancel();
        float w = target.getWidth(), h = target.getHeight();
        float dx = Math.max(cx, w - cx), dy = Math.max(cy, h - cy);
        float far = (float) Math.sqrt(dx * dx + dy * dy) + Ui.dpf(90);
        float width0 = Math.min(w, h) * 0.13f;
        ValueAnimator a = ValueAnimator.ofFloat(0, ms);
        a.setDuration(Math.round(ms * Prefs.k()));
        a.setInterpolator(new LinearInterpolator());
        a.addUpdateListener(v -> {
            float t = (Float) v.getAnimatedValue();
            float p = t / ms;
            float radius = far * inOutQuad(p);
            float thump = t < 110 ? power * outQuad(t / 110f) : power * (1 - outBack(Math.min(1, (t - 110) / 420f), 2.2f));
            float ww = width0 * (0.6f + 0.4f * power) + width0 * ((1.6f + power) - (0.6f + 0.4f * power)) * outQuad(p);
            float strength;
            if (t < 220) strength = power * outQuad(t / 220f);
            else if (t < 480) strength = power;
            else strength = power * (1 - inQuad((t - 480) / Math.max(1f, ms - 480f)));
            try {
                shader.setFloatUniform("res", w, h);
                shader.setFloatUniform("center", cx, cy);
                shader.setFloatUniform("radius", radius);
                shader.setFloatUniform("waveWidth", ww);
                shader.setFloatUniform("strength", Math.max(0, strength));
                shader.setFloatUniform("rim", 1f);
                shader.setFloatUniform("thump", Math.max(-0.3f, thump));
                target.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "src"));
            } catch (RuntimeException e) {
                broken = true;
                target.setRenderEffect(null);
                v.cancel();
            }
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator x) {
                target.setRenderEffect(null);
                if (running == a) running = null;
            }
        });
        running = a;
        a.start();
    }

    private static float outQuad(float x) { return 1 - (1 - x) * (1 - x); }

    private static float inQuad(float x) { return x * x; }

    private static float inOutQuad(float x) { return x < 0.5f ? 2 * x * x : 1 - (float) Math.pow(-2 * x + 2, 2) / 2; }

    private static float outBack(float x, float s) {
        float y = x - 1;
        return 1 + (s + 1) * y * y * y + s * y * y;
    }

    private GlassWave() {}
}
