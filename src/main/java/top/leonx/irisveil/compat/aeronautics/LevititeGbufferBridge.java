package top.leonx.irisveil.compat.aeronautics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.IntBuffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Function;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.system.MemoryStack;

import top.leonx.irisveil.IrisVeilCompat;
import top.leonx.irisveil.compat.veil.VeilCompatRegistry;

/** Owns the Levitite program assembled inside Iris' normal world/shadow pipeline. */
public final class LevititeGbufferBridge {
    private static final ResourceLocation SHADER = ResourceLocation.fromNamespaceAndPath("aeronautics", "levitite/levitite");
    private static final ResourceLocation NOISE = ResourceLocation.fromNamespaceAndPath("aeronautics", "textures/special/noise_composed.png");
    private static final ThreadLocal<Build> BUILD = new ThreadLocal<>();
    private static final Map<Integer, Binding> PROGRAMS = new HashMap<>();
    private static IntSupplier nativeProgram;
    private static final Set<String> PHYSICS_UNIFORMS = Set.of("time", "onSublevel", "layerIndex", "offset",
        "linearVelocity", "angularVelocity", "sublevelPosition", "currentOrientation", "gravityStrength",
        "materialTransitionSpeed", "materialMatrixSlow", "materialMatrixFast");

    private LevititeGbufferBridge() {}

    public static boolean matches(ResourceLocation path) {
        return SHADER.equals(path);
    }

    public static void noteNativeProgram(IntSupplier program) { nativeProgram = program; }

    public static boolean shouldSuppressNativeDraw() {
        return nativeProgram != null && IrisVeilCompat.isShaderPackInUse()
            && VeilCompatRegistry.getExternalRenderStateGeneration() == 0
            && nativeProgram.getAsInt() > 0
            && nativeProgram.getAsInt() == GL20C.glGetInteger(GL20C.GL_CURRENT_PROGRAM)
            && !foundry.veil.api.client.render.VeilLevelPerspectiveRenderer.isRenderingPerspective();
    }

