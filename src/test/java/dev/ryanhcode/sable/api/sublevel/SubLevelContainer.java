package dev.ryanhcode.sable.api.sublevel;

import java.util.List;

public final class SubLevelContainer {
    private static final FakeContainer OBJECT_CONTAINER = new FakeContainer("object-sublevel");
    private static final FakeContainer STRING_CONTAINER = new FakeContainer("string-sublevel");

    private SubLevelContainer() {
    }

    public static FakeContainer getContainer(Object level) {
        return OBJECT_CONTAINER;
    }

    public static FakeContainer getContainer(String level) {
        return STRING_CONTAINER;
    }

    public static final class FakeContainer {
        private final String subLevelName;

        private FakeContainer(String subLevelName) {
            this.subLevelName = subLevelName;
        }

        public List<String> getAllSubLevels() {
            return List.of(subLevelName);
        }
    }
}
