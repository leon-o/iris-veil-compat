package top.leonx.irisveil.compat.aeronautics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.Version;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.binary.AssignmentExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.BinaryExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.MultiplicationAssignmentExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ExpressionStatement;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier.StorageType;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.RootSupplier;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.SingleASTTransformer;

/**
 * Composes the native four-control-point deformation with Iris's already transformed
 * shaderpack vertex program. The pack owns projection, varyings and fragment outputs.
 * This runs after Iris's vertex-attribute transformation, never on an unprocessed pack.
 */
public final class LevititeTessellationTransformer {
    private static final Pattern VERSION = Pattern.compile("(?m)^\\s*#version[^\\r\\n]*");
    private static final Pattern EXTENSION = Pattern.compile(
        "(?m)^[\\t ]*#extension[\\t ]+([A-Za-z_][A-Za-z_0-9]*)[\\t ]*:[\\t ]*([A-Za-z_]+)[\\t ]*$");
    private static final Pattern NATIVE_PROJECT = Pattern.compile(
        "\\bgl_Position\\s*=\\s*([^;]+);");
    private static final Pattern NATIVE_BIAS = Pattern.compile(
        "\\bgl_Position\\.z\\s*-=\\s*([0-9.eE+-]+)[fF]?\\s*;");
    private static final String IDENTIFIER = "[A-Za-z_][A-Za-z_0-9]*";
    // These connected assignments are the corner-only atlas metadata used by BSL/Nostalgia.
    private static final Pattern ATLAS_METADATA = Pattern.compile(
        "vec2(?<mid>" + IDENTIFIER + ")=\\(iris_TextureMat\\*mc_midTexCoord\\)\\.(?:xy|st);"
            + "vec2(?<delta>" + IDENTIFIER + ")=(?<uv>" + IDENTIFIER + "(?:\\[[0-9]+\\])?)-\\k<mid>;"
            + "(?<atlas>" + IDENTIFIER + ")\\.(?<size>zw|pq)=abs\\(\\k<delta>\\)\\*2(?:\\.0*)?;"
            + "\\k<atlas>\\.(?<min>xy|st)=min\\(\\k<uv>,\\k<mid>-\\k<delta>\\);"
            + "(?<local>" + IDENTIFIER + "(?:\\.xy)?)=sign\\(\\k<delta>\\)\\*(?:0?\\.5)\\+(?:0?\\.5);");
    private static final Map<String, String> NATIVE_ATTRIBUTES = Map.of(
        "Position", "iris_Position", "Color", "iris_Color", "UV0", "iris_UV0",
        "UV2", "iris_UV2", "Normal", "iris_Normal");
    private static final Map<String, String> STANDARD_UNIFORMS = Map.of(
        "ModelViewMat", "iris_ModelViewMat", "ProjMat", "iris_ProjMat",
        "ChunkOffset", "iris_ChunkOffset", "NormalMat", "iris_NormalMat");
    private static final Set<String> KNOWN_PACK_INPUTS = Set.of(
        "iris_Position", "iris_Color", "iris_UV0", "iris_UV1", "iris_UV2", "iris_Normal",
        "at_tangent", "mc_Entity", "mc_midTexCoord", "at_midBlock");
    private static final Set<String> PACK_ALBEDO_SAMPLERS = Set.of("gtexture", "tex", "texture", "gcolor", "iris_Texture");
    private static final Set<String> TEXTURE_FUNCTIONS = Set.of(
        "texture", "texture2D", "textureLod", "textureGrad", "textureProj", "textureProjLod", "textureProjGrad");
    private static final String RAW_OUTPUTS = """
        out vec4 _lv_rawColor;
        out vec2 _lv_rawUv;
        out vec2 _lv_rawLight;
        out vec3 _lv_rawNormal;
        """;
    private static final String CONTROL_TRANSPORT = """
        in vec4 _lv_rawColor[];
        in vec2 _lv_rawUv[];
        in vec2 _lv_rawLight[];
        in vec3 _lv_rawNormal[];
        out vec4 _lv_controlColor[];
        out vec2 _lv_controlUv[];
        out vec2 _lv_controlLight[];
        out vec3 _lv_controlNormal[];
        """;
    private static final String EVALUATION_TRANSPORT = """
        in vec4 _lv_controlColor[];
        in vec2 _lv_controlUv[];
        in vec2 _lv_controlLight[];
        in vec3 _lv_controlNormal[];
        vec3 _lv_deformedPosition;
        vec4 _lv_nativeClip;
        vec3 _lv_evaluationCoord;
        """;

    private LevititeTessellationTransformer() {
    }

    public record Sources(String vertex, String control, String evaluation) {
    }

