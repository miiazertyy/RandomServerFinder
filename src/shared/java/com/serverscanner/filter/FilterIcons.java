package com.serverscanner.filter;

/**
 * Pictograms for the filter rows, so the list can be scanned by shape rather than read line by line.
 *
 * <p>These follow the conventions people already know — a magnifier for searching, an eye for what
 * is visible, a person for players — because a filter row is not the place to be inventive. They are
 * drawn here rather than shipped as images: character grids survive the mod being built against
 * Minecraft versions whose texture APIs differ, and rendering them needs nothing but a filled
 * rectangle, the one drawing call that is identical on every version.
 *
 * <p>10x10 rather than 8x8 — at eight pixels a person and a plug end up the same blob.
 *
 * <h2>Colour</h2>
 *
 * <p>{@code #} is {@link #DARK}, {@code o} is {@link #ACCENT}, {@code .} is transparent.
 *
 * <p>There is one rule, and it is worth stating because the earlier icons broke it constantly: an
 * icon is drawn in {@link #DARK}, and {@link #ACCENT} marks <em>the one part that names the filter</em>
 * — the ceiling a player cap presses against, the level in a container that makes it full, the
 * strongest bar in a signal. Where an icon has no such separable part, the whole thing is drawn in
 * {@link #DARK}. Colouring half of a magnifier, or every other line of a paragraph, tells the reader
 * there is a distinction to find and then offers none, which is worse than no colour at all.
 *
 * <p>{@link #VANILLA} is the one deliberate exception: its green is grass, not emphasis.
 */
public final class FilterIcons {
	public static final int DARK = 0xFFC8CCD4;
	public static final int ACCENT = 0xFF7FD1A0;

	public static final int SIZE = 10;

	private FilterIcons() {
	}

	/**
	 * A person: how many are on the server.
	 *
	 * <p>The shoulders taper into the torso rather than running the full width — squared off at the
	 * edges the body reads as a bar with a head resting on it instead of as somebody. Nothing here is
	 * the "filtered part", so it is a single colour.
	 */
	public static final String[] PLAYERS = {
			"...####...",
			"..######..",
			"..######..",
			"..######..",
			"...####...",
			"..######..",
			".########.",
			".########.",
			".########.",
			"..........",
	};

	/**
	 * A person pressed up against a ceiling: the cap on how many may join.
	 *
	 * <p>The same body as {@link #PLAYERS} so the pair are read as a set, with the accent on the
	 * ceiling — that bar is the whole difference between the two.
	 */
	public static final String[] PLAYER_CAP = {
			"oooooooooo",
			"oooooooooo",
			"..........",
			"...####...",
			"..######..",
			"..######..",
			"...####...",
			"..######..",
			".########.",
			".########.",
	};

	/** A container, with the level that makes it full picked out. */
	public static final String[] FULL = {
			"..........",
			"##########",
			"#........#",
			"#.oooooo.#",
			"#.oooooo.#",
			"#.oooooo.#",
			"#.oooooo.#",
			"#........#",
			"##########",
			"..........",
	};

	/** A list, with the players in it picked out: the roster shown on hover. */
	public static final String[] PLAYER_SAMPLE = {
			"..........",
			"oo.#######",
			"..........",
			"oo.#######",
			"..........",
			"oo.#######",
			"..........",
			"oo.#######",
			"..........",
			"..........",
	};

	/**
	 * A luggage tag: which build the server runs.
	 *
	 * <p>The hole is punched through rather than filled in. A solid dot in the same spot still reads
	 * as part of the body, which leaves the whole shape looking like an arrowhead; only an actual gap
	 * makes it a label with a string hole. Nothing here is the filtered part, so there is no accent.
	 */
	public static final String[] VERSION = {
			"..........",
			"....######",
			"...#######",
			"..########",
			".##..#####",
			".##..#####",
			"..########",
			"...#######",
			"....######",
			"..........",
	};

