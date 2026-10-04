package top.leonx.irisveil.compat.aeronautics;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LevititeRenderQueueTest {
    @Test
    void onlyExactLevititeLayersInTheShaderpackWorldPassAreDeferred() {
        LevititeRenderQueue queue = new LevititeRenderQueue();
        queue.beginFrame();
        Runnable draw = () -> {};
        assertFalse(queue.defer("aeronautics:levitite", false, false, draw));
        assertFalse(queue.defer("aeronautics:levitite", true, true, draw));
        assertFalse(queue.defer("aeronautics:other", true, false, draw));
        assertFalse(queue.defer(null, true, false, draw));
        assertTrue(queue.defer("aeronautics:levitite", true, false, draw));
        assertTrue(queue.defer("aeronautics:levitite_ghosts", true, false, draw));
    }

    @Test
    void refreshesDepthBeforeBothBaseAndGhostDrawsAndDoesNotRequeueReplay() {
        LevititeRenderQueue queue = new LevititeRenderQueue();
        queue.beginFrame();
        List<String> calls = new ArrayList<>();
        queue.defer("aeronautics:levitite", true, false, () -> {
            assertTrue(queue.isRendering());
            assertFalse(queue.defer("aeronautics:levitite", true, false, () -> {}));
            calls.add("base");
        });
        queue.defer("aeronautics:levitite_ghosts", true, false, () -> calls.add("ghosts"));
        queue.replay(() -> calls.add("depth"));
        assertEquals(List.of("depth", "base", "depth", "ghosts"), calls);
        assertFalse(queue.hasDraws());
        assertFalse(queue.isRendering());
        queue.replay(() -> calls.add("unexpected"));
        assertEquals(4, calls.size());
    }

    @Test
    void clearsScopeAndPendingCallbacksWhenRenderingFails() {
        LevititeRenderQueue queue = new LevititeRenderQueue();
        queue.beginFrame();
        queue.defer("aeronautics:levitite", true, false, () -> { throw new IllegalStateException("draw"); });
        queue.defer("aeronautics:levitite_ghosts", true, false, () -> { throw new AssertionError("stale draw"); });
        assertThrows(IllegalStateException.class, () -> queue.replay(() -> {}));
        assertFalse(queue.isRendering());
        assertFalse(queue.hasDraws());
        queue.replay(() -> {});
    }

    @Test
    void clearsScopeWhenDepthCopyFailsAndDropsPreviousFrameCommands() {
        LevititeRenderQueue queue = new LevititeRenderQueue();
        queue.beginFrame();
        queue.defer("aeronautics:levitite", true, false, () -> { throw new AssertionError("not reached"); });
        assertThrows(IllegalStateException.class, () -> queue.replay(() -> { throw new IllegalStateException("depth"); }));
        assertFalse(queue.isRendering());
        assertFalse(queue.hasDraws());
        queue.beginFrame();
        queue.defer("aeronautics:levitite", true, false, () -> { throw new AssertionError("stale frame"); });
        queue.clear();
        queue.replay(() -> {});
    }

    @Test
    void fixedBuffersAreRetainedOnlyBetweenFrameStartAndFinalReplay() {
        LevititeRenderQueue queue = new LevititeRenderQueue();
        assertFalse(queue.shouldRetain("aeronautics:levitite", true, false));
        queue.beginFrame();
        assertTrue(queue.shouldRetain("aeronautics:levitite", true, false));
        assertTrue(queue.shouldRetain("aeronautics:levitite_ghosts", true, false));
        assertFalse(queue.shouldRetain("aeronautics:levitite", true, true));
        queue.defer("aeronautics:levitite", true, false,
            () -> assertFalse(queue.shouldRetain("aeronautics:levitite", true, false)));
        queue.replay(() -> {});
        assertFalse(queue.shouldRetain("aeronautics:levitite", true, false));
        queue.beginFrame();
        queue.finishCollection();
        assertFalse(queue.shouldRetain("aeronautics:levitite", true, false));
    }

    @Test
    void detachedMeshesAreOrderedAfterTheirLayerAndReleasedEvenWhenReplayFails() {
        LevititeRenderQueue queue = new LevititeRenderQueue();
        queue.beginFrame();
        List<String> calls = new ArrayList<>();
        queue.deferMesh("aeronautics:levitite_ghosts", true, false,
            () -> calls.add("ghostMesh"), () -> calls.add("releaseGhost"));
        queue.deferMesh("aeronautics:levitite", true, false,
            () -> { calls.add("baseMesh"); throw new IllegalStateException("draw"); }, () -> calls.add("releaseBase"));
        queue.defer("aeronautics:levitite", true, false, () -> calls.add("baseLayer"));
        assertThrows(IllegalStateException.class, () -> queue.replay(() -> {}));
        assertEquals(List.of("baseLayer", "baseMesh", "releaseBase", "releaseGhost"), calls);
        assertFalse(queue.isRendering());
        assertFalse(queue.hasDraws());
    }

    @Test
    void newFrameAndDestroyReleaseDetachedMeshesWithoutDrawingThem() {
        LevititeRenderQueue queue = new LevititeRenderQueue();
        queue.beginFrame();
        List<String> calls = new ArrayList<>();
        queue.deferMesh("aeronautics:levitite", true, false,
            () -> { throw new AssertionError("stale"); }, () -> calls.add("frameDiscard"));
        queue.beginFrame();
        queue.deferMesh("aeronautics:levitite", true, false,
            () -> { throw new AssertionError("stale"); }, () -> calls.add("destroyDiscard"));
        queue.clear();
        assertEquals(List.of("frameDiscard", "destroyDiscard"), calls);
    }
}
