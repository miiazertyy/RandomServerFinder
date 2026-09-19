package com.serverscanner.party;

/**
 * The little gold crown drawn beside whoever is leading the party.
 *
 * <p>A character grid rather than a texture, for the same reason as the filter pictograms: filled
 * rectangles are the one drawing call that is identical on every Minecraft version the mod targets.
 * Seven rows tall so it sits on a line of text without pushing the rows apart.
 */
public final class CrownIcon {
	public static final String[] PIXELS = {
			"y...y...y",
			"Y..yYy..Y",
			"YY.YYY.YY",
			"YYYYYYYYY",
			"YrYYbYYrY",
			"YYYYYYYYY",
			"ddddddddd",
	};

	public static final int WIDTH = 9;
	public static final int HEIGHT = 7;

	private CrownIcon() {
	}

	/** The colour of one grid character, or 0 for a transparent pixel. */
	public static int colour(char c) {
		return switch (c) {
			case 'Y' -> 0xFFF2C037;
			case 'y' -> 0xFFFFE68A;
			case 'd' -> 0xFFB07F1A;
			case 'r' -> 0xFFE0655A;
			case 'b' -> 0xFF6FB6FF;
			default -> 0;
		};
	}
}
