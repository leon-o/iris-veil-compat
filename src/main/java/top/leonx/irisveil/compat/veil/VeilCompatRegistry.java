package top.leonx.irisveil.compat.veil;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import net.minecraft.resources.ResourceLocation;

import top.leonx.irisveil.IrisVeilCompat;

/**
 * Internal registry for optional mod-specific compatibility hooks.
 */
public final class VeilCompatRegistry {
    private static final Map<String, BooleanSupplier> SHADER_REPLACEMENT_EXCLUSIONS =
        new ConcurrentHashMap<>();
    private static final Set<String> FAILED_EXCLUSIONS = ConcurrentHashMap.newKeySet();
    private static final Map<String, ExternalRenderState> EXTERNAL_RENDER_STATES_BY_ID =
        new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<ExternalRenderState> EXTERNAL_RENDER_STATES =
        new CopyOnWriteArrayList<>();

    private static int nextExternalRenderStateMask = 1;

    private VeilCompatRegistry() {
    }

    public static void excludeShaderReplacement(ResourceLocation shaderPath) {
        excludeShaderReplacement(shaderPath.toString());
    }

    static void excludeShaderReplacement(String shaderPath) {
        excludeShaderReplacement(shaderPath, () -> true);
    }

    /** Exclude a shader only while its optional replacement path is available. */
    public static void excludeShaderReplacement(ResourceLocation shaderPath, BooleanSupplier active) {
        excludeShaderReplacement(shaderPath.toString(), active);
    }

    static void excludeShaderReplacement(String shaderPath, BooleanSupplier active) {
        SHADER_REPLACEMENT_EXCLUSIONS.putIfAbsent(
            Objects.requireNonNull(shaderPath, "shaderPath"), Objects.requireNonNull(active, "active"));
    }

    public static boolean shouldReplaceShader(ResourceLocation shaderPath) {
        return shouldReplaceShader(shaderPath.toString());
    }

    static boolean shouldReplaceShader(String shaderPath) {
        BooleanSupplier excluded = SHADER_REPLACEMENT_EXCLUSIONS.get(shaderPath);
        if (excluded == null) {
            return true;
        }
        try {
            return !excluded.getAsBoolean();
        } catch (RuntimeException | LinkageError e) {
            if (FAILED_EXCLUSIONS.add(shaderPath)) {
                IrisVeilCompat.LOGGER.debug("IrisVeilCompat: optional shader exclusion '{}' is unavailable", shaderPath, e);
            }
            return true;
        }
    }

    public static synchronized int registerExternalRenderState(String id, BooleanSupplier active) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(active, "active");

        ExternalRenderState existing = EXTERNAL_RENDER_STATES_BY_ID.get(id);
        if (existing != null) {
            return existing.mask();
        }

        if (nextExternalRenderStateMask == 0) {
            throw new IllegalStateException("Too many Veil compat external render states");
        }

        int mask = nextExternalRenderStateMask;
        nextExternalRenderStateMask <<= 1;
        ExternalRenderState state = new ExternalRenderState(id, mask, active);
        EXTERNAL_RENDER_STATES_BY_ID.put(id, state);
        EXTERNAL_RENDER_STATES.add(state);
        return mask;
    }

    public static int getExternalRenderStateGeneration() {
        int generation = 0;
        for (ExternalRenderState state : EXTERNAL_RENDER_STATES) {
            if (state.isActive()) {
                generation |= state.mask();
            }
        }
        return generation;
    }

    private record ExternalRenderState(String id, int mask, BooleanSupplier active) {
        private boolean isActive() {
            try {
                return active.getAsBoolean();
            } catch (RuntimeException | LinkageError e) {
                IrisVeilCompat.LOGGER.debug(
                    "IrisVeilCompat: external render state '{}' is unavailable: {}",
                    id,
                    e.getMessage());
                return false;
            }
        }
    }
}
