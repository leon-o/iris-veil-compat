package top.leonx.irisveil.compat.veil;

import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.douira.glsl_transformer.GLSLParser;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.Version;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.Expression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.ExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.statement.CompoundStatement;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinNumericTypeSpecifier;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.match.AutoHintedMatcher;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.query.RootSupplier;
import io.github.douira.glsl_transformer.ast.transform.ASTBuilder;
import io.github.douira.glsl_transformer.ast.transform.JobParameters;
import io.github.douira.glsl_transformer.ast.transform.SingleASTTransformer;
import io.github.douira.glsl_transformer.parser.ParseShape;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import top.leonx.irisveil.IrisVeilCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Patches an Iris shaderpack block vertex shader with Veil vertex code,
 * following the Flywheel compat pattern.
 *
 * <p>Flywheel injects its own computation (using Flywheel-specific matrices
 * like {@code flw_viewProjection}) into the Iris vertex shader, then replaces
 * Iris's {@code gl_Vertex} with Flywheel's result.</p>
 *
 * <p>For Veil, we:
 * <ol>
 *   <li>Parse Veil vertex shader, rename functions with {@code _veil_} prefix</li>
 *   <li>Inject renamed Veil function declarations into Iris tree</li>
 *   <li>Inject Veil main body (using renamed functions) at start of Iris main,
 *       wrapped in a block scope</li>
 *   <li>The Veil code uses Iris's own matrices/attributes — we don't declare
 *       Veil-specific uniforms (ModelViewMat, etc.) because they'd be unset.
 *       Instead, we replace Veil's matrix/attribute names with Iris equivalents
 *       at the AST level before injection.</li>
 *   <li>Veil's computation runs first, computing results into global variables.
 *       Iris code then uses these variables (via {@code gl_Vertex} replacement).</li>
 *   <li>Remove Iris extension attributes, replace with zeros.</li>
 * </ol>
 */
public class GlslTransformerVeilPatcher {
    private static final String VEIL_MODEL_VERTEX = "_veil_modelVertex";
    private static final String VEIL_CLIP_POSITION = "_veil_clipPosition";
//    private static final String VEIL_COLOR = "_veil_Color";

    private static final ParseShape<GLSLParser.CompoundStatementContext, CompoundStatement> COMPOUND_STATEMENT_SHAPE =
        new ParseShape<>(
            GLSLParser.CompoundStatementContext.class,
            GLSLParser::compoundStatement,
            ASTBuilder::visitCompoundStatement);
    private static final AutoHintedMatcher<Expression> FTRANSFORM_EXPR =
        new AutoHintedMatcher<>("ftransform()", ParseShape.EXPRESSION);

    private static final Set<String> IRIS_EXTENSION_ATTRIBUTES = Set.of(
        "at_tangent", "mc_Entity", "mc_midTexCoord", "at_midBlock"
    );

    private static final Map<String, String> DEFAULT_REPLACEMENTS = Map.of(
        "at_tangent",    "vec4(1.0, 0.0, 0.0, 1.0)",
        "mc_Entity",     "vec2(0.0)",
        "mc_midTexCoord", "vec4(0.0, 0.0, 0.0, 1.0)",
        "at_midBlock",   "vec4(0.0)"
    );

    // Veil-owned functions and varyings to prefix. Bound uniforms keep their original
    // names so Veil's uniform setters can still find them in the linked program.
    private static final Set<String> VEIL_RENAME = Set.of(
        "fog_distance", "minecraft_mix_light", "minecraft_sample_lightmap",
        "block_brightness", "getVelocity", "linear_fog",
        "vertexDistance", "vertexColor", "lightMapColor", "overlayColor", "texCoord0",
        "lengthData", "vertexLight"
    );

    // Veil attribute/uniform names to SKIP copying (Iris provides these)
    // and to REPLACE in Veil code with Iris equivalents
    private static final Map<String, String> VEIL_TO_IRIS = Map.of(
        "ModelViewMat", "gl_ModelViewMatrix",
        "ProjMat",       "gl_ProjectionMatrix",
        "NormalMat",     "gl_NormalMatrix",
        "Position",      "gl_Vertex.xyz",
        "Color",         "gl_Color",
        "UV0",           "gl_MultiTexCoord0.xy",
        "UV2",           "ivec2(gl_MultiTexCoord1.xy)",
        "Normal",        "gl_Normal",
        "Light0_Direction", "gl_Normal",
        "Light1_Direction", "gl_Normal"
    );

    private static final Set<String> LIGHTMAP_SAMPLE_FUNCTIONS = Set.of(
        "texture", "texture2D", "texelFetch",
        "minecraft_sample_lightmap", "_veil_minecraft_sample_lightmap"
    );

    private static final Pattern VERSION_PATTERN =
        Pattern.compile("^.*#version\\s+(\\d+)(\\s+\\w+)?", Pattern.DOTALL);

    private final SingleASTTransformer<VeilPatchParams> transformer;
    private final SingleASTTransformer<VeilPatchParams> veilTransformer;

