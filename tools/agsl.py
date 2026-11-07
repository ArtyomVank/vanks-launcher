#!/usr/bin/env python3
"""Шейдеры обоев Delta (GLSL 440 для Qt) → AGSL для Android 13+.

    tools/agsl.py ../../delta/shell/wallpaper/super res/raw

Переводит механически: блок uniform — в отдельные uniform, main — в
half4 main(float2), sampler2D — в uniform shader (размер картинки — uniform
<имя>Size), texture() — в eval() в пикселях. qt_Opacity убирается.
"""
import os
import re
import sys

NAMES = ["blackhole", "moon", "earth", "prism", "circles", "you", "glass", "waves", "mountains", "dots", "peak"]


def convert(src):
    s = src
    s = re.sub(r"#version\s+\d+\s*\n", "", s)
    s = re.sub(r"layout\(location = 0\) (in|out) vec\d \w+;\s*\n", "", s)
    # блок uniform → отдельные
    m = re.search(r"layout\(std140, binding = 0\) uniform buf \{(.*?)\};", s, re.S)
    decl = []
    for line in m.group(1).splitlines():
        line = line.split("//")[0].strip()
        if not line or "qt_Matrix" in line or "qt_Opacity" in line:
            continue
        t, n = line.rstrip(";").split()
        decl.append(f"uniform {t} {n};")
    samplers = re.findall(r"layout\(binding = \d+\) uniform sampler2D (\w+);", s)
    for name in samplers:
        decl.append(f"uniform shader {name};")
        decl.append(f"uniform vec2 {name}Size;")
    s = s[:m.start()] + "\n".join(decl) + s[m.end():]
    s = re.sub(r"layout\(binding = \d+\) uniform sampler2D \w+;\s*\n", "", s)
    # мип-уровня вручную (dFdx, textureGrad) в AGSL нет — берём обычную выборку
    s = re.sub(r"[ \t]*vec2 dx = dFdx\(\w+\), dy = dFdy\(\w+\);\n", "", s)
    s = re.sub(r"[ \t]*if \(abs\(d[xy]\.x\) > 0\.5\) d[xy]\.x = 0\.0;\n", "", s)
    s = re.sub(r"textureGrad\((\w+), (.*?), dx, dy\)", r"texture(\1, \2)", s)
    for name in samplers:
        s = re.sub(r"texture\(" + name + r",\s*([^;]*?)\)(\.(?:rgba|rgb|r|g|a)\b|;)",
                   lambda mm: f"{name}.eval(clamp({mm.group(1)}, 0.0, 1.0) * {name}Size){mm.group(2)}", s)
    # transpose() для mat3 в AGSL нет
    if "transpose(" in s:
        s = s.replace("transpose(", "transpose3(")
        helper = ("mat3 transpose3(mat3 m) {\n"
                  "    return mat3(m[0][0], m[1][0], m[2][0], m[0][1], m[1][1], m[2][1], m[0][2], m[1][2], m[2][2]);\n}\n")
        s = s.replace("\n".join(decl), "\n".join(decl) + "\n\n" + helper, 1)
    s = s.replace("void main() {", "half4 main(vec2 fragCoord) {\n    vec2 qt_TexCoord0 = fragCoord / res;", 1)
    s = re.sub(r"fragColor = (.*?) \* qt_Opacity;", r"return half4(\1);", s)
    return s


def main():
    main_to(sys.argv[1], sys.argv[2])


def main_to(src, dst):
    os.makedirs(dst, exist_ok=True)
    for n in NAMES:
        with open(os.path.join(src, n + ".frag")) as f:
            out = convert(f.read())
        with open(os.path.join(dst, "wall_" + n + ".agsl"), "w") as f:
            f.write("// Сделано tools/agsl.py из delta/shell/wallpaper/super/" + n + ".frag — правьте там.\n" + out)


if __name__ == "__main__":
    main()
