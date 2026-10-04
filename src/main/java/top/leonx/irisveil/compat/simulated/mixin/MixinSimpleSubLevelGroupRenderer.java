package top.leonx.irisveil.compat.simulated.mixin;

import java.util.Collection;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.irisshaders.iris.vertices.ImmediateState;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;

import top.leonx.irisveil.compat.simulated.SimulatedDiagramCompat;

@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.util.SimpleSubLevelGroupRenderer", remap = false)
public abstract class MixinSimpleSubLevelGroupRenderer {
    @WrapMethod(method = "renderGroup")
    private static void irisveil$renderDiagramInOwnPass(
            ClientLevel level, Collection<?> subLevels, @Coerce Object framebuffer,
            Matrix4f modelView, Matrix4f projection, Vector3d cameraPosition,
            Quaternionf cameraRotation, float partialTick, boolean renderPlayer,
            Operation<Void> original) {
        // Flush world geometry before switching shader and vertex formats.
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        buffers.endBatch();
        boolean previousBypass = ImmediateState.bypass;
        boolean previousSkipExtension = ImmediateState.skipExtension.get();
        try {
            ImmediateState.bypass = true;
            ImmediateState.skipExtension.set(true);
            SimulatedDiagramCompat.render(() -> {
                try {
                    original.call(level, subLevels, framebuffer, modelView, projection,
                        cameraPosition, cameraRotation, partialTick, renderPlayer);
                } finally {
                    buffers.endBatch();
                }
            });
        } finally {
            ImmediateState.bypass = previousBypass;
            ImmediateState.skipExtension.set(previousSkipExtension);
        }
    }
}
