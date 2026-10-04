# Render compatibility boundaries

This document describes the rendering paths implemented on 2026-10-04.
Levitite now participates in Iris world and shadow rendering. The previous
after-final native replay, owned depth-copy target, deferred mesh queue, and
`experimental.nativeLevitite` option have been removed. An old configuration
file containing that option no longer controls this implementation.

The development stack is Minecraft 1.21.1, NeoForge 21.1.255, Iris
1.8.14-beta.1, Veil 4.5.1, Sodium 0.8.13, Create Aeronautics 1.3.2, its bundled
Simulated 1.3.2, and Sable 2.0.6. Exact dependency coordinates remain in
`gradle.properties`; these observations do not establish compatibility with
every version allowed by mod metadata.

| Boundary | Current implementation |
| --- | --- |
| Ordinary Veil shaders | Capture processed sources, build Iris-compatible programs, and invalidate shader references when the active pipeline changes or its shader instances close. |
| Levitite base | Compose Aeronautics' four-control-point deformation with the shaderpack's Block program, at its existing world stage before Iris composite/final. |
| Levitite ghosts | Select the shaderpack's EntitiesTrans program, preserve native material effects, and compare against Iris' copied opaque depth. |
| Levitite shadow | Draw the registered base layer in Iris' opaque shadow pass using the shaderpack's Shadow program and Sable sublevel transforms. Ghost layers are excluded. |
| Simulated End Sea | Preserve its native shader and draw into the world-color attachment read by the next composite, with world depth. Restore previous draw/read framebuffer bindings. |
| Diagram rendering | Scope shader replacement and vertex bypass around the complete native `renderGroup` call; retain its offscreen framebuffer when a Veil wrapper applies or clears. |

## Levitite geometry and material pipeline

`MixinDirectShaderCompiler` captures Veil's processed vertex, tessellation-control,
tessellation-evaluation, and fragment sources. `IrisVeilProgramLinker` retains
Iris' already-preprocessed shaderpack vertex source and patches the fragment
albedo boundary. The retained vertex source is not passed through Iris' source
preprocessor a second time: generated Iris identifiers can legitimately occur
after the first pass, as observed with Photon.

During Iris shader creation, `LevititeGbufferBridge` supplies a scoped build
context. `LevititeTessellationTransformer` combines the native vertex/control
stages with a composed evaluation stage after Iris has transformed the pack
vertex program. Native deformation runs first; the shaderpack vertex logic
then runs at the resulting tessellated position. Pack projection, shadow
distortion, varyings, and TAA offsets therefore remain part of that program.
Raw vertex color, lightmap coordinates, UVs, and surface basis are transported
through tessellation; normals/tangents are reconstructed from the deformed
surface rather than leaving the original flat normal attached to it.

The fragment adapter applies native hue/noise/ghost effects to the albedo texel
selected by the shaderpack. Existing texture sampling operations and their
LOD/gradient/POM coordinates are preserved. Native vertex lighting and fog are
removed from this material contribution; the shaderpack supplies lighting,
fog, material outputs, and subsequent postprocessing. This puts Levitite into
the pack's normal lighting and postprocess path. It does not force a particular
bloom/reflection effect when the pack or selected material does not provide it.

Aeronautics' existing world stages remain in use: base at
`AFTER_BLOCK_ENTITIES`, ghosts at `AFTER_WEATHER`. Scoped base/ghost state also
covers fixed-buffer draws. The shared Aeronautics shader shard checks this state
before reusing its cached program, selecting Block, EntitiesTrans, or Shadow as
appropriate. Actual vertex-buffer submission uses `GL_PATCHES` with four control
points; the previous `GL_PATCH_VERTICES` value is restored after every draw.

The original Veil uniform wrappers continue to receive Aeronautics' tick and
physics updates. Immediately before a composed draw, the bridge copies the
active native uniform values from their CPU buffers, including array counts.
Sable's post-apply transform and layer updates are therefore visible to the
draw. Standard matrices/ChunkOffset use the corresponding Iris uniforms.
Native Noise is registered with Iris' sampler allocator rather than occupying
a fixed texture unit. Ghost occlusion samples `depthtex1`, the separate opaque
depth copy, rather than sampling the active depth attachment; its dimensions
come from that texture.

## Shadow, lifetime, and fallback behavior

Iris does not dispatch Aeronautics' regular world stage callbacks while building
its shadow map. `LevititeShadowCompat` therefore renders the registered base
terrain layer before Iris copies opaque shadow depth. It uses the shadow
frustum and temporarily refreshes Sable's culling for that view, then restores
player-view culling, the smart-culling flag, framebuffer bindings, and culling
state. Optional Veil and Sable implementations are isolated behind loaded-mod
checks. A linkage/runtime failure disables this additional shadow bridge for
the session and is logged.

Shader instances remain owned by Iris' pipeline. Closing an instance removes
its bridge binding and cached references by Java identity, preventing a reused
OpenGL program ID from selecting a stale binding. Cache invalidation does not
close an Iris-owned instance a second time. The shard generation also changes
when a cached instance closes or the active Iris pipeline changes, including
dimension pipeline changes.

