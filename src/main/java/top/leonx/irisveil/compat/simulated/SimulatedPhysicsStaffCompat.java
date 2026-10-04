package top.leonx.irisveil.compat.simulated;

import java.util.function.Consumer;

import net.minecraft.client.Minecraft;

import com.mojang.blaze3d.vertex.PoseStack;

import top.leonx.irisveil.compat.iris.BeforeDeferredWorldRenderBridge;
import top.leonx.irisveil.compat.iris.BeforeDeferredWorldRenderers;

/** Receives the optional native handler from its mixin without loading Simulated classes. */
public final class SimulatedPhysicsStaffCompat {
    private static final String RENDERER_ID = "simulated:physics_staff";

    private SimulatedPhysicsStaffCompat() {}

    public static void register(Consumer<PoseStack> nativeRenderer) {
        BeforeDeferredWorldRenderers.INSTANCE.register(RENDERER_ID, () -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null || minecraft.options.hideGui) {
                return false;
            }
            // NeoForge's AFTER_TRANSLUCENT_BLOCKS event also supplies a fresh
            // identity PoseStack; native outlines already subtract the camera.
            nativeRenderer.accept(new PoseStack());
            return true;
        });
    }

    public static boolean shouldSkipNativeEvent() {
        return BeforeDeferredWorldRenderBridge.shouldSkipNativeEvent(RENDERER_ID);
    }
}
