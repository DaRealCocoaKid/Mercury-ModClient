package dev.mercury.client.fx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.mercury.client.module.ModuleManager;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.resources.model.geometry.BakedQuad;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Aura del item que llevas en la mano (primera persona): se dibujan copias agrandadas y translucidas del modelo del item,
 * un poco metidas hacia atras, ANTES del item real. Asi el item tapa el centro y solo se ve el contorno que brilla.
 * Estilos: glow suave, linea, fuego y pulso.
 */
public final class ItemAura {
    public static final String[] TYPES = {"Glow suave", "Linea", "Fuego", "Pulso"};
    public static final ColorSet COLOR = new ColorSet(8);   // morado
    public static int type = 0, thick = 3, intensity = 6;

    private static Field fLayers, fCount, fQuads;
    private static Method mApply;
    private static boolean looked, broken;

    private ItemAura() {}

    public static boolean on() { return ModuleManager.ITEM_AURA != null && ModuleManager.ITEM_AURA.isOn(); }

    private static void look() throws Exception {
        looked = true;
        fLayers = ItemStackRenderState.class.getDeclaredField("layers");
        fCount = ItemStackRenderState.class.getDeclaredField("activeLayerCount");
        Class<?> lc = Class.forName("net.minecraft.client.renderer.item.ItemStackRenderState$LayerRenderState");
        fQuads = lc.getDeclaredField("quads");
        mApply = lc.getDeclaredMethod("applyTransform", PoseStack.Pose.class);
        fLayers.setAccessible(true); fCount.setAccessible(true); fQuads.setAccessible(true); mApply.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    public static void submit(ItemStackRenderState state, PoseStack pose, SubmitNodeCollector collector) {
        if (broken || !on() || state == null || state.isEmpty()) return;
        try {
            if (!looked) look();
            int n = fCount.getInt(state);
            Object layers = fLayers.get(state);
            float t = Fx.time();
            int shells = type == 1 ? 1 : 3;
            float grow = 0.022f * thick;                           // cuanto crece cada capa
            for (int s = shells - 1; s >= 0; s--) {                // de la mas externa a la mas interna
                float k = 1f + grow * (s + 1) * (type == 1 ? 0.8f : 1f);
                float alpha;
                int rgb = COLOR.rgb(t * 0.1f + s * 0.08f);
                switch (type) {
                    case 1 -> alpha = 0.95f;
                    case 2 -> {                                    // fuego: capas que parpadean y se calientan hacia afuera
                        k += 0.012f * (float) Math.sin(t * 17f + s * 2.3f) + 0.01f * (float) Math.sin(t * 29f + s * 4.1f);
                        alpha = 0.40f - s * 0.12f;
                        if (s == 2) rgb = mix(rgb, 0xFF3010, 0.65f);
                        else if (s == 1) rgb = mix(rgb, 0xFF8A00, 0.45f);
                    }
                    case 3 -> {                                    // pulso: respira
                        float beat = 0.5f + 0.5f * (float) Math.sin(t * 4.2f);
                        k = 1f + grow * (s + 1) * (0.6f + 0.9f * beat);
                        alpha = (0.34f - s * 0.1f) * (0.5f + 0.5f * beat);
                    }
                    default -> alpha = 0.32f - s * 0.10f;
                }
                alpha = Math.min(1f, alpha * (intensity / 6f));
                int argb = ((int) (alpha * 255f) << 24) | (rgb & 0xFFFFFF);
                final float kk = k, kz = 0.25f;
                for (int li = 0; li < n; li++) {
                    Object layer = Array.get(layers, li);
                    List<BakedQuad> quads = (List<BakedQuad>) fQuads.get(layer);
                    if (quads == null || quads.isEmpty()) continue;
                    pose.pushPose();
                    mApply.invoke(layer, pose.last());
                    pose.translate(0.5f, 0.5f, 0.5f);
                    pose.scale(kk, kk, kz);
                    pose.translate(-0.5f, -0.5f, -0.5f);
                    collector.submitCustomGeometry(pose, RenderTypes.debugQuads(), (p, vc) -> emit(p, vc, quads, argb));
                    pose.popPose();
                }
            }
        } catch (Throwable e) {
            broken = true;   // si algo cambia en una version del juego, se apaga solo y no rompe el render
        }
    }

    private static void emit(PoseStack.Pose p, VertexConsumer vc, List<BakedQuad> quads, int argb) {
        for (BakedQuad q : quads)
            for (int i = 0; i < 4; i++) {
                var v = q.position(i);
                vc.addVertex(p, v.x(), v.y(), v.z()).setColor(argb);
            }
    }

    private static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
