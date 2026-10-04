package top.leonx.irisveil.compat.aeronautics;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

import top.leonx.irisveil.IrisVeilCompat;
import top.leonx.irisveil.compat.veil.RenderStateManager;
import top.leonx.irisveil.compat.veil.VeilCompatRegistry;

/**
 * Preserves Aeronautics' native vertex/control/evaluation/fragment program.
 * Its LDR color is composited after the shaderpack; pack lighting and bloom are
 * intentionally unavailable until a complete tessellation gbuffer bridge exists.
 * No Aeronautics or Sable classes are referenced, so either mod may be absent.
 */
public final class AeronauticsLevititeCompat {
    private static final boolean VEIL_PRESENT = AeronauticsLevititeCompat.class.getClassLoader()
        .getResource("foundry/veil/api/client/render/VeilLevelPerspectiveRenderer.class") != null;
    private static final ResourceLocation SHADER =
        ResourceLocation.fromNamespaceAndPath("aeronautics", "levitite/levitite");
    private static final LevititeRenderQueue QUEUE = new LevititeRenderQueue();
    private static TextureTarget depthSnapshot;
    private static boolean failureReported;
    private static boolean replayReported;
    private static boolean unavailableReported;

    private AeronauticsLevititeCompat() {
    }

    public static void registerCompat() {
        VeilCompatRegistry.excludeShaderReplacement(SHADER);
    }

    public static boolean defer(String layerName, Runnable draw) {
        return QUEUE.defer(layerName, IrisVeilCompat.isShaderPackInUse(),
            isExternalPass(), draw);
    }

    public static boolean isLevititeLayer(String name) {
        return LevititeRenderQueue.isLevititeLayer(name);
    }

    public static boolean isRendering() {
        return QUEUE.isRendering();
    }

    public static boolean isCollecting() {
        return QUEUE.isCollecting();
    }

    public static boolean isVeilPresent() {
        return VEIL_PRESENT;
    }

    public static boolean deferMesh(String name, Runnable draw, Runnable release) {
        return QUEUE.deferMesh(name, IrisVeilCompat.isShaderPackInUse(), isExternalPass(), draw, release);
    }

    public static boolean shouldSkipShadow(String name) {
        return isLevititeLayer(name) && IrisVeilCompat.isShaderPackInUse() && RenderStateManager.isRenderingShadow();
    }

    private static boolean isExternalPass() {
        return RenderStateManager.isRenderingShadow()
            || VeilCompatRegistry.getExternalRenderStateGeneration() != 0
            || !isVeilWorldPass();
    }

    private static boolean isVeilWorldPass() {
        if (!VEIL_PRESENT) {
            return false;
        }
        try {
            return !VeilBridge.isRenderingPerspective();
        } catch (LinkageError e) {
            // Veil is optional. Do not start collecting buffers without it.
            if (!unavailableReported) {
                IrisVeilCompat.LOGGER.debug("IrisVeilCompat: native levitite bridge unavailable: {}", e.getMessage());
                unavailableReported = true;
            }
            return false;
        }
    }

    public static void beginFrame() {
        if (isVeilWorldPass()) {
            QUEUE.beginFrame();
        }
    }

    /** Called after Veil has populated its definition samplers for this apply. */
    public static void applyDepthSampler(Object program) {
        if (!isRendering() || depthSnapshot == null) {
            return;
        }
        VeilBridge.applyDepthSampler(program, depthSnapshot.getDepthTextureId());
    }

