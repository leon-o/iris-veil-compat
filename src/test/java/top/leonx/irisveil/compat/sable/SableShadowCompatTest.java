package top.leonx.irisveil.compat.sable;

import dev.ryanhcode.sable.mixinhelpers.sublevel_render.vanilla.VanillaSubLevelBlockEntityRenderer;
import dev.ryanhcode.sable.sublevel.render.dispatcher.SubLevelRenderDispatcher;
import org.junit.jupiter.api.Test;
import org.joml.Matrix4f;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SableShadowCompatTest {
    @Test
    void forwardsSubLevelBlockEntitiesToSableDispatcher() {
        SubLevelRenderDispatcher.reset();
        Object level = new Object();
        Object renderBuffers = new Object();
        Object blockEntityRenderDispatcher = new Object();

        boolean rendered = SableShadowCompat.renderSubLevelBlockEntities(
            level,
            renderBuffers,
            blockEntityRenderDispatcher,
            1.0,
            2.0,
            3.0,
            0.5f);

        assertTrue(rendered);
        assertTrue(SubLevelRenderDispatcher.renderBlockEntitiesCalled);
        assertEquals(List.of("object-sublevel"), SubLevelRenderDispatcher.lastSubLevels);
        assertNotNull(SubLevelRenderDispatcher.lastRenderer);
        assertEquals(1.0, SubLevelRenderDispatcher.lastCameraX);
        assertEquals(2.0, SubLevelRenderDispatcher.lastCameraY);
        assertEquals(3.0, SubLevelRenderDispatcher.lastCameraZ);
        assertEquals(0.5f, SubLevelRenderDispatcher.lastPartialTick);
        assertSame(renderBuffers, VanillaSubLevelBlockEntityRenderer.lastRenderBuffers);
        assertSame(blockEntityRenderDispatcher, VanillaSubLevelBlockEntityRenderer.lastBlockEntityRenderDispatcher);
    }

    @Test
    void choosesMostSpecificSableContainerOverload() {
        SubLevelRenderDispatcher.reset();

        boolean rendered = SableShadowCompat.renderSubLevelBlockEntities(
            "client-level",
            new Object(),
            new Object(),
            1.0,
            2.0,
            3.0,
            0.5f);

        assertTrue(rendered);
        assertEquals(List.of("string-sublevel"), SubLevelRenderDispatcher.lastSubLevels);
    }

    @Test
    void appliesShadowModelViewWhileSableRendersBlockEntities() {
        SubLevelRenderDispatcher.reset();
        Object level = new Object();
        Object renderBuffers = new Object();
        Object blockEntityRenderDispatcher = new Object();
        Matrix4f shadowModelView = new Matrix4f().translation(10.0f, 20.0f, 30.0f);

        boolean rendered = SableShadowCompat.renderSubLevelBlockEntities(
            level,
            renderBuffers,
            blockEntityRenderDispatcher,
            shadowModelView,
            1.0,
            2.0,
            3.0,
            0.5f);

        assertTrue(rendered);
        assertMatrixEquals(
            new Matrix4f(shadowModelView).mul(SubLevelRenderDispatcher.ORIGINAL_POSE),
            VanillaSubLevelBlockEntityRenderer.poseDuringRender);
        assertMatrixEquals(
            SubLevelRenderDispatcher.ORIGINAL_POSE,
            SubLevelRenderDispatcher.lastPoseStack.last().pose());
    }

    private static void assertMatrixEquals(Matrix4f expected, Matrix4f actual) {
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                assertEquals(expected.get(row, column), actual.get(row, column), 0.0001f);
            }
        }
    }
}