    public static Sources transform(String transformedPackVertex, String nativeVertex,
            String nativeControl, String nativeEvaluation) {
        requireSource(transformedPackVertex, "transformed shaderpack vertex");
        requireSource(nativeVertex, "native vertex");
        requireSource(nativeControl, "native control");
        requireSource(nativeEvaluation, "native evaluation");
        String controlContract = compact(nativeControl);
        String evaluationContract = compact(nativeEvaluation);
        if (!controlContract.contains("layout(vertices=4)out;")
                || !evaluationContract.contains("layout(quads)in;")) {
            throw new IllegalArgumentException("Levitite requires its native four-point quad tessellation stages");
        }
        String capturedEvaluation = captureDeformedPosition(nativeEvaluation);
        Matcher bias = NATIVE_BIAS.matcher(nativeEvaluation);
        if (!bias.find()) {
            throw new IllegalArgumentException("Missing Levitite depth bias contract");
        }
        String depthBias = bias.group(1);
        if (!Float.isFinite(Float.parseFloat(depthBias)) || Float.parseFloat(depthBias) < 0.0f) {
            throw new IllegalArgumentException("Invalid Levitite depth bias");
        }

        Parser parser = new Parser();
        TranslationUnit pack = parser.parse(transformedPackVertex);
        if (pack.getRoot().identifierIndex.has("gl_Vertex")
                || pack.getRoot().identifierIndex.has("gl_VertexID")
                || pack.getRoot().identifierIndex.has("gl_InstanceID")
                || pack.getRoot().identifierIndex.has("_veil_modelVertex")) {
            throw new IllegalArgumentException("Expected a post-Iris vertex shader without vertex IDs or prior Veil injection");
        }
        List<AtlasMetadata> atlasMetadata = findAtlasMetadata(ASTPrinter.printSimple(pack));
        boolean chunkOffset = hasGlobal(pack, "iris_ChunkOffset");
        Map<String, String> inputs = privatizePackInputs(parser, pack);
        isolateNativePlaceholders(parser, pack, parser.parse(nativeEvaluation));
        pack.getRoot().rename("main", "_lv_packVertexMain");

        TranslationUnit vertex = parser.parse(nativeVertex);
        removeNativeShadingTransport(vertex);
        renameNative(parser, vertex, true, false);
        vertex.getRoot().rename("main", "_lv_nativeVertexMain");
        parser.inject(vertex, ASTInjectionPoint.BEFORE_DECLARATIONS, RAW_OUTPUTS);
        parser.inject(vertex, ASTInjectionPoint.END, """
            void main() {
                _lv_nativeVertexMain();
                _lv_rawColor = iris_Color;
                _lv_rawUv = iris_UV0;
                _lv_rawLight = vec2(iris_UV2);
                _lv_rawNormal = iris_Normal;
            }
            """);

        TranslationUnit control = parser.parse(nativeControl);
        removeNativeShadingTransport(control);
        renameNative(parser, control, false, false);
        control.getRoot().rename("main", "_lv_nativeControlMain");
        parser.inject(control, ASTInjectionPoint.BEFORE_DECLARATIONS, CONTROL_TRANSPORT);
        parser.inject(control, ASTInjectionPoint.END, """
            void main() {
                _lv_nativeControlMain();
                _lv_controlColor[gl_InvocationID] = _lv_rawColor[gl_InvocationID];
                _lv_controlUv[gl_InvocationID] = _lv_rawUv[gl_InvocationID];
                _lv_controlLight[gl_InvocationID] = _lv_rawLight[gl_InvocationID];
                _lv_controlNormal[gl_InvocationID] = _lv_rawNormal[gl_InvocationID];
            }
            """);

        TranslationUnit evaluation = parser.parse(capturedEvaluation);
        removeNativeShadingTransport(evaluation);
        renameNative(parser, evaluation, false, false);
        deduplicateUniforms(pack, evaluation);
        evaluation.getRoot().rename("main", "_lv_nativeEvaluationMain");
        // Rename only standalone expressions: gl_in[i].gl_Position is an input block member.
        evaluation.getRoot().replaceReferenceExpressions(parser.transformer, "gl_Position", "_lv_nativeClip");
        evaluation.getRoot().replaceReferenceExpressions(parser.transformer, "gl_TessCoord", "_lv_evaluationCoord");
        parser.inject(evaluation, ASTInjectionPoint.BEFORE_DECLARATIONS, EVALUATION_TRANSPORT);
        String composedEvaluation = ASTPrinter.printSimple(pack) + "\n"
            + VERSION.matcher(ASTPrinter.printSimple(evaluation)).replaceAll("") + "\n"
            + evaluationHelpers() + evaluationMain(inputs, chunkOffset, depthBias, atlasMetadata);
        // Parse the combined source too; declaration/scope syntax errors fail before GL compilation.
        composedEvaluation = ASTPrinter.printSimple(parser.parse(hoistExtensions(composedEvaluation)));
        return new Sources(ASTPrinter.printSimple(vertex), ASTPrinter.printSimple(control), composedEvaluation);
    }

