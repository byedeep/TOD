# TOD — technical design

Version 1.1

Minecraft 1.8.9   |   Forge and Discord   |   22 September 2026

Build a personal companion that records matches after player identities become visible on the islands. It groups the roster by team, publishes available account stats, records bed breaks and final kills, counts observed kills, and recalls notes for saved players. A local Go companion starts with Minecraft and stores the data in SQLite.

Status: proposed implementation specification. No mod has been built or tested as part of this document. Hypixel message formats, identity availability and event coverage require a capture experiment before implementation commitments.

### Version one requirements

| Feature | Required behaviour |
| --- | --- |
| Roster | Start collecting real names when the game has started and players are on their islands. Group by team and reconcile later updates. |
| Bed history | Record who broke which team’s bed, the attacker’s team and elapsed match time. |
| Final kill history | Record killer, victim, both teams and time. Preserve unattributed final deaths without inventing a killer. |
| Kill counts | Show observed regular kills and final kills separately, plus their sum, for players and teams. |
| Saved players | Named lists and notes attached to confirmed identities. Alert once per match when a saved player is observed. |
| Sessions | Name the session by its start date and time. Keep it intact past midnight, with numbered matches and the full date range. |
| Startup | Start the bot companion automatically with Minecraft after one-time setup. |
| Repeat encounters | Recognise players and full lineups seen earlier in this session; report games ago and their recorded stats from that game. |

### Architecture and responsibilities

The Java Forge mod reads client state and incoming game messages. It copies observations to a local outbox and delivers them to the companion. The companion validates and stores events, builds counters, performs cached Hypixel lookups, and maintains the Discord connection. Discord commands update saved lists and notes; encounter alerts return to the mod through an authenticated local connection.

Use one Minecraft client as the authoritative observer in version one. Multi-client capture is deferred: independent clients may receive the same event with different local IDs, so text alone cannot safely deduplicate their observations.

### Evidence boundary

The hidden pregame roster is a user-confirmed requirement. Official sources support the public stats API and Discord interfaces [1–4]. The roster reader, event parser and lifecycle below are proposed client logic, not an official Hypixel feed of all Bed Wars actions.

## Sessions and match lifecycle

### Dated session names

A session groups consecutive matches in one play period. Create it at the first confirmed active Bed Wars match, rather than when Minecraft opens. Default the display timezone to Asia/Kolkata for this installation; store UTC timestamps and the session timezone separately.

```text
Session: Bed Wars 2026-09-22 21-30 IST
Match:   Match 001 — Doubles — Map unknown
IDs:     session_id and match_id are independent UUIDs
```

Names are editable labels, never database keys. Add a short ID if two sessions start in the same minute. Midnight never ends or splits a session. Playing from 22 September at 10 PM until 23 September at 2 AM is one session named for its 22 September start. Show the complete start and end timestamps in its summary; matches retain their actual individual dates.

End a session manually or on normal Minecraft shutdown. Disable automatic idle expiry by default so breaks between games do not silently split the evening. A disconnect leaves the session open. After a crash, mark it interrupted and offer to resume the same session ID or start a new one; never decide from the calendar date alone. Old queued observations retain their original IDs.

### State machine

| State | Handling |
| --- | --- |
| WAITING | Pregame or lobby. No attempt to resolve hidden names or publish a real-player roster. |
| STARTING | Gameplay start evidence arrives. Buffer relevant messages immediately, before roster and team assignments finish loading. |
| ACTIVE | Active-game evidence and visible participant data agree. Bind buffered events to the match and publish the roster. |
| INTERRUPTED | Disconnect or loss of observation opens a coverage gap. Keep the existing match and its events. |
| ENDED | An explicit recognised result establishes the outcome. Finish the report and publish a summary. |
| ABANDONED | Recording ends or a different game begins without a known result. Retain a partial summary. |

