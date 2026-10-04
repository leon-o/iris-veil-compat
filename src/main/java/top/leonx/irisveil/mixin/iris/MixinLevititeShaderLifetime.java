package top.leonx.irisveil.mixin.iris;

import net.minecraft.client.renderer.ShaderInstance;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.neoforged.fml.ModList;

import top.leonx.irisveil.compat.aeronautics.LevititeGbufferBridge;
import top.leonx.irisveil.compat.veil.IrisVeilShaderCache;

@Mixin(ShaderInstance.class)
public abstract class MixinLevititeShaderLifetime {
    @Inject(method = "close", at = @At("HEAD"))
    private void irisveil$forgetClosedProgram(CallbackInfo ci) {
        ShaderInstance shader = (ShaderInstance) (Object) this;
        LevititeGbufferBridge.remove(shader);
        if (ModList.get().isLoaded("veil")) {
            IrisVeilShaderCache.forgetClosedShader(shader);
        }
    }
}