    /** Native color effects become albedo; shaderpack color, lightmap, lighting and fog remain authoritative. */
    public static String nativeAlbedoFragment(String nativeFragment, boolean shadow) {
        requireSource(nativeFragment, "native fragment");
        String source = nativeFragment;
        if (shadow) {
            Pattern fade = Pattern.compile("\\bfloat\\s+fade\\s*=\\s*smoothstep\\([^;]+\\)\\s*;");
            if (!fade.matcher(source).find()) {
                throw new IllegalArgumentException("Unrecognized Levitite ghost depth fade");
            }
            // The light camera cannot sample world-camera depth. Pack shadow alpha handling remains active.
            source = fade.matcher(source).replaceFirst("float fade = 1.0;");
        }
        Parser parser = new Parser();
        TranslationUnit tree = parser.parse(source);
        // The shaderpack applies fog. Remove the native operation instead of approximating
        // an infinite fog distance, which keeps a needless VS/TCS/TES lighting path active.
        for (FunctionCallExpression call : tree.getRoot().nodeIndex.getStream(FunctionCallExpression.class).toList()) {
            if (call.getFunctionName() != null && call.getFunctionName().getName().equals("linear_fog")) {
                if (call.getParameters().size() != 5) throw new IllegalArgumentException("Unrecognized native Levitite fog call");
                call.replaceBy(parser.transformer.parseExpression(tree.getRoot(),
                    ASTPrinter.printSimple(call.getParameters().getFirst())));
            }
        }
        removeDeclarations(tree, Set.of("vertexColor", "vertexDistance", "ColorModulator", "FogStart", "FogEnd", "FogColor", "ScreenSize",
            "ProjMat", "ModelViewMat", "NormalMat"));
        tree.getRoot().replaceReferenceExpressions(parser.transformer, "vertexColor", "vec4(1.0)");
        tree.getRoot().replaceReferenceExpressions(parser.transformer, "ColorModulator", "vec4(1.0)");
        tree.getRoot().replaceReferenceExpressions(parser.transformer, "FogStart", "1.0e20");
        tree.getRoot().replaceReferenceExpressions(parser.transformer, "FogEnd", "1.0e20");
        tree.getRoot().replaceReferenceExpressions(parser.transformer, "FogColor", "vec4(0.0)");
        tree.getRoot().replaceReferenceExpressions(parser.transformer, "ScreenSize",
            "vec2(textureSize(DiffuseDepthSampler, 0))");
        // This source still goes through Iris. Its reserved iris_* interface is introduced there,
        // whereas native VS/TCS/TES are spliced after Iris and can use that interface directly.
        tree.getRoot().replaceReferenceExpressions(parser.transformer, "ProjMat", "gl_ProjectionMatrix");
        tree.getRoot().replaceReferenceExpressions(parser.transformer, "ModelViewMat", "gl_ModelViewMatrix");
        tree.getRoot().replaceReferenceExpressions(parser.transformer, "NormalMat", "gl_NormalMatrix");
        renameNative(parser, tree, false, true);
        return ASTPrinter.printSimple(tree);
    }

