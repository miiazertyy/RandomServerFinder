package com.serverscanner.local;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * Pings a Bedrock Edition server.
 *
 * <p>Bedrock runs on RakNet over UDP and answers an "unconnected ping" with a single semicolon
 * separated line. None of that is Minecraft's Java protocol, so the game's own pinger cannot see
 * these servers at all: point it at one and it times out. Twenty pounds of protocol for six fields,
 * but it is the only way to tell whether a Bedrock address is alive.
 *
 * <p>Blocking, because it is called from the same pool that runs the Java pings.
 */
public final class BedrockPinger {
	/** The fixed RakNet "offline message" bytes, which mark a packet as unconnected. */
	private static final byte[] MAGIC = {
			0x00, (byte) 0xFF, (byte) 0xFF, 0x00, (byte) 0xFE, (byte) 0xFE, (byte) 0xFE, (byte) 0xFE,
			(byte) 0xFD, (byte) 0xFD, (byte) 0xFD, (byte) 0xFD, 0x12, 0x34, 0x56, 0x78,
	};

	private static final byte UNCONNECTED_PING = 0x01;
	private static final byte UNCONNECTED_PONG = 0x1C;

	/** Header before the string: id, send time, server GUID, magic, then a short length. */
	private static final int PONG_HEADER = 1 + 8 + 8 + MAGIC.length + 2;

	private static final Random RANDOM = new Random();

	private BedrockPinger() {
	}

	/**
	 * What a Bedrock server reports.
	 *
	 * <p>The line carries more than this, most of it useless here: a GUID, the two ports it listens
	 * on, and a couple of unlabelled trailing numbers that not every server sends.
	 */
	public record Pong(String name, String levelName, int protocol, String version, int online, int max) {
	}

	/**
	 * Sends one ping and waits for the reply.
	 *
	 * @throws IOException if the socket fails or nothing answers in time
	 */
	public static Pong ping(String host, int port, int timeoutMs) throws IOException {
		byte[] request = ByteBuffer.allocate(1 + 8 + MAGIC.length + 8)
				.put(UNCONNECTED_PING)
				.putLong(System.currentTimeMillis())
				.put(MAGIC)
				.putLong(RANDOM.nextLong())
				.array();

		byte[] buffer = new byte[4096];
		try (DatagramSocket socket = new DatagramSocket()) {
			socket.setSoTimeout(timeoutMs);
			InetAddress target = InetAddress.getByName(host);
			socket.send(new DatagramPacket(request, request.length, target, port));

			DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
			socket.receive(reply);
			return parse(buffer, reply.getLength());
		}
	}

	private static Pong parse(byte[] data, int length) throws IOException {
		if (length < PONG_HEADER || data[0] != UNCONNECTED_PONG) {
			throw new IOException("Not a Bedrock pong");
		}

		int declared = ((data[PONG_HEADER - 2] & 0xFF) << 8) | (data[PONG_HEADER - 1] & 0xFF);
		// Trust the packet's own length over the declared one; some servers overstate it.
		int available = Math.min(declared, length - PONG_HEADER);
		if (available <= 0) {
			throw new IOException("Empty Bedrock pong");
		}

		String line = new String(data, PONG_HEADER, available, StandardCharsets.UTF_8);
		String[] parts = line.split(";", -1);

		// MCPE;name;protocol;version;online;max;guid;level;gamemode;...
		return new Pong(
				field(parts, 1),
				field(parts, 7),
				number(parts, 2),
				field(parts, 3),
				number(parts, 4),
				number(parts, 5));
	}

	private static String field(String[] parts, int index) {
		if (index >= parts.length) return "";
		// Some servers wrap a name containing spaces in quotes; they are not part of it.
		String value = parts[index].trim();
		if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
			value = value.substring(1, value.length() - 1);
		}
		return value;
	}

	private static int number(String[] parts, int index) {
		try {
			return Integer.parseInt(field(parts, index));
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
