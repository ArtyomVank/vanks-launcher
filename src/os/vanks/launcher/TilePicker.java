package os.vanks.launcher;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Выбор плитки для пункта управления — всё, чего ещё нет: свои плитки, затем
 * плитки приложений (VPN, «Быстрая отправка» и т. п. — то, что приложения дают
 * быстрым настройкам Android). Строки со значками, по разделам.
 */
final class TilePicker {
    /** Список для выбора; null — добавлять нечего. */
    static View build(Context c, TileGrid grid, Consumer<String> pick) {
        ArrayList<String> own = new ArrayList<>(), apps = new ArrayList<>();
        for (String t : TileGrid.TYPES) if (!grid.has(t)) own.add(t);
        QsTiles.reset();
        for (QsTiles.Entry e : QsTiles.all(c)) if (!grid.has(e.type)) apps.add(e.type);
        if (own.isEmpty() && apps.isEmpty()) return null;
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, Ui.dp(8), 0, Ui.dp(12));
        TextView title = Views.label(c, "Добавить плитку", 20, Ui.bold, Look.fg());
        title.setPadding(Ui.dp(22), Ui.dp(14), Ui.dp(22), Ui.dp(6));
        col.addView(title);
        if (!own.isEmpty()) {
            col.addView(section(c, "Пункт управления"));
            for (String t : own) col.addView(row(c, t, TileGrid.title(t), null, pick));
        }
        if (!apps.isEmpty()) {
            col.addView(section(c, "Плитки приложений"));
            for (String t : apps) {
                QsTiles.Entry e = QsTiles.get(t);
                String sub = !e.label.equals(e.app) ? e.app : e.vpn ? "VPN — включить и выключить" : null;
                col.addView(row(c, t, e.label, sub, pick));
            }
        }
        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col);
        return sv;
    }

    private static TextView section(Context c, String s) {
        TextView t = Views.label(c, s, 13, Ui.medium, Look.fg2());
        t.setPadding(Ui.dp(22), Ui.dp(14), Ui.dp(22), Ui.dp(4));
        return t;
    }

    /** Строка: значок плитки в кружке, название и (для плиток приложений) приложение. */
    private static View row(Context c, String type, String name, String sub, Consumer<String> pick) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(18), Ui.dp(8), Ui.dp(18), Ui.dp(8));
        View icon = Views.drawn(c, (cv, w, h) -> TileGrid.glyph(cv, type, w / 2, h / 2, w * 0.62f, Look.fg(), false));
        icon.setBackground(Views.round(Ui.alpha(Look.fg(), 0.1f), 22));
        row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)));
        LinearLayout tx = new LinearLayout(c);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(Ui.dp(14), 0, 0, 0);
        tx.addView(Views.label(c, name, 16, Ui.medium, Look.fg()));
        if (sub != null) tx.addView(Views.label(c, sub, 13, Ui.regular, Look.fg2()));
        row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.alpha(Look.fg(), 0.12f)), null, null));
        row.setOnClickListener(v -> pick.accept(type));
        return row;
    }

    private TilePicker() {}
}