    /**
     * Apply native hue/noise/ghost effects to the texel chosen by the shaderpack.
     * Unlike a precomputed-color substitution, this preserves POM coordinates, LOD and gradients.
     * Input and output are pre-Iris fragment sources; call this before patchVanilla.
     */
    public static String patchFragment(String packFragment, String nativeFragment, boolean shadow) {
        requireSource(packFragment, "shaderpack fragment");
        Parser parser = new Parser();
        TranslationUnit pack = parser.parse(packFragment);
        List<FunctionCallExpression> samples = textureCalls(pack, PACK_ALBEDO_SAMPLERS);
        if (samples.isEmpty()) {
            if (shadow) return packFragment;
            throw new IllegalArgumentException("Levitite requires a recognized shaderpack albedo sample");
        }
        for (FunctionCallExpression sample : samples) {
            String original = ASTPrinter.printSimple(sample);
            sample.replaceBy(parser.transformer.parseExpression(pack.getRoot(), "_lv_applyAlbedo(" + original + ")"));
        }
        parser.inject(pack, ASTInjectionPoint.BEFORE_DECLARATIONS, "vec4 _lv_applyAlbedo(vec4 albedo);");

        TranslationUnit nativeTree = parser.parse(nativeAlbedoFragment(nativeFragment, shadow));
        List<FunctionCallExpression> nativeSamples = textureCalls(nativeTree, Set.of("Sampler0"));
        if (nativeSamples.size() != 1) {
            throw new IllegalArgumentException("Unrecognized native Levitite albedo sample contract");
        }
        nativeSamples.getFirst().replaceBy(parser.transformer.parseExpression(nativeTree.getRoot(), "_lv_albedoInput"));
        removeDeclarations(nativeTree, Set.of("Sampler0"));
        if (hasGlobal(pack, "depthtex1")) removeDeclarations(nativeTree, Set.of("depthtex1"));
        // Native side-channel outputs become private data; only the shaderpack writes framebuffer attachments.
        privatizeOutputs(parser, nativeTree);
        nativeTree.getRoot().rename("main", "_lv_nativeFragmentMain");
        parser.inject(nativeTree, ASTInjectionPoint.BEFORE_DECLARATIONS, "vec4 _lv_albedoInput;");
        parser.inject(nativeTree, ASTInjectionPoint.END, """
            vec4 _lv_applyAlbedo(vec4 albedo) {
                _lv_albedoInput = albedo;
                _lv_nativeFragmentMain();
                return _veil_fragColor;
            }
            """);
        String combined = ASTPrinter.printSimple(pack) + "\n"
            + VERSION.matcher(ASTPrinter.printSimple(nativeTree)).replaceAll("");
        String result = ASTPrinter.printSimple(parser.parse(hoistExtensions(combined)));
        // Preserve the legacy/core distinction so Iris still selects the appropriate fragment transformer.
        Matcher version = Pattern.compile("(?m)^\\s*#version\\s+(\\d+)(?:\\s+(compatibility|core))?").matcher(packFragment);
        boolean compatibility = !version.find() || "compatibility".equals(version.group(2))
            || Integer.parseInt(version.group(1)) < 150;
        return VERSION.matcher(result).replaceFirst("#version 450 " + (compatibility ? "compatibility" : "core"));
    }

    private static List<FunctionCallExpression> textureCalls(TranslationUnit tree, Set<String> samplers) {
        return tree.getRoot().nodeIndex.getStream(FunctionCallExpression.class)
            .filter(call -> call.getFunctionName() != null && TEXTURE_FUNCTIONS.contains(call.getFunctionName().getName()))
            .filter(call -> !call.getParameters().isEmpty()
                && samplers.contains(ASTPrinter.printSimple(call.getParameters().getFirst()).trim()))
            .toList();
    }

    private static String hoistExtensions(String source) {
        Map<String, String> extensions = new LinkedHashMap<>();
        Matcher matcher = EXTENSION.matcher(source);
        while (matcher.find()) {
            String previous = extensions.putIfAbsent(matcher.group(1), matcher.group(2));
            if (previous != null && !previous.equals(matcher.group(2))) {
                throw new IllegalArgumentException("Conflicting shader extension behavior for " + matcher.group(1));
            }
        }
        String body = VERSION.matcher(matcher.replaceAll("")).replaceAll("");
        StringBuilder result = new StringBuilder("#version 450 core\n");
        extensions.forEach((name, behavior) -> result.append("#extension ").append(name)
            .append(" : ").append(behavior).append('\n'));
        return result.append(body).toString();
    }

    private static void privatizeOutputs(Parser parser, TranslationUnit tree) {
        for (var child : new ArrayList<>(tree.getChildren())) {
            if (child instanceof DeclarationExternalDeclaration external
                    && external.getDeclaration() instanceof TypeAndInitDeclaration declaration
                    && hasStorage(declaration, StorageType.OUT)) {
                String type = ASTPrinter.printSimple(declaration.getType().getTypeSpecifier()).trim();
                for (var member : declaration.getMembers()) {
                    parser.inject(tree, ASTInjectionPoint.BEFORE_DECLARATIONS, type + " " + member.getName().getName() + ";");
                }
                child.detachAndDelete();
            }
        }
    }

    private static Map<String, String> privatizePackInputs(Parser parser, TranslationUnit tree) {
        Map<String, String> inputs = new LinkedHashMap<>();
        for (var child : new ArrayList<>(tree.getChildren())) {
            if (!(child instanceof DeclarationExternalDeclaration external)
                    || !(external.getDeclaration() instanceof TypeAndInitDeclaration declaration)
                    || !hasStorage(declaration, StorageType.IN)) {
                continue;
            }
            String type = ASTPrinter.printSimple(declaration.getType().getTypeSpecifier()).trim();
            for (var member : declaration.getMembers()) {
                String name = member.getName().getName();
                if (!KNOWN_PACK_INPUTS.contains(name) || ASTPrinter.printSimple(member).contains("[")) {
                    throw new IllegalArgumentException("Unsupported Levitite shaderpack vertex input: " + name);
                }
                inputs.put(name, type);
                parser.inject(tree, ASTInjectionPoint.BEFORE_DECLARATIONS, type + " " + name + ";");
            }
            child.detachAndDelete();
        }
        if (!inputs.containsKey("iris_Position")) {
            throw new IllegalArgumentException("Iris vertex position input is missing");
        }
        return inputs;
    }

