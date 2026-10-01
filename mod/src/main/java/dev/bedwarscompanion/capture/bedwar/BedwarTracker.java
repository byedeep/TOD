/* SPDX-License-Identifier: LGPL-3.0-only
 * Adapted from BedWarMod/BedWar features/session/SessionDisplay.kt at 10b2499.
 * Modified for TOD on 2026-10-01: per-visible-player counters, bounded client-run
 * totals, segment lifecycle, separate final deaths and absolute footer totals.
 * See THIRD_PARTY_NOTICES.md.
 */
package dev.bedwarscompanion.capture.bedwar;

import java.util.*;

/** In-memory preview only. Segment IDs are not confirmed Hypixel match IDs. */
public final class BedwarTracker {
    private static final int MAX_PLAYERS = 4096;
    private final Map<String, Counts> segment = new TreeMap<>(), session = new TreeMap<>();
    private boolean active, started, closed, partial;
    private String segmentId, ownTeam, winner;
    private int[] localTotals;
    private int segments;
    private long dropped;

    private static final class Counts {
        int kills, finals, beds, deaths, finalDeaths, voidDeaths, finalVoidDeaths;
        Map<String, Object> data() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("regular_kills", kills); d.put("final_kills", finals); d.put("beds_broken", beds);
            d.put("uncredited_deaths", deaths); d.put("final_deaths", finalDeaths);
            d.put("uncredited_void_deaths", voidDeaths); d.put("uncredited_final_void_deaths", finalVoidDeaths);
            return d;
        }
    }
    public void newCapture() {
        segment.clear(); active = false; started = false; closed = false; partial = true;
        segmentId = null; ownTeam = null; winner = null; localTotals = null;
    }
    public void activeSidebar(String team) {
        if (closed) return;
        if (!started) start(true);
        ownTeam = BedwarParser.team(team) ? team : null;
    }
    private void start(boolean partialStart) {
        if (started || closed) return;
        started = true; active = true; partial = partialStart;
        segmentId = UUID.randomUUID().toString(); segments++;
    }
    public boolean active() { return active; }
    public String ownTeam() { return ownTeam; }
    public void end() { active = false; closed = true; }
    public void footer(String text) { if (active) localTotals = BedwarParser.footer(text); }

    /** Ordinary kill victim relationships are discarded before anything is serialized. */
    public Map<String, Object> accept(BedwarParser.Event e) {
        if (e == null) return null;
        if (e.kind == BedwarParser.Kind.START) {
            if (closed || (started && (!active || !partial))) return null;
            if (!started) start(false);
            else partial = false;
        } else if (!active) return null;
        apply(segment, e); apply(session, e);
        if (e.kind == BedwarParser.Kind.WINNER) { winner = e.team; active = false; }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("parser", BedwarParser.VERSION); data.put("kind", e.kind.name());
        data.put("segment_id", segmentId); data.put("identity_status", "unresolved");
        if (e.actor != null) data.put("actor", e.actor);
        if (e.kind == BedwarParser.Kind.FINAL || e.kind == BedwarParser.Kind.DEATH) data.put("subject", e.subject);
        if (e.kind == BedwarParser.Kind.BED || e.team != null) data.put("team", e.team);
        if (e.voidDeath) data.put("uncredited_void", true);
        return data;
    }
    private Counts counts(Map<String, Counts> map, String player) {
        if (player == null) return null;
        String key = player.toLowerCase(Locale.ROOT);
        if (!map.containsKey(key) && map.size() >= MAX_PLAYERS) { dropped++; return null; }
        if (!map.containsKey(key)) map.put(key, new Counts());
        return map.get(key);
    }
    private void apply(Map<String, Counts> map, BedwarParser.Event e) {
        Counts actor = counts(map, e.actor);
        if (actor != null) {
            if (e.kind == BedwarParser.Kind.KILL) actor.kills++;
            if (e.kind == BedwarParser.Kind.FINAL) actor.finals++;
            if (e.kind == BedwarParser.Kind.BED) actor.beds++;
        }
        if (e.kind == BedwarParser.Kind.DEATH || e.kind == BedwarParser.Kind.FINAL) {
            Counts subject = counts(map, e.subject);
            if (subject != null) {
                if (e.kind == BedwarParser.Kind.FINAL) {
                    subject.finalDeaths++;
                    if (e.voidDeath) subject.finalVoidDeaths++;
                } else {
                    subject.deaths++;
                    if (e.voidDeath) subject.voidDeaths++;
                }
            }
        }
    }
    public Map<String, Object> snapshot() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("parser", BedwarParser.VERSION); data.put("segment_id", segmentId);
        data.put("active", active); data.put("partial_start", partial); data.put("winner", winner);
        data.put("coverage", "observed-chat-only; identities and complete kill coverage unverified");
        Map<String, Object> players = new TreeMap<>();
        for (Map.Entry<String, Counts> e : segment.entrySet()) players.put(e.getKey(), e.getValue().data());
        data.put("players", players);
        if (localTotals != null) {
            Map<String, Object> local = new LinkedHashMap<>();
            local.put("regular_kills", localTotals[0]); local.put("final_kills", localTotals[1]);
            local.put("beds_broken", localTotals[2]); data.put("local_tab_totals", local);
        }
        data.put("dropped_counter_updates", dropped);
        return data;
    }
    public String status(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        return "Observed " + name + " | segment " + format(segment.get(key)) +
            " | client session " + format(session.get(key)) + " | segments=" + segments +
            (partial ? "; partial start" : "") + "; coverage unverified; drops=" + dropped;
    }
    public String localStatus() {
        return localTotals == null ? "Local tab totals unavailable." : "Local tab totals: " +
            localTotals[0] + " kills, " + localTotals[1] + " finals, " + localTotals[2] + " beds (absolute; separate from chat counts).";
    }
    private String format(Counts c) {
        if (c == null) return "no observations";
        return c.kills + " kills, " + c.finals + " finals, " + c.beds + " beds";
    }
}
