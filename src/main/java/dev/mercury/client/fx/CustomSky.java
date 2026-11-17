package dev.mercury.client.fx;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import dev.mercury.client.module.ModuleManager;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Skybox REAL: un cubo alrededor de la camara dibujado en la fase del cielo del propio juego (detras de bloques, jugadores,
 * HUD, todo). El patron (lineas topograficas / nebulosa, aurora, estrellas) lo genera {@link SkyBake} en un hilo aparte y
 * solo se sube como textura: en el hilo del juego casi no cuesta nada. La animacion es el ruido avanzando + una rotacion lenta.
 */
public final class CustomSky {
    public static final String[] STYLES = {"Topografico fino", "Topografico grueso", "Topografico + relleno", "Nebulosa"};
    public static final String[] QUALITY = {"Baja", "Media", "Alta"};
    private static final int[] FACE = {256, 384, 512};

    public static final ColorSet BG = new ColorSet(ColorSet.NAMES.length - 1);   // personalizado: negro
    public static final ColorSet LINE = new ColorSet(11);                        // blanco
    public static final ColorSet AUR = new ColorSet(4);                          // verde
    static { BG.r = 0; BG.g = 0; BG.b = 0; }

    public static int style = 0, detail = 4, thick = 2, quality = 1, rotate = 2, speed = 3;
    public static boolean animate = true, aurora = false, stars = false, celestials = false;

    // ---- hilo de generacion ----
    private static volatile int[] ready;
    private static volatile int readyFace;
    private static final int[][] BUF = new int[2][];
    private static int bufIdx, bufFace;
    private static Thread thread;
    private static long intervalNs = 150_000_000L;

    // ---- recursos de GPU (solo hilo de render) ----
    private static SkyTexture tex;
    private static int texFace;
    private static GpuBuffer vbuf;
    private static int[] lastUploaded;
    private static boolean uploaded;

    private CustomSky() {}

    public static boolean on() { return ModuleManager.SKYBOX != null && ModuleManager.SKYBOX.isOn(); }
    public static boolean hideCelestials() { return on() && !celestials; }

    /** Textura dinamica con filtro lineal y bordes fijos (la de vanilla es NEAREST + REPEAT). */
    private static final class SkyTexture extends DynamicTexture {
        SkyTexture(int w, int h) {
            super(() -> "mercury_sky", w, h, false);
            this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        }
    }

    // ================= hilo de generacion =================
    private static SkyBake.Params snapshot() {
        SkyBake.Params p = new SkyBake.Params();
        p.face = FACE[Math.floorMod(quality, FACE.length)];
        float phase = Fx.time() * 0.04f;
        p.bg = BG.rgb(phase); p.line = LINE.rgb(phase); p.aur = AUR.rgb(phase + 0.3f);
        p.style = style; p.detail = detail; p.thick = thick;
        p.aurora = aurora; p.stars = stars;
        p.time = animate ? Fx.time() * (0.35f + speed * 0.3f) : 0f;
        return p;
    }

    private static int key(SkyBake.Params p) {
        int h = p.face;
        h = h * 31 + p.bg; h = h * 31 + p.line; h = h * 31 + p.aur;
        h = h * 31 + p.style; h = h * 31 + p.detail; h = h * 31 + p.thick;
        h = h * 31 + (p.aurora ? 1 : 0) + (p.stars ? 2 : 0);
        return h;
    }

    private static void loop() {
        int lastKey = 0;
        long last = 0;
        boolean first = true;
        while (true) {
            try {
                if (!on()) { Thread.sleep(300); first = true; continue; }
                SkyBake.Params p = snapshot();
                int k = key(p);
                boolean animated = animate || p.aurora || p.stars || LINE.mode >= 13 || BG.mode >= 13 || AUR.mode >= 13;
                if (!first && k == lastKey && !(animated && System.nanoTime() - last > intervalNs)) { Thread.sleep(25); continue; }
                int n = p.face * 3 * p.face * 2;
                if (bufFace != p.face || BUF[0] == null) { BUF[0] = new int[n]; BUF[1] = new int[n]; bufFace = p.face; }
                bufIdx ^= 1;
                long t0 = System.nanoTime();
                int[] out = SkyBake.bake(p, BUF[bufIdx]);
                long dt = System.nanoTime() - t0;
                readyFace = p.face;
                ready = out;
                lastKey = k; first = false;
                last = System.nanoTime();
                intervalNs = Math.max(120_000_000L, dt * 2);   // nunca ocupa mas de ~50% de un nucleo
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            }
        }
    }

