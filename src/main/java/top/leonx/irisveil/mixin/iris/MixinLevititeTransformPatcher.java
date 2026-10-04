package top.leonx.irisveil.mixin.iris;

import java.util.Map;

import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import top.leonx.irisveil.compat.aeronautics.LevititeGbufferBridge;

@Mixin(value = TransformPatcher.class, remap = false)
public abstract class MixinLevititeTransformPatcher {
    @Inject(method = "patchVanilla", at = @At("RETURN"), cancellable = true)
    private static void irisveil$composeLevitite(CallbackInfoReturnable<Map<PatchShaderType, String>> cir) {
        cir.setReturnValue(LevititeGbufferBridge.transform(cir.getReturnValue()));
    }
}
