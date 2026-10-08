package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.JaneSyncSession;
import dev.modsbyfox.jane.core.ResolutionPlan;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** The only UI path that may start a ServerProvider connection. */
final class ServerDownloadConfirmScreen extends Screen {
    private static final int ROW_HEIGHT = 39;
    private record Line(FormattedCharSequence text, int color) { }
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
        return session.snapshot().resolution() == null ? List.of()
                : session.snapshot().queue(ResolutionPlan.TransferGroup.SERVER_ONLY);
    }

    private String fit(String value, int max) {
        if (font.width(value) <= max) return value;
        return font.plainSubstrByWidth(value, Math.max(0, max - font.width("…"))) + "…";
    }

    private boolean compact() { return height <= 180; }
    private int top() {
        if (compact()) return 26;
        return 29 + font.split(Component.translatable("jane.server_confirm.description"), Math.max(100, width - 24)).size() * 10
                + font.split(Component.translatable("jane.server_confirm.warning"), Math.max(100, width - 24)).size() * 10 + 32;
    }
    private int bottom() { return height - 32; }
    private void clamp() {
        List<ResolutionPlan.Item> items = items();
        int content = items.size() * ROW_HEIGHT + (compact() ? compactLines(items).size() * 10 : 0);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, content - (bottom() - top()))));
    }

    private List<Line> compactLines(List<ResolutionPlan.Item> items) {
        List<Line> lines = new ArrayList<>();
        int textWidth = Math.max(80, width - 30);
        for (var line : font.split(Component.translatable("jane.server_confirm.warning"), textWidth))
            lines.add(new Line(line, 0xFF9999));
        lines.add(new Line(null, 0));
        for (var line : font.split(Component.translatable("jane.server_confirm.description"), textWidth))
            lines.add(new Line(line, 0xFFCC77));
        for (var line : font.split(Component.translatable("jane.server_confirm.server",
                session.context().serverAddress()), textWidth))
            lines.add(new Line(line, 0xDDDDDD));
        long size = items.stream().mapToLong(i -> i.comparison().required().fileSize()).sum();
        for (var line : font.split(Component.translatable("jane.server_confirm.count",
                items.size(), SyncScreen.mib(size)), textWidth))
            lines.add(new Line(line, 0xFFFFFF));
        lines.add(new Line(null, 0));
        return lines;
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        if (compact()) {
            renderCompact(graphics);
            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }
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
            if (y + ROW_HEIGHT >= top() && y < bottom()) drawItem(graphics, item, left, right, y);
            y += ROW_HEIGHT;
        }
        graphics.disableScissor();
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderCompact(GuiGraphics graphics) {
        List<ResolutionPlan.Item> files = items();
        List<Line> lines = compactLines(files);
        clamp();
        int left = Math.max(12, width / 2 - Math.min(270, (width - 28) / 2));
        int right = width - left;
        graphics.enableScissor(left, top(), right, bottom());
        int y = top() - scroll;
        for (Line line : lines) {
            if (line.text() != null && y + 10 > top() && y < bottom())
                graphics.drawString(font, line.text(), left + 5, y + 1, line.color());
            y += 10;
        }
        for (ResolutionPlan.Item item : files) {
            if (y + ROW_HEIGHT > top() && y < bottom()) drawItem(graphics, item, left, right, y);
            y += ROW_HEIGHT;
        }
        graphics.disableScissor();
        int content = lines.size() * 10 + files.size() * ROW_HEIGHT;
        int view = bottom() - top();
        if (content > view) {
            int thumb = Math.max(10, view * view / content);
            int thumbY = top() + scroll * (view - thumb) / (content - view);
            graphics.fill(right - 3, top(), right, bottom(), 0xFF444444);
            graphics.fill(right - 3, thumbY, right, thumbY + thumb, 0xFFAAAAAA);
        }
    }

    private void drawItem(GuiGraphics graphics, ResolutionPlan.Item item, int left, int right, int y) {
        var entry = item.comparison().required();
        graphics.fill(left, y, right, y + ROW_HEIGHT - 2, 0x88000000);
        graphics.drawString(font, fit(entry.displayName(), right - left - 12), left + 6, y + 3, 0xFFFFFF);
        graphics.drawString(font, fit(entry.version() + " · " + SyncScreen.mib(entry.fileSize()) + " MiB",
                right - left - 12), left + 6, y + 15, 0xCCCCCC);
        String hash = entry.sha512();
        graphics.drawString(font, "SHA-512: " + hash.substring(0, 8) + "..." + hash.substring(120),
                left + 6, y + 27, 0xAAAAAA);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        scroll -= (int) Math.round(amount * 24);
        clamp();
        return true;
    }

    @Override public void onClose() {
        parent.serverConfirmationDeclined();
        minecraft.setScreen(parent);
    }
}
