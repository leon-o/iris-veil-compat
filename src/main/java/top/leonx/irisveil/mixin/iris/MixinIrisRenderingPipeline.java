package top.leonx.irisveil.mixin.iris;

import com.google.common.collect.ImmutableSet;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.pipeline.FinalPassRenderer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.leonx.irisveil.accessors.IrisRenderingPipelineAccessor;
import top.leonx.irisveil.IrisVeilCompat;
import top.leonx.irisveil.accessors.FinalPassRendererAccessor;
import top.leonx.irisveil.compat.veil.CompatWorldRenderContext;
import top.leonx.irisveil.compat.veil.CompatFramebufferTargets;
import top.leonx.irisveil.compat.veil.IrisVeilShaderCache;
import top.leonx.irisveil.compat.veil.VeilCompatRegistry;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@Mixin(IrisRenderingPipeline.class)
public abstract class MixinIrisRenderingPipeline implements IrisRenderingPipelineAccessor {
    @Shadow(remap = false)
    @Final
    private RenderTargets renderTargets;

    @Shadow(remap = false)
    @Final
    private ImmutableSet<Integer> flippedAfterTranslucent;

    @Shadow(remap = false)
    @Final
    private FinalPassRenderer finalPassRenderer;

    @Unique
    private ProgramSet programSet;

    @Unique
    private Map<String, GlFramebuffer> irisveil$compatGbufferTargets;

    @Override
    public ProgramSet getProgramSet(){
        return programSet;
    }

    @Inject(method = "<init>",at = @At("TAIL"),remap = false)
    public void initSet(ProgramSet set, CallbackInfo callbackInfo){
        programSet = set;
        // Iris pipeline (re)created → shaderpack changed → invalidate Veil shader cache
        // Catch Throwable to handle NoClassDefFoundError when Veil is not loaded
        try {
            IrisVeilShaderCache.onShaderPackReload();
        } catch (Throwable e) {
            // Veil not present, silently ignore
        }
    }

    @Override
    public void irisveil$bindCompatGbufferFramebuffer(int[] drawBuffers) {
        if (irisveil$compatGbufferTargets == null) {
            irisveil$compatGbufferTargets = new HashMap<>();
        }

        int[] drawBuffersCopy = drawBuffers.clone();
        String key = Arrays.toString(drawBuffersCopy);
        GlFramebuffer target = irisveil$compatGbufferTargets.computeIfAbsent(
            key,
            ignored -> renderTargets.createColorFramebufferWithDepth(
                ImmutableSet.copyOf(CompatFramebufferTargets.framebufferFlipsForReadSide(
                    flippedAfterTranslucent, drawBuffersCopy)), drawBuffersCopy));
        target.bind();
    }

    @Inject(
        method = "finalizeLevelRendering",
        at = @At(
            value = "INVOKE",
            target = "Lnet/irisshaders/iris/pipeline/FinalPassRenderer;renderFinalPass()V",
            shift = At.Shift.BEFORE
        ),
        remap = false
    )
    private void irisveil$renderFinalCompositeWorldHooks(CallbackInfo ci) {
        if (!IrisVeilCompat.isShaderPackInUse()) {
            CompatWorldRenderContext.clear();
            return;
        }

        if (!CompatWorldRenderContext.hasContext()) {
            CompatWorldRenderContext.clear();
            return;
        }

        Object camera = CompatWorldRenderContext.camera();
        Object gameRenderer = CompatWorldRenderContext.gameRenderer();
        try {
            VeilCompatRegistry.renderWorldHooks(
                VeilCompatRegistry.WorldRenderPhase.FINAL_COMPOSITE,
                camera,
                gameRenderer,
                this::irisveil$bindFinalCompositeFramebuffer,
                () -> Minecraft.getInstance().getMainRenderTarget().bindWrite(false),
                callback -> irisveil$withCapturedGbufferMatrices(gameRenderer, callback));
        } finally {
            CompatWorldRenderContext.clear();
        }
    }

    @Unique
    private boolean irisveil$bindFinalCompositeFramebuffer(int[] drawBuffers) {
        if (drawBuffers.length != 1 || drawBuffers[0] != 0) {
            IrisVeilCompat.LOGGER.warn(
                "IrisVeilCompat: final composite world hooks only support drawBuffers [0], got {}",
                Arrays.toString(drawBuffers));
            return false;
        }

        if (!(finalPassRenderer instanceof FinalPassRendererAccessor accessor)) {
            return false;
        }

        accessor.irisveil$getBaselineFramebuffer().bind();
        return true;
    }

    @Unique
    private void irisveil$withCapturedGbufferMatrices(Object gameRenderer, Runnable callback) {
        if (!(gameRenderer instanceof GameRenderer renderer)) {
            callback.run();
            return;
        }

        RenderSystem.getModelViewStack().pushMatrix();
        RenderSystem.backupProjectionMatrix();
        try {
            RenderSystem.getModelViewStack().set(new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView()));
            RenderSystem.applyModelViewMatrix();
            renderer.resetProjectionMatrix(new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferProjection()));
            callback.run();
        } finally {
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
        }
    }

    @Invoker(remap = false)
    @Override
    public abstract ShaderInstance invokeCreateShader(String name, ProgramSource source, ProgramId programId, AlphaTest fallbackAlpha,
                                                    VertexFormat vertexFormat, FogMode fogMode,
                                                    boolean isIntensity, boolean isFullbright, boolean isGlint,
                                                    boolean isText, boolean isIE) throws IOException;

    @Invoker(remap = false)
    @Override
    public abstract ShaderInstance invokeCreateShadowShader(String name, ProgramSource source, ProgramId programId, AlphaTest fallbackAlpha,
                                                            VertexFormat vertexFormat, boolean isIntensity, boolean isFullbright,
                                                            boolean isText, boolean isIE) throws IOException;

}
