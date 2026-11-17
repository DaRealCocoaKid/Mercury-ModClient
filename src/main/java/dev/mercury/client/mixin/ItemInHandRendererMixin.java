package dev.mercury.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.mercury.client.fx.ItemAura;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Dibuja el aura justo antes de que el juego dibuje el item que llevas en la mano. */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @Redirect(method = "renderItem", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"),
            require = 0)
    private void mercury$aura(ItemStackRenderState state, PoseStack pose, SubmitNodeCollector collector, int light, int overlay, int outline) {
        ItemAura.submit(state, pose, collector);
        state.submit(pose, collector, light, overlay, outline);
    }
}
