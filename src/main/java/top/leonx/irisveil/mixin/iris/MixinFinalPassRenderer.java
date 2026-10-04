package top.leonx.irisveil.mixin.iris;

import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.pipeline.FinalPassRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import top.leonx.irisveil.accessors.FinalPassRendererAccessor;

@Mixin(FinalPassRenderer.class)
public interface MixinFinalPassRenderer extends FinalPassRendererAccessor {
    @Accessor(value = "baseline", remap = false)
    @Override
    GlFramebuffer irisveil$getBaselineFramebuffer();
}
