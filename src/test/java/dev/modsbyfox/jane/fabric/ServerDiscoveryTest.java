package dev.modsbyfox.jane.fabric;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerDiscoveryTest {
    @TempDir Path gameDir;

    private ServerDiscovery.Candidate candidate(String id, boolean nested, Path jar) {
        return new ServerDiscovery.Candidate(id, id, "1.0", ModEnvironment.UNIVERSAL, nested, List.of(jar));
    }

    @Test
    void discoversOnlyDirectTopLevelNonInternalJars() throws Exception {
        Path mods = Files.createDirectory(gameDir.resolve("mods"));
        Path a = Files.write(mods.resolve("a.jar"), new byte[]{1});
        Path b = Files.write(mods.resolve("b.jar"), new byte[]{2});
        ServerDiscovery.Discovery discovery = ServerDiscovery.discoverWithStats(List.of(
                candidate("a", false, a), candidate("nested", true, a), candidate("jane", false, b),
                candidate("fabric-api", false, b), candidate("outside", false, gameDir.resolve("library.jar")),
                candidate("b", false, b)), gameDir);
        assertEquals(List.of("a", "b"), discovery.mods().stream().map(item -> item.candidate().modId()).toList());
        assertEquals(1, discovery.nestedSkipped());
        assertEquals(3, discovery.systemSkipped());
    }

    @Test
    void rejectsTwoIndependentTopLevelIdentitiesForOneJar() throws Exception {
        Path mods = Files.createDirectory(gameDir.resolve("mods"));
        Path jar = Files.write(mods.resolve("a.jar"), new byte[]{1});
        IOException error = assertThrows(IOException.class, () -> ServerDiscovery.discover(List.of(
                candidate("a", false, jar), candidate("b", false, jar)), gameDir));
        assertTrue(error.getMessage().contains("Ambiguous top-level mod identities"));
    }
}
