package top.leonx.irisveil.compat.simulated;

import top.leonx.irisveil.compat.veil.VeilCompatRegistry;

/** Keeps the complete Simulated diagram pass out of the world shader pipeline. */
public final class SimulatedDiagramCompat {
    private static int renderDepth;

    private SimulatedDiagramCompat() {
    }

    public static void registerCompat() {
        VeilCompatRegistry.registerExternalRenderState("simulated:diagram", SimulatedDiagramCompat::isRendering);
    }

    public static boolean isRendering() {
        return renderDepth > 0;
    }

    /** Includes the entity draws after Simulated resets RENDERING_SIMPLE. */
    public static void render(Runnable draw) {
        renderDepth++;
        try {
            draw.run();
        } finally {
            renderDepth--;
        }
    }
}
