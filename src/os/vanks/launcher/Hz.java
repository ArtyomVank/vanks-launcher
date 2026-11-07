package os.vanks.launcher;

import android.app.Activity;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

/**
 * Частота экрана для окон Vanks: по умолчанию — самая высокая, какую умеет
 * экран (120 Гц и т. п.), в том же разрешении. Или та, что выбрана в
 * «Жесты и плавность». Прошивка (HyperOS, ColorOS) может решить по-своему,
 * но обычно просьбу окна слушает.
 */
final class Hz {
    static final int[] CHOICES = {0, 120, 90, 60};   // 0 — максимальная
    static final String[] NAMES = {"Макс.", "120", "90", "60"};

    @SuppressWarnings("deprecation")
    static void apply(Activity a) {
        apply(a.getWindow(), a.getWindowManager().getDefaultDisplay());
    }

    static void apply(Window w, Display d) {
        if (w == null || d == null) return;
        Display.Mode cur = d.getMode();
        Display.Mode best = null;
        float want = Prefs.hz;
        for (Display.Mode m : d.getSupportedModes()) {
            if (m.getPhysicalWidth() != cur.getPhysicalWidth() || m.getPhysicalHeight() != cur.getPhysicalHeight()) continue;
            if (best == null) { best = m; continue; }
            float r = m.getRefreshRate(), b = best.getRefreshRate();
            if (want <= 0) {
                if (r > b) best = m;
            } else if (Math.abs(r - want) < Math.abs(b - want)) best = m;
        }
        if (best == null) return;
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.preferredDisplayModeId = best.getModeId();
        lp.preferredRefreshRate = best.getRefreshRate();
        w.setAttributes(lp);
    }

    /** Что умеет экран: «60, 90, 120 Гц». */
    static String supported(Display d) {
        if (d == null) return "";
        java.util.TreeSet<Integer> s = new java.util.TreeSet<>();
        Display.Mode cur = d.getMode();
        for (Display.Mode m : d.getSupportedModes())
            if (m.getPhysicalWidth() == cur.getPhysicalWidth() && m.getPhysicalHeight() == cur.getPhysicalHeight())
                s.add(Math.round(m.getRefreshRate()));
        StringBuilder b = new StringBuilder();
        for (int v : s) b.append(b.length() == 0 ? "" : ", ").append(v);
        return b + " Гц";
    }

    private Hz() {}
}
