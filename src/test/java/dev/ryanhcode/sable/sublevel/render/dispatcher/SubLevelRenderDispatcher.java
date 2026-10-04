package dev.ryanhcode.sable.sublevel.render.dispatcher;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Matrix4f;

import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public interface SubLevelRenderDispatcher {
    Matrix4f ORIGINAL_POSE = new Matrix4f().translation(4.0f, 5.0f, 6.0f);
    SubLevelRenderDispatcher INSTANCE = new TestDispatcher();

    static SubLevelRenderDispatcher get() {
        return INSTANCE;
    }

    void renderBlockEntities(
        Iterable<ClientSubLevel> subLevels,
        BlockEntityRenderer renderer,
        double cameraX,
        double cameraY,
        double cameraZ,
        float partialTick);

    void renderAfterSections(
        Iterable<ClientSubLevel> subLevels,
        double cameraX,
        double cameraY,
        double cameraZ,
        Matrix4f modelView,
        Matrix4f projection,
        float partialTick);

    final class TestHooks {
        public static boolean renderBlockEntitiesCalled;
        public static Iterable<?> lastSubLevels;
        public static BlockEntityRenderer lastRenderer;
        public static PoseStack lastPoseStack;
        public static double lastCameraX;
        public static double lastCameraY;
        public static double lastCameraZ;
        public static float lastPartialTick;
        public static int afterSectionsCalls;
        public static Matrix4f lastModelView;
        public static Matrix4f lastProjection;
        public static final Set<String> pendingSingleBlockLayers = new LinkedHashSet<>();
        public static final List<String> flushedSingleBlockLayers = new ArrayList<>();

        private TestHooks() {
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
            afterSectionsCalls = 0;
            lastModelView = null;
            lastProjection = null;
            pendingSingleBlockLayers.clear();
            flushedSingleBlockLayers.clear();
        }
    }

    final class TestDispatcher implements SubLevelRenderDispatcher {
        @Override
        public void renderAfterSections(
                Iterable<ClientSubLevel> subLevels,
                double cameraX,
                double cameraY,
                double cameraZ,
                Matrix4f modelView,
                Matrix4f projection,
                float partialTick) {
            TestHooks.afterSectionsCalls++;
            TestHooks.lastSubLevels = subLevels;
            TestHooks.lastCameraX = cameraX;
            TestHooks.lastCameraY = cameraY;
            TestHooks.lastCameraZ = cameraZ;
            TestHooks.lastModelView = new Matrix4f(modelView);
            TestHooks.lastProjection = new Matrix4f(projection);
            TestHooks.lastPartialTick = partialTick;
            // Native Vanilla/ReachAround semantics: only queued single-block
            // layers are emitted, then the queue is emptied for the next pass.
            TestHooks.flushedSingleBlockLayers.addAll(TestHooks.pendingSingleBlockLayers);
            TestHooks.pendingSingleBlockLayers.clear();
        }

        @Override
        public void renderBlockEntities(
                Iterable<ClientSubLevel> subLevels,
                BlockEntityRenderer renderer,
                double cameraX,
                double cameraY,
                double cameraZ,
                float partialTick) {
            TestHooks.renderBlockEntitiesCalled = true;
            TestHooks.lastSubLevels = subLevels;
            TestHooks.lastRenderer = renderer;
            TestHooks.lastCameraX = cameraX;
            TestHooks.lastCameraY = cameraY;
            TestHooks.lastCameraZ = cameraZ;
            TestHooks.lastPartialTick = partialTick;
            TestHooks.lastPoseStack = new PoseStack();
            TestHooks.lastPoseStack.last().pose().set(ORIGINAL_POSE);
            renderer.renderBlockEntities(List.of(new BlockEntity()), TestHooks.lastPoseStack, partialTick, cameraX, cameraY, cameraZ);
        }
    }

    interface BlockEntityRenderer {
        default void renderBlockEntities(
                Collection<BlockEntity> blockEntities,
                PoseStack poseStack,
                float partialTick,
                double cameraX,
                double cameraY,
                double cameraZ) {
            for (BlockEntity blockEntity : blockEntities) {
                renderSingleBE(blockEntity, poseStack, partialTick, cameraX, cameraY, cameraZ);
            }
        }

        void renderSingleBE(
            BlockEntity blockEntity,
            PoseStack poseStack,
            float partialTick,
            double cameraX,
            double cameraY,
            double cameraZ);

        BlockEntityRenderDispatcher getBlockEntityRenderDispatcher();
    }
}
