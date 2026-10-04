package top.leonx.irisveil.compat.simulated;

import org.junit.jupiter.api.Test;

import top.leonx.irisveil.compat.veil.VeilCompatRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulatedDiagramCompatTest {
    @Test
    void wholeDiagramPassInvalidatesWorldShaderCacheState() {
        int mask = VeilCompatRegistry.registerExternalRenderState("test:diagram", SimulatedDiagramCompat::isRendering);
        assertFalse(SimulatedDiagramCompat.isRendering());
        SimulatedDiagramCompat.render(() -> {
            assertTrue(SimulatedDiagramCompat.isRendering());
            assertEquals(mask, VeilCompatRegistry.getExternalRenderStateGeneration() & mask);
        });
        assertFalse(SimulatedDiagramCompat.isRendering());
        assertEquals(0, VeilCompatRegistry.getExternalRenderStateGeneration() & mask);
    }

    @Test
    void nestedRenderAndFailureRestoreOuterState() {
        SimulatedDiagramCompat.render(() -> {
            assertThrows(IllegalStateException.class, () -> SimulatedDiagramCompat.render(() -> {
                throw new IllegalStateException("renderer failed");
            }));
            assertTrue(SimulatedDiagramCompat.isRendering());
        });
        assertFalse(SimulatedDiagramCompat.isRendering());
        assertThrows(IllegalStateException.class, () -> SimulatedDiagramCompat.render(() -> {
            throw new IllegalStateException("renderer failed");
        }));
        assertFalse(SimulatedDiagramCompat.isRendering());
    }
}
