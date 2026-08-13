package com.serverscanner.screen;

import com.serverscanner.api.ScannedServer;
import com.serverscanner.config.Addresses;
import com.serverscanner.config.JoinedServers;
import com.serverscanner.local.LocalServerFeed;
import com.serverscanner.config.ScannerConfig;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The scrolling server list.
 *
 * <p>Written from scratch rather than extending {@code EntryListWidget} because vanilla's list
 * snaps the scroll position to whole steps per mouse notch, which reads as stuttery when you are
 * flicking through thousands of results. Here the wheel moves a <em>target</em> offset and the
 * drawn offset eases toward it every frame, with the easing computed from real elapsed time so the
 * feel is identical at 60 and at 240 fps.
 */
public class ScannerListWidget {
	private static final int ITEM_HEIGHT = 36;
	private static final int ITEM_SPACING = 4;
	private static final int ROW = ITEM_HEIGHT + ITEM_SPACING;
	private static final int ICON_SIZE = 32;
	private static final int SCROLLBAR_WIDTH = 6;

	/** Cards wrap into as many columns as fit at this minimum width. */
	private static final int CARD_MIN_WIDTH = 168;
	private static final int CARD_HEIGHT = 58;
	private static final int CARD_GAP = 4;

	/** Rows travelled per wheel notch. */
	private static final double SCROLL_ROWS_PER_NOTCH = 2.0;

	/** Pixels per second a name too long for its row slides by, and the gap between repeats. */
	private static final double MARQUEE_SPEED = 26.0;
	private static final int MARQUEE_GAP = 28;

	/** Seconds held still at the start of each pass, so the name can be read before it moves. */
	private static final double MARQUEE_PAUSE = 1.6;

	/**
	 * Higher converges on the target faster. Used as {@code 1 - e^(-RESPONSIVENESS * dt)} so the
	 * motion is frame-rate independent.
	 */
	private static final double RESPONSIVENESS = 16.0;

	/** Below this distance the animation is finished, avoiding an endless asymptote. */
	private static final double SNAP_EPSILON = 0.05;

	private static final Identifier INCOMPATIBLE_TEXTURE = Identifier.ofVanilla("server_list/incompatible");
	private static final Identifier UNREACHABLE_TEXTURE = Identifier.ofVanilla("server_list/unreachable");
	private static final Identifier[] PING_TEXTURES = {
			Identifier.ofVanilla("server_list/ping_1"),
			Identifier.ofVanilla("server_list/ping_2"),
			Identifier.ofVanilla("server_list/ping_3"),
			Identifier.ofVanilla("server_list/ping_4"),
			Identifier.ofVanilla("server_list/ping_5"),
	};
	private static final Identifier[] PINGING_TEXTURES = {
			Identifier.ofVanilla("server_list/pinging_1"),
			Identifier.ofVanilla("server_list/pinging_2"),
			Identifier.ofVanilla("server_list/pinging_3"),
			Identifier.ofVanilla("server_list/pinging_4"),
			Identifier.ofVanilla("server_list/pinging_5"),
	};

	private static final int COLOR_TEXT = 0xFFFFFFFF;
	private static final int COLOR_MOTD = 0xFF808080;
	private static final int COLOR_META = 0xFF6A6A6A;
	private static final int COLOR_DIM = 0xFF5A5A5A;


	private final MinecraftClient client = MinecraftClient.getInstance();
	private final LocalServerFeed feed;
	private final ScannerConfig config;

	/** One entry per server, keyed so rebuilds keep the live ping state. */
	private final Map<String, ScannerEntry> entryCache = new HashMap<>();

	/** The rows actually drawn, after the "hide unreachable" filter. */
	private List<ScannerEntry> visibleEntries = new ArrayList<>();

	private int x;
	private int y;
	private int width;
	private int height;

	private double scroll;
	private double targetScroll;
	private long lastFrameNanos;

	private ScannerEntry selected;
	private long lastClickTime;
	private ScannerEntry lastClicked;

	private boolean draggingScrollbar;
	private double dragOffset;

	/** Inputs to the last {@link #rebuildVisibleEntries()}, used to skip redundant rebuilds. */
	private int lastBuiltServerCount = -1;
	private boolean lastBuiltHideOffline;

