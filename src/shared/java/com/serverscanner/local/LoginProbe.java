package com.serverscanner.local;

import com.serverscanner.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Works out whether a server is online-mode, cracked, whitelisted or banning you — none of which a
 * status ping can tell you.
 *
 * <p>The regular ping only asks for the MOTD. Authentication mode and whitelists are only revealed
 * once you begin <em>logging in</em>, so this opens a real login handshake and reads the first
 * reply:
 *
 * <ul>
 *   <li>an encryption request means the server verifies accounts — it is online-mode;</li>
 *   <li>a disconnect carries the reason, which is where "You are not white-listed" appears;</li>
 *   <li>getting through to login success means the server is offline-mode and let us in.</li>
 * </ul>
 *
 * <p>The connection is dropped the instant that first packet arrives, before the join is completed,
 * so this is a handshake rather than an actual visit. It runs only when you ask for a specific
 * server — running it across the whole list would be hammering thousands of machines for
 * information they did not offer.
 */
public final class LoginProbe {
	private static final int TIMEOUT_MS = 6000;

	/** Packet ids in the login state. */
	private static final int S2C_DISCONNECT = 0x00;
	private static final int S2C_ENCRYPTION_REQUEST = 0x01;
	private static final int S2C_LOGIN_SUCCESS = 0x02;
	private static final int S2C_SET_COMPRESSION = 0x03;

	private LoginProbe() {
	}

	public enum Result {
		/** Server verifies accounts against Mojang. A whitelist cannot be checked without logging in for real. */
		ONLINE_MODE("Premium (online-mode)", "Requires a paid account. Whitelist can't be checked without joining."),
		/** No authentication, and it accepted us — so no whitelist is blocking. */
		CRACKED_OPEN("Cracked, open", "No account check and no whitelist, so anyone can join."),
		/** No authentication, but a whitelist turned us away. */
		WHITELISTED("Whitelisted", "The server has a whitelist and you are not on it."),
		/** We are banned from this server. */
		BANNED("Banned", "This server has banned you or your address."),
		/** The server runs a different Minecraft version. */
		WRONG_VERSION("Wrong version", "The server runs a different Minecraft version."),
		/** No free player slots. */
		FULL("Server full", "Every player slot is taken."),
		/** Needs mods the client does not have. */
		NEEDS_MODS("Needs mods", "The server requires mods you do not have installed."),
		/** Turned away for some other reason. */
		REJECTED("Rejected", "The server refused the connection."),
		/** Could not tell. */
		UNKNOWN("Unknown", "No usable answer from the server.");

		public final String label;
		public final String detail;

		Result(String label, String detail) {
			this.label = label;
			this.detail = detail;
		}
	}

	public record Report(Result result, String reason) {
	}

	/**
	 * Probes a server.
	 *
	 * @param address  host, without the port
	 * @param port     the port
	 * @param protocol protocol version to claim; use the running client's so the answer is relevant
	 * @param username name to offer — the player's own, since that is who would be joining
	 */
	public static CompletableFuture<Report> probe(String address, int port, int protocol, String username) {
		return CompletableFuture.supplyAsync(() -> {
			try (Socket socket = new Socket()) {
				socket.setSoTimeout(TIMEOUT_MS);
				socket.connect(new InetSocketAddress(address, port), TIMEOUT_MS);

				OutputStream rawOut = socket.getOutputStream();
				InputStream rawIn = socket.getInputStream();

				sendHandshake(rawOut, address, port, protocol);
				sendLoginStart(rawOut, username);

				return readReply(new DataInputStream(rawIn));
			} catch (Exception e) {
				Log.LOGGER.debug("Login probe failed for {}:{}", address, port, e);
				return new Report(Result.UNKNOWN, e.getMessage() == null ? "No response" : e.getMessage());
			}
		});
	}

	private static Report readReply(DataInputStream in) throws IOException {
		// Skip compression negotiation; it says nothing about authentication.
		for (int attempt = 0; attempt < 3; attempt++) {
			int length = readVarInt(in);
			if (length <= 0) return new Report(Result.UNKNOWN, "Empty response");

			byte[] payload = new byte[length];
			in.readFully(payload);

			DataInputStream packet = new DataInputStream(new java.io.ByteArrayInputStream(payload));
			int id = readVarInt(packet);

			switch (id) {
				case S2C_SET_COMPRESSION -> {
					// Nothing learned yet; the real answer is in the next packet. Compression only
					// applies from here on, so anything after this we cannot read — but in practice
					// servers send disconnect or encryption request first.
					continue;
				}
				case S2C_ENCRYPTION_REQUEST -> {
					return new Report(Result.ONLINE_MODE, Result.ONLINE_MODE.detail);
				}
				case S2C_LOGIN_SUCCESS -> {
					return new Report(Result.CRACKED_OPEN, Result.CRACKED_OPEN.detail);
				}
				case S2C_DISCONNECT -> {
					String raw = readString(packet, 32767);
					Result result = classify(raw);
					String reason = describe(raw, result);
					return new Report(result, reason.isBlank() ? result.detail : reason);
				}
				default -> {
					return new Report(Result.UNKNOWN, "Unexpected packet 0x" + Integer.toHexString(id));
				}
			}
		}
		return new Report(Result.UNKNOWN, "No usable answer");
	}

