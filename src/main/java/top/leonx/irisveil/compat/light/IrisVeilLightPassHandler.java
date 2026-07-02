package top.leonx.irisveil.compat.light;

import foundry.veil.Veil;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import top.leonx.irisveil.IrisVeilCompat;


// Runs the light pass when Iris is installed but no shaderpack is active.

@EventBusSubscriber(modid = IrisVeilCompat.MODID, value = Dist.CLIENT)
public final class IrisVeilLightPassHandler {

    private IrisVeilLightPassHandler() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER || !Veil.IRIS) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        IrisVeilLightPass.renderVanilla(event.getProjectionMatrix(), event.getModelViewMatrix(),
                camera.x, camera.y, camera.z);
    }
}
