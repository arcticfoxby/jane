package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManifestTest {
    private static final String A = "a".repeat(128);
    private static final String B = "b".repeat(128);

    @Test
    void comparisonRequiresExactHashAndIgnoresExtras() {
        RequiredManifest manifest = new RequiredManifest(1, List.of(new ManifestEntry("create", "Create", "1.2.0", 100, A)));
        Path jar = Path.of("create.jar");
        assertEquals(Comparison.Status.MISSING, Comparison.compare(manifest, Map.of(), path -> A).get(0).status());
        var installed = Map.of("create", new Comparison.LocalMod("create", "1.2.0", jar),
                "sodium", new Comparison.LocalMod("sodium", "any", Path.of("sodium.jar")));
        assertTrue(Comparison.passed(Comparison.compare(manifest, installed, path -> A)));
        assertEquals(Comparison.Status.HASH_MISMATCH, Comparison.compare(manifest, installed, path -> B).get(0).status());
        var older = Map.of("create", new Comparison.LocalMod("create", "1.1.0", jar));
        assertEquals(Comparison.Status.VERSION_MISMATCH, Comparison.compare(manifest, older, path -> A).get(0).status());
    }

    @Test
    void protocolCodecRejectsMalformedInput() throws Exception {
        RequiredManifest manifest = new RequiredManifest(1, List.of(new ManifestEntry("create", "Create", "1.2.0", 100, A)));
        assertEquals(manifest, ManifestCodec.decode(ManifestCodec.encode(manifest)));
        byte[] unsupported = ManifestCodec.encode(manifest);
        ByteBuffer.wrap(unsupported).putInt(0, 2);
        assertThrows(ManifestCodec.UnsupportedProtocolException.class, () -> ManifestCodec.decode(unsupported));
        byte[] many = new byte[8];
        ByteBuffer.wrap(many).putInt(1).putInt(129);
        assertThrows(IOException.class, () -> ManifestCodec.decode(many));
        byte[] oversized = new byte[RequiredManifest.MAX_PAYLOAD + 1];
        assertThrows(IOException.class, () -> ManifestCodec.decode(oversized));
        byte[] badString = new byte[12];
        ByteBuffer.wrap(badString).putInt(1).putInt(1).putInt(999999);
        assertThrows(IOException.class, () -> ManifestCodec.decode(badString));
        assertThrows(IllegalArgumentException.class, () -> new ManifestEntry("../bad", "Bad", "1", 1, A));
        assertThrows(IllegalArgumentException.class, () -> new ManifestEntry("bad", "Bad", "1", 1, "x".repeat(128)));
        assertThrows(IllegalArgumentException.class, () -> new RequiredManifest(1, List.of(manifest.entries().get(0), manifest.entries().get(0))));
    }
}
