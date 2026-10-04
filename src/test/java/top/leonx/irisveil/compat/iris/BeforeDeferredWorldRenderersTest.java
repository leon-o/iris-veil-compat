package top.leonx.irisveil.compat.iris;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeforeDeferredWorldRenderersTest {
    @Test
    void submitsOnceAndSuppressesOnlyAfterTheEarlyCallbackCompletes() {
        var registry = new BeforeDeferredWorldRenderers();
        var frame = registry.newFrame();
        AtomicInteger draws = new AtomicInteger();
        registry.register("outline", () -> {
            assertFalse(frame.wasRendered("outline"), "The native method must remain callable during dispatch");
            frame.renderCallbacks();
            draws.incrementAndGet();
            return true;
        });
        frame.renderCallbacks();
        assertEquals(0, draws.get(), "No world frame is active");
        frame.begin();
        assertFalse(frame.wasRendered("outline"));
        frame.renderCallbacks();
        frame.renderCallbacks();
        assertEquals(1, draws.get());
        assertTrue(frame.wasRendered("outline"));
    }

    @Test
    void completionDoesNotLeakToTheNextWorldFrameOrPipeline() {
        var registry = new BeforeDeferredWorldRenderers();
        var frame = registry.newFrame();
        AtomicInteger draws = new AtomicInteger();
        registry.register("outline", () -> {
            draws.incrementAndGet();
            return true;
        });
        frame.begin();
        frame.renderCallbacks();
        frame.end();
        assertFalse(frame.wasRendered("outline"));
        frame.begin();
        assertFalse(frame.wasRendered("outline"));
        assertFalse(registry.newFrame().wasRendered("outline"));
        frame.renderCallbacks();
        assertEquals(2, draws.get());
        assertTrue(frame.wasRendered("outline"));
    }

    @Test
    void failedAdapterKeepsItsNativeEventWithoutBlockingOtherAdapters() {
        var registry = new BeforeDeferredWorldRenderers();
        var frame = registry.newFrame();
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger goodDraws = new AtomicInteger();
        registry.register("missing-optional-api", () -> {
            failures.incrementAndGet();
            throw new NoClassDefFoundError("optional renderer unavailable");
        });
        registry.register("outline", () -> {
            goodDraws.incrementAndGet();
            return true;
        });
        frame.begin();
        frame.renderCallbacks();
        assertFalse(frame.wasRendered("missing-optional-api"));
        assertTrue(frame.wasRendered("outline"));
        frame.begin();
        frame.renderCallbacks();
        assertEquals(1, failures.get(), "An unavailable adapter must not fail on every frame");
        assertEquals(2, goodDraws.get());
        assertFalse(frame.wasRendered("missing-optional-api"));
    }

    @Test
    void hiddenOrOtherwiseSkippedDrawDoesNotSuppressTheNativeEvent() {
        var registry = new BeforeDeferredWorldRenderers();
        var frame = registry.newFrame();
        AtomicInteger attempts = new AtomicInteger();
        registry.register("hidden-outline", () -> {
            attempts.incrementAndGet();
            return false;
        });
        frame.begin();
        frame.renderCallbacks();
        frame.renderCallbacks();
        assertEquals(1, attempts.get());
        assertFalse(frame.wasRendered("hidden-outline"));
        frame.begin();
        frame.renderCallbacks();
        assertEquals(2, attempts.get());
        assertFalse(frame.wasRendered("hidden-outline"));
    }
}
