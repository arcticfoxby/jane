package dev.modsbyfox.jane.fabric.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.PathSafety;
import dev.modsbyfox.jane.core.ResolutionPlan;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

final class ModrinthService {
    private static final int MAX_JSON = 1024 * 1024;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    Optional<ResolutionPlan.Source> find(ManifestEntry target) throws IOException, InterruptedException {
        URI uri = URI.create("https://api.modrinth.com/v2/version_file/" + target.sha512() + "?algorithm=sha512");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "modsbyfox/Jane/1.0.3")
                .header("Accept", "application/json").GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() == 404) return Optional.empty();
            if (response.statusCode() != 200) throw new IOException("Modrinth lookup returned HTTP " + response.statusCode());
            byte[] bytes = readBounded(body, MAX_JSON);
            JsonObject version = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if (!contains(version.getAsJsonArray("game_versions"), "1.20.1") || !contains(version.getAsJsonArray("loaders"), "fabric")) {
                return Optional.empty();
            }
            JsonArray files = version.getAsJsonArray("files");
            if (files == null) return Optional.empty();
            for (JsonElement element : files) {
                JsonObject file = element.getAsJsonObject();
                JsonObject hashes = file.getAsJsonObject("hashes");
                if (hashes == null || !hashes.has("sha512")
                        || !target.sha512().equals(hashes.get("sha512").getAsString())) continue;
                long size = file.get("size").getAsLong();
                if (size != target.fileSize()) continue;
                String name = PathSafety.safeJarName(file.get("filename").getAsString());
                URI download = URI.create(file.get("url").getAsString());
                if (!"https".equalsIgnoreCase(download.getScheme()) || !"cdn.modrinth.com".equalsIgnoreCase(download.getHost())
                        || download.getUserInfo() != null || download.getPort() != -1) {
                    throw new IOException("Modrinth supplied an untrusted download URL");
                }
                return Optional.of(new ResolutionPlan.Source(name, download, size));
            }
            return Optional.empty();
        } catch (RuntimeException exception) {
            throw new IOException("Invalid Modrinth response", exception);
        }
    }

    void download(ResolutionPlan.Source source, ManifestEntry target, java.nio.file.Path destination,
                  BooleanSupplier cancelled, LongConsumer progress) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(source.uri()).timeout(Duration.ofMinutes(5))
                .header("User-Agent", "modsbyfox/Jane/1.0.3").GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body(); var out = java.nio.file.Files.newOutputStream(destination,
                java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
            if (response.statusCode() != 200) throw new IOException("Modrinth download returned HTTP " + response.statusCode());
            byte[] buffer = new byte[64 * 1024];
            long received = 0;
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (cancelled.getAsBoolean()) throw new IOException("Download cancelled");
                received += count;
                if (received > target.fileSize()) throw new IOException("Download exceeds declared size");
                out.write(buffer, 0, count);
                progress.accept(received);
            }
            if (cancelled.getAsBoolean()) throw new IOException("Download cancelled");
            if (received != target.fileSize()) throw new IOException("Download size mismatch");
        }
    }

    private static byte[] readBounded(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) {
            if (out.size() + count > max) throw new IOException("Modrinth response too large");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static boolean contains(JsonArray array, String value) {
        if (array == null) return false;
        for (JsonElement element : array) if (value.equals(element.getAsString())) return true;
        return false;
    }
}