Combine location context, Bed Wars scoreboard state and validated start/result signals. A fixed sleep after server join is not a start detector. Hypixel Mod API may supply useful location context [5]; validate supported fields during the prototype. A server identifier alone is not a unique match ID.

Use a proposed one-second roster debounce before the first Discord publication, then accept corrections. Never discard events while waiting for names. An empty roster stays in a collecting state. Spectating after elimination remains the same match; the local player’s death does not prove the match ended.

### Reconnect decisions

Resume an interrupted match only when retained context and current server, roster and game state strongly indicate the same game. Otherwise begin a partial observation of a new match and preserve the prior match as unresolved. Never reset existing counters just because the world reloads.

## Team roster identity and notes

### Roster extraction

Prototype against Forge 1.8.9 client structures: NetHandlerPlayClient.getPlayerInfoMap(), NetworkPlayerInfo profiles and scoreboard ScorePlayerTeam membership. These names are implementation starting points to verify against the selected mappings. Nearby world entities cannot represent the full roster because distant players may not be loaded.

Assign each participant a match-local ID. Preserve supplied UUID, visible name, raw team formatting and semantic team ID separately. Team colour must come from validated team information, not the rank colour in a display name. Spectators remain distinguishable from active participants.

| Team order | Minecraft code | RGB |
| --- | --- | --- |
| Red | §c | #FF5555 |
| Blue | §9 | #5555FF |
| Green | §a | #55FF55 |
| Yellow | §e | #FFFF55 |
| Aqua | §b | #55FFFF |
| White | §f | #FFFFFF |
| Pink | §d | #FF55FF |
| Gray | §7 | #AAAAAA |

Omit absent teams and place unresolved participants under Team pending. Validate this conventional mapping against actual captures. Discord receives an RGB embed colour rather than a Minecraft formatting code. Team attribution on a historical event is retained even if a later roster update changes membership.

### Nicked and unresolved players

A UUID supplied to the client is not automatically a confirmed real-account identity. Keep supplied_uuid separate from nullable confirmed_uuid. Identity status is confirmed, unresolved or concealed only when supported by evidence. A failed API request or missing profile does not prove a nick.

Display unresolved participants by their visible name and team with Account stats unavailable. Capture their match actions under the participant ID. Do not attach a same-name account’s statistics, infer a hidden UUID or promise recognition across nick changes. Hypixel restricts de-anonymization [2].

### Lists and encounter notifications

Store confirmed UUID, list membership, note author, note text and timestamps within the configured Discord scope. Resolve manually entered usernames before saving. Unresolved players can receive a match-only note, which cannot support future identity matching.

Compare confirmed identities whenever the roster gains a participant or an identity becomes confirmed. Saved-list notices use a persisted scope, match and UUID key to avoid duplicates. Separately recognise any player or complete lineup encountered earlier in this session, even when nobody saved them to a list. Combine repeat notices and saved notes in one card when both apply. Last seen means observed in your recorded matches, not global online tracking.

## Event history and match counters

### Capture and parser

Use an incoming-chat hook such as Forge ClientChatReceivedEvent and periodic scoreboard checks. Copy minimal immutable data on the game thread, then parse and transmit off-thread. Preserve formatting for attribution before stripping it for text matching.

Maintain a versioned catalogue of real Hypixel system-message templates. Recognise final-kill markers before ordinary kill patterns. Match complete messages and reject ordinary player chat, including fake death messages typed by users. Cosmetic messages and language variants require fixtures. Unknown text never produces a guessed event.

| Event | Persisted meaning |
| --- | --- |
| BED_BROKEN | Known attacker, attacker team, victim team, time and evidence. Unknown attacker stays null. |
| FINAL_KILL | Credited killer and victim, both teams, time and evidence. Increment final kills and final deaths once. |
| FINAL_DEATH | Victim is known but no killer is credited. Increment final deaths without crediting a player. |
| REGULAR_KILL | Actor-only count observation. Do not retain a regular-kill victim relationship in durable history. |
| BED_REMOVED | System or unattributed bed loss. Update bed state without awarding a bed break. |

