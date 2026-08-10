package com.serverscanner.compat;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Small drawing helpers that 26.x does not provide directly.
 *
 * <p>The 1.21.x draw context had a centred-text call; the extractor only draws from a left edge, so
 * the centring is done here rather than repeated at every call site.
 */
public final class Draw {
	private Draw() {
	}

	public static void centered(GuiGraphicsExtractor context, Font font, Component text,
			int centreX, int y, int colour) {
		context.text(font, text, centreX - font.width(text) / 2, y, colour);
	}

	public static void centered(GuiGraphicsExtractor context, Font font, String text,
			int centreX, int y, int colour) {
		context.text(font, text, centreX - font.width(text) / 2, y, colour);
	}
}
