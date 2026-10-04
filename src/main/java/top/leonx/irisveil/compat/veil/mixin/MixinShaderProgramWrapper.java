package top.leonx.irisveil.compat.veil.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL30C;

import top.leonx.irisveil.compat.simulated.SimulatedDiagramCompat;

/** Veil's Iris wrapper must not redirect offscreen diagram draws into colortex0. */
@Pseudo
@Mixin(targets = "foundry.veil.impl.client.render.shader.program.ShaderProgramImpl$Wrapper", remap = false)
public abstract class MixinShaderProgramWrapper {
    @WrapMethod(method = {"apply", "clear"})
    private void irisveil$preserveDiagramFramebuffer(Operation<Void> original) {
        if (!SimulatedDiagramCompat.isRendering()) {
            original.call();
            return;
        }

        int drawFramebuffer = GL30C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int readFramebuffer = GL30C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        try {
            original.call();
        } finally {
            GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFramebuffer);
        }
    }
}
