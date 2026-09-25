# TOD — validation status — 2026-09-25

## Step 1: build foundation

- **Passed:** checksum-verified, project-local Temurin Java 8u504-b01 and Gradle
  2.14.1 bootstrap; system Java remains unchanged.
- **Passed:** ForgeGradle 2.1-20211118.174922-42, Forge
  1.8.9-11.15.1.2318-1.8.9 and MCP stable_22 compilation and reobfuscation.
- **Passed:** diagnostic JAR generated at
  `mod/build/libs/bedwars-capture-0.1.0-diagnostic.jar`.
- **Passed:** isolated `runClient` smoke test. Forge identified `bedwarscapture`,
  loaded its resource pack and reported `Forge Mod Loader has successfully loaded
  4 mods` (Minecraft/FML/Forge plus this mod). The process was stopped after startup;
  no account login, Hypixel connection or live match was performed.
- **Passed:** Go companion builds; `go vet ./...`, `version` and `doctor` work.
  The tested host Go version is 1.27.1; module minimum is 1.24.

ForgeGradle's legacy asset task attempted HTTP URLs, failed downloads and still
reported success. The project overrides that task with an HTTPS downloader which
verifies index hashes and propagates errors. 722 distinct asset hashes verified.
The legacy client emitted an unused Twitch integration error; it did not prevent
Forge/mod initialization. No claim is made about live in-game performance yet.

## Step 2: diagnostic tooling ready, live experiment pending

Implemented: opt-in commands, one-second changed snapshots, manually gated
island rosters, raw team metadata, selected formatted message candidates, fixed
manual markers, world/disconnect gate resets, asynchronous bounded JSONL writing,
restricted Linux file permissions, retention and inspection/sanitisation tools.

Automated checks:

- Six Java tests: selected candidates, common chat spoof rejection, orderly drain
  and permissions, retention, size limit/drop accounting, and disk-write failure.
- Two Python tests: formatting-aware identity pseudonymisation and crash/gap summary.
- Synthetic JSONL inspection reports three observations, a clean footer, no
  sequence gaps and explicitly unverified coverage.

**Not yet verified:** commands/roster sampling inside an actual Solo match, real
Hypixel message formats, identity confirmation, complete ordinary-kill visibility,
spectator/reconnect semantics, or live performance. Diagnostic selection is
conservative and can miss cosmetic variants. No production event parser exists.

Next action: install the built JAR into an authenticated standalone Forge 1.8.9
instance and follow `CAPTURE_EXPERIMENT.md`. Complete its evidence table before
calling step 2 complete or building downstream event logic on assumptions.

## Reproduce

```sh
./scripts/bootstrap.sh
./scripts/build.sh
./scripts/mod-gradle.sh setupDevWorkspace
./scripts/mod-gradle.sh runClient
```

The development client uses a separate `mod/run` directory and is intended for
offline loading checks. Use your normal authenticated launcher for Hypixel.
