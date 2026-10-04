package top.leonx.irisveil.compat.veilirislights;

import java.util.Objects;

import net.minecraft.resources.ResourceLocation;

/** Keeps VIL's independent light pass while avoiding a second geometry bridge. */
public final class VeilIrisLightsCompat {
    private VeilIrisLightsCompat() {
    }

    public static boolean shouldSuppressGeometry(
            ResourceLocation shaderPath, boolean shadersEnabled, boolean bridgePipelineAvailable) {
        Objects.requireNonNull(shaderPath, "shaderPath");
        return shadersEnabled && bridgePipelineAvailable
            && !("veil".equals(shaderPath.getNamespace()) && shaderPath.getPath().startsWith("light/"));
    }
}
