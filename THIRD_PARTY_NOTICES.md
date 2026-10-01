# Third-party source notices

TOD includes modified tracking code from [BedWarMod/BedWar](https://github.com/BedWarMod/BedWar),
revision `10b2499`, and message definitions from
[BedWarMod/BedWar-Repo](https://github.com/BedWarMod/BedWar-Repo), revision `db04154`.
Downloaded October 1, 2026. No upstream updater or remote configuration runs in TOD.

## BedWar tracking code — LGPL-3.0

Upstream authors: BedWarMod / CalMWolfs and contributors.
The upstream repository licenses its code under LGPL version 3. The modified
`mod/src/main/java/dev/bedwarscompanion/capture/bedwar/` sources retain that licence.
Copies of LGPL-3.0 and the incorporated GPL-3.0 are in `third_party/bedwar/` and
are embedded in the built JAR. Reference originals are preserved under
`third_party/bedwar/upstream/`.

Adaptations by TOD, October 1, 2026:

- `BedwarParser.java`: Java port of `BedwarsEventManager.kt` dispatch and
  `BedwarsUtils.kt` footer parsing; bundled patterns, whole-message boundaries,
  final-first dispatch, validated visible names/teams, no disconnect-as-death
  inference, and no account/network dependency.
- `BedwarTracker.java`: adaptation of `SessionDisplay.kt` counter handling into
  bounded per-visible-player segment/client-run counters; separate final deaths,
  no ordinary-kill victim history, no inferred loss or win streak, no GUI/timer.
- The existing TOD mod supplies Forge input, context gating, local transport,
  diagnostic persistence and commands. No Kotlin or upstream UI dependencies added.

Keep these notices and licences with copies. When distributing a modified binary,
also provide the corresponding source and build materials, including the LGPL
adaptation, so recipients can modify it and rebuild. This source checkout contains
the adapted code, bundled data and build scripts (`scripts/build.sh`, `mod/build.gradle`).
These notices do not relicense unrelated TOD code.

## BedWar message definitions — MIT

`mod/src/main/resources/bedwar/ChatRegex.json` is an unmodified copy of
`constants/ChatRegex.json` from BedWar-Repo `db04154`.
Copyright (c) 2023 BedWarMod. Full MIT notice: `third_party/bedwar/PATTERNS-MIT.txt`,
also embedded in the JAR. Runtime matching applies TOD's additional restrictions;
not every upstream pattern or game mode is enabled.

## Download provenance

GitHub source archive SHA-256 values:

- BedWar `10b2499`: `a70049c4c16663a2825a65f06c5738021e03965195117abd1ab6a737e0745a4c`
- BedWar-Repo `db04154`: `73820bb5f45ee24f4e3c7098300f789dbcd9ce1fc820e93a9676ab5f2cd5644a`
