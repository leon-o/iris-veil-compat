<p align="center"><img src="src/main/resources/logo.png" width="160" alt="Iris Veil Compat logo"></p>
<h1 align="center">Iris Veil Compat<br>
  <a href="https://github.com/leon-o/iris-veil-compat"><img src="https://img.shields.io/github/license/leon-o/iris-veil-compat" alt="License"></a>
    <br><br>
</h1>

# Iris Veil Compat
Allow mods using the Veil rendering engine (such as **Create: Aeronautics**) to render correctly when using Iris shaderpacks.

# Principle
[Veil](https://github.com/FoundryMC/Veil) is an advanced rendering SDK for Minecraft mods — it provides a shader infrastructure that other mods can build on. The most prominent consumer is [Create: Aeronautics](https://github.com/Creators-of-Aeronautics/Simulated-Project), which uses Veil for its airplane wings, glass panels, contrails, and other visual effects.

The problem: when you enable a shaderpack via Iris, Veil-rendered visuals bypass the shaderpack's pipeline entirely, causing them to appear broken or missing.

This mod automatically merges Veil shader code into the shaderpack's gbuffer programs at runtime, so Veil-based visuals (like Aeronautics planes) integrate seamlessly with your shaderpack.

# Implementation details

This mod intercepts Veil's shader compilation via mixins, capturing and caching the processed shader source. At render time, it injects Veil shader logic into the shaderpack's gbuffer vertex/fragment programs using AST-level patching (powered by [glsl-transformer](https://github.com/IrisShaders/glsl-transformer)), and creates an Iris `ShaderInstance` to replace the original Veil shader.

The cache is automatically invalidated when you switch or reload shaderpacks — no restart needed.

# Compatibility
- **Iris** 1.8.1+ (required)
- **Sodium**
- **Veil** 4.0.0+
- Tested with **Create: Aeronautics** (Spring, Laser) and **Sable**

## Development dependency baseline

The development environment targets **Minecraft 1.21.1 / NeoForge**. Versions were checked against upstream releases and Maven metadata on **2026-10-04** and are pinned in `gradle.properties`.

| Dependency | Version |
| --- | --- |
| NeoForge | 21.1.255 |
| Iris | 1.8.14-beta.1+1.21.1 |
| Sodium | 0.8.13+mc1.21.1 |
| Veil | 4.5.1 |
| Create | 6.0.11-313 (development build) |
| Ponder | 1.0.87 |
| Registrate | MC1.21-1.3.0+67 |
| Flywheel | 1.0.6 |
| Create: Aeronautics | 1.3.2+mc1.21.1 |
| Sable | 2.0.6+mc1.21.1 |
| Iris Flywheel Compat | 1.21.1+2.4.0-release |

Iris remains on the latest published 1.21.1 beta, which supports Sodium 0.8. Create stays on the existing 6.0.11 development line; the latest public release is 6.0.10. Flywheel stays on the latest stable 1.0.6. Sable 2.0.6 requires Veil 4.3.2 or newer, satisfied by the explicit Veil 4.5.1 dependency. Veil and Sable remain optional integrations in this mod.

Upstream references: [Iris](https://modrinth.com/mod/iris/version/KduFYu4t), [Sodium](https://modrinth.com/mod/sodium/version/uMOpc5uV), [Veil](https://github.com/FoundryMC/Veil/releases/tag/mc1.21.1-4.5.1-neoforge), [Create build metadata](https://maven.createmod.net/com/simibubi/create/create-1.21.1/6.0.11-313/create-1.21.1-6.0.11-313.pom), [Aeronautics](https://modrinth.com/mod/create-aeronautics/versions), [Sable](https://modrinth.com/mod/sable/versions).

Levitite now preserves Aeronautics' four-point tessellation and deformation while using Iris's opaque, translucent, and shadow programs. Its geometry and albedo enter shaderpack lighting and post-processing; the previous native replay after Iris's final pass has been removed.

Validation for this baseline: `./gradlew build --no-daemon` passed all 62 unit tests. In an isolated copy of the End test world, a BSL 10.0 GPU capture confirmed actual Levitite shadow, opaque, and ghost draws with the expected attachments, samplers, and subsequent post-processing, including TAA. Nostalgia 5.1 ran all three paths with POM enabled at 75% rendering scale; its final runtime shaders passed all 12 GPU stage compilations and three program links after redundant native lighting and fog were removed. Photon 1.3b also activated all three paths after fixing redundant preprocessing; the moving platform remained visible and a subsequent BSL reload recreated the programs successfully. These checks cover the exercised fixtures and settings, not every shaderpack or gameplay effect. The existing Iris Flywheel Compat missing-access-transformer warning remains in the log.

# Credit
This project uses [glsl-transformer](https://github.com/IrisShaders/glsl-transformer) for shader AST manipulation.
