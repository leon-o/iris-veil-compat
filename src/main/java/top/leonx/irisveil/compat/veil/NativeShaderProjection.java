package top.leonx.irisveil.compat.veil;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kroppeb.stareval.function.FunctionReturn;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2fc;

import top.leonx.irisveil.IrisVeilCompat;

/** Adapts native draws to the narrowly recognized Nostalgia clip-space contract. */
public final class NativeShaderProjection {
    private static final String NUMBER = "(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?[fF]?";
    private static final Pattern DOWNSCALE = Pattern.compile(
        "vec2ViewProjectionDownscaling\\(vec2(?<position>[A-Za-z_][A-Za-z_0-9]*)\\)\\{return"
            + "\\k<position>\\*(?<scale>" + NUMBER + ")-\\((?:1|1\\.0[fF]?)-(?<offset>" + NUMBER + ")\\);\\}");
    private static final Pattern JITTER = Pattern.compile(
        "(?<position>gl_Position|pos)\\.xy\\+=taaOffset\\*\\k<position>\\.w/(?<scale>" + NUMBER + ");");
    private static final String VERTEX_HELPER =
        "voidVertexDownscaling(inoutvec4glPosition){glPosition.xy=ViewProjectionDownscaling(glPosition.xy/glPosition.w)*glPosition.w;}";
    private static final String CALL = "VertexDownscaling(gl_Position);";
    private static final Contract IDENTITY = new Contract(1.0f, false, false);
    // Only the current dimension/pack context is retained. Reload produces a new ProgramSet.
    private static ProgramSet lastProgramSet;
    private static Contract lastContract = IDENTITY;

    private NativeShaderProjection() {
    }

    public static Matrix4f adjustProjection(IrisRenderingPipeline pipeline, ProgramSet set, Matrix4fc projection) {
        Contract contract = resolve(set);
        float jitterX = 0.0f;
        float jitterY = 0.0f;
        if (contract.jitter() && pipeline != null) {
            Vector2fc jitter = IrisUniforms.readJitter(pipeline);
            if (jitter != null) {
                jitterX = jitter.x();
                jitterY = jitter.y();
            }
        }
        return apply(projection, contract, jitterX, jitterY);
    }

    private static Contract resolve(ProgramSet set) {
        if (set == null) {
            lastProgramSet = null;
            lastContract = IDENTITY;
            return IDENTITY;
        }
        if (set != lastProgramSet) {
            lastProgramSet = set;
            lastContract = detect(set.get(ProgramId.Terrain)
                .flatMap(source -> source.getVertexSource()).orElse(null));
            if (lastContract.recognized()) {
                IrisVeilCompat.LOGGER.info(
                    "IrisVeilCompat: native world projection recognized clip scale {} with cached TAA {}",
                    lastContract.scale(), lastContract.jitter());
            }
        }
        return lastContract;
    }

    static Contract detect(String source) {
        if (source == null) {
            return IDENTITY;
        }
        String compact = source.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*", "").replaceAll("\\s+", "");
        if (!compact.contains(VERTEX_HELPER)) {
            return IDENTITY;
        }
        Matcher helper = DOWNSCALE.matcher(compact);
        if (!helper.find()) {
            return IDENTITY;
        }
        float scale = number(helper.group("scale"));
        float offset = number(helper.group("offset"));
        if (!Float.isFinite(scale) || scale <= 0.0f || scale > 1.0f || scale != offset) {
            return IDENTITY;
        }
        String main = mainBody(compact);
        int downscaleCall = main.indexOf(CALL);
        if (downscaleCall < 0) {
            return IDENTITY;
        }
        boolean jitter = false;
        Matcher jitterStatement = JITTER.matcher(main);
        while (jitterStatement.find() && jitterStatement.end() <= downscaleCall) {
            if (number(jitterStatement.group("scale")) == scale
                    && (jitterStatement.group("position").equals("gl_Position")
                    || main.substring(jitterStatement.end(), downscaleCall).contains("gl_Position=pos;"))) {
                jitter = true;
            }
        }
        return new Contract(scale, jitter, true);
    }

    private static String mainBody(String source) {
        Matcher main = Pattern.compile("voidmain\\((?:void)?\\)\\{").matcher(source);
        if (!main.find()) {
            return "";
        }
        int start = main.end();
        int depth = 1;
        for (int index = start; index < source.length(); index++) {
            if (source.charAt(index) == '{') depth++;
            if (source.charAt(index) == '}' && --depth == 0) return source.substring(start, index);
        }
        return "";
    }

    private static float number(String value) {
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    static Matrix4f apply(Matrix4fc projection, Contract contract, float jitterX, float jitterY) {
        if (!contract.recognized()) {
            return new Matrix4f(projection);
        }
        float scale = contract.scale();
        // Shader order: jitter / scale, then downscale. The resulting jitter is unscaled.
        float x = scale - 1.0f + (contract.jitter() ? jitterX : 0.0f);
        float y = scale - 1.0f + (contract.jitter() ? jitterY : 0.0f);
        return new Matrix4f().m00(scale).m11(scale).m30(x).m31(y).mul(projection);
    }

    record Contract(float scale, boolean jitter, boolean recognized) {
    }

    /** Keep Iris's expression runtime out of the standalone transform checks. */
    private static final class IrisUniforms {
        private static Vector2fc readJitter(IrisRenderingPipeline pipeline) {
            var uniforms = pipeline.getCustomUniforms();
            if (uniforms.hasVariable("taaOffset")) {
                FunctionReturn value = new FunctionReturn();
                uniforms.getVariable("taaOffset").evaluateTo(uniforms, value);
                if (value.objectReturn instanceof Vector2fc vector
                        && Float.isFinite(vector.x()) && Float.isFinite(vector.y())) {
                    // The cached vector belongs to Iris. Read it without mutation or update().
                    return vector;
                }
            }
            return null;
        }
    }
}
