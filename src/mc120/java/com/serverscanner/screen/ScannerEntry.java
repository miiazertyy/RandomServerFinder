package com.serverscanner.screen;

import com.serverscanner.ServerScannerMod;
import com.serverscanner.api.ScannedServer;

import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.world.WorldIcon;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Arrays;
import java.util.List;

/**
 * One row of the scanner list: the database record, plus the live state Minecraft's own pinger
 * fills in once the server answers.
 *
 * <p>The scan database knows what a server looked like when it was last crawled, which may be hours
 * old and never includes the icon. Pinging gives the current MOTD, player count, latency and
 * favicon — the same information the vanilla server list shows.
 */
public class ScannerEntry {
	private static final net.minecraft.util.Identifier UNKNOWN_SERVER =
			new net.minecraft.util.Identifier("textures/misc/unknown_server.png");

	private final ScannedServer server;
	private final ServerInfo serverInfo;
	private final MinecraftClient client = MinecraftClient.getInstance();

	/**
	 * Holds the server's icon. Cheap until a favicon actually arrives — until then it has no
	 * texture and reports vanilla's "unknown server" image, which is what we want on screen.
	 */
	private final WorldIcon icon;
	private byte[] uploadedFavicon;

	/** 1.20.6's WorldIcon cannot report whether it is closed, so the state is kept here. */
	private boolean iconClosed;

	private boolean pingRequested;
	private boolean pingFinished;

	public ScannerEntry(ScannedServer server) {
		this.server = server;
		this.icon = WorldIcon.forServer(client.getTextureManager(), server.address());

		if (server.resolved != null) {
			// Local mode already pinged this server to find it; reuse that result rather than
			// opening a second connection to learn what we were just told.
			this.serverInfo = (ServerInfo) server.resolved;
			this.pingRequested = true;
			this.pingFinished = true;
			return;
		}

		this.serverInfo = new ServerInfo(server.address(), server.address(), ServerInfo.ServerType.OTHER);
		this.serverInfo.setStatus(ServerInfo.Status.INITIAL);
		// Seed the row with what the database already knows, so it reads sensibly before any ping.
		this.serverInfo.label = Text.literal(trimMotd(server.descriptionOrEmpty()));
		this.serverInfo.playerCountLabel = Text.literal(server.onlinePlayers() + "/" + server.maxPlayers())
				.formatted(Formatting.GRAY);
		this.serverInfo.version = Text.literal(server.versionName());
		this.serverInfo.protocolVersion = server.protocol();
	}

	public ScannedServer getServer() {
		return server;
	}

	public ServerInfo getServerInfo() {
		return serverInfo;
	}

	public boolean needsPing() {
		return !pingRequested;
	}

	public boolean isPinging() {
		return pingRequested && !pingFinished;
	}

	public boolean isPingFinished() {
		return pingFinished;
	}

	/** True once a ping has completed and the server did not answer. */
	public boolean isUnreachable() {
		return pingFinished && serverInfo.getStatus() == ServerInfo.Status.UNREACHABLE;
	}

	public boolean isReachable() {
		return pingFinished && (serverInfo.getStatus() == ServerInfo.Status.SUCCESSFUL
				|| serverInfo.getStatus() == ServerInfo.Status.INCOMPATIBLE);
	}

	// --- Ping lifecycle, all called on the client thread ------------------------------------

	void onPingStarted() {
		pingRequested = true;
	}

	void onPingSucceeded() {
		pingFinished = true;
		// See the note in LocalServerFeed: with ViaFabricPlus installed, a version gap is
		// not something to warn about.
		serverInfo.setStatus(com.serverscanner.local.ViaFabricPlusBridge.isAvailable()
				|| serverInfo.protocolVersion == SharedConstants.getGameVersion().getProtocolVersion()
				? ServerInfo.Status.SUCCESSFUL
				: ServerInfo.Status.INCOMPATIBLE);
	}

