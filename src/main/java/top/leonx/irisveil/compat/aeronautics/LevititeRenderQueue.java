package top.leonx.irisveil.compat.aeronautics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Frame-local native draws; never retain a renderer or its buffers across frames. */
final class LevititeRenderQueue {
    private final List<PendingDraw> draws = new ArrayList<>();
    private boolean rendering;
    private boolean collecting;

    static boolean isLevititeLayer(String name) {
        return "aeronautics:levitite".equals(name) || "aeronautics:levitite_ghosts".equals(name);
    }

    boolean defer(String name, boolean shaderPack, boolean externalPass, Runnable draw) {
        return defer(name, shaderPack, externalPass, draw, () -> {}, false);
    }

    boolean deferMesh(String name, boolean shaderPack, boolean externalPass, Runnable draw, Runnable release) {
        return defer(name, shaderPack, externalPass, draw, release, true);
    }

    private boolean defer(String name, boolean shaderPack, boolean externalPass,
            Runnable draw, Runnable release, boolean mesh) {
        if (!shouldRetain(name, shaderPack, externalPass)) {
            return false;
        }
        int order = ("aeronautics:levitite_ghosts".equals(name) ? 2 : 0) + (mesh ? 1 : 0);
        draws.add(new PendingDraw(order, Objects.requireNonNull(draw, "draw"), Objects.requireNonNull(release, "release")));
        return true;
    }

    boolean hasDraws() {
        return !draws.isEmpty();
    }

    boolean isRendering() {
        return rendering;
    }

    boolean isCollecting() {
        return collecting;
    }

    boolean shouldRetain(String name, boolean shaderPack, boolean externalPass) {
        return collecting && shaderPack && !externalPass && !rendering && isLevititeLayer(name);
    }

    void beginFrame() {
        clear();
        collecting = true;
    }

    void finishCollection() {
        collecting = false;
    }

    void clear() {
        draws.forEach(PendingDraw::discard);
        draws.clear();
        collecting = false;
    }

    void replay(Runnable beforeEachDraw) {
        if (rendering) {
            throw new IllegalStateException("Recursive levitite replay");
        }
        // A global flush can precede the stage callback. Always initialize the
        // native chunk layer first, then its detached fixed meshes, then ghosts.
        List<PendingDraw> current = draws.stream().sorted(Comparator.comparingInt(PendingDraw::order)).toList();
        draws.clear();
        collecting = false;
        rendering = true;
        int next = 0;
        try {
            while (next < current.size()) {
                beforeEachDraw.run();
                PendingDraw draw = current.get(next++);
                try {
                    draw.draw().run();
                } finally {
                    draw.discard();
                }
            }
        } finally {
            for (; next < current.size(); next++) {
                current.get(next).discard();
            }
            rendering = false;
            draws.clear();
        }
    }

    private record PendingDraw(int order, Runnable draw, Runnable release) {
        private void discard() {
            release.run();
        }
    }
}
