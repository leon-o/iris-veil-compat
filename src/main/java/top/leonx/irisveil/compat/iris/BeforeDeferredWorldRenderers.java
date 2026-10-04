package top.leonx.irisveil.compat.iris;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

import top.leonx.irisveil.IrisVeilCompat;

/** Explicit adapters for opaque world effects normally submitted after deferred lighting. */
public final class BeforeDeferredWorldRenderers {
    public static final BeforeDeferredWorldRenderers INSTANCE = new BeforeDeferredWorldRenderers();

    private final Map<String, Entry> renderers = new LinkedHashMap<>();

    public void register(String id, BooleanSupplier render) {
        renderers.put(Objects.requireNonNull(id, "id"), new Entry(Objects.requireNonNull(render, "render")));
    }

    public Frame newFrame() {
        return new Frame();
    }

    public final class Frame {
        private final Set<String> attempted = new HashSet<>();
        private final Set<String> completed = new HashSet<>();
        private boolean active;
        private boolean dispatching;

        public boolean hasRenderers() {
            return !renderers.isEmpty();
        }

        public void begin() {
            attempted.clear();
            completed.clear();
            active = true;
            dispatching = false;
        }

        public void end() {
            active = false;
            completed.clear();
            attempted.clear();
        }

        public void renderCallbacks() {
            if (!active || dispatching) {
                return;
            }
            dispatching = true;
            try {
                for (var registration : new ArrayList<>(renderers.entrySet())) {
                    String id = registration.getKey();
                    Entry entry = registration.getValue();
                    if (entry.disabled || !attempted.add(id)) {
                        continue;
                    }
                    try {
                        if (entry.render.getAsBoolean()) {
                            completed.add(id);
                            if (!entry.reported) {
                                entry.reported = true;
                                IrisVeilCompat.LOGGER.info(
                                    "IrisVeilCompat: submitting '{}' before deferred lighting", id);
                            }
                        }
                    } catch (RuntimeException | LinkageError e) {
                        entry.disabled = true;
                        IrisVeilCompat.LOGGER.warn(
                            "IrisVeilCompat: early world renderer '{}' failed; retaining its native event", id, e);
                    }
                }
            } finally {
                dispatching = false;
            }
        }

        /** Only a successfully completed early call can suppress its original late call. */
        public boolean wasRendered(String id) {
            return active && !dispatching && completed.contains(id);
        }
    }

    private static final class Entry {
        private final BooleanSupplier render;
        private boolean disabled;
        private boolean reported;

        private Entry(BooleanSupplier render) {
            this.render = render;
        }
    }
}
