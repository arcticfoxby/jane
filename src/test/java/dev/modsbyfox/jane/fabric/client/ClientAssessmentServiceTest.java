package dev.modsbyfox.jane.fabric.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.Hashing;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.RequiredManifest;
import dev.modsbyfox.jane.fabric.ClientPhysicalDiscovery;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientAssessmentServiceTest {
    @TempDir Path game;

    private Path mod(String filename, String id, String environment) throws Exception {
        Path mods = Files.createDirectories(game.resolve("mods"));
        Path jar = mods.resolve(filename);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new ZipEntry("fabric.mod.json"));
            output.write(("{\"schemaVersion\":1,\"id\":\"" + id
                    + "\",\"version\":\"1\",\"environment\":\"" + environment + "\"}")
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }

    @Test void requiredMismatchTakesPriorityOverCompatibilityReview() throws Exception {
        mod("sodium.jar", "sodium", "client");
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL,
                List.of(new ManifestEntry("create", "Create", "1", 10, "a".repeat(128))));
        ClientAssessment assessment = ClientAssessmentService.assess(game, manifest);
        assertFalse(assessment.requiredPassed());
        assertEquals(Comparison.Status.MISSING, assessment.requiredResults().get(0).status());
        assertNull(assessment.compatibility(), "Review must wait until required sync and restart");
        assertEquals(1, assessment.discovery().all().size());
    }

    @Test void passUsesSameDiscoveryForRequiredAndExtraMods() throws Exception {
        Path required = mod("create.jar", "create", "*");
        mod("sodium.jar", "sodium", "client");
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL,
                List.of(new ManifestEntry("create", "Create", "1", Files.size(required), Hashing.sha512(required))));
        ClientAssessment assessment = ClientAssessmentService.assess(game, manifest);
        assertTrue(assessment.requiredPassed());
        assertEquals(2, assessment.discovery().all().size());
        assertEquals(List.of("sodium"), assessment.compatibility().explicitClientMods().stream()
                .map(extra -> extra.modId()).toList());
    }

    @Test void unreadableUniversalHashDoesNotBlockPassButRequiredHashStillFailsClosed() throws Exception {
        Path required = mod("create.jar", "create", "*");
        Path universal = mod("clientlib.jar", "clientlib", "*");
        Path explicit = mod("sodium.jar", "sodium", "client");
        RequiredManifest manifest = new RequiredManifest(RequiredManifest.PROTOCOL,
                List.of(new ManifestEntry("create", "Create", "1", Files.size(required), Hashing.sha512(required))));
        var discovery = ClientPhysicalDiscovery.scan(game);
        ClientAssessment passes = ClientAssessmentService.assess(manifest, discovery, path -> {
            if (path.equals(universal)) throw new java.io.IOException("universal hash unreadable");
            return Hashing.sha512(path);
        });
        assertTrue(passes.requiredPassed());
        assertEquals(Hashing.sha512(explicit), passes.compatibility().explicitClientMods().get(0).sha512());
        assertNull(passes.compatibility().otherExtraMods().get(0).sha512());

        ClientAssessment fails = ClientAssessmentService.assess(manifest, discovery, path -> {
            if (path.equals(required)) throw new java.io.IOException("required hash unreadable");
            return Hashing.sha512(path);
        });
        assertFalse(fails.requiredPassed());
        assertEquals(Comparison.Status.FILE_ERROR, fails.requiredResults().get(0).status());
        assertNull(fails.compatibility());
    }
}
