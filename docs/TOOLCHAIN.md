# TOD — toolchain and source references

The legacy Forge toolchain was validated locally rather than assumed compatible
with the system JDK. Build tooling and Minecraft dependencies live under ignored
`.tools/`; generated game files live under ignored `mod/run/`.

| Component | Pin |
| --- | --- |
| Java | Temurin 8u504-b01, Linux x86_64 |
| Gradle | 2.14.1 |
| ForgeGradle | 2.1-20211118.174922-42 |
| Minecraft / Forge | 1.8.9 / 11.15.1.2318 |
| MCP mappings | stable_22 |
| JUnit | 4.13.2 |
| Go | minimum 1.24; validated with host 1.27.1 |

Download SHA-256 values are pinned in `scripts/bootstrap.sh`. Minecraft asset
SHA-1 values are checked against Mojang's HTTPS-fetched asset index. Build caches
are not shipped or committed. Initial builds require network access. Use
`scripts/mod-gradle.sh` to select the local Java/cache; the upstream Gradle wrapper
is retained from the Forge MDK but does not automatically select our local JDK.

Official sources used:

- Forge MDK: https://files.minecraftforge.net/net/minecraftforge/forge/index_1.8.9.html
- ForgeGradle source: https://github.com/MinecraftForge/ForgeGradle/tree/FG_2.1
- Forge event bus implementation: https://github.com/MinecraftForge/MinecraftForge/blob/1.8.9/src/main/java/net/minecraftforge/fml/common/FMLCommonHandler.java
- Temurin release: https://github.com/adoptium/temurin8-binaries/releases/tag/jdk8u504-b01
- Gradle checksum: https://services.gradle.org/distributions/gradle-2.14.1-bin.zip.sha256

Forge 1.8.9 forwards FML tick events through `MinecraftForge.EVENT_BUS`, so the
mod registers there once. The mod captures plain client-visible data only; there
is no Hypixel Mod API integration in this diagnostic iteration.
