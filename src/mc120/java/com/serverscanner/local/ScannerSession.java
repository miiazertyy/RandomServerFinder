package com.serverscanner.local;

import com.serverscanner.config.ScannerConfig;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;

/**
 * Owns the search so it outlives the screen.
 *
 * <p>Finding servers is slow — every result costs a round trip to a real machine — so throwing the
 * results away when the screen closes would mean starting from nothing each time. The feed lives
 * here instead: closing the screen and reopening it shows what was already found.
 *
 * <p>It also runs while you sit on the title screen, so the list is populated before you ever press
 * the button. Searching stops once you are in a world: the results stay in memory, but the mod does
 * not use your connection while you are playing.
 */
public final class ScannerSession {
	private static LocalServerFeed feed;

	/** Filters the live feed was built for; a change here means the results no longer apply. */
	private static String builtForFilters;

	/** True while the scanner screen is open, which allows searching regardless of where we are. */
	private static boolean screenOpen;

	private ScannerSession() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> tick(client));
	}

	/** The current feed, started if necessary. */
	public static LocalServerFeed feed() {
		ScannerConfig config = ScannerConfig.get();
		String signature = config.filters.signature();

		if (feed == null || !signature.equals(builtForFilters)) {
			if (feed != null) feed.cancel();
			feed = new LocalServerFeed(config.filters, config);
			builtForFilters = signature;
			feed.start();
		}
		return feed;
	}

	/** Throws away the results and starts over, for the Refresh button. */
	public static void restart() {
		if (feed != null) feed.cancel();
		feed = null;
		builtForFilters = null;
		feed();
	}

	public static void setScreenOpen(boolean open) {
		screenOpen = open;
	}

	public static boolean hasResults() {
		return feed != null && feed.size() > 0;
	}

	public static int resultCount() {
		return feed == null ? 0 : feed.size();
	}

	private static void tick(MinecraftClient client) {
		if (!shouldSearch(client)) {
			return;
		}
		// Creating the feed lazily here is what makes the title screen warm it up.
		feed().tick();
	}

	/**
	 * Searching is allowed on the menus and while the finder is open, but never during play — a
	 * background ping storm has no business competing with the game's own connection.
	 */
	private static boolean shouldSearch(MinecraftClient client) {
		if (client == null) return false;
		if (client.world != null) return false;

		if (screenOpen) return true;

		// Auto-join eats a server per attempt and runs from the disconnect screen, where neither of
		// the conditions below holds. Without this the pool is whatever was found before it started,
		// and once that is used up it has nothing left to try.
		if (com.serverscanner.AutoJoin.isActive()) return true;

		if (!ScannerConfig.get().prefetchInMenu) return false;
		return client.currentScreen instanceof TitleScreen;
	}
}
