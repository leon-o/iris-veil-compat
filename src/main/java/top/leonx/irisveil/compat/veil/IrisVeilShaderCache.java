package top.leonx.irisveil.compat.veil;

import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.shader.ShaderManager;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import top.leonx.irisveil.IrisVeilCompat;
import top.leonx.irisveil.compat.aeronautics.LevititeGbufferBridge;
import top.leonx.irisveil.compat.aeronautics.LevititeRenderContext;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Set;

/**
 * Thread-safe cache mapping Veil shader paths to Iris {@link ShaderInstance} objects.
 *
 * <p>Cache entries are keyed by an opaque shaderpack fingerprint that changes
 * whenever Iris reloads its shaders. Iris owns the shader instances; the cache
 * drops its references when those instances close.
 *
 * <p>Auto-classification is implicit: if the Iris {@link IrisVeilProgramLinker}
 * fails to create a program (e.g. the shader is translucent or the shaderpack
 * has no compatible block program), no entry is stored and the caller falls
 * back to the original Veil shader.
 */
public class IrisVeilShaderCache {

    private static final ConcurrentMap<String, ShaderInstance> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> FAILED_LEVITITE = ConcurrentHashMap.newKeySet();
    private static final ConcurrentMap<String, IrisVeilProgramLinker.Params> PARAM_CACHE = new ConcurrentHashMap<>();
    /** Cache of Veil-processed vertex shader source (populated by MixinDirectShaderCompiler). */
    private static final ConcurrentMap<ResourceLocation, String> PROCESSED_VERTEX_SOURCES = new ConcurrentHashMap<>();
    /** Cache of Veil-processed fragment shader source (populated by MixinDirectShaderCompiler). */
    private static final ConcurrentMap<ResourceLocation, String> PROCESSED_FRAGMENT_SOURCES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, String> PROCESSED_EXTRA_SOURCES = new ConcurrentHashMap<>();
    private static final IrisVeilProgramLinker LINKER = new IrisVeilProgramLinker();

    /** Monotonically increasing counter; incremented on shaderpack reload. */
    private static volatile int shaderPackGeneration;
    private static Object lastActivePipeline;

    /**
     * Returns the current shaderpack generation number.
     * Used by the ShaderProgramShard mixin to detect stale cache entries.
     */
    public static int getShaderPackGeneration() {
        Object current = net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable();
        if (current != lastActivePipeline) {
            lastActivePipeline = current;
            shaderPackGeneration++;
            PARAM_CACHE.clear();
            FAILED_LEVITITE.clear();
        }
        return shaderPackGeneration;
    }

    /**
     * Gets or creates an Iris {@link ShaderInstance} for the given Veil shader path.
     *
     * <p>When the Iris shadow pass is active (tracked by {@link RenderStateManager}),
     * automatically uses {@link ProgramId#Shadow} so that Veil shaders write to the
     * shadow map rather than the gbuffer.
     *
     * @param shaderPath the Veil shader resource location (e.g. {@code simulated:spring/spring})
     * @return a new or cached Iris ShaderInstance, or {@code null} if creation failed
     */
    public static ShaderInstance getOrCreate(ResourceLocation shaderPath) {
        if (getExternalRenderStateGeneration() != 0) {
            return null;
        }

        if (!shouldReplaceShader(shaderPath)) {
            return null;
        }

        // Check if the shader is even available (lazy Veil shader compilation)
        ShaderProgram veilProgram = getVeilProgram(shaderPath);
        if (veilProgram == null || !veilProgram.isValid()) {
            return null;
        }

        // During the shadow pass, override to ProgramId.Shadow so the shader
        // writes to the shadow map FBO rather than the gbuffer.
        if (RenderStateManager.isRenderingShadow()) {
            return getOrCreate(shaderPath, ProgramId.Shadow, false);
        }

        if (LevititeGbufferBridge.matches(shaderPath)) {
            return getOrCreate(shaderPath,
                LevititeRenderContext.isGhostPass() ? ProgramId.EntitiesTrans : ProgramId.Block, false);
        }

        String paramsKey = shaderPackFingerprint() + ":" + shaderPath + ":params";
        IrisVeilProgramLinker.Params params = PARAM_CACHE.get(paramsKey);
        if (params == null) {
            params = LINKER.determineParams(veilProgram);
            if (params == null) {
                return null;
            }
            PARAM_CACHE.put(paramsKey, params);
        }

        return getOrCreate(shaderPath, params.programId(), params.useDithering());
    }

    /**
     * Returns whether a Veil shader is safe to replace with an Iris gbuffer program.
     *
     * <p>Some Veil shaders are standalone transparent world effects rather than
     * gbuffer geometry. Replacing them with shaderpack block/entity programs
     * drops their custom samplers and blend semantics.
     */
    public static boolean shouldReplaceShader(ResourceLocation shaderPath) {
        return VeilCompatRegistry.shouldReplaceShader(shaderPath);
    }

    /**
     * Returns a small generation key for external render states that must bypass
     * the Iris shader replacement path without reusing a shard-local cached shader.
     */
    public static int getExternalRenderStateGeneration() {
        return VeilCompatRegistry.getExternalRenderStateGeneration();
    }

