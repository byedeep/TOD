/* SPDX-License-Identifier: LGPL-3.0-only
 * Adapted from BedWarMod/BedWar, BedwarsEventManager.kt and BedwarsUtils.kt
 * at 10b2499. Modified for TOD on 2026-10-01. See THIRD_PARTY_NOTICES.md.
 */
package dev.bedwarscompanion.capture.bedwar;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Pinned upstream English patterns with TOD's stricter boundaries. No network access. */
public final class BedwarParser {
    public enum Kind { START, KILL, FINAL, DEATH, BED, TEAM_ELIMINATED, WINNER }
    public static final String VERSION = "bedwar-10b2499-tod-1";
    private static final Set<String> TEAMS = new HashSet<>(Arrays.asList(
        "Red", "Blue", "Green", "Yellow", "Aqua", "White", "Pink", "Gray"));
    private static final Pattern FORMAT = Pattern.compile("§[0-9a-fk-or]", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELF = Pattern.compile(
        "(?<killed>[A-Za-z0-9_]{1,16}) (?:fell into the void|fell to their death|died|disconnected)\\.");
    // Ported from BedwarsUtils.tabStatPattern. Whitespace may span footer lines.
    private static final Pattern FOOTER = Pattern.compile(
        "Kills: (?<kills>\\d+) Final Kills: (?<finals>\\d+) Beds Broken: (?<beds>\\d+)");
    private final Map<String, Pattern> patterns = new HashMap<>();

    public static final class Event {
        public final Kind kind;
        public final String actor, subject, team;
        public final boolean voidDeath;
        Event(Kind kind, String actor, String subject, String team, boolean voidDeath) {
            this.kind = kind; this.actor = actor; this.subject = subject;
            this.team = team; this.voidDeath = voidDeath;
        }
    }

    public BedwarParser() {
        try (InputStream in = BedwarParser.class.getResourceAsStream("/bedwar/ChatRegex.json")) {
            if (in == null) throw new IllegalStateException("Missing bundled BedWar patterns");
            Map<String, String> definitions = new Gson().fromJson(
                new InputStreamReader(in, StandardCharsets.UTF_8),
                new TypeToken<Map<String, String>>() {}.getType());
            for (Map.Entry<String, String> entry : definitions.entrySet())
                patterns.put(entry.getKey(), Pattern.compile(entry.getValue()));
        } catch (IOException e) { throw new IllegalStateException("Cannot load BedWar patterns", e); }
    }

    public static String plain(String value) { return FORMAT.matcher(value).replaceAll(""); }
    public static boolean team(String value) { return TEAMS.contains(value); }
    private static boolean name(String value) { return value != null && value.matches("[A-Za-z0-9_]{1,16}"); }

    public Event parse(String formatted, boolean inArea, boolean active, String ownTeam) {
        if (!inArea || formatted == null || formatted.length() > 1024) return null;
        String message = formatted.replaceAll("(?i)§r", "").trim();
        String text = plain(message).trim();
        // Forge packet type alone does not establish system origin.
        if (text.contains(":") || text.startsWith("[") || (text.contains(">") &&
            !text.startsWith("BED DESTRUCTION > ") && !text.startsWith("TEAM ELIMINATED > "))) return null;
        if (patterns.get("gameStartPattern").matcher(message).matches())
            return new Event(Kind.START, null, null, null, false);
        if (!active) return null;

        // Finals must be dispatched before ordinary kills/deaths, unlike upstream's self-death path.
        boolean isFinal = text.endsWith(" FINAL KILL!");
        String deathText = isFinal ? text.substring(0, text.length() - " FINAL KILL!".length()) : text;
        Matcher self = SELF.matcher(deathText);
        if (self.matches() && (isFinal || !deathText.endsWith(" disconnected.")))
            return new Event(isFinal ? Kind.FINAL : Kind.DEATH, null, self.group("killed"), null,
                deathText.endsWith(" fell into the void."));
        if (isFinal) {
            Matcher match = patterns.get("finalKillPattern").matcher(text);
            // Require the colored victim/gray wording used by Hypixel, and a whole-message match.
            if (message.matches("§[0-9a-fA-F][A-Za-z0-9_]{1,16}(?: |').*") && match.matches()
                && name(match.group("killer")) && name(match.group("killed")))
                return new Event(Kind.FINAL, match.group("killer"), match.group("killed"), null, false);
            return null;
        }
        Matcher kill = patterns.get("killPattern").matcher(message);
        if (kill.matches() && name(kill.group("killer")) && name(kill.group("killed")))
            return new Event(Kind.KILL, kill.group("killer"), kill.group("killed"), null, false);

        Matcher bed = patterns.get("bedBreakPattern").matcher(text);
        if (bed.matches() && name(bed.group("player"))) {
            String target = bed.group("team");
            if (target.equals("Your")) target = team(ownTeam) ? ownTeam : null;
            else if (!team(target)) return null;
            return new Event(Kind.BED, bed.group("player"), null, target, false);
        }
        Matcher eliminated = patterns.get("teamEliminatedPattern").matcher(text);
        if (eliminated.matches() && team(eliminated.group("team")))
            return new Event(Kind.TEAM_ELIMINATED, null, null, eliminated.group("team"), false);
        Matcher winner = patterns.get("gameEndPattern").matcher(text);
        if (winner.matches() && team(winner.group("team")))
            return new Event(Kind.WINNER, null, null, winner.group("team"), false);
        return null;
    }

    /** Absolute local-player totals, never deltas to add to chat-derived counts. */
    public static int[] footer(String formatted) {
        if (formatted == null || formatted.length() > 8192) return null;
        String text = plain(formatted).replaceAll("\\s+", " ").trim();
        Matcher m = FOOTER.matcher(text);
        if (!m.find()) return null;
        try {
            return new int[] {Integer.parseInt(m.group("kills")), Integer.parseInt(m.group("finals")),
                Integer.parseInt(m.group("beds"))};
        } catch (NumberFormatException e) { return null; }
    }
}
