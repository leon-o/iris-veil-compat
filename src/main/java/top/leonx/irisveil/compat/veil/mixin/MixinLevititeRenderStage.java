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

import top.leonx.irisveil.IrisVeilCompat;
import top.leonx.irisveil.compat.aeronautics.LevititeRenderContext;

/** Selects the appropriate Iris program while retaining Aeronautics' world render stages. */
@Pseudo
@Mixin(targets = "foundry.veil.forge.impl.ForgeRenderTypeStageHandler", remap = false)
public abstract class MixinLevititeRenderStage {
    @WrapMethod(method = "lambda$onRenderLevelStageEnd$1")
    private static void irisveil$scopeLevititeLayer(
            ProfilerFiller profiler, RenderLevelStageEvent event,
            MultiBufferSource.BufferSource buffers, RenderType renderType, Operation<Void> original) {
        if (!IrisVeilCompat.isShaderPackInUse()) {
            original.call(profiler, event, buffers, renderType);
            return;
        }
        String name = VeilRenderType.getName(renderType);
        if (!LevititeRenderContext.isLevititeLayer(name)) {
            original.call(profiler, event, buffers, renderType);
            return;
        }
        // Aero 1.3.2 already registers the base at AFTER_BLOCK_ENTITIES and ghosts
        // at AFTER_WEATHER. Keep the entire section draw and fixed-buffer flush
        // inside that phase, before Iris runs its composite/final processing.
        try (var ignored = LevititeRenderContext.enterLayer(name)) {
            original.call(profiler, event, buffers, renderType);
        }
    }
}
