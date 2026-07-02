package top.leonx.irisveil.api;

// Lets other mods hand the light pass oriented occluder grids, so a rotated structure casts shadows in its own frame instead of world-axis.

public final class OrientedOccluders {
    public static final int MAX_OCCLUDERS = 16;

    private static int atlasTextureId;
    private static int count;
    private static final float[] WORLD_TO_LOCAL = new float[MAX_OCCLUDERS * 16];
    private static final int[] GRID_INFO = new int[MAX_OCCLUDERS * 4];

    private OrientedOccluders() {
    }

    public static void submit(int atlasTextureId, int count, float[] worldToLocal, int[] gridInfo) {
        int n = Math.min(count, MAX_OCCLUDERS);
        if (atlasTextureId == 0 || n <= 0) {
            clear();
            return;
        }
        OrientedOccluders.atlasTextureId = atlasTextureId;
        OrientedOccluders.count = n;
        System.arraycopy(worldToLocal, 0, WORLD_TO_LOCAL, 0, n * 16);
        System.arraycopy(gridInfo, 0, GRID_INFO, 0, n * 4);
    }

    public static void clear() {
        atlasTextureId = 0;
        count = 0;
    }

    public static int atlasTextureId() {
        return atlasTextureId;
    }

    public static int count() {
        return count;
    }

    public static float[] worldToLocal() {
        return WORLD_TO_LOCAL;
    }

    public static int[] gridInfo() {
        return GRID_INFO;
    }
}
