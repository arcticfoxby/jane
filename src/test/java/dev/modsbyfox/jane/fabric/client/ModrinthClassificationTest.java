package dev.modsbyfox.jane.fabric.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.modsbyfox.jane.core.ClientInstallCategory;
import dev.modsbyfox.jane.core.ManifestEntry;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ModrinthClassificationTest {
    private static final String HASH = "a".repeat(128);
    private static final ManifestEntry TARGET = new ManifestEntry("example", "Example", "1", 123, HASH);

    private static JsonObject version(String hash) {
        return JsonParser.parseString("""
                {"project_id":"A1b2C3d4", "game_versions":["1.20.1"], "loaders":["fabric"],
                 "files":[{"hashes":{"sha512":"%s"}, "size":123,
                           "filename":"example.jar", "url":"https://cdn.modrinth.com/data/example.jar"}]}
                """.formatted(hash)).getAsJsonObject();
    }

    private static JsonObject project(String id, String clientSide) {
        return JsonParser.parseString("{" + "\"id\":\"" + id + "\",\"client_side\":\"" + clientSide + "\"}").getAsJsonObject();
    }

    @Test void exactHashAndMatchingProjectOptionalAdviceCanClassifyOptional() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ModrinthService service = new ModrinthService(uri -> {
            calls.incrementAndGet();
            return Optional.of(uri.getPath().startsWith("/v2/version_file/")
                    ? version(HASH) : project("A1b2C3d4", "optional"));
        });
        ModrinthService.ClassifiedSource result = service.findClassified(TARGET);
        assertEquals(ClientInstallCategory.CLIENT_OPTIONAL, result.category());
        assertTrue(result.source().isPresent());
        assertEquals(2, calls.get());
    }

    @Test void unverifiedSameNameProjectCannotClassifyOptional() {
        AtomicInteger calls = new AtomicInteger();
        ModrinthService service = new ModrinthService(uri -> {
            calls.incrementAndGet();
            return Optional.of(version("b".repeat(128)));
        });
        assertThrows(IOException.class, () -> service.findClassified(TARGET));
        assertEquals(1, calls.get());
    }

    @Test void projectLookupFailureAndConflictingMetadataFallBackToRequired() throws Exception {
        ModrinthService networkFailure = new ModrinthService(uri -> {
            if (uri.getPath().startsWith("/v2/project/")) throw new IOException("offline");
            return Optional.of(version(HASH));
        });
        var fallback = networkFailure.findClassified(TARGET);
        assertTrue(fallback.source().isPresent());
        assertEquals(ClientInstallCategory.SERVER_REQUIRED, fallback.category());

        ModrinthService conflicting = new ModrinthService(uri -> Optional.of(
                uri.getPath().startsWith("/v2/project/") ? project("Other123", "optional") : version(HASH)));
        assertEquals(ClientInstallCategory.SERVER_REQUIRED, conflicting.findClassified(TARGET).category());
    }

    @Test void noExactVersionIsRequiredWithoutGuessingByName() throws Exception {
        ModrinthService service = new ModrinthService(uri -> Optional.empty());
        var result = service.findClassified(TARGET);
        assertTrue(result.source().isEmpty());
        assertEquals(ClientInstallCategory.SERVER_REQUIRED, result.category());
    }
}
