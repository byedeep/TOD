package dev.bedwarscompanion.capture;

import java.util.Locale;
import java.util.regex.Pattern;

/** Diagnostic selection only. A selected message is NOT a validated match event. */
public final class MessageFilter {
    private static final Pattern FORMAT = Pattern.compile("\u00a7[0-9A-FK-OR]", Pattern.CASE_INSENSITIVE);
    // Reviewed cosmetic from live capture 542ced7f's client log. Match the whole
    // message so this addition cannot select arbitrary conversation containing it.
    private static final Pattern SLIPPED_VOID = Pattern.compile(
        "[A-Za-z0-9_]{1,16} slipped into void for [A-Za-z0-9_]{1,16}\\.", Pattern.CASE_INSENSITIVE);
    // Broad diagnostic fallback, never a verified kill. Bound the cosmetic
    // wording and anchor both names so chat prefixes/trailing text cannot match.
    private static final Pattern COSMETIC_BY = Pattern.compile(
        "[A-Za-z0-9_]{1,16} was [A-Za-z][A-Za-z '\\-]{0,159} by [A-Za-z0-9_]{1,16}\\.(?: FINAL KILL!)?",
        Pattern.CASE_INSENSITIVE);
    private MessageFilter() {}
    public static String plain(String text) { return FORMAT.matcher(text).replaceAll(""); }
    public static boolean candidate(String formatted) {
        String text = plain(formatted).trim();
        // Fail closed for normal, party, guild, spectator and private chat shapes.
        if (text.length() > 1024 || text.contains(":") || (text.contains(">") && !text.startsWith("BED DESTRUCTION >")) || text.startsWith("[")) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return SLIPPED_VOID.matcher(text).matches()
            || COSMETIC_BY.matcher(text).matches()
            || lower.contains("final kill!") || lower.startsWith("bed destruction >")
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
