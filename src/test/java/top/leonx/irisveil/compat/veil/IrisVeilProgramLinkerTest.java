package top.leonx.irisveil.compat.veil;

import com.mojang.blaze3d.vertex.VertexFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrisVeilProgramLinkerTest {
    private static final String SHADOW_VERTEX = """
        #version 330 compatibility

        void main() {
            gl_Position = ftransform();
        }
        """;

    private static final String ROPE_VERTEX = """
        #version 150
        uniform mat4 ModelViewMat;
        uniform mat4 ProjMat;

        in vec3 Position;

        void main() {
            gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
        }
        """;

    @Test
    void shadowVertexProgramsKeepVeilVertexTransform() {
        String patched = IrisVeilProgramLinker.patchVertexSource(
            SHADOW_VERTEX,
            ROPE_VERTEX,
            new VertexFormat(),
            "shadow_veil_simulated_rope_rope",
            new GlslTransformerVeilPatcher());

        assertAll(
            () -> assertNotEquals(SHADOW_VERTEX, patched),
            () -> assertTrue(patched.contains("_veil_modelVertex = gl_Vertex;"), patched),
            () -> assertTrue(patched.contains("gl_Position = gl_ProjectionMatrix * gl_ModelViewMatrix * _veil_modelVertex"), patched)
        );
    }
}
