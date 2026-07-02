package top.leonx.irisveil.compat.light;

import com.google.common.collect.ImmutableSet;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import foundry.veil.api.client.color.Colorc;
import foundry.veil.api.client.registry.LightTypeRegistry;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.light.data.AreaLightData;
import foundry.veil.api.client.render.light.data.PointLightData;
import foundry.veil.api.client.render.light.renderer.LightRenderHandle;
import foundry.veil.impl.client.render.light.VoxelShadowGrid;
import org.joml.Quaternionfc;
import org.joml.Vector2fc;
import org.joml.Vector3dc;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.CameraUniforms;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryStack;
import top.leonx.irisveil.IrisVeilCompat;
import top.leonx.irisveil.api.OrientedOccluders;

import java.nio.FloatBuffer;
import java.util.Collection;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_3D;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_BINDING_3D;
import static org.lwjgl.opengl.GL13C.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE1;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE2;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL14C.*;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL30C.*;


// Draws Veil's deferred point + area (spot) lights under Iris, which disables Veil's own light pass.

public final class IrisVeilLightPass {
    private static final float IRIS_LIGHT_GAIN = 2.0F;
    private static final float VANILLA_LIGHT_GAIN = 1.0F;

    private static final int MAX_POINT_LIGHTS = 64;
    private static final float[] POINT_POS_RADIUS = new float[MAX_POINT_LIGHTS * 4];
    private static final float[] POINT_COLOR = new float[MAX_POINT_LIGHTS * 4];

    private static final int MAX_AREA_LIGHTS = 32;
    private static final float[] AREA_MATRIX = new float[MAX_AREA_LIGHTS * 16]; // Veil's LightMatrix per light
    private static final float[] AREA_COLOR = new float[MAX_AREA_LIGHTS * 4];    // rgb = colour*brightness, a = occluded
    private static final float[] AREA_PARAMS = new float[MAX_AREA_LIGHTS * 4];   // x,y = size; z = angle; w = distance
    private static final Matrix4f AREA_MATRIX_SCRATCH = new Matrix4f();

    private static final int[] VIEWPORT = new int[4];

    private static int program;
    private static int framebuffer;
    private static int vertexArray;
    private static int attachedColorTexture;
    private static Uniforms uniforms;
    private static boolean initializationFailed;
    private static boolean disabled;

    private IrisVeilLightPass() {
    }

