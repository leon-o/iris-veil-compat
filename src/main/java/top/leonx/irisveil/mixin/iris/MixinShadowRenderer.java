package top.leonx.irisveil.mixin.iris;

import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.leonx.irisveil.compat.veil.RenderStateManager;

/**
 * Tracks the Iris shadow render pass so that Veil shaders can be compiled
 * with the correct Iris program ({@code ProgramId.Shadow}) instead of the
 * default block program.
 *
 * <p>Unlike Flywheel, Veil shaders render through standard Minecraft
 * {@code RenderType} / {@code ShaderProgramShard} — Iris's existing
 * block-entity iteration during the shadow pass already triggers Veil
 * draw calls.  No explicit entity-rendering hook (like Flywheel's
 * {@code afterEntities()}) is required here.
 */
@Mixin(value = ShadowRenderer.class)
public abstract class MixinShadowRenderer {

    @Final
    @Shadow
    private boolean shouldRenderBlockEntities;

    @Inject(method = "renderShadows", at = @At("HEAD"))
    private void irisveil$onShadowPassStart(
            net.irisshaders.iris.mixin.LevelRendererAccessor levelRendererAccessor,
            Camera camera,
            CallbackInfo ci) {
        if (shouldRenderBlockEntities) {
            RenderStateManager.setRenderingShadow(true);
        }
    }

    @Inject(method = "renderShadows", at = @At("TAIL"))
    private void irisveil$onShadowPassEnd(
            net.irisshaders.iris.mixin.LevelRendererAccessor levelRendererAccessor,
            Camera camera,
            CallbackInfo ci) {
        RenderStateManager.setRenderingShadow(false);
    }
}
