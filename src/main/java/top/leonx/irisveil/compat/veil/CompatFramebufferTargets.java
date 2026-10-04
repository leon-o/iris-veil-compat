package top.leonx.irisveil.compat.veil;

import java.util.HashSet;
import java.util.Set;

/** Converts Iris sampler flip flags into framebuffer attachment selections. */
public final class CompatFramebufferTargets {
    private CompatFramebufferTargets() {
    }

    public static Set<Integer> framebufferFlipsForReadSide(Set<Integer> samplerFlips, int[] drawBuffers) {
        Set<Integer> framebufferFlips = new HashSet<>();
        for (int target : drawBuffers) {
            // Samplers select alt when flipped; createColorFramebuffer selects
            // main when flipped. Invert only the attachments this FBO writes.
            if (!samplerFlips.contains(target)) {
                framebufferFlips.add(target);
            }
        }
        return Set.copyOf(framebufferFlips);
    }
}
