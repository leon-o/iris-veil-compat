### 0.4.0
- Replaced the mod icon and added it to the README.
- Updated the Minecraft 1.21.1 development dependencies to NeoForge 21.1.255, Veil 4.5.1, Sodium 0.8.13, Create 6.0.11-313, Ponder 1.0.87, Create: Aeronautics 1.3.2, and Sable 2.0.6.
- Retained the latest published 1.21.1 Iris beta, stable Flywheel 1.0.6, and Iris Flywheel Compat 2.4.0.
- Integrated Levitite's native four-point tessellation and deformation into Iris's opaque, translucent, and shadow programs, replacing the native replay after the final pass.
- Preserved shaderpack albedo sampling and post-processing while forwarding Aeronautics' physics, noise, and ghost effects; removed redundant native lighting and fog.
- Passed 62 unit tests, verified actual draw and post-processing connections in a BSL 10.0 GPU capture, and exercised Nostalgia 5.1 with POM at 75% rendering scale. Validation is limited to the tested fixtures and settings.

### 0.3.0
- Fixed End Sea rendering issues when using Iris shaderpacks.
- Fixed Rope and Spring shadows disappearing with some shaderpacks.
- Improved compatibility with Photon, Complementary, BSL, and Bliss for affected Veil effects.

### 0.2.0
- Added Iris shadow pass support for Veil shaders so block entity shadows render into the shadow map instead of gbuffers.
- Preserved shaderpack shadow alpha-test behavior when building Veil shadow programs.
- Fixed Veil fragment bridge symbol isolation to avoid conflicts with shaderpack globals and helper functions, including SEUS PTGI HRR 3 `ScreenSize` and `frameTimeCounter` cases.
- Reduced noisy per-frame Veil shader cache and translucency diagnostic logging.

### 0.1.0
- Initial release.
- Create Aeronautics now renders correctly with Iris shaderpacks (verified with Spring and Laser).
- Shader compatibility refreshes automatically when switching or reloading shaderpacks.
