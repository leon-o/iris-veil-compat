package top.leonx.irisveil.compat.simulated;

import java.util.Objects;
import java.util.function.IntConsumer;

import net.minecraft.resources.ResourceLocation;

import com.mojang.blaze3d.systems.RenderSystem;

import top.leonx.irisveil.compat.veil.VeilCompatRegistry;

public final class SimulatedEndSeaCompat {
    private static final ResourceLocation END_SEA_SHADER =
        ResourceLocation.fromNamespaceAndPath("simulated", "end_sea");
    private static final String END_SEA_SHADOW_RENDERER =
        "dev.simulated_team.simulated.content.end_sea.EndSeaShadowRenderer";
    private static volatile boolean registered;

    private SimulatedEndSeaCompat() {
    }

    public static synchronized void registerCompat() {
        if (registered) {
            return;
        }

        VeilCompatRegistry.excludeShaderReplacement(END_SEA_SHADER);
        VeilCompatRegistry.registerExternalRenderState(
            "simulated:end_sea_shadow",
            SimulatedEndSeaCompat::isRenderingEndSeaShadowMap);
        registered = true;
    }

    public static boolean isRenderingEndSeaShadowMap() {
        return isRenderingEndSeaShadowMap(END_SEA_SHADOW_RENDERER, "renderingShadowMap");
    }

    public static boolean isRenderingEndSeaShadowMap(String className, String methodName) {
        try {
            Class<?> rendererClass = Class.forName(className);
            Object value = rendererClass.getMethod(methodName).invoke(null);
            return value instanceof Boolean rendering && rendering;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    public static void prepareShadowMapRenderState() {
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
    }

    public static boolean shouldDrawEndSeaIntoBoundFramebuffer(boolean shaderPackInUse, boolean compatibleFramebufferAvailable) {
        return shaderPackInUse && compatibleFramebufferAvailable;
    }

    public static void drawIntoWorldColor(Runnable bindTarget, Runnable draw, Runnable restoreTarget) {
        Objects.requireNonNull(bindTarget, "bindTarget");
        Objects.requireNonNull(draw, "draw");
        Objects.requireNonNull(restoreTarget, "restoreTarget");
        try {
            bindTarget.run();
            draw.run();
        } finally {
            restoreTarget.run();
        }
    }

    public static void applyEndSeaBoundFramebufferShader(
        Runnable bindShader,
        Runnable applyDefaultUniforms,
        IntConsumer bindSamplers
    ) {
        Objects.requireNonNull(bindShader, "bindShader").run();
        Objects.requireNonNull(applyDefaultUniforms, "applyDefaultUniforms").run();
        Objects.requireNonNull(bindSamplers, "bindSamplers").accept(0);
    }
}
