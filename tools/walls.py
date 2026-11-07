#!/usr/bin/env python3
"""Живые и супер обои Delta для лаунчера.

    python3 -m venv /tmp/sk && /tmp/sk/bin/pip install skia-python
    /tmp/sk/bin/python tools/walls.py

Берёт список из delta/shell/wallpaper/SuperWalls.qml и шейдеры оттуда же:
  res/raw/wall_*.agsl          — шейдеры в AGSL (tools/agsl.py);
  res/drawable-nodpi/s_*.jpg   — готовый кадр каждого обоя в портрете (для
                                 Android до 13, стекла, превью) и его размытая копия;
  res/drawable-nodpi/wall_*    — текстуры: карта Луны, фото и маска «Вершины»;
  src/.../WallList.java        — список для кода.
Запускать после правки обоев в Delta.
"""
import os
import re
import sys

import skia

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SUPER = os.path.join(ROOT, "..", "..", "delta", "shell", "wallpaper")
RAW = os.path.join(ROOT, "res", "raw")
NODPI = os.path.join(ROOT, "res", "drawable-nodpi")
JAVA = os.path.join(ROOT, "src", "os", "vanks", "launcher", "WallList.java")
W, H = 720, 1560          # кадр: телефон 9:19.5
BW, BH = 180, 390         # размытая копия

sys.path.insert(0, HERE)
import agsl  # noqa: E402

# id, совпадающие с фото-обоями лаунчера, получают свои
RENAME = {"moon": "moon-super", "peak": "peak-live"}


def catalog():
    qml = open(os.path.join(SUPER, "SuperWalls.qml")).read()
    groups = dict(re.findall(r'\{ id: "(\w+)",\s*title: "([^"]+)" \}', qml))
    out = []
    for m in re.finditer(r'\{ id: "([\w-]+)",\s*kind: "(shader|flow)",([^}]*)\}', qml):
        wid, kind, rest = m.groups()
        f = dict(re.findall(r'(\w+): "([^"]*)"', rest))
        mat = re.search(r"material: (\d)", rest)
        out.append({
            "id": RENAME.get(wid, wid), "src": wid, "kind": kind, "title": f["title"], "frag": f.get("frag", "circles"),
            "group": groups.get(f.get("group", ""), "Супер обои"),
            "colors": [f.get(k, f.get("b", "#000000")) for k in "abcd"] if "a" in f else None,
            "material": int(mat.group(1)) if mat else 0,
            "photo": "photo" in f,
        })
    for i, g, f, t, after in PHONE:
        at = next((k for k, w in enumerate(out) if w["id"] == after), len(out) - 1)
        out.insert(at + 1, {"id": i, "src": i, "kind": "flow", "title": t, "frag": f, "group": groups.get(g, "Супер обои"),
                            "colors": None, "material": 0, "photo": False})
    # свои цвета телефона — сразу за обоями той же группы
    for e in extra(groups):
        last = max((i for i, w in enumerate(out) if w["group"] == e["group"]), default=len(out) - 1)
        out.insert(last + 1, e)
    return out


# Только у телефона: ещё цвета для тех же шейдеров (в Delta их нет).
# id, группа (как в SuperWalls.qml), шейдер, название, материал, a b c d.
EXTRA = [
    ("flow-ocean", "circles", "circles", "Океан", 0, "#021526", "#0077b6", "#90e0ef", "#00b4d8"),
    ("flow-rose", "circles", "circles", "Роза", 0, "#2a0a18", "#e0457b", "#ffd6e5", "#ff8fab"),
    ("flow-lemon", "circles", "circles", "Лимон", 0, "#1f2a05", "#c9d82b", "#fffbd1", "#7fcf4a"),
    ("you-sky", "you", "you", "Небо", 0, "#e3eeff", "#2c5bab", "#a9c8ff", "#ffb18a"),
    ("you-sand", "you", "you", "Песок", 0, "#f6ecdf", "#8a5a2b", "#e8c9a0", "#5a7d8a"),
    ("you-cloud", "you", "you", "Облако", 0, "#eef0f4", "#4a5568", "#c7cdd8", "#9aa9ff"),
    ("glass-ember", "glass", "glass", "Угли", 0, "#120604", "#ff5a1f", "#ffb347", "#b3122e"),
    ("glass-mint", "glass", "glass", "Мята", 0, "#03140f", "#19d3a2", "#6be3ff", "#c0ff8a"),
    ("waves-ocean", "waves", "waves", "Океан", 1, "#031424", "#1a7fd6", "#c8ecff", "#2ad1c9"),
    ("waves-gold", "waves", "waves", "Золото", 2, "#0f0a04", "#b88a2e", "#fff0c2", "#6b4a1f"),
    ("mountains-night", "mountains", "mountains", "Ночь", 0, "#070b22", "#3b4c9a", "#0d1126", "#c9d4ff"),
    ("dots-red", "nothing", "dots", "Красные", 0, "#c8141e", "#ffffff", "#111111", "#a01018"),
]


# Только у телефона, со своим шейдером (res/raw/wall_<frag>.agsl правится
# руками) и текстурами, запечёнными tools/peak_bake.cpp: id, группа, шейдер,
# название, встать после id.
PHONE = [
    ("summit", "mountains", "summit", "Вершина вблизи", "peak-live"),
]


