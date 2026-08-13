package com.serverscanner.party;

import com.serverscanner.ServerScannerMod;
import com.serverscanner.config.ScannerConfig;

import net.minecraft.client.Minecraft;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lets a group hop between random servers together.
 *
 * <p>One person hosts: their client opens a socket that the others connect to. When the host joins a
 * server the address is pushed to everyone, and they follow automatically. There is no third-party
 * service involved — the host's machine <em>is</em> the meeting point, which is the only way to do
 * this without running a directory server somewhere.
 *
 * <p>That is also the one limitation: friends connect to the host's address rather than to a
 * username, because nothing on the client can turn "Steve" into an address without a service that
 * tracks who is online. Usernames are exchanged once connected, so the member list still shows who
 * is present.
 *
 * <p>The protocol is deliberately tiny: newline-terminated, tab-separated text.
 */
public final class PartyManager {
	public static final int DEFAULT_PORT = 25577;

	/** Hard ceiling; the configured limit is clamped to it, and the roster parser relies on it. */
	private static final int MAX_MEMBERS = 16;


	/** Never less than this, however short the auto-join pause is set. */
	private static final long MIN_LEAVE_DELAY_MS = 10_000L;

	/** When to tell members the host has gone, or 0 when nothing is pending. */
	private static volatile long leaveDueAt;

	/**
	 * Where the host currently is, as last announced.
	 *
	 * <p>Kept separately from {@link #pendingTravel}, which is consumed the moment it is applied.
	 * This one persists, so a member who leaves the server can be offered a way back without
	 * waiting for the host to move again. Also sent to anyone joining the party late, who would
	 * otherwise have no idea where everyone is.
	 */
	private static volatile String hostServer;

	/** Set when the host says it has left, applied on the client thread by {@link #tick}. */
	private static volatile boolean pendingLeave;
	private static final int MAX_LINE_LENGTH = 512;

	public enum Mode {
		OFF,
		HOSTING,
		JOINED
	}

	private static Mode mode = Mode.OFF;
	private static Host host;
	private static Client client;

	/** Usernames currently in the party, host first. */
	private static volatile List<String> members = List.of();

	private static volatile String status = "";
	private static volatile String error;

	/** Set when the host tells us to move; consumed on the client thread by {@link #tick}. */
	private static volatile String pendingTravel;

	/** True once we have followed the party into a server, so leaving returns to the finder. */
	private static volatile boolean travelled;

	private PartyManager() {
	}

	// --- State ---------------------------------------------------------------------------------

	public static Mode getMode() {
		return mode;
	}

	public static boolean isActive() {
		return mode != Mode.OFF;
	}

	public static List<String> getMembers() {
		return members;
	}

	public static String getStatus() {
		return status;
	}

	public static String getError() {
		return error;
	}

	public static void clearError() {
		error = null;
	}

	public static boolean hasTravelled() {
		return travelled;
	}

	/** The server the party is on, or null when nobody has said. */
	public static String getHostServer() {
		return hostServer;
	}

	public static void clearTravelled() {
		travelled = false;
	}

	// --- Lifecycle -----------------------------------------------------------------------------

	/** Starts listening so friends can connect to this machine. */
	public static void startHosting(int port) {
		leave();
		error = null;
		try {
			host = new Host(port);
			mode = Mode.HOSTING;
			status = "Hosting on port " + port;
			updateMembers();
		} catch (IOException e) {
			error = "Could not listen on port " + port + ": " + e.getMessage();
			host = null;
			mode = Mode.OFF;
		}
	}

	/** Connects to a friend who is hosting. Accepts {@code host} or {@code host:port}. */
	public static void joinParty(String address) {
		leave();
		error = null;

		String hostName = address.trim();
		int port = DEFAULT_PORT;
		int colon = hostName.lastIndexOf(':');
		if (colon > 0) {
			try {
				port = Integer.parseInt(hostName.substring(colon + 1).trim());
				hostName = hostName.substring(0, colon).trim();
			} catch (NumberFormatException e) {
				error = "\"" + hostName.substring(colon + 1) + "\" is not a port number";
				return;
			}
		}
		if (hostName.isEmpty()) {
			error = "Enter the address your friend is hosting on";
			return;
		}

		status = "Connecting to " + hostName + ":" + port + "...";
		client = new Client(hostName, port);
		mode = Mode.JOINED;
	}

	/** Tears everything down and goes back to being on our own. */
	public static void leave() {
		if (host != null) {
			host.close();
			host = null;
		}
		if (client != null) {
			client.close();
			client = null;
		}
		mode = Mode.OFF;
		members = List.of();
		status = "";
		pendingTravel = null;
		pendingLeave = false;
		leaveDueAt = 0L;
		hostServer = null;
	}

	/**
	 * Announces the server the party should move to. Only the host can do this; a member joining a
	 * server on their own does not drag everyone else along.
	 */
	public static void announceTravel(String serverAddress) {
		if (mode != Mode.HOSTING || host == null) return;

		// The host is travelling either way, so they also come back to the finder afterwards.
		travelled = true;

		// A trip cancels a pending departure: leaving the old world is the first half of this.
		leaveDueAt = 0L;

		hostServer = serverAddress;

		if (!ScannerConfig.get().partyFollowJoin) return;
		host.broadcast("GOTO\t" + serverAddress);
	}

