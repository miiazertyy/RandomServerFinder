package com.serverscanner;

import com.serverscanner.config.ScannerConfig;
import com.serverscanner.local.ScannerSession;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerScannerMod implements ClientModInitializer {
	public static final String MOD_ID = "randomserverfinder";
	public static final Logger LOGGER = LoggerFactory.getLogger("Random Server Finder");

	/** Sent when refreshing the address list, so the file's host can see where requests come from. */
	public static final String USER_AGENT = "RandomServerFinder/1.0 (Minecraft Fabric client mod)";

	@Override
	public void onInitializeClient() {
		ScannerConfig.get();
		ScannerSession.register();
		com.serverscanner.party.PartyTravel.register();
		AutoJoin.register();
		ScannerScreenHooks.register();
		LOGGER.info("Random Server Finder ready");
	}
}
