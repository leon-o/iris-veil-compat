package top.leonx.irisveil.compat.sable;

import java.util.List;

import dev.ryanhcode.sable.sublevel.render.dispatcher.SubLevelRenderDispatcher;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SableShadowSingleBlockTest {
    @BeforeEach
    void resetDispatcher() {
        SubLevelRenderDispatcher.TestHooks.reset();
    }

    @Test
    void consumesTheSingleBlockQueueWithShadowMatricesAndCamera() {
        Matrix4f modelView = new Matrix4f().rotateY(0.7f).translate(1.0f, 2.0f, 3.0f);
        Matrix4f projection = new Matrix4f().ortho(-16.0f, 16.0f, -16.0f, 16.0f, 0.1f, 128.0f);
        SubLevelRenderDispatcher.TestHooks.pendingSingleBlockLayers.add("solid");

        assertTrue(SableShadowCompat.renderSubLevelSingleBlocks(
            new ClientLevel(), modelView, projection, 120.0, 64.5, -20.0, 0.25f));

        assertEquals(List.of("solid"), SubLevelRenderDispatcher.TestHooks.flushedSingleBlockLayers);
        assertTrue(SubLevelRenderDispatcher.TestHooks.pendingSingleBlockLayers.isEmpty());
        assertEquals(modelView, SubLevelRenderDispatcher.TestHooks.lastModelView);
        assertEquals(projection, SubLevelRenderDispatcher.TestHooks.lastProjection);
        assertEquals(120.0, SubLevelRenderDispatcher.TestHooks.lastCameraX);
        assertEquals(64.5, SubLevelRenderDispatcher.TestHooks.lastCameraY);
        assertEquals(-20.0, SubLevelRenderDispatcher.TestHooks.lastCameraZ);
        assertEquals(0.25f, SubLevelRenderDispatcher.TestHooks.lastPartialTick);
        assertFalse(SubLevelRenderDispatcher.TestHooks.renderBlockEntitiesCalled);
    }

    @Test
    void opaqueAndTranslucentFlushesDoNotReplayEarlierGeometry() {
        ClientLevel level = new ClientLevel();
        Matrix4f view = new Matrix4f();
        Matrix4f projection = new Matrix4f();
        SubLevelRenderDispatcher.TestHooks.pendingSingleBlockLayers.add("solid");
        assertTrue(SableShadowCompat.renderSubLevelSingleBlocks(level, view, projection, 0, 0, 0, 0));
        assertEquals(List.of("solid"), SubLevelRenderDispatcher.TestHooks.flushedSingleBlockLayers);

        // Iris copies opaque depth here. Only the later translucent call queues
        // this second layer, so a subsequent flush must not replay solid.
        SubLevelRenderDispatcher.TestHooks.pendingSingleBlockLayers.add("translucent");
        assertTrue(SableShadowCompat.renderSubLevelSingleBlocks(level, view, projection, 0, 0, 0, 0));
        assertTrue(SableShadowCompat.renderSubLevelSingleBlocks(level, view, projection, 0, 0, 0, 0));

        assertEquals(List.of("solid", "translucent"), SubLevelRenderDispatcher.TestHooks.flushedSingleBlockLayers);
        assertTrue(SubLevelRenderDispatcher.TestHooks.pendingSingleBlockLayers.isEmpty());
    }

    @Test
    void nonWorldOrMissingMatrixDoesNotConsumePendingGeometry() {
        SubLevelRenderDispatcher.TestHooks.pendingSingleBlockLayers.add("solid");
        assertFalse(SableShadowCompat.renderSubLevelSingleBlocks(new Object(), new Matrix4f(), new Matrix4f(), 0, 0, 0, 0));
        assertFalse(SableShadowCompat.renderSubLevelSingleBlocks(new ClientLevel(), null, new Matrix4f(), 0, 0, 0, 0));
        assertFalse(SableShadowCompat.renderSubLevelSingleBlocks(new ClientLevel(), new Matrix4f(), null, 0, 0, 0, 0));
        assertEquals(0, SubLevelRenderDispatcher.TestHooks.afterSectionsCalls);
        assertEquals(List.of(), SubLevelRenderDispatcher.TestHooks.flushedSingleBlockLayers);
        assertEquals(1, SubLevelRenderDispatcher.TestHooks.pendingSingleBlockLayers.size());
    }
}
