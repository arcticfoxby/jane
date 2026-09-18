package dev.modsbyfox.jane.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads environment metadata only; it never downloads a JAR. */
final class ModrinthEnvironmentService {
    private static final URI ENDPOINT = URI.create("https://api.modrinth.com/v2/version_files");
    static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    static final int MAX_HASHES_PER_REQUEST = 100;

    record Response(int status, InputStream body) { }
    @FunctionalInterface interface Transport {
        Response send(HttpRequest request) throws IOException, InterruptedException;
    }

    private final Transport transport;

    ModrinthEnvironmentService() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.transport = request -> {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return new Response(response.statusCode(), response.body());
        };
    }

    ModrinthEnvironmentService(Transport transport) { this.transport = transport; }

    Map<String, String> lookup(Set<String> hashes) throws IOException {
        if (hashes.isEmpty()) return Map.of();
        if (hashes.stream().anyMatch(hash -> hash == null || !hash.matches("[0-9a-f]{128}"))) {
            throw new IOException("Invalid SHA-512 batch");
        }
        List<String> ordered = new ArrayList<>(hashes);
        Map<String, String> merged = new LinkedHashMap<>();
        for (int start = 0; start < ordered.size(); start += MAX_HASHES_PER_REQUEST) {
            Set<String> batch = new LinkedHashSet<>(ordered.subList(start, Math.min(start + MAX_HASHES_PER_REQUEST, ordered.size())));
            merged.putAll(lookupBatch(batch));
        }
        return Map.copyOf(merged);
    }

    private Map<String, String> lookupBatch(Set<String> hashes) throws IOException {
        JsonObject body = new JsonObject();
        JsonArray hashArray = new JsonArray();
        hashes.forEach(hashArray::add);
        body.add("hashes", hashArray);
        body.addProperty("algorithm", "sha512");
        HttpRequest request = HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "modsbyfox/Jane/1.0.4")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        try {
            Response response = transport.send(request);
            try (InputStream stream = response.body()) {
                if (response.status() != 200) throw new IOException("Modrinth batch lookup returned HTTP " + response.status());
                if (stream == null) throw new IOException("Modrinth batch lookup returned no body");
                return parseResponse(new String(readBounded(stream, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8), hashes);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Modrinth batch lookup interrupted", exception);
        }
    }

    static Map<String, String> parseResponse(String json, Set<String> requested) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) throw new IOException("Modrinth batch response is not an object");
            JsonObject versions = parsed.getAsJsonObject();
            Map<String, String> environments = new LinkedHashMap<>();
            for (String hash : requested) {
                if (!versions.has(hash)) continue;
                JsonElement value = versions.get(hash);
                if (!value.isJsonObject()) throw new IOException("Invalid Modrinth version for requested hash");
                JsonObject version = value.getAsJsonObject();
                JsonElement environment = version.get("environment");
                if (environment == null || !environment.isJsonPrimitive()
                        || !environment.getAsJsonPrimitive().isString() || environment.getAsString().isBlank()) {
                    throw new IOException("Invalid Modrinth environment field");
                }
                JsonArray files = version.getAsJsonArray("files");
                if (files == null) throw new IOException("Modrinth version has no files");
                boolean exactFile = false;
                for (JsonElement file : files) {
                    if (!file.isJsonObject()) throw new IOException("Invalid Modrinth file metadata");
                    JsonObject hashes = file.getAsJsonObject().getAsJsonObject("hashes");
                    if (hashes == null) throw new IOException("Modrinth file has no hashes");
                    JsonElement sha512 = hashes.get("sha512");
                    if (sha512 == null || !sha512.isJsonPrimitive() || !sha512.getAsJsonPrimitive().isString()) {
                        throw new IOException("Modrinth file has invalid SHA-512");
                    }
                    if (hash.equals(sha512.getAsString())) exactFile = true;
                }
                if (!exactFile) throw new IOException("Modrinth version does not contain requested SHA-512");
                environments.put(hash, environment.getAsString());
            }
            return Map.copyOf(environments);
        } catch (RuntimeException exception) {
            throw new IOException("Malformed Modrinth batch response", exception);
        }
    }

    static byte[] readBounded(InputStream stream, int maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int count;
        while ((count = stream.read(chunk)) != -1) {
            if (buffer.size() > maxBytes - count) throw new IOException("Modrinth batch response too large");
            buffer.write(chunk, 0, count);
        }
        return buffer.toByteArray();
    }
}
