package os.vanks.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

/**
 * Настройки лаунчера, общие для главного экрана и приложения «Настройки».
 * Приложения хранятся по ключу «пакет/активность». rev растёт при каждом
 * сохранении: так главный экран узнаёт, что в «Настройках» что-то поменяли.
 */
final class Prefs {
    static final int SHAPE_SQUIRCLE = 0, SHAPE_CIRCLE = 1, SHAPE_SQUARE = 2;
    static final String DEFAULT_TILES =
            "wifi:2:1,bt:2:1,media:2:2,torch:1:1,dnd:1:1,bright:1:2,volume:1:2,airplane:1:1,rotate:1:1,camera:1:1,calc:1:1,alarm:1:1,battery:2:1";

    static int accent = 0xFF7C5CFF;   // цвет (до 0.3 — номер в старом списке)
    static String ui = "acrylic";      // стиль: acrylic / delta
    static String material = "acrylic"; // тема: см. Look.MAT_IDS
    static String tone = "graphite";   // цвет: см. Look.TONES
    static int shape = SHAPE_SQUIRCLE;
    static float iconRound = 1f;
    static String posterKind = "glass";  // «Длинные»: hollow — пустые внутри, mixed — часы стеклянные, glass — всё стекло, solid — обычные
    static int posterFront = 0xFFFFFFFF; // цвет часов, 0 — акцент
    static int posterBack = 0xFFFFFFFF;  // цвет минут, 0 — акцент
    static float longDigitW = 1f, longStroke = 1f;   // «Длинные»: ширина цифр и толщина линии
    static float iconScale = 1f;
    static boolean themed = false;
    static boolean labels = true;
    static int columns = 4;
    static String wallpaper = "peak";
    static boolean wallAnimate = false;  // живые и супер обои двигаются и на столе (тратит батарею)
    static boolean depth = true;
    static boolean fingerprint = true;
    static boolean swipeSearch = true;
    static int hz = 0;                  // частота экрана окон Vanks: 0 — максимальная
    static float gestSens = 1f;         // чувствительность свайпов: 0,6 — нужен длинный свайп, 1,6 — короткий
    static float gestTop = 0.33f;       // высота верхней зоны (пункт управления, уведомления)
    static boolean ctrlRight = true;
    static boolean syncSystemWall = true;   // те же обои и в системе (чтобы не мелькали чужие)
    static String sysWallHome = "", sysWallLock = "";   // что уже поставлено системе
    static boolean navGestures = true, navBack = true, navPill = true;   // свои жесты (служба «Vanks: жесты и вход»)
    static boolean liveClose = true;   // закрытие жестом: окно за пальцем и в свой значок
    static boolean lockSystemBio = true;
    static boolean ownRecents = true;   // свои «Недавние» (карточки со снимками) вместо системных
    static String recentsView = "row";  // вид «Недавних»: row — лента, grid — сетка в два столбца, stack — стопка
    static String pillHold = "lens";    // зажать полоску внизу: lens — поиск по экрану, google — поиск Google, assist — ассистент, off
    static boolean autoUpdate = true;   // проверять обновления на GitHub   // лицо и палец читает Android, под картинкой экрана Vanks
    static boolean island = true, islandVolume = true, islandRides = true, islandMedia = true;   // динамический остров и что в нём
    static boolean keepGestures = true; // жесты Android вместо кнопок (HyperOS ставит кнопки чужим лаунчерам)    // пункт управления справа сверху, уведомления — слева
    static boolean swipeHintSeen = false;
    static String tiles = DEFAULT_TILES;
    static ArrayList<String> home = new ArrayList<>();
    static ArrayList<String> dock = new ArrayList<>();
    static HashSet<String> hidden = new HashSet<>();
    static boolean sounds = true;        // звуки Delta
    static float soundVolume = 0.6f;
    static boolean haptics = true;
    static String icons = "vanks";       // значки: vanks / system / pack:<пакет>
    static boolean edge = false;         // боковая панель (поверх других приложений)
    static boolean edgeLeft = false;
    static boolean edgeWindow = false;   // из панели — в плавающем окне
    static ArrayList<String> edgeApps = new ArrayList<>();
    // экран блокировки: свои обои ("" — те же, что на столе) и часы, как на ПК
    static String lockWallpaper = "";
    static String lockClock = "";        // стиль (long, ios, thin, mono, neon, stack, serif, wide, outline, minimal, tall, dots); "" — от темы
    static String lockClockColor = "white";  // white / accent / gradient / glass
    static float lockClockSize = 1f;
    static float lockClockW = 1f, lockClockH = 1f;   // ширина и высота часов, как на ПК
    static float lockClockX = 0.5f, lockClockY = 0f; // где стоят часы (середина по X, отступ сверху — доля экрана)
    static boolean clockToHome = true;   // при входе часы переезжают с экрана блокировки на главный
    static String lockWidgetsPos = "under";   // виджеты экрана блокировки: under — под часами, side — сбоку
    static String lockWidgets = "";  // виджеты экрана блокировки, столбиком справа
    static String fpEffect = "pulse";    // отпечаток: pulse / ripple / glow / none
    static boolean lockMain = false;    // свой экран блокировки (отдельно от стола) — основной
    static String aodMode = "off";      // «Всегда на экране»: off / 10s — 10 секунд / always — пока не включат
    static String aodStyle = "big";     // вид AOD: big — крупные цифры, dim — приглушённый экран блокировки
    static boolean fpAuto = true;       // значок отпечатка — над самим датчиком
    static float fpY = -1f;             // место значка вручную: доля высоты снизу; -1 — как раньше
    static String fpIcon = "classic";    // вид отпечатка: classic / glass / bare / ring / photo (своё фото)
    static boolean unlockWave = true;    // стеклянная волна при входе
    static float animSpeed = 1f;         // скорость анимаций: 0,5 — медленнее, 2 — быстрее
    static boolean setupDone = false;    // Vanks OS: мастер первого запуска пройден
    static boolean firstRun = true;
    static int rev = 0;

