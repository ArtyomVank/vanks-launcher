package os.vanks.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Что где лежит на рабочих столах: значки, папки и виджеты в клетках сетки.
 * Сетка — Prefs.columns столбцов и rows рядов (сколько влезает по высоте).
 * Хранится в SharedPreferences «vanks» → «desk» (JSON). Prefs.home держит
 * тот же порядок значков списком — для поиска и боковой панели.
 */
final class Layout {
    static final String APP = "app", FOLDER = "folder", WIDGET = "widget";
    static final String[] FOLDER_STYLES = {"grid", "big", "stack"};
    static final String[] FOLDER_NAMES = {"Сетка", "Крупная", "Стопка"};

    static final class Item {
        String type;
        int page, x, y, w = 1, h = 1;
        String key;                 // приложение
        String id;                  // папка, виджет
        String name;                // папка
        String style = "grid";      // папка: grid / big / stack; виджет: вариант
        ArrayList<String> apps = new ArrayList<>();  // папка
        String kind;                // виджет: clock, analog, calendar, battery, app
        int widgetId = -1;          // виджет приложения

        boolean covers(int px, int py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }

        boolean overlaps(int ox, int oy, int ow, int oh) {
            return ox < x + w && ox + ow > x && oy < y + h && oy + oh > y;
        }
    }

    static final ArrayList<Item> items = new ArrayList<>();
    static int rows = 6;
    static int pages = 1;
    private static int seq;

    // ── хранение ──────────────────────────────────────────────────────────

