package top.leonx.irisveil.mixin.iris;

import net.minecraft.client.renderer.ShaderInstance;

import com.mojang.blaze3d.shaders.Uniform;
import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import top.leonx.irisveil.compat.aeronautics.LevititeGbufferBridge;

@Mixin(value = ExtendedShader.class, remap = false)
public abstract class MixinLevititeUniforms {
    @Inject(method = "getUniform", at = @At("HEAD"), cancellable = true)
    private void irisveil$nativePhysicsUniform(String name, CallbackInfoReturnable<Uniform> cir) {
        Uniform uniform = LevititeGbufferBridge.getNativeUniform((ShaderInstance) (Object) this, name);
        if (uniform != null) cir.setReturnValue(uniform);
    }
}
