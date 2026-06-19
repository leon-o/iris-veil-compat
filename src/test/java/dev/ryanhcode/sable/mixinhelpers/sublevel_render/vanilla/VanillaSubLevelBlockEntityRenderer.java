package dev.ryanhcode.sable.mixinhelpers.sublevel_render.vanilla;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.sublevel.render.dispatcher.SubLevelRenderDispatcher;
import org.joml.Matrix4f;

public final class VanillaSubLevelBlockEntityRenderer implements SubLevelRenderDispatcher.BlockEntityRenderer {
    public static Object lastRenderBuffers;
    public static Object lastBlockEntityRenderDispatcher;
    public static Matrix4f poseDuringRender;

    public VanillaSubLevelBlockEntityRenderer(
            Object blockEntityRenderDispatcher,
            Object renderBuffers,
            Object destructionProgress) {
        lastBlockEntityRenderDispatcher = blockEntityRenderDispatcher;
        lastRenderBuffers = renderBuffers;
    }

    @Override
    public void renderSingleBE(
            Object blockEntity,
            PoseStack poseStack,
            float partialTick,
            double cameraX,
            double cameraY,
            double cameraZ) {
        poseDuringRender = new Matrix4f(poseStack.last().pose());
    }
}
