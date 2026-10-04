package top.leonx.irisveil.compat.aeronautics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LevititeRenderContextTest {
    @Test
    void baseShadowDrawDoesNotInheritOrOverwriteAnEnclosingGhostPass() {
        assertFalse(LevititeRenderContext.isGhostPass());
        try (var ghost = LevititeRenderContext.enterLayer("aeronautics:levitite_ghosts")) {
            assertTrue(LevititeRenderContext.isGhostPass());
            try (var shadowBase = LevititeRenderContext.enterLayer("aeronautics:levitite")) {
                assertFalse(LevititeRenderContext.isGhostPass());
            }
            assertTrue(LevititeRenderContext.isGhostPass());
        }
        assertFalse(LevititeRenderContext.isGhostPass());
    }

    @Test
    void failedLayerDrawRestoresThePriorPass() {
        try (var ghost = LevititeRenderContext.enterLayer("aeronautics:levitite_ghosts")) {
            assertThrows(IllegalStateException.class, () -> {
                try (var shadowBase = LevititeRenderContext.enterLayer("aeronautics:levitite")) {
                    throw new IllegalStateException("draw failure");
                }
            });
            assertTrue(LevititeRenderContext.isGhostPass());
        }
        assertFalse(LevititeRenderContext.isGhostPass());
    }
}
