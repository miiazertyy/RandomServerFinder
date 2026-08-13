package com.serverscanner.screen;

import net.minecraft.util.Identifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Builds a vanilla {@link Identifier} on every version this source tree covers.
 *
 * <p>1.20.x has a public {@code Identifier(String)} constructor. 1.21 made it private and moved the
 * job to a static factory, so one expression cannot compile against both, and this tree serves
 * 1.20.1, 1.20.6 and 1.21.1 alike.
 *
 * <p>Resolved once by shape rather than by name: a public constructor taking a single string, or
 * failing that a public static method taking a single string and handing back an Identifier. The
 * mod ships remapped, so the factory's name in this source does not exist at runtime, but its
 * signature survives.
 */
final class Ids {
	private static boolean resolved;
	private static Constructor<Identifier> constructor;
	private static Method factory;

	private Ids() {
	}

	/** The identifier for {@code path} in the vanilla namespace. */
	static Identifier of(String path) {
		resolve();
		try {
			if (constructor != null) return constructor.newInstance(path);
			if (factory != null) return (Identifier) factory.invoke(null, path);
		} catch (Exception e) {
			throw new IllegalStateException("Could not build the identifier " + path, e);
		}
		throw new IllegalStateException("No way to build an Identifier on this version");
	}

	private static synchronized void resolve() {
		if (resolved) return;
		resolved = true;

		try {
			Constructor<Identifier> candidate = Identifier.class.getConstructor(String.class);
			if (Modifier.isPublic(candidate.getModifiers())) {
				constructor = candidate;
				return;
			}
		} catch (NoSuchMethodException e) {
			// 1.21 and later; the factory below is the way in.
		}

		for (Method method : Identifier.class.getMethods()) {
			if (!Modifier.isStatic(method.getModifiers())) continue;
			if (method.getReturnType() != Identifier.class) continue;

			Class<?>[] parameters = method.getParameterTypes();
			if (parameters.length == 1 && parameters[0] == String.class) {
				factory = method;
				return;
			}
		}
	}
}
