package top.leonx.irisveil.compat.sable;

import top.leonx.irisveil.IrisVeilCompat;

public final class SableShadowCompat {
    private static boolean disabled;
    private static boolean unavailableLogged;
    private static boolean failureLogged;
    private static boolean singleBlocksDisabled;

    private SableShadowCompat() {
    }

    /** Flushes Sable's single-block terrain queue within the current Iris shadow layer. */
    public static boolean renderSubLevelSingleBlocks(
            Object level,
            Object shadowModelView,
            Object shadowProjection,
            double cameraX,
            double cameraY,
            double cameraZ,
            float partialTick) {
        if (singleBlocksDisabled) {
            return false;
        }
        try {
            return SableShadowBridge.renderSubLevelSingleBlocks(
                level, shadowModelView, shadowProjection, cameraX, cameraY, cameraZ, partialTick);
        } catch (NoClassDefFoundError e) {
            singleBlocksDisabled = true;
            IrisVeilCompat.LOGGER.debug("Sable single-block shadow terrain bridge is unavailable", e);
            return false;
        } catch (AssertionError | LinkageError | RuntimeException e) {
            singleBlocksDisabled = true;
            IrisVeilCompat.LOGGER.warn("Disabling Sable single-block shadow terrain bridge after compatibility failure", e);
            return false;
        }
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
        if (disabled) {
            return false;
        }

        try {
            return SableShadowBridge.renderSubLevelBlockEntities(
                level,
                renderBuffers,
                blockEntityRenderDispatcher,
                shadowModelView,
                cameraX,
                cameraY,
                cameraZ,
                partialTick);
        } catch (NoClassDefFoundError e) {
            logUnavailable();
            return false;
        } catch (AssertionError | LinkageError | RuntimeException e) {
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

    private static void logUnavailable() {
        disabled = true;
        if (!unavailableLogged) {
            unavailableLogged = true;
            IrisVeilCompat.LOGGER.debug("Sable shadow block entity bridge is unavailable");
        }
    }

    private static void logFailure(Throwable throwable) {
        disabled = true;
        if (!failureLogged) {
            failureLogged = true;
            IrisVeilCompat.LOGGER.warn("Disabling Sable shadow block entity bridge after compatibility failure", throwable);
        }
    }
}
