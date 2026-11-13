package dev.mercury.client.fx;

import dev.mercury.client.module.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.Random;

/**
 * Skybox: cielo galactico con estrellas, auroras en movimiento, estrellas fugaces o arcoiris RGB.
 * Se pinta como overlay SOLO donde hay cielo (rayos contra los bloques, con cache) y deja huecos
 * donde hay entidades para que no se te escondan jugadores volando.
 */
public final class Skybox {
    public static final String[] STYLES = {"Video (nebulosa)", "Galaxia", "Arcoiris RGB", "Aurora boreal", "Nebulosa", "Neon sunset"};
    public static final String[] QUALITY = {"Alta", "Media", "Baja", "Minima"};
    private static final int[] CELL = {4, 6, 8, 12};

    public static final ColorSet SKY = new ColorSet(9);   // magenta como el video
    public static final ColorSet AUR = new ColorSet(4);   // aurora verde
    public static int style = 0, speed = 4, opacity = 100, quality = 2, density = 600;
    public static boolean auroras = true, stars = true, meteors = true;

    // cache de visibilidad del cielo por direccion (1 grado)
    private static final int AZN = 360, ELN = 90;
    private static final byte[] VAL = new byte[AZN * ELN];   // 0 desconocido, 1 cielo, 2 bloqueado
    private static final int[] STAMP = new int[AZN * ELN];
    private static int gen = 1, sinceGen, budget;
    private static double ox, oy, oz;
    private static boolean hasO;
    private static Object lastLevel;
    private static final BlockPos.MutableBlockPos MP = new BlockPos.MutableBlockPos();

    // estrellas
    private static final int MAX_STARS = 1500;
    private static final double[] SA = new double[MAX_STARS], SE = new double[MAX_STARS];
    private static final float[] SS = new float[MAX_STARS];
    // meteoro
    private static double mAz, mEl, mDAz, mDEl;
    private static long mBorn = -10000;

    // huecos para entidades
    private static final double[][] RECTS = new double[48][4];
    private static int nRects;

    // normal del plano de la via lactea
    private static final double NX = 0.55, NY = 0.30, NZ = 0.78;
    private static final double NL = Math.sqrt(NX * NX + NY * NY + NZ * NZ);

    static {
        Random r = new Random(1337);
        for (int i = 0; i < MAX_STARS; i++) {
            SA[i] = r.nextDouble() * Math.PI * 2;
            SE[i] = Math.asin(r.nextDouble() * 0.98 + 0.02);
            SS[i] = r.nextFloat();
        }
    }

    private Skybox() {}

    public static boolean on() { return ModuleManager.SKYBOX != null && ModuleManager.SKYBOX.isOn(); }

    public static void tick(Minecraft mc) {
        if (!on() || !meteors) return;
        long now = Fx.now();
        if (now - mBorn > 900 && Fx.rnd() < 0.012f) {
            mBorn = now;
            mAz = Fx.rnd() * Math.PI * 2;
            mEl = 0.5 + Fx.rnd() * 0.8;
            double a = Fx.rnd() * Math.PI * 2;
            mDAz = Math.cos(a) * 0.9;
            mDEl = -Math.abs(Math.sin(a)) * 0.5 - 0.1;
        }
    }

    // ---------------- visibilidad del cielo ----------------
    private static boolean computeSky(Minecraft mc, double az, double el) {
        double ce = Math.cos(el);
        double dx = -Math.sin(az) * ce, dy = Math.sin(el), dz = Math.cos(az) * ce;
        double d = 0.6;
        while (d < 192) {
            double y = oy + dy * d;
            if (y > 330) return true;
            MP.set((int) Math.floor(ox + dx * d), (int) Math.floor(y), (int) Math.floor(oz + dz * d));
            BlockState st = mc.level.getBlockState(MP);
            if (!st.isAir() && (!st.getFluidState().isEmpty() || !st.getCollisionShape(mc.level, MP).isEmpty())) return false;
            d += Math.max(0.9, d * 0.08);
        }
        return true;
    }