    static void load(Context c) {
        items.clear();
        SharedPreferences sp = c.getSharedPreferences("vanks", Context.MODE_PRIVATE);
        String s = sp.getString("desk", null);
        if (s == null) {
            migrate();
            return;
        }
        try {
            JSONObject o = new JSONObject(s);
            seq = o.optInt("seq", 0);
            JSONArray a = o.getJSONArray("items");
            for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.getJSONObject(i);
                Item it = new Item();
                it.type = j.getString("type");
                it.page = j.optInt("page");
                it.x = j.optInt("x");
                it.y = j.optInt("y");
                it.w = Math.max(1, j.optInt("w", 1));
                it.h = Math.max(1, j.optInt("h", 1));
                it.key = j.optString("key", null);
                it.id = j.optString("id", null);
                it.name = j.optString("name", null);
                it.style = j.optString("style", "grid");
                it.kind = j.optString("kind", null);
                it.widgetId = j.optInt("widgetId", -1);
                JSONArray ap = j.optJSONArray("apps");
                if (ap != null) for (int k = 0; k < ap.length(); k++) it.apps.add(ap.getString(k));
                items.add(it);
            }
        } catch (Exception e) {
            items.clear();
            migrate();
        }
        countPages();
    }

    static void save() {
        try {
            JSONArray a = new JSONArray();
            for (Item it : items) {
                JSONObject j = new JSONObject();
                j.put("type", it.type).put("page", it.page).put("x", it.x).put("y", it.y).put("w", it.w).put("h", it.h);
                if (it.key != null) j.put("key", it.key);
                if (it.id != null) j.put("id", it.id);
                if (it.name != null) j.put("name", it.name);
                j.put("style", it.style);
                if (it.kind != null) j.put("kind", it.kind);
                if (it.widgetId >= 0) j.put("widgetId", it.widgetId);
                if (!it.apps.isEmpty()) j.put("apps", new JSONArray(it.apps));
                a.put(j);
            }
            JSONObject o = new JSONObject().put("seq", seq).put("items", a);
            Prefs.raw().edit().putString("desk", o.toString()).apply();
        } catch (Exception ignored) {
        }
        // плоский список значков по порядку — для поиска и боковой панели
        Prefs.home.clear();
        for (Item it : sorted()) {
            if (it.type.equals(APP)) Prefs.home.add(it.key);
            else if (it.type.equals(FOLDER)) Prefs.home.addAll(it.apps);
        }
        Prefs.save();
        countPages();
    }

    /** До 0.5 значки лежали списком: на первый стол — часы, дальше по порядку. */
    private static void migrate() {
        Item clock = widget("clock", 4, 2);
        clock.page = 0;
        clock.x = 0;
        clock.y = 0;
        items.add(clock);
        for (String k : Prefs.home) placeApp(k, 0);
        countPages();
    }

    static String newId(String prefix) {
        return prefix + (++seq);
    }

    static Item widget(String kind, int w, int h) {
        Item it = new Item();
        it.type = WIDGET;
        it.kind = kind;
        it.id = newId("w");
        it.w = w;
        it.h = h;
        it.style = "";
        return it;
    }

    // ── размещение ────────────────────────────────────────────────────────

    static int cols() {
        return Math.max(3, Prefs.columns);
    }

    static boolean free(int page, int x, int y, int w, int h, Item except) {
        if (x < 0 || y < 0 || x + w > cols() || y + h > rows) return false;
        for (Item it : items) {
            if (it == except || it.page != page) continue;
            if (it.overlaps(x, y, w, h)) return false;
        }
        return true;
    }

    static Item at(int page, int x, int y) {
        for (Item it : items) if (it.page == page && it.covers(x, y)) return it;
        return null;
    }

    /** Первая свободная клетка начиная со страницы from; нет места — новая страница. */
    static int[] firstFree(int from, int w, int h) {
        for (int p = from; ; p++) {
            for (int y = 0; y + h <= rows; y++)
                for (int x = 0; x + w <= cols(); x++)
                    if (free(p, x, y, w, h, null)) return new int[]{p, x, y};
            if (p > 60) return new int[]{p, 0, 0};
        }
    }

    static Item placeApp(String key, int fromPage) {
        int[] f = firstFree(fromPage, 1, 1);
        Item it = new Item();
        it.type = APP;
        it.key = key;
        it.page = f[0];
        it.x = f[1];
        it.y = f[2];
        items.add(it);
        return it;
    }

    static void countPages() {
        int n = 1;
        for (Item it : items) n = Math.max(n, it.page + 1);
        pages = n;
    }

    static List<Item> onPage(int p) {
        ArrayList<Item> out = new ArrayList<>();
        for (Item it : items) if (it.page == p) out.add(it);
        return out;
    }

    /** По порядку: страница, ряд, столбец. */
    static List<Item> sorted() {
        ArrayList<Item> out = new ArrayList<>(items);
        out.sort((a, b) -> a.page != b.page ? a.page - b.page : a.y != b.y ? a.y - b.y : a.x - b.x);
        return out;
    }

    /**
     * Сверить с установленными приложениями: удалённые и скрытые убрать (из
     * папок тоже), новые — в первую свободную клетку, вылезшее за сетку (стало
     * меньше рядов или столбцов) — переложить. true — что-то поменялось.
     */
    static boolean sync() {
        boolean changed = false;
        HashSet<String> seen = new HashSet<>();
        for (int i = items.size() - 1; i >= 0; i--) {
            Item it = items.get(i);
            if (it.type.equals(APP)) {
                if (!Apps.byKey.containsKey(it.key) || Prefs.hidden.contains(it.key) || Prefs.dock.contains(it.key) || !seen.add(it.key)) {
                    items.remove(i);
                    changed = true;
                }
            } else if (it.type.equals(FOLDER)) {
                if (it.apps.removeIf(k -> !Apps.byKey.containsKey(k) || Prefs.hidden.contains(k) || Prefs.dock.contains(k))) changed = true;
                it.apps.removeIf(k -> !seen.add(k));
                if (it.apps.isEmpty()) {
                    items.remove(i);
                    changed = true;
                } else if (it.apps.size() == 1 && it.w == 1 && it.h == 1) {
                    // от папки осталось одно приложение — просто значок
                    it.type = APP;
                    it.key = it.apps.get(0);
                    it.apps.clear();
                    changed = true;
                }
            }
        }
        // вылезло за сетку — переложить
        ArrayList<Item> out = new ArrayList<>();
        for (Item it : items) {
            if (it.x + it.w > cols() || it.y + it.h > rows) {
                it.w = Math.min(it.w, cols());
                it.h = Math.min(it.h, rows);
                out.add(it);
            }
        }
        for (Item it : out) {
            items.remove(it);
            int[] f = firstFree(it.page, it.w, it.h);
            it.page = f[0];
            it.x = f[1];
            it.y = f[2];
            items.add(it);
            changed = true;
        }
        for (Apps.App a : Apps.visible()) {
            if (seen.contains(a.key) || Prefs.dock.contains(a.key)) continue;
            placeApp(a.key, 0);
            seen.add(a.key);
            changed = true;
        }
        // пустые страницы в середине — убрать
        countPages();
        for (int p = pages - 1; p >= 0; p--) {
            if (!onPage(p).isEmpty() || pages == 1) continue;
            for (Item it : items) if (it.page > p) it.page--;
            countPages();
            changed = true;
        }
        return changed;
    }

    static Item folderOf(String key) {
        for (Item it : items) if (it.type.equals(FOLDER) && it.apps.contains(key)) return it;
        return null;
    }

    static Item byId(String id) {
        for (Item it : items) if (id.equals(it.id)) return it;
        return null;
    }

    private Layout() {}
}
