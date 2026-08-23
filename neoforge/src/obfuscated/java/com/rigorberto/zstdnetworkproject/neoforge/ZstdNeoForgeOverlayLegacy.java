package com.rigorberto.zstdnetworkproject.neoforge;

import com.rigorberto.zstdnetworkproject.ZstdOverlayStats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * F3+3 zstd overlay for pre-26.x eras, where the HUD draws through {@link GuiGraphics} and the
 * debug overlay hangs off {@link Minecraft#gui}. Compiled only against obfuscated-era mappings.
 */
final class ZstdNeoForgeOverlayLegacy {

    /**
     * Draws zstd statistics right above the vanilla F3+3 bandwidth chart (bottom-left, same spot
     * where the chart stacks its min/avg/max labels). Mirrors the vanilla visibility rules: only
     * while the network charts are shown and only for remote servers.
     */
    @SubscribeEvent
    public void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.getConnection() == null || client.isLocalServer()) {
            return;
        }
        if (client.gui.getDebugOverlay() == null || !client.gui.getDebugOverlay().showNetworkCharts()) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        Font font = client.font;
        int y = graphics.guiHeight() - 87;
        for (String line : ZstdOverlayStats.overlayLines()) {
            int width = font.width(line);
            graphics.fill(1, y - 1, width + 3, y + 8, 0x90202020);
            graphics.drawString(font, line, 2, y, 0xFFE0E0E0, false);
            y -= 9;
        }
    }
}
