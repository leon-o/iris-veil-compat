package top.leonx.irisveil.compat.veil.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import foundry.veil.impl.client.render.pipeline.ShaderProgramShard;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.leonx.irisveil.IrisVeilCompat;
import top.leonx.irisveil.compat.veil.IrisVeilShaderCache;

/**
 * Intercepts {@link ShaderProgramShard#setupRenderState()} at runtime.
 * When Iris is active, lets Veil set its normal render state first, then
 * switches the active program to an Iris {@link ShaderInstance} that outputs
 * to gbuffer.
 *
 * <p>This mixin approach avoids inheritance issues with the
 * {@code ShaderStateShard} API which varies between Minecraft versions.
 */
@Mixin(value = ShaderProgramShard.class, remap = false)
public class MixinShaderProgramShard {

    @Unique
    private volatile ShaderInstance irisveil$cachedIrisShader;

    @Unique
    private volatile int irisveil$lastShaderPackGen = -1;

    @Inject(method = "setupRenderState", at = @At("TAIL"))
    private void irisveil$interceptSetupRenderState(CallbackInfo ci) {
        if (!IrisVeilCompat.isShaderPackInUse()) {
            return; // no shaderpack loaded → let Veil handle it
        }

        ResourceLocation shaderPath = ((ShaderProgramShard) (Object) this).getShader();

        try {
            ShaderInstance irisShader = irisveil$getOrCreateIrisShader(shaderPath);
            if (irisShader != null) {
                irisShader.apply();
                RenderSystem.setShader(() -> irisShader); // register in RenderSystem so draw uses Iris shader
            }
        } catch (Exception e) {
            IrisVeilCompat.LOGGER.warn(
                "IrisVeilCompat: failed for '{}', falling back to Veil", shaderPath, e);
            // Invalidate so we retry next frame (Veil shader may have been compiled lazily)
            irisveil$cachedIrisShader = null;
        }
    }

    @Unique
    private ShaderInstance irisveil$getOrCreateIrisShader(ResourceLocation shaderPath) {
        int currentGen = IrisVeilShaderCache.getShaderPackGeneration();
        // Shaderpack changed → invalidate both cached result and gen tracker
        if (irisveil$lastShaderPackGen != currentGen) {
            irisveil$cachedIrisShader = null;
        }

        // Use cached result only if valid (non-null) and generation matches
        if (irisveil$cachedIrisShader != null && irisveil$lastShaderPackGen == currentGen) {
            return irisveil$cachedIrisShader;
        }

        irisveil$cachedIrisShader = IrisVeilShaderCache.getOrCreate(shaderPath);
        irisveil$lastShaderPackGen = currentGen;
        return irisveil$cachedIrisShader;
    }
}
