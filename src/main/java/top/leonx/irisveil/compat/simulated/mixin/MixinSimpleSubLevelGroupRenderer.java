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

import top.leonx.irisveil.IrisVeilCompat;
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
        if (!IrisVeilCompat.isShaderPackInUse()) {
            original.call(level, subLevels, framebuffer, modelView, projection,
                cameraPosition, cameraRotation, partialTick, renderPlayer);
            return;
        }

        // The native entry flush must not submit world vertices with our bypass
        // flags. Drain them first, while the caller's shader/vertex state is active.
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        buffers.endBatch();
        boolean previousBypass = ImmediateState.bypass;
        boolean previousSkipExtension = ImmediateState.skipExtension.get();
        try {
            ImmediateState.bypass = true;
            ImmediateState.skipExtension.set(true);
            // Native renderGroup flushes entities before restoring its camera.
            // Keep the scope through that flush; do not draw again after restoration.
            SimulatedDiagramCompat.render(() -> original.call(
                level, subLevels, framebuffer, modelView, projection,
                cameraPosition, cameraRotation, partialTick, renderPlayer));
        } finally {
            ImmediateState.bypass = previousBypass;
            ImmediateState.skipExtension.set(previousSkipExtension);
        }
    }
}
