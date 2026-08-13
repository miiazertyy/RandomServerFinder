package com.serverscanner.screen;

import com.serverscanner.config.ScannerConfig;
import com.serverscanner.filter.FilterIcons;
import com.serverscanner.filter.ScannerFilters;
import com.serverscanner.filter.TriState;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Editor for the persistent filter set.
 *
 * <p>Every filter offered on the website is here with the same meaning, plus the handful the v2 API
 * supports that the website does not expose. Values are written straight into the config and saved
 * on Done, so they are still in place next time the game starts.
 *
 * <p>Rows are grouped into labelled sections and scroll inside a panel, because there are twenty of
 * them and the usable height varies a lot with GUI scale.
 */
public class FilterScreen extends Screen {
	private static final int FIELD_HEIGHT = 18;
	private static final int FIELD_ROW = 24;
	private static final int SECTION_ROW = 26;
	private static final int FIELD_WIDTH = 130;
	private static final int PANEL_WIDTH = 340;
	private static final int HEADER_HEIGHT = 42;
	private static final int FOOTER_HEIGHT = 38;
	private static final double RESPONSIVENESS = 16.0;

	// Palette. Kept muted so the panel reads as part of the game's own UI.
	private static final int PANEL_BG = 0xB00E1014;
	private static final int PANEL_BORDER = 0xFF2E3440;
	private static final int SECTION_TEXT = 0xFF7FD1A0;
	private static final int SECTION_RULE = 0x332E3440;
	private static final int LABEL_TEXT = 0xFFDCDCDC;
	private static final int LABEL_TEXT_SET = 0xFFFFFFFF;
	private static final int ROW_HOVER = 0x1AFFFFFF;
	private static final int SUBTITLE = 0xFF8A8F98;
	private static final int RULE = 0xFF2E3440;

	/** Which half of the options this screen is showing. */
	public enum Page {
		/** What a server must look like to appear in the list. */
		FILTERS,
		/** How the mod itself behaves. Nothing here changes which servers are found. */
		SETTINGS
	}

	private final Page page;
	private final Screen parent;
	private final ScannerConfig config = ScannerConfig.get();
	private final ScannerFilters filters = ScannerConfig.get().filters;

	/** Snapshot taken on open so Cancel can put everything back. */
	private final ScannerFilters original = ScannerConfig.get().filters.copy();
	private final boolean originalHideJoined = ScannerConfig.get().hideJoined;
	private final boolean originalHideOffline = ScannerConfig.get().hideOffline;
	private final boolean originalStreamerMode = ScannerConfig.get().streamerMode;

	// The settings page can change these too, and Cancel says it discards changes. Without them it
	// restored the filters, then saved the rest of what had just been edited.
	private final ScannerConfig.ViewMode originalViewMode = ScannerConfig.get().viewMode;
	private final ScannerConfig.LabelMode originalLabelMode = ScannerConfig.get().labelMode;
	private final boolean originalPrefetchInMenu = ScannerConfig.get().prefetchInMenu;
	private final int originalAutoJoinDelay = ScannerConfig.get().autoJoinDelaySeconds;
	private final boolean originalAutoJoinAcceptPacks = ScannerConfig.get().autoJoinAcceptPacks;
	private final boolean originalAutoJoinKeepGoing = ScannerConfig.get().autoJoinKeepGoing;
	private final boolean originalIncludeBedrock = ScannerConfig.get().includeBedrock;
	private final boolean originalProbeNeighbours = ScannerConfig.get().probeNeighbourPorts;

	private final List<Item> items = new ArrayList<>();

	private double scroll;
	private double targetScroll;
	private long lastFrameNanos = System.nanoTime();

	private String validationError;

	/** A section heading, or a labelled control. {@code widget} is null for headings. */
	private static final class Item {
		final String text;
		final ClickableWidget widget;
		/** Pictogram drawn before the label, or null for a section heading. */
		String[] icon;
		final Supplier<Boolean> isSet;
		int y;

		Item(String text, ClickableWidget widget, Supplier<Boolean> isSet) {
			this.text = text;
			this.widget = widget;
			this.isSet = isSet;
		}

		boolean isSection() {
			return widget == null;
		}

		int height() {
			return isSection() ? SECTION_ROW : FIELD_ROW;
		}
	}

