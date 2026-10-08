package dev.modsbyfox.jane.fabric.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.modsbyfox.jane.core.ClientInstallCategory;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.ModrinthDownload;
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
import java.util.regex.Pattern;

final class ModrinthService {
    private static final int MAX_JSON = 1024 * 1024;
    private static final Pattern PROJECT_ID = Pattern.compile("[A-Za-z0-9]{1,64}");
    private final HttpClient http;
    private final JsonFetcher json;

    @FunctionalInterface interface JsonFetcher {
        Optional<JsonObject> get(URI uri) throws IOException, InterruptedException;
    }

    record ClassifiedSource(Optional<ResolutionPlan.Source> source, ClientInstallCategory category) { }

    ModrinthService() { this(null); }

    ModrinthService(JsonFetcher json) {
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.json = json == null ? this::fetchJson : json;
    }

    Optional<ResolutionPlan.Source> find(ManifestEntry target) throws IOException, InterruptedException {
        return lookup(target, false).source();
    }

    /** Project metadata can advise optional installation only after an exact-file match. */
    ClassifiedSource findClassified(ManifestEntry target) throws IOException, InterruptedException {
        return lookup(target, true);
    }

    private ClassifiedSource lookup(ManifestEntry target, boolean classify) throws IOException, InterruptedException {
        URI uri = URI.create("https://api.modrinth.com/v2/version_file/" + target.sha512() + "?algorithm=sha512");
        try {
            Optional<JsonObject> response = json.get(uri);
            if (response.isEmpty()) return new ClassifiedSource(Optional.empty(), ClientInstallCategory.SERVER_REQUIRED);
            JsonObject version = response.get();
            if (!contains(version.getAsJsonArray("game_versions"), "1.20.1")
                    || !contains(version.getAsJsonArray("loaders"), "fabric"))
                throw new IOException("Modrinth returned incompatible metadata for exact hash");
            JsonArray files = version.getAsJsonArray("files");
            if (files == null) throw new IOException("Modrinth response omitted exact-hash files");
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
                ResolutionPlan.Source source = new ResolutionPlan.Source(name, download, size);
                ClientInstallCategory category = classify ? projectCategory(version) : ClientInstallCategory.SERVER_REQUIRED;
                return new ClassifiedSource(Optional.of(source), category);
            }
            throw new IOException("Modrinth response did not contain the requested exact file");
        } catch (RuntimeException exception) {
            throw new IOException("Invalid Modrinth response", exception);
        }
    }

    private ClientInstallCategory projectCategory(JsonObject version) throws InterruptedException {
        try {
            String projectId = version.get("project_id").getAsString();
            if (!PROJECT_ID.matcher(projectId).matches()) return ClientInstallCategory.SERVER_REQUIRED;
            Optional<JsonObject> response = json.get(URI.create("https://api.modrinth.com/v2/project/" + projectId));
            if (response.isEmpty()) return ClientInstallCategory.SERVER_REQUIRED;
            JsonObject project = response.get();
            if (!projectId.equals(project.get("id").getAsString())) return ClientInstallCategory.SERVER_REQUIRED;
            return ClientInstallCategory.fromModrinthEvidence(true, project.get("client_side").getAsString());
        } catch (IOException | RuntimeException exception) {
            return ClientInstallCategory.SERVER_REQUIRED;
        }
    }

    private Optional<JsonObject> fetchJson(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "modsbyfox/Jane/1.1.8-beta")
                .header("Accept", "application/json").GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() == 404) return Optional.empty();
            if (response.statusCode() != 200) throw new IOException("Modrinth lookup returned HTTP " + response.statusCode());
            byte[] bytes = readBounded(body, MAX_JSON);
            try {
                return Optional.of(JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject());
            } catch (RuntimeException exception) {
                throw new IOException("Invalid Modrinth response", exception);
            }
        }
    }

    void download(ResolutionPlan.Source source, ManifestEntry target, java.nio.file.Path destination,
                  BooleanSupplier cancelled, LongConsumer progress) throws IOException, InterruptedException {
        ModrinthDownload.download(source, target, destination, cancelled, progress, uri -> {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5))
                    .header("User-Agent", "modsbyfox/Jane/1.1.8-beta").GET().build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return new ModrinthDownload.Response(response.statusCode(), response.headers().allValues("Location"), response.body());
        });
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
