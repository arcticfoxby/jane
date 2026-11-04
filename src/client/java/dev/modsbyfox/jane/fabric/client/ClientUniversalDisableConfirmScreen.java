package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Separate, explicit confirmation for extra JARs that may be needed by other Mods. */
final class ClientUniversalDisableConfirmScreen extends Screen {
    private record Line(FormattedCharSequence text, int color) { }

    private final CompatibilityReviewScreen parent;
    private final List<ClientCompatibilityReport.ExtraMod> chosen;
    private final List<ClientCompatibilityReport.ExtraMod> universal;
    private int scroll;
    private Button confirmButton;

    ClientUniversalDisableConfirmScreen(CompatibilityReviewScreen parent,
                                        List<ClientCompatibilityReport.ExtraMod> chosen) {
        super(Component.translatable("jane.compat.universal_confirm_title"));
        this.parent = parent;
        this.chosen = List.copyOf(chosen);
        this.universal = chosen.stream().filter(mod ->
                mod.kind() == ClientCompatibilityReport.Kind.OTHER_EXTRA).toList();
        if (universal.isEmpty()) throw new IllegalArgumentException("No universal Mod selected");
    }

    private ClientUniversalDisableLayout layout() { return ClientUniversalDisableLayout.forSize(width, height); }

    @Override protected void init() {
        ClientUniversalDisableLayout layout = layout();
        int x = (width - layout.buttonWidth()) / 2;
        String confirmKey = width < 220 ? "jane.compat.universal_confirm_tiny"
                : width < 300 ? "jane.compat.universal_confirm_short" : "jane.compat.universal_confirm";
        confirmButton = addRenderableWidget(Button.builder(Component.translatable(confirmKey), button -> {
            if (!parent.confirmUniversalDisable(chosen)) {
                minecraft.setScreen(parent);
                return;
            }
            minecraft.setScreen(parent);
            parent.disableSelected(chosen);
        }).bounds(x, layout.confirmY(), layout.buttonWidth(), 20).build());
        confirmButton.active = false;
        addRenderableWidget(Button.builder(Component.translatable("jane.compat.universal_cancel"), button -> onClose())
                .bounds(x, layout.cancelY(), layout.buttonWidth(), 20).build());
    }

    private List<Line> lines() {
        List<Line> result = new ArrayList<>();
        addWrapped(result, Component.translatable("jane.compat.universal_scroll_hint"), 0xAAAAAA);
        result.add(new Line(null, 0));
        addWrapped(result, Component.translatable("jane.compat.universal_explain1"), 0xFFCC77);
        result.add(new Line(null, 0));
        addWrapped(result, Component.translatable("jane.compat.universal_explain2"), 0xCCCCCC);
        result.add(new Line(null, 0));
        for (var mod : universal) {
            addWrapped(result, Component.literal(mod.displayName()), 0xFFFFFF);
            addWrapped(result, Component.literal(mod.filename()), 0xAAAAAA);
            result.add(new Line(null, 0));
        }
        return result;
    }

    private void addWrapped(List<Line> result, Component text, int color) {
        for (FormattedCharSequence line : font.split(text, Math.max(60, width - 40)))
            result.add(new Line(line, color));
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float tick) {
        renderBackground(graphics);
        ClientUniversalDisableLayout layout = layout();
        graphics.drawCenteredString(font, Component.translatable(width < 300
                ? "jane.compat.universal_confirm_title_short" : "jane.compat.universal_confirm_title"),
                width / 2, 8, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("jane.compat.universal_counts",
                universal.size(), chosen.size()), width / 2, width < 300 ? 20 : 26, 0xFFCC77);
        List<Line> rows = lines();
        int view = Math.max(1, layout.viewHeight());
        int maxScroll = Math.max(0, rows.size() * 11 - view);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        confirmButton.active = layout.readable() && scroll >= maxScroll;
        if (layout.viewHeight() > 0) {
            graphics.enableScissor(12, layout.contentTop(), width - 12, layout.contentBottom());
            int y = layout.contentTop() - scroll;
            for (Line line : rows) {
                if (line.text() != null && y + 11 > layout.contentTop() && y < layout.contentBottom())
                    graphics.drawString(font, line.text(), 16, y, line.color());
                y += 11;
            }
            graphics.disableScissor();
            if (rows.size() * 11 > view) {
                int thumb = Math.max(8, view * view / (rows.size() * 11));
                int thumbY = layout.contentTop() + scroll * (view - thumb) / (rows.size() * 11 - view);
                graphics.fill(width - 19, layout.contentTop(), width - 15, layout.contentBottom(), 0xFF444444);
                graphics.fill(width - 19, thumbY, width - 15, thumbY + thumb, 0xFFAAAAAA);
            }
        }
        if (!layout.readable())
            graphics.drawCenteredString(font, Component.translatable(width < 170
                            ? "jane.compat.enlarge_window_short" : "jane.compat.enlarge_window"),
                    width / 2, Math.max(26, layout.contentTop()), 0xFF7777);
        super.render(graphics, mouseX, mouseY, tick);
    }

    @Override public boolean mouseScrolled(double x, double y, double amount) {
        ClientUniversalDisableLayout layout = layout();
        if (y < layout.contentTop() || y >= layout.contentBottom()) return super.mouseScrolled(x, y, amount);
        scroll -= (int) Math.round(amount * 22);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, lines().size() * 11 - layout.viewHeight())));
        return true;
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}
