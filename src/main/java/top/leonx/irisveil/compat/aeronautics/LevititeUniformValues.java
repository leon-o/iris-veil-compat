package top.leonx.irisveil.compat.aeronautics;

import java.nio.ByteBuffer;

import org.jetbrains.annotations.Nullable;

import top.leonx.irisveil.accessors.VeilShaderUniformAccessor;

/** Reads the values Aeronautics already uploaded through Veil without a GL readback. */
public final class LevititeUniformValues {
    private LevititeUniformValues() {
    }

    /**
     * Returns Veil's live native-order buffer, or null when the uniform/mixin is
     * unavailable. Read with absolute getFloat/getInt offsets; do not mutate its
     * position, limit, order or contents. Reacquire for every draw because Veil
     * can free or replace the buffer when its native shader is recompiled.
     */
    @Nullable
    public static ByteBuffer getValue(Object shaderUniform) {
        return shaderUniform instanceof VeilShaderUniformAccessor accessor
            ? accessor.irisveil$getCachedValue() : null;
    }
}
