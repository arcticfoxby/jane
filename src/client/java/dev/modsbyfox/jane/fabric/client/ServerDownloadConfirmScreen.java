package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.JaneSyncSession;
import dev.modsbyfox.jane.core.ResolutionPlan;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The only UI path that may start a ServerProvider connection. */
final class ServerDownloadConfirmScreen extends Screen {
    private static final int ROW_HEIGHT = 39;
    private final SyncScreen parent;
    private final JaneSyncSession session;
    private int scroll;

    ServerDownloadConfirmScreen(SyncScreen parent, JaneSyncSession session) {
        super(Component.translatable("jane.server_confirm.title"));
        this.parent = parent;
        this.session = session;
    }

    @Override protected void init() {
        int buttonWidth = Math.min(150, (width - 42) / 2);
        addRenderableWidget(Button.builder(Component.translatable("jane.server_confirm.download"), button -> {
            minecraft.setScreen(parent);
            parent.startServerDownloads();
        }).bounds(width / 2 - buttonWidth - 3, height - 26, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("jane.sync.cancel"), button -> onClose())
                .bounds(width / 2 + 3, height - 26, buttonWidth, 20).build());
    }

    private List<ResolutionPlan.Item> items() {
        ResolutionPlan plan = session.snapshot().resolution();
        return plan == null ? List.of() : plan.queue(ResolutionPlan.Classification.SERVER_DOWNLOADABLE);
    }

    private String fit(String value, int max) {
        if (font.width(value) <= max) return value;
        return font.plainSubstrByWidth(value, Math.max(0, max - font.width("…"))) + "…";
    }

    private int top() {
        return 29 + font.split(Component.translatable("jane.server_confirm.description"), Math.max(100, width - 24)).size() * 10
                + font.split(Component.translatable("jane.server_confirm.warning"), Math.max(100, width - 24)).size() * 10 + 32;
    }
    private int bottom() { return height - 32; }
    private void clamp() { scroll = Math.max(0, Math.min(scroll, Math.max(0, items().size() * ROW_HEIGHT - (bottom() - top())))); }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        int textY = 28;
        for (var line : font.split(Component.translatable("jane.server_confirm.description"), Math.max(100, width - 24))) {
            graphics.drawCenteredString(font, line, width / 2, textY, 0xFFCC77);
            textY += 10;
        }
        textY += 2;
        for (var line : font.split(Component.translatable("jane.server_confirm.warning"), Math.max(100, width - 24))) {
            graphics.drawCenteredString(font, line, width / 2, textY, 0xFF9999);
            textY += 10;
        }
        graphics.drawCenteredString(font, Component.literal(fit(Component.translatable("jane.server_confirm.server",
                session.context().serverAddress()).getString(), width - 24)),
                width / 2, textY + 2, 0xDDDDDD);
        List<ResolutionPlan.Item> items = items();
        long size = items.stream().mapToLong(i -> i.comparison().required().fileSize()).sum();
        graphics.drawCenteredString(font, Component.translatable("jane.server_confirm.count", items.size(), SyncScreen.mib(size)),
                width / 2, textY + 15, 0xFFFFFF);
        clamp();
        int left = Math.max(12, width / 2 - Math.min(270, (width - 28) / 2));
        int right = width - left;
        graphics.enableScissor(left, top(), right, bottom());
        int y = top() - scroll;
        for (ResolutionPlan.Item item : items) {
            if (y + ROW_HEIGHT >= top() && y < bottom()) {
                var entry = item.comparison().required();
                graphics.fill(left, y, right, y + ROW_HEIGHT - 2, 0x88000000);
                graphics.drawString(font, fit(entry.displayName(), right - left - 12), left + 6, y + 3, 0xFFFFFF);
                graphics.drawString(font, fit(entry.version() + " · " + SyncScreen.mib(entry.fileSize()) + " MiB", right - left - 12),
                        left + 6, y + 15, 0xCCCCCC);
                String hash = entry.sha512();
                graphics.drawString(font, "SHA-512: " + hash.substring(0, 8) + "..." + hash.substring(120),
                        left + 6, y + 27, 0xAAAAAA);
            }
            y += ROW_HEIGHT;
        }
        graphics.disableScissor();
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        scroll -= (int) Math.round(amount * 24);
        clamp();
        return true;
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}
