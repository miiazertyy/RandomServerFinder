package com.serverscanner.screen;

import com.serverscanner.AutoJoin;
import com.serverscanner.api.ScannedServer;
import com.serverscanner.config.Addresses;
import com.serverscanner.config.JoinedServers;
import com.serverscanner.config.ScannerConfig;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.Click;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The servers auto-join is about to try, in order, with the order up to you.
 *
 * <p>Auto-join used to pick at random from whatever the search had found, which meant there was
 * nothing to look at and nothing to change: a server you did not want came round again as often as
 * any other. It now works through an explicit queue, and this is that queue — drag a row to move
 * it, or drop one you have no interest in.
 *
 * <p>Rows are drawn rather than made of widgets. There can be hundreds of them, they scroll, and
 * they are draggable, none of which vanilla's button gets involved in.
 */
public class AutoJoinQueueScreen extends Screen {
	private static final int PANEL_WIDTH = 380;

	/** Sized to the finder's icon, so a queued row carries the same information at the same size. */
	private static final int ROW_HEIGHT = 36;
	private static final int ROW_GAP = 3;
	private static final int ROW = ROW_HEIGHT + ROW_GAP;

	/** Where the list starts and stops, measured from the top and bottom of the window. */
	private static final int TOP = 46;
	private static final int BOTTOM_MARGIN = 40;

	private static final int PANEL_BG = 0xB00E1014;
	private static final int PANEL_BORDER = 0xFF2E3440;
	private static final int ROW_BG = 0x22FFFFFF;
	private static final int ROW_HOVER = 0x33FFFFFF;
	private static final int ROW_DRAGGED = 0x557FD1A0;
	private static final int ACCENT = 0xFF7FD1A0;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int DIM = 0xFF8A8F98;
	private static final int GRIP = 0xFF5A5F66;
	private static final int REMOVE = 0xFFE0655A;

	/** Width of the grip column on the left and the remove column on the right. */
	private static final int GRIP_WIDTH = 14;
	private static final int REMOVE_WIDTH = 14;

	/** Borrowed from the finder's rows, so the two lists read as the same thing. */
	private static final int MOTD = 0xFF808080;
	private static final int META = 0xFF6A6A6A;
	private static final int DEAD = 0xFF5A5A5A;

	/** How far the pointer must travel before a press counts as a drag rather than a click. */
	private static final double DRAG_SLOP = 3.0;

	private final Screen parent;
	private final ScannerConfig config = ScannerConfig.get();

	/** One entry per queued server, keyed so a row keeps its icon across frames. */
	private final Map<String, ScannerEntry> entries = new HashMap<>();

	private List<ScannedServer> queue = List.of();
	private double scroll;

	/** Index being dragged, or -1. Set on press and only honoured once the pointer has moved. */
	private int pressed = -1;
	private double pressedY;
	private boolean dragging;

	/** Where the dragged row would land if released now. */
	private int dropIndex = -1;

	public AutoJoinQueueScreen(Screen parent) {
		super(Text.translatable("randomserverfinder.auto_join_queue"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		queue = AutoJoin.queued();

		// Attempts carry on while this is open; saying so here is what gets the screen handed back
		// after each one, instead of leaving you on whichever refusal ended it.
		AutoJoin.setBrowsingQueue(this);

		addDrawableChild(ButtonWidget.builder(Text.translatable("randomserverfinder.back"), b -> this.close())
				.dimensions(this.width / 2 - 100, this.height - 28, 200, 20).build());
	}

	// --- Layout ------------------------------------------------------------------------------

	private int panelX() {
		return this.width / 2 - PANEL_WIDTH / 2;
	}

	private int listBottom() {
		return this.height - BOTTOM_MARGIN;
	}

	private int listHeight() {
		return Math.max(ROW, listBottom() - TOP);
	}

	private int maxScroll() {
		return Math.max(0, queue.size() * ROW - listHeight());
	}

	/** The row under a point, or -1 when the point is outside the list or past the last row. */
	private int rowAt(double mouseX, double mouseY) {
		int px = panelX();
		if (mouseX < px || mouseX > px + PANEL_WIDTH) return -1;
		if (mouseY < TOP || mouseY >= listBottom()) return -1;

		int index = (int) ((mouseY - TOP + scroll) / ROW);
		return index >= 0 && index < queue.size() ? index : -1;
	}

	// --- Rendering ---------------------------------------------------------------------------

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);

		// Rebuilt every frame: the search keeps finding servers while this is open, and a queue that
		// only grew when reopened would look stuck.
		if (!dragging) queue = AutoJoin.queued();
		scroll = Math.max(0, Math.min(scroll, maxScroll()));

		int centre = this.width / 2;
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, centre, 14, TEXT);
		context.drawCenteredTextWithShadow(this.textRenderer, subtitle(), centre, 28, DIM);

