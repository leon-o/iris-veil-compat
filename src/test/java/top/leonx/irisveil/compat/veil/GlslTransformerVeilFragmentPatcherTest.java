package top.leonx.irisveil.compat.veil;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlslTransformerVeilFragmentPatcherTest {
    private static final String IRIS_FRAGMENT = """
        #version 330 compatibility
        uniform sampler2D gtexture;

        void main() {
            vec4 color = texture(gtexture, vec2(0.0));
            gl_FragData[0] = color;
        }
        """;

    @Test
    void removesLayoutQualifierFromRenamedVeilConstGlobals() {
        String veilFragment = """
            #version 150
            in vec4 vertexColor;
            out vec4 fragColor;

            layout(location = 0) const vec3 normal = vec3(0.0, 0.0, -1.0);
            layout(location = 1) out vec4 VeilDynamicAlbedo;

            void main() {
                VeilDynamicAlbedo = vec4(normal, 1.0);
                fragColor = vertexColor;
            }
            """;

        String patched = new GlslTransformerVeilFragmentPatcher().patch(
            IRIS_FRAGMENT,
            veilFragment,
            true);

        assertAll(
            () -> assertTrue(patched.contains("const vec3 _veil_normal"), patched),
            () -> assertFalse(patched.contains("layout(location = 0) const vec3 _veil_normal"), patched)
        );
    }

    @Test
    void keepsRenamedVeilConstGlobalsWithoutLayoutAfterDithering() {
        String veilFragment = """
            #version 150
            in vec4 vertexColor;
            out vec4 fragColor;

            layout(location = 0) const vec3 normal = vec3(0.0, 0.0, -1.0);
            layout(location = 1) out vec4 VeilDynamicAlbedo;
            layout(location = 2) out vec4 VeilDynamicNormal;

            void main() {
                VeilDynamicNormal = vec4(normal, 1.0);
                VeilDynamicAlbedo = vec4(1.0);
                fragColor = vertexColor;
            }
            """;

        String bridged = new GlslTransformerVeilFragmentPatcher().patch(
            IRIS_FRAGMENT,
            veilFragment,
            true);
        String dithered = VeilDitheringPatcher.applyFragmentDithering(
            """
                #version 330 compatibility

                void main() {
                    gl_Position = ftransform();
                }
                """,
            bridged,
            "IGN").patchedFragment();

        assertAll(
            () -> assertTrue(dithered.contains("const vec3 _veil_normal"), dithered),
            () -> assertFalse(dithered.contains("layout(location = 0) const vec3 _veil_normal"), dithered)
        );
    }

    @Test
    void removesInvalidConstLayoutStillPresentWhenDitheringRuns() {
        String fragmentWithLegacyBridge = """
            #version 400 compatibility
            uniform float frameTimeCounter;
            vec4 _veil_fragColor;
            layout(location = 0) const vec3 _veil_normal = vec3(0.0, 0.0, -1.0);

            void main() {
                _veil_fragColor = vec4(_veil_normal, 0.5);
            }
            """;

        String dithered = VeilDitheringPatcher.applyFragmentDithering(
            """
                #version 330 compatibility

                void main() {
                    gl_Position = ftransform();
                }
                """,
            fragmentWithLegacyBridge,
            "IGN").patchedFragment();

        assertAll(
            () -> assertTrue(dithered.contains("const vec3 _veil_normal"), dithered),
            () -> assertFalse(dithered.contains("layout(location = 0) const vec3 _veil_normal"), dithered)
        );
    }

    @Test
    void dropsVeilDynamicSideChannelOutputsBecauseShaderpackOwnsGbufferAttachments() {
        String irisFragment = """
            #version 330 compatibility
            uniform sampler2D gtexture;
            layout(location = 1) out vec4 shaderpackData1;
            layout(location = 2) out vec4 shaderpackData2;

            void main() {
                vec4 color = texture(gtexture, vec2(0.0));
                gl_FragData[0] = color;
                shaderpackData1 = vec4(0.0);
                shaderpackData2 = vec4(0.0, 0.0, 1.0, 1.0);
            }
            """;
        String veilFragment = """
            #version 150
            in vec4 vertexColor;
            out vec4 fragColor;

            const vec3 normal = vec3(0.0, 0.0, -1.0);
            layout(location = 1) out vec4 VeilDynamicAlbedo;
            layout(location = 2) out vec4 VeilDynamicNormal;

            void main() {
                VeilDynamicNormal = vec4(normal, 1.0);
                VeilDynamicAlbedo = vec4(1.0, 0.0, 0.0, 1.0);
                fragColor = vertexColor;
            }
            """;

        String patched = new GlslTransformerVeilFragmentPatcher().patch(
            irisFragment,
            veilFragment,
            true);

        assertAll(
            () -> assertFalse(patched.contains("out vec4 _veil_VeilDynamicAlbedo"), patched),
            () -> assertFalse(patched.contains("out vec4 _veil_VeilDynamicNormal"), patched),
            () -> assertFalse(patched.contains("_veil_VeilDynamicAlbedo ="), patched),
            () -> assertFalse(patched.contains("_veil_VeilDynamicNormal ="), patched),
            () -> assertTrue(patched.contains("_veil_fragColor = _veil_vertexColor"), patched)
        );
    }
}
