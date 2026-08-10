package com.serverscanner.local;

import com.serverscanner.Log;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * The list of known Minecraft server addresses, held locally.
 *
 * <p>The scanner project publishes its results as a plain binary file: six bytes per server, a
 * big-endian IPv4 address followed by a big-endian port. It is about a megabyte for roughly 190,000
 * servers, so it is cheap to download once and keep. Unlike the API's 30-million-row history this
 * file is the current scan, so nearly everything in it is actually alive.
 *
 * <p>Once cached this needs no network at all, which is the point: the mod can find servers with
 * the scanner API completely unavailable.
 */
public final class LocalIpList {
	/** Six bytes per record: 4 for the address, 2 for the port. */
	private static final int RECORD_SIZE = 6;

	/** Re-download after this long; the upstream file is regenerated continuously. */
	private static final Duration REFRESH_AFTER = Duration.ofHours(12);

	private final long[] entries;

	private LocalIpList(long[] entries) {
		this.entries = entries;
	}

	public int size() {
		return entries.length;
	}

	/**
	 * A marker bit above the packed address, saying the entry is a Bedrock server.
	 *
	 * <p>An address alone cannot tell you: Bedrock's usual port is only a convention, and the two
	 * editions answer completely different protocols, so pinging one as the other always times out.
	 * The bit rides along in the spare top half of the long rather than costing a parallel array.
	 */
	private static final long BEDROCK_BIT = 1L << 48;

	/** Address of entry {@code i}, as an unsigned 32-bit value in a long. */
	public long ipAt(int i) {
		return (entries[i] >>> 16) & 0xFFFFFFFFL;
	}

	public int portAt(int i) {
		return (int) (entries[i] & 0xFFFF);
	}

	/** Whether entry {@code i} speaks Bedrock's protocol rather than Java's. */
	public boolean isBedrockAt(int i) {
		return (entries[i] & BEDROCK_BIT) != 0;
	}

	/**
	 * Shuffles in place, so walking the list in order yields servers in random order.
	 *
	 * <p>This is what makes "random" work without a database: rather than asking for a random
	 * offset, the whole list is permuted once and consumed sequentially.
	 */
	public void shuffle(Random random) {
		for (int i = entries.length - 1; i > 0; i--) {
			int j = random.nextInt(i + 1);
			long tmp = entries[i];
			entries[i] = entries[j];
			entries[j] = tmp;
		}
	}

