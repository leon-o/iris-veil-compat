package top.leonx.irisveil.test;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import top.leonx.irisveil.IrisVeilCompat;

public class IrisVeilGameTest {
    @GameTest(templateNamespace = "irisveil")
    public void modLoaded(GameTestHelper helper) {
        helper.succeedWhen(() -> {
            helper.assertTrue(IrisVeilCompat.MODID.equals("irisveil"), "MODID should be irisveil");
            helper.assertTrue(IrisVeilCompat.LOGGER != null, "LOGGER should not be null");
            helper.assertFalse(IrisVeilCompat.isShaderPackInUse(), "No Iris on game test server");
        });
    }
}
