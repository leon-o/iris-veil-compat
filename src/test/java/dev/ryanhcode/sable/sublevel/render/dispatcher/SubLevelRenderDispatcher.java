package dev.ryanhcode.sable.sublevel.render.dispatcher;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;

import java.util.Collection;
import java.util.List;

public final class SubLevelRenderDispatcher {
    public static final Matrix4f ORIGINAL_POSE = new Matrix4f().translation(4.0f, 5.0f, 6.0f);

    public static boolean renderBlockEntitiesCalled;
    public static Iterable<?> lastSubLevels;
    public static BlockEntityRenderer lastRenderer;
    public static PoseStack lastPoseStack;
    public static double lastCameraX;
    public static double lastCameraY;
    public static double lastCameraZ;
    public static float lastPartialTick;

    private static final SubLevelRenderDispatcher INSTANCE = new SubLevelRenderDispatcher();

    private SubLevelRenderDispatcher() {
    }

    public static SubLevelRenderDispatcher get() {
        return INSTANCE;
    }

    public static void reset() {
        renderBlockEntitiesCalled = false;
        lastSubLevels = null;
        lastRenderer = null;
        lastPoseStack = null;
        lastCameraX = 0.0;
        lastCameraY = 0.0;
        lastCameraZ = 0.0;
        lastPartialTick = 0.0f;
    }

    public void renderBlockEntities(
            Iterable<?> subLevels,
            BlockEntityRenderer renderer,
            double cameraX,
            double cameraY,
            double cameraZ,
            float partialTick) {
        renderBlockEntitiesCalled = true;
        lastSubLevels = subLevels;
        lastRenderer = renderer;
        lastCameraX = cameraX;
        lastCameraY = cameraY;
        lastCameraZ = cameraZ;
        lastPartialTick = partialTick;
        lastPoseStack = new PoseStack();
        lastPoseStack.last().pose().set(ORIGINAL_POSE);
        renderer.renderBlockEntities(List.of(new Object()), lastPoseStack, partialTick, cameraX, cameraY, cameraZ);
    }

    public interface BlockEntityRenderer {
        default void renderBlockEntities(
                Collection<?> blockEntities,
                PoseStack poseStack,
                float partialTick,
                double cameraX,
                double cameraY,
                double cameraZ) {
            for (Object blockEntity : blockEntities) {
                renderSingleBE(blockEntity, poseStack, partialTick, cameraX, cameraY, cameraZ);
            }
        }

        void renderSingleBE(
                Object blockEntity,
                PoseStack poseStack,
                float partialTick,
                double cameraX,
                double cameraY,
                double cameraZ);
    }
}
