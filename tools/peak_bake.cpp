// Запекание обоев «Вершина» на процессоре — тот же шейдер, что
// tools/bake/peak.frag (сцена Delta), но без видеокарты и окна, и с любой
// камерой: для телефона — вертикальный кадр ближе к пику.
//
//     g++ -O3 -march=native -fopenmp tools/peak_bake.cpp -o /tmp/peak_bake
//     /tmp/peak_bake W H out.ppm out-mask.ppm [rox roy roz tax tay taz focal [xl xr yb yt]]
// xl xr yb yt — края плоскости кадра (по умолчанию ±ширина/высота и ±1):
// шире кадра — чтобы шейдеру было куда повернуть камеру.
//
// Цвет — как pass 0, маска — как pass 1 (R — небо, G — высота, B — даль).
#include <glm/glm.hpp>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

using namespace glm;

static float hash(vec2 p) { return fract(std::sin(dot(p, vec2(127.1f, 311.7f))) * 43758.5453f); }
static float vnoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    vec2 u = f * f * (3.0f - 2.0f * f);
    return mix(mix(hash(i), hash(i + vec2(1, 0)), u.x), mix(hash(i + vec2(0, 1)), hash(i + vec2(1, 1)), u.x), u.y);
}
static float fbm(vec2 p, int oct) {
    float a = 0.5f, s = 0.0f;
    for (int i = 0; i < oct; i++) {
        s += a * vnoise(p);
        p = mat2(1.6f, 1.2f, -1.2f, 1.6f) * p + vec2(3.1f, 1.7f);
        a *= 0.5f;
    }
    return s;
}
static float ridged(vec2 p, int oct) {
    float a = 0.5f, s = 0.0f;
    for (int i = 0; i < oct; i++) {
        float n = 1.0f - std::abs(vnoise(p) * 2.0f - 1.0f);
        s += a * n * n;
        p = mat2(1.7f, 1.1f, -1.1f, 1.7f) * p + vec2(5.3f, 2.9f);
        a *= 0.5f;
    }
    return s;
}
static float smax(float a, float b, float k) {
    float h = clamp(0.5f + 0.5f * (b - a) / k, 0.0f, 1.0f);
    return mix(a, b, h) + k * h * (1.0f - h);
}
static float needle(vec2 p, vec2 c, float H, float k, int oct) {
    vec2 d = p - c;
    float fac = max(max(std::abs(dot(d, vec2(0.95f, 0.31f))), std::abs(dot(d, vec2(-0.62f, 0.78f)))), dot(d, vec2(0.18f, -0.98f)));
    float r = mix(length(d * vec2(1.0f, 1.3f)), fac, 0.65f);
    float g = ridged(vec2(d.x * 3.0f + d.y * 1.3f, r * 0.7f + c.x * 1.7f), oct);
    float b = fbm(d * 1.4f + c, oct);
    return H - k * r + (g - 0.45f) * 0.95f * smoothstep(0.0f, 0.9f, r) + (b - 0.5f) * 0.6f * smoothstep(0.2f, 1.5f, r);
}
static float terrain(vec2 p, int oct) {
    float h = needle(p, vec2(0.0f, 7.5f), 7.0f, 2.8f, oct);
    h = smax(h, needle(p, vec2(2.0f, 8.6f), 4.2f, 3.0f, oct), 0.25f);
    h = smax(h, needle(p, vec2(2.7f, 9.0f), 3.8f, 3.1f, oct), 0.2f);
    h = smax(h, needle(p, vec2(3.4f, 8.8f), 3.3f, 3.1f, oct), 0.2f);
    h = smax(h, needle(p, vec2(4.2f, 9.6f), 2.9f, 2.8f, oct), 0.2f);
    h = smax(h, needle(p, vec2(5.2f, 10.4f), 2.6f, 2.5f, oct), 0.2f);
    h = smax(h, needle(p, vec2(-1.7f, 8.3f), 3.9f, 2.6f, oct), 0.3f);
    h = smax(h, needle(p, vec2(-3.2f, 9.5f), 3.0f, 2.2f, oct), 0.3f);
    h = smax(h, needle(p, vec2(-5.0f, 10.5f), 2.4f, 2.0f, oct), 0.3f);
    float base = 2.4f * std::exp(-std::pow((p.y - 8.2f) / 2.2f, 2.0f)) * (0.7f + 0.6f * fbm(p * 0.35f, 3)) * (1.0f - 0.08f * max(p.x, 0.0f))
               + 0.3f * ridged(p * 0.9f, oct) - 0.4f;
    h = smax(h, base, 0.6f);
    float far = (2.2f + 1.6f * ridged(vec2(p.x * 0.25f, 1.3f), 4)) * smoothstep(13.0f, 19.0f, p.y) + 0.4f * ridged(p * 0.5f, oct) - 0.6f;
    h = max(h, far);
    float mid = 1.6f * std::exp(-std::pow((p.y - 6.0f) / 1.4f, 2.0f)) * smoothstep(1.5f, 4.5f, p.x) * (0.6f + 0.6f * ridged(p * 0.7f + 2.0f, oct));
    h = max(h, mid - 0.2f);
    float valley = -0.5f + 0.3f * fbm(p * 0.5f, 3) + 0.1f * ridged(p * 2.0f, oct);
    h = max(h, valley);
    float fg = (1.5f - 0.22f * p.x) * std::exp(-std::pow((p.y - 1.6f) / 1.0f, 2.0f)) * (0.6f + 0.5f * fbm(p * 0.8f + 3.0f, oct))
             + 0.18f * ridged(p * 2.2f + 7.0f, oct) * std::exp(-std::pow((p.y - 1.6f) / 1.2f, 2.0f));
    h = max(h, fg - 0.35f);
    return h;
}

