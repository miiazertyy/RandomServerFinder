package com.serverscanner.filter;

/**
 * A boolean filter that can also be "not applied at all".
 *
 * <p>The website models boolean filters as checkboxes that only exist once you add the filter,
 * so a filter has three meaningful states rather than two.
 */
public enum TriState {
	UNSET,
	TRUE,
	FALSE;

	public boolean isSet() {
		return this != UNSET;
	}

	public boolean toBoolean() {
		return this == TRUE;
	}

	/** Cycles UNSET -> TRUE -> FALSE -> UNSET, for click-through UI toggles. */
	public TriState next() {
		return switch (this) {
			case UNSET -> TRUE;
			case TRUE -> FALSE;
			case FALSE -> UNSET;
		};
	}
}
