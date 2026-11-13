package dev.mercury.client.module;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class HitboxOverlay {
    private static final Minecraft mc = Minecraft.getInstance();

    public static void register() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS,
                Identifier.fromNamespaceAndPath("mercuryclient", "crystal_hitbox"),
                (g, dt) -> draw(g, dt.getGameTimeDeltaPartialTick(false)));
    }

    private static void draw(GuiGraphicsExtractor g, float pt) {
        var p = mc.player;
        if (!ModuleManager.CRYSTAL_HITBOX.isOn() || p == null || mc.level == null) return;

        Vec3 eye = p.getEyePosition(pt);
        double yaw = Math.toRadians(p.getViewYRot(pt)), pit = Math.toRadians(p.getViewXRot(pt));
        double sy = Math.sin(yaw), cy = Math.cos(yaw), sp = Math.sin(pit), cp = Math.cos(pit);
        double[] fwd = {-sy * cp, -sp, cy * cp};
        double[] right = {-cy, 0, -sy};
        double[] up = {-sy * sp, cp, cy * sp};

        int W = g.guiWidth(), H = g.guiHeight();
        double scale = (H / 2.0) / Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);
        int color = dev.mercury.client.Config.HB_COLORS[Math.floorMod(Pvp.hitboxColor, dev.mercury.client.Config.HB_COLORS.length)];

        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof EndCrystal) || e.distanceToSqr(p) > 48 * 48) continue;
            AABB bb = e.getBoundingBox();
            double[][] pts = new double[8][];
            for (int i = 0; i < 8; i++) {
                double x = (i & 1) == 0 ? bb.minX : bb.maxX;
                double y = (i & 2) == 0 ? bb.minY : bb.maxY;
                double z = (i & 4) == 0 ? bb.minZ : bb.maxZ;
                double dx = x - eye.x, dy = y - eye.y, dz = z - eye.z;
                double zc = dx * fwd[0] + dy * fwd[1] + dz * fwd[2];
                if (zc < 0.1) continue;
                double xc = dx * right[0] + dy * right[1] + dz * right[2];
                double yc = dx * up[0] + dy * up[1] + dz * up[2];
                pts[i] = new double[]{W / 2.0 + xc / zc * scale, H / 2.0 - yc / zc * scale};
            }
            for (int i = 0; i < 8; i++)
                for (int b = 0; b < 3; b++) {
                    int j = i ^ (1 << b);
                    if (j > i && pts[i] != null && pts[j] != null) line(g, pts[i], pts[j], W, H, color);
                }
        }
    }

    private static void line(GuiGraphicsExtractor g, double[] a, double[] b, int W, int H, int color) {
        if (Math.abs(a[0]) > W * 4 || Math.abs(a[1]) > H * 4 || Math.abs(b[0]) > W * 4 || Math.abs(b[1]) > H * 4) return;
        int steps = (int) Math.min(800, Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1])));
        for (int i = 0; i <= steps; i++) {
            double t = steps == 0 ? 0 : (double) i / steps;
            int x = (int) (a[0] + (b[0] - a[0]) * t), y = (int) (a[1] + (b[1] - a[1]) * t);
            if (x < 0 || y < 0 || x >= W || y >= H) continue;
            g.fill(x, y, x + 1, y + 1, color);
        }
    }
}
