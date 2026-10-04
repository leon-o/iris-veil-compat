package top.leonx.irisveil.compat.veil.mixin;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.profiling.ProfilerFiller;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import foundry.veil.api.client.render.rendertype.VeilRenderType;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import top.leonx.irisveil.compat.aeronautics.AeronauticsLevititeCompat;

/** Defer both the chunk/Sable layer and its fixed-buffer flush as one operation. */
@Pseudo
@Mixin(targets = "foundry.veil.forge.impl.ForgeRenderTypeStageHandler", remap = false)
public abstract class MixinLevititeRenderStage {
    @WrapMethod(method = "lambda$onRenderLevelStageEnd$1")
    private static void irisveil$deferLevitite(
            ProfilerFiller profiler, RenderLevelStageEvent event,
            MultiBufferSource.BufferSource buffers, RenderType renderType, Operation<Void> original) {
        String name = VeilRenderType.getName(renderType);
        if (!AeronauticsLevititeCompat.isLevititeLayer(name)) {
            original.call(profiler, event, buffers, renderType);
            return;
        }
        // Native Veil apply binds a world-color FBO even inside Iris shadows.
        // Until a shadow tessellation bridge exists, do not submit these layers.
        if (AeronauticsLevititeCompat.shouldSkipShadow(name)) {
            return;
        }
        // Matrices in an event belong to the caller and may be reused before final.
        RenderLevelStageEvent captured = new RenderLevelStageEvent(event.getStage(), event.getLevelRenderer(),
            event.getPoseStack(), new Matrix4f(event.getModelViewMatrix()), new Matrix4f(event.getProjectionMatrix()),
            event.getRenderTick(), event.getPartialTick(), event.getCamera(), event.getFrustum());
        if (!AeronauticsLevititeCompat.defer(name, () -> original.call(profiler, captured, buffers, renderType))) {
            original.call(profiler, event, buffers, renderType);
        }
    }
}
