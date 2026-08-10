package com.serverscanner.party;

import com.serverscanner.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * Works out the address friends should connect to when hosting a party.
 *
 * <p>Found from the machine's own network interfaces rather than by asking a website, so this costs
 * nothing and tells no one that you are hosting. That does mean it is the address on your
 * <em>local</em> network: friends elsewhere need your public address and the port forwarded, which
 * is the same as hosting any Minecraft server.
 */
public final class LocalAddress {
	private static String cached;

	private LocalAddress() {
	}

	/**
	 * The best guess at this machine's address on the local network, or null if none was found.
	 *
	 * <p>Prefers a site-local IPv4 — the 192.168.x / 10.x / 172.16-31.x ranges a home router hands
	 * out — over anything else, and skips loopback, virtual and inactive interfaces so VPN and
	 * container adapters don't win.
	 */
	public static String get() {
		if (cached != null) {
			return cached;
		}
		try {
			String fallback = null;
			for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
				if (!network.isUp() || network.isLoopback() || network.isVirtual()) {
					continue;
				}
				for (InetAddress address : Collections.list(network.getInetAddresses())) {
					if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) {
						continue;
					}
					if (address.isSiteLocalAddress()) {
						cached = address.getHostAddress();
						return cached;
					}
					if (fallback == null) {
						fallback = address.getHostAddress();
					}
				}
			}
			cached = fallback;
		} catch (Exception e) {
			Log.LOGGER.debug("Could not determine the local address", e);
		}
		return cached;
	}

	/** The address with the port, ready to hand to a friend, or null if the address is unknown. */
	public static String withPort(int port) {
		String host = get();
		return host == null ? null : host + ":" + port;
	}
}