def extra(groups):
    return [{"id": i, "src": i, "kind": "flow", "title": t, "frag": f, "group": groups.get(g, "Супер обои"),
             "colors": list(cols), "material": m, "photo": False}
            for i, g, f, t, m, *cols in EXTRA]


def hexc(h):
    h = h.lstrip("#")
    return [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)] + [1.0]


def render(w, effects, tex, width, height):
    b = skia.RuntimeShaderBuilder(effects[w["frag"]])
    b.setUniform("res", skia.V2(float(width), float(height)))
    b.setUniform("zoom", 1.0)
    b.setUniform("time", 0.0)
    if w["kind"] == "flow":
        b.setUniform("intro", 1.0)
        if w["colors"]:
            for k, v in zip("ABCD", w["colors"]):
                b.setUniform("color" + k, skia.V4(*hexc(v)))
            b.setUniform("material", float(w["material"]))
    for name, im in tex.get(w["frag"], {}).items():
        b.setChild(name, im.makeShader(skia.TileMode.kRepeat, skia.TileMode.kClamp, skia.SamplingOptions(skia.FilterMode.kLinear)))
        b.setUniform(name + "Size", skia.V2(float(im.width()), float(im.height())))
    surf = skia.Surface(width, height)
    surf.getCanvas().drawRect(skia.Rect(0, 0, width, height), skia.Paint(Shader=b.makeShader()))
    return surf.makeImageSnapshot()


def scaled(im, width, height):
    surf = skia.Surface(width, height)
    surf.getCanvas().drawImageRect(im, skia.Rect(0, 0, width, height), skia.SamplingOptions(skia.FilterMode.kLinear))
    return surf.makeImageSnapshot()


def blurred(im):
    small = scaled(im, BW, BH)
    surf = skia.Surface(BW, BH)
    p = skia.Paint(ImageFilter=skia.ImageFilters.Blur(9, 9, skia.TileMode.kClamp))
    surf.getCanvas().drawImage(small, 0, 0, skia.SamplingOptions(), p)
    return surf.makeImageSnapshot()


def save_jpg(im, path, q=88):
    data = im.encodeToData(skia.EncodedImageFormat.kJPEG, q)
    open(path, "wb").write(bytes(data))


def res_name(wid):
    return "s_" + wid.replace("-", "_")


def main():
    os.makedirs(RAW, exist_ok=True)
    walls = catalog()
    agsl.main_to(os.path.join(SUPER, "super"), RAW)
    effects = {}
    for w in walls:
        if w["frag"] not in effects:
            effects[w["frag"]] = skia.RuntimeEffect.MakeForShader(open(os.path.join(RAW, "wall_" + w["frag"] + ".agsl")).read())
    moon = scaled(skia.Image.open(os.path.join(SUPER, "super", "moon-map.jpg")), 2048, 1024)
    photo = scaled(skia.Image.open(os.path.join(SUPER, "super", "peak.jpg")), 2048, 1152)
    mask = skia.Image.open(os.path.join(SUPER, "super", "peak-mask.png"))
    save_jpg(moon, os.path.join(NODPI, "wall_moon_map.jpg"), 90)
    save_jpg(photo, os.path.join(NODPI, "wall_peak_photo.jpg"), 90)
    open(os.path.join(NODPI, "wall_peak_mask.png"), "wb").write(bytes(mask.encodeToData(skia.EncodedImageFormat.kPNG, 100)))
    tex = {"moon": {"moonMap": moon}, "peak": {"photo": photo, "mask": mask},
           "summit": {"photo": skia.Image.open(os.path.join(NODPI, "wall_summit_photo.jpg")),
                      "mask": skia.Image.open(os.path.join(NODPI, "wall_summit_mask.png"))}}
    for w in walls:
        im = render(w, effects, tex, W, H)
        save_jpg(im, os.path.join(NODPI, res_name(w["id"]) + ".jpg"))
        save_jpg(blurred(im), os.path.join(NODPI, res_name(w["id"]) + "_blur.jpg"), 90)
        print(w["id"], w["group"], w["title"])

    lines = []
    for w in walls:
        cols = "new int[]{" + ", ".join("0xFF" + c.lstrip("#").upper() for c in w["colors"]) + "}" if w["colors"] else "null"
        lines.append(f'            new Walls.W("{w["id"]}", Walls.{w["kind"].upper()}, "{w["group"]}", "{w["title"]}", "{w["frag"]}", {cols}, '
                     f'{w["material"]}, R.drawable.{res_name(w["id"])}, R.drawable.{res_name(w["id"])}_blur),')
    java = (
        "package os.vanks.launcher;\n\n"
        "// Сделано tools/walls.py из delta/shell/wallpaper/SuperWalls.qml (и EXTRA там же) — правьте там.\n"
        "final class WallList {\n"
        "    static final Walls.W[] DELTA = {\n" + "\n".join(lines) + "\n    };\n\n"
        "    private WallList() {}\n}\n")
    open(JAVA, "w").write(java)


if __name__ == "__main__":
    main()
