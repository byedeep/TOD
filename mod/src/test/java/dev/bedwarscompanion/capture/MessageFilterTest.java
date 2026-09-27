package dev.bedwarscompanion.capture;

import org.junit.Test;
import static org.junit.Assert.*;

public class MessageFilterTest {
    @Test public void capturesUnfamiliarByCosmeticsAsDiagnostics() {
        assertTrue(MessageFilter.candidate("§aPlayerA §7was oinked by §cPlayerB§7."));
        assertTrue(MessageFilter.candidate("PlayerA was given the cold shoulder by PlayerB."));
        assertTrue(MessageFilter.candidate("PlayerA was launched by PlayerB. FINAL KILL!"));
        assertFalse(MessageFilter.candidate("PlayerC: PlayerA was oinked by PlayerB."));
        assertFalse(MessageFilter.candidate("[MVP+] PlayerC: PlayerA was oinked by PlayerB."));
        assertFalse(MessageFilter.candidate("Party > PlayerA was oinked by PlayerB."));
        assertFalse(MessageFilter.candidate("From PlayerC: PlayerA was oinked by PlayerB."));
        assertFalse(MessageFilter.candidate("I saw PlayerA was oinked by PlayerB."));
        assertFalse(MessageFilter.candidate("PlayerA was oinked by PlayerB. extra text"));
        assertFalse(MessageFilter.candidate("PlayerA was oinked by PlayerNameTooLong17."));
        assertFalse(MessageFilter.candidate("PlayerA was oinked. hello by PlayerB."));
    }

    @Test public void capturesReviewedSlippedVoidCosmeticWithoutOpeningChatSelection() {
        // Anonymised wording from the two omitted client-log messages, 2026-09-26.
        assertTrue(MessageFilter.candidate("§aPlayerA §7slipped into void for §cPlayerB§7."));
        assertTrue(MessageFilter.candidate("PlayerC slipped into void for PlayerB."));
        assertTrue(MessageFilter.candidate("PlayerA slipped into void for PlayerB. FINAL KILL!"));
        assertFalse(MessageFilter.candidate("PlayerC: PlayerA slipped into void for PlayerB."));
        assertFalse(MessageFilter.candidate("[MVP+] PlayerC: PlayerA slipped into void for PlayerB."));
        assertFalse(MessageFilter.candidate("Party > PlayerA slipped into void for PlayerB."));
        assertFalse(MessageFilter.candidate("From PlayerC: PlayerA slipped into void for PlayerB."));
        assertFalse(MessageFilter.candidate("PlayerA slipped into void for PlayerB. hello"));
        assertFalse(MessageFilter.candidate("I saw PlayerA slipped into void for PlayerB."));
    }

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
