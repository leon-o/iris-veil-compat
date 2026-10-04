package top.leonx.irisveil.compat.veil;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VeilCompatRegistryTest {
    @Test
    void registeredShaderReplacementExclusionsAreApplied() {
        String blocked = "testmod:direct_world_effect";
        String regular = "testmod:regular_geometry";

        assertTrue(VeilCompatRegistry.shouldReplaceShader(blocked));

        VeilCompatRegistry.excludeShaderReplacement(blocked);

        assertFalse(VeilCompatRegistry.shouldReplaceShader(blocked));
        assertTrue(VeilCompatRegistry.shouldReplaceShader(regular));
    }

    @Test
    void externalStateGenerationTracksRegisteredStatesAsStableBitmask() {
        AtomicBoolean firstActive = new AtomicBoolean(false);
        AtomicBoolean secondActive = new AtomicBoolean(false);

        int firstMask = VeilCompatRegistry.registerExternalRenderState("test:first", firstActive::get);
        int secondMask = VeilCompatRegistry.registerExternalRenderState("test:second", secondActive::get);

        assertNotEquals(firstMask, secondMask);
        assertEquals(0, VeilCompatRegistry.getExternalRenderStateGeneration() & (firstMask | secondMask));

        firstActive.set(true);
        assertEquals(firstMask, VeilCompatRegistry.getExternalRenderStateGeneration() & (firstMask | secondMask));

        secondActive.set(true);
        assertEquals(firstMask | secondMask, VeilCompatRegistry.getExternalRenderStateGeneration() & (firstMask | secondMask));

        firstActive.set(false);
        assertEquals(secondMask, VeilCompatRegistry.getExternalRenderStateGeneration() & (firstMask | secondMask));
    }

    @Test
    void inactiveWorldHooksDoNotBindFramebufferOrRender() {
        AtomicBoolean active = new AtomicBoolean(false);
        AtomicBoolean framebufferBound = new AtomicBoolean(false);
        AtomicBoolean rendered = new AtomicBoolean(false);
        AtomicBoolean restored = new AtomicBoolean(false);

        VeilCompatRegistry.registerWorldRenderHook(
            "test:inactive_world_hook",
            new int[] { 0 },
            active::get,
            (camera, gameRenderer) -> {
                rendered.set(true);
                return true;
            });

        VeilCompatRegistry.renderWorldHooks(
            null,
            null,
            drawBuffers -> {
                framebufferBound.set(true);
                return true;
            },
            () -> restored.set(true));

        assertFalse(framebufferBound.get());
        assertFalse(rendered.get());
        assertFalse(restored.get());
    }

    @Test
    void worldHooksOnlyRenderForTheirRegisteredPhase() {
        AtomicBoolean afterTranslucentActive = new AtomicBoolean(true);
        AtomicBoolean finalCompositeActive = new AtomicBoolean(true);
        AtomicBoolean afterTranslucentRendered = new AtomicBoolean(false);
        AtomicBoolean finalCompositeRendered = new AtomicBoolean(false);

        VeilCompatRegistry.registerWorldRenderHook(
            "test:after_translucent_world_hook",
            VeilCompatRegistry.WorldRenderPhase.AFTER_TRANSLUCENT,
            new int[] { 0 },
            afterTranslucentActive::get,
            (camera, gameRenderer) -> {
                afterTranslucentRendered.set(true);
                return true;
            });

        VeilCompatRegistry.registerWorldRenderHook(
            "test:final_composite_world_hook",
            VeilCompatRegistry.WorldRenderPhase.FINAL_COMPOSITE,
            new int[] { 0 },
            finalCompositeActive::get,
            (camera, gameRenderer) -> {
                finalCompositeRendered.set(true);
                return true;
            });

        try {
            VeilCompatRegistry.renderWorldHooks(
                VeilCompatRegistry.WorldRenderPhase.AFTER_TRANSLUCENT,
                null,
                null,
                drawBuffers -> true,
                () -> {});

            assertTrue(afterTranslucentRendered.get());
            assertFalse(finalCompositeRendered.get());

            afterTranslucentRendered.set(false);

            VeilCompatRegistry.renderWorldHooks(
                VeilCompatRegistry.WorldRenderPhase.FINAL_COMPOSITE,
                null,
                null,
                drawBuffers -> true,
                () -> {});

            assertFalse(afterTranslucentRendered.get());
            assertTrue(finalCompositeRendered.get());
        } finally {
            afterTranslucentActive.set(false);
            finalCompositeActive.set(false);
        }
    }

    @Test
    void worldHookScopeWrapsCallbackAndRestoresAfterRender() {
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicBoolean scopeEntered = new AtomicBoolean(false);
        AtomicBoolean scopeActive = new AtomicBoolean(false);
        AtomicBoolean renderedInsideScope = new AtomicBoolean(false);

        VeilCompatRegistry.registerWorldRenderHook(
            "test:scoped_final_composite_world_hook",
            VeilCompatRegistry.WorldRenderPhase.FINAL_COMPOSITE,
            new int[] { 0 },
            active::get,
            (camera, gameRenderer) -> {
                renderedInsideScope.set(scopeActive.get());
                return true;
            });

        try {
            VeilCompatRegistry.renderWorldHooks(
                VeilCompatRegistry.WorldRenderPhase.FINAL_COMPOSITE,
                null,
                null,
                drawBuffers -> true,
                () -> {},
                callback -> {
                    scopeEntered.set(true);
                    scopeActive.set(true);
                    try {
                        callback.run();
                    } finally {
                        scopeActive.set(false);
                    }
                });

            assertTrue(scopeEntered.get());
            assertTrue(renderedInsideScope.get());
            assertFalse(scopeActive.get());
        } finally {
            active.set(false);
        }
    }
}
