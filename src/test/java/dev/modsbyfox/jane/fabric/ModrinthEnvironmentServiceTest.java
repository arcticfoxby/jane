package dev.modsbyfox.jane.fabric;

import static org.junit.jupiter.api.Assertions.*;

import dev.modsbyfox.jane.core.EnvironmentRules;
import dev.modsbyfox.jane.core.RequiredEnvironment;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
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

    @Test
    void parsesExactHashAndTreatsMissingAsUnknown() throws Exception {
        Map<String, String> result = ModrinthEnvironmentService.parseResponse(response(A, "client_and_server"), Set.of(A, B));
        assertEquals("client_and_server", result.get(A));
        assertFalse(result.containsKey(B));
        assertEquals(RequiredEnvironment.UNKNOWN, EnvironmentRules.fromModrinth(result.get(B)));
        assertEquals(RequiredEnvironment.UNKNOWN,
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
            assertEquals("modsbyfox/Jane/1.0.3", request.headers().firstValue("User-Agent").orElseThrow());
            return new ModrinthEnvironmentService.Response(200,
                    new ByteArrayInputStream(response(A, "server_only").getBytes(StandardCharsets.UTF_8)));
        });
        assertEquals(Map.of(A, "server_only"), service.lookup(Set.of(A)));
        assertEquals(1, calls.get());

        ModrinthEnvironmentService broken = new ModrinthEnvironmentService(request ->
                new ModrinthEnvironmentService.Response(503, new ByteArrayInputStream(new byte[0])));
        assertThrows(IOException.class, () -> broken.lookup(Set.of(A)));
    }
}
