package dev.modsbyfox.jane.fabric;

import dev.modsbyfox.jane.core.JaneVersion;

/** Prefixes Jane events written through Minecraft/Fabric's normal logger. */
public final class JaneLog {
    private static final String VERSION = JaneVersion.VALUE;
    private JaneLog() { }
    public static String server() { return "[Jane " + VERSION + "][SERVER] "; }
    public static String clientStartup() { return "[Jane " + VERSION + "][CLIENT] "; }
    public static String client(String serverId) {
        return "[Jane " + VERSION + "][CLIENT][server=" + shortId(serverId) + "] ";
    }
    public static String sync(String syncId) {
        return "[Jane " + VERSION + "][CLIENT][sync=" + shortId(syncId) + "] ";
    }
    private static String shortId(String value) { return value.substring(0, Math.min(12, value.length())); }
}