static const vec3 SUN = vec3(-0.88f, 0.28f, -0.25f);

static vec3 sky(vec3 rd) {
    float y = max(rd.y, 0.0f);
    vec3 top = vec3(0.012f, 0.045f, 0.17f), hor = vec3(0.26f, 0.46f, 0.70f);
    vec3 col = mix(hor, top, std::pow(y, 0.55f));
    float s = max(dot(normalize(vec3(rd.x, 0.0f, rd.z)), normalize(vec3(SUN.x, 0.0f, SUN.z))), 0.0f);
    col += vec3(0.55f, 0.28f, 0.22f) * std::pow(s, 3.0f) * std::exp(-y * 9.0f) * 0.6f;
    return col;
}
static float march(vec3 ro, vec3 rd, vec3 &pos) {
    float t = 0.05f;
    for (int i = 0; i < 420; i++) {
        vec3 p = ro + rd * t;
        float d = p.y - terrain(vec2(p.x, p.z), 6);
        if (d < 0.0008f * t) { pos = p; return t; }
        if (t > 60.0f) break;
        t += max(0.004f, d * 0.3f);
    }
    pos = ro + rd * t;
    return -1.0f;
}
static float shadow(vec3 p) {
    float s = 1.0f, t = 0.03f;
    for (int i = 0; i < 90; i++) {
        vec3 q = p + normalize(SUN) * t;
        float d = q.y - terrain(vec2(q.x, q.z), 4);
        s = min(s, 12.0f * d / t);
        if (s < 0.0f || q.y > 7.0f) break;
        t += max(0.02f, d * 0.5f);
    }
    return clamp(s, 0.0f, 1.0f);
}
static vec3 normalOf(vec2 p, float e, int oct) {
    float hx = terrain(p + vec2(e, 0), oct) - terrain(p - vec2(e, 0), oct);
    float hz = terrain(p + vec2(0, e), oct) - terrain(p - vec2(0, e), oct);
    return normalize(vec3(-hx, 2.0f * e, -hz));
}