	/**
	 * Whether "hide joined" and "hide unreachable" apply to this list.
	 *
	 * <p>Off for the join history, where every row is by definition a server you have joined, so
	 * leaving the filter on emptied the screen completely.
	 */
	private boolean applyListFilters = true;

	/** Invoked when a row is double-clicked. */
	private java.util.function.Consumer<ScannerEntry> onJoin;

	public ScannerListWidget(LocalServerFeed feed, ScannerConfig config) {
		this.feed = feed;
		this.config = config;
		this.lastFrameNanos = System.nanoTime();
	}

	/** Turns off the two list filters, for a list that is not a search. */
	public void setApplyListFilters(boolean apply) {
		this.applyListFilters = apply;
		this.lastBuiltServerCount = -1;
	}

	public void setOnJoin(java.util.function.Consumer<ScannerEntry> onJoin) {
		this.onJoin = onJoin;
	}

	public void setBounds(int x, int y, int width, int height) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
	}

	public ScannerEntry getSelected() {
		return selected;
	}

	public void clearSelection() {
		selected = null;
	}

	/** Drops cached entries and frees their icon textures. */
	public void close() {
		for (ScannerEntry entry : entryCache.values()) {
			entry.close();
		}
		entryCache.clear();
		visibleEntries = new ArrayList<>();
		selected = null;
		lastBuiltServerCount = -1;
	}

	/** Discards state tied to the previous feed contents, e.g. after a filter change. */
	public void reset() {
		close();
		scroll = 0;
		targetScroll = 0;
	}

	// --- Rendering -----------------------------------------------------------------------

	public void render(DrawContext context, int mouseX, int mouseY) {
		rebuildVisibleEntries();
		updateScrollAnimation();

		int maxScroll = getMaxScroll();
		if (targetScroll > maxScroll) targetScroll = maxScroll;
		if (targetScroll < 0) targetScroll = 0;

		int bottom = y + height;
		context.fill(x, y, x + width, bottom, 0x66000000);

		context.enableScissor(x, y, x + width, bottom);

		int columns = columns();
		int rowHeight = rowHeight();
		int firstRow = Math.max(0, (int) (scroll / rowHeight) - 1);
		int lastRow = (int) ((scroll + height) / rowHeight) + 1;

		int last = 0;
		for (int row = firstRow; row <= lastRow; row++) {
			int rowY = y + (int) Math.round(row * (long) rowHeight - scroll);
			if (rowY > bottom || rowY + rowHeight < y) continue;

			for (int column = 0; column < columns; column++) {
				int i = row * columns + column;
				if (i >= visibleEntries.size()) break;
				last = i;

				ScannerEntry entry = visibleEntries.get(i);
				if (columns == 1) {
					renderEntry(context, entry, i, rowY, mouseX, mouseY);
				} else {
					renderCard(context, entry, i, x + 2 + column * cellWidth(), rowY, mouseX, mouseY);
				}
			}
		}

		context.disableScissor();
		renderScrollbar(context, maxScroll);

		// Ask for more results once the user is near the bottom of what's loaded.
		feed.maybeLoadMore(last);
	}

	/**
	 * A tile showing the icon larger and the essentials underneath.
	 *
	 * <p>Cards fit fewer servers on screen than rows do, but give each one enough space for its icon
	 * to actually be recognisable, which is the point of browsing visually.
	 */
	private void renderCard(DrawContext context, ScannerEntry entry, int index, int cardX, int cardY,
			int mouseX, int mouseY) {
		ScannedServer server = entry.getServer();
		ServerInfo info = entry.getServerInfo();

		int w = cellWidth() - CARD_GAP;
		int h = CARD_HEIGHT - CARD_GAP;

		boolean hovered = mouseX >= cardX && mouseX < cardX + w && mouseY >= cardY && mouseY < cardY + h
				&& mouseY >= y && mouseY < y + height;

		int background = entry == selected ? 0xFF12283C : (hovered ? 0x33FFFFFF : 0x40000000);
		context.fill(cardX, cardY, cardX + w, cardY + h, background);
		if (entry == selected) {
			context.fill(cardX, cardY, cardX + w, cardY + 1, 0xFF3C6E9E);
			context.fill(cardX, cardY + h - 1, cardX + w, cardY + h, 0xFF3C6E9E);
			context.fill(cardX, cardY, cardX + 1, cardY + h, 0xFF3C6E9E);
			context.fill(cardX + w - 1, cardY, cardX + w, cardY + h, 0xFF3C6E9E);
		}

		int iconX = cardX + 5;
		int iconY = cardY + (h - ICON_SIZE) / 2;
		context.drawTexture(RenderPipelines.GUI_TEXTURED, entry.getIconTexture(), iconX, iconY,
				0.0F, 0.0F, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);

		int textX = iconX + ICON_SIZE + 5;
		int textWidth = Math.max(20, cardX + w - 5 - textX);
		boolean dead = entry.isUnreachable();

		int nameX = textX;
		if (JoinedServers.get().hasJoined(server.address())) {
			context.drawTextWithShadow(client.textRenderer, Text.translatable("randomserverfinder.x"), nameX, cardY + 6, 0xFF5BD16F);
			nameX += client.textRenderer.getWidth("✔ ");
		}

		String headline = config.labelMode == ScannerConfig.LabelMode.ADDRESS
				? Addresses.display(server.address())
				: entry.displayName();
		// Stops short of the ping icon, which sits at cardX + w - 15 on this same line. Running to
		// the card's edge instead is what let long names slide underneath it.
		drawScrolling(context, headline, nameX, cardY + 6, cardX + w - 19 - nameX,
				dead ? COLOR_DIM : COLOR_TEXT);

		Text motd = config.labelMode == ScannerConfig.LabelMode.NAME
				? Text.literal(entry.remainingMotd())
				: (info.label != null ? info.label : Text.literal(server.descriptionOrEmpty()));
		List<OrderedText> lines = client.textRenderer.wrapLines(motd, textWidth);
		if (!lines.isEmpty()) {
			context.drawTextWithShadow(client.textRenderer, lines.get(0), textX, cardY + 18,
					dead ? COLOR_DIM : COLOR_MOTD);
		}

		Text countText = info.playerCountLabel != null ? info.playerCountLabel
				: Text.literal(server.onlinePlayers() + "/" + server.maxPlayers());
		context.drawTextWithShadow(client.textRenderer, countText, textX, cardY + 30, COLOR_META);

		context.drawTextWithShadow(client.textRenderer,
				client.textRenderer.trimToWidth(server.versionName(), textWidth - 24),
				textX, cardY + 41, COLOR_META);

		Identifier status = statusTexture(entry, index);
		if (status != null) {
			context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, status, cardX + w - 15, cardY + 6, 10, 8);
		}
	}

	private void renderEntry(DrawContext context, ScannerEntry entry, int index, int entryY, int mouseX, int mouseY) {
		ScannedServer server = entry.getServer();
		ServerInfo info = entry.getServerInfo();

		boolean hovered = mouseX >= x && mouseX < x + width - SCROLLBAR_WIDTH
				&& mouseY >= entryY && mouseY < entryY + ITEM_HEIGHT
				&& mouseY >= y && mouseY < y + height;

		if (entry == selected) {
			context.fill(x, entryY - 2, x + width - SCROLLBAR_WIDTH, entryY + ITEM_HEIGHT + 2, 0xFF3C6E9E);
			context.fill(x + 1, entryY - 1, x + width - SCROLLBAR_WIDTH - 1, entryY + ITEM_HEIGHT + 1, 0xFF000000);
		} else if (hovered) {
			context.fill(x, entryY - 2, x + width - SCROLLBAR_WIDTH, entryY + ITEM_HEIGHT + 2, 0x33FFFFFF);
		}

		int iconX = x + 4;
		int textX = iconX + ICON_SIZE + 4;
		int rightEnd = x + width - SCROLLBAR_WIDTH - 4;

		// The live favicon once the server answers, vanilla's placeholder icon until then.
		context.drawTexture(RenderPipelines.GUI_TEXTURED, entry.getIconTexture(), iconX, entryY, 0.0F, 0.0F,
				ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);

		boolean dead = entry.isUnreachable();
		int nameColor = dead ? COLOR_DIM : COLOR_TEXT;

		// A tick for servers already joined, so repeats are obvious at a glance.
		boolean joined = JoinedServers.get().hasJoined(server.address());
		int labelX = textX;
		if (joined) {
			context.drawTextWithShadow(client.textRenderer, Text.translatable("randomserverfinder.x"), labelX, entryY + 1, 0xFF5BD16F);
			labelX += client.textRenderer.getWidth("✔ ");
		}

		// The right-hand side is measured first: the name gets whatever is left, rather than being
		// drawn at full length and running underneath the player count.
		int statusX = rightEnd - 10;
		Text countText = entry.isReachable() && info.playerCountLabel != null
				? info.playerCountLabel
				: Text.literal(server.onlinePlayers() + "/" + server.maxPlayers());
		int countWidth = client.textRenderer.getWidth(countText);
		int countX = statusX - countWidth - 4;

		String headline = config.labelMode == ScannerConfig.LabelMode.ADDRESS
				? Addresses.display(server.address())
				: entry.displayName();
		drawScrolling(context, headline, labelX, entryY + 1, countX - labelX - 6, nameColor);

		Identifier status = statusTexture(entry, index);
		if (status != null) {
			context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, status, statusX, entryY + 1, 10, 8);
		}
		context.drawTextWithShadow(client.textRenderer, countText, countX, entryY + 1, dead ? COLOR_DIM : COLOR_MOTD);

		// MOTD, wrapped to at most two lines like the vanilla server list. In name mode the first
		// line is already the headline, so only what is left of it is drawn here.
		Text motd = config.labelMode == ScannerConfig.LabelMode.NAME
				? Text.literal(entry.remainingMotd())
				: (info.label != null ? info.label : Text.literal(server.descriptionOrEmpty()));
		int motdWidth = Math.max(20, countX - textX - 4);
		List<OrderedText> lines = client.textRenderer.wrapLines(motd, motdWidth);
		for (int i = 0; i < Math.min(lines.size(), 2); i++) {
			context.drawTextWithShadow(client.textRenderer, lines.get(i), textX, entryY + 12 + 9 * i,
					dead ? COLOR_DIM : COLOR_MOTD);
		}

		// Metadata line only fits when the MOTD used a single line.
		if (lines.size() <= 1) {
			context.drawTextWithShadow(client.textRenderer, metaLine(server), textX, entryY + 21 + 5, COLOR_META);
		}

		if (hovered) {
			renderHoverTooltips(context, entry, mouseX, mouseY, statusX, entryY, countX, countWidth);
		}
	}

	private void renderHoverTooltips(DrawContext context, ScannerEntry entry, int mouseX, int mouseY,
			int statusX, int entryY, int countX, int countWidth) {
		if (mouseX >= statusX && mouseX <= statusX + 10 && mouseY >= entryY + 1 && mouseY <= entryY + 9) {
			ServerInfo info = entry.getServerInfo();
			Text tooltip = switch (info.getStatus()) {
				case SUCCESSFUL -> Text.translatable("multiplayer.status.ping", info.ping);
				case INCOMPATIBLE -> Text.translatable("multiplayer.status.incompatible");
				case UNREACHABLE -> Text.translatable("multiplayer.status.cannot_connect");
				default -> Text.translatable("multiplayer.status.pinging");
			};
			context.drawTooltip(tooltip, mouseX, mouseY);
			return;
		}

		List<Text> summary = entry.getPlayerListSummary();
		if (summary != null && !summary.isEmpty()
				&& mouseX >= countX && mouseX <= countX + countWidth
				&& mouseY >= entryY + 1 && mouseY <= entryY + 10) {
			List<OrderedText> ordered = new ArrayList<>(summary.size());
			for (Text t : summary) ordered.add(t.asOrderedText());
			context.drawTooltip(ordered, mouseX, mouseY);
		}
	}

	private String metaLine(ScannedServer server) {
		StringBuilder sb = new StringBuilder();
		// When the headline is the server's name, the address still belongs somewhere.
		if (config.labelMode == ScannerConfig.LabelMode.NAME) {
			sb.append(Addresses.display(server.address())).append("  ");
		}
		sb.append(server.versionName());
		if (server.geo != null && server.geo.country != null) {
			sb.append("  ").append(server.geo.country);
		}
		if (Boolean.TRUE.equals(server.cracked)) {
			sb.append("  cracked");
		}
		if (Boolean.TRUE.equals(server.whitelisted)) {
			sb.append("  whitelisted");
		}
		sb.append("  found ").append(relativeTime(server.lastSeen));
		return sb.toString();
	}

	private static String relativeTime(long epochSeconds) {
		long age = System.currentTimeMillis() / 1000L - epochSeconds;
		if (age < 60) return "just now";
		if (age < 3600) return (age / 60) + "m ago";
		if (age < 86400) return (age / 3600) + "h ago";
		return (age / 86400) + "d ago";
	}

	static Identifier statusTexture(ScannerEntry entry, int index) {
		ServerInfo info = entry.getServerInfo();
		return switch (info.getStatus()) {
			case INITIAL -> PING_TEXTURES[0];
			case PINGING -> {
				int frame = (int) (Util.getMeasuringTimeMs() / 100L + index * 2L & 7L);
				if (frame > 4) frame = 8 - frame;
				yield PINGING_TEXTURES[Math.min(frame, 4)];
			}
			case INCOMPATIBLE -> INCOMPATIBLE_TEXTURE;
			case UNREACHABLE -> UNREACHABLE_TEXTURE;
			case SUCCESSFUL -> {
				long ping = info.ping;
				if (ping < 150L) yield PING_TEXTURES[4];
				if (ping < 300L) yield PING_TEXTURES[3];
				if (ping < 600L) yield PING_TEXTURES[2];
				if (ping < 1000L) yield PING_TEXTURES[1];
				yield PING_TEXTURES[0];
			}
		};
	}

	/**
	 * Draws a line of text, sliding it sideways when it is wider than the space it has.
	 *
	 * <p>Server names are written by their owners and run to any length they like, so something has
	 * to give. Cutting them off loses the end, which is often the part that says what the server is.
	 * This runs them past like a departure board instead: the whole name is readable, just not all
	 * at once, and the row keeps its shape.
	 *
	 * <p>It rests at the start for a moment before setting off, because a name that is only slightly
	 * too long is otherwise in constant motion for the sake of two characters.
	 */
	private void drawScrolling(DrawContext context, String text, int x, int y, int available, int colour) {
		if (available <= 0) return;

		int textWidth = client.textRenderer.getWidth(text);
		if (textWidth <= available) {
			context.drawTextWithShadow(client.textRenderer, text, x, y, colour);
			return;
		}

		// A second copy one gap behind the first, so the loop has no seam to notice.
		int cycle = textWidth + MARQUEE_GAP;
		double travel = cycle / MARQUEE_SPEED;
		double period = travel + MARQUEE_PAUSE;
		double phase = (System.nanoTime() / 1_000_000_000.0) % period;
		int offset = phase < MARQUEE_PAUSE ? 0 : (int) ((phase - MARQUEE_PAUSE) * MARQUEE_SPEED);

		context.enableScissor(x, y - 1, x + available, y + 10);
		context.drawTextWithShadow(client.textRenderer, text, x - offset, y, colour);
		context.drawTextWithShadow(client.textRenderer, text, x - offset + cycle, y, colour);
		context.disableScissor();
	}

	private void renderScrollbar(DrawContext context, int maxScroll) {
		if (maxScroll <= 0) return;

		int barX = x + width - SCROLLBAR_WIDTH;
		context.fill(barX, y, barX + SCROLLBAR_WIDTH, y + height, 0x4D000000);

		int handleHeight = getScrollbarHandleHeight(maxScroll);
		int handleY = y + (int) ((scroll / maxScroll) * (height - handleHeight));
		context.fill(barX, handleY, barX + SCROLLBAR_WIDTH, handleY + handleHeight, 0xFF808080);
		context.fill(barX, handleY, barX + SCROLLBAR_WIDTH - 1, handleY + handleHeight - 1, 0xFFC0C0C0);
	}

	private int getScrollbarHandleHeight(int maxScroll) {
		int contentHeight = visibleEntries.size() * ROW;
		return Math.max(16, height * height / Math.max(1, contentHeight));
	}

	// --- Scrolling -----------------------------------------------------------------------

	/**
	 * Moves the drawn offset a fraction of the way to the target, where the fraction depends on how
	 * long the last frame took. Without the exponential, a fixed per-frame fraction would scroll
	 * faster on a high refresh rate monitor.
	 */
	private void updateScrollAnimation() {
		long now = System.nanoTime();
		double dt = (now - lastFrameNanos) / 1_000_000_000.0;
		lastFrameNanos = now;
		// A long stall (alt-tab, chunk load) shouldn't teleport the list.
		dt = Math.max(0.0, Math.min(dt, 0.1));

		if (draggingScrollbar) {
			scroll = targetScroll;
			return;
		}

		double delta = targetScroll - scroll;
		if (Math.abs(delta) < SNAP_EPSILON) {
			scroll = targetScroll;
			return;
		}
		scroll += delta * (1.0 - Math.exp(-RESPONSIVENESS * dt));
	}

	// --- Grid geometry -------------------------------------------------------------------

	/** One column in list mode; as many cards as fit otherwise. */
	private int columns() {
		if (config.viewMode != ScannerConfig.ViewMode.CARDS) return 1;
		int usable = width - SCROLLBAR_WIDTH - 4;
		return Math.max(1, usable / CARD_MIN_WIDTH);
	}

	private int cellWidth() {
		int columns = columns();
		return columns == 1 ? width : (width - SCROLLBAR_WIDTH - 4) / columns;
	}

	private int rowHeight() {
		return columns() == 1 ? ROW : CARD_HEIGHT;
	}

	private int rowCount() {
		int columns = columns();
		return (visibleEntries.size() + columns - 1) / columns;
	}

	private int getMaxScroll() {
		return Math.max(0, rowCount() * rowHeight() - height);
	}

	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (!isOver(mouseX, mouseY)) return false;
		// Stepped by the row height in use, so a notch moves the same number of rows in either
		// layout rather than crawling through the taller cards.
		targetScroll = clampScroll(targetScroll - vertical * rowHeight() * SCROLL_ROWS_PER_NOTCH);
		return true;
	}

	private double clampScroll(double value) {
		return Math.max(0, Math.min(value, getMaxScroll()));
	}

	public boolean mouseClicked(Click click, boolean doubled) {
		double mouseX = click.x();
		double mouseY = click.y();
		if (!isOver(mouseX, mouseY)) return false;

		int maxScroll = getMaxScroll();
		int barX = x + width - SCROLLBAR_WIDTH;
		if (mouseX >= barX && maxScroll > 0) {
			int handleHeight = getScrollbarHandleHeight(maxScroll);
			int handleY = y + (int) ((scroll / maxScroll) * (height - handleHeight));
			if (mouseY >= handleY && mouseY < handleY + handleHeight) {
				draggingScrollbar = true;
				dragOffset = mouseY - handleY;
			} else {
				// Clicking the track jumps the handle to the cursor.
				double ratio = (mouseY - y - handleHeight / 2.0) / (height - handleHeight);
				targetScroll = clampScroll(ratio * maxScroll);
				scroll = targetScroll;
				draggingScrollbar = true;
				dragOffset = handleHeight / 2.0;
			}
			return true;
		}

		int index = indexAt(mouseX, mouseY);
		if (index < 0 || index >= visibleEntries.size()) {
			return false;
		}

		ScannerEntry clicked = visibleEntries.get(index);
		long now = Util.getMeasuringTimeMs();
		boolean isDoubleClick = clicked == lastClicked && now - lastClickTime < 250L;
		lastClicked = clicked;
		lastClickTime = now;
		selected = clicked;

		if ((isDoubleClick || doubled) && onJoin != null) {
			onJoin.accept(clicked);
		}
		return true;
	}

	public boolean mouseDragged(Click click, double deltaX, double deltaY) {
		if (!draggingScrollbar) return false;
		int maxScroll = getMaxScroll();
		if (maxScroll <= 0) return true;

		int handleHeight = getScrollbarHandleHeight(maxScroll);
		double ratio = (click.y() - y - dragOffset) / (height - handleHeight);
		targetScroll = clampScroll(ratio * maxScroll);
		scroll = targetScroll;
		return true;
	}

	public boolean mouseReleased(Click click) {
		if (!draggingScrollbar) return false;
		draggingScrollbar = false;
		return true;
	}

	/** Page/Home/End navigation. Returns true when the key was consumed. */
	public boolean handleNavigationKey(int keyCode) {
		switch (keyCode) {
			case org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN -> targetScroll = clampScroll(targetScroll + height);
			case org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP -> targetScroll = clampScroll(targetScroll - height);
			case org.lwjgl.glfw.GLFW.GLFW_KEY_HOME -> targetScroll = 0;
			case org.lwjgl.glfw.GLFW.GLFW_KEY_END -> targetScroll = getMaxScroll();
			default -> {
				return false;
			}
		}
		return true;
	}

	private int indexAt(double mouseX, double mouseY) {
		double relative = mouseY - y + scroll;
		if (relative < 0) return -1;

		int rowHeight = rowHeight();
		int row = (int) (relative / rowHeight);
		int columns = columns();

		if (columns == 1) {
			// Ignore clicks that land in the gap between rows.
			return (relative % rowHeight) > ITEM_HEIGHT ? -1 : row;
		}

		int column = (int) ((mouseX - x - 2) / cellWidth());
		if (column < 0 || column >= columns) return -1;
		return row * columns + column;
	}

	public boolean isOver(double mouseX, double mouseY) {
		return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
	}

	// --- Entry bookkeeping -------------------------------------------------------------------

	/**
	 * Syncs the drawn rows with the feed, reusing existing entries so live ping results survive.
	 *
	 * <p>When "hide unreachable" is on, rows vanish as their pings fail. The scroll offset is
	 * adjusted by however many rows disappeared above the viewport so the content under the cursor
	 * stays put instead of jumping upward.
	 *
	 * <p>Rebuilding allocates a list proportional to the number of results, which is not something
	 * to do every frame once thousands are loaded. With the filter off the rows are simply all the
	 * entries, so the work is skipped unless the feed actually grew.
	 */
	private void rebuildVisibleEntries() {
		List<ScannedServer> servers = feed.getServers();

		boolean filterActive = applyListFilters && (config.hideOffline || config.hideJoined);
		boolean sizeChanged = servers.size() != lastBuiltServerCount;
		boolean filterToggled = filterActive != lastBuiltHideOffline;
		if (!sizeChanged && !filterToggled && !filterActive) {
			return;
		}
		lastBuiltServerCount = servers.size();
		lastBuiltHideOffline = filterActive;

		// Whichever entry is at the top of the viewport right now. Afterwards the scroll is moved by
		// however far that same entry shifted, which holds the view still when rows above it are
		// dropped and does nothing at all when the rebuild changes nothing.
		//
		// The previous version counted the dropped rows above the viewport and subtracted them from
		// the scroll. That is only correct once, but with a filter switched on this method runs on
		// every frame, and the count is of every dropped row rather than the newly dropped ones — so
		// the same rows were subtracted again each frame and the list crawled back to the top no
		// matter how far you scrolled.
		int columns = columns();
		int rowHeight = rowHeight();
		int topRow = (int) (scroll / rowHeight);
		String anchor = null;
		int anchorIndex = topRow * columns;
		if (anchorIndex < visibleEntries.size()) {
			anchor = visibleEntries.get(anchorIndex).getServer().key();
		}

		List<ScannerEntry> next = new ArrayList<>(servers.size());
		for (int i = 0; i < servers.size(); i++) {
			ScannedServer server = servers.get(i);
			ScannerEntry entry = entryCache.computeIfAbsent(server.key(), k -> new ScannerEntry(server));

			boolean drop = applyListFilters
					&& ((config.hideOffline && entry.isUnreachable())
							|| (config.hideJoined && JoinedServers.get().hasJoined(server.address())));
			if (!drop) next.add(entry);
		}

		visibleEntries = next;

		if (anchor == null) return;
		for (int i = 0; i < next.size(); i++) {
			if (!anchor.equals(next.get(i).getServer().key())) continue;
			int shift = (i / columns - topRow) * rowHeight;
			if (shift != 0) {
				scroll = clampScroll(scroll + shift);
				targetScroll = clampScroll(targetScroll + shift);
			}
			return;
		}
		// The anchor itself was dropped; leave the scroll where it is rather than guessing.
	}

	// --- Shared with the auto-join queue, which draws the same kind of row -------------------

	/** The server's favicon, or vanilla's placeholder until one arrives. */
	static void drawServerIcon(DrawContext context, ScannerEntry entry, int x, int y) {
		context.drawTexture(RenderPipelines.GUI_TEXTURED, entry.getIconTexture(), x, y, 0.0F, 0.0F,
				ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
	}

	/** One of the ping pictograms, at its natural ten by eight. */
	static void drawStatusIcon(DrawContext context, Identifier texture, int x, int y) {
		context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, texture, x, y, 10, 8);
	}

	/** The row height the finder uses, so another list can match it. */
	static int iconSize() {
		return ICON_SIZE;
	}

}
