package top.leonx.irisveil.compat.sable;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Objects;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

import top.leonx.irisveil.IrisVeilCompat;

public final class SableShadowCompat {
    private static final String SUB_LEVEL_CONTAINER_CLASS = "dev.ryanhcode.sable.api.sublevel.SubLevelContainer";
    private static final String SUB_LEVEL_RENDER_DISPATCHER_CLASS = "dev.ryanhcode.sable.sublevel.render.dispatcher.SubLevelRenderDispatcher";
    private static final String SABLE_BLOCK_ENTITY_RENDERER_CLASS = "dev.ryanhcode.sable.mixinhelpers.sublevel_render.vanilla.VanillaSubLevelBlockEntityRenderer";
    private static final String FASTUTIL_LONG_MAP_CLASS = "it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap";
    private static final String POSE_STACK_CLASS = "com.mojang.blaze3d.vertex.PoseStack";

    private static boolean unavailable;
    private static boolean unavailableLogged;
    private static boolean failureLogged;

    private SableShadowCompat() {
    }

    public static boolean renderSubLevelBlockEntities(
            Object level,
            Object renderBuffers,
            Object blockEntityRenderDispatcher,
            Object shadowModelView,
            double cameraX,
            double cameraY,
            double cameraZ,
            float partialTick) {
        if (unavailable) {
            return false;
        }

        try {
            Object vanillaRenderer = createVanillaBlockEntityRenderer(blockEntityRenderDispatcher, renderBuffers);
            Object renderer = shadowModelView instanceof Matrix4f matrix
                ? createShadowModelViewRenderer(vanillaRenderer, blockEntityRenderDispatcher, matrix)
                : vanillaRenderer;

            return dispatchSubLevelBlockEntities(level, renderer, cameraX, cameraY, cameraZ, partialTick);
        } catch (ClassNotFoundException e) {
            logUnavailable();
            return false;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            logFailure(e);
            return false;
        }
    }

    static boolean renderSubLevelBlockEntities(
            Object level,
            Object renderBuffers,
            Object blockEntityRenderDispatcher,
            double cameraX,
            double cameraY,
            double cameraZ,
            float partialTick) {
        return renderSubLevelBlockEntities(
            level,
            renderBuffers,
            blockEntityRenderDispatcher,
            null,
            cameraX,
            cameraY,
            cameraZ,
            partialTick);
    }

    private static boolean dispatchSubLevelBlockEntities(
            Object level,
            Object blockEntityRenderer,
            double cameraX,
            double cameraY,
            double cameraZ,
        float partialTick) throws ReflectiveOperationException {
        Class<?> containerClass = Class.forName(SUB_LEVEL_CONTAINER_CLASS);
        Method getContainer = findCompatibleMethod(containerClass, "getContainer", true, level);
        Object container = getContainer.invoke(null, level);
        if (container == null) {
            return false;
        }

        Method getAllSubLevels = findMethod(container.getClass(), "getAllSubLevels", 0, false);
        Object subLevels = getAllSubLevels.invoke(container);
        if (!(subLevels instanceof Iterable<?>)) {
            return false;
        }

        Class<?> dispatcherClass = Class.forName(SUB_LEVEL_RENDER_DISPATCHER_CLASS);
        Method getDispatcher = findMethod(dispatcherClass, "get", 0, true);
        Object dispatcher = getDispatcher.invoke(null);
        Method renderBlockEntities = findMethod(dispatcher.getClass(), "renderBlockEntities", 6, false);
        renderBlockEntities.invoke(dispatcher, subLevels, blockEntityRenderer, cameraX, cameraY, cameraZ, partialTick);
        return true;
    }

    private static Object createVanillaBlockEntityRenderer(
            Object blockEntityRenderDispatcher,
            Object renderBuffers) throws ReflectiveOperationException {
        Class<?> rendererClass = Class.forName(SABLE_BLOCK_ENTITY_RENDERER_CLASS);
        Constructor<?> constructor = findConstructor(rendererClass, 3);
        return constructor.newInstance(blockEntityRenderDispatcher, renderBuffers, createDestructionProgressMap());
    }

    private static Object createShadowModelViewRenderer(
            Object delegate,
            Object blockEntityRenderDispatcher,
            Matrix4f shadowModelView) throws ClassNotFoundException {
        Class<?> rendererInterface = Class.forName(SUB_LEVEL_RENDER_DISPATCHER_CLASS + "$BlockEntityRenderer");
        return Proxy.newProxyInstance(
            rendererInterface.getClassLoader(),
            new Class<?>[] { rendererInterface },
            (proxy, method, args) -> {
                String name = method.getName();
                if (method.getDeclaringClass() == Object.class) {
                    return switch (name) {
                        case "toString" -> "IrisVeil shadow " + delegate;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> method.invoke(delegate, args);
                    };
                }
                if ("getBlockEntityRenderDispatcher".equals(name)) {
                    return blockEntityRenderDispatcher;
                }
                return invokeWithShadowModelView(delegate, method, args, shadowModelView);
            });
    }

