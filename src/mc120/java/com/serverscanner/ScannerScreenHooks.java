package com.serverscanner;

import com.serverscanner.screen.ServerScannerScreen;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.List;

/**
 * Adds the entry point to the vanilla multiplayer screen.
 *
 * <p>Uses Fabric's screen events rather than a mixin so it keeps working across snapshots and plays
 * nicely with other mods that add their own buttons. The die is painted over an ordinary button in
 * the screen's render event instead of by subclassing one — the drawing hooks on {@code ButtonWidget}
 * are renamed fairly often, and none of that matters if we never override them.
 */
public final class ScannerScreenHooks {
	/** Square, so it stays a clean icon at every GUI scale rather than clipping a label. */
	private static final int BUTTON_SIZE = 28;
	private static final int MARGIN = 6;

	/** The auto-join panel, in the palette the mod's own screens use. */
	private static final int STATUS_MIN_WIDTH = 110;
	private static final int STATUS_BG = 0xC00E1014;
	private static final int STATUS_BORDER = 0xFF2E3440;
	private static final int STATUS_ACCENT = 0xFF7FD1A0;
	private static final int STATUS_TEXT = 0xFFDCDCDC;
	private static final int STATUS_DIM = 0xFF8A8F98;
	private static final int STATUS_TRACK = 0x33FFFFFF;

	/**
	 * A 24x24 cube inside a 28x28 button: both even, so it centres exactly rather than sitting a
	 * pixel off.
	 *
	 * <p>Size is what makes this read as a die rather than a grey lump. Three faces each need a 3x3
	 * pip grid with gaps between the dots and clearance from the edges; below about 24 pixels the
	 * dots merge into stripes.
	 */
	private static final int CUBE_W = 24;
	private static final int TOP_H = 12;
	private static final int SIDE_H = 12;

	/** Pips are 2x2 blocks; single pixels do not read as dots. */
	private static final int PIP_SIZE = 2;

	/**
	 * Where the three pip columns and rows sit across a face, as a fraction of its width.
	 *
	 * <p>Inset from 0 and 1 so no pip touches an edge — on the top face the outer positions land near
	 * the diamond's side vertices, where a pip flush to the boundary spills onto the face below and
	 * makes the number underneath it unreadable.
	 */
	private static final double[] PIP_GRID = { 0.26, 0.5, 0.74 };

	/**
	 * The same three positions on a side face, but in whole pixels: two in from the edge, then every
	 * three. A 2px pip on a 3px step always leaves exactly one pixel of gap, which is the point —
	 * fractions here round two rows into contact and turn a six into two bars.
	 */
	private static final int SIDE_PIP_INSET = 2;
	private static final int SIDE_PIP_STEP = 3;

	private static final int FACE_TOP = 0;
	private static final int FACE_LEFT = 1;
	private static final int FACE_RIGHT = 2;

	// A white die: the top catches the light, the right side falls away into shadow. The two side
	// faces sit well clear of the top in value — close together, the joins between them turn to mush
	// at the size this is actually drawn.
	private static final int TOP_FACE = 0xFFFDFDFB;
	private static final int LEFT_FACE = 0xFFC4C4BE;
	private static final int RIGHT_FACE = 0xFF8E8E88;
	private static final int OUTLINE = 0xFF2A2A30;
	private static final int PIP = 0xFF1A1A1E;
	private static final int PIP_LIGHT = 0xFF26262C;

	/**
	 * Orientations the die tumbles through, as (top, left, right).
	 *
	 * <p>Each is a face triple you could actually see at once: no two are opposite, and opposite
	 * faces total seven, so it reads as one die turning rather than three unrelated numbers.
	 */
	private static final int[][] ORIENTATIONS = {
			{ 1, 2, 3 },
			{ 3, 2, 6 },
			{ 6, 5, 3 },
			{ 2, 6, 4 },
			{ 4, 1, 5 },
			{ 5, 3, 1 },
	};

