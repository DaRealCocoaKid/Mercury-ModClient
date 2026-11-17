package dev.mercury.client.fx;

/**
 * Genera el cubemap del cielo (6 caras en un atlas de 3x2) a partir de ruido 3D continuo:
 * lineas topograficas (curvas de nivel), aurora en cortinas y estrellas. No usa nada de Minecraft:
 * corre en un hilo aparte, asi que no cuesta FPS. Devuelve pixeles ABGR listos para subir a la textura.
 */
public final class SkyBake {
    /** Parametros de un bake (copia inmutable de los ajustes). */
    public static final class Params {
        public int face = 192;                 // lado de cada cara en pixeles
        public int bg = 0x000000, line = 0xFFFFFF, aur = 0x30FF60; // RGB
        public int style = 0;                  // 0 finas, 1 gruesas, 2 finas + relleno por nivel, 3 nebulosa
        public int detail = 4;                 // 1..10 (cantidad de curvas)
        public int thick = 2;                  // 1..4
        public boolean aurora, stars;
        public float time;                     // segundos (animacion)
    }

    private static final int[] PERM = new int[512];
    private static final float[] GX = new float[256], GY = new float[256], GZ = new float[256];
    static {
        java.util.Random r = new java.util.Random(20261115L);
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        for (int i = 255; i > 0; i--) { int j = r.nextInt(i + 1); int t = p[i]; p[i] = p[j]; p[j] = t; }
        for (int i = 0; i < 512; i++) PERM[i] = p[i & 255];
        for (int i = 0; i < 256; i++) {
            double z = r.nextDouble() * 2 - 1, a = r.nextDouble() * Math.PI * 2, s = Math.sqrt(1 - z * z);
            GX[i] = (float) (s * Math.cos(a)); GY[i] = (float) (s * Math.sin(a)); GZ[i] = (float) z;
        }
    }

    private SkyBake() {}

    private static float fade(float t) { return t * t * t * (t * (t * 6 - 15) + 10); }

