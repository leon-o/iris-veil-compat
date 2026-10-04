package top.leonx.irisveil.compat.aeronautics;

/** Keeps the base and ghost layers distinct even though Aeronautics shares one shader shard. */
public final class LevititeRenderContext {
    private static final ThreadLocal<Boolean> GHOST_PASS = new ThreadLocal<>();

    private LevititeRenderContext() {
    }

    public static boolean isLevititeLayer(String name) {
        return "aeronautics:levitite".equals(name) || "aeronautics:levitite_ghosts".equals(name);
    }

    public static boolean isGhostPass() {
        return Boolean.TRUE.equals(GHOST_PASS.get());
    }

    public static Scope enterLayer(String name) {
        Boolean previous = GHOST_PASS.get();
        GHOST_PASS.set("aeronautics:levitite_ghosts".equals(name));
        return new Scope(previous);
    }

    public static final class Scope implements AutoCloseable {
        private final Boolean previous;
        private boolean closed;

        private Scope(Boolean previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                GHOST_PASS.remove();
            } else {
                GHOST_PASS.set(previous);
            }
        }
    }
}
