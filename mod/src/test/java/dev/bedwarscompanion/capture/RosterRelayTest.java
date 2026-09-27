package dev.bedwarscompanion.capture;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class RosterRelayTest {
    @Test public void resolvesScoreboardTeamsWithoutUsingRankColors() {
        assertEquals("red", RosterRelay.team("Red14", "§c§lR §r§c", "§f"));
        assertEquals("blue", RosterRelay.team("arbitrary", "§9B §9", "§r"));
        assertEquals("pending", RosterRelay.team("rank", "§a[VIP] ", "§a"));
        assertEquals("pending", RosterRelay.team("unknown", "", "§f"));
        assertEquals("pending", RosterRelay.team("1Red", "§9", "§9"));
        assertEquals("gray", RosterRelay.team("8Gray", "§7S ", "§7"));
        assertEquals("white", RosterRelay.team("White", "§fW §f", "§f"));
        assertEquals("pending", RosterRelay.team("unknown", "§c§r", "§r"));
        // Minimized team-only examples from the reviewed September 27 capture.
        assertEquals("yellow", RosterRelay.team("Red0", "§e§lY §r§e", "§f"));
        assertEquals("pending", RosterRelay.team("Red0", "§7", "§f"));
        assertEquals("pending", RosterRelay.team("Blue12", "§7", "§f"));
        assertEquals("blue", RosterRelay.team("Blue12", "§9§lB §r§9", "§f"));
        assertEquals("green", RosterRelay.team("Green18", "§a§lG §r§a", "§f"));
    }
    @Test public void hidesPregameAndDebouncesThenCorrectsRosterAndClosesSegment() {
        List<RosterRelay.Observation> sent = new ArrayList<>();
        RosterRelay relay = new RosterRelay(o -> { sent.add(o); return true; });
        List<RosterRelay.Player> players = Arrays.asList(new RosterRelay.Player("Player_1", "red"));
        for (int i=0; i<5; i++) relay.sample(AutoCapture.Scene.PREGAME, players);
        assertTrue(sent.isEmpty());
        relay.sample(AutoCapture.Scene.ACTIVE, players);
        relay.sample(AutoCapture.Scene.ACTIVE, players);
        assertTrue(sent.isEmpty());
        relay.sample(AutoCapture.Scene.ACTIVE, players);
        assertEquals(1, sent.size());
        List<RosterRelay.Player> corrected = Arrays.asList(new RosterRelay.Player("Player_1", "blue"));
        relay.sample(AutoCapture.Scene.ACTIVE, corrected);
        assertEquals(1, sent.size());
        relay.sample(AutoCapture.Scene.ACTIVE, corrected);
        assertEquals(2, sent.size());
        assertEquals("red", sent.get(0).players.get(0).team);
        assertEquals("blue", sent.get(1).players.get(0).team);
        assertEquals(sent.get(0).segment_id, sent.get(1).segment_id);
        assertNotEquals(sent.get(0).event_id, sent.get(1).event_id);
        relay.end(); assertEquals("ended", sent.get(2).phase);
        for (int i=0; i<3; i++) relay.sample(AutoCapture.Scene.ACTIVE, players);
        assertNotEquals(sent.get(0).segment_id, sent.get(3).segment_id);
    }
    @Test public void retriesQueueRejectionAndPausePreventsPublication() {
        List<RosterRelay.Observation> sent = new ArrayList<>();
        RosterRelay relay = new RosterRelay(o -> { sent.add(o); return sent.size() > 1; });
        List<RosterRelay.Player> players = Arrays.asList(new RosterRelay.Player("Player_1", "pending"));
        for (int i=0; i<4; i++) relay.sample(AutoCapture.Scene.ACTIVE, players);
        assertEquals(2, sent.size());
        relay.enabled(false);
        int paused = sent.size();
        for (int i=0; i<5; i++) relay.sample(AutoCapture.Scene.ACTIVE, players);
        assertEquals(paused, sent.size());
        relay.enabled(true);
        for (int i=0; i<3; i++) relay.sample(AutoCapture.Scene.ACTIVE, Collections.emptyList());
        assertEquals(paused, sent.size());
    }
}
