package top.leonx.irisveil.mixin.iris;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.leonx.irisveil.IrisVeilCompat;
import top.leonx.irisveil.compat.simulated.SimulatedEndSeaCompat;
import top.leonx.irisveil.compat.simulated.SimulatedEndSeaIrisRenderer;

@Mixin(LevelRenderer.class)
public class MixinLevelRendererEndSea {
    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderWorldBorder(Lnet/minecraft/client/Camera;)V",
            shift = At.Shift.AFTER
        )
    )
    private void irisveil$renderEndSeaBeforeIrisFinalize(
        DeltaTracker deltaTracker,
        boolean renderBlockOutline,
        Camera camera,
        GameRenderer gameRenderer,
        LightTexture lightTexture,
        Matrix4f modelView,
        Matrix4f projection,
        CallbackInfo ci) {
        if (!IrisVeilCompat.isShaderPackInUse() || SimulatedEndSeaCompat.isRenderingEndSeaShadowMap()) {
            return;
        }

        SimulatedEndSeaIrisRenderer.renderIntoIrisFramebuffer(camera, gameRenderer);
    }
}
