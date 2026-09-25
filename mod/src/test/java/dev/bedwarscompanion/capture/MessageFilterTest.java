package dev.bedwarscompanion.capture;

import org.junit.Test;
import static org.junit.Assert.*;

public class MessageFilterTest {
    @Test public void selectsCandidatesWithoutClaimingTheyAreEvents() {
        assertTrue(MessageFilter.candidate("\u00a7cBED DESTRUCTION > Blue Bed was destroyed by PlayerA!"));
        assertTrue(MessageFilter.candidate("PlayerA was killed by PlayerB. FINAL KILL!"));
        assertTrue(MessageFilter.candidate("PlayerA fell into the void."));
        assertTrue(MessageFilter.candidate("VICTORY!"));
    }
    @Test public void rejectsPlayerChatSpoofsAndUnrelatedText() {
        assertFalse(MessageFilter.candidate("PlayerA: PlayerB was killed by PlayerC. FINAL KILL!"));
        assertFalse(MessageFilter.candidate("[MVP+] PlayerA: BED DESTRUCTION > Blue Bed was destroyed by PlayerB!"));
        assertFalse(MessageFilter.candidate("Party > PlayerA: VICTORY!"));
        assertFalse(MessageFilter.candidate("From PlayerA: PlayerB died!"));
        assertFalse(MessageFilter.candidate("hello there"));
        assertFalse(MessageFilter.candidate("An unknown cosmetic template"));
    }
}
