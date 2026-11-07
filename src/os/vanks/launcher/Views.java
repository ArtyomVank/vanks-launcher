package os.vanks.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.TextView;

/** Мелкие общие заготовки для разметки кодом: подписи, «таблетки», скругления. */
final class Views {
    interface Painter { void paint(Canvas c, float w, float h); }

    static TextView label(Context c, String s, float sp, Typeface tf, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTypeface(tf);
        t.setTextColor(color);
        return t;
    }

    static TextView pill(Context c, String s) {
        TextView t = label(c, s, 14, Ui.medium, 0xFFFFFFFF);
        t.setPadding(Ui.dp(16), Ui.dp(9), Ui.dp(16), Ui.dp(9));
        t.setBackground(round(0x24FFFFFF, 18));
        return t;
    }

    static GradientDrawable round(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(Ui.dpf(radiusDp));
        return d;
    }

    /** View, который рисует себя лямбдой: для значков-кнопок. */
    static android.view.View drawn(Context c, Painter p) {
        return new android.view.View(c) {
            @Override
            protected void onDraw(Canvas cv) {
                p.paint(cv, getWidth(), getHeight());
            }
        };
    }

    private Views() {}
}
