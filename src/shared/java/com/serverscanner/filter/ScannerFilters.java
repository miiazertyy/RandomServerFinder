package com.serverscanner.filter;

/**
 * The full set of filters, mirroring the ones offered on
 * <a href="https://www.cornbread2100.com/server-scanner">the website</a>, plus a few extras that
 * the v2 API supports but the website does not expose.
 *
 * <p>Instances are serialized to disk verbatim by {@link com.serverscanner.config.ScannerConfig},
 * so field names are part of the on-disk format.
 */
public class ScannerFilters {
	// --- Filters that exist on the website ------------------------------------------------

	/** Range syntax, e.g. {@code 3}, {@code >1}, {@code <=5}, {@code 1-10}. Blank = unused. */
	public String playerCount = "";

	/** Exact player capacity. Blank = unused. */
	public String playerCap = "";

	/** Whether the server is at its player cap. */
	public TriState full = TriState.UNSET;

	/** SQL LIKE pattern against the version string, e.g. {@code %1.21.11%}. */
	public String version = "";

	/** Exact protocol number. */
	public String protocol = "";

	public TriState hasPlayerSample = TriState.UNSET;

	/** Player who was online at the server's last ping. */
	public String onlinePlayer = "";

	/** Player ever seen on the server. */
	public String pastPlayer = "";

	public TriState hasFavicon = TriState.UNSET;

	/** Free text; double-quoted sections become exact LIKE matches. */
	public String description = "";

	/** Epoch seconds; 0 = unused. */
	public long seenAfter = 0L;

	/** Comma-separated IPs/CIDRs, {@code !} prefix excludes, e.g. {@code 1.0.0.0/8, !2.3.4.0/24}. */
	public String ipSubnet = "";

	public String port = "";

	public TriState cracked = TriState.UNSET;

	public TriState whitelisted = TriState.UNSET;

	// --- Extra filters the v2 API supports ------------------------------------------------

	/** Unmodded server software with a plain vanilla version string. */
	public TriState vanilla = TriState.UNSET;

	/** Server reports Forge mod data. */
	public TriState forge = TriState.UNSET;

	/** Two-letter country code, e.g. {@code US}. */
	public String country = "";

	/** SQL LIKE pattern against the hosting organisation. */
	public String org = "";

	/**
	 * Only return servers seen within this many hours, as a rolling window. 0 = unused.
	 *
	 * <p>Defaults to a day rather than 0 because the database keeps every server it has ever seen:
	 * of ~30 million records only a few hundred thousand were seen in the last day, so an unfiltered
	 * random sample is almost entirely servers that died years ago — they never answer a ping, so
	 * they show no icon, no MOTD and cannot be joined.
	 */
	public int onlineWithinHours = 0;

	public ScannerFilters copy() {
		ScannerFilters c = new ScannerFilters();
		c.playerCount = playerCount;
		c.playerCap = playerCap;
		c.full = full;
		c.version = version;
		c.protocol = protocol;
		c.hasPlayerSample = hasPlayerSample;
		c.onlinePlayer = onlinePlayer;
		c.pastPlayer = pastPlayer;
		c.hasFavicon = hasFavicon;
		c.description = description;
		c.seenAfter = seenAfter;
		c.ipSubnet = ipSubnet;
		c.port = port;
		c.cracked = cracked;
		c.whitelisted = whitelisted;
		c.vanilla = vanilla;
		c.forge = forge;
		c.country = country;
		c.org = org;
		c.onlineWithinHours = onlineWithinHours;
		return c;
	}

	/** Overwrites this instance's fields from {@code other}, keeping the same object identity. */
	public void copyFrom(ScannerFilters other) {
		playerCount = other.playerCount;
		playerCap = other.playerCap;
		full = other.full;
		version = other.version;
		protocol = other.protocol;
		hasPlayerSample = other.hasPlayerSample;
		onlinePlayer = other.onlinePlayer;
		pastPlayer = other.pastPlayer;
		hasFavicon = other.hasFavicon;
		description = other.description;
		seenAfter = other.seenAfter;
		ipSubnet = other.ipSubnet;
		port = other.port;
		cracked = other.cracked;
		whitelisted = other.whitelisted;
		vanilla = other.vanilla;
		forge = other.forge;
		country = other.country;
		org = other.org;
		onlineWithinHours = other.onlineWithinHours;
	}

