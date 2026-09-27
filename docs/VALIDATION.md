# TOD — validation status — 2026-09-26

## September 27 roster preview implementation

Added the explicitly requested local communication and Discord roster slice.
See [Discord roster setup and scope](DISCORD_ROSTER.md) for configuration,
protocol, storage/retry behavior and validation limits. This does not complete
the full step 3 contract or step 6 Discord feature set. Live Discord publishing
and the updated mod's roster behavior remain to be exercised with the user's bot.

Team-only metadata reviewed from local capture
`2c861003-01c8-4025-8ac7-c0e405008d50` shows `Red0` with a Yellow `Y` prefix,
`Blue12` with both plain gray and explicit Blue prefixes, and a default White
format across multiple teams. Registered names and default format are therefore
not reliable team evidence. The preview requires a matching colored team-letter
prefix and keeps plain gray/unassigned profiles pending. Minimized team-only
examples are covered by Java regression tests; no player names were copied.

Verification passed: rebuilt/reobfuscated diagnostic JAR; 15 Java tests;
9 Go tests, including local HTTP/Discord simulation, state recovery and instance
locking; Go race detection and vet; 8 Python diagnostic regression tests.
The companion binary reports `0.2.0-roster-preview`. No real Discord message
was sent during these checks, and the game instance's installed JAR was not changed.

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

## Step 2: late-game and pregame-to-ending samples reviewed

Reviewed local capture `deda34bf-a3d3-4d52-807f-56f153073548`, manually labelled
3v3v3v3, started late in a game. All 86 records parse, sequence 1–85 is continuous,
footer reports 85 written and zero dropped, and closure is clean. Evidence:

- Manual islands marker at sequence 12; subsequent roster snapshots contain up
  to six profiles. Full initial lineup completeness is not established.
- Void-death candidates at sequences 44 and 48; credited final at sequence 55.
  The user recalled three deaths, consistent with these observations.
- End leaderboard candidates at sequences 56–58, followed by sidebar elimination
  and later lobby snapshots. No explicit victory/game-over candidate captured.
- No bed-break candidate; the local team's bed was already absent at first
  snapshot. End-screen sidebar changes do not establish an attributed bed break.
- No match-start or ordinary credited-kill example. Identity confirmation,
  complete roster/team attribution, variants and reconnect continuity remain open.

Raw data remains outside Git; this is an evidence reference, not a durable player
history or a validated parser fixture. Retention may remove the local source.

Second local capture `542ced7f-cbf4-483b-ac8a-34ec8a16349d` covers pregame through
the ending leaderboard and manual stop: 193 valid records, sequence 1–192,
zero reported drops, clean footer. Automatic start was triggered by pregame
context (sequence 3); roster sampling enabled at sequence 62 after active sidebar
samples. The initial roster includes two unassigned profiles; by sequence 67 it
contains twelve team-assigned profiles, three per team. Do not treat the maximum
raw profile count (14) as the player count.

Reviewed candidate evidence: three bed breaks (104, 133, 163), two ordinary
credited kills (103, 116), two uncredited void deaths (125, 136), nine finals,
and the ending leaderboard (185–187). The report now recognises the observed
knockback, cosmetic bed, and "Your Bed" variants. Start markers include repeated
countdown messages, not eleven distinct match starts.

The local sidebar and leaderboard report two kills absent from that capture.
The matching client log contains two `slipped into void for <actor>.` messages
at 00:27:33 and 00:27:35 local time. The old diagnostic filter rejects this wording.
Added an anchored candidate pattern and report support, with anonymised regression
examples in Java/Python tests. The original capture remains unchanged; its report
still correctly counts only two retained ordinary credited-kill messages, not the
two additional messages found in the client log. This demonstrates that clean
writer health does not establish complete event coverage.

Added experimental opt-in automatic capture and directory/watch review tooling.
Automatic capture recognises pregame/active sidebar shapes, gates roster after
consecutive active samples, and segments on world/context changes. It does not
infer winners or merge reconnects. Automatic start, roster gating and manual stop
have live evidence in the second sample. Automatic game splitting and reconnects
remain unverified. The new slipped-void filter still needs a targeted live check.

Diagnostic capture accepts manual Solo, Doubles and 3v3v3v3 mode labels via
`/bwcapture start <mode>`. Existing roster sampling retains multiple players per
team without a Solo restriction. All three modes still need broader live validation;
mode labels are user-supplied context, not automatic detection.

