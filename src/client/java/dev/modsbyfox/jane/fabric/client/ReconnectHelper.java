package dev.modsbyfox.jane.fabric.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

final class ReconnectHelper {
    private ReconnectHelper() { }

    static void reconnect(Minecraft client, ReconnectTarget target) {
        ConnectScreen.startConnecting(new TitleScreen(), client,
                ServerAddress.parseString(target.data().ip), target.data(), false);
    }
}