    private static SharedPreferences sp;

    static void load(Context c) {
        sp = c.getSharedPreferences("vanks", Context.MODE_PRIVATE);
        firstRun = !sp.contains("home");
        accent = sp.getInt("accent", 0xFF7C5CFF);
        // 0.1–0.2 хранили номер цвета
        int[] old = {0xFF7B8CFF, 0xFFFF7A59, 0xFF3FD6A0, 0xFFC58CFF, 0xFFFFC145, 0xFFFF5C8A};
        if (accent >= 0 && accent < old.length) accent = old[accent];
        ui = sp.getString("ui", "acrylic");
        material = sp.getString("material", "acrylic");
        tone = sp.getString("tone", "graphite");
        wallAnimate = sp.getBoolean("wallAnimate", false);
        shape = sp.getInt("shape", SHAPE_SQUIRCLE);
        iconRound = sp.getFloat("iconRound", 1f);
        // 0.9: значки снова круглые у всех (раньше маска на HyperOS не срабатывала)
        if (sp.getInt("iconRoundV", 0) < 9) iconRound = 1f;
        posterKind = sp.getString("posterKind", "glass");
        posterFront = sp.getInt("posterFront", 0xFFFFFFFF);
        posterBack = sp.getInt("posterBack", 0xFFFFFFFF);
        longDigitW = sp.getFloat("longDigitW", 1f);
        longStroke = sp.getFloat("longStroke", 1f);
        iconScale = sp.getFloat("iconScale", 1f);
        themed = sp.getBoolean("themed", false);
        labels = sp.getBoolean("labels", true);
        columns = sp.getInt("columns", 4);
        wallpaper = sp.getString("wallpaper", "peak");
        depth = sp.getBoolean("depth", true);
        fingerprint = sp.getBoolean("fingerprint", true);
        swipeSearch = sp.getBoolean("swipeSearch", true);
        hz = sp.getInt("hz", 0);
        gestSens = sp.getFloat("gestSens", 1f);
        gestTop = sp.getFloat("gestTop", 0.33f);
        ctrlRight = sp.getBoolean("ctrlRight", true);
        keepGestures = sp.getBoolean("keepGestures", true);
        navGestures = sp.getBoolean("navGestures", true);
        navBack = sp.getBoolean("navBack", true);
        navPill = sp.getBoolean("navPill", true);
        liveClose = sp.getBoolean("liveClose", true);
        lockSystemBio = sp.getBoolean("lockSystemBio", true);
        ownRecents = sp.getBoolean("ownRecents", true);
        recentsView = sp.getString("recentsView", "row");
        pillHold = sp.getString("pillHold", "lens");
        autoUpdate = sp.getBoolean("autoUpdate", true);
        island = sp.getBoolean("island", true);
        islandVolume = sp.getBoolean("islandVolume", true);
        islandRides = sp.getBoolean("islandRides", true);
        islandMedia = sp.getBoolean("islandMedia", true);
        syncSystemWall = sp.getBoolean("syncSystemWall", true);
        sysWallHome = sp.getString("sysWallHome", "");
        sysWallLock = sp.getString("sysWallLock", "");
        swipeHintSeen = sp.getBoolean("swipeHintSeen", false);
        tiles = sp.getString("tiles", DEFAULT_TILES);
        home = split(sp.getString("home", ""));
        dock = split(sp.getString("dock", ""));
        hidden = new HashSet<>(split(sp.getString("hidden", "")));
        sounds = sp.getBoolean("sounds", true);
        soundVolume = sp.getFloat("soundVolume", 0.6f);
        haptics = sp.getBoolean("haptics", true);
        icons = sp.getString("icons", "vanks");
        edge = sp.getBoolean("edge", false);
        edgeLeft = sp.getBoolean("edgeLeft", false);
        edgeWindow = sp.getBoolean("edgeWindow", false);
        edgeApps = split(sp.getString("edgeApps", ""));
        lockWallpaper = sp.getString("lockWallpaper", "");
        lockClock = sp.getString("lockClock", "");
        if (lockClock.equals("poster")) lockClock = "long";   // «Афишу» заменили «Длинные»
        lockClockColor = sp.getString("lockClockColor", "white");
        lockClockSize = sp.getFloat("lockClockSize", 1f);
        lockClockW = sp.getFloat("lockClockW", 1f);
        lockClockH = sp.getFloat("lockClockH", 1f);
        lockClockX = sp.getFloat("lockClockX", 0.5f);
        lockClockY = sp.getFloat("lockClockY", 0f);
        clockToHome = sp.getBoolean("clockToHome", true);
        lockWidgets = sp.getString("lockWidgets", "");
        lockWidgetsPos = sp.getString("lockWidgetsPos", "under");
        fpEffect = sp.getString("fpEffect", "pulse");
        fpIcon = sp.getString("fpIcon", "classic");
        // в Vanks OS свой экран блокировки — единственный (системный выключен в прошивке)
        lockMain = sp.getBoolean("lockMain", Updater.osVersion() != null);
        aodMode = sp.getString("aodMode", "off");
        aodStyle = sp.getString("aodStyle", "big");
        fpAuto = sp.getBoolean("fpAuto", true);
        fpY = sp.getFloat("fpY", -1f);
        unlockWave = sp.getBoolean("unlockWave", true);
        setupDone = sp.getBoolean("setupDone", false);
        animSpeed = sp.getFloat("animSpeed", 1f);
        rev = sp.getInt("rev", 0);
    }

