package com.serverscanner.local;

/**
 * How far a server's ping got, and how it ended.
 *
 * <p>1.20.2 and later carry this on {@code ServerInfo} as a {@code Status} enum. 1.20.1 has only a
 * bare {@code boolean online}, which cannot tell "still pinging" from "answered" from "refused", so
 * this tree tracks the state itself and the list reads it from here instead of from the server info.
 */
public enum PingState {
	/** Not pinged yet. */
	INITIAL,

	/** A ping is out and no reply has come back. */
	PINGING,

	/** Answered, and joinable. */
	SUCCESSFUL,

	/** Answered, but speaks a protocol nothing here can translate. */
	INCOMPATIBLE,

	/** Nothing answered, or the connection was refused. */
	UNREACHABLE
}