	void onPingFailed(boolean unknownHost) {
		pingRequested = true;
		pingFinished = true;
		serverInfo.setStatus(ServerInfo.Status.UNREACHABLE);
		serverInfo.label = Text.translatable(unknownHost
						? "multiplayer.status.cannot_resolve"
						: "multiplayer.status.cannot_connect")
				.formatted(Formatting.DARK_RED);
	}

	/**
	 * Called when a ping ran out of time.
	 *
	 * <p>Some servers answer the status query — sending their MOTD, player count and icon — but
	 * never return the latency ping. Those are reachable and joinable, so they are kept as a
	 * success rather than being crossed out; only servers that said nothing at all are marked
	 * unreachable.
	 */
	void onPingTimedOut() {
		if (serverInfo.getFavicon() != null || serverInfo.ping > 0L) {
			onPingSucceeded();
		} else {
			onPingFailed(false);
		}
	}

	void onFaviconChanged() {
		// The bytes are picked up during rendering; nothing to do here beyond keeping the callback
		// available for the pinger, which requires one.
	}

	/**
	 * Uploads a newly received favicon to the GPU if it changed.
	 *
	 * @return the texture to draw — never null, falling back to vanilla's "unknown server" image
	 *         for servers that have not answered yet or have no custom icon
	 */
	public net.minecraft.util.Identifier getIconTexture() {
		if (iconClosed) {
			return UNKNOWN_SERVER;
		}

		byte[] favicon = serverInfo.getFavicon();
		if (!Arrays.equals(favicon, uploadedFavicon)) {
			if (favicon == null) {
				icon.destroy();
				uploadedFavicon = null;
			} else {
				try {
					icon.load(NativeImage.read(favicon));
					uploadedFavicon = favicon;
				} catch (Throwable t) {
					// A malformed or wrongly sized icon; fall back rather than retry every frame.
					ServerScannerMod.LOGGER.debug("Invalid favicon for {}", serverInfo.address, t);
					serverInfo.setFavicon(null);
					uploadedFavicon = null;
				}
			}
		}
		return icon.getTextureId();
	}

	public List<Text> getPlayerListSummary() {
		return serverInfo.playerListSummary;
	}

	/**
	 * The headline for this row.
	 *
	 * <p>A scanned server has no name field, so the closest thing to one is the first line of its
	 * MOTD — that is what its owner chose to call it, and it identifies the server far better than
	 * an IP does. Servers whose MOTD is blank or generic fall back to the address.
	 */
	public String displayName() {
		String first = firstMotdLine();
		if (first.isEmpty() || first.length() > 48) {
			return com.serverscanner.config.Addresses.display(server.address());
		}
		return first;
	}

	/**
	 * The remainder of the MOTD once {@link #displayName()} has taken its first line.
	 *
	 * <p>Without this the same text is drawn twice — once as the row's name and again as its
	 * description — which is what most single-line MOTDs would produce.
	 */
	public String remainingMotd() {
		String motd = rawMotd();
		int newline = motd.indexOf('\n');
		if (newline < 0) {
			// The whole MOTD became the name, so there is nothing left to say.
			return firstMotdLine().isEmpty() || firstMotdLine().length() > 48 ? motd : "";
		}
		return motd.substring(newline + 1).trim();
	}

	private String firstMotdLine() {
		// Strip section-sign formatting so the name doesn't render as coloured soup.
		return rawMotd().split("\n", 2)[0].replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim();
	}

	private String rawMotd() {
		return serverInfo.label == null ? server.descriptionOrEmpty() : serverInfo.label.getString();
	}

	/** Frees the icon texture. Called when the screen closes. */
	public void close() {
		if (!iconClosed) {
			icon.close();
			iconClosed = true;
		}
	}

	private static String trimMotd(String motd) {
		String single = motd.replace('\n', ' ').trim();
		return single.length() > 180 ? single.substring(0, 180) + "..." : single;
	}
}