Implemented: opt-in commands, one-second changed snapshots, manually gated
island rosters, raw team metadata, selected formatted message candidates, fixed
manual markers, world/disconnect gate resets, asynchronous bounded JSONL writing,
restricted Linux file permissions, retention and inspection/sanitisation tools.

Automated checks:

- Ten Java tests: reviewed slipped-void selection and spoof rejection,
  experimental lobby/pregame/active sidebar classification,
  roster debounce/context transitions, plus selected candidates, common chat spoof rejection, orderly drain
  and permissions, retention, size limit/drop accounting, and disk-write failure.
- Six Python tests: formatting-aware identity pseudonymisation, crash/gap summary,
  candidate separation and coverage gaps, reviewed cosmetic/relative-bed patterns,
  limits/drops, and batch error isolation.
- Watcher smoke check: waits for a footer, reports a completed capture once, and
  exits cleanly on Ctrl+C. Review of the real sample matches the findings above.
- Synthetic JSONL inspection reports three observations, a clean footer, no
  sequence gaps and explicitly unverified coverage.

**Not yet verified:** commands/roster sampling inside an actual Solo match, additional
Hypixel message formats, identity confirmation, complete ordinary-kill visibility,
spectator/reconnect semantics, or live performance. Diagnostic selection is
conservative and can miss cosmetic variants. No production event parser exists.

Next action: restart with the rebuilt JAR; automatic capture is armed at client
startup. Compare retained kill messages with the local sidebar in one targeted game.
Complete the evidence table in `CAPTURE_EXPERIMENT.md` before
calling step 2 complete or building downstream event logic on assumptions.

## September 27 stopped-session review

The user identified their player as Dhanji and corrected their initial report
to confirm two finals, agreeing with the retained sidebar and final-kill messages.
They also reported two kills, "2 ins" and zero beds; the leaderboard's two kills
include those finals, while the sidebar shows zero ordinary kills. Whether
"ins" means wins is awaiting clarification. This is a user observation, not
resolved account/UUID evidence.

Three local segments were reviewed with `capture_tool.py review`:

- `d6412a35-5dd9-4774-ab05-a426d17b4f91`: 699 records, partial active-game start,
  automatic world-unload stop. Sidebar totals remain zero kills/finals/beds.
  Sequence 492 records the local player's final death; the local Yellow team
  is eliminated by the last sidebar (695). No ending leaderboard was retained.
- `2c861003-01c8-4025-8ac7-c0e405008d50`: 485 records, pregame start and
  automatic world-unload stop. Sequences 350 and 467 explicitly credit the local
  player with cosmetic `was oinked by` finals. Sidebar sequence 472 shows zero
  regular kills, two finals and zero beds, with Yellow the only remaining team.
  The ending leaderboard (470) lists the local player with two kills; that
  leaderboard number must not be interpreted as two ordinary kills. The sidebar
  supports a Yellow win, but no explicit result candidate was retained.
- `07b3cec1-8864-4d3d-84d5-d0e7cb67da0c`: 14 records, pregame only, then
  manual stop. This is not evidence of another played match.

All three have clean footers, continuous ordered sequences and zero reported
drops. Automatic segmentation across these world transitions has live evidence;
reconnect continuity and general boundary correctness remain unverified.
The retained bed cosmetic `was ripped to shreds by` is unclassified by the
report at first-segment sequence 326 and second-segment sequences 207/345.
Capture selection retained those messages; report recognition needs extension.
Raw captures remain local and unchanged. This review does not establish two
wins or complete session coverage, because the first segment starts mid-game.

## Reproduce

September 27 cosmetic fallback follow-up: diagnostic selection now accepts
anchored `<player> was <bounded wording> by <player>.` shapes. Review extracts
names transiently, emits name-free fallback evidence with prior-roster checks,
and keeps unfamiliar cosmetics out of ordinary credited-kill counts. Explicit
final markers remain separate from ordinary kills. Both matching roster names
are supporting context only, not verified attribution or account identity.
Java selection/chat-rejection tests and seven Python tests pass; the offline
Gradle test/build produced an updated diagnostic JAR. The new ordinary cosmetic
selection still needs a live check after installing the JAR and restarting.

```sh
./scripts/bootstrap.sh
./scripts/build.sh
./scripts/mod-gradle.sh setupDevWorkspace
./scripts/mod-gradle.sh runClient
```

The development client uses a separate `mod/run` directory and is intended for
offline loading checks. Use your normal authenticated launcher for Hypixel.