	/**
	 * Starts the clock on telling members the host has gone.
	 *
	 * <p>Leaving a world and moving to another one look identical from here, and the order settles
	 * it: the finder is only reachable outside a world, so a hop is always leave-then-travel. The
	 * previous version suppressed the message for a while <em>after</em> a trip was announced, which
	 * is the wrong half of the sequence, so every hop threw the party off the old server a moment
	 * before inviting them to the new one.
	 *
	 * <p>Deferring instead gets it right both ways round. Announcing a trip cancels this, so a hop
	 * is silent; nothing cancelling it means the host really has gone, and it fires.
	 */
	public static void announceLeave() {
		if (mode != Mode.HOSTING || host == null) return;
		if (!ScannerConfig.get().partyFollowLeave) return;
		leaveDueAt = System.currentTimeMillis() + leaveDelayMillis();
	}

	/**
	 * How long to wait before deciding the host is not simply on their way somewhere else.
	 *
	 * <p>Long enough to cover auto-join's own pause between attempts, or a hop would be announced as
	 * a departure whenever that pause was set high.
	 */
	private static long leaveDelayMillis() {
		long autoJoinGap = ScannerConfig.get().autoJoinDelaySeconds * 1000L;
		return Math.max(MIN_LEAVE_DELAY_MS, autoJoinGap + 5_000L);
	}

	/** Pumps queued work on the client thread. */
	public static void tick(Minecraft minecraft) {
		if (leaveDueAt != 0L) {
			if (minecraft.level != null) {
				// Somewhere new already, so that was a hop after all.
				leaveDueAt = 0L;
			} else if (System.currentTimeMillis() >= leaveDueAt) {
				leaveDueAt = 0L;
				if (mode == Mode.HOSTING && host != null) host.broadcast("LEAVE");
			}
		}

		if (pendingLeave) {
			pendingLeave = false;
			if (minecraft.level != null) {
				minecraft.level.disconnect(net.minecraft.network.chat.Component.translatable("randomserverfinder.the_party_host_left"));
			}
		}

		String travel = pendingTravel;
		if (travel == null) return;
		pendingTravel = null;

		if (minecraft.level != null) {
			// Already playing; disconnect first so the follow is not ignored.
			minecraft.level.disconnect(net.minecraft.network.chat.Component.translatable("randomserverfinder.following_the_party"));
		}
		travelled = true;
		PartyTravel.connect(minecraft, travel);
	}

	/** The configured member limit, held inside what the protocol can carry. */
	private static int maxMembers() {
		return Math.max(1, Math.min(MAX_MEMBERS, ScannerConfig.get().partyMaxMembers));
	}

	private static void updateMembers() {
		List<String> names = new ArrayList<>();
		String self = selfName();
		if (mode == Mode.HOSTING) {
			names.add(self + " (host)");
			if (host != null) names.addAll(host.memberNames());
		} else if (mode == Mode.JOINED && client != null) {
			names.addAll(client.knownMembers);
		}
		members = Collections.unmodifiableList(names);
	}

