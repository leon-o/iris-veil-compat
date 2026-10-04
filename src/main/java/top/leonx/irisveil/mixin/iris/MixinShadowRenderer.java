package top.leonx.irisveil.mixin.iris;

import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.uniforms.CameraUniforms;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.fml.ModList;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.leonx.irisveil.compat.aeronautics.LevititeShadowCompat;
import top.leonx.irisveil.compat.iris.ShadowBlockEntityList;
import top.leonx.irisveil.compat.sable.SableShadowCompat;
import top.leonx.irisveil.compat.veil.RenderStateManager;

/**
 * Tracks the Iris shadow render pass so that Veil shaders can be compiled
 * with the correct Iris program ({@code ProgramId.Shadow}) instead of the
 * default block program.
 *
 * <p>Veil draw calls can still run from render-stage hooks while Iris is
 * rendering the shadow map. The full {@code renderShadows()} method must be
 * treated as a shadow pass even when the shaderpack disables block-entity
 * shadow iteration, otherwise those draws keep using gbuffer shaders.
 */
@Mixin(value = ShadowRenderer.class)
public abstract class MixinShadowRenderer {

    @Unique
    private boolean irisveil$sableShadowBlockEntitiesAttempted;

    @Final
    @Shadow
    private boolean shouldRenderBlockEntities;

    @Final
    @Shadow
    private boolean shouldRenderTerrain;

    @Inject(method = "renderShadows", at = @At("HEAD"))
    private void irisveil$onShadowPassStart(
            LevelRendererAccessor levelRendererAccessor,
            Camera camera,
            CallbackInfo ci) {
        irisveil$sableShadowBlockEntitiesAttempted = false;
        RenderStateManager.beginShadowPass(shouldRenderBlockEntities);
    }

    @WrapOperation(method = "renderShadows", at = @At(value = "INVOKE",
        target = "Lnet/irisshaders/iris/mixin/LevelRendererAccessor;invokeRenderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"))
    private void irisveil$renderSingleBlocksAfterShadowLayer(
            LevelRendererAccessor renderer, RenderType layer,
            double cameraX, double cameraY, double cameraZ,
            Matrix4f modelView, Matrix4f projection, Operation<Void> original) {
        original.call(renderer, layer, cameraX, cameraY, cameraZ, modelView, projection);
        // Run only after a layer Iris actually requested. This preserves its
        // terrain/translucent enable flags and the opaque-depth copy boundary.
        irisveil$flushSingleBlockShadowLayer(renderer, modelView, projection, cameraX, cameraY, cameraZ);
    }

    @Inject(
        method = "renderShadows",
        at = @At(
            value = "INVOKE",
            target = "Lnet/irisshaders/iris/shadows/ShadowRenderingState;renderBlockEntities(Lnet/irisshaders/iris/shadows/ShadowRenderer;Lnet/minecraft/client/renderer/RenderBuffers;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/Camera;DDDFZZ)I",
            shift = At.Shift.BEFORE,
            remap = false),
        remap = false)
    private void irisveil$includeCurrentShadowBlockEntities(
            LevelRendererAccessor levelRendererAccessor,
            Camera camera,
            CallbackInfo ci) {
        if (!ShadowRenderer.ACTIVE || ShadowRenderer.visibleBlockEntities == null) {
            return;
        }
        // Iris gathers vanilla visibleSections, which Sodium does not populate.
        // NeoForge's public iterator delegates to Sodium's current render lists;
        // Iris selects its shadow lists while ACTIVE, after shadow setupRender.
        // Keep the original render call (including lightsOnly/distance checks).
        ShadowBlockEntityList.appendMissing(ShadowRenderer.visibleBlockEntities,
            ((LevelRenderer) levelRendererAccessor)::iterateVisibleBlockEntities);
    }

