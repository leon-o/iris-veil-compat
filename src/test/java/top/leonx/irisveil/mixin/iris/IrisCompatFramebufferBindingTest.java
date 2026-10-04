package top.leonx.irisveil.mixin.iris;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;

import top.leonx.irisveil.compat.veil.CompatFramebufferTargets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class IrisCompatFramebufferBindingTest {
    @Test
    void framebufferAttachmentsMatchTheTexturesReadByIrisSamplers() {
        int[] targets = {0, 1, 3};
        for (Set<Integer> samplerFlips : Set.of(Set.<Integer>of(), Set.of(0), Set.of(1, 3), Set.of(0, 1, 3, 7))) {
            Set<Integer> framebufferFlips = CompatFramebufferTargets.framebufferFlipsForReadSide(samplerFlips, targets);
            for (int target : targets) {
                // Actual Iris 1.8.14 sampler and framebuffer attachment contracts.
                String sampledTexture = samplerFlips.contains(target) ? "alt" : "main";
                String attachedTexture = framebufferFlips.contains(target) ? "main" : "alt";
                assertEquals(sampledTexture, attachedTexture, "colortex" + target);
            }
            assertFalse(framebufferFlips.contains(7), "Unwritten attachments must not affect this framebuffer");
        }
    }

    @Test
    void simulatedEndSeaDoesNotUseFinalCompositeReplay() throws IOException {
        String simulatedCompat = Files.readString(Path.of(
            "src/main/java/top/leonx/irisveil/compat/simulated/SimulatedEndSeaCompat.java"));

        assertFalse(simulatedCompat.contains("WorldRenderPhase.FINAL_COMPOSITE"), simulatedCompat);
    }
}
