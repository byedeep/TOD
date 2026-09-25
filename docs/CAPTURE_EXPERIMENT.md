# TOD — Step 2: live Solo capture experiment

## Goal and boundary

Determine what Forge 1.8.9 exposes during real matches. This is not a replay mod
or a production event parser. A selected message is **not** a verified event.
No live captures or validated Hypixel templates are bundled yet.

The mod samples Bed Wars sidebar changes and, after manual island confirmation,
tab-list profiles and scoreboard team metadata once per second. It retains
supplied UUID separately in each participant sample; all identities are unresolved.
No UUID is confirmed, resolved by username, or looked up through an API.

The message filter conservatively rejects colon-delimited, bracket-prefixed,
interactive and ordinary `>` chat. It retains a narrow set of English candidate
signals, including bed destruction, common deaths/finals and results. This
intentionally misses unknown cosmetics/languages. Candidates are diagnostic
text, never counters; this filter is not a proven spoof-resistant parser.
The first experiment must compare visible messages against retained candidates
and extend selection using reviewed examples, without enabling whole-chat logging.

## Runbook

Use the commands in the README. Capture at least three Solo matches, stopping
between games. Include a game spectated after elimination and a disconnect/rejoin
when practical. Do not disrupt other players just to manufacture an event.
After reconnect or loss of the Bed Wars sidebar, confirm islands again. Use
`pause` before changing games if the sidebar persists between transitions.
Start/stop/islands/markers are manual controls, not validated lifecycle detection.

For each case below, add an evidence reference with capture ID, sample sequence,
and manually observed outcome. Never replace “pending” with “verified” solely
because synthetic tests pass.

| Question | Evidence to collect | Current result |
| --- | --- | --- |
| Start/end detection | Countdown, island arrival, active sidebar, victory/defeat | Pending live capture |
| Roster completeness | Visible Solo roster vs tab profiles at spawn and later revisions | Pending |
| Teams vs rank colours | Profile display, scoreboard prefix/suffix/format, actual island team | Pending |
| Identity confirmation | Whether any supplied identity has reliable account evidence | Pending; supplied UUID is not proof |
| Beds | Attributed break, unattributed/system removal, sidebar transition | Pending |
| Finals | Credited final and unattributed final death, including void | Pending |
| Ordinary kills | Compare local kills, visible remote kills and retained messages | Pending; global completeness not promised |
| Cosmetic messages | Actual variants missed by the conservative filter | Pending |
| Spectating | Roster/game-type/sidebar after local elimination | Pending |
| Reconnect | Sidebar/roster before and after, same-game vs new-game evidence | Pending |
| Reported self totals | End screen/sidebar totals compared to observed messages | Pending |
| Performance | Playability, status drops, file size, writer failures | Pending live play |

## Fixture preparation

Stop capture; inspect summary. A missing footer, gaps or drops means incomplete
sampling. Malformed/truncated JSONL is reported with its line number; preserve
the original and work on a copy if trimming a crashed final line.

Pseudonymise with `capture_tool.py sanitise`; provide `--name` for unsampled names.
Manually review all strings. Keep matching aliases within one fixture; cross-file
identity continuity is not established by the automatic per-file mapping. Retain
formatting needed for team attribution but remove secrets, private chat and
unnecessary personal text. Do not commit raw captures. Retain diagnostic evidence
only as long as needed (maximum seven days); promote only sanitised, minimised
system-message examples to parser fixtures. Ordinary-kill victim pairs must not
become durable match records.

For each reviewed fixture record: English/Solo, mod/build version, whether it was
captured from spawn, missing sections, actual expected facts and evidence limits.
Write a capture findings report before implementing the production parser.

## Gate for step 3

Proceed once the mod has loaded in a real client and we have reviewed enough
samples to define initial supported start, team, bed/final and common kill cases.
Explicitly list unknown variants, incomplete kill coverage and unresolved
identity/reconnect evidence. Unsupported cases must stay unknown downstream.