Unsupported or failed Levitite programs are remembered for the current cache
generation, so compilation is not retried every frame. Shaderpack reload or an
active-pipeline change permits another attempt. If composition or uniform
synchronization fails, the custom main/shadow Levitite draw is suppressed and
Aeronautics' ordinary block fallback remains available. Native color output is
not submitted into an incompatible pack gbuffer as a recovery path.

With shaders disabled, native Aeronautics rendering remains in use. Veil
perspective and registered external render contexts, including Diagram and
End Sea shadow rendering, retain native shaders and draw targets. No mesh is
deferred until after Iris final, and the bridge does not own a second world
render or a copied world-depth framebuffer.

## Explicit limits

- Packs with their own geometry, tessellation-control, or tessellation-evaluation
  stages are rejected by this composition path rather than having those stages
  silently replaced. Unrecognized required vertex attributes or albedo sampling
  contracts also fail explicitly.
- The composed shaders currently require GLSL 4.50. Driver verification on the
  development RTX 4070 Ti does not establish support on older OpenGL hardware.
- Aeronautics' current Levitite terrain payload does not provide a mapped block
  material ID for each quad. The bridge uses the unmapped `mc_Entity` value, not
  an invented emissive/material ID. Pack rules requiring a custom per-block ID
  need a corresponding vertex-data path before they can be promised.
- Verified submission, successful compilation, and visible samples have
  different scopes. They do not establish every pack feature, every render
  scale, arbitrary moving-object occlusion, or absence of temporal artifacts.

## End Sea and Diagram boundaries

End Sea wraps only its existing draw operation. Unmatched or unavailable paths
call the original operation. Its correction uses a local projection adjustment
and framebuffer binding; it does not schedule a second world render. The unused
world-render callback registry, reflected End Sea replay entry point, captured
world context, and FinalPass framebuffer accessor were removed.

For Diagram rendering, world buffers are drained before changing the bypass
flags. Simulated 1.3.2 flushes entity buffers inside `renderGroup`, before
restoring its camera, model-view matrix, projection, and framebuffer. The
redundant wrapper-tail flush was removed. Its renderer class is byte-for-byte
identical to the audited 1.3.0 class. The bypass covers the native entity flush,
keeping Diagram entities and the rotating propeller in their offscreen view.

## Verification evidence

Evidence is kept under `build/` and is not bundled in the release JAR. Tests and
captures use an isolated copy of the End test world.

| Evidence | What it establishes |
| --- | --- |
| `build/levitite-gbuffer-20261004/build-photon-fix.log` | Final full build and 62 passing unit tests, including the Photon preprocessing and shader-lifetime corrections. |
| `build/levitite-gbuffer-20261004/gpu-link-check/bsl-composed-first.log` | Real NVIDIA driver compilation of 12 shader stages and linking of three BSL programs; exact sources and hashes are preserved alongside it. |
| `build/levitite-gbuffer-20261004/gpu-link-check/nostalgia-final-actual.log` and `photon-final-actual.log` | Exact runtime dumps: all 12 stages compile and all three programs link for each pack; reflection confirms removal of redundant native lighting/fog inputs. |
| `build/levitite-gbuffer-20261004/nsight/verification.md` and `levitite-draw-audit.json` | The 20:54:34 capture contains five composed shadow draws, five opaque draws, and four ghost draws, using four-point patches and the correct 52-byte vertex layout. Per-draw uniforms and targets are recorded. |
| `build/levitite-gbuffer-20261004/runtime-verified-latest.log` | Photon activates block/shadow/ghost at 21:16:15-16; switching back to BSL rebuilds all three at 21:20:22. No Levitite creation or bridge-disable failure. The shader-lifetime changes are included. |
| `build/compat-cleanup-20261004/` | Earlier End Sea and Diagram checks, including Nostalgia at 0.75 render scale and shader-disabled comparisons. The native after-final replay tests in this folder describe the superseded implementation. |

In the Nsight capture, composed shadow/base/ghost draws precede BSL's composite
passes. The later native Levitite draws target Simulated's separate End Sea
shadow framebuffer; they are not after-final replay into the main image. Ghost
depth sampling uses a copied texture distinct from the active depth attachment.
The capture predates the native-lighting cleanup and records differing ghost
material matrices between shadow and main stages; the audit does not claim
those matrices are identical. Nsight also reported unsupported capture APIs,
so the exported calls are evidence of submission/state, not a claim that every
API can be replayed faithfully. Capture inspection deliberately excludes
`metadata.json`.

The standalone OpenGL checker creates a hidden, unfocused context and performs
compilation/linking plus optional uniform reflection. It does not draw, upload
runtime uniform values, or validate framebuffer pixels. Final runtime movement,
shaderpack reload, and screenshot results belong in the accompanying local
verification report so their tested revision and scope remain explicit.

The final runtime test moved the assembled platform about 39.4 blocks along -Z under Photon while its Levitite remained visible. Four fixed-camera Nostalgia screenshots span 44 seconds; verified interior regions vary by less than 0.1/255 in mean encoded RGB. These are scoped samples, not continuous ghost-occlusion or motion-vector validation. See `build/levitite-gbuffer-20261004/VERIFICATION.txt` and `screenshots/nostalgia-static-samples.json` for coordinates, methods and limits. The existing Veil bool-uniform and Iris Flywheel Compat/reload warnings remain separately recorded.
