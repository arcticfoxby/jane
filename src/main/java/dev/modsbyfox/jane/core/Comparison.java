package dev.modsbyfox.jane.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Comparison {
    public enum Status { MISSING, VERSION_MISMATCH, HASH_MISMATCH, FILE_ERROR, OK }
    public record LocalMod(String modId, String version, Path jar) { }
    public record Result(ManifestEntry required, LocalMod local, Status status, String localHash) { }
    @FunctionalInterface public interface FileHasher { String hash(Path file) throws IOException; }

    private Comparison() { }

    public static List<Result> compare(RequiredManifest manifest, Map<String, LocalMod> installed, FileHasher hasher) {
        List<Result> results = new ArrayList<>();
        for (ManifestEntry required : manifest.entries()) {
            LocalMod local = installed.get(required.modId());
            if (local == null) {
                results.add(new Result(required, null, Status.MISSING, null));
                continue;
            }
            try {
                String hash = hasher.hash(local.jar());
                Status status = !required.version().equals(local.version()) ? Status.VERSION_MISMATCH
                        : !required.sha512().equals(hash) ? Status.HASH_MISMATCH : Status.OK;
                results.add(new Result(required, local, status, hash));
            } catch (IOException | IllegalArgumentException exception) {
                results.add(new Result(required, local, Status.FILE_ERROR, null));
            }
        }
        return List.copyOf(results);
    }

    public static boolean passed(List<Result> results) {
        return results.stream().allMatch(result -> result.status() == Status.OK);
    }
}
