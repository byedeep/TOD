package dev.bedwarscompanion.capture;

import com.google.gson.*;
import org.junit.Test;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class DiagnosticWriterTest {
    @Test public void drainsOnStopAndWritesRestrictedJsonl() throws Exception {
        Path dir = Files.createTempDirectory("bwc-writer-test");
        DiagnosticWriter writer = new DiagnosticWriter(dir);
        writer.emit("manual_marker", Collections.singletonMap("label", "spawn"));
        writer.shutdown();
        assertTrue(writer.finished());
        List<String> lines = Files.readAllLines(writer.file());
        assertEquals(3, lines.size());
        assertEquals("capture_start", new JsonParser().parse(lines.get(0)).getAsJsonObject().get("type").getAsString());
        assertEquals(0, new JsonParser().parse(lines.get(2)).getAsJsonObject().getAsJsonObject("payload").get("dropped").getAsInt());
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(writer.file())));
    }
    @Test public void prunesOnlyExpiredDiagnosticFiles() throws Exception {
        Path dir = Files.createTempDirectory("bwc-retention-test");
        Path old = Files.write(dir.resolve("capture-old.jsonl"), new byte[]{1});
        Path other = Files.write(dir.resolve("keep.txt"), new byte[]{1});
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8)));
        DiagnosticWriter writer = new DiagnosticWriter(dir);
        writer.shutdown();
        assertFalse(Files.exists(old)); assertTrue(Files.exists(other));
    }
    @Test public void sizeLimitStopsCaptureAndAccountsForLostSamples() throws Exception {
        Path dir = Files.createTempDirectory("bwc-size-test");
        DiagnosticWriter writer = new DiagnosticWriter(dir);
        char[] chars = new char[32768];
        Arrays.fill(chars, 'x');
        Map<String, Object> payload = Collections.singletonMap("sample", new String(chars));
        for (int i = 0; i < 900; i++) writer.emit("snapshot", payload);
        writer.shutdown();
        assertTrue(writer.finished());
        assertTrue(Files.size(writer.file()) <= DiagnosticWriter.MAX_BYTES);
        List<String> lines = Files.readAllLines(writer.file());
        JsonObject footer = new JsonParser().parse(lines.get(lines.size() - 1)).getAsJsonObject();
        assertEquals("stopped-size-limit", footer.getAsJsonObject("payload").get("reason").getAsString());
        assertTrue(footer.getAsJsonObject("payload").get("dropped").getAsLong() > 0);
    }
    @Test public void diskFailureIsReportedWithoutThrowingOnProducer() throws Exception {
        Path file = Files.createTempFile("bwc-not-directory", ".tmp");
        DiagnosticWriter writer = new DiagnosticWriter(file.resolve("child"));
        writer.shutdown();
        assertTrue(writer.status(), writer.status().startsWith("failed-"));
        assertFalse(writer.accepting());
    }
}