	/** The dotted form of a packed address. */
	public static String formatIp(long ip) {
		return ((ip >> 24) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + (ip & 0xFF);
	}

	/**
	 * A list built from addresses already in hand, rather than fetched.
	 *
	 * <p>Used for the join history, which is a fixed set of servers to be walked in the order given
	 * rather than a pool to sample from.
	 */
	public static LocalIpList of(long[] packed) {
		return new LocalIpList(packed);
	}

	/** Packs one address the same way the file format does. */
	public static long pack(long ip, int port, boolean bedrock) {
		return (bedrock ? BEDROCK_BIT : 0L) | (ip << 16) | (port & 0xFFFFL);
	}

	/** A dotted address back to its packed form, or -1 when it is not one. */
	public static long parseAddress(String host) {
		byte[] bytes = host.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		return parseIpv4(bytes, 0, bytes.length);
	}

	// --- Loading -----------------------------------------------------------------------------

	public static Path cachePath() {
		return FabricLoader.getInstance().getConfigDir().resolve("randomserverfinder-ips.bin");
	}

	/**
	 * Loads the list, downloading it only if there is no usable cache.
	 *
	 * @param url            where to fetch the published list from
	 * @param overridePath   a user-supplied file to use instead, or blank
	 * @param forceRefresh   ignore cache age and re-download
	 */
	public static CompletableFuture<LocalIpList> load(String url, List<String> extraUrls,
			String bedrockUrl, String overridePath, boolean forceRefresh) {
		return CompletableFuture.supplyAsync(() -> {
			if (overridePath != null && !overridePath.isBlank()) {
				Path custom = Path.of(overridePath.trim());
				try {
					return parseAny(Files.readAllBytes(custom));
				} catch (IOException e) {
					throw new LocalListException("Could not read IP list at " + custom + ": " + e.getMessage());
				}
			}

			LocalIpList primary = loadOne(url, cachePath(), forceRefresh, true);

			List<String> extras = new ArrayList<>();
			if (extraUrls != null) extras.addAll(extraUrls);
			if (bedrockUrl != null && !bedrockUrl.isBlank()) extras.add(bedrockUrl.trim());
			if (extras.isEmpty()) return primary;

			// Concatenated, then sorted and de-duplicated in place. A HashSet of boxed Longs would be
			// the obvious way to drop duplicates, but it costs roughly seven times what the packed
			// array does, and the sources worth adding here are the multi-million-address dumps. On
			// those it is the difference between a large allocation and running the client out of
			// heap. Sorting reorders the list, which costs nothing: it is shuffled before use.
			List<long[]> parts = new ArrayList<>();
			parts.add(primary.entries);
			int total = primary.entries.length;

			for (String extra : extras) {
				if (extra == null || extra.isBlank()) continue;
				String source = extra.trim();
				try {
					LocalIpList list = loadOne(source, extraCachePath(source), forceRefresh, false);
					if (source.equals(bedrockUrl == null ? null : bedrockUrl.trim())) list = list.asBedrock();
					parts.add(list.entries);
					total += list.entries.length;
					Log.LOGGER.info("Merged {} addresses from {}", list.size(), source);
				} catch (RuntimeException e) {
					// One unreachable or malformed extra source should not take the finder down.
					Log.LOGGER.warn("Skipping address list {}: {}", source, e.getMessage());
				}
			}

			long[] all = new long[total];
			int at = 0;
			for (long[] part : parts) {
				System.arraycopy(part, 0, all, at, part.length);
				at += part.length;
			}
			Arrays.sort(all);

			int kept = 0;
			for (int i = 0; i < all.length; i++) {
				if (i == 0 || all[i] != all[i - 1]) all[kept++] = all[i];
			}
			long[] entries = kept == all.length ? all : Arrays.copyOf(all, kept);
			Log.LOGGER.info("{} addresses in total across all sources, {} of them duplicates",
					entries.length, all.length - kept);
			return new LocalIpList(entries);
		});
	}

	/** Fetches and parses one source, preferring a fresh cache and falling back to a stale one. */
	private static LocalIpList loadOne(String url, Path cache, boolean forceRefresh, boolean allowBundled) {
		if (!forceRefresh && isFresh(cache)) {
			try {
				LocalIpList cached = parseAny(Files.readAllBytes(cache));
				Log.LOGGER.info("Loaded {} server addresses from the cache", cached.size());
				return cached;
			} catch (IOException | LocalListException e) {
				// Covers a corrupt cache as well as an unreadable one.
				Log.LOGGER.warn("Cached address list unusable ({}), re-downloading", e.getMessage());
			}
		}

		byte[] data;
		try {
			data = download(url);
		} catch (RuntimeException e) {
			// Offline, or the upstream file moved. Fall back to a stale cache, then to the
			// snapshot shipped inside the mod, so the finder still works with no network.
			Log.LOGGER.warn("Refreshing the address list failed: {}", e.getMessage());
			byte[] fallback = readCache(cache);
			if (fallback == null && allowBundled) fallback = readBundled();
			if (fallback == null) throw e;
			return parseAny(fallback);
		}

		try {
			Files.createDirectories(cache.getParent());
			Files.write(cache, data);
		} catch (IOException e) {
			// Not fatal, we can still use what we just downloaded.
			Log.LOGGER.warn("Could not cache the IP list to {}", cache, e);
		}
		return parseAny(data);
	}

	/** Cached beside the main list, under a name derived from the URL so sources cannot collide. */
	private static Path extraCachePath(String url) {
		String key = Integer.toHexString(url.hashCode());
		return FabricLoader.getInstance().getConfigDir()
				.resolve("randomserverfinder-ips-" + key + ".bin");
	}

	private static byte[] readCache(Path cache) {
		try {
			return Files.exists(cache) && Files.size(cache) >= RECORD_SIZE ? Files.readAllBytes(cache) : null;
		} catch (IOException e) {
			return null;
		}
	}

	/** The copy bundled in the mod jar, used when there has never been a successful download. */
	private static byte[] readBundled() {
		try (java.io.InputStream in = LocalIpList.class.getResourceAsStream(
				"/assets/randomserverfinder/ips.bin")) {
			return in == null ? null : in.readAllBytes();
		} catch (IOException e) {
			return null;
		}
	}

	/** True when a cached copy exists and is recent enough to use without asking the network. */
	public static boolean isFresh(Path cache) {
		try {
			if (!Files.exists(cache) || Files.size(cache) < RECORD_SIZE) return false;
			FileTime modified = Files.getLastModifiedTime(cache);
			return modified.toInstant().isAfter(Instant.now().minus(REFRESH_AFTER));
		} catch (IOException e) {
			return false;
		}
	}

	public static boolean hasCache() {
		try {
			Path cache = cachePath();
			return Files.exists(cache) && Files.size(cache) >= RECORD_SIZE;
		} catch (IOException e) {
			return false;
		}
	}

	private static byte[] download(String url) {
		HttpClient http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(20))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofMinutes(3))
				.header("User-Agent", Log.USER_AGENT)
				.GET()
				.build();
		try {
			HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
			if (response.statusCode() != 200) {
				throw new LocalListException("Could not download the server list (HTTP "
						+ response.statusCode() + ")");
			}
			return response.body();
		} catch (LocalListException e) {
			throw e;
		} catch (Exception e) {
			throw new LocalListException("Could not download the server list: " + e.getMessage());
		}
	}

	/**
	 * Parses whichever of the two formats a source turns out to use.
	 *
	 * <p>The published lists are six bytes per record. Raw scanner dumps are usually text, one
	 * address per line, sometimes with a port and sometimes not.
	 */
	private static LocalIpList parseAny(byte[] data) {
		return looksLikeText(data) ? parseText(data) : parse(data);
	}

	/** Returns a copy of this list with every entry marked as Bedrock. */
	private LocalIpList asBedrock() {
		long[] marked = new long[entries.length];
		for (int i = 0; i < entries.length; i++) marked[i] = entries[i] | BEDROCK_BIT;
		return new LocalIpList(marked);
	}

	/**
	 * Text if the opening bytes contain nothing but what an address list can hold.
	 *
	 * <p>Sniffed rather than decided by file extension, because these arrive over HTTP from
	 * whatever URL the user pasted in.
	 */
	private static boolean looksLikeText(byte[] data) {
		int sample = Math.min(data.length, 512);
		if (sample == 0) return false;
		for (int i = 0; i < sample; i++) {
			int b = data[i] & 0xFF;
			boolean printable = (b >= '0' && b <= '9')
					|| b == '.' || b == ':' || b == ',' || b == ' ' || b == '\t'
					|| b == '\r' || b == '\n';
			if (!printable) return false;
		}
		return true;
	}

	/**
	 * One address per line, with an optional {@code :port} and any further CSV columns ignored.
	 *
	 * <p>Read straight out of the bytes rather than through {@code new String(...).split("\n")}. The
	 * lists this format exists for run to tens of megabytes: decoding one to a String doubles it,
	 * splitting it allocates a few million more, and a boxed set to de-duplicate costs several times
	 * again. Together that is enough to exhaust the heap of a client that is also running a game.
	 */
	private static LocalIpList parseText(byte[] data) {
		long[] entries = new long[1024];
		int count = 0;

		int at = 0;
		while (at < data.length) {
			int start = at;
			while (at < data.length && data[at] != '\n') at++;
			int end = at;
			at++;

			// Trim the line ending and any padding, then keep only the first CSV column.
			while (end > start && isBlank(data[end - 1])) end--;
			while (start < end && isBlank(data[start])) start++;
			for (int i = start; i < end; i++) {
				if (data[i] == ',') {
					end = i;
					break;
				}
			}
			while (end > start && isBlank(data[end - 1])) end--;
			if (start >= end) continue;

			// A scan of the default port records no port at all, so assume the one it scanned.
			int port = 25565;
			int colon = -1;
			for (int i = start; i < end; i++) {
				if (data[i] == ':') {
					colon = i;
					break;
				}
			}
			if (colon >= 0) {
				port = parseNumber(data, colon + 1, end);
				end = colon;
			}
			if (port <= 0 || port > 65535) continue;

			long ip = parseIpv4(data, start, end);
			if (ip < 0) continue;

			if (count == entries.length) entries = Arrays.copyOf(entries, entries.length * 2);
			entries[count++] = (ip << 16) | port;
		}

		if (count == 0) {
			throw new LocalListException("No usable addresses in that list");
		}

		long[] packed = Arrays.copyOf(entries, count);
		Arrays.sort(packed);
		int kept = 0;
		for (int i = 0; i < packed.length; i++) {
			if (i == 0 || packed[i] != packed[i - 1]) packed[kept++] = packed[i];
		}
		return new LocalIpList(kept == packed.length ? packed : Arrays.copyOf(packed, kept));
	}

	private static boolean isBlank(byte b) {
		return b == ' ' || b == '\t' || b == '\r';
	}

	/** Digits in {@code [start, end)} as a number, or -1 for anything else. */
	private static int parseNumber(byte[] data, int start, int end) {
		if (start >= end) return -1;
		int value = 0;
		for (int i = start; i < end; i++) {
			int digit = data[i] - '0';
			if (digit < 0 || digit > 9) return -1;
			value = value * 10 + digit;
			if (value > 65535) return -1;
		}
		return value;
	}

	/** Dotted quad in {@code [start, end)} as a packed address, or -1 for anything that is not one. */
	private static long parseIpv4(byte[] data, int start, int end) {
		long ip = 0;
		int octets = 0;
		int at = start;

		while (at <= end && octets < 4) {
			int dot = at;
			while (dot < end && data[dot] != '.') dot++;
			int octet = parseNumber(data, at, dot);
			if (octet < 0 || octet > 255) return -1;
			ip = (ip << 8) | octet;
			octets++;
			at = dot + 1;
		}
		return octets == 4 && at > end ? ip : -1;
	}

	private static LocalIpList parse(byte[] data) {
		int count = data.length / RECORD_SIZE;
		if (count == 0) {
			throw new LocalListException("The server list is empty");
		}

		long[] entries = new long[count];
		int plausible = 0;
		for (int i = 0; i < count; i++) {
			int o = i * RECORD_SIZE;
			long ip = ((long) (data[o] & 0xFF) << 24)
					| ((long) (data[o + 1] & 0xFF) << 16)
					| ((long) (data[o + 2] & 0xFF) << 8)
					| (data[o + 3] & 0xFF);
			int port = ((data[o + 4] & 0xFF) << 8) | (data[o + 5] & 0xFF);
			// Packed as ip<<16|port so the whole list is one primitive array rather than 190k objects.
			entries[i] = (ip << 16) | port;

			int firstOctet = (int) (ip >> 24);
			if (port != 0 && firstOctet != 0 && firstOctet != 127 && firstOctet < 240) {
				plausible++;
			}
		}

		// A truncated or half-written cache still divides by six and parses into nonsense addresses,
		// which then look exactly like "nothing on the internet answered". Reject it instead so the
		// caller falls back to a copy that works.
		if (plausible < count / 2) {
			throw new LocalListException("The server list looks corrupted (" + plausible + " of "
					+ count + " addresses are usable)");
		}
		return new LocalIpList(entries);
	}

	/** Carries a message already fit to show the user. */
	public static class LocalListException extends RuntimeException {
		public LocalListException(String message) {
			super(message);
		}
	}
}