	public FilterScreen(Screen parent) {
		this(parent, Page.FILTERS);
	}

	public FilterScreen(Screen parent, Page page) {
		super(Text.literal(page == Page.FILTERS ? "Server Filters" : "Settings"));
		this.parent = parent;
		this.page = page;
	}

	@Override
	protected void init() {
		items.clear();

		int fieldX = panelX() + PANEL_WIDTH - 12 - FIELD_WIDTH;

		if (page == Page.FILTERS) {
			section("randomserverfinder.players");
			text(fieldX, FilterIcons.PLAYERS, "randomserverfinder.player_count", "Exact, or a range: 3, >1, <=5, 1-10",
					() -> filters.playerCount, v -> filters.playerCount = v);
			text(fieldX, FilterIcons.PLAYER_CAP, "randomserverfinder.player_cap", "The server's maximum player slots",
					() -> filters.playerCap, v -> filters.playerCap = v);
			tri(fieldX, FilterIcons.FULL, "randomserverfinder.full", "Whether the server has reached its player cap",
					() -> filters.full, v -> filters.full = v);
			tri(fieldX, FilterIcons.PLAYER_SAMPLE, "randomserverfinder.has_player_sample", "Server reports the player list shown on hover",
					() -> filters.hasPlayerSample, v -> filters.hasPlayerSample = v);

			section("randomserverfinder.server");
			text(fieldX, FilterIcons.VERSION, "randomserverfinder.version", "Pattern matched against the version, e.g. %1.21.11% or Paper%",
					() -> filters.version, v -> filters.version = v);
			text(fieldX, FilterIcons.PROTOCOL, "randomserverfinder.protocol", "Protocol number. 774 is 1.21.11, so this finds servers you can join",
					() -> filters.protocol, v -> filters.protocol = v);
			text(fieldX, FilterIcons.DESCRIPTION, "randomserverfinder.description", "MOTD words in any order; \"quote\" for an exact phrase",
					() -> filters.description, v -> filters.description = v);
			tri(fieldX, FilterIcons.FAVICON, "randomserverfinder.has_favicon", "Server uses a custom icon",
					() -> filters.hasFavicon, v -> filters.hasFavicon = v);
			tri(fieldX, FilterIcons.VANILLA, "randomserverfinder.vanilla", "Unmodded server with a plain version string",
					() -> filters.vanilla, v -> filters.vanilla = v);

			section("randomserverfinder.network");
			text(fieldX, FilterIcons.SUBNET, "randomserverfinder.ip_subnet", "Comma separated, e.g. 1.0.0.0/8, !2.3.4.0/24\nA ! prefix excludes that range.",
					() -> filters.ipSubnet, v -> filters.ipSubnet = v);
			text(fieldX, FilterIcons.PORT, "randomserverfinder.port", "The server's port",
					() -> filters.port, v -> filters.port = v);

			section("randomserverfinder.list");
			toggle(fieldX, FilterIcons.JOINED, "randomserverfinder.hide_joined", "Leave out servers you have already joined",
					() -> config.hideJoined, v -> config.hideJoined = v);
			toggle(fieldX, FilterIcons.REACHABLE, "randomserverfinder.hide_unreachable", "Remove rows if a server stops answering",
					() -> config.hideOffline, v -> config.hideOffline = v);
		} else {
			section("randomserverfinder.display");
			cycle(fieldX, FilterIcons.LAYOUT, "randomserverfinder.layout", "How each server is drawn in the list",
					() -> config.viewMode.displayName(), () -> config.viewMode = config.viewMode.next());
			cycle(fieldX, FilterIcons.HEADLINE, "randomserverfinder.headline", "What each row leads with. A server's own name says far more than its IP.",
					() -> config.labelMode.displayName(), () -> config.labelMode = config.labelMode.next());
			toggle(fieldX, FilterIcons.STREAMER, "randomserverfinder.streamer_mode", "Hide every server address on screen.\n"
							+ "Random servers are usually someone's home machine, and putting one on stream\n"
							+ "tends to get it griefed. Joining and Direct Connect still work normally.",
					() -> config.streamerMode, v -> config.streamerMode = v);
			toggle(fieldX, FilterIcons.NEARBY_PORTS, "randomserverfinder.nearby_ports", "After finding a server, look at a few ports either side on that\n"
							+ "same machine. People who run one often run several, and the published\n"
							+ "lists miss plenty of them.\n"
							+ "This is port scanning from your connection, so it is off by default.",
					() -> config.probeNeighbourPorts, v -> config.probeNeighbourPorts = v);
			toggle(fieldX, FilterIcons.BEDROCK, "randomserverfinder.bedrock_servers", "Also look for Bedrock Edition servers, about 30,000 more.\n"
							+ "Joining one needs ViaFabricPlus signed in to a Bedrock account:\n"
							+ "without that they are found and listed, but not playable.\n",
					() -> config.includeBedrock, v -> config.includeBedrock = v);
			toggle(fieldX, FilterIcons.SEARCH, "randomserverfinder.search_in_menu", "Keep looking for servers while you sit on the title screen,\n"
							+ "so the list is ready before you open it. Never runs while you are in a world.",
					() -> config.prefetchInMenu, v -> config.prefetchInMenu = v);

			section("randomserverfinder.auto_join");
			text(fieldX, FilterIcons.DELAY, "randomserverfinder.auto_join_wait", "Seconds between auto-join attempts.\n"
							+ "Each attempt is a real login and goes through Mojang's session server,\n"
							+ "so trying too fast gets you rate limited. Five is a safe default.",
					() -> Integer.toString(config.autoJoinDelaySeconds),
					v -> config.autoJoinDelaySeconds = parseWholeNumber(v, config.autoJoinDelaySeconds));
			toggle(fieldX, FilterIcons.RESOURCE_PACK, "randomserverfinder.accept_packs", "Say yes to a server's resource pack while auto-joining.\n"
							+ "The prompt waits for a click, and nobody is watching, so leaving this off\n"
							+ "means a run can stall on the first server that offers one.",
					() -> config.autoJoinAcceptPacks, v -> config.autoJoinAcceptPacks = v);
			toggle(fieldX, FilterIcons.AUTO_JOIN, "randomserverfinder.keep_hopping", "Carry on auto-joining after you leave a server.\n"
							+ "Turn this off to stop as soon as one server lets you in.",
					() -> config.autoJoinKeepGoing, v -> config.autoJoinKeepGoing = v);
		}

		for (Item item : items) {
			if (item.widget != null) {
				addDrawableChild(item.widget);
			}
		}

		int centre = this.width / 2;
		int buttonY = this.height - 27;
		addDrawableChild(ButtonWidget.builder(Text.translatable("randomserverfinder.reset_all"), b -> {
					filters.reset();
					this.clearAndInit();
				})
				.dimensions(centre - 154, buttonY, 100, 20).build());

		addDrawableChild(ButtonWidget.builder(Text.translatable("randomserverfinder.cancel"), b -> cancel())
				.dimensions(centre - 50, buttonY, 100, 20).build());

		addDrawableChild(ButtonWidget.builder(Text.translatable("randomserverfinder.done"), b -> this.close())
				.dimensions(centre + 54, buttonY, 100, 20).build());

		layoutItems();
	}

