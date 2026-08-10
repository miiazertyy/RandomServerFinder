package com.serverscanner;

import com.serverscanner.api.ScannedServer;
import com.serverscanner.config.JoinedServers;
import com.serverscanner.config.ScannerConfig;
import com.serverscanner.local.LocalServerFeed;
import com.serverscanner.local.ScannerSession;
import com.serverscanner.local.ViaFabricPlusBridge;
import com.serverscanner.party.PartyManager;
import com.serverscanner.screen.ServerScannerScreen;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Keeps trying random servers until one lets you in, and picks up again when you leave.
 *
 * <p>Most addresses that answer a ping still will not let you play: whitelists, bans, mod
 * requirements and full servers all only show up once you actually try to connect. Doing that by
 * hand means going back to the list after every rejection, so this does the going back.
 *
 * <p>It stays armed after a successful join rather than switching itself off. Leaving a server is
 * the signal to carry on, which is what makes it useful for hopping: quit, and the next one is
 * already loading. Only the stop button ends it.
 */
public final class AutoJoin {
	/** Bounds on the configured wait, so a hand-edited config cannot turn this into a spin loop. */
	private static final int MIN_DELAY_SECONDS = 1;
	private static final int MAX_DELAY_SECONDS = 300;

	private static final Random RANDOM = new Random();

	/** Addresses tried since the last successful join, so it works through the list. */
	private static final Set<String> tried = new HashSet<>();

	private static boolean active;

	/**
	 * True while there is nothing left to try and we are waiting on the search.
	 *
	 * <p>Only kept so the on-screen line can say so, rather than showing a countdown that expires
	 * and then visibly does nothing.
	 */
	private static boolean waitingForCandidates;

	private static long lastAttemptAt;
	private static int attempts;
	private static String target;

	/**
	 * The server being tried, held until we know whether it let us in.
	 *
	 * <p>It is only written to the join history once we are actually in the world. Recording it at
	 * the point of the attempt marked servers as joined that had refused us, which put them behind
	 * the "hide joined" filter: every server it tried became ineligible for the next pick, so once
	 * it had worked through what the search had found there was nothing left to try and it stopped
	 * without saying so.
	 */
	private static ScannedServer pending;

