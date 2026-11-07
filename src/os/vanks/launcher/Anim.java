package os.vanks.launcher;

import android.animation.ValueAnimator;
import android.view.animation.PathInterpolator;

import java.util.function.Consumer;

/** Кривые и короткие анимации. */
final class Anim {
    static final PathInterpolator EMPH = new PathInterpolator(0.2f, 0f, 0f, 1f);
    static final PathInterpolator STD = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    /** Кривая разблокировки Delta на компьютере: cubic-bezier(.65, 0, .3, 1). */
    static final PathInterpolator PC = new PathInterpolator(0.65f, 0f, 0.3f, 1f);

    /** Пружинка: чуть проскакивает и возвращается. */
    static float back(float x) {
        float c1 = 1.5f, c3 = c1 + 1f;
        float y = x - 1f;
        return 1f + c3 * y * y * y + c1 * y * y;
    }

    static ValueAnimator run(float from, float to, long ms, android.animation.TimeInterpolator in, Consumer<Float> step, Runnable end) {
        ValueAnimator a = ValueAnimator.ofFloat(from, to);
        a.setDuration(Math.round(ms * Prefs.k()));
        a.setInterpolator(in);
        a.addUpdateListener(v -> step.accept((Float) v.getAnimatedValue()));
        if (end != null) {
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                boolean cancelled;

                @Override
                public void onAnimationCancel(android.animation.Animator animation) {
                    cancelled = true;
                }

                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    if (!cancelled) end.run();
                }
            });
        }
        a.start();
        return a;
    }

    /**
     * Пружина (как в ColorOS и iOS): от from к to с начальной скоростью v0
     * (единиц в секунду). stiffness — жёсткость (300…1500), damping — 0…1
     * (0,7 — чуть проскочит, 0,95 — мягко встанет). Длительность — сколько
     * пружине нужно, чтобы успокоиться.
     */
    static ValueAnimator spring(float from, float to, float v0, float stiffness, float damping, Consumer<Float> step, Runnable end) {
        // медленнее анимации — мягче пружина (время растёт в k раз)
        float w = (float) Math.sqrt(stiffness) / Prefs.k();
        float z = Math.min(0.99f, damping);
        float wd = w * (float) Math.sqrt(1 - z * z);
        float a0 = from - to;
        float b0 = (v0 + z * w * a0) / wd;   // v0 — в единицах в секунду
        // до |x - to| < 0,1 % от размаха
        float amp = Math.abs(a0) + Math.abs(b0) + 1e-4f;
        float settle = (float) (Math.log(amp / (0.001f * Math.max(Math.abs(a0), 1e-3f))) / (z * w));
        long ms = Math.max(120, Math.min(2000, Math.round(settle * 1000)));
        ValueAnimator a = ValueAnimator.ofFloat(0, ms / 1000f);
        a.setDuration(ms);
        a.setInterpolator(new android.view.animation.LinearInterpolator());
        a.addUpdateListener(v -> {
            float t = (Float) v.getAnimatedValue();
            float e = (float) Math.exp(-z * w * t);
            float x = to + e * (a0 * (float) Math.cos(wd * t) + b0 * (float) Math.sin(wd * t));
            step.accept(t >= ms / 1000f ? to : x);
        });
        if (end != null) {
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                boolean cancelled;

                @Override
                public void onAnimationCancel(android.animation.Animator animation) {
                    cancelled = true;
                }

                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    if (!cancelled) end.run();
                }
            });
        }
        a.start();
        return a;
    }

    private Anim() {}
}