    private static String captureDeformedPosition(String nativeEvaluation) {
        Matcher projection = NATIVE_PROJECT.matcher(nativeEvaluation);
        while (projection.find()) {
            String expression = compact(projection.group(1)).replace("(", "").replace(")", "");
            if (expression.matches("ProjMat\\*ModelViewMat\\*vec4pos,1(?:\\.0*)?")) {
                return nativeEvaluation.substring(0, projection.start()) + "_lv_deformedPosition = pos;\n"
                    + nativeEvaluation.substring(projection.start());
            }
        }
        throw new IllegalArgumentException("Unrecognized Levitite evaluation projection boundary");
    }

    private static void isolateNativePlaceholders(Parser parser, TranslationUnit pack, TranslationUnit nativeEvaluation) {
        Set<String> nativeOutputs = new java.util.HashSet<>();
        for (var child : nativeEvaluation.getChildren()) {
            if (child instanceof DeclarationExternalDeclaration external
                    && external.getDeclaration() instanceof TypeAndInitDeclaration declaration
                    && hasStorage(declaration, StorageType.OUT)) {
                declaration.getMembers().forEach(member -> nativeOutputs.add("_veil_" + member.getName().getName()));
            }
        }
        // Iris repairs VS/FS interfaces before our post-transform splice. Its missing-native-output
        // placeholders must not overwrite the real TES results when the relocated pack main runs.
        for (var child : new ArrayList<>(pack.getChildren())) {
            if (!(child instanceof DeclarationExternalDeclaration external)
                    || !(external.getDeclaration() instanceof TypeAndInitDeclaration declaration)
                    || !hasStorage(declaration, StorageType.OUT)) continue;
            String type = ASTPrinter.printSimple(declaration.getType().getTypeSpecifier()).trim();
            for (var member : new ArrayList<>(declaration.getMembers())) {
                String name = member.getName().getName();
                if (!nativeOutputs.contains(name)) continue;
                String unused = "_lv_unused" + name;
                pack.getRoot().rename(name, unused);
                parser.inject(pack, ASTInjectionPoint.BEFORE_DECLARATIONS, type + " " + unused + ";");
                member.detachAndDelete();
            }
            if (declaration.getMembers().isEmpty()) child.detachAndDelete();
        }
    }

    private static void renameNative(Parser parser, TranslationUnit tree, boolean vertex, boolean fragment) {
        Map<String, String> names = new LinkedHashMap<>();
        Set<String> attributes = new java.util.HashSet<>();
        for (var child : new ArrayList<>(tree.getChildren())) {
            if (child instanceof FunctionDefinition function) {
                String name = function.getFunctionPrototype().getName().getName();
                if (!name.equals("main")) names.put(name, "_veil_" + name);
            } else if (child instanceof DeclarationExternalDeclaration external
                    && external.getDeclaration() instanceof TypeAndInitDeclaration declaration) {
                boolean uniform = hasStorage(declaration, StorageType.UNIFORM);
                boolean attribute = vertex && hasStorage(declaration, StorageType.IN);
                for (var member : declaration.getMembers()) {
                    String name = member.getName().getName();
                    if (name.startsWith("gl_") || name.startsWith("_lv_")) continue;
                    if (attribute) {
                        String mapped = NATIVE_ATTRIBUTES.get(name);
                        if (mapped == null) throw new IllegalArgumentException("Unsupported native Levitite attribute: " + name);
                        attributes.add(name);
                        names.put(name, mapped);
                    } else if (uniform) {
                        names.put(name, fragment && name.equals("Sampler0") ? name
                            : fragment && name.equals("DiffuseDepthSampler") ? "depthtex1"
                            : !fragment && STANDARD_UNIFORMS.containsKey(name) ? STANDARD_UNIFORMS.get(name) : "_lv_" + name);
                    } else {
                        names.put(name, "_veil_" + name);
                    }
                }
                if (attribute || fragment && hasStorage(declaration, StorageType.OUT)
                        && declaration.getMembers().stream().anyMatch(member -> member.getName().getName().equals("fragColor"))) {
                    // Attribute locations come from Iris/VertexFormat; native fragment bridge expects a plain out.
                    String type = ASTPrinter.printSimple(declaration.getType().getTypeSpecifier()).trim();
                    for (var member : declaration.getMembers()) {
                        parser.inject(tree, ASTInjectionPoint.BEFORE_DECLARATIONS,
                            (attribute ? "in " : "out ") + type + " " + member.getName().getName() + ";");
                    }
                    child.detachAndDelete();
                }
            }
        }
        if (vertex && !attributes.containsAll(NATIVE_ATTRIBUTES.keySet())) {
            throw new IllegalArgumentException("Native Levitite is missing required raw vertex attributes");
        }
        names.forEach((before, after) -> { if (!before.equals(after)) tree.getRoot().rename(before, after); });
    }

