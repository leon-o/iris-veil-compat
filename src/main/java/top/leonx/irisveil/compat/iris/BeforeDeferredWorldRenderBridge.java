package top.leonx.irisveil.compat.iris;

import foundry.veil.api.client.render.VeilLevelPerspectiveRenderer;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pathways.HandRenderer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.irisshaders.iris.vertices.ImmediateState;

import top.leonx.irisveil.IrisVeilCompat;
import top.leonx.irisveil.accessors.IrisRenderingPipelineAccessor;
import top.leonx.irisveil.compat.veil.VeilCompatRegistry;

/** Runs registered world effects between solid hand rendering and Iris' deferred pass. */
public final class BeforeDeferredWorldRenderBridge {
    private static boolean unavailable;

    private BeforeDeferredWorldRenderBridge() {}

    public static void render(IrisRenderingPipeline pipeline, BeforeDeferredWorldRenderers.Frame frame) {
        if (unavailable || !frame.hasRenderers()) {
            return;
        }
        try {
            if (!pipeline.isBeforeTranslucent || !isMainWorldContext(pipeline)) {
                return;
            }
            WorldRenderingPhase previousPhase = pipeline.getPhase();
            try {
                pipeline.setPhase(WorldRenderingPhase.ENTITIES);
                frame.renderCallbacks();
            } finally {
                pipeline.setPhase(previousPhase);
            }
        } catch (RuntimeException | LinkageError e) {
            unavailable = true;
            IrisVeilCompat.LOGGER.warn(
                "IrisVeilCompat: before-deferred world rendering unavailable; retaining native events", e);
        }
    }

    public static boolean shouldSkipNativeEvent(String id) {
        if (unavailable) {
            return false;
        }
        try {
            var current = Iris.getPipelineManager().getPipelineNullable();
            return current instanceof IrisRenderingPipeline pipeline
                && current instanceof IrisRenderingPipelineAccessor accessor
                && !pipeline.isBeforeTranslucent && isMainWorldContext(pipeline)
                && accessor.irisveil$getBeforeDeferredFrame().wasRendered(id);
        } catch (RuntimeException | LinkageError e) {
            unavailable = true;
            IrisVeilCompat.LOGGER.warn(
                "IrisVeilCompat: before-deferred event tracking unavailable; retaining native events", e);
            return false;
        }
    }

    private static boolean isMainWorldContext(IrisRenderingPipeline pipeline) {
        return Iris.getPipelineManager().getPipelineNullable() == pipeline
            && pipeline.shouldOverrideShaders()
            && !ShadowRenderingState.areShadowsCurrentlyBeingRendered()
            && !HandRenderer.INSTANCE.isActive() && !ImmediateState.bypass
            && VeilCompatRegistry.getExternalRenderStateGeneration() == 0
            && !VeilLevelPerspectiveRenderer.isRenderingPerspective();
    }
}
