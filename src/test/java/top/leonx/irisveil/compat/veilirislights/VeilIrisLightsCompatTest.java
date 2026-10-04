package top.leonx.irisveil.compat.veilirislights;

import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VeilIrisLightsCompatTest {
    @Test
    void activeBridgeOwnsGenericAndNativeSpecializedGeometry() {
        for (ResourceLocation shader : new ResourceLocation[] {
                shader("simulated", "rope/rope"),
                shader("aeronautics", "levitite/levitite"),
                shader("simulated", "end_sea")}) {
            assertTrue(VeilIrisLightsCompat.shouldSuppressGeometry(shader, true, true));
        }
    }

    @Test
    void disabledShadersOrUnavailableBridgeLeaveVilUntouched() {
        ResourceLocation rope = shader("simulated", "rope/rope");
        assertFalse(VeilIrisLightsCompat.shouldSuppressGeometry(rope, false, true));
        assertFalse(VeilIrisLightsCompat.shouldSuppressGeometry(rope, true, false));
        assertFalse(VeilIrisLightsCompat.shouldSuppressGeometry(rope, false, false));
    }

    @Test
    void reservesOnlyVeilsLightNamespaceAndPathPrefix() {
        assertFalse(VeilIrisLightsCompat.shouldSuppressGeometry(shader("veil", "light/point"), true, true));
        assertFalse(VeilIrisLightsCompat.shouldSuppressGeometry(shader("veil", "light/area"), true, true));
        assertTrue(VeilIrisLightsCompat.shouldSuppressGeometry(shader("other", "light/point"), true, true));
        assertTrue(VeilIrisLightsCompat.shouldSuppressGeometry(shader("veil", "lightning"), true, true));
    }

    private static ResourceLocation shader(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }
}
