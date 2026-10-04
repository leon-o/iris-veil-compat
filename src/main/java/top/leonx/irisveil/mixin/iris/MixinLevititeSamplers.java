package top.leonx.irisveil.mixin.iris;

import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import top.leonx.irisveil.compat.aeronautics.LevititeGbufferBridge;

@Mixin(value = ProgramSamplers.Builder.class, remap = false)
public abstract class MixinLevititeSamplers {
    @Inject(method = "build", at = @At("HEAD"))
    private void irisveil$addNoiseSampler(CallbackInfoReturnable<ProgramSamplers> cir) {
        LevititeGbufferBridge.addSamplers((SamplerHolder) this);
    }
}