    /** Ruido de gradiente 3D en [-1, 1] aprox. */
    static float noise(float x, float y, float z) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
        float fx = x - xi, fy = y - yi, fz = z - zi;
        xi &= 255; yi &= 255; zi &= 255;
        float u = fade(fx), v = fade(fy), w = fade(fz);
        float r000 = g(xi, yi, zi, fx, fy, fz), r100 = g(xi + 1, yi, zi, fx - 1, fy, fz);
        float r010 = g(xi, yi + 1, zi, fx, fy - 1, fz), r110 = g(xi + 1, yi + 1, zi, fx - 1, fy - 1, fz);
        float r001 = g(xi, yi, zi + 1, fx, fy, fz - 1), r101 = g(xi + 1, yi, zi + 1, fx - 1, fy, fz - 1);
        float r011 = g(xi, yi + 1, zi + 1, fx, fy - 1, fz - 1), r111 = g(xi + 1, yi + 1, zi + 1, fx - 1, fy - 1, fz - 1);
        float a = r000 + (r100 - r000) * u, b = r010 + (r110 - r010) * u, c = r001 + (r101 - r001) * u, d = r011 + (r111 - r011) * u;
        float e = a + (b - a) * v, f = c + (d - c) * v;
        return e + (f - e) * w;
    }

    private static float g(int x, int y, int z, float dx, float dy, float dz) {
        int h = PERM[PERM[PERM[x & 255] + (y & 255)] + (z & 255)];
        return GX[h] * dx + GY[h] * dy + GZ[h] * dz;
    }

    private static float fbm(float x, float y, float z, int oct) {
        float s = 0, a = 1, n = 0;
        for (int i = 0; i < oct; i++) { s += a * noise(x, y, z); n += a; x *= 2.03f; y *= 2.03f; z *= 2.03f; a *= 0.5f; }
        return s / n;
    }

    private static float hash3(int x, int y, int z) {
        int h = x * 374761393 + y * 668265263 + z * 1274126177;
        h = (h ^ (h >>> 13)) * 1103515245;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }

    private static float smooth(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    /** Direccion (en espacio del cielo) del pixel (i,j) de la cara f: mismas rotaciones que usa el juego para armar el cubo. */
    private static void dir(int f, float u, float v, float[] o) {
        float x = -1 + 2 * u, y = -1, z = -1 + 2 * v, nx = x, ny = y, nz = z;
        switch (f) {
            case 1 -> { ny = -z; nz = y; }            // rotationX(+90)
            case 2 -> { ny = z; nz = -y; }            // rotationX(-90)
            case 3 -> { ny = -y; nz = -z; }           // rotationX(180)
            case 4 -> { nx = -y; ny = x; }            // rotationZ(+90)
            case 5 -> { nx = y; ny = -x; }            // rotationZ(-90)
            default -> { }
        }
        float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        o[0] = nx / l; o[1] = ny / l; o[2] = nz / l;
    }

    /** Devuelve el atlas (3F x 2F) en ABGR. */
    public static int[] bake(Params p) { return bake(p, new int[p.face * 3 * p.face * 2]); }

    /** Igual que bake(p) pero escribe en un arreglo ya creado (evita basura en cada animacion). */
    public static int[] bake(Params p, int[] out) {
        final int F = p.face, W = F * 3;
        float[] fld = new float[F * F];
        float[] d = new float[3];
        float t = p.time;
        float scale = 0.45f + p.detail * 0.2f;
        float levels = 7f + p.detail * 2.6f;                     // curvas de nivel por unidad de ruido
        float halfW = (0.35f + p.thick * 0.22f) * (F / 192f);      // semi-grosor de linea en pixeles
        if (p.style == 1) halfW *= 1.7f;
        float ox = t * 0.045f, oy = t * 0.031f, oz = t * 0.037f;

        int bgR = (p.bg >> 16) & 255, bgG = (p.bg >> 8) & 255, bgB = p.bg & 255;
        int lnR = (p.line >> 16) & 255, lnG = (p.line >> 8) & 255, lnB = p.line & 255;
        int auR = (p.aur >> 16) & 255, auG = (p.aur >> 8) & 255, auB = p.aur & 255;

        for (int f = 0; f < 6; f++) {
            int cx = (f % 3) * F, cy = (f / 3) * F;
            for (int j = 0; j < F && p.style != 3; j++)
                for (int i = 0; i < F; i++) {
                    dir(f, (i + 0.5f) / F, (j + 0.5f) / F, d);
                    float n = fbm(d[0] * scale + 11.3f + ox, d[1] * scale + 4.7f + oy, d[2] * scale + 7.9f + oz, 3);
                    fld[j * F + i] = n * levels;
                }
            for (int j = 0; j < F; j++)
                for (int i = 0; i < F; i++) {
                    int idx = j * F + i;
                    if (p.style == 3) { neb(p, f, i, j, F, W, out, cx, cy, t, d); continue; }
                    float ph = fld[idx];
                    // gradiente local (pixeles) para que la linea tenga grosor constante
                    float gx = fld[j * F + Math.min(F - 1, i + 1)] - fld[j * F + Math.max(0, i - 1)];
                    float gy = fld[Math.min(F - 1, j + 1) * F + i] - fld[Math.max(0, j - 1) * F + i];
                    float grad = Math.max(0.002f, (float) Math.sqrt(gx * gx + gy * gy) * 0.5f);
                    float fr = ph - (float) Math.floor(ph + 0.5f);        // -0.5..0.5 distancia al nivel mas cercano
                    float dist = Math.abs(fr) / grad;                      // en pixeles
                    int level = (int) Math.floor(ph + 0.5f);
                    boolean idxLine = (level % 5 == 0);
                    float w = idxLine ? halfW * 1.9f : halfW;
                    float a = Math.max(0f, Math.min(1f, w + 0.5f - dist));
                    if (!idxLine) a *= 0.8f;
                    float r = bgR, gg = bgG, b = bgB;
                    if (p.style == 2) {                                    // relleno suave segun el nivel
                        float k = 0.10f + 0.06f * (((level % 4) + 4) % 4);
                        r += (lnR - r) * k; gg += (lnG - gg) * k; b += (lnB - b) * k;
                    }
                    r += (lnR - r) * a; gg += (lnG - gg) * a; b += (lnB - b) * a;

                    if (p.aurora || p.stars) {
                        dir(f, (i + 0.5f) / F, (j + 0.5f) / F, d);
                        float el = (float) Math.asin(Math.max(-1f, Math.min(1f, d[1])));
                        if (p.aurora && el > 0.02f && el < 1.0f) {
                            float az = (float) Math.atan2(d[2], d[0]);
                            float ca = (float) Math.cos(az), sa = (float) Math.sin(az);
                            // cortinas: ondas en azimut que se mueven + rayos verticales
                            float wave = fbm(ca * 1.6f + t * 0.05f, sa * 1.6f + 3.1f, t * 0.07f, 3) * 0.5f + 0.5f;
                            float base = 0.22f + 0.26f * wave;                         // altura de la cortina
                            float below = smooth(base - 0.20f, base, el);              // borde inferior marcado
                            float above = 1f - smooth(base, base + 0.42f, el);         // se desvanece hacia arriba
                            float rays = fbm(ca * 9f + 2.3f, sa * 9f - 1.7f, el * 2.2f + t * 0.10f, 2) * 0.5f + 0.5f;
                            float inten = below * above * (0.35f + 0.9f * rays * rays) * 0.9f;
                            r += (auR * 1.0f) * inten; gg += (auG * 1.0f) * inten; b += (auB * 1.0f) * inten;
                        }
                        if (p.stars) {
                            float sx = d[0] * 70f, sy = d[1] * 70f, sz = d[2] * 70f;
                            int ix = (int) Math.floor(sx), iy = (int) Math.floor(sy), iz = (int) Math.floor(sz);
                            float h = hash3(ix, iy, iz);
                            if (h > 0.9955f) {
                                float ddx = sx - ix - 0.5f, ddy = sy - iy - 0.5f, ddz = sz - iz - 0.5f;
                                float dd = ddx * ddx + ddy * ddy + ddz * ddz;
                                float tw = 0.65f + 0.35f * (float) Math.sin(t * (1.3f + h * 40f) + h * 900f);
                                float s = Math.max(0f, 1f - dd * 7f) * tw;
                                r += 255 * s; gg += 255 * s; b += 255 * s;
                            }
                        }
                    }
                    int R = (int) Math.min(255f, r), G = (int) Math.min(255f, gg), B = (int) Math.min(255f, b);
                    out[(cy + j) * W + cx + i] = 0xFF000000 | (B << 16) | (G << 8) | R; // ABGR
                }
        }
        return out;
    }

    /** Nebulosa: nubes de gas con ruido deformado (domain warp) entre fondo, color de linea y color de aurora. */
    private static void neb(Params p, int f, int i, int j, int F, int W, int[] out, int cx, int cy, float t, float[] d) {
        dir(f, (i + 0.5f) / F, (j + 0.5f) / F, d);
        float sc = 0.9f + p.detail * 0.18f;
        float x = d[0] * sc, y = d[1] * sc, z = d[2] * sc, tt = t * 0.03f;
        float wx = fbm(x + 3.1f + tt, y + 1.7f, z + 5.3f, 3), wy = fbm(x + 8.2f, y + 2.4f - tt, z + 9.1f, 3), wz = fbm(x + 6.6f, y + 7.7f, z + 1.2f + tt, 3);
        float a = fbm(x + wx * 1.6f, y + wy * 1.6f, z + wz * 1.6f, 4) * 0.5f + 0.5f;
        float b = 1f - Math.abs(fbm(x * 1.9f + wy * 2.2f, y * 1.9f + wz * 2.2f, z * 1.9f + wx * 2.2f + tt, 4));
        float k = smooth(0.30f, 0.85f, a);
        float w = b * b * b * k;
        int bgR = (p.bg >> 16) & 255, bgG = (p.bg >> 8) & 255, bgB = p.bg & 255;
        int lnR = (p.line >> 16) & 255, lnG = (p.line >> 8) & 255, lnB = p.line & 255;
        int auR = (p.aur >> 16) & 255, auG = (p.aur >> 8) & 255, auB = p.aur & 255;
        float r = bgR + (lnR - bgR) * k * 0.75f + auR * w * 0.9f;
        float g = bgG + (lnG - bgG) * k * 0.75f + auG * w * 0.9f;
        float bl = bgB + (lnB - bgB) * k * 0.75f + auB * w * 0.9f;
        if (p.stars) {
            float sx = d[0] * 70f, sy = d[1] * 70f, sz = d[2] * 70f;
            int ix = (int) Math.floor(sx), iy = (int) Math.floor(sy), iz = (int) Math.floor(sz);
            float h = hash3(ix, iy, iz);
            if (h > 0.9955f) {
                float ddx = sx - ix - 0.5f, ddy = sy - iy - 0.5f, ddz = sz - iz - 0.5f;
                float s = Math.max(0f, 1f - (ddx * ddx + ddy * ddy + ddz * ddz) * 7f) * (0.65f + 0.35f * (float) Math.sin(t * (1.3f + h * 40f) + h * 900f));
                r += 255 * s; g += 255 * s; bl += 255 * s;
            }
        }
        int R = (int) Math.min(255f, r), G = (int) Math.min(255f, g), B = (int) Math.min(255f, bl);
        out[(cy + j) * W + cx + i] = 0xFF000000 | (B << 16) | (G << 8) | R;
    }
}
