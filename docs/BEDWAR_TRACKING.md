# Adapted BedWar tracking

Implemented October 1, 2026 at the user's request to reuse existing Hypixel
tracking code. [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) records the
upstream revisions, licences, reference source and modifications.

## Reused components

- `BedwarsEventManager.kt`: dispatch of start, ordinary kill, final kill,
  unattributed death, bed break, team elimination and winning-team messages.
- `BedwarsUtils.kt`: absolute local-player kills/finals/beds from the tab footer.
- `SessionDisplay.kt`: event-driven session counters, adapted to visible players
  and exposed via `/bwcapture stats [player]` rather than the upstream GUI.
- `BedWar-Repo/constants/ChatRegex.json`: bundled unchanged. No runtime download,
  Kotlin runtime, Mixin dependency, auto-updater or account request is introduced.

## TOD behavior

Tracking runs while a diagnostic capture is accepting records, on a multiplayer
connection to `hypixel.net` or a subdomain, in a Bed Wars sidebar context. Ordinary
events require active tracking and are rejected in detected pregame context.
Player chat shapes and interactive messages are rejected. Patterns match whole
messages; unsupported variants remain diagnostic candidates where selected by
the existing filter. This is not a claim of perfect spoof resistance.

The first active sidebar starts a partial segment. The recognized start banner
marks start evidence without resetting already observed events or counting the
banner twice. New captures reset segment counters; client-session counters
survive those resets. Session totals count observed messages, not API stat deltas.
Midnight does not reset anything. Restarting Minecraft clears the in-memory
session. World unload closes the segment even in manual capture mode; manually
start another capture to track the next world if automation is disabled.

Final kills never increment regular kills. Unattributed deaths award no actor;
uncredited void deaths have separate regular/final counts. Plain disconnect
messages are not counted as deaths. Only final events retain a victim relationship
in structured output; ordinary kills serialize the actor alone. Player names
remain unresolved visible names, with case-insensitive counter keys.

Bed targets use the named team. `Your Bed` uses the local player's explicit
team-letter prefix when available; otherwise its team remains unknown. A sidebar
bed disappearance or system-wide bed destruction awards no bed to a player.
An explicit parsed winning-team message ends counting; team elimination alone
does not stop spectating observations. Wins/losses and reconnect match merging
are not inferred. Session counters contain kills/finals/beds; they are not a
complete port of the upstream ratios, timers or win-streak display.

Local tab-footer totals are absolute, sampled once per second while active, and
shown separately from chat-derived counters. Repeated footer values never add
kills. Missing/unreadable footer data is unknown. Reading the vanilla footer uses
Forge reflection with both development and runtime field names. It fails closed
if a client changes that field. No API fallback runs.

Counters are bounded to 4,096 visible names per map; rejected counter updates
are reported. Capture size/time/queue/retention limits still apply. `pause` only
pauses roster publication/sampling, as before; event counting continues.
`stop` stops counting along with capture. No text-based kill deduplication is used:
two identical received messages count as two observations.

## Stored records and compatibility

Diagnostic schema version 1 adds `tracker_event` and `tracker_snapshot` types;
ordinary `snapshot` payloads also include `tracking`. Parser revision is
`bedwar-10b2499-tod-1`. The updated Python tools read these types and pseudonymise
both event names and counter-map keys, including names never sampled in a roster.
Older inspection scripts reject the new types; use the tools from this checkout.
Raw message candidates remain temporary diagnostic evidence under the existing
retention policy. The structured preview is not ingested by the Go companion.

Discord remains names/team colors only. SQLite sessions, delivery of event
counters to Discord, account identity resolution and interrupted-session recovery
remain separate planned work. Reconnect segments may describe the same game;
segment count must not be presented as a confirmed match count.

## Validation

Java regression tests cover source patterns, reviewed cosmetics, resets,
unattributed final void deaths, ordinary actor-only output, distinct identical
messages, relative beds, unknown teams, rejected chat, context gating, start
idempotency, segment/session isolation, absolute footer values and winner closure.
Python regression tests cover new-record reading and name sanitisation.

The reobfuscated JAR builds. A live game using this JAR is still needed to verify
current Hypixel formatting, footer access and full runtime behavior. Existing
live-capture findings are evidence for selected messages, not validation of the
new adapter. Install the rebuilt JAR, restart, and compare `/bwcapture stats`
with your tab totals and visible messages through a full game and a reconnect.
