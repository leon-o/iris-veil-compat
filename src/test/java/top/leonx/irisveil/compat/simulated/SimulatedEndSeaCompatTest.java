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
    void endSeaBoundFramebufferDrawStillAppliesVeilDefaultUniformsBeforeSamplers() {
        List<String> calls = new ArrayList<>();

        SimulatedEndSeaCompat.applyEndSeaBoundFramebufferShader(
            () -> calls.add("bind"),
            () -> calls.add("defaultUniforms"),
            samplerStart -> calls.add("samplers:" + samplerStart));

        assertEquals(List.of("bind", "defaultUniforms", "samplers:0"), calls);
    }
}
