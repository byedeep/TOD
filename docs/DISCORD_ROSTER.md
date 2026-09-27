# Discord roster preview

This preview publishes visible player names grouped into team-colored cards in a
Discord text channel. It updates the same message as the roster changes and uses
a new message after a world/context boundary. No account statistics, UUIDs,
kills, beds, notes, or diagnostic chat are sent to Discord.

## One-time setup

1. Create an application and bot in the [Discord Developer Portal](https://discord.com/developers/applications).
   Install the bot in your server with the `bot` scope and the channel permissions
   **View Channel**, **Send Messages**, and **Embed Links**. Select an ordinary
   server text channel. No privileged intents or Gateway connection are needed
   for this posting-only preview; the bot may appear offline in the member list.
2. Enable Developer Mode in Discord, right-click the destination channel, and
   copy its channel ID.
3. Create a private config from the supplied template:

   ```sh
   install -d -m 700 "${XDG_CONFIG_HOME:-$HOME/.config}/bedwars-companion"
   install -m 600 companion/discord.example.json "${XDG_CONFIG_HOME:-$HOME/.config}/bedwars-companion/discord.json"
   ```

   Edit that local `discord.json`, replacing `bot_token` with the bot token and
   `channel_id` with the copied channel ID. Keep the token in the local file,
   outside Git and chat. The companion requires file permissions `600`.
   Use `--config /path/to/private/discord.json` for a different location.

## Build and run

```sh
./scripts/build.sh
```

Replace the previous mod JAR in your Forge 1.8.9 instance with
`mod/build/libs/bedwars-capture-0.1.0-diagnostic.jar`, then restart Minecraft.
Run the companion in a terminal:

```sh
./bin/companion serve --game-dir "$HOME/.local/share/PrismLauncher/instances/TOD-Bed-Wars/.minecraft"
```

For another instance, pass its game directory (the directory containing `mods/`).
Start the companion once to opt that instance into publishing, then play normally.
The mod notices the connection file even if Minecraft was already running.
Keep the companion running for Discord updates; Ctrl+C stops the companion.

After active Bed Wars sidebar detection and two stable roster samples, the
mod sends a roster. Teams get Red, Blue, Green, Yellow, Aqua, White, Pink or Gray
cards. Unknown team metadata goes under **Team pending**. Rank colors in tab
display names are not used. Mapping requires the explicit colored team-letter
scoreboard prefix: internal registered names and plain gray prefixes can be
misleading in the reviewed captures. Explicit spectator game types are excluded.
Roster changes update the message; an unchanged roster refreshes approximately
every 30 seconds. Every message shows when its roster was last observed.

`/bwcapture status` includes connection, pending, and dropped-update status.
`/bwcapture stop` or `/bwcapture auto off` disables both capture automation and
roster publishing until re-enabled or Minecraft restarts. `/bwcapture auto`
re-enables them. During an active diagnostic capture, `pause` also pauses roster
publishing, and `islands` resumes it. The Discord roster still requires recognized
active-game context; manual confirmation never publishes hidden pregame names.

## What is implemented

- An authenticated API bound to `127.0.0.1` on a random port. The private
  `<game-dir>/bedwars-companion/bridge.json` contains only the local port and
  a per-installation local secret. The Discord token never enters the mod.
- `GET /v1/health` and `POST /v1/observations`. Both require bearer authentication;
  browser-origin requests are rejected. Batches are limited to 32 observations
  and 256 KiB, with strict version/field/name/team validation.
- A Java background sender with a bounded persistent latest-roster outbox,
  connection timeouts, retry backoff, stable retry IDs, and acknowledgement checks.
  Updates for the same segment coalesce; this is not an event-history outbox.
- Atomic private JSON storage in the Go companion. An observation is acknowledged
  only after storage is flushed. Duplicate or older sequences never roll a roster
  back. Invalid batches do not partially commit.
- Persistent Discord message bindings, queued edits, three-second spacing, rate
  limit handling, and replacement of deleted messages. A bot/channel permission
  failure keeps the roster queued and prints an HTTP status without secrets.
- A separate lock for the companion and the mod outbox, preventing two processes
  from writing the same instance's state simultaneously.

Discord's message API supports colored embeds, suppressed mentions, and create
nonces; the implementation uses those features and the returned retry timing.
See [message API](https://docs.discord.com/developers/resources/message) and
[rate limits](https://docs.discord.com/developers/topics/rate-limits).

## Scope and recovery limits

This is the requested roster-only slice of the local contract and Discord work.
It does not implement SQLite match recording, account resolution, Discord
commands/session threads, alert WebSockets, or automatic companion startup.
World/reconnect boundaries create separate preview segments, not confirmed
distinct matches. Names are visible game names, not verified account identities.
Team inference and spectator behavior still need broader live validation.

The mod keeps at most 128 pending segments and 256 in-memory updates. Repeated
snapshots of a segment replace its older pending snapshot. Only updates already
flushed by the background worker survive a hard process crash; drops are reported
in status. A stopped companion leaves its connection file so the mod can keep
queueing. The last roster message remains in Discord, with its observation time,
if Minecraft crashes before sending an end snapshot.

The companion retains at most 128 segment bindings. At capacity it can retire
the oldest fully delivered entry older than seven days; otherwise ingest reports
failure and the mod keeps retrying. This is a bounded preview store, not permanent
match history. Deduplication applies while a segment binding is retained.
Recent Discord creates reuse a stable nonce to recover a lost response. Discord's
nonce deduplication is time-limited, so a crash/outage between posting and saving
the returned ID can still produce a duplicate after that window.

If you change Discord channels, use a separate game/state setup or stop the
companion and move `rosters.json` aside as a backup before restarting. The
companion refuses to apply old message bindings to a different channel.
Do not remove the outbox while expecting pending updates to survive.

## Validation

Automated tests cover roster gating/corrections, ambiguous/rank team metadata,
Java disk replay with unchanged IDs, incorrect acknowledgement rejection, API
authentication and limits, atomic batch failures, duplicate/out-of-order updates,
restart/message binding recovery, team embed limits, mention suppression, and
Discord create/edit/rate-limit behavior using a local test server.

A real Discord post and the updated mod's live roster/team display require your
configured bot and an authenticated Forge match; mock tests do not verify either.