    // ================= dibujo (hilo de render) =================
    /** Dibuja el cielo propio. Devuelve false si todavia no hay textura lista (se deja el cielo normal ese momento). */
    public static boolean draw(RenderTarget target) {
        if (thread == null) {
            thread = new Thread(CustomSky::loop, "mercury-sky");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.start();
        }
        int face = FACE[Math.floorMod(quality, FACE.length)];
        if (tex == null || texFace != face) {
            if (tex != null) tex.close();
            if (vbuf != null) { vbuf.close(); vbuf = null; }
            tex = new SkyTexture(face * 3, face * 2);
            texFace = face;
            uploaded = false;
            lastUploaded = null;
        }
        int[] r = ready;
        if (r != null && readyFace == texFace && r != lastUploaded) {
            ByteBuffer bb = tex.getPixels().getPixelBytes().order(ByteOrder.nativeOrder());
            bb.asIntBuffer().put(r, 0, face * 3 * face * 2);
            tex.upload();
            lastUploaded = r;
            uploaded = true;
        }
        if (!uploaded) return false;
        if (vbuf == null) vbuf = buildCube(face);

        RenderSystem.AutoStorageIndexBuffer seq = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer idx = seq.getBuffer(36);
        GpuTextureView color = target.getColorTextureView(), depth = target.getDepthTextureView();
        Matrix4f mv = RenderSystem.getModelViewMatrixCopy();
        if (rotate > 0) mv.rotateY((Fx.time() * rotate * 0.012f) % 6.2831855f);
        GpuBufferSlice dyn = RenderSystem.getDynamicUniforms().writeTransform(mv);
        CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass pass = enc.createRenderPass(() -> "Mercury skybox", color, Optional.empty(), depth, OptionalDouble.empty())) {
            pass.setPipeline(RenderPipelines.END_SKY);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", dyn);
            pass.bindTexture("Sampler0", tex.getTextureView(), tex.getSampler());
            pass.setVertexBuffer(0, vbuf.slice());
            pass.setIndexBuffer(idx, seq.type());
            pass.drawIndexed(36, 1, 0, 0, 0);
        }
        return true;
    }

    /** Cubo de 6 caras con las mismas rotaciones que el cielo del End; cada cara usa su zona del atlas 3x2. */
    private static GpuBuffer buildCube(int face) {
        float ix = 0.5f / (face * 3), iy = 0.5f / (face * 2);   // medio pixel hacia adentro para que no se mezclen las caras
        try (ByteBufferBuilder bb = ByteBufferBuilder.exactlySized(24 * DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize())) {
            BufferBuilder b = new BufferBuilder(bb, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            for (int f = 0; f < 6; f++) {
                Matrix4f m = new Matrix4f();
                switch (f) {
                    case 1 -> m.rotationX(1.5707964f);
                    case 2 -> m.rotationX(-1.5707964f);
                    case 3 -> m.rotationX(3.1415927f);
                    case 4 -> m.rotationZ(1.5707964f);
                    case 5 -> m.rotationZ(-1.5707964f);
                    default -> { }
                }
                float u0 = (f % 3) / 3f + ix, u1 = (f % 3 + 1) / 3f - ix;
                float v0 = (f / 3) / 2f + iy, v1 = (f / 3 + 1) / 2f - iy;
                b.addVertex(m, -100f, -100f, -100f).setUv(u0, v0).setColor(0xFFFFFFFF);
                b.addVertex(m, -100f, -100f, 100f).setUv(u0, v1).setColor(0xFFFFFFFF);
                b.addVertex(m, 100f, -100f, 100f).setUv(u1, v1).setColor(0xFFFFFFFF);
                b.addVertex(m, 100f, -100f, -100f).setUv(u1, v0).setColor(0xFFFFFFFF);
            }
            try (MeshData mesh = b.buildOrThrow()) {
                return RenderSystem.getDevice().createBuffer(() -> "Mercury skybox", 40, mesh.vertexBuffer());
            }
        }
    }
}
