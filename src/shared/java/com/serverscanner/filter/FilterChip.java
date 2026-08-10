package com.serverscanner.filter;

/**
 * One entry in the row of active filters: a pictogram, and the value it was given.
 *
 * <p>The row used to spell each filter out — "players = 1", "port = 25565" — which cost more width
 * than the results underneath it and pushed the row into wrapping once three filters were set. The
 * icon carries the field and the value is the only part that still has to be read.
 *
 * <p>A filter that is merely on or off has no value to show. Rather than captioning those, the icon
 * is struck through when the filter demands the <em>absence</em> of the thing, the way a no-entry
 * sign works — the only way to distinguish "full" from "not full" in ten pixels.
 */
public record FilterChip(String[] icon, String value, boolean struck) {
	/** Long values are cut rather than allowed to push the row off both edges of the screen. */
	private static final int MAX_VALUE = 18;

	/** A filter with a value worth reading, such as a version pattern or a port. */
	public static FilterChip of(String[] icon, String value) {
		String trimmed = value.trim();
		if (trimmed.length() > MAX_VALUE) {
			trimmed = trimmed.substring(0, MAX_VALUE - 1) + "…";
		}
		return new FilterChip(icon, trimmed, false);
	}

	/** A filter that is only ever required or forbidden, so the icon alone says it. */
	public static FilterChip flag(String[] icon, boolean required) {
		return new FilterChip(icon, "", !required);
	}
}