		int px = panelX();
		int bottom = listBottom();
		context.fill(px, TOP, px + PANEL_WIDTH, bottom, PANEL_BG);
		context.fill(px, TOP, px + PANEL_WIDTH, TOP + 1, PANEL_BORDER);
		context.fill(px, bottom - 1, px + PANEL_WIDTH, bottom, PANEL_BORDER);
		context.fill(px, TOP, px + 1, bottom, PANEL_BORDER);
		context.fill(px + PANEL_WIDTH - 1, TOP, px + PANEL_WIDTH, bottom, PANEL_BORDER);

		if (queue.isEmpty()) {
			context.drawCenteredTextWithShadow(this.textRenderer,
					Text.translatable("randomserverfinder.queue_empty"), centre, TOP + 18, DIM);
			return;
		}

		context.enableScissor(px + 1, TOP + 1, px + PANEL_WIDTH - 1, bottom - 1);
		renderRows(context, px, mouseX, mouseY);
		context.disableScissor();

		renderScrollbar(context, px, bottom);
	}

	private Text subtitle() {
		if (queue.isEmpty()) return Text.translatable("randomserverfinder.queue_hint_empty");
		return Text.translatable("randomserverfinder.queue_hint", queue.size());
	}

	private void renderRows(DrawContext context, int px, int mouseX, int mouseY) {
		int hovered = rowAt(mouseX, mouseY);

		for (int i = 0; i < queue.size(); i++) {
			// The dragged row is drawn last, under the pointer, so it is skipped here.
			if (dragging && i == pressed) continue;

			int y = rowY(i);
			if (y > listBottom() || y + ROW_HEIGHT < TOP) continue;
			renderRow(context, queue.get(i), displayNumber(i), px, y,
					!dragging && i == hovered, mouseX, mouseY, false);
		}

		if (dragging && pressed >= 0 && pressed < queue.size()) {
			// A line where it would land, then the row itself following the pointer.
			int gapY = TOP - (int) scroll + dropIndex * ROW - 1;
			context.fill(px + 4, gapY, px + PANEL_WIDTH - 4, gapY + 1, ACCENT);

			int y = (int) pressedY - ROW_HEIGHT / 2;
			renderRow(context, queue.get(pressed), dropIndex + 1, px, y, false, mouseX, mouseY, true);
		}
	}

	/**
	 * The position shown on a row.
	 *
	 * <p>While a row is being dragged the others have not moved yet, so their numbers are worked out
	 * from where everything would end up rather than from the list as it currently stands.
	 */
	private int displayNumber(int index) {
		if (!dragging || pressed < 0) return index + 1;

		int shifted = index;
		if (index > pressed) shifted--;
		if (shifted >= dropIndex) shifted++;
		return shifted + 1;
	}

	private int rowY(int index) {
		int y = TOP - (int) scroll + index * ROW;

		// While dragging, rows either side of the gap slide to open it up.
		if (dragging && pressed >= 0) {
			if (index > pressed && index <= dropIndex) y -= ROW;
			else if (index < pressed && index >= dropIndex) y += ROW;
		}
		return y;
	}

	private void renderRow(DrawContext context, ScannedServer server, int number, int px, int y,
			boolean hovered, int mouseX, int mouseY, boolean lifted) {
		int left = px + 4;
		int right = px + PANEL_WIDTH - 4;

		context.fill(left, y, right, y + ROW_HEIGHT, lifted ? ROW_DRAGGED : hovered ? ROW_HOVER : ROW_BG);
		if (lifted) {
			context.fill(left, y, right, y + 1, ACCENT);
			context.fill(left, y + ROW_HEIGHT - 1, right, y + ROW_HEIGHT, ACCENT);
		}

		// Grip: three short bars, the usual "this can be dragged" mark.
		int gripX = left + 5;
		for (int i = 0; i < 3; i++) {
			context.fill(gripX, y + 14 + i * 3, gripX + 5, y + 15 + i * 3, GRIP);
		}

		context.drawTextWithShadow(this.textRenderer, number + ".", left + GRIP_WIDTH + 2, y + 2, DIM);

		// The same entry the finder builds, so the favicon, live player count and ping all come from
		// the ping that found this server rather than being looked up a second time.
		ScannerEntry entry = entryFor(server);
		int iconX = left + GRIP_WIDTH + 2 + this.textRenderer.getWidth("00.") + 4;
		int iconY = y + (ROW_HEIGHT - ScannerListWidget.iconSize()) / 2;
		ScannerListWidget.drawServerIcon(context, entry, iconX, iconY);

		int textX = iconX + ScannerListWidget.iconSize() + 5;
		boolean dead = entry.isUnreachable();
		ServerInfo info = entry.getServerInfo();

		// The right-hand side is measured first, so the name gets what is left rather than running
		// underneath the player count.
		int statusX = right - REMOVE_WIDTH - 14;
		Text count = entry.isReachable() && info.playerCountLabel != null
				? info.playerCountLabel
				: Text.literal(server.onlinePlayers() + "/" + server.maxPlayers());
		int countWidth = this.textRenderer.getWidth(count);
		int countX = statusX - countWidth - 4;

		int labelX = textX;
		if (JoinedServers.get().hasJoined(server.address())) {
			context.drawTextWithShadow(this.textRenderer, Text.translatable("randomserverfinder.x"),
					labelX, y + 2, 0xFF5BD16F);
			labelX += this.textRenderer.getWidth("✔ ");
		}

		String headline = config.labelMode == ScannerConfig.LabelMode.ADDRESS
				? Addresses.display(server.address())
				: entry.displayName();
		context.drawTextWithShadow(this.textRenderer,
				this.textRenderer.trimToWidth(headline, Math.max(20, countX - labelX - 6)),
				labelX, y + 2, dead ? DEAD : TEXT);

		Identifier status = ScannerListWidget.statusTexture(entry, number);
		if (status != null) {
			ScannerListWidget.drawStatusIcon(context, status, statusX, y + 2);
		}
		context.drawTextWithShadow(this.textRenderer, count, countX, y + 2, dead ? DEAD : MOTD);

		// MOTD, then the version and the rest, exactly as the finder stacks them.
		Text motd = config.labelMode == ScannerConfig.LabelMode.NAME
				? Text.literal(entry.remainingMotd())
				: (info.label != null ? info.label : Text.literal(server.descriptionOrEmpty()));
		List<OrderedText> lines = this.textRenderer.wrapLines(motd, Math.max(20, countX - textX - 4));
		for (int i = 0; i < Math.min(lines.size(), 2); i++) {
			context.drawTextWithShadow(this.textRenderer, lines.get(i), textX, y + 13 + 9 * i,
					dead ? DEAD : MOTD);
		}
		if (lines.size() <= 1) {
			context.drawTextWithShadow(this.textRenderer, metaLine(server), textX, y + 26, META);
		}

		// Remove: a cross, brighter when the pointer is on it.
		int crossX = right - REMOVE_WIDTH + 3;
		int crossY = y + ROW_HEIGHT / 2 - 3;
		boolean overCross = !lifted && mouseX >= right - REMOVE_WIDTH && mouseX <= right
				&& mouseY >= y && mouseY <= y + ROW_HEIGHT;
		int colour = overCross ? REMOVE : GRIP;
		for (int i = 0; i < 7; i++) {
			context.fill(crossX + i, crossY + i, crossX + i + 1, crossY + i + 1, colour);
			context.fill(crossX + 6 - i, crossY + i, crossX + 7 - i, crossY + i + 1, colour);
		}
	}

	/**
	 * The row's entry, built once and kept.
	 *
	 * <p>Each one owns an icon texture, so rebuilding them every frame would upload a favicon per
	 * row per frame. Released when the screen goes.
	 */
	private ScannerEntry entryFor(ScannedServer server) {
		return entries.computeIfAbsent(server.key(), k -> new ScannerEntry(server));
	}

	/** The address, version and where it came from, as the finder writes it. */
	private String metaLine(ScannedServer server) {
		StringBuilder sb = new StringBuilder();
		if (config.labelMode == ScannerConfig.LabelMode.NAME) {
			sb.append(Addresses.display(server.address())).append("  ");
		}
		sb.append(server.versionName());
		if (server.bedrock) sb.append("  Bedrock");
		if (server.geo != null && server.geo.country != null) sb.append("  ").append(server.geo.country);
		return sb.toString();
	}

	private void renderScrollbar(DrawContext context, int px, int bottom) {
		int max = maxScroll();
		if (max <= 0) return;

		int trackX = px + PANEL_WIDTH - 4;
		int trackHeight = bottom - TOP - 2;
		int handleHeight = Math.max(16, (int) (trackHeight * (listHeight() / (float) (queue.size() * ROW))));
		int handleY = TOP + 1 + (int) ((trackHeight - handleHeight) * (scroll / max));

		context.fill(trackX, TOP + 1, trackX + 3, bottom - 1, 0x33000000);
		context.fill(trackX, handleY, trackX + 3, handleY + handleHeight, ACCENT);
	}

	// --- Input -------------------------------------------------------------------------------

	/** Shared by every version's click override, which differ only in how they carry the position. */
	private boolean press(double mouseX, double mouseY) {
		int index = rowAt(mouseX, mouseY);
		if (index < 0) return false;

		int right = panelX() + PANEL_WIDTH - 4;
		if (mouseX >= right - REMOVE_WIDTH && mouseX <= right) {
			AutoJoin.removeQueued(queue.get(index).key());
			queue = AutoJoin.queued();
			return true;
		}

		pressed = index;
		pressedY = mouseY;
		dropIndex = index;
		dragging = false;
		return true;
	}

	private boolean drag(double mouseY) {
		if (pressed < 0) return false;

		// A press that has not moved is still a click; only past the slop is it a drag.
		if (!dragging && Math.abs(mouseY - pressedY) < DRAG_SLOP) return true;

		dragging = true;
		pressedY = mouseY;

		int over = (int) ((mouseY - TOP + scroll) / ROW);
		dropIndex = Math.max(0, Math.min(queue.size() - 1, over));

		// Dragging past either end scrolls, so a row can be moved further than one screenful.
		if (mouseY < TOP + ROW) scroll = Math.max(0, scroll - 4);
		else if (mouseY > listBottom() - ROW) scroll = Math.min(maxScroll(), scroll + 4);
		return true;
	}

	private boolean release() {
		if (pressed < 0) return false;

		if (dragging && dropIndex != pressed) {
			AutoJoin.moveQueued(queue.get(pressed).key(), dropIndex);
			queue = AutoJoin.queued();
		}
		pressed = -1;
		dragging = false;
		dropIndex = -1;
		return true;
	}

	private boolean scrollBy(double mouseX, double mouseY, double amount) {
		int px = panelX();
		if (mouseX < px || mouseX > px + PANEL_WIDTH) return false;
		if (mouseY < TOP || mouseY > listBottom()) return false;

		scroll = Math.max(0, Math.min(maxScroll(), scroll - amount * ROW * 2));
		return true;
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		return press(click.x(), click.y()) || super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double deltaX, double deltaY) {
		return drag(click.y()) || super.mouseDragged(click, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		return release() || super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		return scrollBy(mouseX, mouseY, vertical) || super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}

	@Override
	public void removed() {
		for (ScannerEntry entry : entries.values()) {
			entry.close();
		}
		entries.clear();
	}

	@Override
	public void close() {
		AutoJoin.setBrowsingQueue(null);
		this.client.setScreen(parent);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
