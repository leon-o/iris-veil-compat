package top.leonx.irisveil.compat.veil;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeShaderProjectionTest {
    private static String source(String scale, String main) {
        return "vec2 ViewProjectionDownscaling(vec2 Position) { return Position * " + scale + " - (1-" + scale + "); }\n"
            + "void VertexDownscaling(inout vec4 glPosition) { glPosition.xy = ViewProjectionDownscaling(glPosition.xy / glPosition.w) * glPosition.w; }\n"
            + "void main() { " + main + " }";
    }

    @Test
    void recognizesEffectiveScaleAndOnlyAnActiveMatchingJitterStatement() {
        var active = NativeShaderProjection.detect(source("0.75", "pos.xy += taaOffset * pos.w / 0.75; gl_Position=pos; VertexDownscaling(gl_Position);"));
        assertEquals(0.75f, active.scale());
        assertTrue(active.jitter());
        assertTrue(active.recognized());
        assertFalse(NativeShaderProjection.detect(source("0.75", "VertexDownscaling(gl_Position);")).jitter());
        assertFalse(NativeShaderProjection.detect(source("0.75", "// pos.xy += taaOffset * pos.w / 0.75;\nVertexDownscaling(gl_Position);")).jitter());
        assertFalse(NativeShaderProjection.detect(source("0.75", "pos.xy += taaOffset * pos.w / 0.5; gl_Position=pos; VertexDownscaling(gl_Position);")).jitter());
        assertTrue(NativeShaderProjection.detect(source("7.5e-1f", "gl_Position.xy += taaOffset * gl_Position.w / .75; VertexDownscaling(gl_Position);")).jitter());
    }

    @Test
    void rejectsCoincidentalNamesInactiveHelpersAndDifferentTransforms() {
        assertFalse(NativeShaderProjection.detect(null).recognized());
        assertFalse(NativeShaderProjection.detect("#define ResolutionScale 0.75\nvoid main() { gl_Position=vec4(1); }").recognized());
        assertFalse(NativeShaderProjection.detect(source(".75", "gl_Position=vec4(1);")).recognized());
        assertFalse(NativeShaderProjection.detect(source(".75", "/* VertexDownscaling(gl_Position); */")).recognized());
        assertFalse(NativeShaderProjection.detect(source(".75", "VertexDownscaling(gl_Position);").replace("(1-.75)", "(1-.5)")).recognized());
        assertFalse(NativeShaderProjection.detect(source(".75", "VertexDownscaling(gl_Position);").replace("* glPosition.w", "+ glPosition.w")).recognized());
        for (String invalid : new String[] {"0", "1.5", "1e99", "NaN", "-0.5"}) {
            assertFalse(NativeShaderProjection.detect(source(invalid, "VertexDownscaling(gl_Position);")).recognized());
        }
    }

    @Test
    void leftMultiplyMatchesShaderClipTransformAndCompositeRoundTripWithoutChangingDepth() {
        Matrix4f projection = new Matrix4f().perspective(1.1f, 1.7f, .05f, 500.0f);
        Matrix4f original = new Matrix4f(projection);
        for (float scale : new float[] {.25f, .5f, .75f, 1.0f}) {
            var contract = NativeShaderProjection.detect(source(Float.toString(scale),
                "gl_Position.xy += taaOffset * gl_Position.w / " + scale + "; VertexDownscaling(gl_Position);"));
            Matrix4f adjusted = NativeShaderProjection.apply(projection, contract, .002f, -.003f);
            for (Vector4f input : new Vector4f[] {new Vector4f(1, -2, -5, 1), new Vector4f(-3, 1, -17, 1), new Vector4f(.2f, -.8f, -2, 2)}) {
                Vector4f before = projection.transform(new Vector4f(input));
                Vector4f after = adjusted.transform(new Vector4f(input));
                assertEquals(scale * before.x + (scale - 1 + .002f) * before.w, after.x, 1e-5f);
                assertEquals(scale * before.y + (scale - 1 - .003f) * before.w, after.y, 1e-5f);
                assertEquals(before.z, after.z, 1e-6f);
                assertEquals(before.w, after.w, 1e-6f);
                float upscaledU = (after.x / after.w * .5f + .5f) / scale;
                assertEquals(before.x / before.w * .5f + .5f + .001f / scale, upscaledU, 1e-5f);
            }
        }
        assertEquals(original, projection);
    }

    @Test
    void unrecognizedPackIsAnIndependentIdentityCopyAndDoesNotInheritPreviousScale() {
        var recognized = NativeShaderProjection.detect(source(".5", "VertexDownscaling(gl_Position);"));
        var unknown = NativeShaderProjection.detect("void main() {}");
        assertEquals(.5f, recognized.scale());
        Matrix4f projection = new Matrix4f().perspective(1, 1.5f, .1f, 200);
        Matrix4f adjusted = NativeShaderProjection.apply(projection, unknown, 5, 9);
        assertNotSame(projection, adjusted);
        assertEquals(projection, adjusted);
    }
}
