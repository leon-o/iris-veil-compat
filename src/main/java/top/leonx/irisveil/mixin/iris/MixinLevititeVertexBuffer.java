package top.leonx.irisveil.mixin.iris;

import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL40C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import top.leonx.irisveil.compat.aeronautics.LevititeGbufferBridge;

/** Submits Levitite's four control points without the quad's triangle index expansion. */
@Mixin(value = VertexBuffer.class, priority = 1100)
public abstract class MixinLevititeVertexBuffer {
    @Shadow private VertexFormat.Mode mode;
    @Shadow private int indexCount;

    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    private void irisveil$drawLevititePatches(CallbackInfo ci) {
        if (LevititeGbufferBridge.shouldSuppressNativeDraw()) {
            // Keep Aeronautics' ordinary SOLID fallback if composition failed.
            // Native fragColor alone cannot populate a shaderpack gbuffer.
            ci.cancel();
            return;
        }
        if (mode != VertexFormat.Mode.QUADS || !LevititeGbufferBridge.isActiveShader()) {
            return;
        }

        // Sable changes physics uniforms, matrices and ChunkOffset after apply().
        // This boundary covers both uploaded BufferBuilder meshes and the vanilla
        // section buffers used by Sable's Sodium render dispatcher.
        if (!LevititeGbufferBridge.beforeDraw()) {
            ci.cancel();
            return;
        }
        int previousPatchVertices = GL11C.glGetInteger(GL40C.GL_PATCH_VERTICES);
        try {
            GL40C.glPatchParameteri(GL40C.GL_PATCH_VERTICES, 4);
            GL11C.glDrawArrays(GL40C.GL_PATCHES, 0, indexCount / 6 * 4);
        } finally {
            GL40C.glPatchParameteri(GL40C.GL_PATCH_VERTICES, previousPatchVertices);
        }
        ci.cancel();
    }
}