    static void save() {
        if (sp == null) return;
        rev++;
        sp.edit()
                .putInt("accent", accent)
                .putString("ui", ui)
                .putString("material", material)
                .putString("tone", tone)
                .putBoolean("wallAnimate", wallAnimate)
                .putInt("shape", shape)
                .putFloat("iconRound", iconRound)
                .putInt("iconRoundV", 9)
                .putString("posterKind", posterKind)
                .putInt("posterFront", posterFront)
                .putInt("posterBack", posterBack)
                .putFloat("longDigitW", longDigitW)
                .putFloat("longStroke", longStroke)
                .putFloat("iconScale", iconScale)
                .putBoolean("themed", themed)
                .putBoolean("labels", labels)
                .putInt("columns", columns)
                .putString("wallpaper", wallpaper)
                .putBoolean("depth", depth)
                .putBoolean("fingerprint", fingerprint)
                .putBoolean("swipeSearch", swipeSearch)
                .putBoolean("swipeHintSeen", swipeHintSeen)
                .putString("tiles", tiles)
                .putString("home", String.join("|", home))
                .putString("dock", String.join("|", dock))
                .putString("hidden", String.join("|", hidden))
                .putBoolean("sounds", sounds)
                .putFloat("soundVolume", soundVolume)
                .putBoolean("haptics", haptics)
                .putString("icons", icons)
                .putBoolean("edge", edge)
                .putBoolean("edgeLeft", edgeLeft)
                .putBoolean("edgeWindow", edgeWindow)
                .putString("edgeApps", String.join("|", edgeApps))
                .putString("lockWallpaper", lockWallpaper)
                .putString("lockClock", lockClock)
                .putString("lockClockColor", lockClockColor)
                .putFloat("lockClockSize", lockClockSize)
                .putFloat("lockClockW", lockClockW)
                .putFloat("lockClockH", lockClockH)
                .putFloat("lockClockX", lockClockX)
                .putFloat("lockClockY", lockClockY)
                .putBoolean("clockToHome", clockToHome)
                .putString("lockWidgets", lockWidgets)
                .putString("lockWidgetsPos", lockWidgetsPos)
                .putString("fpEffect", fpEffect)
                .putString("fpIcon", fpIcon)
                .putBoolean("lockMain", lockMain)
                .putString("aodMode", aodMode)
                .putString("aodStyle", aodStyle)
                .putInt("hz", hz)
                .putFloat("gestSens", gestSens)
                .putFloat("gestTop", gestTop)
                .putBoolean("ctrlRight", ctrlRight)
                .putBoolean("keepGestures", keepGestures)
                .putBoolean("navGestures", navGestures)
                .putBoolean("navBack", navBack)
                .putBoolean("navPill", navPill)
                .putBoolean("liveClose", liveClose)
                .putBoolean("lockSystemBio", lockSystemBio)
                .putBoolean("ownRecents", ownRecents)
                .putString("recentsView", recentsView)
                .putString("pillHold", pillHold)
                .putBoolean("autoUpdate", autoUpdate)
                .putBoolean("island", island)
                .putBoolean("islandVolume", islandVolume)
                .putBoolean("islandRides", islandRides)
                .putBoolean("islandMedia", islandMedia)
                .putBoolean("syncSystemWall", syncSystemWall)
                .putString("sysWallHome", sysWallHome)
                .putString("sysWallLock", sysWallLock)
                .putBoolean("fpAuto", fpAuto)
                .putFloat("fpY", fpY)
                .putBoolean("unlockWave", unlockWave)
                .putBoolean("setupDone", setupDone)
                .putFloat("animSpeed", animSpeed)
                .putInt("rev", rev)
                .apply();
        firstRun = false;
    }

    static SharedPreferences raw() {
        return sp;
    }

    /** Множитель длительности анимаций (настройка «Скорость анимаций»). */
    static float k() {
        return 1f / Math.max(0.25f, animSpeed);
    }

    private static ArrayList<String> split(String s) {
        ArrayList<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        out.addAll(Arrays.asList(s.split("\\|")));
        out.removeIf(String::isEmpty);
        return out;
    }

    private Prefs() {}
}