    private static Object invokeWithShadowModelView(
            Object delegate,
            Method method,
            Object[] args,
            Matrix4f shadowModelView) throws Throwable {
        int poseStackIndex = findPoseStackArgument(args);
        if (poseStackIndex < 0) {
            return invokeDelegate(delegate, method, args);
        }

        Object poseStack = args[poseStackIndex];
        Object pose = invokeNoArg(poseStack, "last");
        Matrix4f poseMatrix = (Matrix4f) invokeNoArg(pose, "pose");
        Matrix3f normalMatrix = (Matrix3f) invokeNoArg(pose, "normal");
        Matrix4f originalPose = new Matrix4f(poseMatrix);
        Matrix3f originalNormal = new Matrix3f(normalMatrix);

        poseMatrix.set(new Matrix4f(shadowModelView).mul(originalPose));
        normalMatrix.set(poseMatrix).invert().transpose();
        try {
            return invokeDelegate(delegate, method, args);
        } finally {
            poseMatrix.set(originalPose);
            normalMatrix.set(originalNormal);
        }
    }

    private static Object invokeDelegate(Object delegate, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static int findPoseStackArgument(Object[] args) {
        if (args == null) {
            return -1;
        }
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg != null && POSE_STACK_CLASS.equals(arg.getClass().getName())) {
                return i;
            }
        }
        return -1;
    }

    private static Object invokeNoArg(Object target, String methodName) throws ReflectiveOperationException {
        Method method = findMethod(target.getClass(), methodName, 0, false);
        return method.invoke(target);
    }

    private static Object createDestructionProgressMap() {
        try {
            return Class.forName(FASTUTIL_LONG_MAP_CLASS).getConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            return new HashMap<>();
        }
    }

    private static Method findMethod(
            Class<?> owner,
            String name,
            int parameterCount,
            boolean requireStatic) throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != parameterCount) {
                continue;
            }
            if (Modifier.isStatic(method.getModifiers()) == requireStatic) {
                return method;
            }
        }
        throw new NoSuchMethodException(owner.getName() + "#" + name + "/" + parameterCount);
    }

    private static Method findCompatibleMethod(
            Class<?> owner,
            String name,
            boolean requireStatic,
            Object... args) throws NoSuchMethodException {
        Method bestMethod = null;
        int bestScore = Integer.MAX_VALUE;

        for (Method method : owner.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) {
                continue;
            }
            if (Modifier.isStatic(method.getModifiers()) != requireStatic) {
                continue;
            }

            int score = compatibilityScore(method.getParameterTypes(), args);
            if (score >= 0 && score < bestScore) {
                bestMethod = method;
                bestScore = score;
            }
        }

        if (bestMethod != null) {
            return bestMethod;
        }
        throw new NoSuchMethodException(owner.getName() + "#" + name + "/" + args.length);
    }

    private static int compatibilityScore(Class<?>[] parameterTypes, Object[] args) {
        int score = 0;
        for (int i = 0; i < parameterTypes.length; i++) {
            Object arg = args[i];
            if (arg == null) {
                score += 1000;
                continue;
            }

            Class<?> parameterType = wrapPrimitive(parameterTypes[i]);
            Class<?> argType = arg.getClass();
            if (!parameterType.isAssignableFrom(argType)) {
                return -1;
            }
            score += inheritanceDistance(argType, parameterType);
        }
        return score;
    }

    private static int inheritanceDistance(Class<?> argType, Class<?> parameterType) {
        if (Objects.equals(argType, parameterType)) {
            return 0;
        }

        int distance = 1;
        Class<?> current = argType.getSuperclass();
        while (current != null) {
            if (Objects.equals(current, parameterType)) {
                return distance;
            }
            current = current.getSuperclass();
            distance++;
        }

        return 100 + distance;
    }

    private static Class<?> wrapPrimitive(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        return Void.class;
    }

    private static Constructor<?> findConstructor(Class<?> owner, int parameterCount) throws NoSuchMethodException {
        for (Constructor<?> constructor : owner.getConstructors()) {
            if (constructor.getParameterCount() == parameterCount) {
                return constructor;
            }
        }
        throw new NoSuchMethodException(owner.getName() + " constructor/" + parameterCount);
    }

    private static void logUnavailable() {
        unavailable = true;
        if (!unavailableLogged) {
            unavailableLogged = true;
            IrisVeilCompat.LOGGER.debug("Sable shadow block entity bridge is unavailable");
        }
    }

    private static void logFailure(Throwable throwable) {
        if (!failureLogged) {
            failureLogged = true;
            IrisVeilCompat.LOGGER.warn("Failed to render Sable sub-level block entities into the Iris shadow pass", throwable);
        }
    }
}