    @Inject(
        method = "renderShadows",
        at = @At(
            value = "INVOKE",
            target = "Lnet/irisshaders/batchedentityrendering/impl/FullyBufferedMultiBufferSource;readyUp()V",
            shift = At.Shift.BEFORE),
        require = 0)
    private void irisveil$renderSableShadowBlockEntitiesBeforeReadyUp(
            LevelRendererAccessor levelRendererAccessor,
            Camera camera,
            CallbackInfo ci) {
        irisveil$renderSableShadowBlockEntities(levelRendererAccessor);
    }

    @Inject(
        method = "renderShadows",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V",
            shift = At.Shift.BEFORE))
    private void irisveil$renderSableShadowBlockEntitiesBeforeEndBatch(
            LevelRendererAccessor levelRendererAccessor,
            Camera camera,
            CallbackInfo ci) {
        irisveil$renderSableShadowBlockEntities(levelRendererAccessor);
    }

    @Inject(
        method = "renderShadows",
        at = @At(
            value = "INVOKE",
            target = "Lnet/irisshaders/iris/shadows/ShadowRenderer;copyPreTranslucentDepth(Lnet/irisshaders/iris/mixin/LevelRendererAccessor;)V",
            shift = At.Shift.BEFORE))
    private void irisveil$renderLevititeShadowTerrain(
            LevelRendererAccessor levelRendererAccessor,
            Camera camera,
            CallbackInfo ci) {
        if (!shouldRenderTerrain) {
            return;
        }
        // Iris dispatches ordinary terrain layers, but not Aero's
        // AFTER_BLOCK_ENTITIES stage. Reuse its current shadow culling list,
        // matrices and camera origin before opaque shadow depth is copied.
        Vector3d cameraPosition = CameraUniforms.getUnshiftedCameraPosition();
        LevititeShadowCompat.renderBaseLayer(
            levelRendererAccessor, ShadowRenderer.MODELVIEW, ShadowRenderer.PROJECTION, ShadowRenderer.FRUSTUM,
            cameraPosition.x(), cameraPosition.y(), cameraPosition.z());
        irisveil$flushSingleBlockShadowLayer(levelRendererAccessor, ShadowRenderer.MODELVIEW, ShadowRenderer.PROJECTION,
            cameraPosition.x(), cameraPosition.y(), cameraPosition.z());
    }

    @Inject(method = "renderShadows", at = @At("RETURN"))
    private void irisveil$onShadowPassEnd(
            LevelRendererAccessor levelRendererAccessor,
            Camera camera,
            CallbackInfo ci) {
        RenderStateManager.endShadowPass();
    }

    @Unique
    private static void irisveil$flushSingleBlockShadowLayer(
            LevelRendererAccessor renderer, Matrix4f modelView, Matrix4f projection,
            double cameraX, double cameraY, double cameraZ) {
        if (!ModList.get().isLoaded("sable")) {
            return;
        }
        int drawFramebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int readFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        boolean cull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
        try {
            SableShadowCompat.renderSubLevelSingleBlocks(renderer.getLevel(), modelView, projection,
                cameraX, cameraY, cameraZ, CapturedRenderingState.INSTANCE.getTickDelta());
        } finally {
            // Native RenderType.clear/ShaderInstance.clear can restore main.
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFramebuffer);
            if (cull) {
                RenderSystem.enableCull();
            } else {
                RenderSystem.disableCull();
            }
        }
    }

    @Unique
    private void irisveil$renderSableShadowBlockEntities(LevelRendererAccessor levelRendererAccessor) {
        if (irisveil$sableShadowBlockEntitiesAttempted || !shouldRenderBlockEntities) {
            return;
        }
        irisveil$sableShadowBlockEntitiesAttempted = true;

        Vector3d cameraPosition = CameraUniforms.getUnshiftedCameraPosition();
        SableShadowCompat.renderSubLevelBlockEntities(
            levelRendererAccessor.getLevel(),
            levelRendererAccessor.getRenderBuffers(),
            Minecraft.getInstance().getBlockEntityRenderDispatcher(),
            ShadowRenderer.MODELVIEW,
            cameraPosition.x(),
            cameraPosition.y(),
            cameraPosition.z(),
            CapturedRenderingState.INSTANCE.getTickDelta());
    }
}
