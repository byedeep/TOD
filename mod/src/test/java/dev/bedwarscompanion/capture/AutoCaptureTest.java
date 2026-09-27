package dev.bedwarscompanion.capture;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutoCaptureTest {
    @Test public void lobbyTitleAloneDoesNotStartCapture() {
        assertEquals(AutoCapture.Scene.OTHER, AutoCapture.scene("§e§lBED WARS",
            Arrays.asList("Level: 106", "Total Wins: 513", "www.hypixel.net")));
        assertEquals(AutoCapture.Scene.OTHER, AutoCapture.scene("OTHER",
            Arrays.asList("R Red: ✓", "B Blue: ✓")));
    }
    @Test public void recognisesDecoratedSidebarAndPregame() {
        assertEquals(AutoCapture.Scene.ACTIVE, AutoCapture.scene("§e§lBED WARS",
            Arrays.asList("§eY §fYellow§f: 🍭§a1§7 YOU", "§aG §fGreen§f: 🌠§a§l✓",
                "§9B §fBlue§f: §c👾§c§l✗", "§cR §fRed§f: §c🐍§c§l✗")));
        assertEquals(AutoCapture.Scene.PREGAME, AutoCapture.scene("BED WARS",
            Arrays.asList("Players: 1🎉1/12", "Starting in §a5s")));
        assertEquals(AutoCapture.Scene.PREGAME, AutoCapture.scene("BED WARS",
            Arrays.asList("Players: 2/8", "Waiting...")));
    }
    @Test public void gatesRosterAndKeepsSpectatingUntilContextChanges() {
        AutoCapture auto = new AutoCapture();
        auto.observe(AutoCapture.Scene.PREGAME);
        assertFalse(auto.rosterReady());
        auto.observe(AutoCapture.Scene.ACTIVE);
        assertFalse(auto.rosterReady());
        auto.observe(AutoCapture.Scene.ACTIVE);
        assertTrue(auto.rosterReady());
        assertFalse(auto.shouldStop(AutoCapture.Scene.ACTIVE));
        assertTrue(auto.shouldStop(AutoCapture.Scene.PREGAME));
        auto.observe(AutoCapture.Scene.OTHER);
        assertFalse(auto.rosterReady());
        assertFalse(auto.shouldStop(AutoCapture.Scene.OTHER));
        for (int i = 0; i < 4; i++) auto.observe(AutoCapture.Scene.OTHER);
        assertTrue(auto.shouldStop(AutoCapture.Scene.OTHER));
        auto.reset();
        assertFalse(auto.shouldStop(AutoCapture.Scene.PREGAME));
        assertFalse(auto.rosterReady());
    }
}
