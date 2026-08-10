package com.serverscanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Identity and logging, kept free of any Minecraft reference.
 *
 * <p>The mod is built against Minecraft versions whose APIs share almost no names — 1.21.x uses Yarn
 * mappings, 26.x is unobfuscated and uses Mojang's own. Everything that does not need Minecraft
 * lives in the shared source set and compiles once for all of them; this class exists so that shared
 * code can log without reaching into the version-specific entry point.
 */
public final class Log {
	public static final String MOD_ID = "randomserverfinder";

	public static final Logger LOGGER = LoggerFactory.getLogger("Random Server Finder");

	/** Sent when refreshing the address list, so the file's host can see where requests come from. */
	public static final String USER_AGENT = "RandomServerFinder/1.0 (Minecraft Fabric client mod)";

	/** Port used when hosting a party. Here rather than in the party code, which needs Minecraft. */
	public static final int DEFAULT_PARTY_PORT = 25577;

	private Log() {
	}
}