	private static String selfName() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft == null || minecraft.getUser() == null ? "Player" : minecraft.getUser().getName();
	}

	// --- Host ----------------------------------------------------------------------------------

	/** Accepts connections and relays the host's travel announcements to everyone. */
	private static final class Host {
		private final ServerSocket serverSocket;
		private final List<Member> connected = new CopyOnWriteArrayList<>();
		private volatile boolean closed;

		Host(int port) throws IOException {
			serverSocket = new ServerSocket();
			serverSocket.setReuseAddress(true);
			serverSocket.bind(new InetSocketAddress(port), MAX_MEMBERS);
			Thread accepter = new Thread(this::acceptLoop, "party-host");
			accepter.setDaemon(true);
			accepter.start();
		}

		private void acceptLoop() {
			while (!closed) {
				try {
					Socket socket = serverSocket.accept();
					if (ScannerConfig.get().partyLocked || connected.size() >= maxMembers()) {
						socket.close();
						continue;
					}
					Member member = new Member(socket);
					connected.add(member);
					member.start();
				} catch (IOException e) {
					if (!closed) ServerScannerMod.LOGGER.debug("Party accept failed", e);
					return;
				}
			}
		}

		void broadcast(String line) {
			for (Member member : connected) {
				member.send(line);
			}
		}

		List<String> memberNames() {
			List<String> names = new ArrayList<>();
			for (Member member : connected) {
				if (member.name != null) names.add(member.name);
			}
			return names;
		}

		void remove(Member member) {
			connected.remove(member);
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft != null) minecraft.execute(PartyManager::updateMembers);
			broadcast(rosterLine());
		}

		String rosterLine() {
			StringBuilder sb = new StringBuilder("MEMBERS\t").append(selfName()).append(" (host)");
			for (String name : memberNames()) sb.append('\t').append(name);
			return sb.toString();
		}

		void close() {
			closed = true;
			for (Member member : connected) member.close();
			connected.clear();
			try {
				serverSocket.close();
			} catch (IOException ignored) {
				// Closing a socket that is already shut is not worth reporting.
			}
		}

		/** One connected friend. */
		private final class Member {
			private final Socket socket;
			private BufferedWriter out;
			volatile String name;

			Member(Socket socket) {
				this.socket = socket;
			}

			void start() {
				Thread thread = new Thread(this::readLoop, "party-member");
				thread.setDaemon(true);
				thread.start();
			}

			private void readLoop() {
				try (Socket open = socket;
						BufferedReader in = new BufferedReader(
								new InputStreamReader(open.getInputStream(), StandardCharsets.UTF_8))) {
					out = new BufferedWriter(new OutputStreamWriter(open.getOutputStream(), StandardCharsets.UTF_8));

					String line;
					while ((line = in.readLine()) != null) {
						if (line.length() > MAX_LINE_LENGTH) continue;
						String[] parts = line.split("\t");
						if (parts.length >= 2 && parts[0].equals("HELLO")) {
							String at = hostServer;
							if (at != null) send("AT\t" + at);
							name = sanitise(parts[1]);
							Minecraft minecraft = Minecraft.getInstance();
							if (minecraft != null) minecraft.execute(PartyManager::updateMembers);
							broadcast(rosterLine());
						}
					}
				} catch (IOException e) {
					ServerScannerMod.LOGGER.debug("Party member dropped", e);
				} finally {
					remove(this);
				}
			}

			void send(String line) {
				BufferedWriter writer = out;
				if (writer == null) return;
				try {
					writer.write(line);
					writer.write('\n');
					writer.flush();
				} catch (IOException e) {
					close();
				}
			}

			void close() {
				try {
					socket.close();
				} catch (IOException ignored) {
					// Already gone.
				}
			}
		}
	}

	// --- Client --------------------------------------------------------------------------------

	/** Our end of someone else's party. */
	private static final class Client {
		private final List<String> knownMembers = new CopyOnWriteArrayList<>();
		private volatile Socket socket;
		private volatile boolean closed;

		Client(String hostName, int port) {
			Thread thread = new Thread(() -> run(hostName, port), "party-client");
			thread.setDaemon(true);
			thread.start();
		}

		private void run(String hostName, int port) {
			try (Socket open = new Socket()) {
				socket = open;
				open.connect(new InetSocketAddress(hostName, port), 8000);

				BufferedWriter out = new BufferedWriter(
						new OutputStreamWriter(open.getOutputStream(), StandardCharsets.UTF_8));
				out.write("HELLO\t" + sanitise(selfName()));
				out.write('\n');
				out.flush();

				status = "Connected to " + hostName;

				BufferedReader in = new BufferedReader(
						new InputStreamReader(open.getInputStream(), StandardCharsets.UTF_8));
				String line;
				while (!closed && (line = in.readLine()) != null) {
					if (line.length() > MAX_LINE_LENGTH) continue;
					handle(line);
				}
			} catch (IOException e) {
				if (!closed) {
					error = "Lost the party connection: " + e.getMessage();
					status = "";
				}
			} finally {
				if (!closed) {
					Minecraft minecraft = Minecraft.getInstance();
					if (minecraft != null) minecraft.execute(PartyManager::leave);
				}
			}
		}

		private void handle(String line) {
			String[] parts = line.split("\t");
			switch (parts[0]) {
				case "MEMBERS" -> {
					knownMembers.clear();
					for (int i = 1; i < parts.length && i <= MAX_MEMBERS; i++) {
						knownMembers.add(sanitise(parts[i]));
					}
					Minecraft minecraft = Minecraft.getInstance();
					if (minecraft != null) minecraft.execute(PartyManager::updateMembers);
				}
				case "LEAVE" -> {
					// Applied on the client thread; a world cannot be torn down from here.
					pendingLeave = true;
				}
				case "AT" -> {
					// Where the party already is. Unlike GOTO this does not move anyone; it only
					// answers "where is everyone" for someone who just connected or just left.
					if (parts.length >= 2) hostServer = sanitise(parts[1]);
				}
				case "GOTO" -> {
					if (parts.length >= 2) {
						hostServer = sanitise(parts[1]);
						// Applied on the client thread; screens cannot be changed from here.
						pendingTravel = sanitise(parts[1]);
					}
				}
				default -> {
					// Unknown message from a newer version; ignoring is the friendly thing to do.
				}
			}
		}

		void close() {
			closed = true;
			Socket open = socket;
			if (open != null) {
				try {
					open.close();
				} catch (IOException ignored) {
					// Already gone.
				}
			}
		}
	}

	/** Strips control characters so a peer cannot inject formatting or newlines into our UI. */
	private static String sanitise(String value) {
		String trimmed = value.replaceAll("[\\p{Cntrl}§]", "").trim();
		return trimmed.length() > 48 ? trimmed.substring(0, 48) : trimmed;
	}
}
