# TOD prototype plan

Based on [`TOD.md`](TOD.md).

## Agreed target

Full feature preview, Linux first, standalone Forge 1.8.9, English Solo Bed Wars.
One Minecraft client observes matches. The user supplies live gameplay testing and
configures Discord/Hypixel credentials locally when integrations are reached.
“Recording” means match event logging, not video, audio, movement or world replay.

## Ordered work

1. **Build foundation (current):** Java mod and Go companion projects; isolated,
   pinned Forge-compatible Java/Gradle tooling; reproducible Linux builds. Gate:
   build both artifacts and verify the mod loads in Forge.
2. **Capture experiment (current):** opt-in diagnostic capture of system-message
   candidates, roster/team revisions and scoreboard/lifecycle signals. Capture
   real Solo spawn, kills, finals, beds, spectating, reconnect and end screens.
   Sanitise fixtures. Gate: document demonstrated signal coverage, identity
   evidence, and unsupported cases. Do not claim full ordinary-kill coverage.
3. **Local contract:** versioned observation envelopes; authenticated loopback
   health, batch ingest, alert WebSocket and session-end endpoints; persistent
   bounded outbox, stable IDs, sequences and acknowledgements after commit.
4. **Recording core:** SQLite migrations, dated sessions and match ordinals,
   deterministic lifecycle/counter reducers, roster reconciliation and coverage.
   Atomically commit deduplicated observations, counters and delivery jobs.
5. **Live logging integration:** connect validated adapters/parsers to the outbox;
   status/session controls and explicit interrupted-session recovery. Validate a
   real match against its stored events and partial/full observation labels.
6. **Discord:** Gateway connection, session threads, coloured team rosters,
   counters, paginated bed/final history, summaries, match/session commands,
   persisted message bindings and outage-safe delivery queues.
7. **Account stats:** confirmed identities only; six-hour cache, request
   coalescing, rate limits, separate lifetime/mode stats and honest unknowns.
8. **Saved players:** named lists, notes/modals, authorisation, manual username
   resolution, once-per-match Discord/mod alerts; unresolved match-only notes.
9. **Repeat encounters:** session-local confirmed players and complete lineups,
   recorded-games-ago, prior counters/coverage/results, editable deduplicated
   notices. Solo validated live; multi-member lineups validated with fixtures.
10. **Packaging and validation:** automatic companion startup, single-client
    lock, restricted credentials/config, shutdown/crash recovery, backup/session
    deletion, install guide and Linux bundle. Run end-to-end failure tests.

## Boundaries and defaults

Work currently authorised for implementation: steps 1 and 2 only. A minimal Go
CLI is scaffolding, not the step 3 service. No SQLite ingestion, Discord, public
API calls, identity confirmation claims or production parsers in this stage.
Steps 1–2 remain pending wherever real client/live match evidence is missing.

Later defaults: Asia/Kolkata display timezone, UTC storage, no idle expiry or
midnight split, 1-second roster debounce, 3-second Discord edit debounce,
6-hour stats cache, optional diagnostics retained for at most 7 days.
No Windows/Lunar/multi-client/public distribution/background service in preview.
No concealed-identity recovery or API polling for match counters.

## Acceptance across the complete preview

- Hidden pregame players trigger no account lookups; early events survive roster loading.
- Final kills never count as regular kills; unattributed deaths/beds award no actor.
- Regular kills retain actor-only counts, not durable victim relationships.
- Retry IDs are idempotent; distinct identical-text events remain distinct.
- Partial capture, reconnects and queue loss preserve coverage gaps.
- Sessions/threads survive midnight; rejoining retains match ordinal/counters.
- Identity renames retain notes; nicknames never match solely by visible name.
- Repeat cards handle colour changes, partial overlaps and roster corrections.
- Discord/API outages never block local capture; permissions and mentions are controlled.

## Delivery checkpoints

See `docs/CAPTURE_EXPERIMENT.md` for the live test protocol and
`docs/VALIDATION.md` for actual completed checks and remaining evidence.