```text
04:12  PlayerA [Red] broke Blue team’s bed
04:35  PlayerA [Red] final-killed PlayerB [Blue]
05:08  PlayerC [Green] suffered an unattributed final death
```

A bed belongs to a team, so a bed-break victim is a team, not an arbitrary teammate. Buffer unresolved participant references and reconcile them when roster information arrives. A scoreboard bed disappearance alone proves neither an attacker nor the exact break time.

### Counter definitions

regular_kills counts credited non-final kills. final_kills counts credited final kills. total_kills equals their sum. beds_broken counts attributed bed breaks. final_deaths counts observed final eliminations. A final kill must never also increment regular_kills. Team totals use team membership at event time.

Every counter carries coverage metadata. Zero observed kills is not proof of zero actual kills. Show partial coverage for late recording, disconnects, queue loss or unsupported event formats. Even an uninterrupted connection does not prove the client received every opponent’s ordinary kill.

### Reported totals and limits

When a scoreboard or end screen explicitly reports the local player’s totals, preserve those separately and reconcile the display with the observed counters. Do not invent individual events from a total. A top-killer leaderboard cannot supply every player’s count. Until capture tests prove full coverage, label other players’ counts as observed.

Do not derive match counters from repeated public API snapshots. That cannot reliably attribute actions to this match, and Hypixel’s policy restricts continuous polling for stat histories [2]. Record bed and final-kill history from client evidence instead.

## SQLite data model and local protocol

The Go companion owns SQLite writes, foreign keys and migrations. Commit each accepted observation, its derived counter changes and outgoing Discord job atomically. Discord messages are views of the database, not the source of truth.

| Record | Essential fields |
| --- | --- |
| sessions | id, display_name, timezone, started_at, ended_at, status |
| matches | id, session_id, ordinal, map, mode, context, start/end, outcome, coverage |
| players and participants | Confirmed UUID and name; match-local participant ID, visible name, supplied UUID, confirmed UUID nullable, team, identity status |
| match_events | id, match_id, type, actor/victim nullable, event-time teams, observed_at, elapsed_ms, parser version, evidence |
| counter_observations | Unique event ID, participant, team, kind and delta; no ordinary-kill victim field |
| player_match_stats | Match + participant key, observed counters, coverage and separate reported self totals |
| lists notes alerts | Scope, list membership, confirmed UUID, note author/text; unique alert per scope + match + UUID |
| cache and delivery | UUID stat cache with expiry; accepted event IDs; Discord jobs; thread/message IDs and published revision |

### Delivery and idempotency

Assign an event UUID once in the mod before writing its outbox entry. Retries reuse it. A UNIQUE(source_id, event_id) constraint prevents repeat delivery from changing totals. Different real kills may have identical text, so text hashes cannot be event IDs. Retain roster revisions and pending references for later attribution.

Bind the companion only to 127.0.0.1 on an OS-assigned port. Use a restricted rendezvous file and a per-installation authentication secret. Reject browser origins, unauthenticated requests, excessive payload sizes and incompatible schema versions.

```text
GET  /v1/health         Protocol version and status
POST /v1/observations   Ordered batch; acknowledge after commit
GET  /v1/events         Authenticated WebSocket for alerts
POST /v1/session/end    Idempotent session closure
```

```text
Envelope: schema_version, event_id, source_id, sequence,
          session_id, match_id, observed_at, type, payload
FINAL_KILL payload: actor_id, victim_id, actor_team,
                    victim_team, elapsed_ms, parser_version
```

UTC timestamps support persistence; a monotonic clock measures elapsed time during a connection. If the true match start is unknown, label elapsed time as since recording began. Ordinary-kill observations retain actor-only deltas for safe retry and rebuild, satisfying the requirement to store detailed victim relationships only for final kills.

Keep match history and notes until explicitly deleted. Start with a configurable six-hour account-stat cache. Optional diagnostic samples are limited to relevant system messages, bounded and deleted after seven days; exclude general chat and secrets. Offer a database backup and session deletion action.