	// --- Item builders ---------------------------------------------------------------------

	private void section(String titleKey) {
		String title = net.minecraft.client.resource.language.I18n.translate(titleKey);
		items.add(new Item(title, null, null));
	}

	/** Adds a row, tagging it with the pictogram drawn before its label. */
	/** Keeps the previous value for anything that is not a whole number, including a half-typed one. */
	private static int parseWholeNumber(String value, int fallback) {
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private void addRow(String[] icon, Item item) {
		item.icon = icon;
		items.add(item);
	}

	private void text(int x, String[] icon, String labelKey, String tooltipKey, Supplier<String> get, Consumer<String> set) {
		String label = net.minecraft.client.resource.language.I18n.translate(labelKey);
		String tooltip = net.minecraft.client.resource.language.I18n.translate(tooltipKey);
		TextFieldWidget field = new TextFieldWidget(this.textRenderer, x, 0, FIELD_WIDTH, FIELD_HEIGHT,
				Text.literal(label));
		field.setMaxLength(256);
		field.setText(get.get());
		field.setChangedListener(set);
		field.setTooltip(Tooltip.of(Text.literal(tooltip)));
		addRow(icon, new Item(label, field, () -> !get.get().isBlank()));
	}

	private void tri(int x, String[] icon, String labelKey, String tooltipKey, Supplier<TriState> get, Consumer<TriState> set) {
		String label = net.minecraft.client.resource.language.I18n.translate(labelKey);
		String tooltip = net.minecraft.client.resource.language.I18n.translate(tooltipKey);
		ButtonWidget[] holder = new ButtonWidget[1];
		holder[0] = ButtonWidget.builder(triText(get.get()), b -> {
					TriState next = get.get().next();
					set.accept(next);
					holder[0].setMessage(triText(next));
				})
				.dimensions(x, 0, FIELD_WIDTH, FIELD_HEIGHT)
				.tooltip(Tooltip.of(Text.literal(tooltip + "\n\nAny = filter not applied")))
				.build();
		addRow(icon, new Item(label, holder[0], () -> get.get().isSet()));
	}

	private void toggle(int x, String[] icon, String labelKey, String tooltipKey, Supplier<Boolean> get, Consumer<Boolean> set) {
		String label = net.minecraft.client.resource.language.I18n.translate(labelKey);
		String tooltip = net.minecraft.client.resource.language.I18n.translate(tooltipKey);
		ButtonWidget[] holder = new ButtonWidget[1];
		holder[0] = ButtonWidget.builder(onOffText(get.get()), b -> {
					boolean next = !get.get();
					set.accept(next);
					holder[0].setMessage(onOffText(next));
				})
				.dimensions(x, 0, FIELD_WIDTH, FIELD_HEIGHT)
				.tooltip(Tooltip.of(Text.literal(tooltip)))
				.build();
		addRow(icon, new Item(label, holder[0], get));
	}

	/** A row that cycles through a fixed set of values, showing the current one. */
	private void cycle(int x, String[] icon, String labelKey, String tooltipKey, Supplier<String> display, Runnable advance) {
		String label = net.minecraft.client.resource.language.I18n.translate(labelKey);
		String tooltip = net.minecraft.client.resource.language.I18n.translate(tooltipKey);
		ButtonWidget[] holder = new ButtonWidget[1];
		holder[0] = ButtonWidget.builder(Text.literal(display.get()), b -> {
					advance.run();
					holder[0].setMessage(Text.literal(display.get()));
				})
				.dimensions(x, 0, FIELD_WIDTH, FIELD_HEIGHT)
				.tooltip(Tooltip.of(Text.literal(tooltip)))
				.build();
		addRow(icon, new Item(label, holder[0], () -> false));
	}

	private static Text triText(TriState state) {
		return switch (state) {
			case UNSET -> Text.translatable("randomserverfinder.any").formatted(Formatting.DARK_GRAY);
			case TRUE -> Text.translatable("randomserverfinder.yes").formatted(Formatting.GREEN);
			case FALSE -> Text.translatable("randomserverfinder.no").formatted(Formatting.RED);
		};
	}

	private static Text onOffText(boolean value) {
		return value ? Text.translatable("randomserverfinder.on").formatted(Formatting.GREEN)
				: Text.translatable("randomserverfinder.off").formatted(Formatting.DARK_GRAY);
	}

	private static int parseIntOrZero(String value) {
		try {
			return Math.max(0, Integer.parseInt(value.trim()));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	// --- Layout ----------------------------------------------------------------------------

	private int panelX() {
		return (this.width - PANEL_WIDTH) / 2;
	}

	private int viewTop() {
		return HEADER_HEIGHT;
	}

	private int viewBottom() {
		return this.height - FOOTER_HEIGHT;
	}

	private int contentHeight() {
		int total = 0;
		for (Item item : items) total += item.height();
		return total + 8;
	}

	private int getMaxScroll() {
		return Math.max(0, contentHeight() - (viewBottom() - viewTop()));
	}

	/** Assigns each item its y for the current scroll and hides the ones outside the panel. */
	private void layoutItems() {
		int top = viewTop();
		int bottom = viewBottom();
		int offset = top + 4 - (int) Math.round(scroll);

		for (Item item : items) {
			item.y = offset;
			if (item.widget != null) {
				item.widget.setY(offset + (FIELD_ROW - FIELD_HEIGHT) / 2);
				// A widget only shows when it fits entirely inside the panel. Vanilla widgets do not clip
				// themselves, so a partially scrolled row would otherwise draw over the title and footer.
				boolean visible = item.widget.getY() >= top && item.widget.getY() + FIELD_HEIGHT <= bottom;
				item.widget.visible = visible;
				item.widget.active = visible;
			}
			offset += item.height();
		}
	}

	// --- Rendering ---------------------------------------------------------------------------

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		updateScrollAnimation();
		layoutItems();

		// The screen's background is already drawn by renderWithTooltip before this runs; calling
		// renderBackground again here would blur twice in one frame, which the renderer rejects.
		int px = panelX();
		int top = viewTop();
		int bottom = viewBottom();

		// Panel.
		context.fill(px, top, px + PANEL_WIDTH, bottom, PANEL_BG);
		context.fill(px, top, px + PANEL_WIDTH, top + 1, PANEL_BORDER);
		context.fill(px, bottom - 1, px + PANEL_WIDTH, bottom, PANEL_BORDER);
		context.fill(px, top, px + 1, bottom, PANEL_BORDER);
		context.fill(px + PANEL_WIDTH - 1, top, px + PANEL_WIDTH, bottom, PANEL_BORDER);

		// Header.
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12, 0xFFFFFFFF);
		// The filter count belongs to the filter page. On Settings it counted something that
		// is not on screen there, so that header is just the title.
		if (page == Page.FILTERS) {
			int active = filters.activeCount();
			String subtitle = active == 0
					? "Same filters as the website, saved automatically"
					: active + (active == 1 ? " filter active" : " filters active");
			context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(subtitle),
					this.width / 2, 24, active == 0 ? SUBTITLE : SECTION_TEXT);
		}

		context.enableScissor(px + 1, top + 1, px + PANEL_WIDTH - 1, bottom - 1);
		renderItems(context, mouseX, mouseY, px, top, bottom);
		context.disableScissor();

		// The widgets themselves are drawn by the screen, and are already clipped by visibility.
		super.render(context, mouseX, mouseY, deltaTicks);

		renderScrollbar(context, px, top, bottom);

		// Footer.
		context.fill(px, bottom + 6, px + PANEL_WIDTH, bottom + 7, RULE);
		if (validationError != null) {
			context.drawCenteredTextWithShadow(this.textRenderer,
					Text.literal(validationError).formatted(Formatting.RED),
					this.width / 2, bottom + 13, 0xFFFF5555);
		}
	}

