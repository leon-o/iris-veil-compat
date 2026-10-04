package top.leonx.irisveil.compat.aeronautics;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.neoforged.fml.ModList;

import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

import top.leonx.irisveil.IrisVeilCompat;

/** Draws the registered base terrain layer while Iris still owns its opaque shadow pass. */
public final class LevititeShadowCompat {
    private static boolean disabled;

    private LevititeShadowCompat() {
    }

    public static void renderBaseLayer(Object levelRenderer, Matrix4f modelView, Matrix4f projection, Frustum shadowFrustum,
                                      double cameraX, double cameraY, double cameraZ) {
        if (disabled || !ModList.get().isLoaded("veil")) {
            return;
        }
        try {
            VeilBridge.renderBaseLayer(levelRenderer, modelView, projection, shadowFrustum, cameraX, cameraY, cameraZ);
        } catch (NoClassDefFoundError e) {
            disabled = true;
            IrisVeilCompat.LOGGER.debug("Levitite shadow terrain bridge is unavailable", e);
        } catch (LinkageError | RuntimeException e) {
            disabled = true;
            IrisVeilCompat.LOGGER.warn("Disabling Levitite shadow terrain bridge after compatibility failure", e);
        }
    }

    /** All optional Veil linkage stays behind the loaded-mod check above. */
    private static final class VeilBridge {
        private static void renderBaseLayer(Object levelRenderer, Matrix4f modelView, Matrix4f projection, Frustum shadowFrustum,
                                            double cameraX, double cameraY, double cameraZ) {
            if (!(levelRenderer instanceof foundry.veil.ext.LevelRendererBlockLayerExtension renderer)) {
                return;
            }
            // Veil extends this public list with the exact registered RenderType
            // instances. Aero constructs these in Java, not the resource lookup.
            for (RenderType layer : RenderType.chunkBufferLayers()) {
                String name = foundry.veil.api.client.render.rendertype.VeilRenderType.getName(layer);
                if (!"aeronautics:levitite".equals(name)) {
                    continue;
                }

                int drawFramebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
                int readFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
                boolean cull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
                try (var ignored = LevititeRenderContext.enterLayer(name)) {
                    // The renderer uploads these shadow matrices before each
                    // section draw, then Sable applies its sublevel transform.
                    if (ModList.get().isLoaded("sable")) {
                        SableBridge.drawLayer(renderer, layer, modelView, projection, shadowFrustum,
                            cameraX, cameraY, cameraZ);
                    } else {
                        renderer.veil$drawBlockLayer(layer, cameraX, cameraY, cameraZ, modelView, projection);
                    }
                } finally {
                    // ShaderInstance.clear() may bind main; leave Iris' shadow
                    // depth-copy boundary with exactly the targets it owned.
                    GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
                    GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFramebuffer);
                    if (cull) {
                        RenderSystem.enableCull();
                    } else {
                        RenderSystem.disableCull();
                    }
                }
                return; // Ghost layers stay out of the opaque shadow map.
            }
        }
    }

    /** Sable's Fancy renderer caches culling separately from Iris/Sodium terrain. */
    private static final class SableBridge {
        private static void drawLayer(foundry.veil.ext.LevelRendererBlockLayerExtension renderer,
                                      RenderType layer, Matrix4f modelView, Matrix4f projection, Frustum shadowFrustum,
                                      double cameraX, double cameraY, double cameraZ) {
            Minecraft minecraft = Minecraft.getInstance();
            var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(minecraft.level);
            if (container == null || container.getAllSubLevels().isEmpty()) {
                renderer.veil$drawBlockLayer(layer, cameraX, cameraY, cameraZ, modelView, projection);
                return;
            }
            var dispatcher = dev.ryanhcode.sable.sublevel.render.dispatcher.SubLevelRenderDispatcher.get();
            var sublevels = container.getAllSubLevels();
            var playerFrustum = foundry.veil.api.client.render.VeilRenderBridge.create(minecraft.levelRenderer.getFrustum());
            var playerPosition = minecraft.gameRenderer.getMainCamera().getPosition();
            boolean spectator = minecraft.player != null && minecraft.player.isSpectator();
            boolean smartCull = minecraft.smartCull;
            try {
                // Match Iris: camera-based occlusion cannot reject a surface
                // that the sun can see. Vanilla/ReachAround treat this as a no-op.
                minecraft.smartCull = false;
                dispatcher.updateCulling(sublevels, cameraX, cameraY, cameraZ,
                    foundry.veil.api.client.render.VeilRenderBridge.create(shadowFrustum), spectator);
                minecraft.smartCull = smartCull;
                renderer.veil$drawBlockLayer(layer, cameraX, cameraY, cameraZ, modelView, projection);
            } finally {
                minecraft.smartCull = smartCull;
                dispatcher.updateCulling(sublevels, playerPosition.x, playerPosition.y, playerPosition.z,
                    playerFrustum, spectator);
            }
        }
    }
}