int main(int argc, char **argv) {
    if (argc < 5) {
        std::fprintf(stderr, "usage: %s W H out.ppm out-mask.ppm [rox roy roz tax tay taz focal]\n", argv[0]);
        return 1;
    }
    int W = std::atoi(argv[1]), H = std::atoi(argv[2]);
    vec3 ro(-0.4f, 1.9f, -2.2f), ta(0.4f, 3.2f, 7.5f);
    float focal = 1.6f;
    if (argc >= 12) {
        ro = vec3(std::atof(argv[5]), std::atof(argv[6]), std::atof(argv[7]));
        ta = vec3(std::atof(argv[8]), std::atof(argv[9]), std::atof(argv[10]));
        focal = std::atof(argv[11]);
    }
    float xl = W / (float)H, xr = xl, yb = 1, yt = 1;
    if (argc >= 16) {
        xl = std::atof(argv[12]);
        xr = std::atof(argv[13]);
        yb = std::atof(argv[14]);
        yt = std::atof(argv[15]);
    }
    std::vector<unsigned char> img(W * H * 3), msk(W * H * 3);
    vec3 fw = normalize(ta - ro);
    vec3 rt = normalize(cross(vec3(0, 1, 0), fw));
    vec3 up = cross(fw, rt);
    vec3 L = normalize(SUN);
    // плоскость кадра на расстоянии focal: по x от −xl до xr, по y от −yb до yt
#pragma omp parallel for schedule(dynamic, 1)
    for (int y = 0; y < H; y++) {
        for (int x = 0; x < W; x++) {
            vec2 q((x + 0.5f) / W, (y + 0.5f) / H);
            vec2 uv(-xl + q.x * (xl + xr), yt - q.y * (yt + yb));
            vec3 rd = normalize(uv.x * rt + uv.y * up + focal * fw);
            vec3 pos;
            float t = march(ro, rd, pos);
            int k = (y * W + x) * 3;
            float skyMask = t < 0 ? 1.0f : 0.0f;
            float height = t < 0 ? 1.0f : clamp(pos.y / 7.0f, 0.0f, 1.0f);
            float far = t < 0 ? 1.0f : clamp(t / 25.0f, 0.0f, 1.0f);
            msk[k] = (unsigned char)std::lround(skyMask * 255);
            msk[k + 1] = (unsigned char)std::lround(height * 255);
            msk[k + 2] = (unsigned char)std::lround(far * 255);
            vec3 col;
            if (t < 0) col = sky(rd);
            else {
                vec2 pz(pos.x, pos.z);
                vec3 n = normalOf(pz, 0.0015f + 0.0006f * t, 8);
                vec3 nb = normalize(mix(n, normalOf(pz, 0.03f + 0.002f * t, 3), 0.7f));
                float v = fbm(pz * 3.0f + pos.y * 2.0f, 5);
                vec3 rock = mix(vec3(0.36f, 0.30f, 0.27f), vec3(0.62f, 0.54f, 0.48f), v);
                rock *= 0.85f + 0.3f * ridged(vec2(pos.x * 6.0f + pos.z * 2.0f, pos.y * 1.5f), 4);
                float snow = smoothstep(0.62f, 0.86f, nb.y + 0.35f * (fbm(pz * 4.0f, 4) - 0.5f));
                snow *= 1.0f - smoothstep(4.6f, 5.8f, pos.y);
                vec3 alb = mix(rock, vec3(0.92f, 0.93f, 0.97f), snow);
                float sh = shadow(pos + n * 0.01f);
                float dif = std::pow(max(dot(nb, L), 0.0f), 0.8f) * sh;
                dif *= mix(0.12f, 1.0f, smoothstep(0.7f, 3.0f, pos.y));
                vec3 sun = vec3(1.0f, 0.40f, 0.13f) * 4.6f;
                vec3 amb = vec3(0.09f, 0.13f, 0.30f) * (0.55f + 0.45f * nb.y);
                vec3 bounce = vec3(0.26f, 0.11f, 0.14f) * max(dot(nb, -L * vec3(1, 0, 1)), 0.0f) * 0.5f;
                float ao = clamp(0.4f + 0.6f * (pos.y - terrain(pz, 2) + 0.6f), 0.4f, 1.0f);
                col = alb * (sun * dif + amb * ao + bounce);
                float fog = 1.0f - std::exp(-max(t - 6.0f, 0.0f) * 0.06f);
                col = mix(col, vec3(0.24f, 0.36f, 0.58f), fog * 0.85f);
                col = mix(col, vec3(0.62f, 0.62f, 0.78f), (1.0f - smoothstep(0.1f, 0.9f, pos.y)) * 0.35f * smoothstep(5.0f, 10.0f, t));
            }
            col = 1.0f - exp(-col * 0.85f);
            col = pow(col, vec3(1.0f / 2.2f));
            col = mix(vec3(dot(col, vec3(0.299f, 0.587f, 0.114f))), col, t < 0 ? 1.2f : 1.75f);
            col = clamp((col - 0.5f) * 1.1f + 0.5f, 0.0f, 1.0f);
            for (int c = 0; c < 3; c++) img[k + c] = (unsigned char)std::lround(col[c] * 255);
        }
    }
    for (int f = 0; f < 2; f++) {
        FILE *o = std::fopen(argv[3 + f], "wb");
        if (!o) return 2;
        std::fprintf(o, "P6\n%d %d\n255\n", W, H);
        std::fwrite((f ? msk : img).data(), 1, W * H * 3, o);
        std::fclose(o);
    }
    return 0;
}
