package top.leonx.irisveil.mixin.iris;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.irisshaders.iris.pipeline.IrisRenderingPipeline;

import top.leonx.irisveil.compat.aeronautics.AeronauticsLevititeCompat;

@Mixin(value = IrisRenderingPipeline.class, remap = false)
public abstract class MixinLevititeFinalPass {
    @Inject(method = "beginLevelRendering", at = @At("HEAD"))
    private void irisveil$beginLevititeFrame(CallbackInfo ci) {
        AeronauticsLevititeCompat.beginFrame();
    }

    @Inject(method = "finalizeLevelRendering", at = @At(value = "INVOKE",
        target = "Lnet/irisshaders/iris/pipeline/FinalPassRenderer;renderFinalPass()V", shift = At.Shift.AFTER))
    private void irisveil$renderNativeLevitite(CallbackInfo ci) {
        AeronauticsLevititeCompat.renderDeferred();
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void irisveil$destroyLevititeBuffers(CallbackInfo ci) {
        AeronauticsLevititeCompat.destroy();
    }
}
