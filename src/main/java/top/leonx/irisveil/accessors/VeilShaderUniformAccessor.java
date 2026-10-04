package top.leonx.irisveil.accessors;

import java.nio.ByteBuffer;

import org.jetbrains.annotations.Nullable;

/** Access to Veil's existing CPU uniform cache without linking optional Veil classes. */
public interface VeilShaderUniformAccessor {
    @Nullable
    ByteBuffer irisveil$getCachedValue();
}
