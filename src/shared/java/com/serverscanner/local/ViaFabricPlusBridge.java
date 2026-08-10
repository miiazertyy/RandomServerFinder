package com.serverscanner.local;

import com.serverscanner.Log;

import java.lang.reflect.Method;

/**
 * Tells ViaFabricPlus which version a server speaks, when ViaFabricPlus is installed.
 *
 * <p>ViaFabricPlus keeps a per-server target version, set from the little version button it adds to
 * each row of the vanilla server list. Servers found here never pass through that list, so they
 * never got one, and connecting fell back to the global target version — which for most people is
 * their own release. Join anything older and the server rejects the handshake as an incompatible
 * version, with ViaFabricPlus sitting right there having been told nothing was being translated.
 *
 * <p>The version is already known: it came back in the ping that put the server on screen. Handing
 * it over is the same thing choosing it by hand in the server list would do, minus the choosing.
 *
 * <p>Reached by reflection, and quietly inert when the classes are absent, so this stays a mod that
 * works on its own and cooperates with ViaFabricPlus rather than depending on it.
 */
public final class ViaFabricPlusBridge {
	private static final String SERVER_DATA =
			"com.viaversion.viafabricplus.injection.access.base.IServerData";
	private static final String PROTOCOL_VERSION =
			"com.viaversion.viaversion.api.protocol.version.ProtocolVersion";

	private static boolean resolved;
	private static boolean available;

	private static Class<?> serverDataType;
	private static Method forceVersion;
	private static Method getProtocol;
	private static Method isKnown;

	private ViaFabricPlusBridge() {
	}

	/**
	 * Whether ViaFabricPlus is present and its API is the shape we expect.
	 *
	 * <p>Used to decide whether a server on another version is worth flagging. With translation
	 * available almost none of them are, so the warning is noise.
	 */
	public static boolean isAvailable() {
		return resolve();
	}

	/**
	 * Marks a server as speaking {@code protocol}, so ViaFabricPlus translates to it on connect.
	 *
	 * @param serverInfo the game's own server entry — typed as Object because its class differs
	 *                   between the Minecraft versions this mod builds against
	 * @param protocol   the protocol number the server reported when pinged
	 */
	public static void forceVersion(Object serverInfo, int protocol) {
		if (protocol <= 0 || serverInfo == null) return;
		if (!resolve()) return;
		if (!serverDataType.isInstance(serverInfo)) return;

		try {
			Object version = getProtocol.invoke(null, protocol);
			// An unrecognised protocol is one ViaFabricPlus has no translation for. Forcing it would
			// replace a clear rejection with a connection that fails further in, so leave it be.
			if (version == null || !(boolean) isKnown.invoke(version)) return;

			forceVersion.invoke(serverInfo, version);
		} catch (Exception e) {
			available = false;
			Log.LOGGER.warn("Could not hand the server version to ViaFabricPlus: {}", e.toString());
		}
	}

	/**
	 * Marks a server as Bedrock, so ViaFabricPlus translates to it rather than to a Java version.
	 *
	 * <p>Its numeric protocol means nothing to the Java table: 2168 is a Bedrock release, not a
	 * Minecraft one, so looking it up the usual way finds the wrong thing or nothing. ViaBedrock
	 * publishes the version to use as a constant, and being a library class rather than a Minecraft
	 * one, its name survives remapping and can simply be looked up.
	 */
	public static void forceBedrock(Object serverInfo) {
		if (serverInfo == null || !resolve()) return;
		if (!serverDataType.isInstance(serverInfo)) return;

		try {
			Class<?> bedrock = Class.forName("net.raphimc.viabedrock.api.BedrockProtocolVersion",
					false, ViaFabricPlusBridge.class.getClassLoader());
			forceVersion.invoke(serverInfo, bedrock.getField("bedrockLatest").get(null));
		} catch (ClassNotFoundException | NoSuchFieldException e) {
			// ViaFabricPlus without its Bedrock half; joining will fail, finding them still worked.
			Log.LOGGER.warn("ViaFabricPlus has no Bedrock support, so that server cannot be joined");
		} catch (Exception e) {
			Log.LOGGER.warn("Could not select the Bedrock version: {}", e.toString());
		}
	}

	private static synchronized boolean resolve() {
		if (resolved) return available;
		resolved = true;

		try {
			ClassLoader loader = ViaFabricPlusBridge.class.getClassLoader();
			serverDataType = Class.forName(SERVER_DATA, false, loader);
			Class<?> protocolVersion = Class.forName(PROTOCOL_VERSION, false, loader);

			// Named with the mod's own prefix, which is how a mixin-added method avoids colliding
			// with the class it is added to, so these names are not remapped and are safe to look up.
			forceVersion = serverDataType.getMethod("viaFabricPlus$forceVersion", protocolVersion);
			getProtocol = protocolVersion.getMethod("getProtocol", int.class);
			isKnown = protocolVersion.getMethod("isKnown");

			available = true;
			Log.LOGGER.info("ViaFabricPlus found; servers will be joined on the version they report");
		} catch (ClassNotFoundException e) {
			// Not installed. Normal, and not worth a line in the log.
			available = false;
		} catch (Exception e) {
			available = false;
			Log.LOGGER.warn("ViaFabricPlus is installed but its API did not look as expected: {}",
					e.toString());
		}
		return available;
	}
}
