package top.leonx.irisveil.compat.simulated;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulatedEndSeaCompatTest {
    @Test
    void finalCompositeRenderScopeIsOnlyActiveDuringCallback() {
        assertFalse(SimulatedEndSeaCompat.isRenderingFinalCompositeEndSea());

        SimulatedEndSeaCompat.withFinalCompositeEndSeaRender(
            () -> assertTrue(SimulatedEndSeaCompat.isRenderingFinalCompositeEndSea()));

        assertFalse(SimulatedEndSeaCompat.isRenderingFinalCompositeEndSea());
    }

    @Test
    void finalCompositeRenderScopeIsClearedAfterFailure() {
        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
            SimulatedEndSeaCompat.withFinalCompositeEndSeaRender(() -> {
                assertTrue(SimulatedEndSeaCompat.isRenderingFinalCompositeEndSea());
                throw new RuntimeException("boom");
            }));

        assertEquals("boom", thrown.getMessage());
        assertFalse(SimulatedEndSeaCompat.isRenderingFinalCompositeEndSea());
    }

    @Test
    void endSeaKeepsVanillaDrawWhenShaderpackIsInactive() {
        assertFalse(SimulatedEndSeaCompat.shouldDrawEndSeaIntoBoundFramebuffer(false, true));
    }

    @Test
    void endSeaKeepsVanillaDrawWithoutCompatibleFramebuffer() {
        assertFalse(SimulatedEndSeaCompat.shouldDrawEndSeaIntoBoundFramebuffer(true, false));
    }

    @Test
    void endSeaDrawsIntoBoundFramebufferWithShaderpackAndCompatibleFramebuffer() {
        assertTrue(SimulatedEndSeaCompat.shouldDrawEndSeaIntoBoundFramebuffer(true, true));
    }

    @Test
    void framebufferIsRestoredWhenWorldColorDrawFails() {
        List<String> calls = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> SimulatedEndSeaCompat.drawIntoWorldColor(
            () -> calls.add("bind-read-side"),
            () -> { throw new IllegalStateException("draw failed"); },
            () -> calls.add("restore")));
        assertEquals(List.of("bind-read-side", "restore"), calls);
    }

    @Test
    void framebufferIsRestoredWhenTargetBindingFails() {
        List<String> calls = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> SimulatedEndSeaCompat.drawIntoWorldColor(
            () -> { throw new IllegalStateException("bind failed"); },
            () -> calls.add("draw"),
            () -> calls.add("restore")));
        assertEquals(List.of("restore"), calls);
    }

    @Test
    void endSeaFinalCompositeWritesToColortexZero() {
        int[] drawBuffers = SimulatedEndSeaCompat.finalCompositeDrawBuffers();

        assertEquals(1, drawBuffers.length);
        assertEquals(0, drawBuffers[0]);

        drawBuffers[0] = 3;
        assertEquals(0, SimulatedEndSeaCompat.finalCompositeDrawBuffers()[0]);
    }

    @Test
    void endSeaBoundFramebufferDrawStillAppliesVeilDefaultUniformsBeforeSamplers() {
        List<String> calls = new ArrayList<>();

        SimulatedEndSeaCompat.applyEndSeaBoundFramebufferShader(
            () -> calls.add("bind"),
            () -> calls.add("defaultUniforms"),
            samplerStart -> calls.add("samplers:" + samplerStart));

        assertEquals(List.of("bind", "defaultUniforms", "samplers:0"), calls);
    }
}
