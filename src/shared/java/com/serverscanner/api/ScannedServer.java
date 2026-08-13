package com.serverscanner.api;

import com.google.gson.annotations.SerializedName;

/**
 * One server as returned by {@code /v2/servers}.
 *
 * <p>Field names match the JSON so Gson can bind directly. Note that the API stores addresses as
 * signed 32-bit values offset back into unsigned range, and returns the timestamps as strings.
 */
public class ScannedServer {
	public long ip;
	public int port;
	public long discovered;
	public long lastSeen;
	public Version version;
	public String description;
	public String rawDescription;
	public Players players;
	public boolean hasFavicon;

	/**
	 * Bedrock Edition, which answers a different protocol and is joined a different way.
	 *
	 * <p>Not something an address can tell you, so it is carried from the list that supplied it.
	 */
	public boolean bedrock;
	public boolean hasForgeData;
	public Boolean enforcesSecureChat;
	public String org;
	public Geo geo;
	public Boolean cracked;
	public Boolean whitelisted;

	/**
	 * In local mode the server has already been pinged before it reaches the list, so the resolved
	 * status travels with it and the list must not ping it a second time. Untyped because this class is shared\r
	 * across Minecraft versions whose ping classes have different names; the version-specific code\r
	 * casts it back. Never serialized — it exists only in memory.
	 */
	public transient Object resolved;

	/**
	 * How that ping ended, where the version cannot record it on the ping result itself. 1.20.1's
	 * server info has no status field, so its source tree writes its own value here and reads it
	 * back when the row is built. Untyped and unused on every other version.
	 */
	public transient Object pingState;

	public static class Version {
		public String name;
		public int protocol;
	}

	public static class Players {
		public int max;
		public int online;
		@SerializedName("hasPlayerSample")
		public boolean hasPlayerSample;
	}

	public static class Geo {
		public String country;
		public String city;
		public Double lat;
		public Double lon;
	}

	/** Dotted-quad form of {@link #ip}. */
	public String ipString() {
		return String.format("%d.%d.%d.%d",
				(ip >> 24) & 0xFF,
				(ip >> 16) & 0xFF,
				(ip >> 8) & 0xFF,
				ip & 0xFF);
	}

	/** The address in the form Minecraft's server list expects, omitting the default port. */
	public String address() {
		return port == 25565 ? ipString() : ipString() + ":" + port;
	}

	/** Stable identity used to de-duplicate results across random pages. */
	public String key() {
		return ip + ":" + port;
	}

	public String versionName() {
		return version == null || version.name == null ? "?" : version.name;
	}

	public int protocol() {
		return version == null ? 0 : version.protocol;
	}

	public int onlinePlayers() {
		return players == null ? 0 : players.online;
	}

	public int maxPlayers() {
		return players == null ? 0 : players.max;
	}

	public String descriptionOrEmpty() {
		return description == null ? "" : description;
	}
}