	/** A plug: the numeric protocol underneath the version name. */
	public static final String[] PROTOCOL = {
			"..##..##..",
			"..##..##..",
			".########.",
			".########.",
			".########.",
			"..######..",
			"...####...",
			"....##....",
			"....##....",
			"..........",
	};

	/** Lines of prose: the message of the day. */
	public static final String[] DESCRIPTION = {
			"..........",
			"##########",
			"..........",
			"########..",
			"..........",
			"##########",
			"..........",
			"######....",
			"..........",
			"..........",
	};

	/** A frame, with the picture inside it picked out: the server's custom icon. */
	public static final String[] FAVICON = {
			"##########",
			"#........#",
			"#..oo....#",
			"#........#",
			"#.....oo.#",
			"#...oooo.#",
			"#..ooooo.#",
			"##########",
			"..........",
			"..........",
	};

	/**
	 * A grass block: unmodded, nothing bolted on.
	 *
	 * <p>The plain box this replaced meant nothing on its own — a grass block is the one image that
	 * says "ordinary Minecraft" without a caption. The flecks keep the two bands from reading as a
	 * flag, and the green just under the soil line is the grass overhang the real block has. This is
	 * the one icon whose green is depiction rather than emphasis.
	 */
	public static final String[] VANILLA = {
			"..........",
			".oooooooo.",
			".oooooooo.",
			".oo#ooo#o.",
			".########.",
			".##o###o#.",
			".########.",
			".########.",
			".########.",
			"..........",
	};

	/** Linked nodes narrowing to one: a range of addresses. */
	public static final String[] SUBNET = {
			"##......##",
			"##......##",
			".#......#.",
			"..#....#..",
			"...####...",
			"...####...",
			"....##....",
			"..######..",
			"..######..",
			"..........",
	};

	/** A socket, with the pin holes picked out: the port number. */
	public static final String[] PORT = {
			"..........",
			".########.",
			".#......#.",
			".#.o..o.#.",
			".#.o..o.#.",
			".#......#.",
			".#......#.",
			".########.",
			"..........",
			"..........",
	};

	/** A grid: rows or cards. */
	public static final String[] LAYOUT = {
			"..........",
			"####.####.",
			"####.####.",
			"####.####.",
			"..........",
			"####.####.",
			"####.####.",
			"####.####.",
			"..........",
			"..........",
	};

	/**
	 * Body text with the heading picked out: what each row leads with.
	 *
	 * <p>Three body lines rather than two, so the heading is the smaller half. With only two short
	 * lines under it the accent outweighed the dark and the icon read as a green block with some
	 * debris beneath, which is the opposite of "the heading is the bit being chosen".
	 */
	public static final String[] HEADLINE = {
			"..........",
			"oooooooo..",
			"oooooooo..",
			"..........",
			"########..",
			"..........",
			"########..",
			"..........",
			"######....",
			"..........",
	};

	/** An eye, with the pupil picked out: what is visible on screen. */
	public static final String[] STREAMER = {
			"..........",
			"...####...",
			"..#....#..",
			".#..oo..#.",
			"#...oo...#",
			".#..oo..#.",
			"..#....#..",
			"...####...",
			"..........",
			"..........",
	};

	/** A tick: servers already visited. Green throughout, because a tick means done. */
	public static final String[] JOINED = {
			"........oo",
			".......oo.",
			"......oo..",
			".....oo...",
			"o....oo...",
			"oo..oo....",
			".oo.oo....",
			"..ooo.....",
			"...o......",
			"..........",
	};

	/** Signal bars, with the strongest picked out: whether a server still answers. */
	public static final String[] REACHABLE = {
			"........oo",
			"........oo",
			".....##.oo",
			".....##.oo",
			"..##.##.oo",
			"..##.##.oo",
			"..##.##.oo",
			"..##.##.oo",
			"..........",
			"..........",
	};