	/** Pip layout per face value, as (column, row) on a 3x3 grid. */
	private static final int[][] PIP_LAYOUTS = {
			{},
			{ 1, 1 },
			{ 0, 0, 2, 2 },
			{ 0, 0, 1, 1, 2, 2 },
			{ 0, 0, 2, 0, 0, 2, 2, 2 },
			{ 0, 0, 2, 0, 1, 1, 0, 2, 2, 2 },
			{ 0, 0, 2, 0, 0, 1, 2, 1, 0, 2, 2, 2 },
	};

	/** Quarter turns per second while rolling. */
	private static final double TURNS_PER_SECOND = 1.6;

	/** Fraction of each turn spent resting on a face before tipping onto the next. */
	private static final double DWELL = 0.30;

	/**
	 * The labels our two buttons carry, which is also how they are recognised.
	 *
	 * <p>A screen shown again re-runs the init event without necessarily rebuilding its widgets, so
	 * ours can already be present when we are asked to add it. Finding it by label keeps nothing
	 * alive between screens: the map that used to do this was keyed by the screen and held a button
	 * whose click handler captured that same screen, so no entry was ever collectable and every
	 * multiplayer screen ever opened stayed in memory with its widgets and favicons.
	 */
	private static final String FINDER_LABEL = "Random Server Finder";
	private static final String STOP_LABEL = "Stop auto-join";

	private static float hoverAmount;
	private static long lastNanos;
	private static double rollPhase;

	private ScannerScreenHooks() {
	}

