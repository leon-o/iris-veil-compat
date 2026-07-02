package top.leonx.irisveil.compat.veil.mixin;

import foundry.veil.Veil;
import foundry.veil.api.client.render.CullFrustum;
import foundry.veil.api.client.render.framebuffer.AdvancedFbo;
import foundry.veil.api.client.render.light.renderer.LightRenderer;
import foundry.veil.api.client.render.light.renderer.LightTypeRenderer;
import foundry.veil.impl.client.render.light.VoxelShadowGrid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.leonx.irisveil.IrisVeilCompat;


// Takes over Veil's deferred light rendering under Iris.

@Mixin(value = LightRenderer.class, remap = false)
public class MixinLightRenderer {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void irisveil$replaceUnderIris(CullFrustum frustum, AdvancedFbo lightFbo,
                                           CallbackInfoReturnable<Boolean> cir) {
        if (!Veil.IRIS) {
            return;
        }
        try {
            LightRenderer self = (LightRenderer) (Object) this;
            for (LightTypeRenderer<?> renderer : self.getRenderers().values()) {
                renderer.prepareLights(self, frustum);
            }
            VoxelShadowGrid.setup();
        } catch (Throwable t) {
            IrisVeilCompat.LOGGER.error("IrisVeilCompat: world shadow grid update failed", t);
        }
        cir.setReturnValue(false);
    }
}
