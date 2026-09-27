# TOD — diagnostic prototype

Current work: diagnostic capture plus a roster-only local/Discord preview from
[the prototype plan](PROTOTYPE_PLAN.md).
This logs selected match signals, **not video or world replays**. The diagnostic
mod makes no account/API requests. An optional local Go companion can now publish
visible names and team colors to Discord. See [Discord roster setup](docs/DISCORD_ROSTER.md).

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
The Go CLI supports `version`, `doctor`, and the foreground `serve` roster service.
It does not start automatically with Minecraft.

Artifact: `mod/build/libs/bedwars-capture-0.1.0-diagnostic.jar`.
Install it in the **mods directory of your standalone Forge 1.8.9 instance**,
using a Java 8 runtime. Do not put it in a vanilla or 1.20 instance.
Installation into your existing game directory is deliberately manual.

## First live experiment

### Automatic test capture (experimental)

After installing the updated JAR and restarting Minecraft, automatic capture is
already armed. Queue and play normally; no capture command is required. The
automatic mode label is `unspecified` because the game mode is not yet detected.
You may optionally run `/bwcapture auto 3s`, `solo`, or `doubles` before queueing
to add a manual mode label. `/bwcapture stop` (or `/bwcapture auto off`) stops
recording and disarms automation for the rest of the current client run; the next
Minecraft restart arms it again.

The recorder starts on a recognised pregame sidebar (player count plus waiting
or countdown), or starts a **partial** capture if an active sidebar is already
visible. Two consecutive active-sidebar samples enable roster capture without
`islands`. The log distinguishes this experimental gate from manual confirmation.
`pause` suspends roster sampling for the current capture; `islands` resumes it.
Spectating does not stop capture. World unload, a return to pregame after active
play, or five consecutive unrecognised sidebar samples closes the file. The next
recognised game context starts a separate file. Disconnect/rejoin therefore
produces separate diagnostic segments, not confirmed separate matches.

Detection samples once per second and may miss initial messages or unfamiliar
sidebar layouts. The lobby title alone does not trigger recording. Existing size,
time and retention limits still apply; a storage failure or limit disables auto
capture until explicitly armed again. **Automatic detection still needs a live
playthrough.** These are diagnostic boundaries, not production match detection.

For automatic reports in a terminal, from this project run:

```sh
./scripts/watch-captures.sh
```

This watches the `TOD-Bed-Wars` Prism instance and prints a report when each
capture finishes. Pass another captures directory as its first argument if needed.
Ctrl+C stops the watcher; it does not stop Minecraft capture. No reports are saved
to disk automatically. To review existing files, including interrupted captures:

```sh
python3 scripts/capture_tool.py review /path/to/captures
```

Reports show writer health, candidate counts and sequence references, missing
examples, and checks still requiring your observation. A missing example does
not prove an event never happened, and a clean file does not prove full coverage.
Unfamiliar `<player> was <wording> by <player>.` cosmetics are retained for
diagnostic review. `cosmetic_fallback_evidence` reports sequence references,
final markers and whether both names were sampled earlier in the capture;
it does not add ordinary kills or expose the extracted names. A roster match
does not verify attribution. Other unknown sentence structures may still be missed.
`uncredited_void_deaths` counts observed `<player> fell into the void.` messages
across all players in each capture, with a total and separate non-final/final
counts. Credited knockbacks and `slipped into void for <killer>` are excluded.
An uncredited final void death awards nobody a kill; it also appears in the
report's final-message evidence. Uncredited means no killer was named, not proof
that the death was the player's fault.
Watch mode waits for a footer; use one-shot review after a crash or forced exit.

### Manual capture

1. Launch your authenticated Forge 1.8.9 instance and join Solo, Doubles or
   3v3v3v3 Bed Wars in English.
2. Run `/bwcapture start solo`, `/bwcapture start doubles`, or
   `/bwcapture start 3v3v3v3` before the countdown. `duos` is an alias for
   `doubles`; `3s` and `threes` are aliases for `3v3v3v3`. Plain
   `/bwcapture start` still works with an unspecified mode. This captures selected message
   candidates and Bed Wars sidebar changes; it does not sample the player roster.
3. Once physically on your island, run `/bwcapture islands` to enable roster snapshots.
4. Play normally. `/bwcapture mark bed` (also `kill`, `final`, `spectating`, `rejoin`,
   `spawn`, `end`) adds an optional fixed label to help compare observations.
5. After the result screen, run `/bwcapture stop`, then `/bwcapture status`.

Modes are manually labelled, not detected or validated. Roster capture records
each tab-list profile with its raw team metadata, including multiple teammates.
Stop and start a separate capture for each match. You can start mid-match, but
the log will be missing earlier events. After installing a rebuilt JAR, restart
Minecraft; replace the previous JAR rather than keeping two copies of the mod.

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
