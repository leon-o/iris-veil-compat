package top.leonx.irisveil.compat.simulated.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import top.leonx.irisveil.compat.simulated.SimulatedPhysicsStaffCompat;

@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler", remap = false)
public abstract class MixinPhysicsStaffClientHandler {
    @Shadow
    public abstract void onRender(PoseStack poseStack);

    @Inject(method = "<init>", at = @At("TAIL"))
    private void irisveil$registerBeforeDeferred(CallbackInfo ci) {
        SimulatedPhysicsStaffCompat.register(this::onRender);
    }

    @Inject(method = "onRender", at = @At("HEAD"), cancellable = true)
    private void irisveil$skipCompletedLateRender(PoseStack poseStack, CallbackInfo ci) {
        if (SimulatedPhysicsStaffCompat.shouldSkipNativeEvent()) {
            ci.cancel();
        }
    }
}
