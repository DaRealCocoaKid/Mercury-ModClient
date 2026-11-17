package dev.mercury.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.mercury.client.fx.CustomSky;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reemplaza el cielo del juego por el Skybox de Mercury (dentro de la misma fase del cielo, detras de todo). */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {
    @Shadow @Final private RenderTarget renderTarget;

    /** Overworld: el disco de cielo es lo primero que se dibuja. */
    @Inject(method = "renderSkyDisc", at = @At("HEAD"), cancellable = true, require = 0)
    private void mercury$sky(int color, CallbackInfo ci) {
        if (CustomSky.on() && CustomSky.draw(this.renderTarget)) ci.cancel();
    }

    /** End: el cubo de cielo del End. */
    @Inject(method = "renderEndSky", at = @At("HEAD"), cancellable = true, require = 0)
    private void mercury$endSky(CallbackInfo ci) {
        if (CustomSky.on() && CustomSky.draw(this.renderTarget)) ci.cancel();
    }

    @Inject(method = "renderSunriseAndSunset", at = @At("HEAD"), cancellable = true, require = 0)
    private void mercury$noSunrise(PoseStack pose, float a, int b, CallbackInfo ci) {
        if (CustomSky.hideCelestials()) ci.cancel();
    }

    @Inject(method = "renderSunMoonAndStars", at = @At("HEAD"), cancellable = true, require = 0)
    private void mercury$noSunMoon(PoseStack pose, float a, float b, float c, net.minecraft.world.level.MoonPhase ph, float d, float e, CallbackInfo ci) {
        if (CustomSky.hideCelestials()) ci.cancel();
    }

    @Inject(method = "renderDarkDisc", at = @At("HEAD"), cancellable = true, require = 0)
    private void mercury$noDark(CallbackInfo ci) {
        if (CustomSky.on()) ci.cancel();
    }
}
