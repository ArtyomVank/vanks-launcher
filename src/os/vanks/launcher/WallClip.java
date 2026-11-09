package os.vanks.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import java.io.File;

/**
 * Видео или GIF своих обоев. Видео идёт в TextureView прямо под WallView (она
 * рисует сверху матовость и затемнение), GIF рисует сама WallView. Пока первый
 * кадр не готов, showing = false и WallView держит обычную картинку — чёрного
 * мигания нет. Звука у обоев нет; на паузе плеера нет вовсе.
 */
final class WallClip implements TextureView.SurfaceTextureListener, Drawable.Callback {
    private static final Handler main = new Handler(Looper.getMainLooper());

    private final WallView wall;
    private final String path;
    private final boolean gif;
    private boolean wanted, loop;
    private int pos;   // где остановились: анимированные продолжают с этого места

    boolean showing;

    private TextureView tv;
    private MediaPlayer mp;
    private Surface surface;
    private int vw, vh;
    private final ViewTreeObserver.OnPreDrawListener follow = () -> {
        copy();
        return true;
    };

    private AnimatedImageDrawable anim;
    private boolean loading;
    private final Matrix m = new Matrix();

    WallClip(WallView wall, String path) {
        this.wall = wall;
        this.path = path;
        gif = path.endsWith(".gif");
    }

    void play(boolean loop) {
        if (wanted && this.loop == loop) return;
        wanted = true;
        this.loop = loop;
        if (gif) startGif();
        else if (tv == null) attach();
    }

    void stop() {
        if (!wanted) return;
        wanted = false;
        showing = false;
        if (gif) {
            if (anim != null) {
                anim.stop();
                anim.setCallback(null);
            }
            anim = null;
        } else {
            detach();
        }
        wall.invalidate();
    }

    // ── видео ─────────────────────────────────────────────────────────────

    private void attach() {
        ViewGroup p = (ViewGroup) wall.getParent();
        if (p == null) return;
        Context c = wall.getContext();
        tv = new TextureView(c);
        tv.setOpaque(true);
        tv.setSurfaceTextureListener(this);
        p.addView(tv, p.indexOfChild(wall), new FrameLayout.LayoutParams(-1, -1));
        wall.getViewTreeObserver().addOnPreDrawListener(follow);
        copy();
    }

    private void detach() {
        close();
        TextureView t = tv;
        tv = null;
        if (t == null) return;
        wall.getViewTreeObserver().removeOnPreDrawListener(follow);
        ViewGroup p = (ViewGroup) t.getParent();
        if (p != null) p.removeView(t);
    }

    /** Экран анимирует WallView — видео под ней двигается вместе с ней. */
    private void copy() {
        if (tv == null) return;
        float z = wall.photoZoom();
        tv.setAlpha(wall.getAlpha());
        tv.setTranslationX(wall.getTranslationX());
        tv.setTranslationY(wall.getTranslationY());
        tv.setScaleX(wall.getScaleX() * z);
        tv.setScaleY(wall.getScaleY() * z);
    }

    private void open() {
        close();
        try {
            MediaPlayer p = new MediaPlayer();
            mp = p;
            p.setSurface(surface);
            p.setDataSource(path);
            p.setVolume(0, 0);
            p.setLooping(loop);
            p.setOnPreparedListener(x -> {
                if (loop && pos > 0) x.seekTo(pos);
                x.start();
            });
            p.setOnVideoSizeChangedListener((x, w, h) -> {
                vw = w;
                vh = h;
                fit();
            });
            p.setOnCompletionListener(x -> wall.clipEnded());
            p.setOnErrorListener((x, a, b) -> {
                wall.clipEnded();
                return true;
            });
            p.prepareAsync();
        } catch (Exception e) {
            close();
            wall.clipEnded();
        }
    }

    private void close() {
        MediaPlayer p = mp;
        mp = null;
        if (p == null) return;
        try {
            if (loop) pos = p.getCurrentPosition();
        } catch (RuntimeException ignored) {
        }
        p.release();
    }

    /** По размеру экрана с обрезкой краёв, как картинка обоев. */
    private void fit() {
        if (tv == null || vw <= 0 || vh <= 0) return;
        int w = tv.getWidth(), h = tv.getHeight();
        if (w <= 0 || h <= 0) return;
        float s = Math.max(w / (float) vw, h / (float) vh);
        m.setScale(vw * s / w, vh * s / h, w / 2f, h / 2f);
        tv.setTransform(m);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surface = new Surface(st);
        if (wanted) open();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
        fit();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        close();
        if (surface != null) surface.release();
        surface = null;
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
        if (showing || !wanted) return;
        showing = true;
        wall.invalidate();
    }

    // ── GIF ───────────────────────────────────────────────────────────────

    private void startGif() {
        if (loading) return;
        loading = true;
        new Thread(() -> {
            Drawable d;
            try {
                d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(new File(path)));
            } catch (Exception | OutOfMemoryError e) {
                d = null;
            }
            Drawable done = d;
            main.post(() -> {
                loading = false;
                if (!wanted) return;
                if (done instanceof AnimatedImageDrawable) begin((AnimatedImageDrawable) done);
                else wall.clipEnded();
            });
        }, "wall-gif").start();
    }

    private void begin(AnimatedImageDrawable d) {
        anim = d;
        d.setBounds(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        d.setCallback(this);
        d.setRepeatCount(loop ? AnimatedImageDrawable.REPEAT_INFINITE : 0);
        d.registerAnimationCallback(new android.graphics.drawable.Animatable2.AnimationCallback() {
            @Override
            public void onAnimationEnd(Drawable x) {
                if (x == anim) wall.clipEnded();
            }
        });
        d.start();
        showing = true;
        wall.invalidate();
    }

    /** Кадр GIF под размер экрана. false — это видео, оно рисуется само. */
    boolean draw(Canvas c, int w, int h, float zoom) {
        AnimatedImageDrawable d = anim;
        if (d == null) return false;
        c.save();
        c.concat(Ui.wallMatrix(d.getIntrinsicWidth(), d.getIntrinsicHeight(), w, h, zoom, m));
        d.draw(c);
        c.restore();
        return true;
    }

    @Override
    public void invalidateDrawable(Drawable who) {
        wall.invalidate();
    }

    @Override
    public void scheduleDrawable(Drawable who, Runnable what, long when) {
        main.postAtTime(what, who, when);
    }

    @Override
    public void unscheduleDrawable(Drawable who, Runnable what) {
        main.removeCallbacks(what, who);
    }
}
