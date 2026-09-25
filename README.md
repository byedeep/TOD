# TOD — diagnostic prototype

Current work: steps 1–2 of [the prototype plan](PROTOTYPE_PLAN.md).
This logs selected match signals, **not video or world replays**. The diagnostic
mod makes no account/API requests and does not connect to Discord.

## Build on Linux x86_64

Prerequisites: `bash`, `curl`, `unzip`, `tar`, `sha256sum`, Python 3 and Go >= 1.24.

```sh
./scripts/bootstrap.sh
./scripts/build.sh
```

Bootstrap downloads checksum-verified Temurin Java 8u504-b01 and Gradle 2.14.1
under ignored `.tools/`; it does not change system Java. The Gradle helper uses
that local JDK/cache. Forge is pinned to 1.8.9-11.15.1.2318-1.8.9, MCP stable_22,
ForgeGradle 2.1-20211118.174922-42. First setup downloads Minecraft/Forge libraries.
The Go CLI currently supports only `version` and `doctor` (no background service).

Artifact: `mod/build/libs/bedwars-capture-0.1.0-diagnostic.jar`.
Install it in the **mods directory of your standalone Forge 1.8.9 instance**,
using a Java 8 runtime. Do not put it in a vanilla or 1.20 instance.
Installation into your existing game directory is deliberately manual.

## First live experiment

1. Launch your authenticated Forge 1.8.9 instance and join Solo Bed Wars in English.
2. Run `/bwcapture start` before the countdown. This captures selected message
   candidates and Bed Wars sidebar changes; it does not sample the player roster.
3. Once physically on your island, run `/bwcapture islands` to enable roster snapshots.
4. Play normally. `/bwcapture mark bed` (also `kill`, `final`, `spectating`, `rejoin`,
   `spawn`, `end`) adds an optional fixed label to help compare observations.
5. After the result screen, run `/bwcapture stop`, then `/bwcapture status`.

Files: `<game directory>/bedwars-companion/captures/capture-<id>.jsonl`.
`/bwcapture pause` disables roster sampling. Each capture is limited to 30 minutes
and 16 MiB, with a 1,024-sample memory queue. Dropped samples and write failures
are visible in status; normal closure writes a footer. A hard crash can leave a
partial last line or no footer. Captures older than seven days are pruned when a
new capture starts; the directory has a 128 MiB admission limit.

```sh
python3 scripts/capture_tool.py inspect /path/to/capture.jsonl
python3 scripts/capture_tool.py sanitise /path/to/capture.jsonl --output /tmp/review.jsonl
```

Sanitisation replaces sampled player names, UUIDs and common server identifiers,
removes absolute observation timestamps, and retains relative sample timing and
formatting. Add `--name MissingPlayer` for an identity absent from the roster.
**Review the copy before sharing or committing**: unseen names, sidebar dates,
cosmetic text and other personal text may need manual removal. Originals stay
local and are ignored by Git. Sanitised diagnostic samples remain temporary
research evidence, not durable regular-kill victim history; extract actor-only
observations when implementing the later recording core.

See [capture protocol](docs/CAPTURE_EXPERIMENT.md) and
[validation status](docs/VALIDATION.md) before treating any signal as reliable.
