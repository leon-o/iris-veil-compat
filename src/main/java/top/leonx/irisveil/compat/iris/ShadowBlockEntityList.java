package top.leonx.irisveil.compat.iris;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Supplements Iris's current shadow list without submitting an entity twice. */
public final class ShadowBlockEntityList {
    private ShadowBlockEntityList() {
    }

    public static <T> void appendMissing(List<T> shadowEntities, Consumer<Consumer<T>> currentVisibleEntities) {
        Set<T> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.addAll(shadowEntities);
        currentVisibleEntities.accept(entity -> {
            if (seen.add(entity)) {
                shadowEntities.add(entity);
            }
        });
    }
}
