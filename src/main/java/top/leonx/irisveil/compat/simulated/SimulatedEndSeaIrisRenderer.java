package top.leonx.irisveil.compat.simulated;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import top.leonx.irisveil.IrisVeilCompat;
import top.leonx.irisveil.accessors.IrisRenderingPipelineAccessor;

public class SimulatedEndSeaIrisRenderer {
    private static int debugLogs;
    private static long attempts;
    private static long failures;
    private static long missingPipelineFrames;

    private SimulatedEndSeaIrisRenderer() {
    }

    public static boolean renderIntoIrisFramebuffer(Camera camera, GameRenderer gameRenderer) {
        attempts++;

        if (!bindEndSeaTarget()) {
            failures++;
            logResult(false, false);
            return false;
        }

        boolean rendered = false;
        try {
            rendered = SimulatedEndSeaCompat.render(camera, gameRenderer);
            if (!rendered) {
                failures++;
            }
            return rendered;
        } finally {
            Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
            logResult(true, rendered);
        }
    }

    private static boolean bindEndSeaTarget() {
        try {
            WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
            if (pipeline instanceof IrisRenderingPipelineAccessor accessor) {
                accessor.irisveil$bindEndSeaFinalCompositeTarget();
                return true;
            }

            missingPipelineFrames++;
            return false;
        } catch (RuntimeException | LinkageError e) {
            IrisVeilCompat.LOGGER.warn("IrisVeilCompat: failed to bind Iris framebuffer for Simulated End Sea", e);
            return false;
        }
    }

    private static void logResult(boolean framebufferBound, boolean rendered) {
        if (debugLogs >= 20) {
            return;
        }

        IrisVeilCompat.LOGGER.debug(
            "IrisVeilCompat: Simulated End Sea Iris framebuffer fallback attempts={} failures={} "
                + "missingPipelineFrames={} framebufferBound={} rendered={}",
            attempts,
            failures,
            missingPipelineFrames,
            framebufferBound,
            rendered);
        debugLogs++;
    }
}