    private static void removeDeclarations(TranslationUnit tree, Set<String> names) {
        for (var child : new ArrayList<>(tree.getChildren())) {
            if (child instanceof DeclarationExternalDeclaration external
                    && external.getDeclaration() instanceof TypeAndInitDeclaration declaration) {
                if (declaration.getMembers().stream().allMatch(member -> names.contains(member.getName().getName()))) {
                    child.detachAndDelete();
                } else {
                    for (var member : new ArrayList<>(declaration.getMembers())) {
                        if (names.contains(member.getName().getName())) member.detachAndDelete();
                    }
                }
            }
        }
    }

    private static void removeNativeShadingTransport(TranslationUnit tree) {
        // Unconsumed outputs between tessellation stages can keep their samplers and uniforms
        // active. Remove this path explicitly; raw color/lightmap take the separate pack path.
        Set<String> varyings = Set.of("vertexColor", "vertexColor_out", "vertexDistance", "vertexDistance_out");
        for (ExpressionStatement statement : tree.getRoot().nodeIndex.getStream(ExpressionStatement.class).toList()) {
            var expression = statement.getExpression();
            if (!(expression instanceof AssignmentExpression) && !(expression instanceof MultiplicationAssignmentExpression)) continue;
            String target = ASTPrinter.printSimple(((BinaryExpression) expression).getLeft()).trim();
            String name = target.split("[.\\[]", 2)[0];
            if (varyings.contains(name)) statement.detachAndDelete();
        }
        removeDeclarations(tree, varyings);
    }

    private static boolean hasStorage(TypeAndInitDeclaration declaration, StorageType storage) {
        var qualifier = declaration.getType().getTypeQualifier();
        return qualifier != null && qualifier.getParts().stream()
            .anyMatch(part -> part instanceof StorageQualifier candidate && candidate.storageType == storage);
    }

    private static void deduplicateUniforms(TranslationUnit pack, TranslationUnit nativeStage) {
        Map<String, String> packUniforms = new LinkedHashMap<>();
        for (var child : pack.getChildren()) {
            if (child instanceof DeclarationExternalDeclaration external
                    && external.getDeclaration() instanceof TypeAndInitDeclaration declaration
                    && hasStorage(declaration, StorageType.UNIFORM)) {
                String type = ASTPrinter.printSimple(declaration.getType().getTypeSpecifier()).trim();
                declaration.getMembers().forEach(member -> packUniforms.put(member.getName().getName(), type));
            }
        }
        for (var child : new ArrayList<>(nativeStage.getChildren())) {
            if (child instanceof DeclarationExternalDeclaration external
                    && external.getDeclaration() instanceof TypeAndInitDeclaration declaration
                    && hasStorage(declaration, StorageType.UNIFORM)) {
                String type = ASTPrinter.printSimple(declaration.getType().getTypeSpecifier()).trim();
                for (var member : new ArrayList<>(declaration.getMembers())) {
                    String name = member.getName().getName();
                    if (!packUniforms.containsKey(name)) continue;
                    if (!packUniforms.get(name).equals(type)) {
                        throw new IllegalArgumentException("Conflicting native/shaderpack uniform type for " + name);
                    }
                    member.detachAndDelete();
                }
                if (declaration.getMembers().isEmpty()) child.detachAndDelete();
            }
        }
    }

    private static boolean hasGlobal(TranslationUnit tree, String name) {
        return tree.getChildren().stream().anyMatch(child -> child instanceof DeclarationExternalDeclaration external
            && external.getDeclaration() instanceof TypeAndInitDeclaration declaration
            && declaration.getMembers().stream().anyMatch(member -> member.getName().getName().equals(name)));
    }

    private static String evaluationHelpers() {
        return """
            vec4 _lv_weights(vec2 coord) {
                return vec4((1.0-coord.x)*(1.0-coord.y), coord.x*(1.0-coord.y),
                    coord.x*coord.y, (1.0-coord.x)*coord.y);
            }
            vec3 _lv_samplePosition(vec2 coord) {
                _lv_evaluationCoord = vec3(coord, 0.0);
                _lv_nativeEvaluationMain();
                return _lv_deformedPosition;
            }
            vec3 _lv_safeNormal(vec3 value, vec3 fallback) {
                return dot(value, value) > 1.0e-12 ? normalize(value) : fallback;
            }
            """;
    }