## Discord views and public player statistics

### Dated session threads

Create one thread per session in a configured channel, or use session-prefixed messages if thread permissions are missing. Keep the same thread after midnight. Persist bindings so reconnects update existing messages. Show the date range, timezone, duration, match list, known results and observed team totals. Repeat-encounter cards link to the prior match and show its recorded counters.

Each match has a header, roster grouped by team, counters and a bed/final-kill timeline. Use one colour-matched embed per team. Eight team embeds fit within Discord’s ten-embed limit, but all embeds in a message share a 6000-character limit [4]. Split messages as needed and put additional player stats behind a detail action.

### Illustrative match summary

| Red team | Regular kills | Final kills | Beds |
| --- | --- | --- | --- |
| PlayerA | 7 | 3 | 1 |
| PlayerD | 4 | 1 | 0 |
| Observed total | 11 | 4 | 1 |

Show the coverage explanation immediately below the counts, for example: Recorded from match start; opponent kill coverage unverified. Keep lifetime stats clearly separate from this-match counters. Paginate the detailed timeline; ordinary kills update counts without adding victim entries.

### Bot actions

| Action | Behaviour |
| --- | --- |
| /player name | Show cached public stats and permitted saved notes. |
| Save player and Add note | Select a confirmed participant, choose a list and enter a note in a modal. |
| /list name | Show saved entries within the current Discord scope. |
| /session current or id | Show the dated session and its match list. |
| /match id | Show roster, timeline, counters and coverage. |
| /session rename or end | Change the display name or close the play session. |

Receive slash commands, buttons and modals through the outbound Discord Gateway connection; no public inbound server is required [3]. Defer interactions promptly if processing may exceed Discord’s initial response window. Restrict note editing to configured users or roles and suppress mentions in user-provided text.

### Stats enrichment

For confirmed UUIDs, check the cache then call GET /v2/player as needed [1]. Validate Bedwars and available level/achievement fields against actual responses. Keep overall and selected-mode statistics separate. Show stars, wins, losses, beds, KDR and FKDR where supported; unavailable streaks stay unknown.

Coalesce requests for the same UUID, bound concurrency and honour rate-limit headers and 429 responses. Publish the roster immediately while stats load. On API failure, show cached data with its timestamp. Missing fields do not become zero, and a zero denominator is labelled explicitly rather than converted to a misleading finite ratio.

## Automatic startup and operational behaviour

### Starting with Minecraft

Install the Forge mod and a Go companion executable for the selected OS and architecture. On Minecraft startup, check the local health endpoint and launch the companion with Java ProcessBuilder if no compatible instance exists. Use fixed arguments, no shell construction and no credentials in command-line arguments.

A per-user process lock prevents duplicate bot instances. Version one allows only one active capturing Minecraft client; additional clients receive a capture already active message. Publish the local port after the companion has successfully bound and loaded its configuration.

One-time setup creates and installs the Discord bot, selects the server/channel, and configures Hypixel application access. Store tokens outside the mod in an OS credential store or user-only configuration. Never ship a shared bot token or API key. A publicly distributed product should use service-owned Hypixel access rather than collecting user keys in a public mod [2].

### Failure handling

| Condition | Response |
| --- | --- |
| Companion down | Queue a bounded disk outbox and retry with backoff; never block the rendering thread. |
| Disk or queue full | Record a coverage gap and show a clear local status. Dropped events invalidate complete counters. |
| Discord unavailable | Continue SQLite recording. Retry queued work and collapse obsolete routine edits. |
| Client crash | Recover committed data and resend unacknowledged IDs. Mark the prior session interrupted. |
| Normal shutdown | Attempt a bounded flush and final session write, then exit the companion. Do not freeze Minecraft indefinitely. |
| Parser uncertainty | Keep unresolved attribution and optionally retain a bounded diagnostic sample. Do not fabricate a result. |

