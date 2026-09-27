package dev.bedwarscompanion.capture;

import java.util.List;
import java.util.regex.Pattern;

/** Experimental diagnostic segmentation, never authoritative match lifecycle. */
final class AutoCapture {
    enum Scene { OTHER, PREGAME, ACTIVE }
    private static final Pattern TEAM = Pattern.compile(
        "^[A-Z] (?:Red|Blue|Green|Yellow|Aqua|White|Pink|Gray): (?:✓|✗|[0-9]+)(?: YOU)?$");
    private int activeSamples;
    private int missingSamples;
    private boolean seenActive;

    static Scene scene(String title, List<String> lines) {
        if (!clean(title).equals("BED WARS")) return Scene.OTHER;
        int teams = 0;
        boolean players = false, waiting = false;
        for (String line : lines) {
            String text = clean(line);
            if (TEAM.matcher(text).matches()) teams++;
            if (text.matches("Players: [0-9]+/[0-9]+")) players = true;
            if (text.equals("Waiting...") || text.matches("Starting in [0-9]+s")) waiting = true;
        }
        if (teams >= 2) return Scene.ACTIVE;
        return players && waiting ? Scene.PREGAME : Scene.OTHER;
    }

    // Hypixel sidebar entries can contain invisible identifiers rendered as emoji.
    static String clean(String text) {
        return MessageFilter.plain(text == null ? "" : text)
            .replaceAll("[^\\x20-\\x7E✓✗]", "").trim();
    }

    void observe(Scene scene) {
        activeSamples = scene == Scene.ACTIVE ? activeSamples + 1 : 0;
        missingSamples = scene == Scene.OTHER ? missingSamples + 1 : 0;
        if (scene == Scene.ACTIVE) seenActive = true;
    }
    boolean rosterReady() { return activeSamples >= 2; }
    boolean shouldStop(Scene scene) {
        return missingSamples >= 5 || (seenActive && scene == Scene.PREGAME);
    }
    void reset() { activeSamples = 0; missingSamples = 0; seenActive = false; }
}