	/**
	 * Works out why we were turned away.
	 *
	 * <p>Vanilla sends a <em>translation key</em> rather than English — a real kick looks like
	 * {@code {"translate":"multiplayer.disconnect.not_whitelisted"}} — so the keys are checked first
	 * and are reliable regardless of the player's language. Only then does it fall back to matching
	 * words, which is all that is possible for the plugins that send their own custom text.
	 */
	private static Result classify(String raw) {
		String lower = raw.toLowerCase(Locale.ROOT);

		if (lower.contains("multiplayer.disconnect.not_whitelisted")) return Result.WHITELISTED;
		if (lower.contains("multiplayer.disconnect.banned")) return Result.BANNED;
		if (lower.contains("multiplayer.disconnect.server_full")) return Result.FULL;
		if (lower.contains("multiplayer.disconnect.incompatible")
				|| lower.contains("multiplayer.disconnect.outdated_client")
				|| lower.contains("multiplayer.disconnect.outdated_server")) {
			return Result.WRONG_VERSION;
		}

		// Custom messages from plugins, which can say anything at all.
		if (lower.contains("whitelist") || lower.contains("white-list") || lower.contains("white list")) {
			return Result.WHITELISTED;
		}
		if (lower.contains("banned") || lower.contains("blacklist")) return Result.BANNED;
		if (lower.contains("server is full") || lower.contains("server full")) return Result.FULL;
		if (lower.contains("require") && lower.contains("forge")) return Result.NEEDS_MODS;
		if (lower.contains("mods that require") || lower.contains("modpack")) return Result.NEEDS_MODS;
		if (lower.contains("outdated") || lower.contains("version")) return Result.WRONG_VERSION;

		return Result.REJECTED;
	}

	/** Human-readable reason, preferring the server's own words over a bare translation key. */
	private static String describe(String raw, Result result) {
		String text = flatten(raw);
		if (text.startsWith("multiplayer.disconnect.")) {
			// A bare key is no use to the user; say it plainly, adding the version if one came with it.
			java.util.regex.Matcher with =
					java.util.regex.Pattern.compile("\"with\"\\s*:\\s*\\[\\s*\"([^\"]+)\"").matcher(raw);
			if (result == Result.WRONG_VERSION && with.find()) {
				return "Server runs " + with.group(1);
			}
			return result.detail;
		}
		return text;
	}

	/** Disconnect reasons are chat components; pull the readable text out without a full parser. */
	private static String flatten(String json) {
		if (json == null) return "";
		StringBuilder out = new StringBuilder();
		java.util.regex.Matcher matcher =
				java.util.regex.Pattern.compile("\"(?:text|translate)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
						.matcher(json);
		while (matcher.find()) {
			if (out.length() > 0) out.append(' ');
			out.append(matcher.group(1).replace("\\n", " ").replace("\\\"", "\""));
		}
		String text = out.length() > 0 ? out.toString() : json;
		return text.replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim();
	}

	// --- Protocol writing ----------------------------------------------------------------------

	private static void sendHandshake(OutputStream out, String address, int port, int protocol)
			throws IOException {
		java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
		DataOutputStream data = new DataOutputStream(body);
		writeVarInt(data, 0x00);
		writeVarInt(data, protocol);
		writeString(data, address);
		data.writeShort(port);
		writeVarInt(data, 2); // next state: login
		writePacket(out, body.toByteArray());
	}

	private static void sendLoginStart(OutputStream out, String username) throws IOException {
		java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
		DataOutputStream data = new DataOutputStream(body);
		writeVarInt(data, 0x00);
		writeString(data, username);
		UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
		data.writeLong(uuid.getMostSignificantBits());
		data.writeLong(uuid.getLeastSignificantBits());
		writePacket(out, body.toByteArray());
	}

	private static void writePacket(OutputStream out, byte[] body) throws IOException {
		java.io.ByteArrayOutputStream framed = new java.io.ByteArrayOutputStream();
		DataOutputStream data = new DataOutputStream(framed);
		writeVarInt(data, body.length);
		data.write(body);
		out.write(framed.toByteArray());
		out.flush();
	}

	private static void writeString(DataOutputStream out, String value) throws IOException {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		writeVarInt(out, bytes.length);
		out.write(bytes);
	}

	private static void writeVarInt(DataOutputStream out, int value) throws IOException {
		while ((value & ~0x7F) != 0) {
			out.writeByte((value & 0x7F) | 0x80);
			value >>>= 7;
		}
		out.writeByte(value);
	}

	private static int readVarInt(DataInputStream in) throws IOException {
		int result = 0;
		for (int i = 0; i < 5; i++) {
			int read = in.read();
			if (read == -1) throw new EOFException("Connection closed");
			result |= (read & 0x7F) << (i * 7);
			if ((read & 0x80) == 0) return result;
		}
		throw new IOException("VarInt too long");
	}

	private static String readString(DataInputStream in, int max) throws IOException {
		int length = readVarInt(in);
		if (length < 0 || length > max * 4) throw new IOException("String too long");
		byte[] bytes = new byte[length];
		in.readFully(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}
}
