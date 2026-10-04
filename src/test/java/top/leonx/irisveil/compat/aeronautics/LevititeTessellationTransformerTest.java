package top.leonx.irisveil.compat.aeronautics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LevititeTessellationTransformerTest {
    private static final String PACK = """
        #version 400 core
        in vec3 iris_Position;
        in vec4 iris_Color;
        in vec2 iris_UV0;
        in ivec2 iris_UV2;
        in vec3 iris_Normal;
        in vec4 at_tangent;
        in vec4 mc_midTexCoord;
        in vec3 mc_Entity;
        uniform mat4 iris_ModelViewMat;
        uniform mat4 iris_ProjMat;
        uniform mat4 iris_TextureMat;
        uniform vec3 iris_ChunkOffset;
        out vec2 uv[2];
        flat out mat3 tbn;
        flat out uint material_mask;
        out vec4 vCoordAM;
        out vec2 vCoord;
        void main() {
            gl_Position = iris_ProjMat * iris_ModelViewMat * vec4(iris_Position + iris_ChunkOffset, 1.0);
            uv[0] = (iris_TextureMat * vec4(iris_UV0, 0.0, 1.0)).xy;
            uv[1] = vec2(iris_UV2) / 256.0;
            tbn = mat3(at_tangent.xyz, cross(at_tangent.xyz, iris_Normal) * at_tangent.w, iris_Normal);
            material_mask = uint(mc_Entity.x);
            vec2 coordMid = (iris_TextureMat * mc_midTexCoord).xy;
            vec2 coordNMid = uv[0] - coordMid;
            vCoordAM.zw = abs(coordNMid) * 2.0;
            vCoordAM.xy = min(uv[0], coordMid - coordNMid);
            vCoord = sign(coordNMid) * 0.5 + 0.5;
        }
        """;
    private static final String VERTEX = """
        #version 400 core
        layout(location=0) in vec3 Position;
        layout(location=1) in vec4 Color;
        layout(location=2) in vec2 UV0;
        layout(location=3) in ivec2 UV2;
        layout(location=4) in vec3 Normal;
        uniform vec3 ChunkOffset;
        uniform int layerIndex;
        out vec2 texCoord0;
        out vec4 vertexColor;
        out float vertexDistance;
        void main() {
            vec3 pos = Position + ChunkOffset;
            pos += Normal * float(layerIndex) * 0.15;
            gl_Position = vec4(pos, 1.0);
            texCoord0 = UV0;
            vertexColor = Color;
            vertexDistance = length(pos);
        }
        """;
    private static final String CONTROL = """
        #version 400 core
        layout(vertices=4) out;
        in vec2 texCoord0[];
        in vec4 vertexColor[];
        in float vertexDistance[];
        out vec2 texCoord0_out[];
        out vec4 vertexColor_out[];
        out float vertexDistance_out[];
        void main() {
            gl_TessLevelInner[0] = 4;
            gl_TessLevelInner[1] = 4;
            gl_TessLevelOuter[0] = 4;
            gl_TessLevelOuter[1] = 4;
            gl_TessLevelOuter[2] = 4;
            gl_TessLevelOuter[3] = 4;
            gl_out[gl_InvocationID].gl_Position = gl_in[gl_InvocationID].gl_Position;
            texCoord0_out[gl_InvocationID] = texCoord0[gl_InvocationID];
            vertexColor_out[gl_InvocationID] = vertexColor[gl_InvocationID];
            vertexDistance_out[gl_InvocationID] = vertexDistance[gl_InvocationID];
        }
        """;
    private static final String EVALUATION = """
        #version 400 core
        layout(quads) in;
        in vec2 texCoord0_out[];
        in vec4 vertexColor_out[];
        in float vertexDistance_out[];
        out vec2 texCoord0;
        out vec4 vertexColor;
        out float vertexDistance;
        out float ghostLayerFullness;
        out float ghostNoiseMagnitude;
        out float depth;
        uniform mat4 ProjMat;
        uniform mat4 ModelViewMat;
        uniform float time;
        uniform int layerIndex;
        void main() {
            vec4 tePosition = mix(mix(gl_in[0].gl_Position, gl_in[3].gl_Position, gl_TessCoord.y),
                mix(gl_in[1].gl_Position, gl_in[2].gl_Position, gl_TessCoord.y), gl_TessCoord.x);
            vec3 pos = tePosition.xyz;
            pos.y += sin(time) * float(layerIndex) * 0.01;
            texCoord0 = mix(mix(texCoord0_out[0], texCoord0_out[3], gl_TessCoord.y),
                mix(texCoord0_out[1], texCoord0_out[2], gl_TessCoord.y), gl_TessCoord.x);
            vertexColor = vertexColor_out[0];
            vertexDistance = vertexDistance_out[0];
            ghostLayerFullness = 0.4;
            ghostNoiseMagnitude = 0.2;
            gl_Position = ProjMat * ModelViewMat * vec4(pos,1);
            gl_Position.z -= 0.00001;
            depth = gl_Position.z;
        }
        """;
    private static final String FRAGMENT = """
        #version 400 core
        uniform sampler2D Sampler0;
        uniform sampler2D DiffuseDepthSampler;
        uniform sampler2D Noise;
        uniform mat4 ProjMat;
        uniform vec2 ScreenSize;
        uniform float time;
        uniform int layerIndex;
        uniform vec4 ColorModulator;
        uniform float FogStart;
        uniform float FogEnd;
        uniform vec4 FogColor;
        in vec2 texCoord0;
        in vec4 vertexColor;
        in float depth;
        in float ghostLayerFullness;
        layout(location=0) out vec4 fragColor;
        float linearizeDepth(float d) { return -ProjMat[3].z / (d * -2.0 + 1.0 - ProjMat[2].z); }
        vec4 linear_fog(vec4 c, float d, float start, float end, vec4 fog) { return c; }
        void main() {
            vec4 color = texture(Sampler0, texCoord0) * vertexColor;
            float depthSample = texture(DiffuseDepthSampler, gl_FragCoord.xy / ScreenSize).r;
            float depth2 = linearizeDepth(depthSample);
            float fade = smoothstep(-2, 4, (depth2-depth)*16);
            if (fade < 0.001) discard;
            color.a *= ghostLayerFullness * fade;
            color.rgb += texture(Noise, texCoord0 + time).rgb * 0.01;
            fragColor = linear_fog(color, depth, FogStart, FogEnd, FogColor) * ColorModulator;
        }
        """;

    @Test
    void preservesQuadStagesAndRunsPackVertexAfterNativeDeformation() {
        var stages = compose(PACK);
        String vertex = compact(stages.vertex());
        String control = compact(stages.control());
        String evaluation = compact(stages.evaluation());
        assertTrue(vertex.contains("invec3iris_Position;"), stages.vertex());
        assertTrue(vertex.contains("_lv_nativeVertexMain();"), stages.vertex());
        assertTrue(vertex.contains("_lv_rawColor=iris_Color;"), stages.vertex());
        assertTrue(vertex.contains("_lv_rawLight=vec2(iris_UV2);"), stages.vertex());
        assertTrue(control.contains("layout(vertices=4)out;"), stages.control());
        assertTrue(control.contains("outvec3_lv_controlNormal[];"), stages.control());
        assertTrue(control.contains("_lv_controlUv[gl_InvocationID]=_lv_rawUv[gl_InvocationID];"), stages.control());
        assertTrue(evaluation.contains("layout(quads)in;"), stages.evaluation());
        assertTrue(evaluation.contains("_lv_deformedPosition=pos;"), stages.evaluation());
        assertTrue(evaluation.contains("iris_Position=point-iris_ChunkOffset;"), stages.evaluation());
        assertFalse(evaluation.contains("invec3iris_Position;"), stages.evaluation());
        assertTrue(evaluation.indexOf("vec3point=_lv_samplePosition(coord);")
            < evaluation.indexOf("_lv_packVertexMain();"), stages.evaluation());
        assertTrue(evaluation.contains("_lv_packVertexMain();gl_Position.z-=1.0e-5;"), stages.evaluation());
        assertTrue(evaluation.contains("gl_in[0].gl_Position"), stages.evaluation());
        assertFalse(evaluation.contains("gl_in[0]._lv_nativeClip"), stages.evaluation());
    }

    @Test
    void preservesPackOutputArraysQualifiersAndNativeFragmentVaryingNames() {
        var stages = compose(PACK);
        String evaluation = compact(stages.evaluation());
        assertTrue(evaluation.contains("outvec2uv[2];"), stages.evaluation());
        assertTrue(evaluation.contains("flatoutmat3tbn;"), stages.evaluation());
        assertTrue(evaluation.contains("flatoutuintmaterial_mask;"), stages.evaluation());
        assertTrue(evaluation.contains("outfloat_veil_ghostLayerFullness;"), stages.evaluation());
        assertTrue(evaluation.contains("outfloat_veil_ghostNoiseMagnitude;"), stages.evaluation());
        assertTrue(evaluation.contains("_veil_depth=-(iris_ModelViewMat*vec4(point,1.0)).z;"), stages.evaluation());
        assertFalse(evaluation.contains("_lv_ModelViewMat"), stages.evaluation());
        assertTrue(evaluation.indexOf("uniformmat4iris_ModelViewMat;")
            == evaluation.lastIndexOf("uniformmat4iris_ModelViewMat;"), stages.evaluation());
        assertTrue(evaluation.contains("uniformfloat_lv_time;"), stages.evaluation());
        assertTrue(evaluation.contains("iris_UV2=ivec2(round(rawLight));"), stages.evaluation());
        assertTrue(evaluation.contains("cross(tangent,normal)"), stages.evaluation());
    }

    @Test
    void correctsNostalgiaAndBslCornerOnlyAtlasMetadataAtTessellationPoints() {
        String nostalgia = compact(compose(PACK).evaluation());
        assertTrue(nostalgia.contains("vCoordAM.zw=quadSize;"), nostalgia);
        assertTrue(nostalgia.contains("vCoordAM.xy=quadMin;"), nostalgia);
        assertTrue(nostalgia.contains("vCoord=(uv[0]-quadMin)/max(quadSize,vec2(1.0e-12));"), nostalgia);
        assertTrue(nostalgia.indexOf("_lv_packVertexMain();") < nostalgia.indexOf("vCoordAM.zw=quadSize;"), nostalgia);
        String bsl = PACK.replace("out vec2 vCoord;", "out vec4 vCoord;")
            .replace("vCoordAM.zw", "vCoordAM.pq").replace("vCoordAM.xy", "vCoordAM.st")
            .replace("vCoord = sign", "vCoord.xy = sign");
        String bslResult = compact(compose(bsl).evaluation());
        assertTrue(bslResult.contains("vCoordAM.pq=quadSize;"), bslResult);
        assertTrue(bslResult.contains("vCoord.xy=(uv[0]-quadMin)/max(quadSize,vec2(1.0e-12));"), bslResult);
    }

    @Test
    void fragmentUsesAlbedoOnlyCopiedWorldDepthAndNativeTickTime() {
        String fragment = compact(LevititeTessellationTransformer.nativeAlbedoFragment(FRAGMENT, false));
        assertTrue(fragment.contains("uniformsampler2Ddepthtex1;"), fragment);
        assertTrue(fragment.contains("uniformsampler2D_lv_Noise;"), fragment);
        assertTrue(fragment.contains("uniformfloat_lv_time;"), fragment);
        assertTrue(fragment.contains("gl_ProjectionMatrix[3].z"), fragment);
        assertFalse(fragment.contains("iris_ProjMat"), fragment);
        assertTrue(fragment.contains("vec2(textureSize(depthtex1,0))"), fragment);
        assertFalse(fragment.contains("_lv_ScreenSize"), fragment);
        assertTrue(fragment.contains("texture(Sampler0,_veil_texCoord0)*vec4(1.0)"), fragment);
        assertFalse(fragment.contains("invec4_veil_vertexColor;"), fragment);
        assertFalse(fragment.contains("uniformfloat_lv_FogStart;"), fragment);
        assertTrue(fragment.contains("outvec4_veil_fragColor;"), fragment);
        assertFalse(fragment.contains("layout(location=0)"), fragment);
        assertTrue(fragment.contains("floatfade=smoothstep"), fragment);
        String shadow = compact(LevititeTessellationTransformer.nativeAlbedoFragment(FRAGMENT, true));
        assertTrue(shadow.contains("floatfade=1.0;"), shadow);
        assertFalse(shadow.contains("floatfade=smoothstep"), shadow);
    }

    @Test
    void isolatesIrisMissingOutputRepairsAndAcceptsProcessedProjectionFormatting() {
        String repairedPack = PACK.replace("out vec2 uv[2];", "out vec2 uv[2];\nout vec2 _veil_texCoord0;\nout float _veil_depth;")
            .replace("void main() {", "void main() { _veil_texCoord0=vec2(0.0); _veil_depth=0.0;");
        String processedEvaluation = EVALUATION
            .replace("ProjMat * ModelViewMat * vec4(pos,1)", "((ProjMat * ModelViewMat) * vec4(pos, 1.0f))")
            .replace("0.00001;", "1.0E-5f;");
        String evaluation = compact(LevititeTessellationTransformer.transform(
            repairedPack, VERTEX, CONTROL, processedEvaluation).evaluation());
        assertTrue(evaluation.contains("vec2_lv_unused_veil_texCoord0;"), evaluation);
        assertTrue(evaluation.contains("_lv_unused_veil_depth=0.0;"), evaluation);
        assertFalse(java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])_veil_depth=0\\.0;").matcher(evaluation).find(), evaluation);
        assertTrue(evaluation.contains("outfloat_veil_depth;"), evaluation);
        assertTrue(evaluation.contains("_lv_deformedPosition=pos;"), evaluation);
        assertTrue(evaluation.contains("gl_in[0].gl_Position"), evaluation);
    }

    @Test
    void wrapsPackAlbedoSamplesWithoutLosingParallaxCoordinatesLodOrGradients() {
        String packFragment = """
            #version 400 compatibility
            uniform sampler2D gtexture;
            uniform sampler2D depthtex1;
            in vec2 uv;
            void main() {
                vec2 parallaxUv=uv+vec2(0.01);
                vec4 a=textureGrad(gtexture,parallaxUv,dFdx(uv),dFdy(uv));
                vec4 b=textureLod(gtexture,parallaxUv,2.0);
                gl_FragData[0]=a*b;
            }
            """;
        String result = compact(LevititeTessellationTransformer.patchFragment(packFragment, FRAGMENT, false));
        assertTrue(result.startsWith("#version450compatibility"), result);
        assertTrue(result.contains("_lv_applyAlbedo(textureGrad(gtexture,parallaxUv,dFdx(uv),dFdy(uv)))"), result);
        assertTrue(result.contains("_lv_applyAlbedo(textureLod(gtexture,parallaxUv,2.0))"), result);
        assertTrue(result.contains("vec4color=_lv_albedoInput*vec4(1.0);"), result);
        assertFalse(result.contains("outvec4_veil_fragColor"), result);
        assertTrue(result.contains("vec4_veil_fragColor;"), result);
        assertTrue(result.contains("gl_FragData[0]=a*b;"), result);
        assertFalse(result.contains("uniformsampler2DSampler0;"), result);
        assertTrue(result.indexOf("uniformsampler2Ddepthtex1;") == result.lastIndexOf("uniformsampler2Ddepthtex1;"), result);
    }

    @Test
    void texturelessShadowKeepsPackCoverageAndTexturelessWorldProgramFailsClosed() {
        String textureless = "#version 400 compatibility\nvoid main(){ gl_FragData[0]=vec4(1.0); }";
        assertTrue(LevititeTessellationTransformer.patchFragment(textureless, FRAGMENT, true).equals(textureless));
        assertThrows(IllegalArgumentException.class,
            () -> LevititeTessellationTransformer.patchFragment(textureless, FRAGMENT, false));
    }

    @Test
    void acceptsIrisGeneratedFtransformUsingTransformedAttributes() {
        String transformed = PACK.replace("void main() {", "vec4 ftransform(){return iris_ProjMat*iris_ModelViewMat*vec4(iris_Position,1.0);}\nvoid main() {")
            .replace("gl_Position = iris_ProjMat * iris_ModelViewMat * vec4(iris_Position + iris_ChunkOffset, 1.0);", "gl_Position=ftransform();");
        String result = compact(compose(transformed).evaluation());
        assertTrue(result.contains("vec4ftransform()"), result);
        assertTrue(result.contains("gl_Position=ftransform();"), result);
    }

    @Test
    void placesBothStagesExtensionsBeforeDeclarationsAndDeduplicatesThem() {
        String extension = "#extension GL_ARB_tessellation_shader : require\n";
        String pack = PACK.replace("#version 400 core", "#version 400 core\n" + extension
            + "#extension GL_ARB_shader_image_load_store : enable");
        String nativeEvaluation = EVALUATION.replace("#version 400 core", "#version 400 core\n" + extension);
        String result = LevititeTessellationTransformer.transform(pack, VERTEX, CONTROL, nativeEvaluation).evaluation();
        assertTrue(result.indexOf("#extension GL_ARB_tessellation_shader") < result.indexOf("vec3 iris_Position"), result);
        assertTrue(result.indexOf("#extension GL_ARB_shader_image_load_store") < result.indexOf("vec3 iris_Position"), result);
        assertTrue(result.indexOf("#extension GL_ARB_tessellation_shader")
            == result.lastIndexOf("#extension GL_ARB_tessellation_shader"), result);
        assertTrue(result.lastIndexOf("#extension") < result.indexOf("void _lv_packVertexMain"), result);
    }

    @Test
    void preservesUnmappedBlockMaterialSentinelAndOrdinaryBlockComponents() {
        assertTrue(compact(compose(PACK).evaluation()).contains("mc_Entity=vec3(-1.0,0.0,0.0);"));
        String vector4 = PACK.replace("in vec3 mc_Entity;", "in vec4 mc_Entity;");
        assertTrue(compact(compose(vector4).evaluation()).contains("mc_Entity=vec4(-1.0,0.0,0.0,1.0);"));
        String integer2 = PACK.replace("in vec3 mc_Entity;", "in ivec2 mc_Entity;");
        assertTrue(compact(compose(integer2).evaluation()).contains("mc_Entity=ivec2(-1,0);"));
    }

    @Test
    void removesNativeBakedLightingAndFogTransportWhilePreservingRawPackInputs() {
        String vertexWithLighting = VERTEX.replace("uniform int layerIndex;", """
            uniform int layerIndex;
            uniform sampler2D Sampler2;
            uniform float VeilBlockFaceBrightness[6];
            """).replace("vertexColor = Color;", """
                vertexColor = Color * texelFetch(Sampler2, UV2 / 16, 0);
                vertexColor.rgb *= VeilBlockFaceBrightness[2];
                """);
        var sources = LevititeTessellationTransformer.transform(PACK, vertexWithLighting, CONTROL, EVALUATION);
        String vertex = compact(sources.vertex());
        assertFalse(vertex.contains("texelFetch("), sources.vertex());
        assertFalse(vertex.contains("_veil_vertexColor"), sources.vertex());
        assertFalse(vertex.contains("_veil_vertexDistance"), sources.vertex());
        assertFalse(sources.control().contains("_veil_vertexColor"), sources.control());
        assertFalse(sources.control().contains("_veil_vertexDistance"), sources.control());
        assertFalse(sources.evaluation().contains("_veil_vertexColor"), sources.evaluation());
        assertFalse(sources.evaluation().contains("_veil_vertexDistance"), sources.evaluation());
        assertTrue(vertex.contains("_lv_rawColor=iris_Color;"), sources.vertex());
        assertTrue(vertex.contains("_lv_rawLight=vec2(iris_UV2);"), sources.vertex());
        String fragment = compact(LevititeTessellationTransformer.nativeAlbedoFragment(FRAGMENT, false));
        assertTrue(fragment.contains("_veil_fragColor=color*vec4(1.0);"), fragment);
        assertFalse(fragment.contains("infloat_veil_vertexDistance;"), fragment);
    }

    @Test
    void rejectsUnrecognizedStageContractsAndUnprocessedOrUnsupportedVertexInputs() {
        assertThrows(IllegalArgumentException.class, () -> compose(PACK.replace("in vec3 iris_Position;", "in vec3 mystery_Position;")));
        assertThrows(IllegalArgumentException.class, () -> compose(PACK + "\nfloat useId(){return float(gl_VertexID);}"));
        assertThrows(IllegalArgumentException.class, () -> LevititeTessellationTransformer.transform(
            PACK, VERTEX, CONTROL.replace("vertices=4", "vertices=3"), EVALUATION));
        assertThrows(IllegalArgumentException.class, () -> LevititeTessellationTransformer.transform(
            PACK, VERTEX, CONTROL, EVALUATION.replace("layout(quads)", "layout(triangles)")));
        assertThrows(IllegalArgumentException.class, () -> LevititeTessellationTransformer.transform(
            PACK, VERTEX, CONTROL, EVALUATION.replace("ProjMat * ModelViewMat", "ModelViewMat * ProjMat")));
        assertThrows(IllegalArgumentException.class, () -> LevititeTessellationTransformer.transform(
            PACK, "#include unresolved", CONTROL, EVALUATION));
    }

    private static LevititeTessellationTransformer.Sources compose(String pack) {
        return LevititeTessellationTransformer.transform(pack, VERTEX, CONTROL, EVALUATION);
    }

    private static String compact(String source) {
        return source.replaceAll("(?<=\\d)[fF]\\b", "")
            .replaceAll("E(?=[+-]?\\d)", "e").replaceAll("\\s+", "");
    }
}
