package dev.bedwarscompanion.capture;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded latest-snapshot outbox. All file/network operations run off the client thread. */
final class RosterTransport {
    private static final int MAX_PENDING = 128;
    private final Gson gson = new Gson();
    private final ArrayBlockingQueue<RosterRelay.Observation> queue = new ArrayBlockingQueue<>(256);
    private final AtomicLong dropped = new AtomicLong();
    private final Path directory, outbox, rendezvous;
    private final Thread worker;
    private volatile boolean running = true, configured;
    private volatile String state = "not configured (start companion serve)";
    private volatile int pendingCount;

    RosterTransport(Path directory) {
        this.directory = directory; outbox = directory.resolve("roster-outbox.json"); rendezvous = directory.resolve("bridge.json");
        worker = new Thread(this::run, "bedwars-roster-transport"); worker.setDaemon(true); worker.start();
    }
    boolean offer(RosterRelay.Observation observation) {
        if (!configured || !running) return false;
        if (!queue.offer(observation)) { dropped.incrementAndGet(); return false; }
        return true;
    }
    String status() { return "Discord bridge=" + state + "; pending=" + pendingCount + "; dropped=" + dropped.get(); }
    void shutdown() {
        running = false;
        try { worker.join(5500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    private void run() {
        try {
            // Starting the companion explicitly opts this instance into roster publishing.
            while (running && !Files.isRegularFile(rendezvous)) Thread.sleep(500);
            if (!running) return;
            Files.createDirectories(directory);
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            try (FileChannel channel = FileChannel.open(directory.resolve("roster-outbox.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                if (lock == null) throw new IOException("another Minecraft client owns the roster outbox");
                LinkedHashMap<String, RosterRelay.Observation> pending = load();
                configured = true; pendingCount = pending.size(); state = "ready; waiting for visible roster";
                long nextAttempt = 0, backoff = 1000;
                while (running || !queue.isEmpty()) {
                    RosterRelay.Observation o = queue.poll(200, TimeUnit.MILLISECONDS);
                    boolean changed = false;
                    while (o != null) {
                        String key = o.source_id + "/" + o.segment_id;
                        if (!pending.containsKey(key) && pending.size() >= MAX_PENDING) {
                            dropped.incrementAndGet(); state = "outbox full";
                        } else { pending.put(key, o); changed = true; }
                        o = queue.poll();
                    }
                    if (changed) persist(pending);
                    pendingCount = pending.size();
                    if (!running || pending.isEmpty() || System.currentTimeMillis() < nextAttempt) continue;
                    Map.Entry<String, RosterRelay.Observation> first = pending.entrySet().iterator().next();
                    try {
                        send(first.getValue());
                        pending.remove(first.getKey()); persist(pending); pendingCount = pending.size();
                        state = "connected"; backoff = 1000; nextAttempt = 0;
                    } catch (IOException | RuntimeException e) {
                        state = "waiting for companion (" + e.getClass().getSimpleName() + ")";
                        nextAttempt = System.currentTimeMillis() + backoff; backoff = Math.min(30000, backoff * 2);
                    }
                }
            }
        } catch (Exception e) {
            state = "failed (" + e.getClass().getSimpleName() + ")";
        } finally { configured = false; running = false; }
    }
    private LinkedHashMap<String, RosterRelay.Observation> load() throws IOException {
        LinkedHashMap<String, RosterRelay.Observation> pending = new LinkedHashMap<>();
        if (!Files.exists(outbox)) return pending;
        if (Files.size(outbox) > 4*1024*1024) throw new IOException("outbox too large");
        RosterRelay.Observation[] observations = gson.fromJson(new String(Files.readAllBytes(outbox), StandardCharsets.UTF_8), RosterRelay.Observation[].class);
        if (observations == null || observations.length > MAX_PENDING) throw new IOException("invalid outbox");
        for (RosterRelay.Observation o : observations) {
            if (o == null || o.source_id == null || o.segment_id == null || o.event_id == null || o.players == null) throw new IOException("invalid outbox record");
            pending.put(o.source_id + "/" + o.segment_id, o);
        }
        return pending;
    }
    private void persist(Map<String, RosterRelay.Observation> pending) throws IOException {
        byte[] bytes = gson.toJson(pending.values()).getBytes(StandardCharsets.UTF_8);
        Path temp = Files.createTempFile(directory, ".roster-outbox-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try (FileChannel f = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) f.write(buffer); f.force(true);
            }
            Files.move(temp, outbox, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)) { dir.force(true); }
        } finally { Files.deleteIfExists(temp); }
    }
    private void send(RosterRelay.Observation o) throws IOException {
        if (Files.size(rendezvous) > 4096) throw new IOException("invalid bridge file");
        JsonObject config = new JsonParser().parse(new String(Files.readAllBytes(rendezvous), StandardCharsets.UTF_8)).getAsJsonObject();
        int port = config.get("port").getAsInt(); String token = config.get("token").getAsString();
        if (config.get("schema_version").getAsInt() != 1 || port < 1 || port > 65535 || !token.matches("[0-9a-f]{64}")) throw new IOException("invalid bridge config");
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/v1/observations").openConnection(Proxy.NO_PROXY);
        conn.setInstanceFollowRedirects(false); conn.setConnectTimeout(1500); conn.setReadTimeout(1500);
        conn.setRequestMethod("POST"); conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + token); conn.setRequestProperty("Content-Type", "application/json");
        byte[] body = gson.toJson(Collections.singletonMap("observations", Collections.singletonList(o))).getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(body.length);
        try {
            try (OutputStream stream = conn.getOutputStream()) { stream.write(body); }
            if (conn.getResponseCode() != 200) throw new IOException("ingest not acknowledged");
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            try (InputStream input = conn.getInputStream()) {
                byte[] buffer = new byte[1024]; int read;
                while ((read = input.read(buffer)) != -1) {
                    if (response.size() + read > 16384) throw new IOException("ack too large");
                    response.write(buffer, 0, read);
                }
            }
            JsonObject ack = new JsonParser().parse(new String(response.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
            if (ack.get("schema_version").getAsInt() != 1) throw new IOException("ack schema mismatch");
            for (JsonElement id : ack.getAsJsonArray("acknowledged")) if (o.event_id.equals(id.getAsString())) return;
            throw new IOException("missing event acknowledgement");
        } finally { conn.disconnect(); }
    }
}
