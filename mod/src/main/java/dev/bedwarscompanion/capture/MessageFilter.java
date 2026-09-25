package dev.bedwarscompanion.capture;

import java.util.Locale;
import java.util.regex.Pattern;

/** Diagnostic selection only. A selected message is NOT a validated match event. */
public final class MessageFilter {
    private static final Pattern FORMAT = Pattern.compile("\u00a7[0-9A-FK-OR]", Pattern.CASE_INSENSITIVE);
    private MessageFilter() {}
    public static String plain(String text) { return FORMAT.matcher(text).replaceAll(""); }
    public static boolean candidate(String formatted) {
        String text = plain(formatted).trim();
        // Fail closed for normal, party, guild, spectator and private chat shapes.
        if (text.length() > 1024 || text.contains(":") || (text.contains(">") && !text.startsWith("BED DESTRUCTION >")) || text.startsWith("[")) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("final kill!") || lower.startsWith("bed destruction >")
            || lower.startsWith("bed destruction") || lower.contains(" was killed by ")
            || lower.contains(" was slain by ") || lower.contains(" was knocked ")
            || lower.contains(" was thrown ") || lower.contains(" fell into the void")
            || lower.contains(" fell to their death") || lower.contains(" died!")
            || lower.contains(" has been eliminated!") || lower.equals("victory!")
            || lower.equals("game over!") || lower.contains("beds have been destroyed")
            || lower.startsWith("protect your bed") || lower.startsWith("the game starts in ")
            || lower.startsWith("1st killer") || lower.startsWith("2nd killer")
            || lower.startsWith("3rd killer");
    }
}
