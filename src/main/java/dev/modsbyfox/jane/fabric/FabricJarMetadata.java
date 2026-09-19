package dev.modsbyfox.jane.fabric;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.metadata.ModEnvironment;

/** Reads only the root Fabric identity fields, without extracting the JAR. */
final class FabricJarMetadata {
    private static final int MAX_METADATA_BYTES = 1024 * 1024;
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{0,63}");

    private FabricJarMetadata() { }

    static Optional<ServerDiscovery.Candidate> read(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry metadata = zip.getEntry("fabric.mod.json");
            if (metadata == null) return Optional.empty();
            if (metadata.isDirectory() || metadata.getSize() > MAX_METADATA_BYTES)
                throw new IOException("Invalid Fabric metadata size in " + jar.getFileName());
            byte[] bytes;
            try (var input = zip.getInputStream(metadata)) {
                bytes = input.readNBytes(MAX_METADATA_BYTES + 1);
            }
            if (bytes.length == 0 || bytes.length > MAX_METADATA_BYTES)
                throw new IOException("Invalid Fabric metadata size in " + jar.getFileName());
            String json;
            try {
                json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException exception) {
                throw new IOException("Invalid Fabric metadata encoding in " + jar.getFileName(), exception);
            }
            return Optional.of(parse(json, jar));
        } catch (RuntimeException exception) {
            throw new IOException("Invalid Fabric JAR metadata in " + jar.getFileName(), exception);
        }
    }

    private static ServerDiscovery.Candidate parse(String json, Path jar) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw new IOException("Invalid Fabric metadata in " + jar.getFileName());
            reader.beginObject();
            Set<String> seen = new HashSet<>();
            String schema = null, id = null, name = null, version = null, environment = "*";
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (!seen.add(field)) throw new IOException("Duplicate Fabric metadata field in " + jar.getFileName());
                switch (field) {
                    case "schemaVersion" -> schema = value(reader, JsonToken.NUMBER);
                    case "id" -> id = value(reader, JsonToken.STRING);
                    case "name" -> name = value(reader, JsonToken.STRING);
                    case "version" -> version = value(reader, JsonToken.STRING);
                    case "environment" -> environment = value(reader, JsonToken.STRING);
                    default -> reader.skipValue();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || !"1".equals(schema)
                    || id == null || !ID.matcher(id).matches() || version == null || !validText(version, 64)) {
                throw new IOException("Invalid Fabric identity in " + jar.getFileName());
            }
            if (name == null) name = id;
            if (!validText(name, 128)) throw new IOException("Invalid Fabric name in " + jar.getFileName());
            ModEnvironment fabric = switch (environment) {
                case "*" -> ModEnvironment.UNIVERSAL;
                case "client" -> ModEnvironment.CLIENT;
                case "server" -> ModEnvironment.SERVER;
                default -> throw new IOException("Invalid Fabric environment in " + jar.getFileName());
            };
            return new ServerDiscovery.Candidate(id, name, version, fabric);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid Fabric metadata in " + jar.getFileName(), exception);
        }
    }

    private static String value(JsonReader reader, JsonToken expected) throws IOException {
        if (reader.peek() != expected) throw new IOException("Invalid Fabric metadata field type");
        return reader.nextString();
    }

    private static boolean validText(String text, int maxLength) {
        return !text.isBlank() && text.length() <= maxLength
                && text.chars().noneMatch(Character::isISOControl);
    }
}