    public static void renderDeferred() {
        // A Veil perspective can invoke its own Iris finalize while a main frame
        // is queued. Leave ownership and collection untouched in nested views.
        if (isExternalPass()) {
            return;
        }
        QUEUE.finishCollection();
        if (!QUEUE.hasDraws()) {
            return;
        }
        if (!IrisVeilCompat.isShaderPackInUse()) {
            QUEUE.clear();
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget main = minecraft.getMainRenderTarget();
        try (NativeRenderState state = new NativeRenderState()) {
            ensureDepthSnapshot(main);
            RenderSystem.getModelViewStack().pushMatrix();
            RenderSystem.backupProjectionMatrix();
            try {
                RenderSystem.getModelViewStack().set(new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView()));
                RenderSystem.applyModelViewMatrix();
                minecraft.gameRenderer.resetProjectionMatrix(new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferProjection()));
                RenderSystem.colorMask(true, true, true, true);
                RenderSystem.depthFunc(GL11C.GL_LEQUAL);
                RenderSystem.blendEquation(GL14C.GL_FUNC_ADD);
                QUEUE.replay(() -> {
                    // Refresh between base and ghost layers: ghosts must see base depth,
                    // while sampling a texture distinct from the active depth attachment.
                    depthSnapshot.copyDepthFrom(main);
                    main.bindWrite(true);
                    RenderSystem.enableDepthTest();
                    RenderSystem.depthMask(true);
                });
                if (!replayReported) {
                    IrisVeilCompat.LOGGER.info("IrisVeilCompat: native levitite tessellation replay active after shaderpack final pass with copied world depth");
                    replayReported = true;
                }
            } finally {
                RenderSystem.getModelViewStack().popMatrix();
                RenderSystem.applyModelViewMatrix();
                RenderSystem.restoreProjectionMatrix();
            }
        } catch (RuntimeException | LinkageError e) {
            QUEUE.clear();
            if (!failureReported) {
                IrisVeilCompat.LOGGER.error("IrisVeilCompat: native levitite replay failed", e);
                failureReported = true;
            }
        }
    }

    private static void ensureDepthSnapshot(RenderTarget main) {
        if (depthSnapshot != null && (depthSnapshot.width != main.width || depthSnapshot.height != main.height
                || depthSnapshot.isStencilEnabled() != main.isStencilEnabled())) {
            depthSnapshot.destroyBuffers();
            depthSnapshot = null;
        }
        if (depthSnapshot == null) {
            depthSnapshot = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
            if (main.isStencilEnabled()) {
                depthSnapshot.enableStencil();
            }
        }
    }

    public static void destroy() {
        QUEUE.clear();
        if (depthSnapshot != null) {
            depthSnapshot.destroyBuffers();
            depthSnapshot = null;
        }
        failureReported = false;
        replayReported = false;
    }

    /** Loaded only on a Veil render path; the public compat shell has no Veil types. */
    private static final class VeilBridge {
        private static boolean isRenderingPerspective() {
            return foundry.veil.api.client.render.VeilLevelPerspectiveRenderer.isRenderingPerspective();
        }

        private static void applyDepthSampler(Object candidate, int depthTexture) {
            if (candidate instanceof foundry.veil.api.client.render.shader.program.ShaderProgram program
                    && SHADER.equals(program.getName())) {
                program.setTexture("DiffuseDepthSampler", GL11C.GL_TEXTURE_2D, depthTexture);
                // A non-null context overwrites our copy with minecraft:main:depth.
                program.bindSamplers(null, 0);
            }
        }
    }

    /** Restore GL state through Minecraft's cache-aware setters. */
    private static final class NativeRenderState implements AutoCloseable {
        private final int drawFramebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        private final int readFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        private final int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        private final int activeTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        private final int depthFunction = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);
        private final boolean depthTest = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
        private final boolean depthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
        private final boolean blend = GL11C.glIsEnabled(GL11C.GL_BLEND);
        private final boolean cull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
        private final int srcRgb = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB);
        private final int dstRgb = GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB);
        private final int srcAlpha = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_ALPHA);
        private final int dstAlpha = GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA);
        private final int blendEquationRgb = GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_RGB);
        private final int blendEquationAlpha = GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_ALPHA);
        private final int[] viewport = new int[4];
        private final int[] colorMask = new int[4];
        private final ShaderInstance shader = RenderSystem.getShader();

        private NativeRenderState() {
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
            GL11C.glGetIntegerv(GL11C.GL_COLOR_WRITEMASK, colorMask);
        }

        @Override
        public void close() {
            RenderSystem.depthMask(depthMask);
            RenderSystem.depthFunc(depthFunction);
            if (depthTest) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
            GlStateManager._blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            GL20C.glBlendEquationSeparate(blendEquationRgb, blendEquationAlpha);
            RenderSystem.colorMask(colorMask[0] != 0, colorMask[1] != 0, colorMask[2] != 0, colorMask[3] != 0);
            GlStateManager._activeTexture(activeTexture);
            GlStateManager._glUseProgram(program);
            RenderSystem.setShader(() -> shader);
            GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFramebuffer);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        }
    }
}
