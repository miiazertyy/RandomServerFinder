package com.serverscanner.local;

import com.serverscanner.ServerScannerMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.multiplayer.ServerData;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Starts a server ping across Minecraft versions.
 *
 * <p>1.21.11 added a {@code NetworkingBackend} argument to that method. Referring to either shape
 * directly would pin the mod to one version, so the right overload is looked up once by reflection
 * and reused. This is the only place the two differ, which is why it is worth the indirection —
 * everything else the mod touches has been stable.
 *
 * <p>The lookup deliberately ignores method names. The mod ships remapped, so the name it has in
 * source does not exist at runtime, and matching on one silently finds nothing in every installed
 * copy while working perfectly in a development client. The parameter types are matched instead.
 */
public final class PingerCompat {
	private static Method addMethod;
	private static Object networkingBackend;
	private static boolean resolved;

	/** What the lookup actually found, so a failure names the mismatch instead of just reporting one. */
	private static String signatures = "not looked up yet";

	private PingerCompat() {
	}

	/**
	 * Starts a ping.
	 *
	 * @param saver    run when the server sends a favicon
	 * @param onPong   run when the latency reply arrives
	 * @throws Exception whatever the underlying call throws, including {@code UnknownHostException}
	 */
	public static void add(ServerStatusPinger pinger, ServerData info, Runnable saver, Runnable onPong)
			throws Exception {
		resolve();
		if (addMethod == null) {
			throw new IllegalStateException(
					"No usable ServerStatusPinger.add on this version; candidates were: " + signatures);
		}

		try {
			if (networkingBackend != null) {
				addMethod.invoke(pinger, info, saver, onPong, networkingBackend);
			} else {
				addMethod.invoke(pinger, info, saver, onPong);
			}
		} catch (InvocationTargetException e) {
			// Surface the real failure (UnknownHostException and friends) rather than the wrapper.
			Throwable cause = e.getCause();
			if (cause instanceof Exception real) throw real;
			throw e;
		}
	}

	/** Public methods first, then declared ones, in case a mixin has changed the method's visibility. */
	private static java.util.List<Method> candidates() {
		java.util.List<Method> all = new java.util.ArrayList<>();
		java.util.Collections.addAll(all, ServerStatusPinger.class.getMethods());
		for (Method declared : ServerStatusPinger.class.getDeclaredMethods()) {
			if (!all.contains(declared)) all.add(declared);
		}
		return all;
	}

	private static synchronized void resolve() {
		if (resolved) return;
		resolved = true;

		Method threeArg = null;
		StringBuilder seen = new StringBuilder();

		for (Method method : candidates()) {
			Class<?>[] parameters = method.getParameterTypes();
			seen.append(' ').append(describe(method));

			// Matched on shape, never on name: the installed jar is remapped, so at runtime this
			// method is called something like method_2998. Class references are remapped with it,
			// which is what makes the parameter types a reliable thing to recognise it by.
			if (!looksLikePing(parameters)) continue;
			method.setAccessible(true);

			if (parameters.length == 4) {
				Object backend = createBackend(parameters[3]);
				if (backend != null) {
					addMethod = method;
					networkingBackend = backend;
					return;
				}
			} else if (threeArg == null) {
				threeArg = method;
			}
		}

		// Only settle for the older signature once every four-argument candidate has been ruled out —
		// an earlier version of this loop let a failed candidate clear a good one it had already found.
		if (threeArg != null) {
			addMethod = threeArg;
			networkingBackend = null;
			return;
		}

		signatures = seen.length() == 0 ? "none" : seen.toString().trim();
	}

	/** {@code (ServerData, Runnable, Runnable)}, optionally followed by the backend argument. */
	private static boolean looksLikePing(Class<?>[] parameters) {
		if (parameters.length != 3 && parameters.length != 4) return false;
		return parameters[0].isAssignableFrom(ServerData.class)
				&& parameters[1] == Runnable.class
				&& parameters[2] == Runnable.class;
	}

	private static String describe(Method method) {
		StringBuilder out = new StringBuilder(method.getName()).append('(');
		Class<?>[] parameters = method.getParameterTypes();
		for (int i = 0; i < parameters.length; i++) {
			if (i > 0) out.append(", ");
			out.append(parameters[i].getSimpleName());
		}
		return out.append(')').toString();
	}

	/**
	 * Builds the {@code NetworkingBackend} the newer signature wants, honouring the game's setting.
	 *
	 * <p>Its factory is found by shape for the same reason the ping method is: the name it has in
	 * source is not the name it has once the mod is remapped and installed.
	 */
	private static Object createBackend(Class<?> backendType) {
		boolean nativeTransport = true;
		Minecraft client = Minecraft.getInstance();
		if (client != null && client.options != null) {
			nativeTransport = client.options.useNativeTransport();
		}

		Method noArgs = null;
		for (Method method : backendType.getMethods()) {
			if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
			if (!backendType.isAssignableFrom(method.getReturnType())) continue;

			Class<?>[] parameters = method.getParameterTypes();
			if (parameters.length == 1 && parameters[0] == boolean.class) {
				// The remote(useNativeTransport) factory — what pinging an internet server wants.
				Object backend = invokeFactory(method, nativeTransport);
				if (backend != null) return backend;
			} else if (parameters.length == 0 && noArgs == null) {
				noArgs = method;
			}
		}

		// Nothing took the transport flag, so fall back to whatever the default backend is.
		if (noArgs != null) return invokeFactory(noArgs);

		ServerScannerMod.LOGGER.warn("No factory found on {} for pinging", backendType.getName());
		return null;
	}

	private static Object invokeFactory(Method factory, Object... arguments) {
		try {
			return factory.invoke(null, arguments);
		} catch (Exception e) {
			ServerScannerMod.LOGGER.warn("Could not create a networking backend for pinging", e);
			return null;
		}
	}
}
