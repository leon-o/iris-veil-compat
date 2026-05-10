<h1 align="center">Iris Veil Compat<br>
  <a href="https://github.com/leon-o/iris-veil-compat"><img src="https://img.shields.io/github/license/leon-o/iris-veil-compat" alt="License"></a>
    <br><br>
</h1>

# Iris Veil Compat
Allow mods using the Veil rendering engine (such as **Create: Aeronautics**) to render correctly when using Iris shaderpacks.

# Principle
[Create: Aeronautics](https://github.com/Create-Modern/Create-Aeronautics) uses the **Veil** rendering engine for its visual effects — airplane wings, glass panels, contrails, and more. Normally, when you enable a shaderpack via Iris, these Veil-rendered visuals bypass the shaderpack's pipeline entirely, causing them to appear broken or missing.

This mod automatically merges Veil shader code into the shaderpack's gbuffer programs at runtime, so Aeronautics planes and other Veil-based visuals integrate with your shaderpack.

# Implementation details

This mod intercepts Veil's shader compilation via mixins, capturing and caching the processed shader source. At render time, it injects Veil shader logic into the shaderpack's gbuffer vertex/fragment programs using AST-level patching (powered by [glsl-transformer](https://github.com/IrisShaders/glsl-transformer)), and creates an Iris `ShaderInstance` to replace the original Veil shader.

The cache is automatically invalidated when you switch or reload shaderpacks — no restart needed.

# Compatibility
- **Iris** 1.8.1+ (required)
- **Sodium**
- **Veil(Create: Aeronautics)**

# Credit
This project uses [glsl-transformer](https://github.com/IrisShaders/glsl-transformer) for shader AST manipulation.
