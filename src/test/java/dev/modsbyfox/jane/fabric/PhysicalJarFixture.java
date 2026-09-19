package dev.modsbyfox.jane.fabric;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Small real ZIP/JAR fixtures for physical discovery tests. */
final class PhysicalJarFixture {
    private PhysicalJarFixture() { }

    static Path mod(Path mods, String fileName, String id, String environment) throws IOException {
        return jar(mods, fileName, "{\"schemaVersion\":1,\"id\":\"" + id
                + "\",\"name\":\"Name " + id + "\",\"version\":\"1.2.3\",\"environment\":\""
                + environment + "\"}");
    }

    static Path jar(Path mods, String fileName, String metadata) throws IOException {
        Files.createDirectories(mods);
        Path jar = mods.resolve(fileName);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(jar))) {
            if (metadata != null) {
                output.putNextEntry(new ZipEntry("fabric.mod.json"));
                output.write(metadata.getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
            output.putNextEntry(new ZipEntry("fixture.txt"));
            output.write(fileName.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }
}
