package os.vanks.launcher;

// Сделано tools/walls.py из delta/shell/wallpaper/SuperWalls.qml (и EXTRA там же) — правьте там.
final class WallList {
    static final Walls.W[] DELTA = {
            new Walls.W("blackhole", Walls.SHADER, "Супер обои", "Чёрная дыра", "blackhole", null, 0, R.drawable.s_blackhole, R.drawable.s_blackhole_blur),
            new Walls.W("moon-super", Walls.SHADER, "Супер обои", "Луна", "moon", null, 0, R.drawable.s_moon_super, R.drawable.s_moon_super_blur),
            new Walls.W("earth", Walls.SHADER, "Супер обои", "Земля", "earth", null, 0, R.drawable.s_earth, R.drawable.s_earth_blur),
            new Walls.W("prism", Walls.SHADER, "Супер обои", "Стеклянные грани", "prism", null, 0, R.drawable.s_prism, R.drawable.s_prism_blur),
            new Walls.W("flow-blue", Walls.FLOW, "Круги", "Синие", "circles", new int[]{0xFF0A1C4A, 0xFF1F6DFF, 0xFF9FDCFF, 0xFF36C6FF}, 0, R.drawable.s_flow_blue, R.drawable.s_flow_blue_blur),
            new Walls.W("flow-violet", Walls.FLOW, "Круги", "Фиолетовые", "circles", new int[]{0xFF1D0D47, 0xFF7A3CFF, 0xFFE2B8FF, 0xFFFF5FD2}, 0, R.drawable.s_flow_violet, R.drawable.s_flow_violet_blur),
            new Walls.W("flow-coral", Walls.FLOW, "Круги", "Коралловые", "circles", new int[]{0xFF3D0D1C, 0xFFF0523F, 0xFFFFD2A8, 0xFFFFB03A}, 0, R.drawable.s_flow_coral, R.drawable.s_flow_coral_blur),
            new Walls.W("flow-green", Walls.FLOW, "Круги", "Зелёные", "circles", new int[]{0xFF04302B, 0xFF14B48A, 0xFFC4FFD9, 0xFFA6E84A}, 0, R.drawable.s_flow_green, R.drawable.s_flow_green_blur),
            new Walls.W("flow-sunset", Walls.FLOW, "Круги", "Закат", "circles", new int[]{0xFF24062E, 0xFFFF4F7B, 0xFFFFE0B5, 0xFFFFA63D}, 0, R.drawable.s_flow_sunset, R.drawable.s_flow_sunset_blur),
            new Walls.W("flow-aurora", Walls.FLOW, "Круги", "Сияние", "circles", new int[]{0xFF031A2A, 0xFF19C3A7, 0xFFD8FFF4, 0xFF7A5CFF}, 0, R.drawable.s_flow_aurora, R.drawable.s_flow_aurora_blur),
            new Walls.W("flow-ocean", Walls.FLOW, "Круги", "Океан", "circles", new int[]{0xFF021526, 0xFF0077B6, 0xFF90E0EF, 0xFF00B4D8}, 0, R.drawable.s_flow_ocean, R.drawable.s_flow_ocean_blur),
            new Walls.W("flow-rose", Walls.FLOW, "Круги", "Роза", "circles", new int[]{0xFF2A0A18, 0xFFE0457B, 0xFFFFD6E5, 0xFFFF8FAB}, 0, R.drawable.s_flow_rose, R.drawable.s_flow_rose_blur),
            new Walls.W("flow-lemon", Walls.FLOW, "Круги", "Лимон", "circles", new int[]{0xFF1F2A05, 0xFFC9D82B, 0xFFFFFBD1, 0xFF7FCF4A}, 0, R.drawable.s_flow_lemon, R.drawable.s_flow_lemon_blur),
            new Walls.W("you-lavender", Walls.FLOW, "Material You", "Лаванда", "you", new int[]{0xFFECE1FF, 0xFF6F43C0, 0xFFC9B3FF, 0xFFFF8FB1}, 0, R.drawable.s_you_lavender, R.drawable.s_you_lavender_blur),
            new Walls.W("you-mint", Walls.FLOW, "Material You", "Мята", "you", new int[]{0xFFD8F5E7, 0xFF006C4F, 0xFF8BE0B8, 0xFFFF9F7F}, 0, R.drawable.s_you_mint, R.drawable.s_you_mint_blur),
            new Walls.W("you-peach", Walls.FLOW, "Material You", "Персик", "you", new int[]{0xFFFFE6D8, 0xFF9C4323, 0xFFFFB68F, 0xFF56703F}, 0, R.drawable.s_you_peach, R.drawable.s_you_peach_blur),
            new Walls.W("you-night", Walls.FLOW, "Material You", "Ночь", "you", new int[]{0xFF1C1B25, 0xFF4B5BD6, 0xFFBCC3FF, 0xFFFFB4A9}, 0, R.drawable.s_you_night, R.drawable.s_you_night_blur),
            new Walls.W("you-sky", Walls.FLOW, "Material You", "Небо", "you", new int[]{0xFFE3EEFF, 0xFF2C5BAB, 0xFFA9C8FF, 0xFFFFB18A}, 0, R.drawable.s_you_sky, R.drawable.s_you_sky_blur),
            new Walls.W("you-sand", Walls.FLOW, "Material You", "Песок", "you", new int[]{0xFFF6ECDF, 0xFF8A5A2B, 0xFFE8C9A0, 0xFF5A7D8A}, 0, R.drawable.s_you_sand, R.drawable.s_you_sand_blur),
            new Walls.W("you-cloud", Walls.FLOW, "Material You", "Облако", "you", new int[]{0xFFEEF0F4, 0xFF4A5568, 0xFFC7CDD8, 0xFF9AA9FF}, 0, R.drawable.s_you_cloud, R.drawable.s_you_cloud_blur),
            new Walls.W("glass-lagoon", Walls.FLOW, "Жидкое стекло", "Лагуна", "glass", new int[]{0xFF04161F, 0xFF00A6C8, 0xFF3B5BFF, 0xFF8CF2D0}, 0, R.drawable.s_glass_lagoon, R.drawable.s_glass_lagoon_blur),
            new Walls.W("glass-dawn", Walls.FLOW, "Жидкое стекло", "Рассвет", "glass", new int[]{0xFF1A0A1C, 0xFFFF6A88, 0xFFFFB35C, 0xFFA77BFF}, 0, R.drawable.s_glass_dawn, R.drawable.s_glass_dawn_blur),
            new Walls.W("glass-night", Walls.FLOW, "Жидкое стекло", "Полночь", "glass", new int[]{0xFF05060D, 0xFF3A46A8, 0xFF8A2FA0, 0xFF1F8FA3}, 0, R.drawable.s_glass_night, R.drawable.s_glass_night_blur),
            new Walls.W("glass-ember", Walls.FLOW, "Жидкое стекло", "Угли", "glass", new int[]{0xFF120604, 0xFFFF5A1F, 0xFFFFB347, 0xFFB3122E}, 0, R.drawable.s_glass_ember, R.drawable.s_glass_ember_blur),
            new Walls.W("glass-mint", Walls.FLOW, "Жидкое стекло", "Мята", "glass", new int[]{0xFF03140F, 0xFF19D3A2, 0xFF6BE3FF, 0xFFC0FF8A}, 0, R.drawable.s_glass_mint, R.drawable.s_glass_mint_blur),
            new Walls.W("waves-matte", Walls.FLOW, "Волны", "Матовые", "waves", new int[]{0xFF0B1630, 0xFF2F6BD8, 0xFFA8D4FF, 0xFF7F5CF0}, 0, R.drawable.s_waves_matte, R.drawable.s_waves_matte_blur),
            new Walls.W("waves-glass", Walls.FLOW, "Волны", "Стеклянные", "waves", new int[]{0xFF071A24, 0xFF19B3C9, 0xFFE0FBFF, 0xFF6A7DFF}, 1, R.drawable.s_waves_glass, R.drawable.s_waves_glass_blur),
            new Walls.W("waves-metal", Walls.FLOW, "Волны", "Металл", "waves", new int[]{0xFF0D0D10, 0xFF8A6B3D, 0xFFFFF1D0, 0xFF4E5566}, 2, R.drawable.s_waves_metal, R.drawable.s_waves_metal_blur),
            new Walls.W("waves-silk", Walls.FLOW, "Волны", "Шёлк", "waves", new int[]{0xFF26071A, 0xFFC2275B, 0xFFFFD1E0, 0xFFFF8A3D}, 2, R.drawable.s_waves_silk, R.drawable.s_waves_silk_blur),
            new Walls.W("waves-ocean", Walls.FLOW, "Волны", "Океан", "waves", new int[]{0xFF031424, 0xFF1A7FD6, 0xFFC8ECFF, 0xFF2AD1C9}, 1, R.drawable.s_waves_ocean, R.drawable.s_waves_ocean_blur),
            new Walls.W("waves-gold", Walls.FLOW, "Волны", "Золото", "waves", new int[]{0xFF0F0A04, 0xFFB88A2E, 0xFFFFF0C2, 0xFF6B4A1F}, 2, R.drawable.s_waves_gold, R.drawable.s_waves_gold_blur),
            new Walls.W("peak-live", Walls.FLOW, "Горы", "Вершина", "peak", null, 0, R.drawable.s_peak_live, R.drawable.s_peak_live_blur),
            new Walls.W("summit", Walls.FLOW, "Горы", "Вершина вблизи", "summit", null, 0, R.drawable.s_summit, R.drawable.s_summit_blur),
            new Walls.W("mountains-dawn", Walls.FLOW, "Горы", "Рассвет", "mountains", new int[]{0xFF33427A, 0xFFF4B28C, 0xFF2C3150, 0xFFFFE6D6}, 0, R.drawable.s_mountains_dawn, R.drawable.s_mountains_dawn_blur),
            new Walls.W("mountains-day", Walls.FLOW, "Горы", "День", "mountains", new int[]{0xFF3F7FCF, 0xFFCFE5F4, 0xFF2E4A5F, 0xFFFFFFFF}, 0, R.drawable.s_mountains_day, R.drawable.s_mountains_day_blur),
            new Walls.W("mountains-dusk", Walls.FLOW, "Горы", "Закат", "mountains", new int[]{0xFF26163F, 0xFFFF8D5E, 0xFF2A1B34, 0xFFFFC6AA}, 0, R.drawable.s_mountains_dusk, R.drawable.s_mountains_dusk_blur),
            new Walls.W("mountains-night", Walls.FLOW, "Горы", "Ночь", "mountains", new int[]{0xFF070B22, 0xFF3B4C9A, 0xFF0D1126, 0xFFC9D4FF}, 0, R.drawable.s_mountains_night, R.drawable.s_mountains_night_blur),
            new Walls.W("dots-black", Walls.FLOW, "Nothing", "Чёрные", "dots", new int[]{0xFF000000, 0xFFF2F2F2, 0xFFD71921, 0xFF262626}, 0, R.drawable.s_dots_black, R.drawable.s_dots_black_blur),
            new Walls.W("dots-white", Walls.FLOW, "Nothing", "Белые", "dots", new int[]{0xFFF0F0F0, 0xFF111111, 0xFFD71921, 0xFFD4D4D4}, 0, R.drawable.s_dots_white, R.drawable.s_dots_white_blur),
            new Walls.W("dots-red", Walls.FLOW, "Nothing", "Красные", "dots", new int[]{0xFFC8141E, 0xFFFFFFFF, 0xFF111111, 0xFFA01018}, 0, R.drawable.s_dots_red, R.drawable.s_dots_red_blur),
    };

    private WallList() {}
}
