package top.leonx.irisveil.compat.veil.mixin;

import java.nio.ByteBuffer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

import top.leonx.irisveil.accessors.VeilShaderUniformAccessor;

@Pseudo
@Mixin(targets = "foundry.veil.impl.client.render.shader.uniform.ShaderUniformImpl", remap = false)
public abstract class MixinShaderUniformImpl implements VeilShaderUniformAccessor {
    @Override
    @Accessor("value")
    public abstract ByteBuffer irisveil$getCachedValue();
}
