package com.serverscanner.config;

/**
 * Formats server addresses for display.
 *
 * <p>With streamer mode on, addresses are hidden everywhere they would be drawn. Random servers are
 * usually somebody's home machine or small host, and putting one on stream tends to get it griefed
 * or worse. Joining, saving and Direct Connect all still use the real address — only what appears on
 * screen is masked.
 */
public final class Addresses {
	private static final String MASK = "•••.•••.•••.•••";

	private Addresses() {
	}

	/** The address as it should be shown, masked when streamer mode is on. */
	public static String display(String address) {
		if (!ScannerConfig.get().streamerMode || address == null) {
			return address;
		}
		// Keep the port: it says something about the server and gives away nothing on its own.
		int colon = address.lastIndexOf(':');
		return colon > 0 ? MASK + address.substring(colon) : MASK;
	}

	/** True when addresses must not be drawn. */
	public static boolean hidden() {
		return ScannerConfig.get().streamerMode;
	}
}