    public static Build begin(String name, String vertex, String control, String evaluation) {
        if (BUILD.get() != null) throw new IllegalStateException("Nested Levitite program construction");
        Build build = new Build(name, vertex, control, evaluation);
        try {
            Path folder = Path.of("patched_shaders", "levitite");
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("native.vsh"), vertex);
            Files.writeString(folder.resolve("native.tcsh"), control);
            Files.writeString(folder.resolve("native.tesh"), evaluation);
        } catch (IOException e) {
            IrisVeilCompat.LOGGER.debug("Unable to dump native Levitite stages", e);
        }
        BUILD.set(build);
        return build;
    }

    public static Map<PatchShaderType, String> transform(Map<PatchShaderType, String> input) {
        Build build = BUILD.get();
        if (build == null) return input;
        try {
            Path folder = Path.of("patched_shaders", "levitite");
            Files.createDirectories(folder);
            Files.writeString(folder.resolve(build.name + ".input.vsh"), input.get(PatchShaderType.VERTEX));
            Files.writeString(folder.resolve(build.name + ".input.fsh"), input.get(PatchShaderType.FRAGMENT));
        } catch (IOException e) {
            IrisVeilCompat.LOGGER.debug("Unable to dump Iris Levitite input", e);
        }
        if (input.get(PatchShaderType.GEOMETRY) != null || input.get(PatchShaderType.TESS_CONTROL) != null
                || input.get(PatchShaderType.TESS_EVAL) != null) {
            throw new IllegalArgumentException("Levitite cannot compose a shaderpack's existing extra geometry stages");
        }
        var stages = LevititeTessellationTransformer.transform(input.get(PatchShaderType.VERTEX),
            build.vertex, build.control, build.evaluation);
        Map<PatchShaderType, String> result = new EnumMap<>(PatchShaderType.class);
        result.putAll(input); // Iris caches its map; never modify that shared instance.
        result.put(PatchShaderType.VERTEX, stages.vertex());
        result.put(PatchShaderType.TESS_CONTROL, stages.control());
        result.put(PatchShaderType.TESS_EVAL, stages.evaluation());
        build.transformed = true;
        try {
            Path folder = Path.of("patched_shaders", "levitite");
            Files.createDirectories(folder);
            for (var entry : result.entrySet()) {
                if (entry.getValue() != null) Files.writeString(folder.resolve(build.name + "." + entry.getKey().extension), entry.getValue());
            }
        } catch (IOException e) {
            IrisVeilCompat.LOGGER.debug("Unable to dump composed Levitite stages", e);
        }
        return result;
    }

    /** Register with Iris' allocator so Noise never steals a shaderpack texture unit. */
    public static void addSamplers(SamplerHolder holder) {
        if (BUILD.get() != null && holder.hasSampler("_lv_Noise")) {
            holder.addDynamicSampler(() -> Minecraft.getInstance().getTextureManager().getTexture(NOISE).getId(), "_lv_Noise");
        }
    }

    public static void register(ShaderInstance shader, ShaderInstance original, Function<String, ByteBuffer> nativeValues) {
        Build build = BUILD.get();
        if (build == null || !build.transformed) throw new IllegalStateException("Levitite tessellation transform did not run");
        PROGRAMS.put(shader.getId(), new Binding(shader, original, nativeValues));
    }

    /** Aeronautics writes through Veil's immediate uniform wrappers, preserving tick/physics semantics. */
    public static Uniform getNativeUniform(ShaderInstance shader, String name) {
        Binding binding = PROGRAMS.get(shader.getId());
        return binding != null && binding.shader == shader && PHYSICS_UNIFORMS.contains(name)
            ? binding.original.getUniform(name) : null;
    }

    public static boolean isActiveShader() {
        ShaderInstance shader = RenderSystem.getShader();
        if (shader == null) return false;
        Binding binding = PROGRAMS.get(shader.getId());
        return binding != null && binding.shader == shader
            && shader.getId() == GL20C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
    }

    /** Sable updates transforms and layerIndex after apply(), so synchronize at the actual draw. */
    public static boolean beforeDraw() {
        int program = GL20C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        Binding binding = PROGRAMS.get(program);
        if (binding == null || binding.failed) return false;
        try {
            if (!binding.inspected) binding.inspect();
            for (Copy copy : binding.copies) copy.upload(binding.nativeValues.apply(copy.name));
            if (!binding.reported) {
                binding.reported = true;
                IrisVeilCompat.LOGGER.info("IrisVeilCompat: Levitite gbuffer tessellation active for {} ({} native uniforms)",
                    binding.shader.getName(), binding.copies.size());
            }
            return true;
        } catch (RuntimeException | LinkageError e) {
            binding.failed = true;
            IrisVeilCompat.LOGGER.error("IrisVeilCompat: Levitite program {} disabled; using ordinary block fallback", binding.shader.getName(), e);
            return false;
        }
    }

    public static void remove(ShaderInstance shader) {
        Binding binding = PROGRAMS.get(shader.getId());
        if (binding != null && binding.shader == shader) PROGRAMS.remove(shader.getId());
    }

    public static void clear() { PROGRAMS.clear(); }

    public static final class Build implements AutoCloseable {
        private final String name;
        private final String vertex;
        private final String control;
        private final String evaluation;
        private boolean transformed;

        private Build(String name, String vertex, String control, String evaluation) {
            this.name = name;
            this.vertex = vertex;
            this.control = control;
            this.evaluation = evaluation;
        }

        @Override
        public void close() { BUILD.remove(); }
    }

    private static final class Binding {
        private final ShaderInstance shader;
        private final ShaderInstance original;
        private final Function<String, ByteBuffer> nativeValues;
        private final List<Copy> copies = new ArrayList<>();
        private boolean inspected;
        private boolean failed;
        private boolean reported;

        private Binding(ShaderInstance shader, ShaderInstance original, Function<String, ByteBuffer> nativeValues) {
            this.shader = shader;
            this.original = original;
            this.nativeValues = nativeValues;
        }

        private void inspect() {
            copies.clear();
            int target = shader.getId();
            int count = GL20C.glGetProgrami(target, GL20C.GL_ACTIVE_UNIFORMS);
            try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer size = stack.mallocInt(1);
            IntBuffer type = stack.mallocInt(1);
            for (int i = 0; i < count; i++) {
                String name = GL20C.glGetActiveUniform(target, i, 256, size, type);
                if (!name.startsWith("_lv_") || type.get(0) == GL20C.GL_SAMPLER_2D) continue;
                String originalName = name.substring(4);
                copies.add(new Copy(originalName, GL20C.glGetUniformLocation(target, name), type.get(0), size.get(0)));
            }
            }
            inspected = true;
        }
    }

    private static final class Copy {
        private final String name;
        private final int to;
        private final int type;
        private final float[] floats;
        private final int[] ints;

        private Copy(String name, int to, int type, int count) {
            this.name = name;
            this.to = to;
            this.type = type;
            this.ints = new int[count];
            this.floats = new float[count * switch (type) {
                case GL20C.GL_FLOAT_VEC2 -> 2;
                case GL20C.GL_FLOAT_VEC3 -> 3;
                case GL20C.GL_FLOAT_VEC4 -> 4;
                case GL20C.GL_FLOAT_MAT3 -> 9;
                case GL20C.GL_FLOAT_MAT4 -> 16;
                case GL20C.GL_FLOAT, GL20C.GL_INT, GL20C.GL_BOOL -> 1;
                default -> throw new IllegalArgumentException("Unsupported Levitite uniform type " + type);
            }];
        }

        private void upload(ByteBuffer value) {
            if (value == null || value.capacity() < floats.length * Float.BYTES) {
                throw new IllegalStateException("Missing native Levitite uniform value: " + name);
            }
            if (type == GL20C.GL_INT || type == GL20C.GL_BOOL) {
                for (int i = 0; i < ints.length; i++) ints[i] = value.getInt(i * Integer.BYTES);
                GL20C.glUniform1iv(to, ints);
                return;
            }
            for (int i = 0; i < floats.length; i++) floats[i] = value.getFloat(i * Float.BYTES);
            switch (type) {
                case GL20C.GL_FLOAT -> GL20C.glUniform1fv(to, floats);
                case GL20C.GL_FLOAT_VEC2 -> GL20C.glUniform2fv(to, floats);
                case GL20C.GL_FLOAT_VEC3 -> GL20C.glUniform3fv(to, floats);
                case GL20C.GL_FLOAT_VEC4 -> GL20C.glUniform4fv(to, floats);
                case GL20C.GL_FLOAT_MAT3 -> GL20C.glUniformMatrix3fv(to, false, floats);
                case GL20C.GL_FLOAT_MAT4 -> GL20C.glUniformMatrix4fv(to, false, floats);
                default -> throw new IllegalStateException("Unsupported Levitite uniform type " + type);
            }
        }
    }
}