	public static void register() {
		registerStopButton();
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (!(screen instanceof MultiplayerScreen)) {
				return;
			}

			List<ClickableWidget> buttons = Screens.getButtons(screen);
			int y = scaledHeight - BUTTON_SIZE - MARGIN;

			ButtonWidget existing = findByLabel(buttons, FINDER_LABEL);
			final ButtonWidget button;
			if (existing != null && buttons.contains(existing)) {
				// This screen kept its widgets through the re-init, so ours is still on it. Adding
				// another here is what left a row of buttons behind after every trip to the finder.
				button = existing;
				button.setX(findFreeX(buttons, scaledHeight, button));
				button.setY(y);
			} else {
				// The die is drawn on top of the label, but the text is still what the narrator reads.
				button = ButtonWidget
						.builder(Text.literal(FINDER_LABEL),
								b -> client.setScreen(new ServerScannerScreen(screen)))
						.dimensions(findFreeX(buttons, scaledHeight, null), y, BUTTON_SIZE, BUTTON_SIZE)
						.tooltip(Tooltip.of(Text.translatable("randomserverfinder.tip.random_server_finder_nfind_a")))
						.build();
				buttons.add(button);
			}

			// Registered on every init because Fabric replaces a screen's render events each time it
			// initialises — unlike the widgets, these do not survive, so they cannot pile up.
			ScreenEvents.afterRender(screen).register((rendered, context, mouseX, mouseY, delta) -> {
				if (!button.visible) return;
				boolean hovered = mouseX >= button.getX() && mouseX < button.getX() + BUTTON_SIZE
						&& mouseY >= button.getY() && mouseY < button.getY() + BUTTON_SIZE;
				drawDice(context, button.getX(), button.getY(), hovered);
			});
		});
	}

	/** Our own button on a screen, found by its label, or null when it is not there yet. */
	private static ButtonWidget findByLabel(List<ClickableWidget> widgets, String label) {
		for (ClickableWidget widget : widgets) {
			if (widget instanceof ButtonWidget button && label.equals(button.getMessage().getString())) {
				return button;
			}
		}
		return null;
	}

	/**
	 * Puts a stop button on every menu screen while auto-join is running.
	 *
	 * <p>On every screen rather than only the connecting one, because the screens you actually need
	 * it on are the refusals: "You are not white-listed on this server" and its many cousins. Each
	 * of those is an ordinary screen the game happens to have thrown up, and there is no telling in
	 * advance which one a server will use, so the button goes on all of them.
	 *
	 * <p>No bookkeeping like the multiplayer button needs, because these screens are built fresh
	 * each time rather than being shown again.
	 */
	private static void registerStopButton() {
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (!AutoJoin.isActive()) return;

			// Menus only. In a world auto-join is idle until you leave, and a button here would land
			// on top of the pause menu, the inventory and everything else.
			if (client.world != null) return;

			// The finder has its own toggle in the corner already.
			if (screen instanceof ServerScannerScreen) return;

			// Same as the finder button: this screen may already carry ours.
			if (findByLabel(Screens.getButtons(screen), STOP_LABEL) == null) {
				Screens.getButtons(screen).add(ButtonWidget
						.builder(Text.literal(STOP_LABEL),
								b -> AutoJoin.stopAndReturn(client, screen))
						.dimensions(6, 6, 110, 20)
						.tooltip(Tooltip.of(Text.translatable("randomserverfinder.tip.stop_trying_servers_drop_the")))
						.build());
			}

			// The countdown, under the button. A refusal screen is completely still, so without
			// something ticking there is no way to tell waiting from having quietly stopped.
			ScreenEvents.afterRender(screen).register((rendered, context, mouseX, mouseY, delta) ->
					drawStatus(context, client, MARGIN, MARGIN + 24));
		});
	}

	/**
	 * Draws what auto-join is up to, as a small panel rather than a line of text.
	 *
	 * <p>Bare text floating over someone else's screen reads as something that escaped from a log.
	 * This borrows what the mod's own screens use, a dark plate with the green edge the filter chips
	 * carry, so it reads as part of the mod rather than debris on top of the game.
	 *
	 * <p>The wait is a bar draining along the bottom as well as a number. At a glance the bar says
	 * whether anything is happening, which is the actual question being asked.
	 */
	private static void drawStatus(DrawContext context, MinecraftClient client, int x, int y) {
		String phase = AutoJoin.phase();
		if (phase == null) return;

		int seconds = AutoJoin.secondsLeft();
		String headline = seconds >= 0 ? phase + " in " + seconds + "s" : phase;
		int attempts = AutoJoin.attempts();
		String tail = attempts > 0 ? attempts + " tried" : "";

		int textWidth = client.textRenderer.getWidth(headline);
		if (!tail.isEmpty()) textWidth += 10 + client.textRenderer.getWidth(tail);

		int width = Math.max(STATUS_MIN_WIDTH, textWidth + 16);
		int height = 20;

		context.fill(x, y, x + width, y + height, STATUS_BG);
		context.fill(x, y, x + width, y + 1, STATUS_BORDER);
		context.fill(x, y + height - 1, x + width, y + height, STATUS_BORDER);
		context.fill(x, y, x + 1, y + height, STATUS_BORDER);
		context.fill(x + width - 1, y, x + width, y + height, STATUS_BORDER);
		context.fill(x, y, x + 2, y + height, STATUS_ACCENT);

		context.drawTextWithShadow(client.textRenderer, headline, x + 9, y + 6, STATUS_TEXT);
		if (!tail.isEmpty()) {
			context.drawTextWithShadow(client.textRenderer, tail,
					x + width - 8 - client.textRenderer.getWidth(tail), y + 6, STATUS_DIM);
		}

		float remaining = AutoJoin.waitRemaining();
		if (remaining > 0.0f) {
			int track = width - 6;
			context.fill(x + 3, y + height - 3, x + 3 + track, y + height - 2, STATUS_TRACK);
			context.fill(x + 3, y + height - 3, x + 3 + (int) (track * remaining), y + height - 2,
					STATUS_ACCENT);
		}
	}
	/**
	 * Finds an unoccupied spot along the bottom-left.
	 *
	 * <p>Other mods put buttons in the same corner, so rather than assuming it is empty we start at
	 * the edge and step right past anything already sitting in our row. Ours is the last widget
	 * added during init, so everything else is already positioned by the time this runs.
	 *
	 * @param self our own button when it is being repositioned, so it does not push itself along the
	 *             row a little further on every re-init
	 */
	private static int findFreeX(List<ClickableWidget> existing, int scaledHeight, ClickableWidget self) {
		int y = scaledHeight - BUTTON_SIZE - MARGIN;
		int x = MARGIN;

		boolean moved = true;
		while (moved && x < 400) {
			moved = false;
			for (ClickableWidget widget : existing) {
				if (widget == self || !widget.visible) continue;
				boolean overlapsRow = widget.getY() < y + BUTTON_SIZE && widget.getY() + widget.getHeight() > y;
				boolean overlapsColumn = widget.getX() < x + BUTTON_SIZE && widget.getX() + widget.getWidth() > x;
				if (overlapsRow && overlapsColumn) {
					x = widget.getX() + widget.getWidth() + 4;
					moved = true;
				}
			}
		}
		return x;
	}

	// --- The die -------------------------------------------------------------------------------

	/**
	 * Draws the die over the button.
	 *
	 * <p>Seen from above and to one side, so three faces are visible at once — that angle is what
	 * makes it read as a cube rather than a square. Hovering makes it tumble: it rests on an
	 * orientation, tips onto the next, and hops while it does.
	 */
	private static void drawDice(DrawContext context, int buttonX, int buttonY, boolean hovered) {
		double dt = advanceHover(hovered);
		advanceRoll(dt);

		double ms = Util.getMeasuringTimeMs();
		double hop = Math.abs(Math.sin(ms / 1000.0 * 5.0)) * 2.6 * hoverAmount;
		int lift = (int) Math.round(hop);

		int step = (int) Math.floor(rollPhase);
		int[] faces = ORIENTATIONS[Math.floorMod(step, ORIENTATIONS.length)];

		// Both dimensions are even against an even button, so this lands dead centre.
		int ox = buttonX + (BUTTON_SIZE - CUBE_W) / 2;
		int oy = buttonY + (BUTTON_SIZE - (TOP_H + SIDE_H)) / 2 - lift;

		drawCube(context, ox, oy, faces[0], faces[1], faces[2]);
	}

	/** Eases the hover value on real elapsed time so the motion is frame-rate independent. */
	private static double advanceHover(boolean hovered) {
		long now = System.nanoTime();
		double dt = lastNanos == 0 ? 0 : Math.min((now - lastNanos) / 1_000_000_000.0, 0.1);
		lastNanos = now;

		float target = hovered ? 1.0f : 0.0f;
		hoverAmount += (target - hoverAmount) * (float) (1.0 - Math.exp(-14.0 * dt));
		if (Math.abs(target - hoverAmount) < 0.01f) hoverAmount = target;
		return dt;
	}

	/**
	 * Advances the tumble, holding on each orientation rather than turning at a constant rate — a
	 * die rests on a face, tips over, and lands on the next.
	 */
	private static void advanceRoll(double dt) {
		rollPhase += dt * TURNS_PER_SECOND * hoverAmount;
		if (hoverAmount < 0.05f) {
			double target = Math.round(rollPhase);
			rollPhase += (target - rollPhase) * (1.0 - Math.exp(-12.0 * dt));
		}
		if (rollPhase > 4096) rollPhase -= 4096;
	}

	/**
	 * Draws the cube: a diamond top with two sheared faces below it.
	 *
	 * <p>Built row by row rather than from a texture so the 2:1 isometric edges stay crisp at any
	 * GUI scale, and so the faces can be shaded independently.
	 */
	private static void drawCube(DrawContext context, int ox, int oy, int top, int left, int right) {
		int half = CUBE_W / 2;

		// Top face: a diamond, widening to the full width at its middle two rows.
		for (int r = 0; r < TOP_H; r++) {
			int d = r < TOP_H / 2 ? r : TOP_H - 1 - r;
			int x0 = (half - 2) - 2 * d;
			int x1 = (half + 1) + 2 * d;
			context.fill(ox + x0, oy + r, ox + x1 + 1, oy + r + 1, TOP_FACE);
			// Two pixels wide, not one: the diamond steps two columns per row, so a single pixel
			// per row leaves gaps the face colour shows through and the edge comes out dotted. The
			// side faces outline per column, at two columns per row, which is why only this one
			// broke.
			//
			// On every row, including the lower half where these edges are interior joins with the
			// side faces rather than the silhouette. Leaving those bare looks right at high zoom
			// and wrong at the size the button actually is: white meeting near-white across a
			// two-column stair reads as the top face bleeding raggedly into the left one.
			context.fill(ox + x0, oy + r, ox + x0 + 2, oy + r + 1, OUTLINE);
			context.fill(ox + x1 - 1, oy + r, ox + x1 + 1, oy + r + 1, OUTLINE);
		}

		// Side faces hang from the diamond's lower edges, one column at a time. The 2:1 slope means
		// each pair of columns drops by one pixel, which is what gives the isometric look.
		for (int x = 0; x < half; x++) {
			int startY = oy + sideTop(x);
			context.fill(ox + x, startY, ox + x + 1, startY + SIDE_H, LEFT_FACE);
			// The outer edge and the bottom only. The corner where the two sides meet used to be
			// outlined from both sides at once, which drew a two-pixel black bar down the middle.
			if (x == 0) {
				context.fill(ox + x, startY, ox + x + 1, startY + SIDE_H, OUTLINE);
			}
			context.fill(ox + x, startY + SIDE_H - 1, ox + x + 1, startY + SIDE_H, OUTLINE);
		}
		for (int x = half; x < CUBE_W; x++) {
			int startY = oy + sideTop(CUBE_W - 1 - x);
			context.fill(ox + x, startY, ox + x + 1, startY + SIDE_H, RIGHT_FACE);
			if (x == CUBE_W - 1) {
				context.fill(ox + x, startY, ox + x + 1, startY + SIDE_H, OUTLINE);
			}
			context.fill(ox + x, startY + SIDE_H - 1, ox + x + 1, startY + SIDE_H, OUTLINE);
		}

		drawPips(context, ox, oy, top, FACE_TOP);
		drawPips(context, ox, oy, left, FACE_LEFT);
		drawPips(context, ox, oy, right, FACE_RIGHT);
	}

	/** First row of a side face at the given distance from the cube's outer edge. */
	private static int sideTop(int fromEdge) {
		return TOP_H / 2 + 1 + fromEdge / 2;
	}

	/**
	 * Draws the pips for one face.
	 *
	 * <p>Each pip is placed along the face's own two axes rather than along the screen's, which is
	 * what makes a number sit square on the face it belongs to: the top face's grid runs down the
	 * two diagonals, and a side face's runs along its slope and straight down. Positioning them by
	 * eye in screen coordinates is what previously left the outer pips drifting off their faces.
	 */
	private static void drawPips(DrawContext context, int ox, int oy, int face, int side) {
		int[] layout = PIP_LAYOUTS[Math.max(1, Math.min(6, face))];

		for (int i = 0; i < layout.length; i += 2) {
			int column = layout[i];
			int row = layout[i + 1];

			int px;
			int py;
			if (side == FACE_TOP) {
				// The diamond's grid runs along its two diagonals, so every step moves a pip both
				// across and down and they stay well apart. Fractions are fine here.
				double half = CUBE_W / 2.0;
				double topHalf = TOP_H / 2.0;
				double a = PIP_GRID[column];
				double b = PIP_GRID[row];
				px = (int) Math.round(half + half * a - half * b - PIP_SIZE / 2.0);
				py = (int) Math.round(topHalf * a + topHalf * b - PIP_SIZE / 2.0);
			} else {
				// A side face's grid runs along its slope and then straight down, and twelve pixels
				// of height leaves under three between rows. Placed by fraction and rounded, that
				// gap collapses to nothing on two of the three rows, and a six comes out as a pair
				// of solid bars instead of six dots. Whole-pixel steps keep a clear pixel between
				// every pip; the slope is still followed, via the face top at that column.
				int fromEdge = SIDE_PIP_INSET + column * SIDE_PIP_STEP;
				px = side == FACE_LEFT ? fromEdge : CUBE_W - PIP_SIZE - fromEdge;
				py = sideTop(fromEdge) + SIDE_PIP_INSET + row * SIDE_PIP_STEP;
			}

			context.fill(ox + px, oy + py, ox + px + PIP_SIZE, oy + py + PIP_SIZE,
					side == FACE_RIGHT ? PIP_LIGHT : PIP);
		}
	}
}
