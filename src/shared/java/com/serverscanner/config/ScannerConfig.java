package com.serverscanner.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.serverscanner.Log;
import com.serverscanner.filter.ScannerFilters;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Everything that survives a restart: the filters and a handful of display preferences.
 *
 * <p>Saved to {@code config/randomserverfinder.json}. Filters persist automatically because the
 * whole config is written whenever the filter screen closes.
 */
public final class ScannerConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH =
			FabricLoader.getInstance().getConfigDir().resolve("randomserverfinder.json");

	private static ScannerConfig instance;

	/** How each server is drawn in the list. */
	public enum ViewMode {
		/** One compact row per server — the default. */
		LIST,
		/** Larger tiles in a grid, showing more of the icon and description. */
		CARDS;

		public ViewMode next() {
			return this == LIST ? CARDS : LIST;
		}

		public String displayName() {
			return this == LIST ? "List" : "Cards";
		}
	}

	/** What to show as each row's headline, since a bare IP tells you very little. */
	public enum LabelMode {
		/** The server's own name from its MOTD, falling back to the host. */
		NAME,
		/** The raw address. */
		ADDRESS;

		public LabelMode next() {
			return this == NAME ? ADDRESS : NAME;
		}

		public String displayName() {
			return this == NAME ? "Server name" : "IP address";
		}
	}

	// --- Persisted fields ------------------------------------------------------------------

	public ScannerFilters filters = new ScannerFilters();

	public ViewMode viewMode = ViewMode.LIST;
	public LabelMode labelMode = LabelMode.NAME;

	/**
	 * The published scan results: six bytes per server, big-endian address then port. Downloaded
	 * once and cached next to this file. A copy also ships inside the mod, so the list still works
	 * with no network at all.
	 */
	public String ipsListUrl =
			"https://raw.githubusercontent.com/kgurchiek/Minecraft-Server-Scanner/main/ips";

	/**
	 * Further address lists to merge in, on top of the one above.
	 *
	 * <p>Empty by default. Anything here is fetched and cached alongside the main list and folded
	 * into the same pool, with duplicates dropped. Both formats are accepted: the six-byte records
	 * the scanner publishes, and plain text with one address per line.
	 *
	 * <p>Raw scanner dumps are much larger but only record that a port was open, not that anything
	 * answered as a Minecraft server, so expect a lower hit rate per ping.
	 */
	public java.util.List<String> extraIpListUrls = new java.util.ArrayList<>();

	/**
	 * Include Bedrock Edition servers, from the same publisher's second list.
	 *
	 * <p>Off by default because joining one needs ViaFabricPlus signed in to a Bedrock account:
	 * without that they are findable but not playable. Finding them costs nothing extra, since they
	 * answer a different protocol on their own port.
	 */
	public boolean includeBedrock = false;

	/** The Bedrock half of the published scan, used only when the above is on. */
	public String bedrockListUrl =
			"https://raw.githubusercontent.com/kgurchiek/Minecraft-Server-Scanner/main/ips_b";

	/**
	 * After finding a server, try a few nearby ports on that same machine.
	 *
	 * <p>People who run one server often run several, side by side on consecutive ports, and the
	 * published lists miss plenty of them. This is how those lists grow in the first place.
	 *
	 * <p>Off by default, and deliberately narrow: only hosts that have already answered, only a
	 * handful of ports each, never a sweep of addresses that never advertised anything. It is still
	 * port scanning from your own connection, which some providers take a dim view of.
	 */
	public boolean probeNeighbourPorts = false;

	/** Use a local file instead of downloading — e.g. output from your own scanner. Blank = off. */
	public String ipsListPath = "";

	/**
	 * Hide every server address on screen. For streaming or screenshots — a random server is usually
	 * someone's home machine, and showing its address on stream invites trouble for them.
	 */
	public boolean streamerMode = false;

	/** Hide servers you have already joined. */
	public boolean hideJoined = false;

	/** Remove rows once their ping fails. */
	public boolean hideOffline = false;

	/** Keep searching in the background while you are on the title screen. */
	public boolean prefetchInMenu = true;

	// --- Auto-join ------------------------------------------------------------------------

	/**
	 * Seconds to wait between auto-join attempts.
	 *
	 * <p>Every attempt is a real login, which goes through Mojang's session server as well as the
	 * server itself. Hammering either gets you rate limited, and a refusal comes back fast enough
	 * that without a deliberate pause this would fire several times a second. Five is unhurried
	 * enough to be safe and still quicker than doing it by hand.
	 */
	public int autoJoinDelaySeconds = 5;

	/** Accept a server's resource pack during auto-join instead of stopping to ask. */
	public boolean autoJoinAcceptPacks = true;

	/** Carry on trying after you leave a server, rather than switching off once you are in. */
	public boolean autoJoinKeepGoing = true;

	// --- Party, all decided by whoever is hosting -----------------------------------------

	/** Members are taken along to each server the host joins. */
	public boolean partyFollowJoin = true;

	/**
	 * Members are disconnected too when the host leaves a server.
	 *
	 * <p>Without this, quitting strands everyone else on a server they were only there for.
	 */
	public boolean partyFollowLeave = true;

	/** Turn away anyone else trying to connect, without having to change the port. */
	public boolean partyLocked = false;

	/** How many friends may be connected at once. Capped by the protocol at sixteen. */
	public int partyMaxMembers = 16;

	/** Port this machine listens on when hosting a party. */
	public int partyPort = com.serverscanner.Log.DEFAULT_PARTY_PORT;

	/** Last friend's address typed into the party screen, so it is there next time. */
	public String lastPartyAddress = "";

	// --- Loading / saving ------------------------------------------------------------------

	public static ScannerConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static ScannerConfig load() {
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
				ScannerConfig loaded = GSON.fromJson(reader, ScannerConfig.class);
				if (loaded != null) {
					// Guard against fields missing from an older config file.
					if (loaded.filters == null) loaded.filters = new ScannerFilters();
					if (loaded.viewMode == null) loaded.viewMode = ViewMode.LIST;
					if (loaded.labelMode == null) loaded.labelMode = LabelMode.NAME;
					if (loaded.ipsListUrl == null || loaded.ipsListUrl.isBlank()) {
						loaded.ipsListUrl = new ScannerConfig().ipsListUrl;
					}
					if (loaded.ipsListPath == null) loaded.ipsListPath = "";
					if (loaded.bedrockListUrl == null || loaded.bedrockListUrl.isBlank()) {
						loaded.bedrockListUrl = new ScannerConfig().bedrockListUrl;
					}
					if (loaded.extraIpListUrls == null) {
						loaded.extraIpListUrls = new java.util.ArrayList<>();
					}
					if (loaded.lastPartyAddress == null) loaded.lastPartyAddress = "";
					if (loaded.partyPort <= 0 || loaded.partyPort > 65535) {
						loaded.partyPort = com.serverscanner.Log.DEFAULT_PARTY_PORT;
					}
					return loaded;
				}
			} catch (Exception e) {
				Log.LOGGER.warn("Could not read {}, falling back to defaults", PATH, e);
			}
		}
		return new ScannerConfig();
	}

	public void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			Log.LOGGER.error("Could not save {}", PATH, e);
		}
	}
}