	private void renderItems(DrawContext context, int mouseX, int mouseY, int px, int top, int bottom) {
		boolean pointerInPanel = mouseX >= px && mouseX < px + PANEL_WIDTH && mouseY >= top && mouseY < bottom;

		for (Item item : items) {
			if (item.y + item.height() < top || item.y > bottom) continue;

			if (item.isSection()) {
				int textY = item.y + 12;
				context.drawTextWithShadow(this.textRenderer, Text.literal(item.text.toUpperCase()),
						px + 12, textY, SECTION_TEXT);
				int ruleX = px + 16 + this.textRenderer.getWidth(item.text.toUpperCase());
				context.fill(ruleX, textY + 3, px + PANEL_WIDTH - 12, textY + 4, SECTION_RULE);
				continue;
			}

			boolean hovered = pointerInPanel && mouseY >= item.y && mouseY < item.y + FIELD_ROW;
			if (hovered) {
				context.fill(px + 1, item.y, px + PANEL_WIDTH - 1, item.y + FIELD_ROW, ROW_HOVER);
			}

			boolean set = item.isSet != null && Boolean.TRUE.equals(item.isSet.get());
			if (set) {
				// A small accent bar marks the filters that are actually doing something.
				context.fill(px + 4, item.y + 5, px + 6, item.y + FIELD_ROW - 5, SECTION_TEXT);
			}

			int labelX = px + 12;
			if (item.icon != null) {
				Icons.draw(context, item.icon, px + 10, item.y + (FIELD_ROW - FilterIcons.SIZE) / 2);
				labelX = px + 10 + FilterIcons.SIZE + 5;
			}
			context.drawTextWithShadow(this.textRenderer, Text.literal(item.text),
					labelX, item.y + (FIELD_ROW - 8) / 2, set ? LABEL_TEXT_SET : LABEL_TEXT);
		}
	}

