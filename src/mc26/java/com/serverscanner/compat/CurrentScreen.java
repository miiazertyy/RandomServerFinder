package com.serverscanner.compat;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.Screen;

/**
 * Tracks which screen is open.
 *
 * <p>26.x removed the public handle on the current screen from {@code Minecraft} — it can be set but
 * not read — so the mod keeps its own note of it, updated whenever any screen initialises.
 */
public final class CurrentScreen {
	private static Screen current;

	private CurrentScreen() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> current = screen);
	}

	/** The screen last opened, or null before any has been. */
	public static Screen get() {
		return current;
	}

	public static boolean is(Class<? extends Screen> type) {
		return current != null && type.isInstance(current);
	}
}