    private static String evaluationMain(Map<String, String> inputs, boolean chunkOffset,
            String depthBias, List<AtlasMetadata> atlasMetadata) {
        StringBuilder source = new StringBuilder("""
            void main() {
                vec2 coord = gl_TessCoord.xy;
                vec4 weights = _lv_weights(coord);
                float du = coord.x < 0.999 ? 0.001 : -0.001;
                float dv = coord.y < 0.999 ? 0.001 : -0.001;
                vec3 pointU = _lv_samplePosition(coord + vec2(du, 0.0));
                vec3 pointV = _lv_samplePosition(coord + vec2(0.0, dv));
                // Restore all native outputs at the actual tessellation point after derivative samples.
                vec3 point = _lv_samplePosition(coord);
                vec3 derivativeU = (pointU - point) / du;
                vec3 derivativeV = (pointV - point) / dv;
                vec3 rawNormal = _lv_safeNormal(
                    _lv_controlNormal[0]*weights.x + _lv_controlNormal[1]*weights.y
                    + _lv_controlNormal[2]*weights.z + _lv_controlNormal[3]*weights.w, vec3(0.0, 1.0, 0.0));
                vec3 normal = _lv_safeNormal(cross(derivativeU, derivativeV), rawNormal);
                if (dot(normal, rawNormal) < 0.0) normal = -normal;
                vec2 rawUv = _lv_controlUv[0]*weights.x + _lv_controlUv[1]*weights.y
                    + _lv_controlUv[2]*weights.z + _lv_controlUv[3]*weights.w;
                vec2 uvDerivativeU = mix(_lv_controlUv[1]-_lv_controlUv[0],
                    _lv_controlUv[2]-_lv_controlUv[3], coord.y);
                vec2 uvDerivativeV = mix(_lv_controlUv[3]-_lv_controlUv[0],
                    _lv_controlUv[2]-_lv_controlUv[1], coord.x);
                float determinant = uvDerivativeU.x*uvDerivativeV.y - uvDerivativeU.y*uvDerivativeV.x;
                vec3 fallbackTangent = _lv_safeNormal(cross(abs(normal.y)<0.9 ? vec3(0,1,0) : vec3(1,0,0), normal), vec3(1,0,0));
                vec3 tangent = abs(determinant)>1.0e-12
                    ? (derivativeU*uvDerivativeV.y-derivativeV*uvDerivativeU.y)/determinant : fallbackTangent;
                tangent = _lv_safeNormal(tangent - normal*dot(normal,tangent), fallbackTangent);
                vec3 bitangent = abs(determinant)>1.0e-12
                    ? (derivativeV*uvDerivativeU.x-derivativeU*uvDerivativeV.x)/determinant : cross(tangent,normal);
                float handedness = dot(bitangent,cross(tangent,normal)) < 0.0 ? -1.0 : 1.0;
                vec4 rawColor = _lv_controlColor[0]*weights.x + _lv_controlColor[1]*weights.y
                    + _lv_controlColor[2]*weights.z + _lv_controlColor[3]*weights.w;
                vec2 rawLight = _lv_controlLight[0]*weights.x + _lv_controlLight[1]*weights.y
                    + _lv_controlLight[2]*weights.z + _lv_controlLight[3]*weights.w;
                vec2 midUv = (_lv_controlUv[0]+_lv_controlUv[1]+_lv_controlUv[2]+_lv_controlUv[3])*0.25;
                vec3 midBlock = ((gl_in[0].gl_Position.xyz+gl_in[1].gl_Position.xyz
                    +gl_in[2].gl_Position.xyz+gl_in[3].gl_Position.xyz)*0.25 - rawNormal*0.5 - point)*64.0;
            """);
        for (var input : inputs.entrySet()) {
            String value = switch (input.getKey()) {
                case "iris_Position" -> requireType(input, "vec3", chunkOffset ? "point - iris_ChunkOffset" : "point");
                case "iris_Color" -> requireType(input, "vec4", "rawColor");
                case "iris_UV0" -> requireType(input, "vec2", "rawUv");
                case "iris_UV1" -> vectorValue(input.getValue(), "vec2(0.0)", true);
                case "iris_UV2" -> vectorValue(input.getValue(), "rawLight", true);
                case "iris_Normal" -> requireType(input, "vec3", "normal");
                case "at_tangent" -> requireType(input, "vec4", "vec4(tangent, handedness)");
                case "mc_midTexCoord" -> vectorValue(input.getValue(), "midUv", false);
                case "at_midBlock" -> input.getValue().equals("vec3") ? "midBlock"
                    : requireType(input, "vec4", "vec4(midBlock, 0.0)");
                case "mc_Entity" -> unknownBlockMaterial(input.getValue());
                default -> throw new IllegalArgumentException("Unhandled vertex input " + input.getKey());
            };
            source.append(input.getKey()).append(" = ").append(value).append(";\n");
        }
        source.append("_veil_depth = -(iris_ModelViewMat * vec4(point, 1.0)).z;\n")
            .append("_lv_packVertexMain();\n")
            .append("gl_Position.z -= ").append(depthBias).append(";\n");
        for (AtlasMetadata metadata : atlasMetadata) source.append(metadata.correction());
        return source.append("}\n").toString();
    }

