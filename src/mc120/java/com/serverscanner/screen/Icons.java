package com.serverscanner.screen;

import com.serverscanner.filter.FilterIcons;

import net.minecraft.client.gui.DrawContext;

/**
 * Draws the filter pictograms, one filled square per set pixel.
 *
 * <p>Rectangles are the only drawing call that behaves identically on every Minecraft version the
 * mod targets, so the icons are built from them rather than shipped as a texture.
 */
final class Icons {
	/** A struck icon steps back so the slash across it is the first thing read. */
	private static final int MUTED = 0xFF6E7278;

	private static final int SLASH = 0xFFE0655A;
	private static final int SLASH_SHADOW = 0xC0000000;

	private Icons() {
	}

	static void draw(DrawContext context, String[] icon, int x, int y) {
		draw(context, icon, x, y, false);
	}

	static void draw(DrawContext context, String[] icon, int x, int y, boolean struck) {
		for (int row = 0; row < icon.length; row++) {
			String line = icon[row];
			for (int column = 0; column < line.length(); column++) {
				int colour = switch (line.charAt(column)) {
					case '#' -> struck ? MUTED : FilterIcons.DARK;
					case 'o' -> struck ? MUTED : FilterIcons.ACCENT;
					default -> 0;
				};
				if (colour != 0) {
					context.fill(x + column, y + row, x + column + 1, y + row + 1, colour);
				}
			}
		}

		if (!struck) return;

		// A no-entry slash: the icon says which field, this says the filter wants it absent. Laid on
		// a dark underline so it stays visible where it crosses the lit parts of the pictogram.
		for (int step = 0; step < FilterIcons.SIZE; step++) {
			int px = x + step;
			int py = y + FilterIcons.SIZE - 1 - step;
			context.fill(px, py + 1, px + 1, py + 2, SLASH_SHADOW);
		}
		for (int step = 0; step < FilterIcons.SIZE; step++) {
			int px = x + step;
			int py = y + FilterIcons.SIZE - 1 - step;
			context.fill(px, py, px + 1, py + 1, SLASH);
		}
	}
}
