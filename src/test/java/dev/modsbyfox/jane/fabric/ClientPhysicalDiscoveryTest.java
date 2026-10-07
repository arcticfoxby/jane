package dev.modsbyfox.jane.fabric;

import static org.junit.jupiter.api.Assertions.*;

import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.Hashing;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.RequiredManifest;
import dev.modsbyfox.jane.core.ResolutionPlan;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientPhysicalDiscoveryTest {
    @TempDir Path game;

    private Path mods() { return game.resolve("mods"); }

    private Path shapeShifterWithNestedAzureLib() throws IOException {
        Files.createDirectories(mods());
        Path jar = mods().resolve("shape-shifter.jar");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new ZipEntry("fabric.mod.json"));
            output.write("{\"schemaVersion\":1,\"id\":\"shape-shifter-curse\",\"version\":\"1\"}"
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("META-INF/jars/azurelib.jar"));
            output.write("nested fixture".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }

    private ManifestEntry required(Path jar, String id, String version) throws IOException {
        return new ManifestEntry(id, id, version, Files.size(jar), Hashing.sha512(jar));
    }

    private List<Comparison.Result> compare(List<ManifestEntry> entries) throws Exception {
        var found = ClientPhysicalDiscovery.scan(game);
        return Comparison.compareCandidates(new RequiredManifest(RequiredManifest.PROTOCOL, entries),
                found.candidates(), Hashing::sha512);
    }

    @Test void nestedAzureLibDoesNotReplaceExactTopLevelAzureLib() throws Exception {
        shapeShifterWithNestedAzureLib();
        Path azurelib = PhysicalJarFixture.mod(mods(), "azurelib.jar", "azurelib", "3.0.19", "*");
        var found = ClientPhysicalDiscovery.scan(game);
        assertEquals(List.of("azurelib", "shape-shifter-curse"), found.candidates().keySet().stream().sorted().toList());
        assertEquals(Comparison.Status.OK, compare(List.of(required(azurelib, "azurelib", "3.0.19")))
                .get(0).status());
    }

    @Test void nestedAzureLibWithoutPhysicalJarIsMissing() throws Exception {
        shapeShifterWithNestedAzureLib();
        ManifestEntry required = new ManifestEntry("azurelib", "AzureLib", "3.0.19", 10, "a".repeat(128));
        assertEquals(Comparison.Status.MISSING, compare(List.of(required)).get(0).status());
    }

    @Test void installedExactAzureLibCrateDelightAndMoreDelightStayPresent() throws Exception {
        List<ManifestEntry> required = List.of(
                required(PhysicalJarFixture.mod(mods(), "azurelib.jar", "azurelib", "3.0.19", "*"),
                        "azurelib", "3.0.19"),
                required(PhysicalJarFixture.mod(mods(), "cratedelight.jar", "cratedelight", "1.0", "*"),
                        "cratedelight", "1.0"),
                required(PhysicalJarFixture.mod(mods(), "moredelight.jar", "moredelight", "1.0", "*"),
                        "moredelight", "1.0"));
        List<Comparison.Result> compared = compare(required);
        assertTrue(Comparison.passed(compared));
        ResolutionPlan plan = ResolutionPlan.resolve(compared, entry -> {
            fail("Exact physical JAR must never be resolved");
            return Optional.empty();
        });
        assertEquals(3, plan.count(ResolutionPlan.Availability.ALREADY_PRESENT));
        assertEquals(0, plan.count(ResolutionPlan.Availability.TRUSTED_AVAILABLE));
    }

    @Test void farmersDelightExactPhysicalJarPassesAndRetainsUnicodeOldCopy() throws Exception {
        Path old = PhysicalJarFixture.mod(mods(), "【农夫乐事】farmers-delight-fabric-1.4.3.jar",
                "farmersdelight", "1.20.1-1.4.3", "*");
        Path exact = PhysicalJarFixture.mod(mods(), "【农夫乐事：重织】FarmersDelight-1.20.1-2.5.4+refabricated.jar",
                "farmersdelight", "1.20.1-2.5.4+refabricated", "*");
        var found = ClientPhysicalDiscovery.scan(game);
        assertEquals(List.of(old, exact), found.candidates().get("farmersdelight").stream()
                .map(Comparison.LocalMod::jar).toList());
        List<Comparison.Result> compared = compare(List.of(required(exact, "farmersdelight",
                "1.20.1-2.5.4+refabricated")));
        assertEquals(Comparison.Status.OK, compared.get(0).status());
        assertEquals(exact, compared.get(0).local().jar());
        assertEquals(List.of(old), compared.get(0).retainedCandidates().stream()
                .map(Comparison.LocalMod::jar).toList());
        assertTrue(Comparison.passed(compared));
        ResolutionPlan plan = ResolutionPlan.resolve(compared, entry -> {
            fail("An exact physical candidate must not be resolved");
            return Optional.empty();
        });
        assertEquals(1, plan.count(ResolutionPlan.Availability.ALREADY_PRESENT));
        assertTrue(Files.exists(old));
        assertTrue(Files.exists(exact));
    }

    @Test void duplicateNoExactIsMissingThenSecondComparisonPassesAfterAdd() throws Exception {
        Path oldA = PhysicalJarFixture.mod(mods(), "old-a.jar", "example", "1.0", "*");
        Path oldB = PhysicalJarFixture.mod(mods(), "old-b.jar", "example", "1.1", "*");
        Path requiredFile = PhysicalJarFixture.mod(game, "required.jar", "example", "2.0", "*");
        ManifestEntry required = required(requiredFile, "example", "2.0");
        Comparison.Result first = compare(List.of(required)).get(0);
        assertEquals(Comparison.Status.MISSING, first.status());
        assertNull(first.local());
        assertEquals(List.of(oldA, oldB), first.retainedCandidates().stream().map(Comparison.LocalMod::jar).toList());
        ResolutionPlan before = ResolutionPlan.resolve(List.of(first), entry -> Optional.of(
                new ResolutionPlan.Source("old-a.jar", URI.create("https://cdn.modrinth.com/test.jar"), entry.fileSize())));
        assertEquals(1, before.count(ResolutionPlan.Availability.TRUSTED_AVAILABLE));
        Path installed = mods().resolve(ResolutionPlan.serverSource(required).name());
        Files.copy(requiredFile, installed);
        Comparison.Result second = compare(List.of(required)).get(0);
        assertEquals(Comparison.Status.OK, second.status());
        assertEquals(installed, second.local().jar());
        assertEquals(2, second.retainedCandidates().size());
        assertEquals(1, ResolutionPlan.resolve(List.of(second), entry -> {
            fail("Second comparison must not resolve or download again");
            return Optional.empty();
        }).count(ResolutionPlan.Availability.ALREADY_PRESENT));
        assertTrue(Files.exists(oldA));
        assertTrue(Files.exists(oldB));
    }

    @Test void mixedAvailabilityTreatsDuplicateNoExactAsServerDownloadable() throws Exception {
        PhysicalJarFixture.mod(mods(), "bad-old.jar", "bad", "1.0", "*");
        PhysicalJarFixture.mod(mods(), "bad-new.jar", "bad", "2.0", "*");
        List<ManifestEntry> entries = List.of(
                new ManifestEntry("trusted", "Trusted", "1", 10, "a".repeat(128)),
                new ManifestEntry("server", "Server", "1", 10, "b".repeat(128)),
                new ManifestEntry("bad", "Bad", "2.0", 10, "c".repeat(128)));
        var compared = compare(entries);
        ResolutionPlan plan = ResolutionPlan.resolve(compared, entry -> entry.modId().equals("trusted")
                ? Optional.of(new ResolutionPlan.Source("trusted.jar",
                URI.create("https://cdn.modrinth.com/test.jar"), entry.fileSize())) : Optional.empty(),
                true, ignored -> { });
        assertEquals(Comparison.Status.MISSING, compared.get(2).status());
        assertEquals(2, compared.get(2).retainedCandidates().size());
        assertEquals(1, plan.count(ResolutionPlan.Availability.TRUSTED_AVAILABLE));
        assertEquals(2, plan.count(ResolutionPlan.Availability.SERVER_ONLY));
        assertEquals(0, plan.count(ResolutionPlan.Availability.LOCAL_ERROR));
    }
}
