package com.serverscanner.party;

import com.serverscanner.ServerScannerMod;
import com.serverscanner.screen.ServerScannerScreen;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;

/**
 * Moves this client onto whatever server the party is heading for, and brings it back to the finder
 * when the group disbands.
 */
public final class PartyTravel {
	/** True while we were in a world that the party sent us to. */
	private static boolean wasInWorld;

	private PartyTravel() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			PartyManager.tick(minecraft);
			trackDisconnect(minecraft);
		});
	}

	/** Connects to a server the host announced. */
	public static void connect(MinecraftClient minecraft, String address) {
		try {
			ServerInfo info = new ServerInfo(address, address, ServerInfo.ServerType.OTHER);
			ConnectScreen.connect(currentOrTitle(minecraft), minecraft,
					ServerAddress.parse(address), info, false, null);
		} catch (Exception e) {
			ServerScannerMod.LOGGER.warn("Could not follow the party to {}", address, e);
		}
	}

	/**
	 * Returns to the finder once a party trip ends.
	 *
	 * <p>Leaving a server you were sent to normally dumps you on the multiplayer menu, which is not
	 * where the group is — the finder is, and it has kept searching in the meantime, so you can pick
	 * the next one straight away.
	 */
	private static void trackDisconnect(MinecraftClient minecraft) {
		boolean inWorld = minecraft.world != null;

		if (inWorld) {
			wasInWorld = true;
			return;
		}

		// The host has just left a server. Members are told so they are not stranded there; whether
		// that is a quit or the start of a trip elsewhere is decided inside announceLeave.
		if (wasInWorld) {
			PartyManager.announceLeave();
		}

		if (wasInWorld && PartyManager.hasTravelled()) {
			wasInWorld = false;
			PartyManager.clearTravelled();

			// Only take over an idle menu; never interrupt a dialog the user is reading.
			if (minecraft.currentScreen instanceof TitleScreen
					|| minecraft.currentScreen instanceof net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen) {
				minecraft.setScreen(new ServerScannerScreen(minecraft.currentScreen));
			}
		}
		wasInWorld = false;
	}

	private static net.minecraft.client.gui.screen.Screen currentOrTitle(MinecraftClient minecraft) {
		return minecraft.currentScreen != null ? minecraft.currentScreen : new TitleScreen();
	}
}
