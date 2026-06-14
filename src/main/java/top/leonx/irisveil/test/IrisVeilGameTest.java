package top.leonx.irisveil.test;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import top.leonx.irisveil.IrisVeilCompat;
import top.leonx.irisveil.compat.simulated.SimulatedEndSeaCompat;
import top.leonx.irisveil.compat.veil.IrisVeilShaderCache;

public class IrisVeilGameTest {
    @GameTest(templateNamespace = "irisveil")
    public void modLoaded(GameTestHelper helper) {
        helper.succeedWhen(() -> {
            helper.assertTrue(IrisVeilCompat.MODID.equals("irisveil"), "MODID should be irisveil");
            helper.assertTrue(IrisVeilCompat.LOGGER != null, "LOGGER should not be null");
            helper.assertFalse(IrisVeilCompat.isShaderPackInUse(), "No Iris on game test server");
        });
    }

    @GameTest(templateNamespace = "irisveil")
    public void directEndSeaShaderIsNotReplaced(GameTestHelper helper) {
        helper.succeedWhen(() -> {
            helper.assertFalse(
                IrisVeilShaderCache.shouldReplaceShader(ResourceLocation.fromNamespaceAndPath("simulated", "end_sea")),
                "End Sea is a direct transparent world effect and should stay on the Veil shader");
            helper.assertTrue(
                IrisVeilShaderCache.shouldReplaceShader(ResourceLocation.fromNamespaceAndPath("simulated", "spring/spring")),
                "Regular Veil RenderType shaders should still be replaced");
            helper.assertFalse(
                IrisVeilShaderCache.isRenderingExternalShadowMap("missing.Clazz", "missingMethod"),
                "Missing optional integration classes should not be treated as active shadow maps");
        });
    }

    @GameTest(templateNamespace = "irisveil")
    public void optionalEndSeaShadowMapStateIsDetected(GameTestHelper helper) {
        helper.succeedWhen(() -> {
            helper.assertFalse(
                SimulatedEndSeaCompat.isRenderingEndSeaShadowMap("missing.Clazz", "missingMethod"),
                "Missing optional integration classes should not be treated as active shadow maps");
            helper.assertTrue(
                IrisVeilShaderCache.getExternalRenderStateGeneration() == 0,
                "Inactive optional external render state should use the base generation");

            FakeExternalShadowRenderer.rendering = true;
            helper.assertTrue(
                SimulatedEndSeaCompat.isRenderingEndSeaShadowMap(
                    "top.leonx.irisveil.test.IrisVeilGameTest$FakeExternalShadowRenderer",
                    "renderingShadowMap"),
                "Active optional external shadow maps should be detected");

            FakeExternalShadowRenderer.rendering = false;
            helper.assertFalse(
                SimulatedEndSeaCompat.isRenderingEndSeaShadowMap(
                    "top.leonx.irisveil.test.IrisVeilGameTest$FakeExternalShadowRenderer",
                    "renderingShadowMap"),
                "Inactive optional external shadow maps should not be treated as active");
        });
    }

    @GameTest(templateNamespace = "irisveil")
    public void endSeaFinalCompositeWritesToColortexZero(GameTestHelper helper) {
        helper.succeedWhen(() -> {
            int[] drawBuffers = SimulatedEndSeaCompat.finalCompositeDrawBuffers();
            helper.assertTrue(
                drawBuffers.length == 1 && drawBuffers[0] == 0,
                "End Sea final composite should write to shaderpack colortex0");

            drawBuffers[0] = 7;
            helper.assertTrue(
                SimulatedEndSeaCompat.finalCompositeDrawBuffers()[0] == 0,
                "End Sea draw buffer contract should not be mutable by callers");
        });
    }

    public static class FakeExternalShadowRenderer {
        private static boolean rendering;

        public static boolean renderingShadowMap() {
            return rendering;
        }
    }
}
