package dev.bedwarscompanion.capture;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class RosterTransportTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void persistsBeforeSendAndReplaysExactIDAfterRestartUntilMatchingAck() throws Exception {
        Path dir = temp.newFolder().toPath();
        AtomicInteger status = new AtomicInteger(503);
        List<String> ids = Collections.synchronizedList(new ArrayList<>());
        List<String> auth = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/observations", exchange -> {
            JsonObject batch = new JsonParser().parse(new java.io.InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)).getAsJsonObject();
            String id = batch.getAsJsonArray("observations").get(0).getAsJsonObject().get("event_id").getAsString();
            ids.add(id); auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = ("{\"schema_version\":1,\"acknowledged\":[\"" + (status.get() == 200 ? id : "wrong-id") + "\"]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get() == 201 ? 200 : status.get(), response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        String token = String.join("", Collections.nCopies(64, "a"));
        Files.write(dir.resolve("bridge.json"), ("{\"schema_version\":1,\"port\":" + server.getAddress().getPort() + ",\"token\":\"" + token + "\"}").getBytes(StandardCharsets.UTF_8));
        RosterTransport first = new RosterTransport(dir);
        RosterTransport second = null;
        try {
            RosterRelay.Observation o = new RosterRelay.Observation();
            o.source_id = UUID.randomUUID().toString(); o.segment_id = UUID.randomUUID().toString(); o.sequence = 1; o.phase = "active";
            o.players = Arrays.asList(new RosterRelay.Player("ExamplePlayer", "red"));
            await(() -> first.offer(o));
            await(() -> ids.size() >= 1);
            Path outbox = dir.resolve("roster-outbox.json");
            assertTrue(new String(Files.readAllBytes(outbox), StandardCharsets.UTF_8).contains(o.event_id));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(outbox));
            first.shutdown();
            status.set(201); // HTTP 200 but a mismatched acknowledgement must not retire the record.
            second = new RosterTransport(dir);
            await(() -> ids.size() >= 2);
            assertTrue(new String(Files.readAllBytes(outbox), StandardCharsets.UTF_8).contains(o.event_id));
            status.set(200);
            await(() -> { try { return new String(Files.readAllBytes(outbox), StandardCharsets.UTF_8).equals("[]"); } catch (Exception e) { return false; } });
            for (String id : ids) assertEquals(o.event_id, id);
            for (String header : auth) assertEquals("Bearer " + token, header);
        } finally { first.shutdown(); if (second != null) second.shutdown(); server.stop(0); }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 6000;
        while (!condition.getAsBoolean()) { if (System.currentTimeMillis() > deadline) fail("timed out"); Thread.sleep(25); }
    }
}
