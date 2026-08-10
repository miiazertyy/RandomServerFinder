package com.serverscanner.screen;

import com.serverscanner.ServerScannerMod;
import com.serverscanner.api.ScannedServer;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.multiplayer.ServerData;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

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
	private static final net.minecraft.resources.Identifier UNKNOWN_SERVER =
			net.minecraft.resources.Identifier.withDefaultNamespace("textures/misc/unknown_server.png");

	private final ScannedServer server;
	private final ServerData serverInfo;
	private final Minecraft client = Minecraft.getInstance();

	/**
	 * Holds the server's icon. Cheap until a favicon actually arrives — until then it has no
	 * texture and reports vanilla's "unknown server" image, which is what we want on screen.
	 */
	private final FaviconTexture icon;
	private byte[] uploadedFavicon;

	private boolean pingRequested;
	private boolean pingFinished;

	public ScannerEntry(ScannedServer server) {
		this.server = server;
		this.icon = FaviconTexture.forServer(client.getTextureManager(), server.address());

		if (server.resolved != null) {
			// Local mode already pinged this server to find it; reuse that result rather than
			// opening a second connection to learn what we were just told.
			this.serverInfo = (ServerData) server.resolved;
			this.pingRequested = true;
			this.pingFinished = true;
			return;
		}

		this.serverInfo = new ServerData(server.address(), server.address(), ServerData.Type.OTHER);
		this.serverInfo.setState(ServerData.State.INITIAL);
		// Seed the row with what the database already knows, so it reads sensibly before any ping.
		this.serverInfo.motd = Component.literal(trimMotd(server.descriptionOrEmpty()));
		this.serverInfo.status = Component.literal(server.onlinePlayers() + "/" + server.maxPlayers())
				.withStyle(ChatFormatting.GRAY);
		this.serverInfo.version = Component.literal(server.versionName());
		this.serverInfo.protocol = server.protocol();
	}

	public ScannedServer getServer() {
		return server;
	}

	public ServerData getServerInfo() {
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
		return pingFinished && serverInfo.state() == ServerData.State.UNREACHABLE;
	}

	public boolean isReachable() {
		return pingFinished && (serverInfo.state() == ServerData.State.SUCCESSFUL
				|| serverInfo.state() == ServerData.State.INCOMPATIBLE);
	}

	// --- Ping lifecycle, all called on the client thread ------------------------------------

	void onPingStarted() {
		pingRequested = true;
	}

	void onPingSucceeded() {
		pingFinished = true;
		// See the note in LocalServerFeed: with ViaFabricPlus installed, a version gap is
		// not something to warn about.
		serverInfo.setState(com.serverscanner.local.ViaFabricPlusBridge.isAvailable()
				|| serverInfo.protocol == SharedConstants.RELEASE_NETWORK_PROTOCOL_VERSION
				? ServerData.State.SUCCESSFUL
				: ServerData.State.INCOMPATIBLE);
	}

	void onPingFailed(boolean unknownHost) {
		pingRequested = true;
		pingFinished = true;
		serverInfo.setState(ServerData.State.UNREACHABLE);
		serverInfo.motd = Component.translatable(unknownHost
						? "multiplayer.status.cannot_resolve"
						: "multiplayer.status.cannot_connect")
				.withStyle(ChatFormatting.DARK_RED);
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
		if (serverInfo.getIconBytes() != null || serverInfo.ping > 0L) {
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
	public net.minecraft.resources.Identifier getIconTexture() {
		if (icon.isClosed()) {
			return UNKNOWN_SERVER;
		}

		byte[] favicon = serverInfo.getIconBytes();
		if (!Arrays.equals(favicon, uploadedFavicon)) {
			if (favicon == null) {
				icon.clear();
				uploadedFavicon = null;
			} else {
				try {
					icon.upload(NativeImage.read(favicon));
					uploadedFavicon = favicon;
				} catch (Throwable t) {
					// A malformed or wrongly sized icon; fall back rather than retry every frame.
					ServerScannerMod.LOGGER.debug("Invalid favicon for {}", serverInfo.ip, t);
					serverInfo.setIconBytes(null);
					uploadedFavicon = null;
				}
			}
		}
		return icon.textureLocation();
	}

	public List<Component> getPlayerListSummary() {
		return serverInfo.playerList;
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
		return serverInfo.motd == null ? server.descriptionOrEmpty() : serverInfo.motd.getString();
	}

	/** Frees the icon texture. Called when the screen closes. */
	public void close() {
		if (!icon.isClosed()) {
			icon.close();
		}
	}

	private static String trimMotd(String motd) {
		String single = motd.replace('\n', ' ').trim();
		return single.length() > 180 ? single.substring(0, 180) + "..." : single;
	}
}