    /**
     * Gets or creates an Iris {@link ShaderInstance} for the given Veil shader path
     * with the specified program parameters.
     *
     * @param shaderPath    the Veil shader resource location
     * @param programId     the Iris program ID to use
     * @param useDithering  whether dithering is enabled
     * @return a new or cached Iris ShaderInstance, or {@code null} if creation failed
     */
    public static ShaderInstance getOrCreate(ResourceLocation shaderPath, ProgramId programId, boolean useDithering) {
        // Check if the shader is even available (lazy Veil shader compilation)
        ShaderProgram veilProgram = getVeilProgram(shaderPath);
        if (veilProgram == null || !veilProgram.isValid()) {
            return null;
        }

        String key = shaderPackFingerprint() + ":" + shaderPath + ":" + programId + ":" + (useDithering ? "dither" : "nodither");
        boolean levitite = LevititeGbufferBridge.matches(shaderPath);
        if (levitite) {
            LevititeGbufferBridge.noteNativeProgram(veilProgram::getProgram);
            if (FAILED_LEVITITE.contains(key)) return null;
        }
        return CACHE.computeIfAbsent(key, k -> {
            ShaderInstance created = LINKER.create(shaderPath, veilProgram, programId, useDithering);
            if (created != null) {
                IrisVeilCompat.LOGGER.debug("IrisVeilShaderCache: cached Iris shader for '{}'", shaderPath);
            }
            if (created == null && levitite) FAILED_LEVITITE.add(key);
            return created; // may be null → fallback to Veil
        });
    }

    /**
     * Invalidates all cached ShaderInstances. Called on Iris shaderpack reload.
     * Old entries are lazily replaced on next access because the fingerprint changes.
     */
    public static void onShaderPackReload() {
        shaderPackGeneration++;
        FAILED_LEVITITE.clear();
        PARAM_CACHE.clear();
        // Old entries will be superseded by new fingerprint on next getOrCreate().
        IrisVeilCompat.LOGGER.debug("IrisVeilShaderCache: shaderpack reloaded (gen {})", shaderPackGeneration);
    }

    /**
     * Drops every reference to a shader that Iris is closing without closing it again.
     * Identity comparison prevents a reused OpenGL program ID from evicting another instance.
     */
    public static void forgetClosedShader(ShaderInstance shader) {
        if (CACHE.entrySet().removeIf(entry -> entry.getValue() == shader)) {
            // Shards also retain a fast-path reference; make them look up a live shader next time.
            shaderPackGeneration++;
            PARAM_CACHE.clear();
            FAILED_LEVITITE.clear();
        }
    }

    /**
     * Stores a processed Veil vertex shader source, captured by {@code MixinDirectShaderCompiler}
     * during Veil's internal shader compilation. This is the fully-processed GLSL with
     * all {@code #include} directives resolved and all Veil preprocessor transformations applied.
     *
     * @param shaderId   the Veil vertex shader logical ID (e.g. {@code aeronautics:levitite/levitite})
     * @param sourceCode the fully-processed GLSL source code
     */
    public static void storeProcessedVertexSource(ResourceLocation shaderId, String sourceCode) {
        PROCESSED_VERTEX_SOURCES.put(shaderId, sourceCode);
    }

    /**
     * Retrieves a cached Veil-processed vertex shader source, if available.
     *
     * @param shaderId the Veil vertex shader logical ID
     * @return the processed GLSL source, or {@code null} if not yet compiled
     */
    public static String getProcessedSource(ResourceLocation shaderId) {
        return getProcessedVertexSource(shaderId);
    }

    public static String getProcessedVertexSource(ResourceLocation shaderId) {
        return PROCESSED_VERTEX_SOURCES.get(shaderId);
    }

    public static void storeProcessedFragmentSource(ResourceLocation shaderId, String sourceCode) {
        PROCESSED_FRAGMENT_SOURCES.put(shaderId, sourceCode);
    }

    public static String getProcessedFragmentSource(ResourceLocation shaderId) {
        return PROCESSED_FRAGMENT_SOURCES.get(shaderId);
    }

    public static void storeProcessedExtraSource(int type, ResourceLocation shaderId, String sourceCode) {
        PROCESSED_EXTRA_SOURCES.put(type + ":" + shaderId, sourceCode);
    }

    public static String getProcessedExtraSource(int type, ResourceLocation shaderId) {
        return PROCESSED_EXTRA_SOURCES.get(type + ":" + shaderId);
    }

    /**
     * Clears cache references and processed sources. Iris remains responsible for closing
     * its shader instances, which are also registered in the pipeline's loaded-shader set.
     */
    public static void clear() {
        CACHE.values().forEach(LevititeGbufferBridge::remove);
        CACHE.clear();
        FAILED_LEVITITE.clear();
        PARAM_CACHE.clear();
        PROCESSED_VERTEX_SOURCES.clear();
        PROCESSED_FRAGMENT_SOURCES.clear();
        PROCESSED_EXTRA_SOURCES.clear();
        shaderPackGeneration++;
    }

    private static ShaderProgram getVeilProgram(ResourceLocation shaderPath) {
        try {
            ShaderManager sm = VeilRenderSystem.renderer().getShaderManager();
            return sm.getShader(shaderPath);
        } catch (Exception e) {
            IrisVeilCompat.LOGGER.warn("IrisVeilShaderCache: cannot get Veil program for '{}'", shaderPath, e);
            return null;
        }
    }

    private static String shaderPackFingerprint() {
        // Combine generation counter with a hint of the active shaderpack
        return "gen" + getShaderPackGeneration();
    }
}
