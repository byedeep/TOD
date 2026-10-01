package dev.bedwarscompanion.capture.bedwar;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class BedwarTrackingTest {
    private final BedwarParser parser = new BedwarParser();
    private BedwarParser.Event parse(String text) { return parser.parse(text, true, true, "Yellow"); }
    private static final String KILL = "§aPlayerA §7slipped into void for §cPlayerB§7.";
    private static final String FINAL = "§aPlayerA §7was oinked by §cPlayerB§7. §b§lFINAL KILL!";
    @SuppressWarnings("unchecked")
    private Map<String, Object> counts(BedwarTracker tracker, String player) {
        return (Map<String, Object>) ((Map<String, Object>) tracker.snapshot().get("players")).get(player.toLowerCase());
    }

    @Test public void portsCreditedCosmeticsAndResets() {
        for (String wording : new String[] {"was killed by", "was oinked by", "slipped into void for",
                "was given the cold shoulder by", "was knocked into the void by"}) {
            BedwarParser.Event event = parse("§r§aPlayerA §7" + wording + " §cPlayerB§7.§r");
            assertNotNull(wording, event);
            assertEquals(BedwarParser.Kind.KILL, event.kind);
            assertEquals("PlayerB", event.actor);
        }
    }
    @Test public void separatesFinalsIncludingUncreditedFinalVoid() {
        BedwarTracker tracker = new BedwarTracker(); tracker.activeSidebar("Yellow");
        tracker.accept(parse(FINAL));
        tracker.accept(parse("§aPlayerC §7fell into the void§7. §b§lFINAL KILL!"));
        assertEquals(0, counts(tracker, "PlayerB").get("regular_kills"));
        assertEquals(1, counts(tracker, "PlayerB").get("final_kills"));
        assertEquals(1, counts(tracker, "PlayerC").get("final_deaths"));
        assertEquals(0, counts(tracker, "PlayerC").get("uncredited_deaths"));
        assertEquals(1, counts(tracker, "PlayerC").get("uncredited_final_void_deaths"));
        assertFalse(tracker.snapshot().toString().contains("-="));
    }
    @Test public void ordinaryEventsKeepActorOnlyAndIdenticalMessagesRemainDistinct() {
        BedwarTracker tracker = new BedwarTracker(); tracker.activeSidebar("Yellow");
        Map<String, Object> event = tracker.accept(parse(KILL));
        tracker.accept(parse(KILL));
        assertFalse(event.containsKey("subject"));
        assertFalse(event.toString().contains("PlayerA"));
        assertNull(counts(tracker, "PlayerA"));
        assertEquals(2, counts(tracker, "PlayerB").get("regular_kills"));
    }
    @Test public void selfDeathsAwardNobodyAndDisconnectIsNotDeath() {
        BedwarTracker tracker = new BedwarTracker(); tracker.activeSidebar("Yellow");
        tracker.accept(parse("§aPlayerA §7fell into the void."));
        assertEquals(1, counts(tracker, "PlayerA").get("uncredited_void_deaths"));
        assertEquals(0, counts(tracker, "PlayerA").get("regular_kills"));
        assertNull(parse("§aPlayerA §7disconnected."));
        assertNull(parse("§aPlayerA §7reconnected."));
    }
    @Test public void bedsHandleCosmeticsAndUnknownOwnTeam() {
        String text = "BED DESTRUCTION > Your Bed was ripped to shreds by PlayerB!";
        assertEquals("Yellow", parse(text).team);
        assertNull(parser.parse(text, true, true, null).team);
        assertEquals("PlayerB", parse("BED DESTRUCTION > Red Bed has left the game after seeing PlayerB!").actor);
        assertNull(parse("BED DESTRUCTION > Orange Bed was destroyed by PlayerB!"));
        assertNull(parse("All beds have been destroyed!"));
    }
    @Test public void rejectsChatSpoofsSuffixesAndWrongContext() {
        for (String prefix : new String[] {"PlayerC: ", "[MVP+] PlayerC: ", "Party > ", "From PlayerC: ", "I saw "}) {
            assertNull(parse(prefix + KILL)); assertNull(parse(prefix + FINAL));
        }
        assertNull(parse(KILL + " hello"));
        assertNull(parse(FINAL + " hello"));
        assertNull(parse(KILL.replace("PlayerB", "PlayerNameTooLong17")));
        assertNull(parser.parse(KILL, false, true, null));
        assertNull(parser.parse(KILL, true, false, null));
        assertNull(parse("1st Killer - PlayerA - 9"));
        assertNull(parse("Random - PlayerA!"));
    }
    @Test public void startIsIdempotentAndCanConfirmEarlyActiveSidebar() {
        BedwarParser.Event start = parser.parse("§e§lProtect your bed and destroy the enemy beds.", true, false, null);
        assertEquals(BedwarParser.Kind.START, start.kind);
        BedwarTracker tracker = new BedwarTracker(); tracker.activeSidebar("Yellow");
        tracker.accept(parse(KILL));
        Object id = tracker.snapshot().get("segment_id");
        assertNotNull(tracker.accept(start));
        assertNull(tracker.accept(start));
        assertEquals(id, tracker.snapshot().get("segment_id"));
        assertEquals(false, tracker.snapshot().get("partial_start"));
        assertEquals(1, counts(tracker, "PlayerB").get("regular_kills"));
    }
    @Test public void segmentsResetButClientTotalsSurviveAndEndedSegmentsStayClosed() {
        BedwarTracker tracker = new BedwarTracker(); tracker.activeSidebar("Yellow");
        tracker.accept(parse(KILL)); tracker.end();
        assertNull(tracker.accept(parse(KILL)));
        tracker.activeSidebar("Yellow"); assertFalse(tracker.active());
        tracker.newCapture(); tracker.activeSidebar("Red");
        assertNull(counts(tracker, "PlayerB"));
        assertTrue(tracker.status("PlayerB").contains("client session 1 kills"));
        tracker.accept(parse(KILL));
        assertTrue(tracker.status("playerb").contains("client session 2 kills"));
    }
    @Test public void footerIsAbsoluteSeparateAndMissingMeansUnknown() {
        BedwarTracker tracker = new BedwarTracker(); tracker.activeSidebar("Yellow");
        tracker.accept(parse(KILL));
        tracker.footer("§eKills: §a7\n§eFinal Kills: §a2\n§eBeds Broken: §a1");
        tracker.footer("Kills: 7 Final Kills: 2 Beds Broken: 1");
        assertTrue(tracker.localStatus().contains("7 kills, 2 finals, 1 beds"));
        assertEquals(1, counts(tracker, "PlayerB").get("regular_kills"));
        assertTrue(tracker.status("PlayerB").contains("client session 1 kills"));
        tracker.footer(""); assertFalse(tracker.snapshot().containsKey("local_tab_totals"));
        assertNull(BedwarParser.footer("Kills: 99999999999999999999 Final Kills: 1 Beds Broken: 1"));
    }
    @Test public void eliminationDoesNotEndSpectatingButWinnerStopsCounting() {
        BedwarTracker tracker = new BedwarTracker(); tracker.activeSidebar("Yellow");
        tracker.accept(parse("TEAM ELIMINATED > Yellow Team has been eliminated!"));
        assertTrue(tracker.active());
        tracker.accept(parse("Red - PlayerB!"));
        assertEquals("Red", tracker.snapshot().get("winner"));
        assertFalse(tracker.active());
        assertNull(tracker.accept(parse(KILL)));
        tracker.activeSidebar("Yellow"); assertFalse(tracker.active());
    }
}