Debounce routine Discord edits to a proposed three-second interval and honour Discord’s returned rate limits. Prioritise saved-player alerts and final summaries. Existing messages remain after Minecraft closes; interactive commands need the companion online. An optional background mode can keep it running after the game exits.

### Suggested modules

```text
mod/capture        Roster, scoreboard and message adapter
mod/parser         Versioned Bed Wars templates
mod/transport      Persistent outbox and local API client
companion/ingest    Validation, ordering and deduplication
companion/reducer  Deterministic counters and lifecycle
companion/discord  Views, commands, interactions and alerts
companion/stats    Public API client and cache
migrations         SQLite schema versions
fixtures           Sanitised captured messages and rosters
```

Pin the Forge 1.8.9 toolchain after a build experiment [6]. Keeping Discord in Go avoids loading modern bot dependencies into legacy Java. The reducer transforms accepted observations into match state and counters; test it with stored fixtures independently of Minecraft. Standalone Forge is the target; Lunar compatibility is not assumed.

## Repeat encounters within a session

### Player and team recognition

Check every confirmed participant against earlier matches with the same session_id; list membership is not required. Compare confirmed UUIDs so renamed players still match. Exclude the local player from repeat notifications. Continue tracking teammates and opponents, but consolidate an unchanged own-team lineup into one notice to avoid repetitive cards.

A repeated team means the same complete set of confirmed player UUIDs, irrespective of team colour. Canonicalise the original lineup as mode plus sorted UUIDs and retain its member list. Red in one game can match Blue in the next. One returning member of a different lineup is a repeated player, not the same team; label any overlap explicitly.

Use the match’s initial full lineup, not the shrinking list of survivors. Wait for sufficient evidence of complete team membership before making an exact-team claim. Nicked or unresolved identities prevent a confirmed whole-lineup match. Roster corrections recompute affected notices and edit an existing card instead of creating duplicates.

### Calculating how many games ago

Give each started recorded match a monotonically increasing ordinal inside its session, including matches with partial or abandoned recordings. Pregame queues do not count. For the current match ordinal N, find the greatest earlier ordinal M containing the UUID or exact lineup. N minus M equals the number of recorded games ago: one means last game, two means two games ago. Never compare across sessions or reset the ordinal at midnight.

Use the most recent match for the main card; include the total encounters this session and an action to open older ones. Explicitly say recorded games ago if this installation may have missed intervening games. Rejoining the same match must retain its ordinal.

### Prior match stats on the notification

Read the earlier match’s stored player_match_stats and event history, not the latest lifetime API response. For players show regular kills, final kills, beds, their former team and the prior match result when known. For a repeated lineup show every member’s row plus team totals. Preserve partial coverage and unknown values exactly as recorded; corrected prior records update the card.

```text
Match 005 — Blue team
Same lineup as 2 games ago — Match 003, previously Red
PlayerA: 7 regular kills, 3 final kills, 1 bed
PlayerD: 4 regular kills, 1 final kill, 0 beds
Previous team totals: 11 regular kills, 4 final kills, 1 bed
Coverage: observed counters; full kill coverage unverified
```

### Storage and delivery

Index participants by confirmed_uuid and match_id, and matches by session_id and ordinal. Store team_rosters(match_id, team_id, mode, canonical_members, completeness). For sent notices persist current_match_id, subject_type, subject_key, previous_match_id and message binding. A unique current-match/subject key makes retries safe. Post after identities become visible on the islands, then attach saved notes if present.

Tests must cover a colour change with identical members, one-player overlap, a nickname, a roster correction, two appearances in one session, and an encounter after midnight. A final kill remains separate from ordinary kills in every historical card.

## Implementation plan and acceptance criteria

### Build order

1. Capture experiment. Build a minimal diagnostic mod and gather sanitised roster and system-message fixtures from island spawn to game end. Verify team formatting, identity handling, kill-message variants, spectator behaviour and actual ordinary-kill coverage.

