package dev.modsbyfox.jane.fabric;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.modsbyfox.jane.core.EnvironmentRules;
import dev.modsbyfox.jane.core.ClientSyncDecision;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ModrinthEnvironmentServiceTest {
    private static final String A = "a".repeat(128);
    private static final String B = "b".repeat(128);

    private static String version(String hash, String environment) {
        return "{\"environment\":\"" + environment + "\",\"files\":[{\"hashes\":{\"sha512\":\"" + hash + "\"}}]}";
    }

    private static String response(String hash, String environment) {
        return "{\"" + hash + "\":" + version(hash, environment) + "}";
    }

    private static String requestBody(HttpRequest request) {
        CompletableFuture<String> completed = new CompletableFuture<>();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer buffer) {
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            public void onError(Throwable error) { completed.completeExceptionally(error); }
            public void onComplete() { completed.complete(bytes.toString(StandardCharsets.UTF_8)); }
        });
        return completed.orTimeout(5, TimeUnit.SECONDS).join();
    }

    @Test
    void parsesExactHashAndTreatsMissingAsUnknown() throws Exception {
        Map<String, String> result = ModrinthEnvironmentService.parseResponse(response(A, "client_and_server"), Set.of(A, B));
        assertEquals("client_and_server", result.get(A));
        assertFalse(result.containsKey(B));
        assertEquals(ClientSyncDecision.SYNC_CONSERVATIVE, EnvironmentRules.fromModrinth(result.get(B)));
        assertEquals(ClientSyncDecision.SYNC_CONSERVATIVE,
                EnvironmentRules.fromModrinth(ModrinthEnvironmentService.parseResponse(response(A, "future_value"),
                        Set.of(A)).get(A)));
    }

    @Test
    void rejectsMalformedOrMismatchedMetadata() {
        for (String invalid : new String[]{"{", "[]", "null", "{\"" + A + "\":null}",
                "{\"" + A + "\":{\"environment\":123,\"files\":[]}}",
                "{\"" + A + "\":{\"environment\":\"client_and_server\",\"files\":[]}}",
                "{\"" + A + "\":{\"environment\":\"client_and_server\","
                        + "\"files\":[{\"hashes\":{\"sha512\":\"" + B + "\"}}]}}"}) {
            assertThrows(IOException.class, () -> ModrinthEnvironmentService.parseResponse(invalid, Set.of(A)), invalid);
        }
    }

    @Test
    void boundedReaderRejectsOversizedBody() {
        assertThrows(IOException.class, () -> ModrinthEnvironmentService.readBounded(
                new ByteArrayInputStream("abcd".getBytes(StandardCharsets.UTF_8)), 3));
    }

    @Test
    void sendsOneFixedEndpointRequestAndFailsClosedOnHttpError() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ModrinthEnvironmentService service = new ModrinthEnvironmentService(request -> {
            calls.incrementAndGet();
            assertEquals("POST", request.method());
            assertEquals("https://api.modrinth.com/v2/version_files", request.uri().toString());
            assertEquals(Duration.ofSeconds(30), request.timeout().orElseThrow());
            assertEquals("application/json", request.headers().firstValue("Accept").orElseThrow());
            assertEquals("application/json", request.headers().firstValue("Content-Type").orElseThrow());
            assertEquals("modsbyfox/Jane/1.0.4.1", request.headers().firstValue("User-Agent").orElseThrow());
            return new ModrinthEnvironmentService.Response(200,
                    new ByteArrayInputStream(response(A, "server_only").getBytes(StandardCharsets.UTF_8)));
        });
        assertEquals(Map.of(A, "server_only"), service.lookup(Set.of(A)));
        assertEquals(1, calls.get());

        ModrinthEnvironmentService broken = new ModrinthEnvironmentService(request ->
                new ModrinthEnvironmentService.Response(503, new ByteArrayInputStream(new byte[0])));
        assertThrows(IOException.class, () -> broken.lookup(Set.of(A)));
        ModrinthEnvironmentService timeout = new ModrinthEnvironmentService(request -> {
            throw new HttpTimeoutException("timed out");
        });
        assertThrows(HttpTimeoutException.class, () -> timeout.lookup(Set.of(A)));
    }

    @Test
    void splits250HashesAndFailsIfAnyBatchFails() throws Exception {
        Set<String> hashes = new LinkedHashSet<>();
        for (int i = 0; i < 250; i++) hashes.add(String.format("%0128x", i));
        String[] matched = {String.format("%0128x", 0), String.format("%0128x", 100), String.format("%0128x", 200)};
        AtomicInteger calls = new AtomicInteger();
        ModrinthEnvironmentService service = new ModrinthEnvironmentService(request -> {
            int index = calls.getAndIncrement();
            var body = JsonParser.parseString(requestBody(request)).getAsJsonObject();
            assertEquals(index < 2 ? 100 : 50, body.getAsJsonArray("hashes").size());
            assertEquals("sha512", body.get("algorithm").getAsString());
            return new ModrinthEnvironmentService.Response(200,
                    new ByteArrayInputStream(response(matched[index], "client_and_server").getBytes(StandardCharsets.UTF_8)));
        });
        assertEquals(Set.of(matched), service.lookup(hashes).keySet());
        assertEquals(3, calls.get());

        AtomicInteger failedCalls = new AtomicInteger();
        ModrinthEnvironmentService failed = new ModrinthEnvironmentService(request -> {
            int call = failedCalls.incrementAndGet();
            return new ModrinthEnvironmentService.Response(call == 2 ? 503 : 200,
                    new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
        });
        assertThrows(IOException.class, () -> failed.lookup(hashes));
        assertEquals(2, failedCalls.get());
    }
}
