package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.JaneSyncSession;
import dev.modsbyfox.jane.core.ResolutionPlan;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** A clipped, scrollable list of the server's required mods only. */
final class SyncDetailsScreen extends Screen {
    private static final int HEADER_HEIGHT = 23;
    private final Screen parent;
    private final JaneSyncSession session;
    private boolean downloadsOpen = true;
    private boolean serverOpen = true;
    private boolean presentOpen;
    private boolean unresolvedOpen = true;
    private int scroll;
    private boolean draggingBar;
    private record Row(ResolutionPlan.Classification group, JaneSyncSession.ItemState item, int height) { }

    SyncDetailsScreen(Screen parent, JaneSyncSession session) {
        super(Component.translatable("jane.details.title"));
        this.parent = parent;
        this.session = session;
    }

    @Override
    protected void init() {
        int buttonWidth = Math.min(150, width - 40);
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds((width - buttonWidth) / 2, height - 26, buttonWidth, 20).build());
    }

    private List<Row> rows(JaneSyncSession.Snapshot snapshot) {
        List<Row> rows = new ArrayList<>();
        if (snapshot.resolution() == null) return rows;
        addGroup(rows, snapshot, ResolutionPlan.Classification.MODRINTH_DOWNLOADABLE, downloadsOpen);
        addGroup(rows, snapshot, ResolutionPlan.Classification.SERVER_DOWNLOADABLE, serverOpen);
        addGroup(rows, snapshot, ResolutionPlan.Classification.ALREADY_PRESENT, presentOpen);
        addGroup(rows, snapshot, ResolutionPlan.Classification.UNRESOLVED, unresolvedOpen);
        return rows;
    }

    private static void addGroup(List<Row> rows, JaneSyncSession.Snapshot snapshot,
                                 ResolutionPlan.Classification group, boolean open) {
        rows.add(new Row(group, null, HEADER_HEIGHT));
        if (!open) return;
        for (JaneSyncSession.ItemState item : snapshot.items()) {
            if (item.item().classification() == group) {
                rows.add(new Row(group, item, group == ResolutionPlan.Classification.UNRESOLVED ? 52
                        : item.item().source() != null ? 38 : 29));
            }
        }
    }

    private int top() { return 35; }
    private int bottom() { return height - 33; }
    private int contentHeight(List<Row> rows) { return rows.stream().mapToInt(Row::height).sum(); }
    private void clampScroll(List<Row> rows) {
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight(rows) - (bottom() - top()))));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 14, 0xFFFFFF);
        JaneSyncSession.Snapshot snapshot = session.snapshot();
        if (snapshot.resolution() == null) {
            drawPending(graphics, snapshot);
        } else {
            List<Row> rows = rows(snapshot);
            clampScroll(rows);
            int left = Math.max(12, width / 2 - Math.min(270, (width - 28) / 2));
            int right = width - left - 10;
            graphics.enableScissor(left, top(), right + 8, bottom());
            int y = top() - scroll;
            for (Row row : rows) {
                if (y + row.height() >= top() && y < bottom()) {
                    if (row.item() == null) drawHeader(graphics, snapshot, row.group(), left, right, y, mouseX, mouseY);
                    else drawItem(graphics, row.item(), left, right, y);
                }
                y += row.height();
            }
            graphics.disableScissor();
            int total = contentHeight(rows);
            int view = bottom() - top();
            if (total > view) {
                int trackX = right + 4;
                int thumbHeight = Math.max(12, view * view / total);
                int thumbY = top() + scroll * (view - thumbHeight) / (total - view);
                graphics.fill(trackX, top(), trackX + 4, bottom(), 0xFF444444);
                graphics.fill(trackX, thumbY, trackX + 4, thumbY + thumbHeight, 0xFFAAAAAA);
            }
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawPending(GuiGraphics graphics, JaneSyncSession.Snapshot snapshot) {
        if (snapshot.error() != null) {
            graphics.drawCenteredString(font, Component.translatable("jane.sync.failed"), width / 2, 27, 0xFF7777);
        } else if (snapshot.resolutionTotal() > 0) {
            graphics.drawCenteredString(font, Component.translatable("jane.sync.resolving_progress",
                    snapshot.resolutionProcessed(), snapshot.resolutionTotal(), snapshot.resolutionPercent()),
                    width / 2, 27, 0xFFCC77);
        }
        int left = Math.max(12, width / 2 - Math.min(270, (width - 28) / 2));
        int right = width - left - 10;
        int rowHeight = 29;
        int total = session.context().results().size() * rowHeight;
        int view = bottom() - top();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - view)));
        graphics.enableScissor(left, top(), right + 8, bottom());
        int y = top() - scroll;
        for (Comparison.Result result : session.context().results()) {
            if (y + rowHeight >= top() && y < bottom()) {
                graphics.fill(left, y, right, y + rowHeight - 2, 0x88000000);
                graphics.drawString(font, fit(result.required().displayName(), right - left - 12), left + 6, y + 3, 0xFFFFFF);
                graphics.drawString(font, fit(Component.translatable("jane.status."
                        + result.status().name().toLowerCase(java.util.Locale.ROOT)).getString(),
                        right - left - 100), left + 6, y + 16, 0xCCCCCC);
                Component source = Component.translatable(result.status() == Comparison.Status.OK
                        ? "jane.details.matched" : "jane.details.source_pending");
                graphics.drawString(font, source, right - 6 - font.width(source), y + 16, 0xFFCC77);
            }
            y += rowHeight;
        }
        graphics.disableScissor();
        if (total > view) {
            int thumbHeight = Math.max(12, view * view / total);
            int thumbY = top() + scroll * (view - thumbHeight) / (total - view);
            graphics.fill(right + 4, top(), right + 8, bottom(), 0xFF444444);
            graphics.fill(right + 4, thumbY, right + 8, thumbY + thumbHeight, 0xFFAAAAAA);
        }
    }

    private void drawHeader(GuiGraphics graphics, JaneSyncSession.Snapshot snapshot, ResolutionPlan.Classification group,
                            int left, int right, int y, int mouseX, int mouseY) {
        boolean open = switch (group) {
            case MODRINTH_DOWNLOADABLE -> downloadsOpen;
            case SERVER_DOWNLOADABLE -> serverOpen;
            case ALREADY_PRESENT -> presentOpen;
            case UNRESOLVED -> unresolvedOpen;
        };
        String key = switch (group) {
            case MODRINTH_DOWNLOADABLE -> "jane.details.downloadable";
            case SERVER_DOWNLOADABLE -> "jane.details.server_downloadable";
            case ALREADY_PRESENT -> "jane.details.present";
            case UNRESOLVED -> "jane.details.unresolved";
        };
        int shade = mouseX >= left && mouseX < right && mouseY >= y && mouseY < y + HEADER_HEIGHT ? 0xFF555555 : 0xFF333333;
        graphics.fill(left, y, right, y + HEADER_HEIGHT - 2, shade);
        String label = (open ? "▼ " : "▶ ") + Component.translatable(key).getString();
        graphics.drawString(font, fit(label, right - left - 65), left + 5, y + 6, 0xFFFFFF);
        graphics.drawString(font, Long.toString(snapshot.resolution().count(group)), right - 25, y + 6, 0xFFFFFF);
    }

    private void drawItem(GuiGraphics graphics, JaneSyncSession.ItemState state, int left, int right, int y) {
        var comparison = state.item().comparison();
        var target = comparison.required();
        graphics.fill(left, y, right, y + (state.item().classification() == ResolutionPlan.Classification.UNRESOLVED ? 50
                : state.item().source() != null ? 36 : 27), 0x88000000);
        int textLeft = left + 6;
        int textRight = right - 6;
        graphics.drawString(font, fit(target.displayName(), textRight - textLeft), textLeft, y + 3, 0xFFFFFF);
        if (state.item().classification() == ResolutionPlan.Classification.ALREADY_PRESENT) {
            graphics.drawString(font, fit(target.version(), textRight - textLeft - 90), textLeft, y + 16, 0xCCCCCC);
            right(graphics, Component.translatable("jane.details.matched"), textRight, y + 16, 0xAAFFAA);
        } else if (state.item().source() != null) {
            String reason = Component.translatable("jane.status." + comparison.status().name().toLowerCase(java.util.Locale.ROOT)).getString();
            String versions = comparison.local() == null ? target.version() : comparison.local().version() + " → " + target.version();
            graphics.drawString(font, fit(reason + "  " + versions, textRight - textLeft), textLeft, y + 15, 0xCCCCCC);
            String stateText = runtime(state);
            String sourceName = state.item().classification() == ResolutionPlan.Classification.SERVER_DOWNLOADABLE
                    ? Component.translatable("jane.details.current_server").getString() : "Modrinth ✓";
            graphics.drawString(font, fit(sourceName + "  " + SyncScreen.mib(target.fileSize()) + " MiB", textRight - textLeft - 90),
                    textLeft, y + 27, 0xAAFFAA);
            right(graphics, Component.literal(stateText), textRight, y + 27, 0xFFCC77);
        } else {
            graphics.drawString(font, fit(Component.translatable("jane.details.unknown_source").getString() + "  "
                    + Component.translatable("jane.details.server_version", target.version()).getString(), textRight - textLeft),
                    textLeft, y + 16, 0xFFAA55);
            String hash = target.sha512();
            String shortHash = hash.substring(0, 8) + "..." + hash.substring(hash.length() - 8);
            graphics.drawString(font, fit(Component.translatable("jane.details.hash", shortHash).getString(), textRight - textLeft),
                    textLeft, y + 29, 0xCCCCCC);
            right(graphics, Component.literal(SyncScreen.mib(target.fileSize()) + " MiB"), textRight, y + 39, 0xCCCCCC);
        }
    }

    private String runtime(JaneSyncSession.ItemState state) {
        if (state.state() == JaneSyncSession.RuntimeState.DOWNLOADING) {
            int percent = (int) (100 * state.downloadedBytes() / state.item().comparison().required().fileSize());
            return Component.translatable("jane.runtime.downloading", percent).getString();
        }
        return Component.translatable("jane.runtime." + state.state().name().toLowerCase(java.util.Locale.ROOT)).getString();
    }

    private String fit(String value, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (font.width(value) <= maxWidth) return value;
        return font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width("…"))) + "…";
    }

    private void right(GuiGraphics graphics, Component text, int x, int y, int color) {
        graphics.drawString(font, text, x - font.width(text), y, color);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        scroll -= (int) Math.round(amount * 24);
        if (session.snapshot().resolution() == null) {
            int total = session.context().results().size() * 29;
            scroll = Math.max(0, Math.min(scroll, Math.max(0, total - (bottom() - top()))));
        } else clampScroll(rows(session.snapshot()));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0 || session.snapshot().resolution() == null || mouseY < top() || mouseY >= bottom()) return false;
        List<Row> rows = rows(session.snapshot());
        int left = Math.max(12, width / 2 - Math.min(270, (width - 28) / 2));
        int right = width - left - 10;
        if (mouseX >= right + 3 && mouseX <= right + 9 && contentHeight(rows) > bottom() - top()) {
            draggingBar = true;
            return true;
        }
        if (mouseX < left || mouseX >= right) return false;
        int y = top() - scroll;
        for (Row row : rows) {
            if (row.item() == null && mouseY >= y && mouseY < y + row.height()) {
                switch (row.group()) {
                    case MODRINTH_DOWNLOADABLE -> downloadsOpen = !downloadsOpen;
                    case SERVER_DOWNLOADABLE -> serverOpen = !serverOpen;
                    case ALREADY_PRESENT -> presentOpen = !presentOpen;
                    case UNRESOLVED -> unresolvedOpen = !unresolvedOpen;
                }
                clampScroll(rows(session.snapshot()));
                return true;
            }
            y += row.height();
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingBar && button == 0) {
            List<Row> rows = rows(session.snapshot());
            int view = bottom() - top();
            int total = contentHeight(rows);
            int thumb = Math.max(12, view * view / total);
            scroll += (int) Math.round(dragY * (total - view) / Math.max(1.0, view - thumb));
            clampScroll(rows);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingBar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void onClose() { minecraft.setScreen(parent); }
}
