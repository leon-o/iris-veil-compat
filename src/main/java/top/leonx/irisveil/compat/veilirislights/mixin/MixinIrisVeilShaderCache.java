package top.leonx.irisveil.compat.veilirislights.mixin;

import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.irisshaders.iris.Iris;

import top.leonx.irisveil.IrisVeilCompat;
import top.leonx.irisveil.accessors.IrisRenderingPipelineAccessor;
import top.leonx.irisveil.compat.veilirislights.VeilIrisLightsCompat;

/** Optional cache boundary verified against Veil Iris Lights 1.4.6. */
@Pseudo
@Mixin(targets = "com.yesmenn.veilirislights.compat.veil.IrisVeilShaderCache", remap = false)
public abstract class MixinIrisVeilShaderCache {
    @Unique
    private static boolean irisveil$loggedGeometryOwnership;

    @Inject(
        method = "getOrCreate(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/ShaderInstance;",
        at = @At("HEAD"),
        cancellable = true,
        require = 1,
        remap = false)
    private static void irisveil$yieldGeometryReplacement(
            ResourceLocation shaderPath, CallbackInfoReturnable<ShaderInstance> cir) {
        boolean shadersEnabled = IrisVeilCompat.isShaderPackInUse();
        boolean bridgePipelineAvailable = shadersEnabled
            && Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipelineAccessor;
        if (!VeilIrisLightsCompat.shouldSuppressGeometry(shaderPath, shadersEnabled, bridgePipelineAvailable)) {
            return;
        }
        // VIL's shard privately caches this result. Never permit a first lookup
        // in an external/native-specialized pass to retain a competing shader
        // for later world draws. A null leaves the shared native setup and our
        // replacement/exclusion rules in control, regardless of mixin order.
        cir.setReturnValue(null);
        if (!irisveil$loggedGeometryOwnership) {
            irisveil$loggedGeometryOwnership = true;
            IrisVeilCompat.LOGGER.info(
                "Veil Iris Lights coexistence: disabled duplicate geometry replacement; independent light passes are untouched");
        }
    }
}