    public GlslTransformerVeilPatcher() {
        transformer = new SingleASTTransformer<>() {
            {
                setRootSupplier(RootSupplier.PREFIX_UNORDERED_ED_EXACT);
            }

            @Override
            public TranslationUnit parseTranslationUnit(Root rootInstance, String input) {
                java.util.regex.Matcher matcher = VERSION_PATTERN.matcher(input);
                if (!matcher.find()) {
                    throw new IllegalArgumentException("No #version directive found in source code!");
                }
                int originalVersion = Integer.parseInt(matcher.group(1));
                int finalVersion = Math.max(originalVersion, 400);
                String newVersionLine = "#version " + finalVersion + " compatibility\n";
                input = matcher.replaceAll(newVersionLine);
                transformer.getLexer().version = Version.fromNumber(finalVersion);
                return super.parseTranslationUnit(rootInstance, input);
            }
        };

        transformer.setTransformation(this::transform);

        veilTransformer = new SingleASTTransformer<>();
        veilTransformer.setRootSupplier(RootSupplier.PREFIX_UNORDERED_ED_EXACT);
        veilTransformer.getLexer().version = Version.GLSL40;
    }

    public String patch(String irisVertexSource, String veilVertexSource, VertexFormat targetFormat, String shaderName) {
        if (irisVertexSource == null || targetFormat == null) return irisVertexSource;
        try {
            String result = transformer.transform(irisVertexSource,
                new VeilPatchParams(veilVertexSource, targetFormat));
            return result;
        } catch (Exception e) {
            IrisVeilCompat.LOGGER.error("IrisVeilPatcher: AST transform failed, falling back to original", e);
            return irisVertexSource;
        }
    }

    private void transform(TranslationUnit irisTree, Root irisRoot, VeilPatchParams params) {
        String veilSource = params.veilVertexSource;
        if (veilSource == null || veilSource.isEmpty()) {
            removeExtensionAttributes(irisRoot, new HashMap<>());
            for (var e : DEFAULT_REPLACEMENTS.entrySet())
                irisRoot.replaceReferenceExpressions(transformer, e.getKey(), e.getValue());
            return;
        }

        // Step 1: Parse Veil tree
        String processed = JcppProcessor.glslPreprocessSource(veilSource,
            List.of(new StringPair("VEIL_VERTEX", "1")));
        TranslationUnit veilTree = veilTransformer.parseSeparateTranslationUnit(processed);

        // Step 2: Replace Veil matrix/attribute names with Iris equivalents
        // (ModelViewMat → gl_ModelViewMatrix, Position → gl_Vertex, etc.)
        // These resolve to Iris's correctly-set uniforms/attributes after TransformPatcher
        var veilRoot = veilTree.getRoot();
        for (var entry : VEIL_TO_IRIS.entrySet()) {
            veilRoot.replaceReferenceExpressions(veilTransformer, entry.getKey(), entry.getValue());
        }

        // Step 3: Rename Veil functions and Veil-specific varyings
        // (fog_distance → _veil_fog_distance, etc.) to avoid Iris conflicts
        renameVeilIdentifiers(veilRoot);
        neutralizeVeilLightmapSamples(veilRoot);

        // Step 4: Extract main body and non-main declarations
        var veilMainBody = veilTree.getOneMainDefinitionBody();
        if (veilMainBody == null) {
            IrisVeilCompat.LOGGER.warn("IrisVeilPatcher: no main() in Veil shader");
            return;
        }
        var mainFuncDecl = veilMainBody.getAncestor(ExternalDeclaration.class);

        // Step 5: Inject Veil declarations (functions, Veil-specific uniforms/varyings)
        // into Iris tree, BEFORE Iris declarations
        injectVeilDeclarations(irisTree, irisRoot, veilTree, mainFuncDecl);

        // Step 6: Route Iris's later vertex reads through Veil's computed model-space vertex.
        // This mirrors Flywheel: Veil computes first, then Iris/Photon consumes the result.
        irisRoot.replaceReferenceExpressions(transformer, "gl_Color", "vec4(1.0)");
        irisTree.parseAndInjectNode(transformer, ASTInjectionPoint.BEFORE_DECLARATIONS,
            "vec4 " + VEIL_MODEL_VERTEX + ";");
        irisTree.parseAndInjectNode(transformer, ASTInjectionPoint.BEFORE_DECLARATIONS,
            "vec4 " + VEIL_CLIP_POSITION + ";");
//        irisTree.parseAndInjectNode(transformer, ASTInjectionPoint.BEFORE_DECLARATIONS,
//            "vec4 " + VEIL_COLOR + ";");
        irisRoot.replaceReferenceExpressions(transformer, "gl_Vertex", VEIL_MODEL_VERTEX);
        irisRoot.replaceExpressionMatches(transformer, FTRANSFORM_EXPR,
            "gl_ProjectionMatrix * gl_ModelViewMatrix * " + VEIL_MODEL_VERTEX);

        // Step 7: Inject Veil main body at START of Iris main, in a fresh block scope.
        // Re-parsing avoids moving AST nodes between roots, which glsl-transformer rejects.
        CompoundStatement veilBlock = parseVeilMainBlock(veilMainBody);
        irisTree.prependMainFunctionBody(veilBlock.getStatements());

        // Step 8: Remove Iris extension attributes
        var dims = new HashMap<String, Integer>();
        removeExtensionAttributes(irisRoot, dims);
        for (var e : DEFAULT_REPLACEMENTS.entrySet())
            irisRoot.replaceReferenceExpressions(transformer, e.getKey(), e.getValue());

        IrisVeilCompat.LOGGER.debug("IrisVeilPatcher: injected Veil shader (Flywheel pattern)");
    }