    // Iris path: draw into the shaderpack's render targets.
    public static void render(RenderTargets renderTargets, ImmutableSet<Integer> flippedAfterTranslucent) {
        if (disabled) {
            return;
        }
        try {
            RenderSystem.assertOnRenderThread();
            Collection<? extends LightRenderHandle<PointLightData>> pointHandles = points();
            Collection<? extends LightRenderHandle<AreaLightData>> areaHandles = areas();
            if (pointHandles.isEmpty() && areaHandles.isEmpty()) {
                return;
            }

            net.irisshaders.iris.targets.RenderTarget colorTarget = renderTargets.getOrCreate(0);
            int colorTexture = flippedAfterTranslucent.contains(0)
                    ? colorTarget.getAltTexture()
                    : colorTarget.getMainTexture();

            Matrix4f inverseViewProjection = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferProjection())
                    .mul(CapturedRenderingState.INSTANCE.getGbufferModelView())
                    .invert();
            Vector3d camera = CameraUniforms.getUnshiftedCameraPosition();
            renderTo(colorTexture, renderTargets.getDepthTexture(), inverseViewProjection,
                    camera.x, camera.y, camera.z,
                    renderTargets.getCurrentWidth(), renderTargets.getCurrentHeight(),
                    IRIS_LIGHT_GAIN, pointHandles, areaHandles);
        } catch (Throwable t) {
            disabled = true;
            IrisVeilCompat.LOGGER.error("Disabling Iris Veil light pass after an error", t);
        }
    }

    // No-pack path: draw into MC's main framebuffer. No-op if a pack is active. Matrices are this frame's.
    public static void renderVanilla(Matrix4fc projection, Matrix4fc modelView,
                                     double cameraX, double cameraY, double cameraZ) {
        if (disabled || IrisVeilCompat.isShaderPackInUse()) {
            return;
        }
        try {
            RenderSystem.assertOnRenderThread();
            Collection<? extends LightRenderHandle<PointLightData>> pointHandles = points();
            Collection<? extends LightRenderHandle<AreaLightData>> areaHandles = areas();
            if (pointHandles.isEmpty() && areaHandles.isEmpty()) {
                return;
            }

            RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
            Matrix4f inverseViewProjection = new Matrix4f(projection).mul(modelView).invert();
            renderTo(main.getColorTextureId(), main.getDepthTextureId(), inverseViewProjection,
                    cameraX, cameraY, cameraZ, main.width, main.height,
                    VANILLA_LIGHT_GAIN, pointHandles, areaHandles);
        } catch (Throwable t) {
            disabled = true;
            IrisVeilCompat.LOGGER.error("Disabling Iris Veil light pass after an error", t);
        }
    }

    private static Collection<? extends LightRenderHandle<PointLightData>> points() {
        return VeilRenderSystem.renderer().getLightRenderer().getLights(LightTypeRegistry.POINT.get());
    }

    private static Collection<? extends LightRenderHandle<AreaLightData>> areas() {
        return VeilRenderSystem.renderer().getLightRenderer().getLights(LightTypeRegistry.AREA.get());
    }

    private static void renderTo(int colorTexture, int depthTexture, Matrix4fc inverseViewProjection,
                                 double cameraX, double cameraY, double cameraZ, int width, int height,
                                 float lightGain,
                                 Collection<? extends LightRenderHandle<PointLightData>> pointHandles,
                                 Collection<? extends LightRenderHandle<AreaLightData>> areaHandles) {
        ensureInitialized();
        if (initializationFailed || program == 0) {
            return;
        }

        int worldGrid = VoxelShadowGrid.getTextureId();
        Vector3fc worldGridOrigin = VoxelShadowGrid.getUniformGridPos();

        // Save the GL state we touch so rendering keeps working correctly after us.
        int oldFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int oldProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int oldVertexArray = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int oldActiveTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);
        int oldTexture0 = glGetInteger(GL_TEXTURE_BINDING_2D);
        glActiveTexture(GL_TEXTURE1);
        int oldTexture1 = glGetInteger(GL_TEXTURE_BINDING_3D);
        glActiveTexture(GL_TEXTURE2);
        int oldTexture2 = glGetInteger(GL_TEXTURE_BINDING_3D);
        glActiveTexture(oldActiveTexture);
        boolean oldBlend = glIsEnabled(GL_BLEND);
        boolean oldDepthTest = glIsEnabled(GL_DEPTH_TEST);
        boolean oldCull = glIsEnabled(GL_CULL_FACE);
        int oldBlendSrcRgb = glGetInteger(GL_BLEND_SRC_RGB);
        int oldBlendDstRgb = glGetInteger(GL_BLEND_DST_RGB);
        int oldBlendSrcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA);
        int oldBlendDstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
        glGetIntegerv(GL_VIEWPORT, VIEWPORT);

        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
        if (attachedColorTexture != colorTexture) {
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTexture, 0);
            attachedColorTexture = colorTexture;
            if (glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                IrisVeilCompat.LOGGER.error("Iris Veil light framebuffer is incomplete");
                restoreState(oldFramebuffer, oldProgram, oldVertexArray, oldActiveTexture, oldTexture0,
                        oldTexture1, oldTexture2, oldBlend, oldDepthTest, oldCull,
                        oldBlendSrcRgb, oldBlendDstRgb, oldBlendSrcAlpha, oldBlendDstAlpha);
                return;
            }
        }
        glDrawBuffer(GL_COLOR_ATTACHMENT0);
        glViewport(0, 0, width, height);

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendEquation(GL_FUNC_ADD);
        // scene * (1 + light): inject light modulated by the existing surface colour, not a flat add.
        glBlendFuncSeparate(GL_DST_COLOR, GL_ONE, GL_ZERO, GL_ONE);

        glUseProgram(program);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, depthTexture);
        glUniform1i(uniforms.depth, 0);

        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_3D, worldGrid);
        int worldGridSize = worldGrid != 0 ? glGetTexLevelParameteri(GL_TEXTURE_3D, 0, GL_TEXTURE_WIDTH) : 0;
        glUniform1i(uniforms.worldGrid, 1);
        glUniform1i(uniforms.worldGridSize, worldGridSize);
        glUniform3f(uniforms.worldGridOrigin, worldGridOrigin.x(), worldGridOrigin.y(), worldGridOrigin.z());

        int occluderCount = OrientedOccluders.count();
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_3D, occluderCount > 0 ? OrientedOccluders.atlasTextureId() : 0);
        glUniform1i(uniforms.occluderAtlas, 2);
        glUniform1i(uniforms.subCount, occluderCount);
        if (occluderCount > 0) {
            glUniformMatrix4fv(uniforms.subWorldToLocal, false, OrientedOccluders.worldToLocal());
            glUniform4iv(uniforms.subGridInfo, OrientedOccluders.gridInfo());
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer matrix = stack.mallocFloat(16);
            inverseViewProjection.get(matrix);
            glUniformMatrix4fv(uniforms.inverseViewProjection, false, matrix);
        }
        glUniform3f(uniforms.cameraPosition, (float) cameraX, (float) cameraY, (float) cameraZ);
        glUniform2f(uniforms.inverseViewSize, 1.0F / width, 1.0F / height);
        glUniform1f(uniforms.lightGain, lightGain);

        uploadPointLights(pointHandles);
        uploadAreaLights(areaHandles);

        glBindVertexArray(vertexArray);
        glDrawArrays(GL_TRIANGLES, 0, 3);

        restoreState(oldFramebuffer, oldProgram, oldVertexArray, oldActiveTexture, oldTexture0, oldTexture1,
                oldTexture2, oldBlend, oldDepthTest, oldCull, oldBlendSrcRgb, oldBlendDstRgb, oldBlendSrcAlpha,
                oldBlendDstAlpha);
    }

    public static void close() {
        if (program != 0) {
            glDeleteProgram(program);
            program = 0;
        }
        if (framebuffer != 0) {
            glDeleteFramebuffers(framebuffer);
            framebuffer = 0;
        }
        if (vertexArray != 0) {
            glDeleteVertexArrays(vertexArray);
            vertexArray = 0;
        }
        attachedColorTexture = 0;
        uniforms = null;
        initializationFailed = false;
    }

    private static void uploadPointLights(Collection<? extends LightRenderHandle<PointLightData>> handles) {
        int count = 0;
        for (LightRenderHandle<PointLightData> handle : handles) {
            if (!handle.isValid() || count >= MAX_POINT_LIGHTS) {
                continue;
            }
            PointLightData light = handle.getLightData();
            var position = light.getPosition();
            Colorc color = light.getColor();
            float brightness = light.getBrightness();
            int index = count * 4;
            POINT_POS_RADIUS[index] = (float) position.x();
            POINT_POS_RADIUS[index + 1] = (float) position.y();
            POINT_POS_RADIUS[index + 2] = (float) position.z();
            POINT_POS_RADIUS[index + 3] = light.getRadius();
            POINT_COLOR[index] = color.red() * brightness;
            POINT_COLOR[index + 1] = color.green() * brightness;
            POINT_COLOR[index + 2] = color.blue() * brightness;
            POINT_COLOR[index + 3] = light.isOcclusionEnabled() ? 1.0F : 0.0F;
            count++;
        }
        glUniform1i(uniforms.pointCount, count);
        if (count > 0) {
            glUniform4fv(uniforms.pointPositionRadius, POINT_POS_RADIUS);
            glUniform4fv(uniforms.pointColor, POINT_COLOR);
        }
    }

    // Upload the same LightMatrix Veil packs (rotate by orientation, then translate to world pos) + size, cone
    // angle, range, so the shader matches Veil's area.fsh. Colour premultiplied by brightness, like points.
    private static void uploadAreaLights(Collection<? extends LightRenderHandle<AreaLightData>> handles) {
        int count = 0;
        for (LightRenderHandle<AreaLightData> handle : handles) {
            if (!handle.isValid() || count >= MAX_AREA_LIGHTS) {
                continue;
            }
            AreaLightData light = handle.getLightData();
            Vector3dc position = light.getPosition();
            Quaternionfc orientation = light.getOrientation();
            Vector2fc size = light.getSize();
            Colorc color = light.getColor();
            float brightness = light.getBrightness();

            // Matches AreaLightData#store: identity, rotate by orientation, translate to the world position.
            AREA_MATRIX_SCRATCH.identity()
                    .rotate(orientation)
                    .translate((float) position.x(), (float) position.y(), (float) position.z());
            AREA_MATRIX_SCRATCH.get(AREA_MATRIX, count * 16);

            int c4 = count * 4;
            AREA_COLOR[c4] = color.red() * brightness;
            AREA_COLOR[c4 + 1] = color.green() * brightness;
            AREA_COLOR[c4 + 2] = color.blue() * brightness;
            AREA_COLOR[c4 + 3] = light.isOcclusionEnabled() ? 1.0F : 0.0F;
            AREA_PARAMS[c4] = size.x();
            AREA_PARAMS[c4 + 1] = size.y();
            AREA_PARAMS[c4 + 2] = light.getAngle();     // cone half-angle, radians
            AREA_PARAMS[c4 + 3] = light.getDistance();  // range
            count++;
        }
        glUniform1i(uniforms.areaCount, count);
        if (count > 0) {
            glUniformMatrix4fv(uniforms.areaMatrix, false, AREA_MATRIX);
            glUniform4fv(uniforms.areaColor, AREA_COLOR);
            glUniform4fv(uniforms.areaParams, AREA_PARAMS);
        }
    }

    private static void ensureInitialized() {
        if (program != 0 || initializationFailed) {
            return;
        }
        int vertexShader = compileShader(GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShader = compileShader(GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        if (vertexShader == 0 || fragmentShader == 0) {
            initializationFailed = true;
            return;
        }
        program = glCreateProgram();
        glAttachShader(program, vertexShader);
        glAttachShader(program, fragmentShader);
        glLinkProgram(program);
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            IrisVeilCompat.LOGGER.error("Failed to link Iris Veil light shader: {}", glGetProgramInfoLog(program));
            glDeleteProgram(program);
            program = 0;
            initializationFailed = true;
            return;
        }
        framebuffer = glGenFramebuffers();
        vertexArray = glGenVertexArrays();
        uniforms = Uniforms.create(program);
    }

    private static int compileShader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            IrisVeilCompat.LOGGER.error("Failed to compile Iris Veil light shader: {}", glGetShaderInfoLog(shader));
            glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private static void restoreState(int framebufferId, int programId, int vertexArrayId, int activeTexture,
                                     int texture0, int texture1, int texture2, boolean blend, boolean depthTest,
                                     boolean cull, int blendSrcRgb, int blendDstRgb, int blendSrcAlpha,
                                     int blendDstAlpha) {
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebufferId);
        glUseProgram(programId);
        glBindVertexArray(vertexArrayId);
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_3D, texture2);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_3D, texture1);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture0);
        glActiveTexture(activeTexture);
        glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
        glViewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
        setEnabled(GL_BLEND, blend);
        setEnabled(GL_DEPTH_TEST, depthTest);
        setEnabled(GL_CULL_FACE, cull);
    }

    private static void setEnabled(int capability, boolean enabled) {
        if (enabled) {
            glEnable(capability);
        } else {
            glDisable(capability);
        }
    }

    private static final class Uniforms {
        final int depth;
        final int worldGrid;
        final int worldGridOrigin;
        final int worldGridSize;
        final int occluderAtlas;
        final int inverseViewProjection;
        final int cameraPosition;
        final int inverseViewSize;
        final int lightGain;
        final int pointCount;
        final int pointPositionRadius;
        final int pointColor;
        final int areaCount;
        final int areaMatrix;
        final int areaColor;
        final int areaParams;
        final int subCount;
        final int subWorldToLocal;
        final int subGridInfo;

        private Uniforms(int program) {
            this.depth = glGetUniformLocation(program, "uDepth");
            this.worldGrid = glGetUniformLocation(program, "uWorldGrid");
            this.worldGridOrigin = glGetUniformLocation(program, "uWorldGridOrigin");
            this.worldGridSize = glGetUniformLocation(program, "uWorldGridSize");
            this.occluderAtlas = glGetUniformLocation(program, "uOccluderAtlas");
            this.inverseViewProjection = glGetUniformLocation(program, "uInverseViewProjection");
            this.cameraPosition = glGetUniformLocation(program, "uCameraPosition");
            this.inverseViewSize = glGetUniformLocation(program, "uInverseViewSize");
            this.lightGain = glGetUniformLocation(program, "uLightGain");
            this.pointCount = glGetUniformLocation(program, "uPointCount");
            this.pointPositionRadius = glGetUniformLocation(program, "uPointPositionRadius");
            this.pointColor = glGetUniformLocation(program, "uPointColor");
            this.areaCount = glGetUniformLocation(program, "uAreaCount");
            this.areaMatrix = glGetUniformLocation(program, "uAreaMatrix");
            this.areaColor = glGetUniformLocation(program, "uAreaColor");
            this.areaParams = glGetUniformLocation(program, "uAreaParams");
            this.subCount = glGetUniformLocation(program, "uSubCount");
            this.subWorldToLocal = glGetUniformLocation(program, "uSubWorldToLocal");
            this.subGridInfo = glGetUniformLocation(program, "uSubGridInfo");
        }

        private static Uniforms create(int program) {
            return new Uniforms(program);
        }
    }

    private static final String VERTEX_SHADER = """
            #version 150
            out vec2 vUv;
            void main() {
                vec2 p = vec2((gl_VertexID == 1) ? 3.0 : -1.0, (gl_VertexID == 2) ? 3.0 : -1.0);
                vUv = p * 0.5 + 0.5;
                gl_Position = vec4(p, 0.0, 1.0);
            }
            """;

    private static final String FRAGMENT_SHADER = """
            #version 150

            const int MAX_POINT_LIGHTS = 64;
            const int MAX_AREA_LIGHTS = 32;
            const int MAX_SUB_OCCLUDERS = 16;
            const int WORLD_MAX_STEPS = 128;
            const float MC_AMBIENT_LIGHT = 0.4;

            uniform sampler2D uDepth;
            uniform sampler3D uWorldGrid;
            uniform sampler3D uOccluderAtlas;
            uniform mat4 uInverseViewProjection;
            uniform vec3 uCameraPosition;
            uniform vec2 uInverseViewSize;
            uniform float uLightGain;

            uniform vec3 uWorldGridOrigin;
            uniform int uWorldGridSize;

            uniform int uPointCount;
            uniform vec4 uPointPositionRadius[MAX_POINT_LIGHTS];
            uniform vec4 uPointColor[MAX_POINT_LIGHTS];

            uniform int uAreaCount;
            uniform mat4 uAreaMatrix[MAX_AREA_LIGHTS];
            uniform vec4 uAreaColor[MAX_AREA_LIGHTS];
            uniform vec4 uAreaParams[MAX_AREA_LIGHTS];

            uniform int uSubCount;
            uniform mat4 uSubWorldToLocal[MAX_SUB_OCCLUDERS];
            uniform ivec4 uSubGridInfo[MAX_SUB_OCCLUDERS];

            in vec2 vUv;
            out vec4 fragColor;

            vec3 worldFromDepth(vec2 uv, float depth) {
                vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
                vec4 world = uInverseViewProjection * clip;
                return world.xyz / world.w + uCameraPosition;
            }

            float worldShadowVisibility(vec3 fragPos, vec3 lightPos) {
                if (uWorldGridSize <= 0) return 1.0;
                vec3 startG = fragPos - uWorldGridOrigin;
                vec3 endG = lightPos - uWorldGridOrigin;
                vec3 delta = endG - startG;
                float rayLen = length(delta);
                if (rayLen < 0.001) return 1.0;

                vec3 rDir = delta / rayLen;
                ivec3 cell = ivec3(floor(startG));
                ivec3 iStep = ivec3(sign(rDir));
                vec3 invAbs = 1.0 / max(abs(rDir), vec3(1e-5));
                vec3 tDelta = invAbs;

                vec3 cellF = vec3(cell);
                vec3 tMax;
                tMax.x = (rDir.x >= 0.0) ? (cellF.x + 1.0 - startG.x) * invAbs.x : (startG.x - cellF.x) * invAbs.x;
                tMax.y = (rDir.y >= 0.0) ? (cellF.y + 1.0 - startG.y) * invAbs.y : (startG.y - cellF.y) * invAbs.y;
                tMax.z = (rDir.z >= 0.0) ? (cellF.z + 1.0 - startG.z) * invAbs.z : (startG.z - cellF.z) * invAbs.z;

                for (int i = 0; i < WORLD_MAX_STEPS; i++) {
                    if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, ivec3(uWorldGridSize)))) break;
                    if (i > 0 && texelFetch(uWorldGrid, cell, 0).r > 0.5) return 0.0;

                    if (tMax.x < tMax.y && tMax.x < tMax.z) {
                        if (tMax.x >= rayLen) break;
                        tMax.x += tDelta.x;
                        cell.x += iStep.x;
                    } else if (tMax.y < tMax.z) {
                        if (tMax.y >= rayLen) break;
                        tMax.y += tDelta.y;
                        cell.y += iStep.y;
                    } else {
                        if (tMax.z >= rayLen) break;
                        tMax.z += tDelta.z;
                        cell.z += iStep.z;
                    }
                }
                return 1.0;
            }

            float subLevelShadow(int i, vec3 surfaceWorld, vec3 normalWorld, vec3 lightWorld) {
                mat4 toLocal = uSubWorldToLocal[i];
                ivec3 isize = uSubGridInfo[i].xyz;
                vec3 size = vec3(isize);
                int zOffset = uSubGridInfo[i].w;

                vec3 startG = (toLocal * vec4(surfaceWorld, 1.0)).xyz;
                vec3 endG = (toLocal * vec4(lightWorld, 1.0)).xyz;

                vec3 nLocal = (toLocal * vec4(normalWorld, 0.0)).xyz;
                float nLen = length(nLocal);

                vec3 delta = endG - startG;
                float rayLen = length(delta);
                if (rayLen < 1e-4) return 1.0;
                vec3 rDir = delta / rayLen;

                vec3 nDir = nLen > 1e-6 ? nLocal / nLen : rDir;
                if (dot(nDir, rDir) < 0.0) nDir = -nDir;
                vec3 rSafe = max(abs(rDir), vec3(1e-6)) * sign(rDir + vec3(1e-9));
                vec3 inv = 1.0 / rSafe;
                vec3 invAbs = abs(inv);

                vec3 t0 = (vec3(0.0) - startG) * inv;
                vec3 t1 = (size - startG) * inv;
                vec3 tlo = min(t0, t1);
                vec3 thi = max(t0, t1);
                float tEnter = max(max(tlo.x, max(tlo.y, tlo.z)), 0.0);
                float tExit = min(min(thi.x, min(thi.y, thi.z)), rayLen);
                if (tEnter >= tExit) return 1.0;

                vec3 p = startG + rDir * (tEnter + 1e-4);
                ivec3 cell = clamp(ivec3(floor(p)), ivec3(0), isize - ivec3(1));
                ivec3 iStep = ivec3(sign(rSafe));
                vec3 cellF = vec3(cell);
                vec3 tMax;
                tMax.x = (iStep.x > 0 ? cellF.x + 1.0 - p.x : p.x - cellF.x) * invAbs.x;
                tMax.y = (iStep.y > 0 ? cellF.y + 1.0 - p.y : p.y - cellF.y) * invAbs.y;
                tMax.z = (iStep.z > 0 ? cellF.z + 1.0 - p.z : p.z - cellF.z) * invAbs.z;
                float limit = tExit - tEnter;

                for (int s = 0; s < 256; s++) {
                    if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, isize))) break;
                    if (texelFetch(uOccluderAtlas, ivec3(cell.x, cell.y, cell.z + zOffset), 0).r > 0.5
                            && dot(vec3(cell) + 0.5 - startG, nDir) > 0.25) {
                        return 0.0;
                    }
                    if (tMax.x < tMax.y && tMax.x < tMax.z) {
                        if (tMax.x > limit) break;
                        tMax.x += invAbs.x;
                        cell.x += iStep.x;
                    } else if (tMax.y < tMax.z) {
                        if (tMax.y > limit) break;
                        tMax.y += invAbs.y;
                        cell.y += iStep.y;
                    } else {
                        if (tMax.z > limit) break;
                        tMax.z += invAbs.z;
                        cell.z += iStep.z;
                    }
                }
                return 1.0;
            }

            float occluderVisibility(vec3 worldPosition, vec3 normal, vec3 lightPosition) {
                float visibility = 1.0;
                for (int i = 0; i < MAX_SUB_OCCLUDERS; i++) {
                    if (i >= uSubCount) break;
                    visibility *= subLevelShadow(i, worldPosition, normal, lightPosition);
                    if (visibility <= 0.001) return 0.0;
                }
                return visibility;
            }

            float attenuateNoCusp(float dist, float radius) {
                float s = dist / radius;
                if (s >= 1.0) return 0.0;
                float oneMinusS = 1.0 - s;
                return oneMinusS * oneMinusS * oneMinusS;
            }

            float sacos(float x) {
                float y = abs(clamp(x, -1.0, 1.0));
                float z = (-0.168577 * y + 1.56723) * sqrt(1.0 - y);
                return mix(0.5 * 3.1415927, z, sign(x));
            }

            void areaClosest(vec3 point, mat4 planeMatrix, vec2 planeSize, out vec3 outPos, out float outAngle) {
                planeMatrix[3].xyz *= -1.0;
                vec3 localSpacePoint = (planeMatrix * vec4(point, 1.0)).xyz;
                vec3 localSpacePointOnPlane = vec3(clamp(localSpacePoint.xy, -planeSize, planeSize), 0.0);
                vec3 direction = normalize(localSpacePoint - localSpacePointOnPlane);
                outAngle = sacos(dot(direction, vec3(0.0, 0.0, 1.0)));
                outPos = (inverse(planeMatrix) * vec4(localSpacePointOnPlane, 1.0)).xyz;
            }

            void main() {
                float depth = texture(uDepth, vUv).r;
                if (depth >= 1.0) {
                    discard;
                }
                vec3 worldPosition = worldFromDepth(vUv, depth);
                vec3 dx = dFdx(worldPosition);
                vec3 dy = dFdy(worldPosition);
                vec3 normal = normalize(cross(dx, dy));
                if (dot(normal, uCameraPosition - worldPosition) < 0.0) {
                    normal = -normal;
                }

                vec3 lightSum = vec3(0.0);
                for (int i = 0; i < MAX_POINT_LIGHTS; i++) {
                    if (i >= uPointCount) break;
                    vec3 lightPos = uPointPositionRadius[i].xyz;
                    vec3 delta = lightPos - worldPosition;
                    float radius = uPointPositionRadius[i].w;
                    float dist = length(delta);
                    if (dist <= 1e-4 || dist >= radius) continue;
                    float falloff = 1.0 - dist / radius;
                    falloff *= falloff;
                    vec3 toLight = delta / dist;
                    float lambert = 0.25 + 0.75 * max(dot(normal, toLight), 0.0);
                    float visibility = 1.0;
                    if (uPointColor[i].a > 0.5) {
                        visibility = occluderVisibility(worldPosition, normal, lightPos);
                        if (visibility > 0.001) {
                            visibility *= worldShadowVisibility(worldPosition + normal * 0.02, lightPos);
                        }
                    }
                    lightSum += uPointColor[i].rgb * (falloff * lambert * visibility);
                }

                for (int i = 0; i < MAX_AREA_LIGHTS; i++) {
                    if (i >= uAreaCount) break;
                    vec2 size = uAreaParams[i].xy;
                    float maxAngle = uAreaParams[i].z;
                    float maxDistance = uAreaParams[i].w;
                    if (maxAngle <= 1e-4 || maxDistance <= 1e-4) continue;

                    vec3 lightPos;
                    float angle;
                    areaClosest(worldPosition, uAreaMatrix[i], size, lightPos, angle);

                    vec3 offset = lightPos - worldPosition;
                    float dist = length(offset);
                    float atten = attenuateNoCusp(dist, maxDistance);
                    if (atten <= 0.0) continue;

                    vec3 toLight = dist > 1e-5 ? offset / dist : normal;
                    float diffuse = (dot(normal, toLight) + 1.0) * 0.5;
                    diffuse = (diffuse + MC_AMBIENT_LIGHT) / (1.0 + MC_AMBIENT_LIGHT);
                    diffuse *= atten;
                    float angleFalloff = smoothstep(1.0, 0.0, clamp(angle, 0.0, maxAngle) / maxAngle);
                    diffuse *= angleFalloff;
                    if (diffuse <= 0.0) continue;

                    float visibility = 1.0;
                    if (uAreaColor[i].a > 0.5) {
                        visibility = occluderVisibility(worldPosition, normal, lightPos);
                        if (visibility > 0.001) {
                            visibility *= worldShadowVisibility(worldPosition + normal * 0.02, lightPos);
                        }
                    }
                    lightSum += uAreaColor[i].rgb * (diffuse * visibility);
                }

                fragColor = vec4(lightSum * uLightGain, 0.0);
            }
            """;
}