    private static boolean visible(Minecraft mc, double az, double el) {
        int ai = Math.floorMod((int) Math.floor(Math.toDegrees(az)), AZN);
        int ei = Math.min(ELN - 1, (int) Math.floor(Math.toDegrees(el)));
        int idx = ei * AZN + ai;
        if (STAMP[idx] != gen && budget > 0) {
            budget--;
            VAL[idx] = computeSky(mc, Math.toRadians(ai + 0.5), Math.toRadians(ei + 0.5)) ? (byte) 1 : (byte) 2;
            STAMP[idx] = gen;
        }
        return VAL[idx] == 1;
    }

    private static boolean inRect(double x, double y) {
        for (int i = 0; i < nRects; i++) {
            double[] r = RECTS[i];
            if (x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3]) return true;
        }
        return false;
    }

    private static void collectRects(Minecraft mc) {
        nRects = 0;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player || nRects >= RECTS.length) continue;
            double dx = e.getX() - Cam.ex, dy = e.getY() - Cam.ey, dz = e.getZ() - Cam.ez;
            if (dx * dx + dy * dy + dz * dz > 170 * 170) continue;
            AABB bb = e.getBoundingBox();
            double minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9;
            boolean okAll = true;
            for (int i = 0; i < 8 && okAll; i++) {
                if (!Cam.project((i & 1) == 0 ? bb.minX : bb.maxX, (i & 2) == 0 ? bb.minY : bb.maxY, (i & 4) == 0 ? bb.minZ : bb.maxZ)) { okAll = false; break; }
                minX = Math.min(minX, Cam.sx); maxX = Math.max(maxX, Cam.sx);
                minY = Math.min(minY, Cam.sy); maxY = Math.max(maxY, Cam.sy);
            }
            if (!okAll) continue;
            double[] r = RECTS[nRects++];
            r[0] = minX - 4; r[1] = minY - 4; r[2] = maxX + 4; r[3] = maxY + 4;
        }
    }

    // ---------------- colores del cielo ----------------
    private static float sm(float x) { x = Math.max(0f, Math.min(1f, x)); return x * x * (3 - 2 * x); }

    private static int base(double az, double el, float t, double dx, double dy, double dz) {
        float e = (float) (el / (Math.PI / 2));
        float sp = speed * 0.25f;
        int c;
        switch (style) {
            case 0 -> {
                // cielo del video: degradado saturado + nubes oscuras suaves que se mueven
                int sky = SKY.rgb((float) (az / (Math.PI * 2)) * 0.5f + t * 0.03f);
                int horizon = Col.lerp(sky, 0xFFFFFF, 0.30f), top = Col.scale(sky, 0.50f);
                c = e < 0.45f ? Col.lerp(horizon, sky, e / 0.45f) : Col.lerp(sky, top, (e - 0.45f) / 0.55f);
                float n = 0.5f + 0.25f * (float) Math.sin(az * 3 + el * 4 + t * 0.05 * sp)
                        + 0.15f * (float) Math.sin(az * 7 - el * 5 + t * 0.08 * sp + 1.3)
                        + 0.10f * (float) Math.sin(az * 13 + el * 9 - t * 0.12 * sp);
                float dark = sm((n - 0.35f) / 0.45f);
                c = Col.scale(c, 0.50f + 0.50f * dark);
                float wisp = (float) Math.max(0, Math.sin(az * 5 + t * 0.04 * sp) * Math.sin(el * 8 + 1)) * (1f - e);
                c = Col.add(c, Col.scale(Col.lerp(sky, 0xFFFFFF, 0.5f), wisp * 0.18f));
            }
            case 2 -> c = Col.hsv((float) (az / (Math.PI * 2)) + e * 0.35f + t * 0.06f * sp, 0.85f, 0.55f + 0.4f * (1f - e));
            case 3 -> c = Col.lerp(0x08301F, 0x02040E, (float) Math.pow(e, 0.6));
            case 4 -> c = Col.lerp(0x3A0F5E, 0x04041A, (float) Math.pow(e, 0.55));
            case 5 -> {
                if (e < 0.35f) c = Col.lerp(0xFF6A3A, 0xC2308A, e / 0.35f);
                else c = Col.lerp(0xC2308A, 0x140A4A, (e - 0.35f) / 0.65f);
            }
            default -> c = Col.lerp(0x2A1650, 0x04051A, (float) Math.pow(e, 0.5));
        }
        if (style == 1 || style == 4) {
            double d = (dx * NX + dy * NY + dz * NZ) / NL;
            float band = (float) Math.exp(-(d * d) / 0.02);
            float n1 = 0.5f + 0.5f * (float) (Math.sin(az * 5 + el * 3 + t * 0.05) * Math.sin(az * 3 - el * 6));
            c = Col.add(c, Col.scale(Col.lerp(0x9E7BFF, 0xFFD0F0, n1), band * (0.3f + 0.6f * n1)));
            float neb = (float) Math.max(0, Math.sin(az * 2 + 1 + t * 0.02) * Math.sin(el * 5 + 2 + az));
            c = Col.add(c, Col.scale(style == 4 ? 0x40E0FF : 0xFF4FB8, neb * 0.22f));
        }
        return c;
    }

    private static int auroraAdd(double az, double el, float t) {
        float sp = speed * 0.25f;
        float rr = 0, gg = 0, bb = 0;
        for (int k = 0; k < 3; k++) {
            double h = 0.40 + 0.22 * Math.sin(az * (2 + k) + t * 0.35 * sp + k * 2.1) + 0.08 * Math.sin(az * 7 - t * 0.8 * sp + k);
            double u = el - h;
            if (u < -0.06 || u > 0.6) continue;
            double prof = u < 0 ? Math.exp(-(u * u) / 0.0008) : Math.exp(-u / 0.17);
            double rays = style == 0
                    ? Math.pow(0.5 + 0.5 * Math.sin(az * 48 + el * 34 + k * 3 + t * 1.2 * sp), 1.6)
                    : 0.65 + 0.35 * Math.sin(az * 60 + k * 3 + t * 1.5 * sp + Math.sin(az * 5 + t * 0.4) * 3);
            double win = 0.5 + 0.5 * Math.sin(az + k * 2 + t * 0.12 * sp);
            win = win * win;
            float I = (float) (prof * rays * win * (0.95 - 0.15 * k));
            if (I < 0.01f) continue;
            float uu = (float) Math.max(0, u);
            int c = Col.lerp(AUR.rgb((float) (az / (Math.PI * 2)) + t * 0.05f * sp + uu), 0xFFFFFF, Math.min(0.5f, uu));
            rr += ((c >> 16) & 255) * I; gg += ((c >> 8) & 255) * I; bb += (c & 255) * I;
        }
        return (Math.min(255, (int) rr) << 16) | (Math.min(255, (int) gg) << 8) | Math.min(255, (int) bb);
    }

    // ---------------- dibujo ----------------
    public static void draw(Minecraft mc, GuiGraphicsExtractor g, float pt) {
        if (mc.level == null) return;
        if (lastLevel != mc.level) { lastLevel = mc.level; Arrays.fill(VAL, (byte) 0); Arrays.fill(STAMP, 0); hasO = false; }
        double ddx = Cam.ex - ox, ddy = Cam.ey - oy, ddz = Cam.ez - oz;
        if (!hasO || ddx * ddx + ddy * ddy + ddz * ddz > 9 || ++sinceGen > 240) {
            gen++; sinceGen = 0; ox = Cam.ex; oy = Cam.ey; oz = Cam.ez; hasO = true;
        }
        budget = 900;
        collectRects(mc);

        float t = Fx.time();
        float alpha = Math.max(0.1f, Math.min(1f, opacity / 100f));
        int cs = CELL[Math.floorMod(quality, CELL.length)];
        int W = Cam.W, H = Cam.H;
        double inv = 1.0 / Cam.scale;

        for (int y0 = 0; y0 < H; y0 += cs) {
            double py = y0 + cs / 2.0;
            double yc = (H / 2.0 - py) * inv;
            for (int x0 = 0; x0 < W; x0 += cs) {
                double px = x0 + cs / 2.0;
                double xc = (px - W / 2.0) * inv;
                double dx = Cam.fwd[0] + Cam.right[0] * xc + Cam.up[0] * yc;
                double dy = Cam.fwd[1] + Cam.right[1] * xc + Cam.up[1] * yc;
                double dz = Cam.fwd[2] + Cam.right[2] * xc + Cam.up[2] * yc;
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                dx /= len; dy /= len; dz /= len;
                if (dy < 0.003) continue;
                double el = Math.asin(dy), az = Math.atan2(-dx, dz);
                if (az < 0) az += Math.PI * 2;
                if (!visible(mc, az, el)) continue;
                if (inRect(px, py)) continue;
                int c = base(az, el, t, dx, dy, dz);
                if (auroras) c = Col.add(c, auroraAdd(az, el, t));
                g.fill(x0, y0, Math.min(W, x0 + cs), Math.min(H, y0 + cs), Col.argb(c, alpha));
            }
        }

        if (stars) {
            int n = Math.min(MAX_STARS, density);
            for (int i = 0; i < n; i++) {
                double az = SA[i], el = SE[i];
                double ce = Math.cos(el);
                double dx = -Math.sin(az) * ce, dy = Math.sin(el), dz = Math.cos(az) * ce;
                if (!Cam.project(Cam.ex + dx * 100, Cam.ey + dy * 100, Cam.ez + dz * 100)) continue;
                int sx = (int) Cam.sx, sy = (int) Cam.sy;
                if (sx < 0 || sy < 0 || sx >= W || sy >= H || inRect(sx, sy) || !visible(mc, az, el)) continue;
                float tw = 0.55f + 0.45f * (float) Math.sin(t * (1.5 + SS[i] * 3) + SS[i] * 40);
                int col = Col.lerp(0xFFFFFF, SS[i] < 0.3f ? 0xA8C8FF : SS[i] > 0.8f ? 0xFFD8A8 : 0xFFFFFF, 0.6f);
                g.fill(sx, sy, sx + 1, sy + 1, Col.argb(col, tw));
                if (SS[i] > 0.9f) {
                    g.fill(sx - 1, sy, sx + 2, sy + 1, Col.argb(col, tw * 0.4f));
                    g.fill(sx, sy - 1, sx + 1, sy + 2, Col.argb(col, tw * 0.4f));
                }
            }
        }

        if (meteors) {
            float age = (Fx.now() - mBorn) / 800f;
            if (age >= 0 && age < 1f) {
                for (int k = 0; k < 8; k++) {
                    float a = age - k * 0.025f;
                    if (a < 0) break;
                    double az = mAz + mDAz * a, el = mEl + mDEl * a;
                    if (el <= 0.02) break;
                    double ce = Math.cos(el);
                    if (!Cam.project(Cam.ex - Math.sin(az) * ce * 100, Cam.ey + Math.sin(el) * 100, Cam.ez + Math.cos(az) * ce * 100)) continue;
                    int sx = (int) Cam.sx, sy = (int) Cam.sy;
                    if (sx < 0 || sy < 0 || sx >= W || sy >= H || !visible(mc, az, el)) continue;
                    float f = (1f - age) * (1f - k / 8f);
                    g.fill(sx - 1, sy - 1, sx + 2, sy + 2, Col.argb(0xAAD8FF, f * 0.35f));
                    g.fill(sx, sy, sx + 1, sy + 1, Col.argb(0xFFFFFF, f));
                }
            }
        }
    }
}
