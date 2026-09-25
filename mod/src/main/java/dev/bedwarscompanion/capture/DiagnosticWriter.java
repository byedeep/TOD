package dev.bedwarscompanion.capture;

import com.google.gson.Gson;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Best-effort diagnostic sink, not the future durable delivery outbox. */
public final class DiagnosticWriter {
    static final long MAX_BYTES = 16L * 1024 * 1024;
    static final long MAX_DIRECTORY_BYTES = 128L * 1024 * 1024;
    static final long RETENTION_MS = TimeUnit.DAYS.toMillis(7);
    private final ArrayBlockingQueue<Map<String, Object>> queue = new ArrayBlockingQueue<>(1024);
    private final AtomicLong dropped = new AtomicLong();
    private final long startedNanos = System.nanoTime();
    private final String captureId = UUID.randomUUID().toString();
    private final Thread worker;
    private volatile boolean accepting = true;
    private volatile String state = "starting";
    private volatile long written;
    private long sequence;
    private final Path directory;
    private final Path file;

    public DiagnosticWriter(Path directory) {
        this.directory = directory;
        this.file = directory.resolve("capture-" + captureId + ".jsonl");
        worker = new Thread(this::run, "bedwars-diagnostic-writer");
        worker.setDaemon(true);
        emit("capture_start", Collections.singletonMap("kind", "diagnostic-not-match-events"));
        worker.start();
    }
    // Called on the client thread. Payloads must be detached snapshots, never reused.
    public synchronized void emit(String type, Map<String, Object> payload) {
        if (!accepting) return;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("schema_version", 1);
        row.put("capture_id", captureId);
        row.put("sequence", ++sequence);
        row.put("observed_at", java.time.Instant.now().toString());
        row.put("elapsed_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
        row.put("type", type);
        row.put("payload", payload);
        if (!queue.offer(row)) dropped.incrementAndGet();
    }
    public synchronized void stop() { accepting = false; }
    public boolean finished() { return !worker.isAlive(); }
    public boolean accepting() { return accepting; }
    public String status() { return state + "; written=" + written + "; dropped=" + dropped.get(); }
    public Path file() { return file; }
    public void shutdown() {
        stop();
        try { worker.join(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    private void run() {
        Gson gson = new Gson();
        long bytes = 0;
        try {
            Files.createDirectories(directory);
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            long existing = pruneAndSize();
            if (existing + MAX_BYTES > MAX_DIRECTORY_BYTES) throw new IOException("capture-directory-full");
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            state = "capturing";
            try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                while (accepting || !queue.isEmpty()) {
                    if (System.nanoTime() - startedNanos > TimeUnit.MINUTES.toNanos(30)) {
                        stop();
                        state = "stopped-time-limit";
                    }
                    Map<String, Object> row = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (row == null) continue;
                    String line = gson.toJson(row);
                    long length = line.getBytes(StandardCharsets.UTF_8).length + 1;
                    if (bytes + length > MAX_BYTES - 4096) {
                        stop();
                        dropped.addAndGet(1 + queue.size());
                        queue.clear();
                        state = "stopped-size-limit";
                        break;
                    }
                    out.write(line); out.newLine(); out.flush();
                    bytes += length;
                    written++;
                }
                if (state.equals("capturing")) state = "stopped";
                Map<String, Object> end = new LinkedHashMap<>();
                end.put("schema_version", 1);
                end.put("capture_id", captureId);
                end.put("type", "capture_end");
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("dropped", dropped.get()); result.put("written", written); result.put("reason", state);
                end.put("payload", result);
                out.write(gson.toJson(end)); out.newLine();
            }
        } catch (IOException | InterruptedException | UnsupportedOperationException e) {
            stop();
            state = "failed-" + e.getClass().getSimpleName();
            dropped.addAndGet(queue.size());
            queue.clear();
        } finally { stop(); }
    }
    private long pruneAndSize() throws IOException {
        long total = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "capture-*.jsonl")) {
            for (Path entry : entries) {
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) continue;
                if (System.currentTimeMillis() - Files.getLastModifiedTime(entry).toMillis() > RETENTION_MS) {
                    Files.delete(entry);
                } else total += Files.size(entry);
            }
        }
        return total;
    }
}