    private static String requireType(Map.Entry<String, String> input, String expected, String expression) {
        if (!input.getValue().equals(expected)) {
            throw new IllegalArgumentException("Unsupported type " + input.getValue() + " for " + input.getKey());
        }
        return expression;
    }

    private static String vectorValue(String type, String expression, boolean integerAllowed) {
        return switch (type) {
            case "vec2" -> expression;
            case "vec3" -> "vec3(" + expression + ", 0.0)";
            case "vec4" -> "vec4(" + expression + ", 0.0, 1.0)";
            case "ivec2" -> {
                if (!integerAllowed) throw new IllegalArgumentException("Unexpected integer UV midpoint");
                yield "ivec2(round(" + expression + "))";
            }
            default -> throw new IllegalArgumentException("Unsupported vertex UV type " + type);
        };
    }

    private static String unknownBlockMaterial(String type) {
        // Iris reserves -1 for an unmapped block. The remaining components follow
        // the ordinary-block attribute convention, including OpenGL's default w=1.
        return switch (type) {
            case "float" -> "-1.0";
            case "vec2" -> "vec2(-1.0, 0.0)";
            case "vec3" -> "vec3(-1.0, 0.0, 0.0)";
            case "vec4" -> "vec4(-1.0, 0.0, 0.0, 1.0)";
            case "int" -> "-1";
            case "ivec2" -> "ivec2(-1, 0)";
            case "ivec3" -> "ivec3(-1, 0, 0)";
            case "ivec4" -> "ivec4(-1, 0, 0, 1)";
            default -> throw new IllegalArgumentException("Unsupported material ID type " + type);
        };
    }

    private static List<AtlasMetadata> findAtlasMetadata(String source) {
        List<AtlasMetadata> metadata = new ArrayList<>();
        Matcher matcher = ATLAS_METADATA.matcher(compact(source));
        while (matcher.find()) {
            metadata.add(new AtlasMetadata(matcher.group("uv"), matcher.group("atlas"),
                matcher.group("size"), matcher.group("min"), matcher.group("local")));
        }
        return metadata;
    }

    private record AtlasMetadata(String uv, String atlas, String size, String min, String local) {
        String correction() {
            return "{\n"
                + "vec2 uv0=(iris_TextureMat*vec4(_lv_controlUv[0],0.0,1.0)).xy;\n"
                + "vec2 uv1=(iris_TextureMat*vec4(_lv_controlUv[1],0.0,1.0)).xy;\n"
                + "vec2 uv2=(iris_TextureMat*vec4(_lv_controlUv[2],0.0,1.0)).xy;\n"
                + "vec2 uv3=(iris_TextureMat*vec4(_lv_controlUv[3],0.0,1.0)).xy;\n"
                + "vec2 quadMin=min(min(uv0,uv1),min(uv2,uv3));\n"
                + "vec2 quadSize=max(max(uv0,uv1),max(uv2,uv3))-quadMin;\n"
                + atlas + "." + size + "=quadSize;\n"
                + atlas + "." + min + "=quadMin;\n"
                + local + "=(" + uv + "-quadMin)/max(quadSize,vec2(1.0e-12));\n}\n";
        }
    }

    private static String compact(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*", "")
            .replaceAll("(?<=\\d)[fF]\\b", "").replaceAll("\\s+", "");
    }

    private static void requireSource(String source, String stage) {
        if (source == null || source.isBlank() || source.contains("#include")) {
            throw new IllegalArgumentException("Missing fully processed " + stage + " source");
        }
    }

    private static final class Parser {
        @SuppressWarnings("rawtypes")
        private final SingleASTTransformer transformer = new SingleASTTransformer();

        private Parser() {
            transformer.setRootSupplier(RootSupplier.PREFIX_UNORDERED_ED_EXACT);
            transformer.getLexer().version = Version.GLSL45;
        }

        private TranslationUnit parse(String source) {
            Matcher matcher = VERSION.matcher(source);
            String normalized = matcher.find() ? matcher.replaceFirst("#version 450 core") : "#version 450 core\n" + source;
            return transformer.parseSeparateTranslationUnit(normalized);
        }

        private void inject(TranslationUnit tree, ASTInjectionPoint point, String source) {
            String[] declarations = parse(source).getChildren().stream()
                .map(ASTPrinter::printSimple).toArray(String[]::new);
            tree.parseAndInjectNodes(transformer, point, declarations);
        }
    }
}
