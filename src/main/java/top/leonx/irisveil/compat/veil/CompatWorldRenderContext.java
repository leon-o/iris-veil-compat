package top.leonx.irisveil.compat.veil;

/**
 * Stores the current world render arguments for Iris pipeline hooks that run
 * outside {@code LevelRenderer.renderLevel}'s argument list.
 */
public final class CompatWorldRenderContext {
    private static Object camera;
    private static Object gameRenderer;

    private CompatWorldRenderContext() {
    }

    public static void capture(Object camera, Object gameRenderer) {
        CompatWorldRenderContext.camera = camera;
        CompatWorldRenderContext.gameRenderer = gameRenderer;
    }

    public static void clear() {
        camera = null;
        gameRenderer = null;
    }

    public static boolean hasContext() {
        return camera != null && gameRenderer != null;
    }

    public static Object camera() {
        return camera;
    }

    public static Object gameRenderer() {
        return gameRenderer;
    }
}