2. Recording core. Implement dated sessions, match lifecycle, SQLite ingestion, retry IDs and the event reducer. Establish correct bed, final-kill and actor-only ordinary-kill accounting before adding external services.

3. Discord delivery. Add session threads, team rosters, timelines, summaries and cached player stats. Add session-local player and lineup lookup with prior-match counters, then saved lists, note modals and automatic companion startup.

### Acceptance tests

- Hidden pregame names cause no real-account lookups. After island spawn, participants appear under correct teams and buffered events resolve.

- A final kill increments final_kills once and never also regular_kills. Unattributed final deaths do not award a killer.

- Bed events identify the victim team. System destruction and scoreboard-only bed loss do not award player credit.

- Replaying an observation ID changes no totals. Two genuine identical-text observations with distinct IDs both count.

- Regular kills retain actor-only count records; durable killer-victim history contains only final kills.

- Confirmed renamed accounts retain their notes. Nicknames do not match by name alone. Alerts occur once per match.

- Late starts, dropped observations and reconnect gaps produce partial labels. Rejoining does not erase those gaps.

- A session spanning 10 PM to 2 AM retains its ID and thread. Different same-day sessions remain distinct; API or Discord outages do not stop local recording. Repeat notices correctly distinguish the last game and two games ago, preserving prior-game counters and coverage.

### Questions resolved by the capture experiment

Does the client receive every ordinary kill, or only some? Which cosmetic and language variants occur? Can all team memberships be resolved after spawn? Which personal counters are explicitly reported? What evidence reliably distinguishes rejoining the same match from a new game? Version one promises only the coverage demonstrated by these tests.

### Policy and release boundaries

Hypixel restricts de-anonymization, continuous API polling for stat histories, projects solely targeting particular players and uses that compromise game integrity [2]. Local encounter notes and event capture do not establish approval for live opponent stats or alerts. Review those features against current rules before release and do not present this design as Hypixel approved.

No continuous monitoring of saved players is required: recognition occurs only when this mod sees a confirmed identity in its current match. No combat automation, concealed-identity recovery or reconstruction of unobserved games is part of this design.

## Official references and implementation assumptions

These references support the platform interfaces and constraints. They do not guarantee that a Bed Wars message parser can observe every event. Official content was consulted earlier in this conversation; a fresh web refresh for this document failed. Recheck policies and dependency requirements before release.

### 1 Hypixel Public API

Player endpoint, UUID parameters, game-stat storage, authentication and rate limits. The endpoint documentation does not enumerate a complete Bed Wars schema.

<https://api.hypixel.net/>

### 2 Hypixel API Policy

Application registration, credential handling, caching, automated stat collection, targeted tracking and game integrity. Earlier retrieved policy stated a last update of 16 July 2026.

<https://developer.hypixel.net/policies/>

### 3 Discord interactions

Receiving and responding to commands, components and modals through Gateway events or HTTP. Gateway delivery supports a locally running bot without a public inbound endpoint.

<https://docs.discord.com/developers/interactions/receiving-and-responding>

### 4 Discord message resource

Embed colours, fields and message size limits. Check limits when implementing the renderer.

<https://docs.discord.com/developers/resources/message>

### 5 Hypixel Mod API

Official Minecraft plugin-message integration. Inspect the current supported packets and compatible client integration before relying on location fields.

<https://github.com/HypixelDev/ModAPI>

### 6 Forge development downloads

Official Forge 1.8.9 development kit and builds. Validate the Java and Gradle toolchain in an isolated build before selecting dependencies.

<https://files.minecraftforge.net/net/minecraftforge/forge/index_1.8.9.html>

### Chosen defaults

Asia/Kolkata display timezone, disabled automatic idle expiry, one-second roster debounce, three-second Discord edit debounce, six-hour stats cache and seven-day optional diagnostic retention are configurable design defaults. Midnight never creates a session boundary. Local session and event UUIDs are not official Hypixel match IDs.