    private CompoundStatement parseVeilMainBlock(CompoundStatement veilMainBody) {
        String blockSource = "{\n"
//            + VEIL_COLOR + " = gl_Color;\n"
            + ASTPrinter.printSimple(veilMainBody)
            + "\n"
            + VEIL_CLIP_POSITION + " = gl_Position;\n"
            + VEIL_MODEL_VERTEX + " = inverse(gl_ProjectionMatrix * gl_ModelViewMatrix) * gl_Position;\n"
            + "}";

        return veilTransformer.parseNodeSeparate(
            veilTransformer.getRootSupplier(),
            COMPOUND_STATEMENT_SHAPE,
            blockSource);
    }

    private void renameVeilIdentifiers(Root veilRoot) {
        for (String name : VEIL_RENAME) {
            veilRoot.rename(name, "_veil_" + name);
        }
    }

    private void neutralizeVeilLightmapSamples(Root veilRoot) {
        var calls = new ArrayList<Expression>();
        veilRoot.nodeIndex.getStream(FunctionCallExpression.class)
            .filter(this::isLightmapSampleCall)
            .forEach(calls::add);

        if (!calls.isEmpty()) {
            veilRoot.replaceExpressions(veilTransformer, calls.stream(), "vec4(1.0)");
        }
    }

    private boolean isLightmapSampleCall(FunctionCallExpression call) {
        var functionName = call.getFunctionName();
        if (functionName == null || !LIGHTMAP_SAMPLE_FUNCTIONS.contains(functionName.getName())) {
            return false;
        }
        if (call.getParameters().isEmpty()) {
            return false;
        }

        String sampler = ASTPrinter.printSimple(call.getParameters().getFirst()).trim();
        return "Sampler2".equals(sampler);
    }

    /**
     * Copies Veil declarations into Iris tree, EXCEPT:
     * - main() function (injected separately)
     * - Declarations whose member names are in VEIL_TO_IRIS keys
     *   (ModelViewMat, Position, etc. — Iris provides these)
     */
    private void injectVeilDeclarations(TranslationUnit irisTree, Root irisRoot, TranslationUnit veilTree,
                                         ExternalDeclaration mainFunc) {
        var decls = new java.util.ArrayList<ExternalDeclaration>();
        for (var child : veilTree.getChildren()) {
            if (child == mainFunc) continue;
            if (child instanceof DeclarationExternalDeclaration dex) {
                if (dex.getDeclaration() instanceof TypeAndInitDeclaration t) {
                    boolean skip = t.getMembers().stream()
                        .anyMatch(m -> {
                            String name = m.getName().getName();
                            return VEIL_TO_IRIS.containsKey(name) || hasGlobalDeclaration(irisRoot, name);
                        });
                    if (skip) continue;
                }
            }
            decls.add(child);
        }
        if (!decls.isEmpty()) {
            irisTree.injectNodes(ASTInjectionPoint.BEFORE_DECLARATIONS, decls);
        }
    }

    private static void removeExtensionAttributes(Root root, Map<String, Integer> dims) {
        root.process(
            root.nodeIndex.getStream(DeclarationExternalDeclaration.class).distinct(),
            node -> {
                if (node.getDeclaration() instanceof TypeAndInitDeclaration t) {
                    var found = t.getMembers().stream()
                        .filter(m -> IRIS_EXTENSION_ATTRIBUTES.contains(m.getName().getName()))
                        .findAny();
                    if (found.isPresent()) {
                        if (t.getType().getTypeSpecifier() instanceof BuiltinNumericTypeSpecifier s) {
                            var d = s.type.getDimensions();
                            dims.put(found.get().getName().getName(), d.length > 0 ? d[0] : 1);
                        }
                        node.detachAndDelete();
                    }
                }
            }
        );
    }

    private static boolean hasGlobalDeclaration(Root root, String name) {
        return root.nodeIndex.getStream(DeclarationExternalDeclaration.class).distinct()
            .anyMatch(node -> {
                if (node.getDeclaration() instanceof TypeAndInitDeclaration t) {
                    return t.getMembers().stream().anyMatch(m -> name.equals(m.getName().getName()));
                }
                return false;
            });
    }

    public static class VeilPatchParams implements JobParameters {
        public final String veilVertexSource;
        public final VertexFormat targetFormat;
        public VeilPatchParams(String s, VertexFormat f) { veilVertexSource = s; targetFormat = f; }
    }
}