	/** Replaces every field with defaults. */
	public void reset() {
		ScannerFilters d = new ScannerFilters();
		playerCount = d.playerCount;
		playerCap = d.playerCap;
		full = d.full;
		version = d.version;
		protocol = d.protocol;
		hasPlayerSample = d.hasPlayerSample;
		onlinePlayer = d.onlinePlayer;
		pastPlayer = d.pastPlayer;
		hasFavicon = d.hasFavicon;
		description = d.description;
		seenAfter = d.seenAfter;
		ipSubnet = d.ipSubnet;
		port = d.port;
		cracked = d.cracked;
		whitelisted = d.whitelisted;
		vanilla = d.vanilla;
		forge = d.forge;
		country = d.country;
		org = d.org;
		onlineWithinHours = d.onlineWithinHours;
	}

	/** Number of filters currently doing something, for display on the filter button. */
	public int activeCount() {
		int n = 0;
		if (!playerCount.isBlank()) n++;
		if (!playerCap.isBlank()) n++;
		if (full.isSet()) n++;
		if (!version.isBlank()) n++;
		if (!protocol.isBlank()) n++;
		if (hasPlayerSample.isSet()) n++;
		if (!onlinePlayer.isBlank()) n++;
		if (!pastPlayer.isBlank()) n++;
		if (hasFavicon.isSet()) n++;
		if (!description.isBlank()) n++;
		if (seenAfter > 0) n++;
		if (!ipSubnet.isBlank()) n++;
		if (!port.isBlank()) n++;
		if (cracked.isSet()) n++;
		if (whitelisted.isSet()) n++;
		if (vanilla.isSet()) n++;
		if (forge.isSet()) n++;
		if (!country.isBlank()) n++;
		if (!org.isBlank()) n++;
		if (onlineWithinHours > 0) n++;
		return n;
	}

	/**
	 * The filters that are actually doing something, as the chips shown under the title. Keeps the
	 * reason results look the way they do visible without opening the filter screen.
	 */
	public java.util.List<FilterChip> activeSummary() {
		java.util.List<FilterChip> out = new java.util.ArrayList<>();
		if (!playerCount.isBlank()) out.add(FilterChip.of(FilterIcons.PLAYERS, playerCount));
		if (!playerCap.isBlank()) out.add(FilterChip.of(FilterIcons.PLAYER_CAP, playerCap));
		if (full.isSet()) out.add(FilterChip.flag(FilterIcons.FULL, full.toBoolean()));
		if (!version.isBlank()) out.add(FilterChip.of(FilterIcons.VERSION, version));
		if (!protocol.isBlank()) out.add(FilterChip.of(FilterIcons.PROTOCOL, protocol));
		if (hasPlayerSample.isSet()) {
			out.add(FilterChip.flag(FilterIcons.PLAYER_SAMPLE, hasPlayerSample.toBoolean()));
		}
		if (hasFavicon.isSet()) out.add(FilterChip.flag(FilterIcons.FAVICON, hasFavicon.toBoolean()));
		if (!description.isBlank()) out.add(FilterChip.of(FilterIcons.DESCRIPTION, description));
		if (!ipSubnet.isBlank()) out.add(FilterChip.of(FilterIcons.SUBNET, ipSubnet));
		if (!port.isBlank()) out.add(FilterChip.of(FilterIcons.PORT, port));
		if (vanilla.isSet()) out.add(FilterChip.flag(FilterIcons.VANILLA, vanilla.toBoolean()));
		return out;
	}

	/**
	 * A value that changes whenever the filters change, used to invalidate the cached result count
	 * and the loaded server list.
	 */
	public String signature() {
		return String.join("\0",
				playerCount, playerCap, full.name(), version, protocol, hasPlayerSample.name(),
				onlinePlayer, pastPlayer, hasFavicon.name(), description, Long.toString(seenAfter),
				ipSubnet, port, cracked.name(), whitelisted.name(), vanilla.name(), forge.name(),
				country, org, Integer.toString(onlineWithinHours));
	}
}
