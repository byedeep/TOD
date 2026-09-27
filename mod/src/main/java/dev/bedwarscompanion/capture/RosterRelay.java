package dev.bedwarscompanion.capture;

import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;

/** Roster-only preview. Segment IDs are local capture boundaries, not match IDs. */
final class RosterRelay {
    static final class Player {
        String name, team;
        Player(String name, String team) { this.name = name; this.team = team; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Player)) return false;
            Player p = (Player) other; return name.equals(p.name) && team.equals(p.team);
        }
        @Override public int hashCode() { return Objects.hash(name, team); }
    }
    static final class Observation {
        int schema_version = 1;
        String event_id = UUID.randomUUID().toString();
        String source_id, segment_id;
        long sequence;
        String observed_at = Instant.now().toString();
        String type = "roster_snapshot", phase;
        List<Player> players;
    }
    private static final Map<Character, String> COLORS = new HashMap<>();
    static {
        COLORS.put('c', "red"); COLORS.put('9', "blue"); COLORS.put('a', "green"); COLORS.put('e', "yellow");
        COLORS.put('b', "aqua"); COLORS.put('f', "white"); COLORS.put('d', "pink"); COLORS.put('7', "gray");
    }
    private final String source = UUID.randomUUID().toString();
    private final Predicate<Observation> sink;
    private final AutoCapture gate = new AutoCapture();
    private String segment;
    private long sequence;
    private List<Player> candidate, published;
    private int steady, heartbeat;
    private boolean enabled = true;

    RosterRelay(Predicate<Observation> sink) { this.sink = sink; }

    // Reviewed live prefixes carry a colored team initial. Internal names can be
    // misleading ("Red0" with a Yellow prefix), and plain gray is also unassigned.
    // Tab display/rank colors, registered names, and default format are not evidence.
    static String team(String registeredName, String prefix, String format) {
        String raw = prefix == null ? "" : prefix;
        String plain = MessageFilter.plain(raw).trim();
        if (!plain.matches("[RBGYAWPS]")) return "pending";
        Character color = null;
        for (int i = 0; i + 1 < raw.length(); i++) {
            if (raw.charAt(i) != '\u00a7') continue;
            char code = Character.toLowerCase(raw.charAt(++i));
            if ("0123456789abcdef".indexOf(code) >= 0) color = code;
            else if (code == 'r') color = null;
        }
        String inferred = COLORS.get(color);
        if (inferred == null) return "pending";
        String letter = inferred.equals("gray") ? "S" : inferred.substring(0, 1).toUpperCase(Locale.ROOT);
        if (!plain.equals(letter)) return "pending";
        return inferred;
    }

    void sample(AutoCapture.Scene scene, List<Player> players) {
        if (!enabled) return;
        gate.observe(scene);
        if (gate.shouldStop(scene)) { end(); return; }
        if (!gate.rosterReady()) { candidate = null; steady = 0; return; }
        List<Player> sorted = new ArrayList<>(players);
        sorted.sort(Comparator.comparing(p -> p.name));
        if (sorted.isEmpty()) { candidate = null; steady = 0; return; }
        steady = sorted.equals(candidate) ? steady + 1 : 1;
        candidate = sorted;
        if (steady < 2) return;
        if (published != null && published.equals(sorted) && ++heartbeat < 30) return;
        if (segment == null) { segment = UUID.randomUUID().toString(); sequence = 0; }
        if (emit("active", sorted)) { published = sorted; heartbeat = 0; }
    }
    private boolean emit(String phase, List<Player> players) {
        Observation o = new Observation(); o.source_id = source; o.segment_id = segment;
        o.sequence = ++sequence; o.phase = phase; o.players = new ArrayList<>(players);
        return sink.test(o);
    }
    void end() {
        if (segment != null && published != null) emit("ended", published);
        segment = null; published = null; candidate = null; steady = 0; heartbeat = 0; gate.reset();
    }
    void enabled(boolean value) { if (!value) end(); enabled = value; }
}
