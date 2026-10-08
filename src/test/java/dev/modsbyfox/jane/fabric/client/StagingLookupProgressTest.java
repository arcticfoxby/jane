package dev.modsbyfox.jane.fabric.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.ManifestEntry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StagingLookupProgressTest {
    @Test void eightyFourMatchedAndOneMissingReportOneSourceLookup() {
        List<Comparison.Result> results = new ArrayList<>();
        for (int index = 0; index < 85; index++) {
            ManifestEntry entry = new ManifestEntry("mod" + index, "Mod " + index, "1", 1,
                    "a".repeat(128));
            results.add(new Comparison.Result(entry, null,
                    index == 84 ? Comparison.Status.MISSING : Comparison.Status.OK, null));
        }
        int[] progress = StagingService.lookupProgress(results);
        assertArrayEquals(new int[85], java.util.Arrays.copyOf(progress, 85));
        assertEquals(1, progress[85]);
    }

    @Test void fileErrorDoesNotCountAsNetworkLookup() {
        ManifestEntry entry = new ManifestEntry("broken", "Broken", "1", 1, "a".repeat(128));
        assertArrayEquals(new int[] {0, 0}, StagingService.lookupProgress(List.of(
                new Comparison.Result(entry, null, Comparison.Status.FILE_ERROR, null))));
    }
}
