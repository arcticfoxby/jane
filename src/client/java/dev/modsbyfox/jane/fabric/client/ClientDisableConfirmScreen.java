package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class ClientDisableConfirmScreen extends Screen {
    private final CompatibilityReviewScreen parent;
    private final List<ClientCompatibilityReport.ExtraMod> chosen;

    ClientDisableConfirmScreen(CompatibilityReviewScreen parent,
                               List<ClientCompatibilityReport.ExtraMod> chosen) {
        super(Component.translatable("jane.compat.confirm_title"));
        this.parent = parent;
        this.chosen = List.copyOf(chosen);
        if (chosen.stream().anyMatch(mod -> mod.kind() == ClientCompatibilityReport.Kind.OTHER_EXTRA))
            throw new IllegalArgumentException("Universal Mod requires separate risk confirmation");
    }

    @Override protected void init() {
        int w = Math.min(230, width - 32);
        addRenderableWidget(Button.builder(Component.translatable("jane.compat.confirm_disable"), button -> {
            minecraft.setScreen(parent);
            parent.disableSelected(chosen);
        }).bounds((width - w) / 2, height - 52, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds((width - w) / 2, height - 28, w, 20).build());
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float tick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 52, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("jane.compat.confirm_count", chosen.size()),
                width / 2, height / 2 - 34, 0xFFCC77);
        int y = height / 2 - 20;
        for (var line : font.split(Component.translatable("jane.compat.confirm_explain"), Math.max(100, width - 28))) {
            graphics.drawCenteredString(font, line, width / 2, y, 0xCCCCCC);
            y += 11;
        }
        y += 4;
        for (var line : font.split(Component.translatable("jane.compat.dependency_warning"), Math.max(100, width - 28))) {
            graphics.drawCenteredString(font, line, width / 2, y, 0xFFCC77);
            y += 11;
        }
        super.render(graphics, mouseX, mouseY, tick);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}
