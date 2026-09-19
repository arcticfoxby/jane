package dev.modsbyfox.jane.fabric;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;

final class ServerConfig {
    private static final System.Logger LOGGER = System.getLogger("jane");
    private static final String LEGACY_DEFAULT = "{\n  \"mode\": \"AUTO_DISCOVER\",\n  \"serverProvider\": {\n    \"enabled\": false,\n    \"bindPort\": 25566,\n    \"advertisedPort\": 25566\n  }\n}\n";
    private static final String DEFAULT = "{\n  \"mode\": \"AUTO_DISCOVER\",\n  \"serverProvider\": {\n    \"mode\": \"AUTO\"\n  }\n}\n";
    enum ProviderMode { AUTO, MINECRAFT, SEPARATE_PORT, DISABLED }
    record Provider(ProviderMode mode, OptionalInt bindPort, OptionalInt advertisedPort) {
        Provider {
            if (mode == null || bindPort == null || advertisedPort == null) throw new IllegalArgumentException("Invalid provider mode");
            if (mode == ProviderMode.SEPARATE_PORT) {
                if (bindPort.isEmpty() || advertisedPort.isEmpty() || bindPort.getAsInt() < 1
                        || bindPort.getAsInt() > 65535 || advertisedPort.getAsInt() < 1
                        || advertisedPort.getAsInt() > 65535) throw new IllegalArgumentException("Invalid provider ports");
            } else if (bindPort.isPresent() || advertisedPort.isPresent()) {
                throw new IllegalArgumentException("Ports belong only to separate-port mode");
            }
        }
        static Provider of(ProviderMode mode) { return new Provider(mode, OptionalInt.empty(), OptionalInt.empty()); }
        static Provider separate(int bind, int advertised) {
            return new Provider(ProviderMode.SEPARATE_PORT, OptionalInt.of(bind), OptionalInt.of(advertised));
        }
    }
    record Config(boolean legacyFieldsPresent, Provider provider) { }

    private ServerConfig() { }

    static Config read(Path gameDir, Path configDir) throws IOException {
        Path realGame = gameDir.toRealPath();
        if (!configDir.toAbsolutePath().normalize().getParent().equals(realGame) || Files.isSymbolicLink(configDir)) {
            throw new IOException("Jane config directory is outside the current instance");
        }
        if (!Files.exists(configDir)) Files.createDirectory(configDir);
        if (!configDir.toRealPath().getParent().equals(realGame)) throw new IOException("Jane config directory escaped current instance");
        Path janeDir = configDir.resolve("jane");
        Path path = janeDir.resolve("server.json");
        if (Files.isSymbolicLink(janeDir) || Files.isSymbolicLink(path)) {
            throw new IOException("Jane server config path is a symlink");
        }
        if (!Files.exists(janeDir)) Files.createDirectory(janeDir);
        if (!janeDir.toRealPath().getParent().equals(configDir.toRealPath())) throw new IOException("Jane config path escaped current instance");
        if (!Files.exists(path)) Files.writeString(path, DEFAULT, StandardCharsets.UTF_8);
        if (!Files.isRegularFile(path) || Files.size(path) > 64 * 1024) throw new IOException("Invalid Jane server config size or type");
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(content);
            if (!parsed.isJsonObject()) throw new IOException("Invalid Jane server.json");
            JsonObject json = parsed.getAsJsonObject();
            for (String key : json.keySet()) {
                if (!"mode".equals(key) && !"requiredMods".equals(key) && !"environmentOverrides".equals(key)
                        && !"serverProvider".equals(key)) {
                    throw new IOException("Unknown Jane server.json field: " + key);
                }
            }
            if (json.has("mode") && (!json.get("mode").isJsonPrimitive()
                    || !"AUTO_DISCOVER".equals(json.get("mode").getAsString()))) {
                throw new IOException("Jane server.json mode must be AUTO_DISCOVER");
            }
            boolean legacy = json.has("requiredMods") || json.has("environmentOverrides");
            if (!json.has("mode") && !legacy) throw new IOException("Jane server.json requires AUTO_DISCOVER mode");
            Provider provider = Provider.of(ProviderMode.AUTO);
            if (json.has("serverProvider")) {
                if (!json.get("serverProvider").isJsonObject()) throw new IOException("Invalid ServerProvider config");
                JsonObject settings = json.getAsJsonObject("serverProvider");
                for (String key : settings.keySet()) {
                    if (!"mode".equals(key) && !"enabled".equals(key) && !"bindPort".equals(key)
                            && !"advertisedPort".equals(key)) {
                        throw new IOException("Unknown ServerProvider field: " + key);
                    }
                }
                if (settings.has("mode")) {
                    if (settings.has("enabled") || !settings.get("mode").isJsonPrimitive()
                            || !settings.getAsJsonPrimitive("mode").isString()) throw new IOException("Invalid ServerProvider mode");
                    ProviderMode selected;
                    try { selected = ProviderMode.valueOf(settings.get("mode").getAsString()); }
                    catch (IllegalArgumentException exception) { throw new IOException("Invalid ServerProvider mode", exception); }
                    provider = selected == ProviderMode.SEPARATE_PORT
                            ? Provider.separate(port(settings, "bindPort"), port(settings, "advertisedPort"))
                            : Provider.of(selected);
                    if (selected != ProviderMode.SEPARATE_PORT
                            && (settings.has("bindPort") || settings.has("advertisedPort")))
                        throw new IOException("Ports require SEPARATE_PORT mode");
                } else {
                    if (!settings.has("enabled") || !settings.get("enabled").isJsonPrimitive()
                            || !settings.getAsJsonPrimitive("enabled").isBoolean()) throw new IOException("Invalid ServerProvider enabled");
                    int bind = port(settings, "bindPort");
                    int advertised = port(settings, "advertisedPort");
                    if (settings.get("enabled").getAsBoolean()) provider = Provider.separate(bind, advertised);
                    else if (LEGACY_DEFAULT.equals(content)) {
                        LOGGER.log(System.Logger.Level.INFO, "Jane migrated legacy default ServerProvider configuration to AUTO.");
                        provider = Provider.of(ProviderMode.AUTO);
                    } else {
                        LOGGER.log(System.Logger.Level.WARNING, "Jane retained legacy disabled ServerProvider configuration; select AUTO explicitly to enable Minecraft transport.");
                        provider = Provider.of(ProviderMode.DISABLED);
                    }
                }
            }
            return new Config(legacy, provider);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid Jane server.json", exception);
        }
    }

    private static int port(JsonObject json, String key) throws IOException {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()
                || !json.getAsJsonPrimitive(key).isNumber()
                || !json.get(key).getAsString().matches("[0-9]{1,5}")) throw new IOException("Invalid ServerProvider " + key);
        int value = json.get(key).getAsInt();
        if (value < 1 || value > 65535) throw new IOException("Invalid ServerProvider " + key);
        return value;
    }
}