	/** Two figures: the party, and the settings that apply to everyone in it. */
	public static final String[] PARTY = {
			"..oo...##.",
			".oooo.####",
			"..oo...##.",
			"..........",
			".oooo.####",
			"oooooo####",
			"oooooo####",
			"oooooo####",
			"..........",
			"..........",
	};

	/** An arrow leaving a doorway: what happens to everyone else when the host goes. */
	public static final String[] PARTY_LEAVE = {
			"#####.....",
			"#...#..o..",
			"#...#...o.",
			"#...#.oooo",
			"#...#...o.",
			"#...#..o..",
			"#...#.....",
			"#####.....",
			"..........",
			"..........",
	};

	/** A padlock: no more members. */
	public static final String[] PARTY_LOCK = {
			"...####...",
			"..#....#..",
			"..#....#..",
			".########.",
			".###oo###.",
			".###oo###.",
			".####o###.",
			".########.",
			"..........",
			"..........",
	};

	/** Fast forward: keep trying servers without being asked each time. */
	public static final String[] AUTO_JOIN = {
			"..........",
			"..........",
			".##...##..",
			".###..###.",
			".####.####",
			".####.####",
			".###..###.",
			".##...##..",
			"..........",
			"..........",
	};

	/** An hourglass, with the sand picked out: how long to wait between attempts. */
	public static final String[] DELAY = {
			"..........",
			".########.",
			"..#oooo#..",
			"...#oo#...",
			"....##....",
			"....##....",
			"...#oo#...",
			"..#oooo#..",
			".########.",
			"..........",
	};

	/**
	 * A framed picture: the resource pack a server hands you on the way in.
	 *
	 * <p>The chequered swatch this replaced alternated dark and accent across the whole icon, which
	 * is the rule above broken as plainly as it can be — half of it was lit and none of that half
	 * meant anything. At ten pixels it read as static rather than as a texture.
	 */
	public static final String[] RESOURCE_PACK = {
			"..........",
			".########.",
			".#......#.",
			".#......#.",
			".#...#..#.",
			".#..###.#.",
			".#.######.",
			".#......#.",
			".########.",
			"..........",
	};

	/**
	 * A handheld: Bedrock Edition, the one that runs on phones and consoles.
	 *
	 * <p>This row used to borrow {@link #VANILLA}, whose grass block means "an unmodded server" and
	 * nothing whatever about which edition it runs. Two rows sharing one picture is bad enough; that
	 * picture also being the only green-by-depiction icon made this the loudest thing on the page.
	 */
	public static final String[] BEDROCK = {
			"..######..",
			"..#....#..",
			"..#....#..",
			"..#....#..",
			"..#....#..",
			"..#....#..",
			"..#....#..",
			"..#.##.#..",
			"..######..",
			"..........",
	};

	/**
	 * A block with one either side of it: the ports next to a server that answered.
	 *
	 * <p>The neighbours carry the accent because they are what the setting adds — the middle is the
	 * server you already had. Borrowed {@link #SUBNET} before, which is a range of addresses and is
	 * still in use by the filter of that name a few rows up.
	 */
	public static final String[] NEARBY_PORTS = {
			"..........",
			"..........",
			"...####...",
			"...####...",
			".oo####oo.",
			".oo####oo.",
			"...####...",
			"...####...",
			"..........",
			"..........",
	};

	/**
	 * A magnifier: searching before you open the screen.
	 *
	 * <p>The lens is a closed seven-pixel ring and the handle a clean two-pixel diagonal leaving it
	 * at the corner. The previous one had a ring that did not quite meet and a handle that widened
	 * into a wedge, which at this size read as a smudge rather than as a magnifier.
	 */
	public static final String[] SEARCH = {
			"..###.....",
			".#...#....",
			"#.....#...",
			"#.....#...",
			"#.....#...",
			".#...#....",
			"..###.##..",
			".......##.",
			"........##",
			".........#",
	};
}
