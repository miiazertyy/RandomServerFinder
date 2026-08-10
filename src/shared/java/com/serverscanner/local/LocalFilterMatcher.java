package com.serverscanner.local;

import com.serverscanner.api.ScannedServer;
import com.serverscanner.filter.ScannerFilters;
import com.serverscanner.filter.TriState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Applies the filters in the client instead of in the database.
 *
 * <p>In local mode there is no metadata until a server has been pinged, so filtering splits in two:
 * address filters are cheap and are applied <em>before</em> pinging to avoid wasted connections,
 * and everything else is checked against what the ping came back with.
 *
 * <p>Some filters have no local equivalent at all. Country and hosting organisation come from a
 * GeoIP database, cracked and whitelisted come from separate scans, and the player history filters
 * come from data accumulated over time — none of which a single ping can tell us. Those are
 * reported by {@link #unsupported} so the UI can say so rather than silently ignoring them.
 */
public final class LocalFilterMatcher {
	private final ScannerFilters filters;

	private final Pattern versionPattern;
	private final List<String> descriptionPhrases = new ArrayList<>();
	private final List<String> descriptionWords = new ArrayList<>();
	private final List<long[]> allowedRanges = new ArrayList<>();
	private final List<long[]> excludedRanges = new ArrayList<>();
	private final Integer requiredPort;

	public LocalFilterMatcher(ScannerFilters filters) {
		this.filters = filters;
		this.versionPattern = filters.version.isBlank() ? null : likeToPattern(filters.version.trim());
		this.requiredPort = parseIntOrNull(filters.port);
		parseDescription(filters.description);
		parseSubnets(filters.ipSubnet);
	}

	/** Filters that are set but cannot be evaluated without the scanner database. */
	public static List<String> unsupported(ScannerFilters f) {
		List<String> out = new ArrayList<>();
		if (!f.country.isBlank()) out.add("Country");
		if (!f.org.isBlank()) out.add("Host / org");
		if (f.cracked.isSet()) out.add("Cracked");
		if (f.whitelisted.isSet()) out.add("Whitelisted");
		if (!f.onlinePlayer.isBlank()) out.add("Online player");
		if (!f.pastPlayer.isBlank()) out.add("Past player");
		if (f.forge.isSet()) out.add("Forge");
		return out;
	}

	/**
	 * Checks the filters that need only an address, so non-matching candidates are skipped without
	 * opening a connection.
	 */
	public boolean acceptsAddress(long ip, int port) {
		if (requiredPort != null && port != requiredPort) return false;

		for (long[] range : excludedRanges) {
			if (ip >= range[0] && ip <= range[1]) return false;
		}
		if (!allowedRanges.isEmpty()) {
			boolean inAny = false;
			for (long[] range : allowedRanges) {
				if (ip >= range[0] && ip <= range[1]) {
					inAny = true;
					break;
				}
			}
			if (!inAny) return false;
		}
		return true;
	}

	/** Checks the filters that need a ping response. */
	public boolean acceptsPinged(ScannedServer server) {
		if (!matchesPlayerCount(server.onlinePlayers())) return false;

		Integer cap = parseIntOrNull(filters.playerCap);
		if (cap != null && server.maxPlayers() != cap) return false;

		if (filters.full.isSet()) {
			boolean isFull = server.maxPlayers() > 0 && server.onlinePlayers() >= server.maxPlayers();
			if (isFull != filters.full.toBoolean()) return false;
		}

		if (versionPattern != null && !versionPattern.matcher(server.versionName()).matches()) return false;

		Integer protocol = parseIntOrNull(filters.protocol);
		if (protocol != null && server.protocol() != protocol) return false;

		if (!matchesDescription(server.descriptionOrEmpty())) return false;

		if (filters.hasFavicon.isSet() && server.hasFavicon != filters.hasFavicon.toBoolean()) return false;

		if (filters.hasPlayerSample.isSet()) {
			boolean has = server.players != null && server.players.hasPlayerSample;
			if (has != filters.hasPlayerSample.toBoolean()) return false;
		}

		if (filters.vanilla.isSet()) {
			boolean looksVanilla = server.versionName().matches("\\d\\.\\d{1,2}(\\.\\d)?");
			if (looksVanilla != filters.vanilla.toBoolean()) return false;
		}

		return true;
	}

	// --- Individual filters -------------------------------------------------------------------

	private boolean matchesPlayerCount(int online) {
		String raw = filters.playerCount.trim();
		if (raw.isEmpty()) return true;

		try {
			if (raw.startsWith(">=")) return online >= Integer.parseInt(raw.substring(2).trim());
			if (raw.startsWith("<=")) return online <= Integer.parseInt(raw.substring(2).trim());
			if (raw.startsWith(">")) return online > Integer.parseInt(raw.substring(1).trim());
			if (raw.startsWith("<")) return online < Integer.parseInt(raw.substring(1).trim());
			if (raw.contains("-")) {
				String[] parts = raw.split("-", 2);
				return online >= Integer.parseInt(parts[0].trim()) && online <= Integer.parseInt(parts[1].trim());
			}
			return online == Integer.parseInt(raw);
		} catch (NumberFormatException e) {
			// Bad input is reported by the filter screen; don't drop everything here.
			return true;
		}
	}

	private boolean matchesDescription(String description) {
		if (descriptionPhrases.isEmpty() && descriptionWords.isEmpty()) return true;
		String haystack = description.toLowerCase(Locale.ROOT);
		for (String phrase : descriptionPhrases) {
			if (!haystack.contains(phrase)) return false;
		}
		for (String word : descriptionWords) {
			if (!haystack.contains(word)) return false;
		}
		return true;
	}

	// --- Parsing ------------------------------------------------------------------------------

	/**
	 * Splits on unescaped quotes the same way the website does: quoted sections must appear
	 * verbatim, everything else is matched word by word in any order.
	 */
	private void parseDescription(String value) {
		if (value == null || value.isBlank()) return;

		List<StringBuilder> segments = new ArrayList<>();
		segments.add(new StringBuilder());
		boolean escaped = false;
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (!escaped) {
				if (c == '\\') {
					escaped = true;
					continue;
				}
				if (c == '"') {
					segments.add(new StringBuilder());
					continue;
				}
			}
			segments.get(segments.size() - 1).append(c);
			escaped = false;
		}

		int size = segments.size();
		boolean lastQuoteClosed = size % 2 == 1;
		for (int i = 0; i < size; i++) {
			String seg = segments.get(i).toString();
			if (seg.isBlank()) continue;
			if (i % 2 == 1 && (i != size - 1 || lastQuoteClosed)) {
				descriptionPhrases.add(seg.toLowerCase(Locale.ROOT));
			} else {
				for (String word : seg.trim().split("\\s+")) {
					if (!word.isBlank()) descriptionWords.add(word.toLowerCase(Locale.ROOT));
				}
			}
		}
	}

	private void parseSubnets(String value) {
		if (value == null || value.isBlank()) return;

		for (String rawRange : value.split(",")) {
			String range = rawRange.trim();
			if (range.isEmpty()) continue;

			boolean inverted = range.startsWith("!");
			if (inverted) range = range.substring(1).trim();

			String[] halves = range.split("/", 2);
			Long ip = parseIpv4(halves[0].trim());
			if (ip == null) continue;

			int prefix = 32;
			if (halves.length > 1) {
				Integer p = parseIntOrNull(halves[1]);
				if (p == null || p < 0 || p > 32) continue;
				prefix = p;
			}

			long hostMask = prefix >= 32 ? 0L : (1L << (32 - prefix)) - 1L;
			long min = ip & ~hostMask & 0xFFFFFFFFL;
			long max = (ip | hostMask) & 0xFFFFFFFFL;
			(inverted ? excludedRanges : allowedRanges).add(new long[] { min, max });
		}
	}

	private static Long parseIpv4(String text) {
		String[] octets = text.split("\\.");
		if (octets.length != 4) return null;
		long ip = 0;
		for (String octet : octets) {
			Integer v = parseIntOrNull(octet);
			if (v == null || v < 0 || v > 255) return null;
			ip = (ip << 8) | v;
		}
		return ip;
	}

	/** Converts a SQL LIKE pattern to an equivalent regex, so {@code %} and {@code _} keep meaning. */
	private static Pattern likeToPattern(String like) {
		StringBuilder regex = new StringBuilder();
		for (int i = 0; i < like.length(); i++) {
			char c = like.charAt(i);
			switch (c) {
				case '%' -> regex.append(".*");
				case '_' -> regex.append('.');
				default -> regex.append(Pattern.quote(String.valueOf(c)));
			}
		}
		return Pattern.compile(regex.toString(), Pattern.DOTALL);
	}

	private static Integer parseIntOrNull(String text) {
		if (text == null || text.isBlank()) return null;
		try {
			return Integer.parseInt(text.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
