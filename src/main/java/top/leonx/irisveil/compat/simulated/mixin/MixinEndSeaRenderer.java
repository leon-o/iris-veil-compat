package top.leonx.irisveil.compat.simulated.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.lwjgl.opengl.GL30C;

import top.leonx.irisveil.IrisVeilCompat;
import top.leonx.irisveil.accessors.IrisRenderingPipelineAccessor;
import top.leonx.irisveil.compat.simulated.SimulatedEndSeaCompat;
import top.leonx.irisveil.compat.veil.NativeShaderProjection;

@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.end_sea.EndSeaRenderer", remap = false)
public class MixinEndSeaRenderer {
    @WrapOperation(
        method = "renderLayers",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/BufferUploader;drawWithShader(Lcom/mojang/blaze3d/vertex/MeshData;)V"
        ),
        require = 0
    )
    private static void irisveil$drawEndSeaIntoBoundFramebuffer(MeshData meshData, Operation<Void> original) {
        var pipeline = Iris.getPipelineManager().getPipelineNullable();
        if (!SimulatedEndSeaCompat.shouldDrawEndSeaIntoBoundFramebuffer(
            IrisVeilCompat.isShaderPackInUse(),
            pipeline instanceof IrisRenderingPipelineAccessor)) {
            original.call(meshData);
            return;
        }

        ShaderProgram shader = VeilRenderSystem.getShader();
        if (shader == null) {
            original.call(meshData);
            return;
        }

        int drawFramebuffer = GL30C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int readFramebuffer = GL30C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        var accessor = (IrisRenderingPipelineAccessor) pipeline;
        SimulatedEndSeaCompat.drawIntoWorldColor(
            // The next composite reads this ping-pong half. Veil's wrapper binds
            // the main half unconditionally, which that composite can overwrite.
            () -> accessor.irisveil$bindCompatGbufferFramebuffer(new int[] {0}),
            () -> {
                SimulatedEndSeaCompat.applyEndSeaBoundFramebufferShader(
                    shader::bind,
                    () -> shader.setDefaultUniforms(
                        VertexFormat.Mode.QUADS,
                        RenderSystem.getModelViewMatrix(),
                        NativeShaderProjection.adjustProjection(
                            (IrisRenderingPipeline) pipeline, accessor.getProgramSet(),
                            RenderSystem.getProjectionMatrix())),
                    shader::bindSamplers);
                BufferUploader.draw(meshData);
            },
            () -> {
                GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
                GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFramebuffer);
            });
    }
}
