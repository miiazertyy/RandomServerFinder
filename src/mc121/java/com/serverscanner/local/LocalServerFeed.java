package com.serverscanner.local;

import com.serverscanner.ServerScannerMod;
import com.serverscanner.api.ScannedServer;
import com.serverscanner.config.ScannerConfig;
import com.serverscanner.filter.ScannerFilters;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.MultiplayerServerListPinger;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.server.ServerMetadata;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Finds servers without the scanner API, by pinging addresses from the cached list.
 *
 * <p>The list is shuffled once and then walked in order, which is how "random" works here. Each
 * candidate is pinged with Minecraft's own server-list ping Ã¢â‚¬â€ exactly what the vanilla multiplayer
 * screen does for the servers you have saved Ã¢â‚¬â€ and only the ones that answer <em>and</em> match the
 * filters are added to the list. Everything on screen is therefore known to be online right now,
 * which is stronger than the API can promise.
 *
 * <p>The cost is latency rather than correctness: results trickle in at the speed of the network
 * instead of arriving a page at a time, and filters that need the scanner's database
 * ({@link LocalFilterMatcher#unsupported}) cannot be applied at all.
 */
public class LocalServerFeed {
	/** Simultaneous pings. Enough to fill the list quickly without behaving like a port scanner. */
	private static final int MAX_CONCURRENT = 16;

	/** Stop prospecting once this many results are loaded beyond what the user has reached. */
	private static final int LOOKAHEAD = 60;

	/** A candidate that has said nothing for this long is abandoned so its slot can be reused. */
	private static final long PING_TIMEOUT_MS = 10_000L;

	/** Ports either side of a hit to look at, and a ceiling so they cannot crowd out the main list. */
	private static final int NEIGHBOUR_PORTS = 4;
	private static final int NEIGHBOUR_QUEUE_LIMIT = 256;

	/** How many of the concurrent pings neighbours may occupy at once. */
	private static final int NEIGHBOUR_SLOTS = MAX_CONCURRENT / 4;

	private final MinecraftClient client = MinecraftClient.getInstance();
	private final ScannerFilters filters;
	private final LocalFilterMatcher matcher;
	private final ScannerConfig config;

	private final List<ScannedServer> servers = new ArrayList<>();
	private final List<Candidate> inFlight = new ArrayList<>();

	private MultiplayerServerListPinger pinger;
	private ExecutorService executor;

	private LocalIpList list;
	private int cursor;

	/** Counted separately from the cursor, which only walks the published list. */
	private int neighboursChecked;

	/**
	 * Ports queued for a look because a server answered on the same machine.
	 *
	 * <p>Jumped ahead of the main list, since a neighbour of a live server is a far better bet than
	 * the next random address, and the queue is capped so a busy host cannot crowd everything else
	 * out.
	 */
	private final java.util.ArrayDeque<long[]> neighbourQueue = new java.util.ArrayDeque<>();

	/** Hosts already expanded, so each machine is only walked once. */
	private final java.util.Set<Long> expanded = new java.util.HashSet<>();
	private int wanted = LOOKAHEAD;

	private boolean started;
	private boolean listLoading;
	private boolean exhausted;
	private String error;
	private volatile boolean cancelled;

	/** One-shot guard so a broken ping path is logged once rather than per address. */
	private static boolean pingFailureReported;

	private static final class Candidate {
		final ServerInfo info;
		final long ip;
		final int port;
		final boolean bedrock;
		final boolean neighbour;
		final long startedAt;
		boolean answered;

		Candidate(ServerInfo info, long ip, int port, boolean bedrock, boolean neighbour) {
			this.info = info;
			this.ip = ip;
			this.port = port;
			this.bedrock = bedrock;
			this.neighbour = neighbour;
			this.startedAt = System.currentTimeMillis();
		}
	}

	/** Set when the addresses were handed to us, so start() does not go looking for a list. */
	private LocalIpList preloaded;

	public LocalServerFeed(ScannerFilters filters, ScannerConfig config) {
		this.filters = filters.copy();
		this.matcher = new LocalFilterMatcher(this.filters);
		this.config = config;
	}

	/**
	 * A feed over a fixed set of addresses, walked in the order given.
	 *
	 * <p>The join history is a list of servers, not a pool to sample from, so it is neither
	 * downloaded nor shuffled. Everything after that is the same: the same pings fill in the same
	 * icons, names and player counts, which is what lets the history use the finder's own list.
	 */
	public static LocalServerFeed over(LocalIpList addresses, ScannerFilters filters, ScannerConfig config) {
		LocalServerFeed feed = new LocalServerFeed(filters, config);
		feed.preloaded = addresses;
		feed.wanted = addresses.size();
		return feed;
	}

	// --- Feed state ---------------------------------------------------------------------------

	public List<ScannedServer> getServers() {
		return servers;
	}

	public int size() {
		return servers.size();
	}

	public int getTotal() {
		// The number of addresses to try is known; how many will answer is not.
		return list == null ? -1 : list.size();
	}

	/** How many addresses have been tried so far, answered or not. */
	public int checkedCount() {
		return cursor + neighboursChecked;
	}

	public boolean isLoading() {
		return listLoading || !inFlight.isEmpty();
	}

	public boolean isExhausted() {
		return exhausted;
	}

	public String getError() {
		return error;
	}

	public String sourceLabel() {
		return "local list";
	}

	/** Filters the user has set that this feed cannot honour. */
	public List<String> unsupportedFilters() {
		return LocalFilterMatcher.unsupported(filters);
	}

	// --- Loading ------------------------------------------------------------------------------

	public void start() {
		if (started) return;
		started = true;
		listLoading = true;

		pinger = new MultiplayerServerListPinger();
		executor = Executors.newFixedThreadPool(MAX_CONCURRENT, r -> {
			Thread t = new Thread(r, "server-scanner-local");
			t.setDaemon(true);
			return t;
		});

		if (preloaded != null) {
			list = preloaded;
			listLoading = false;
			return;
		}

		LocalIpList.load(config.ipsListUrl, config.extraIpListUrls,
				config.includeBedrock ? config.bedrockListUrl : null, config.ipsListPath, false)
				.whenComplete((loaded, throwable) -> client.execute(() -> {
					listLoading = false;
					if (cancelled) return;
					if (throwable != null) {
						Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
						error = cause instanceof LocalIpList.LocalListException
								? cause.getMessage()
								: "Could not load the server list: " + cause.getMessage();
						ServerScannerMod.LOGGER.error("Local list load failed", cause);
						return;
					}
					loaded.shuffle(new Random());
					list = loaded;
				}));
	}

	public void maybeLoadMore(int lastVisibleIndex) {
		// Keep a buffer of results ahead of wherever the user has scrolled to.
		wanted = Math.max(wanted, lastVisibleIndex + LOOKAHEAD);
	}

	/**
	 * Pumps the pinger and tops up the in-flight set. Must run on the client thread, because the
	 * pinger's connections are advanced from here.
	 */
	public void tick() {
		if (cancelled || list == null || error != null) return;

		pinger.tick();
		harvest();

		// Neighbours are better bets than the next random address, but they are still mostly closed
		// ports that sit there for the full timeout. Letting them take the whole pipe stalls the
		// main search completely, so they get a share of it and no more.
		int neighboursInFlight = 0;
		for (Candidate c : inFlight) {
			if (c.neighbour) neighboursInFlight++;
		}
		while (neighboursInFlight < NEIGHBOUR_SLOTS && inFlight.size() < MAX_CONCURRENT
				&& servers.size() < wanted && !neighbourQueue.isEmpty()) {
			long[] next = neighbourQueue.poll();
			neighboursChecked++;
			if (matcher.acceptsAddress(next[0], (int) next[1])) {
				startPing(next[0], (int) next[1], false, true);
				neighboursInFlight++;
			}
		}

		while (inFlight.size() < MAX_CONCURRENT && servers.size() < wanted && cursor < list.size()) {
			int index = cursor++;
			long ip = list.ipAt(index);
			int port = list.portAt(index);
			boolean bedrock = list.isBedrockAt(index);
			if (!matcher.acceptsAddress(ip, port)) continue;
			startPing(ip, port, bedrock, false);
		}

		if (cursor >= list.size() && inFlight.isEmpty()) {
			exhausted = true;
		}
	}

	private void startPing(long ip, int port, boolean bedrock, boolean neighbour) {
		String address = formatAddress(ip, port);
		ServerInfo info = new ServerInfo(address, address, ServerInfo.ServerType.OTHER);
		info.setStatus(ServerInfo.Status.PINGING);

		Candidate candidate = new Candidate(info, ip, port, bedrock, neighbour);
		inFlight.add(candidate);

		if (bedrock) {
			startBedrockPing(candidate, info, ip, port);
			return;
		}

		executor.submit(() -> {
			try {
				PingerCompat.add(pinger, info,
						() -> {},
						() -> client.execute(() -> candidate.answered = true));
			} catch (Exception e) {
				// An unresolvable host or refused connection is normal. Anything else means the ping
				// path itself is broken, which looks identical to "no servers exist" from the screen,
				// so report the first one rather than swallowing every failure.
				if (!pingFailureReported && !(e instanceof java.net.UnknownHostException)
						&& !(e instanceof java.net.ConnectException)) {
					pingFailureReported = true;
					com.serverscanner.Log.LOGGER.warn("Server pings are failing: {}", e.toString(), e);
				}
				client.execute(() -> info.setStatus(ServerInfo.Status.UNREACHABLE));
			}
		});
	}

	/** Moves finished candidates out of the in-flight set, keeping the ones that matched. */
	private void harvest() {
		long now = System.currentTimeMillis();
		Iterator<Candidate> it = inFlight.iterator();

		while (it.hasNext()) {
			Candidate c = it.next();
			ServerInfo.Status status = c.info.getStatus();
			boolean timedOut = now - c.startedAt > PING_TIMEOUT_MS;

			if (status == ServerInfo.Status.PINGING && !c.answered && !timedOut) {
				continue;
			}
			it.remove();

			// A server that sent its details but no latency reply is still perfectly joinable.
			boolean responded = c.answered || c.info.getFavicon() != null || c.info.players != null;
			if (!responded || status == ServerInfo.Status.UNREACHABLE) {
				continue;
			}

			// Flagged as incompatible only when nothing here can translate it. With
			// ViaFabricPlus installed the version gap is its job to close, not a warning
			// worth stamping on most of the list.
			c.info.setStatus(ViaFabricPlusBridge.isAvailable()
					|| c.info.protocolVersion == net.minecraft.SharedConstants.getGameVersion().protocolVersion()
					? ServerInfo.Status.SUCCESSFUL
					: ServerInfo.Status.INCOMPATIBLE);

			queueNeighbours(c);

			ScannedServer server = toScannedServer(c);
			if (matcher.acceptsPinged(server)) {
				servers.add(server);
			}
		}
	}

	/** Builds the record the list displays from what the ping told us. */
	private ScannedServer toScannedServer(Candidate c) {
		ScannedServer server = new ScannedServer();
		server.ip = c.ip;
		server.port = c.port;

		long now = System.currentTimeMillis() / 1000L;
		server.discovered = now;
		server.lastSeen = now;

		server.version = new ScannedServer.Version();
		server.version.name = c.info.version == null ? "?" : c.info.version.getString();
		server.version.protocol = c.info.protocolVersion;

		server.description = c.info.label == null ? "" : c.info.label.getString();
		server.rawDescription = server.description;

		ServerMetadata.Players players = c.info.players;
		server.players = new ScannedServer.Players();
		server.players.online = players == null ? 0 : players.online();
		server.players.max = players == null ? 0 : players.max();
		server.players.hasPlayerSample = players != null && !players.sample().isEmpty();

		server.hasFavicon = c.info.getFavicon() != null;
		server.bedrock = c.bedrock;
		server.geo = new ScannedServer.Geo();

		// The list must not ping this again; it already has a live result.
		server.resolved = c.info;
		return server;
	}

	/**
	 * Pings a Bedrock server and fills in the same fields the Java ping would.
	 *
	 * <p>Nothing in the game's own pinger understands this protocol, so the reply is unpacked here
	 * and written straight onto the entry, including the latency, which RakNet does not report and
	 * is therefore measured around the call.
	 */
	private void startBedrockPing(Candidate candidate, ServerInfo info, long ip, int port) {
		String host = LocalIpList.formatIp(ip);
		executor.submit(() -> {
			long startedAt = System.currentTimeMillis();
			try {
				BedrockPinger.Pong pong = BedrockPinger.ping(host, port, (int) PING_TIMEOUT_MS);
				long latency = System.currentTimeMillis() - startedAt;
				client.execute(() -> {
					info.protocolVersion = pong.protocol();
					info.version = net.minecraft.text.Text.literal(pong.version().isBlank() ? "Bedrock" : "Bedrock " + pong.version());
					info.label = net.minecraft.text.Text.literal(pong.name());
					// The counts live in the same record the Java ping fills in, so everything
					// downstream reads them without knowing which protocol answered.
					info.players = new net.minecraft.server.ServerMetadata.Players(pong.max(), pong.online(), java.util.List.of());
					info.playerCountLabel = net.minecraft.text.Text.literal(pong.online() + "/" + pong.max());
					info.ping = latency;
					candidate.answered = true;
				});
			} catch (Exception e) {
				// Almost always a timeout, which for a UDP ping just means nothing is listening.
				client.execute(() -> info.setStatus(ServerInfo.Status.UNREACHABLE));
			}
		});
	}

	/**
	 * Queues the ports either side of a server that just answered.
	 *
	 * <p>Only ever the same machine, and only once per machine. Bedrock is left alone: its
	 * neighbours would be Bedrock too, and this walks the Java ports.
	 */
	private void queueNeighbours(Candidate c) {
		if (!config.probeNeighbourPorts || c.bedrock) return;
		if (neighbourQueue.size() >= NEIGHBOUR_QUEUE_LIMIT) return;
		if (!expanded.add(c.ip)) return;

		for (int offset = 1; offset <= NEIGHBOUR_PORTS; offset++) {
			int below = c.port - offset;
			int above = c.port + offset;
			if (below > 1024) neighbourQueue.add(new long[] { c.ip, below });
			if (above < 65535) neighbourQueue.add(new long[] { c.ip, above });
		}
	}

	private static String formatAddress(long ip, int port) {
		String host = String.format("%d.%d.%d.%d",
				(ip >> 24) & 0xFF, (ip >> 16) & 0xFF, (ip >> 8) & 0xFF, ip & 0xFF);
		return port == 25565 ? host : host + ":" + port;
	}

	public void cancel() {
		cancelled = true;
		inFlight.clear();
		if (pinger != null) pinger.cancel();
		if (executor != null) executor.shutdownNow();
	}
}