	private void renderScrollbar(DrawContext context, int px, int top, int bottom) {
		int maxScroll = getMaxScroll();
		if (maxScroll <= 0) return;

		int viewHeight = bottom - top;
		int barX = px + PANEL_WIDTH + 4;
		int handleHeight = Math.max(20, viewHeight * viewHeight / contentHeight());
		int handleY = top + (int) ((scroll / maxScroll) * (viewHeight - handleHeight));

		context.fill(barX, top, barX + 4, bottom, 0x40000000);
		context.fill(barX, handleY, barX + 4, handleY + handleHeight, PANEL_BORDER);
		context.fill(barX, handleY, barX + 3, handleY + handleHeight - 1, 0xFF8A8F98);
	}

	private void updateScrollAnimation() {
		long now = System.nanoTime();
		double dt = Math.max(0.0, Math.min((now - lastFrameNanos) / 1_000_000_000.0, 0.1));
		lastFrameNanos = now;

		targetScroll = Math.max(0, Math.min(targetScroll, getMaxScroll()));
		double delta = targetScroll - scroll;
		if (Math.abs(delta) < 0.05) {
			scroll = targetScroll;
			return;
		}
		scroll += delta * (1.0 - Math.exp(-RESPONSIVENESS * dt));
	}

	// --- Input -------------------------------------------------------------------------------

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (mouseY >= viewTop() && mouseY < viewBottom()) {
			targetScroll = Math.max(0, Math.min(targetScroll - vertical * FIELD_ROW * 2, getMaxScroll()));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		// Clicks outside the panel must not reach rows that are scrolled behind the header/footer.
		if (mouseY < viewTop() || mouseY >= viewBottom()) {
			for (Item item : items) {
				ClickableWidget w = item.widget;
				if (w != null && w.visible && mouseY >= w.getY() && mouseY < w.getY() + FIELD_HEIGHT) {
					return false;
				}
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	// --- Closing ------------------------------------------------------------------------------

	private void cancel() {
		filters.copyFrom(original);
		config.hideJoined = originalHideJoined;
		config.hideOffline = originalHideOffline;
		config.streamerMode = originalStreamerMode;
		config.viewMode = originalViewMode;
		config.labelMode = originalLabelMode;
		config.prefetchInMenu = originalPrefetchInMenu;
		config.autoJoinDelaySeconds = originalAutoJoinDelay;
		config.autoJoinAcceptPacks = originalAutoJoinAcceptPacks;
		config.autoJoinKeepGoing = originalAutoJoinKeepGoing;
		config.includeBedrock = originalIncludeBedrock;
		config.probeNeighbourPorts = originalProbeNeighbours;
		config.save();

		// Some settings decide which addresses are searched at all, and that list is built once when
		// the search starts. Changing one and leaving it to take effect "next time" means the toggle
		// appears to do nothing, so the search is restarted here instead.
		if (config.includeBedrock != originalIncludeBedrock) {
			com.serverscanner.local.ScannerSession.restart();
		}
		this.client.setScreen(parent);
	}

	@Override
	public void close() {
		// Catch bad input here rather than letting it silently produce an empty list.
		String problem = validate();
		if (problem != null) {
			validationError = problem;
			return;
		}
		config.save();
		this.client.setScreen(parent);
	}

	private String validate() {
		if (!filters.playerCap.isBlank() && parseIntOrNull(filters.playerCap) == null) {
			return "Player cap must be a number";
		}
		if (!filters.protocol.isBlank() && parseIntOrNull(filters.protocol) == null) {
			return "Protocol must be a number";
		}
		if (!filters.port.isBlank() && parseIntOrNull(filters.port) == null) {
			return "Port must be a number";
		}
		if (!filters.playerCount.isBlank()) {
			String bare = filters.playerCount.trim().replaceAll("^([<>]=?)", "");
			for (String part : bare.split("-")) {
				if (parseIntOrNull(part) == null) {
					return "Player count must be a number or a range like 1-10";
				}
			}
		}
		return null;
	}

	private static Integer parseIntOrNull(String text) {
		try {
			return Integer.parseInt(text.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