	private AutoJoin() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(AutoJoin::tick);
	}

	public static boolean isActive() {
		return active;
	}

	/** How many servers have been tried on this run, for the label on the stop button. */
	public static int attempts() {
		return attempts;
	}

	/** The address currently being tried, or null before the first attempt. */
	public static String target() {
		return target;
	}

	/**
	 * What auto-join is doing, or null when it is not running.
	 *
	 * <p>Reported in pieces rather than as a finished sentence, so the screen can lay it out as
	 * something that belongs there. It is shown over whatever is up, and most of the time that is a
	 * refusal like "You are not white-listed on this server" sitting perfectly still: without it,
	 * the wait between attempts is indistinguishable from the mod having quietly given up.
	 */
	public static String phase() {
		if (!active) return null;
		if (waitingForCandidates) return "Searching for servers";
		return remainingMillis() > 0 ? "Next server" : "Connecting";
	}

	/** Whole seconds until the next attempt, or -1 when it is not waiting on the clock. */
	public static int secondsLeft() {
		long remaining = remainingMillis();
		// Rounded up, so it reads 5, 4, 3, 2, 1 rather than resting on 0.
		return remaining > 0 ? (int) ((remaining + 999L) / 1000L) : -1;
	}

	/** How much of the wait is left, 1 just after an attempt and 0 when the next one is due. */
	public static float waitRemaining() {
		long delay = delayMillis();
		long remaining = remainingMillis();
		if (delay <= 0L || remaining <= 0L) return 0.0f;
		return Math.min(1.0f, remaining / (float) delay);
	}

	private static long remainingMillis() {
		if (!active || waitingForCandidates) return 0L;
		return delayMillis() - (System.currentTimeMillis() - lastAttemptAt);
	}

	public static void start() {
		active = true;
		pending = null;
		waitingForCandidates = false;
		attempts = 0;
		lastAttemptAt = 0L;
		target = null;
		tried.clear();
	}

	public static void stop() {
		active = false;
		pending = null;
		waitingForCandidates = false;
		tried.clear();
	}

	/**
	 * Stops, drops the connection being made, and puts the finder back on screen.
	 *
	 * <p>What the stop button does from wherever you happen to be: the connecting screen, or a
	 * refusal like "You are not white-listed on this server".
	 */
	public static void stopAndReturn(Minecraft client, Screen from) {
		cancelConnecting(from);
		stop();
		client.setScreenAndShow(new ServerScannerScreen(new JoinMultiplayerScreen(new TitleScreen())));
	}

	/**
	 * Aborts a connection that is still being made.
	 *
	 * <p>Walking away from the connecting screen does not stop it. The handshake carries on in its
	 * own thread and drops you into the world whatever is on screen, so stopping has to do what that
	 * screen's own Cancel button does: raise its cancelled flag and hang up the socket.
	 *
	 * <p>Both fields are found by type rather than by name. The mod ships remapped, so the names
	 * they have in this source do not exist at runtime; their types are remapped along with them.
	 */
	private static void cancelConnecting(Screen from) {
		if (!(from instanceof ConnectScreen)) return;

		for (Field field : ConnectScreen.class.getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers())) continue;
			try {
				if (field.getType() == boolean.class) {
					field.setAccessible(true);
					field.setBoolean(from, true);
				} else if (Connection.class.isAssignableFrom(field.getType())) {
					field.setAccessible(true);
					Connection connection = (Connection) field.get(from);
					if (connection != null) connection.disconnect(ConnectScreen.ABORT_CONNECTION);
				}
			} catch (Exception e) {
				Log.LOGGER.warn("Could not cancel the connection in progress: {}", e.toString());
			}
		}
	}

	/**
	 * Whether starting makes sense right now.
	 *
	 * <p>A party member follows whoever is hosting, so letting this loose as well would mean two
	 * things steering the same client to different servers.
	 */
	public static boolean canStart() {
		return PartyManager.getMode() != PartyManager.Mode.JOINED;
	}

	private static void tick(Minecraft client) {
		if (!active || client == null) return;

		if (!canStart()) {
			// Joined a party while running; the host takes over from here.
			stop();
			return;
		}

		// In a world, so this attempt worked. Now, and only now, is it worth remembering.
		if (client.level != null) {
			if (pending != null) {
				JoinedServers.get().record(pending.address(), nameOf(pending), pending.versionName());
				pending = null;
			}
			if (!ScannerConfig.get().autoJoinKeepGoing) {
				stop();
				return;
			}
			// Stay armed and let leaving start the next one.
			tried.clear();
			attempts = 0;
			return;
		}

		// Already on the way somewhere. The connect screen ends by itself, one way or the other.
		if (com.serverscanner.compat.CurrentScreen.get() instanceof ConnectScreen) return;

		// Back at a menu with an attempt still held means that attempt was refused. Left set, it
		// would be written to the join history the next time a world was entered by any route at
		// all, marking a server you were turned away from as one you had played on.
		pending = null;

		long now = System.currentTimeMillis();
		if (now - lastAttemptAt < delayMillis()) return;

		ScannedServer next = pick();
		waitingForCandidates = next == null;
		if (next == null) return;

		lastAttemptAt = now;
		attempts++;

		connect(client, next);
	}

	/**
	 * The configured wait between attempts, in milliseconds.
	 *
	 * <p>Each attempt is a real login, so it goes through Mojang's session server as well as the
	 * server being tried. A refusal comes back quickly, so without a deliberate pause this would
	 * fire several times a second and get you rate limited by one or the other.
	 */
	private static long delayMillis() {
		int seconds = ScannerConfig.get().autoJoinDelaySeconds;
		return Math.max(MIN_DELAY_SECONDS, Math.min(MAX_DELAY_SECONDS, seconds)) * 1000L;
	}

	/** A server that has answered a ping and has not been tried yet on this run. */
	private static ScannedServer pick() {
		LocalServerFeed feed = ScannerSession.feed();
		List<ScannedServer> found = feed.getServers();

		// The search only looks as far ahead as the list on screen asks it to, and nothing is on
		// screen here. Without this it stops at whatever it had already found, and once those are
		// all tried there is nothing left to pick and the run stalls in silence.
		feed.maybeLoadMore(found.size());

		if (found.isEmpty()) return null;

		ScannerConfig config = ScannerConfig.get();
		List<ScannedServer> candidates = new ArrayList<>();
		for (ScannedServer server : found) {
			if (tried.contains(server.key())) continue;
			if (config.hideJoined && JoinedServers.get().hasJoined(server.address())) continue;
			candidates.add(server);
		}

		if (candidates.isEmpty()) {
			// Everything found so far has been refused. Forget the run and let the search, which is
			// still going in the background, offer them again alongside whatever it turns up next.
			tried.clear();
			return null;
		}
		return candidates.get(RANDOM.nextInt(candidates.size()));
	}

	private static void connect(Minecraft client, ScannedServer server) {
		String address = server.address();
		tried.add(server.key());
		target = address;

		ServerData info = new ServerData(address, address, ServerData.Type.OTHER);
		if (server.bedrock) {
			ViaFabricPlusBridge.forceBedrock(info);
		} else {
			ViaFabricPlusBridge.forceVersion(info, server.protocol());
		}

		// Say yes to the resource pack up front. The prompt is a screen that waits for a click, and
		// nobody is watching: unanswered, it would sit there and the run would stall on one server.
		if (ScannerConfig.get().autoJoinAcceptPacks) {
			info.setResourcePackStatus(ServerData.ServerPackStatus.ENABLED);
		}

		// Written to the join history only once we are in, which happens back in tick().
		pending = server;

		// Anyone in the party comes along, exactly as they would for a join made by hand.
		PartyManager.announceTravel(address);

		Screen parent = com.serverscanner.compat.CurrentScreen.get();
		if (parent == null) parent = new JoinMultiplayerScreen(new TitleScreen());
		ConnectScreen.startConnecting(parent, client, ServerAddress.parseString(address), info, false, null);
	}

	/** The server's first line of MOTD, or its address when that is missing or unwieldy. */
	private static String nameOf(ScannedServer server) {
		String description = server.descriptionOrEmpty();
		if (description.isBlank()) return server.address();

		int newline = description.indexOf('\n');
		String line = newline < 0 ? description : description.substring(0, newline);
		return line.isBlank() || line.length() > 48 ? server.address() : line;
	}
}
