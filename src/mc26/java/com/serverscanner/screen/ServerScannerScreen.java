package com.serverscanner.screen;

import com.serverscanner.config.JoinedServers;
import com.serverscanner.config.ScannerConfig;
import com.serverscanner.filter.FilterChip;
import com.serverscanner.filter.FilterIcons;
import com.serverscanner.local.LocalServerFeed;
import com.serverscanner.local.LoginProbe;
import com.serverscanner.local.ScannerSession;
import com.serverscanner.party.PartyManager;

import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * The finder: a scrolling list of random servers that are online right now, with the controls along
 * the bottom.
 *
 * <p>The search itself lives in {@link ScannerSession} rather than here, so closing this screen
 * keeps everything that was found and reopening it is instant.
 */
public class ServerScannerScreen extends Screen {
	private static final int HEADER_HEIGHT = 52;

	/** First line below the title and status, where the optional header rows begin. */
	private static final int HEADER_BASE = 34;
	private static final int FOOTER_HEIGHT = 58;

	/** Height of one wrapped line of the check result, and how many of them are ever drawn. */
	private static final int CHECK_LINE = 10;
	private static final int CHECK_MAX_LINES = 2;

	/** Tall enough for the ten-pixel pictograms with a little room above and below. */
	private static final int CHIP_HEIGHT = 14;
	private static final int CHIP_PAD = 4;
	private static final int CHIP_GAP = 4;

	private final Screen parent;
	private final ScannerConfig config = ScannerConfig.get();

	private LocalServerFeed feed;
	private ScannerListWidget list;

	private Button joinButton;
	private Button directButton;
	private Button saveButton;
	private Button checkButton;
	private Button autoJoinButton;

	/** Result of the last access check, shown under the buttons until another server is picked. */
	private String checkAddress;
	private Component checkResult;

	/** Set only when leaving for a screen we will come back from. */
	private boolean returningHere;

	/**
	 * Set once a server has been written to servers.dat during this visit.
	 *
	 * <p>The multiplayer screen reads that file once, in init, and showing it again does not run
	 * init a second time: {@code init(width, height)} skips it once the screen has been initialised,
	 * {@code resize} only repositions widgets, and {@code clearAndInit} is protected. So the entry
	 * lands on disk and stays invisible there, which is what "Save does nothing" looks like. Leaving
	 * hands back a freshly built screen instead, which reads the file on the way up.
	 */
	private boolean savedToServerList;

	/** Rebuilt each frame: the header's height depends on how many of these there are. */
	private java.util.List<FilterChip> chips = java.util.List.of();

	public ServerScannerScreen(Screen parent) {
		super(Component.translatable("randomserverfinder.random_server_finder"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		ScannerSession.setScreenOpen(true);

		LocalServerFeed current = ScannerSession.feed();
		if (feed != current || list == null) {
			feed = current;
			list = new ScannerListWidget(feed, config);
			list.setOnJoin(this::join);
		}
		layoutList();

		int centre = this.width / 2;
		int rowOne = this.height - FOOTER_HEIGHT + 6;
		int rowTwo = rowOne + 24;

		joinButton = addRenderableWidget(Button.builder(Component.translatable("randomserverfinder.join"), b -> joinSelected())
				.bounds(centre - 154, rowOne, 74, 20).build());

		directButton = addRenderableWidget(Button.builder(Component.translatable("randomserverfinder.direct"), b -> openDirectConnect())
				.tooltip(Tooltip.create(Component.translatable("randomserverfinder.open_direct_connect_with")))
				.bounds(centre - 76, rowOne, 74, 20).build());

		saveButton = addRenderableWidget(Button.builder(Component.translatable("randomserverfinder.save"), b -> saveToServerList())
				.tooltip(Tooltip.create(Component.translatable("randomserverfinder.add_this_server_to_your")))
				.bounds(centre + 2, rowOne, 74, 20).build());

		checkButton = addRenderableWidget(Button.builder(Component.translatable("randomserverfinder.check"), b -> probeSelected())
				.tooltip(Tooltip.create(Component.translatable("randomserverfinder.tip.ask_this_server_whether_it")))
				.bounds(centre + 80, rowOne, 74, 20).build());

		addRenderableWidget(Button.builder(Component.translatable("randomserverfinder.filters"), b -> {
					returningHere = true;
					this.minecraft.setScreenAndShow(new FilterScreen(this));
				})
				.bounds(centre - 154, rowTwo, 74, 20).build());

		addRenderableWidget(Button.builder(partyButtonText(), b -> {
					returningHere = true;
					this.minecraft.setScreenAndShow(new PartyScreen(this));
				})
				.tooltip(Tooltip.create(Component.translatable("randomserverfinder.hop_between_servers_together")))
				.bounds(centre - 76, rowTwo, 74, 20).build());

		addRenderableWidget(Button.builder(Component.translatable("randomserverfinder.history"), b -> {
					returningHere = true;
					this.minecraft.setScreenAndShow(new JoinHistoryScreen(this));
				})
				.tooltip(Tooltip.create(Component.translatable("randomserverfinder.servers_you_have_joined_from")))
				.bounds(centre + 2, rowTwo, 74, 20).build());

		addRenderableWidget(Button.builder(Component.translatable("randomserverfinder.back"), b -> this.onClose())
				.bounds(centre + 80, rowTwo, 74, 20).build());

		// Top-left, opposite auto-join and reshuffle. Filters decide which servers appear;
		// these decide how the mod behaves, which is a different question.
		addRenderableWidget(Button.builder(Component.translatable("randomserverfinder.settings"), b -> {
					returningHere = true;
					this.minecraft.setScreenAndShow(new FilterScreen(this, FilterScreen.Page.SETTINGS));
				})
				.tooltip(Tooltip.create(Component.translatable("randomserverfinder.layout_streamer_mode_auto")))
				.bounds(6, 6, 74, 20).build());

		autoJoinButton = addRenderableWidget(Button.builder(autoJoinText(), b -> toggleAutoJoin())
				.tooltip(Tooltip.create(Component.translatable("randomserverfinder.tip.keep_trying_random_servers")))
				.bounds(this.width - 156, 6, 74, 20).build());
		// Kept out of the two main rows so they stay centred and fit at any GUI scale.
		addRenderableWidget(Button.builder(Component.translatable("randomserverfinder.reshuffle"), b -> {
					ScannerSession.restart();
					feed = null;
					if (list != null) list.close();
					list = null;
					this.rebuildWidgets();
				})
				.tooltip(Tooltip.create(Component.translatable("randomserverfinder.throw_these_results_away_and")))
				.bounds(this.width - 78, 6, 72, 20).build());

		updateButtonStates();
	}

	/** Called by the history screen, which saves through the same file but sits behind this one. */
	void markSavedToServerList() {
		savedToServerList = true;
	}

	/** The row of chips sits under the party line when there is one. */
	private int chipsY() {
		return HEADER_BASE + (PartyManager.isActive() ? 12 : 0);
	}

	/**
	 * Where the list starts, which is however far down the header actually reaches.
	 *
	 * <p>It was a fixed 52, chosen for a header with no party line. Add one and the chips moved down
	 * onto the first row of servers, because nothing connected the two numbers.
	 */
	private int listTop() {
		int top = chipsY() + (chips.isEmpty() ? 0 : CHIP_HEIGHT + 4);
		return Math.max(HEADER_HEIGHT, top);
	}

	/** Positioned every frame, because a party line or a chip can appear while the screen is open. */
	private void layoutList() {
		int top = listTop();
		// The check result used to be drawn eleven pixels above this edge, which put it on top of the
		// last row rather than under the list. The list gives up that strip instead.
		int bottom = this.height - FOOTER_HEIGHT - checkHeight();
		list.setBounds(20, top, this.width - 40, Math.max(40, bottom - top));
	}

	/** Vertical space the check result needs, zero when there is nothing to report. */
	private int checkHeight() {
		int lines = checkLines().size();
		return lines == 0 ? 0 : lines * CHECK_LINE + 3;
	}

	/**
	 * The check result, wrapped to the screen.
	 *
	 * <p>Reasons run to a full sentence, so one line ran off both edges. Two is enough for every
	 * message the check produces and keeps the list from shrinking noticeably.
	 */
	private List<FormattedCharSequence> checkLines() {
		if (checkResult == null) return List.of();
		List<FormattedCharSequence> lines = this.font.split(checkResult, this.width - 60);
		return lines.size() <= CHECK_MAX_LINES ? lines : lines.subList(0, CHECK_MAX_LINES);
	}

	private Component autoJoinText() {
		if (!com.serverscanner.AutoJoin.isActive()) return Component.translatable("randomserverfinder.auto_join");
		int attempts = com.serverscanner.AutoJoin.attempts();
		return Component.literal(attempts > 0 ? "Stop (" + attempts + ")" : "Stop");
	}

	private void toggleAutoJoin() {
		if (com.serverscanner.AutoJoin.isActive()) {
			com.serverscanner.AutoJoin.stop();
		} else {
			com.serverscanner.AutoJoin.start();
		}
		updateButtonStates();
	}

	private Component partyButtonText() {
		int members = PartyManager.getMembers().size();
		return Component.literal(PartyManager.isActive() ? "Party (" + members + ")" : "Party");
	}

	@Override
	public void tick() {
		adoptRestartedFeed();
		updateButtonStates();
	}

	/**
	 * Picks up a search that was restarted while this screen was open.
	 *
	 * <p>Returning here does not re-run init, so the fields set there still point at the discarded
	 * feed: the screen would keep drawing results from a search nothing is running any more.
	 */
	private void adoptRestartedFeed() {
		LocalServerFeed current = ScannerSession.feed();
		if (feed == current) return;

		feed = current;
		if (list != null) list.close();
		list = new ScannerListWidget(feed, config);
		list.setOnJoin(this::join);
		layoutList();
	}

	private void updateButtonStates() {
		boolean hasSelection = list != null && list.getSelected() != null;
		if (joinButton != null) joinButton.active = hasSelection;
		if (directButton != null) directButton.active = hasSelection;
		if (saveButton != null) saveButton.active = hasSelection;
		if (checkButton != null) checkButton.active = hasSelection;
		if (autoJoinButton != null) {
			autoJoinButton.setMessage(autoJoinText());
			// A party member is taken along by the host, so there is nothing here to steer.
			autoJoinButton.active = com.serverscanner.AutoJoin.canStart();
		}

		// A stale result under a different server would be misleading.
		if (hasSelection && checkAddress != null
				&& !checkAddress.equals(list.getSelected().getServer().address())) {
			checkResult = null;
			checkAddress = null;
		}
	}

	/** Asks the selected server what it takes to get in. See {@link LoginProbe}. */
	private void probeSelected() {
		ScannerEntry selected = list.getSelected();
		if (selected == null) return;

		String address = selected.getServer().address();
		checkAddress = address;
		checkResult = Component.translatable("randomserverfinder.checking_address", address).withStyle(ChatFormatting.GRAY);
		checkButton.active = false;

		String host = selected.getServer().ipString();
		int port = selected.getServer().port;
		String username = this.minecraft.getUser().getName();
		int protocol = net.minecraft.SharedConstants.RELEASE_NETWORK_PROTOCOL_VERSION;

		LoginProbe.probe(host, port, protocol, username).whenComplete((report, error) ->
				this.minecraft.execute(() -> {
					// The user may have moved on while we waited.
					if (!address.equals(checkAddress)) return;
					if (error != null || report == null) {
						checkResult = Component.translatable("randomserverfinder.check_failed").withStyle(ChatFormatting.RED);
						return;
					}
					checkResult = Component.literal(report.result().label + ": " + report.reason())
							.withStyle(colourFor(report.result()));
				}));
	}

	private static ChatFormatting colourFor(LoginProbe.Result result) {
		return switch (result) {
			case CRACKED_OPEN -> ChatFormatting.GREEN;
			case ONLINE_MODE -> ChatFormatting.AQUA;
			case WHITELISTED, BANNED -> ChatFormatting.RED;
			case WRONG_VERSION, FULL, NEEDS_MODS, REJECTED -> ChatFormatting.GOLD;
			case UNKNOWN -> ChatFormatting.GRAY;
		};
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
		super.extractRenderState(context, mouseX, mouseY, deltaTicks);

		com.serverscanner.compat.Draw.centered(context, this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);
		com.serverscanner.compat.Draw.centered(context, this.font, statusLine(), this.width / 2, 23, 0xFFA0A0A0);

		chips = config.filters.activeSummary();
		if (config.hideJoined) chips.add(FilterChip.flag(FilterIcons.JOINED, false));
		if (config.hideOffline) chips.add(FilterChip.flag(FilterIcons.REACHABLE, true));

		renderPartyLine(context);
		renderActiveFilters(context);

		layoutList();
		list.render(context, mouseX, mouseY);

		int checkY = this.height - FOOTER_HEIGHT - checkHeight() + 3;
		for (FormattedCharSequence line : checkLines()) {
			com.serverscanner.compat.Draw.centered(context, this.font, line, this.width / 2, checkY, 0xFFFFFFFF);
			checkY += CHECK_LINE;
		}

		String error = feed.getError();
		if (error != null) {
			com.serverscanner.compat.Draw.centered(context, this.font,
					Component.literal(error).withStyle(ChatFormatting.RED),
					this.width / 2, listTop() + 8, 0xFFFF5555);
		} else if (feed.size() == 0) {
			renderEmptyState(context);
		}
	}

	/**
	 * Explains an empty list.
	 *
	 * <p>Nearly always a filter that is narrower than it looks — "players = 1" matches only servers
	 * with exactly one person on them, which is rare enough to look like the mod is broken. So once
	 * enough addresses have been tried to be confident, say plainly that the filters are the reason
	 * and where to change them.
	 */
	private void renderEmptyState(GuiGraphicsExtractor context) {
		boolean filtered = config.filters.activeCount() > 0 || config.hideJoined || config.hideOffline;
		int y = listTop() + 8;

		if (!filtered) {
			// With no filters at all, roughly nine in ten addresses answer. Hundreds in a row
			// answering nothing is not bad luck — it means the pings are not getting out.
			if (feed.checkedCount() > 400) {
				com.serverscanner.compat.Draw.centered(context, this.font,
						Component.literal("Checked " + String.format("%,d", feed.checkedCount())
								+ " addresses, none answered").withStyle(ChatFormatting.YELLOW),
						this.width / 2, y, 0xFFFFFF55);
				com.serverscanner.compat.Draw.centered(context, this.font,
						Component.translatable("randomserverfinder.normally_most_of_them_do_so"),
						this.width / 2, y + 12, 0xFFA0A0A0);
				com.serverscanner.compat.Draw.centered(context, this.font,
						Component.translatable("randomserverfinder.connections_a_firewall_vpn"),
						this.width / 2, y + 23, 0xFFA0A0A0);
				return;
			}
			com.serverscanner.compat.Draw.centered(context, this.font,
					Component.literal(feed.isExhausted() ? "No servers answered." : "Looking for servers..."),
					this.width / 2, y, 0xFFFFFFFF);
			return;
		}

		// Give the search a fair chance before blaming the filters.
		boolean searchedEnough = feed.isExhausted() || feed.checkedCount() > 1500;
		if (!searchedEnough) {
			com.serverscanner.compat.Draw.centered(context, this.font,
					Component.translatable("randomserverfinder.looking_for_servers_matching"),
					this.width / 2, y, 0xFFFFFFFF);
			return;
		}

		com.serverscanner.compat.Draw.centered(context, this.font,
				Component.translatable("randomserverfinder.no_servers_match_your").withStyle(ChatFormatting.YELLOW),
				this.width / 2, y, 0xFFFFFF55);
		com.serverscanner.compat.Draw.centered(context, this.font,
				Component.translatable("randomserverfinder.the_chips_above_show_what_is"),
				this.width / 2, y + 12, 0xFFA0A0A0);
		com.serverscanner.compat.Draw.centered(context, this.font,
				Component.translatable("randomserverfinder.open_filters_and_press_reset"),
				this.width / 2, y + 23, 0xFFA0A0A0);
	}

	/** Names everyone in the party, so it is clear who follows you when you join something. */
	private void renderPartyLine(GuiGraphicsExtractor context) {
		if (!PartyManager.isActive()) return;

		java.util.List<String> members = PartyManager.getMembers();
		String text = members.isEmpty()
				? "Party: waiting for friends to connect"
				: "Party: " + String.join(", ", members);
		com.serverscanner.compat.Draw.centered(context, this.font, Component.literal(text),
				this.width / 2, 34, 0xFF7FD1A0);
	}

	/**
	 * Draws the filters that are actually doing something as a row of chips under the title, so it
	 * is obvious why the results look the way they do without opening the filter screen.
	 */
	private void renderActiveFilters(GuiGraphicsExtractor context) {
		if (chips.isEmpty()) return;

		int totalWidth = -CHIP_GAP;
		for (FilterChip chip : chips) totalWidth += chipWidth(chip) + CHIP_GAP;

		int x = this.width / 2 - totalWidth / 2;
		int y = chipsY();
		for (FilterChip chip : chips) {
			int w = chipWidth(chip);
			context.fill(x, y, x + w, y + CHIP_HEIGHT, 0x66000000);
			context.fill(x, y, x + 1, y + CHIP_HEIGHT, 0xFF7FD1A0);
			Icons.draw(context, chip.icon(), x + CHIP_PAD, y + 2, chip.struck());
			if (!chip.value().isEmpty()) {
				context.text(this.font, chip.value(),
						x + CHIP_PAD + FilterIcons.SIZE + 3, y + 3, 0xFFD0D0D0);
			}
			x += w + CHIP_GAP;
		}
	}

	private int chipWidth(FilterChip chip) {
		int w = CHIP_PAD + FilterIcons.SIZE + CHIP_PAD;
		if (!chip.value().isEmpty()) w += 3 + this.font.width(chip.value());
		return w;
	}

	private Component statusLine() {
		StringBuilder sb = new StringBuilder();
		sb.append(feed.size()).append(" online");

		int total = feed.getTotal();
		if (total >= 0) {
			// The old wording called the whole list "checked addresses", which hid how far the
			// search had actually got — and so hid that a narrow filter was rejecting everything.
			sb.append("  •  ").append(String.format("%,d", feed.checkedCount()))
					.append(" of ").append(String.format("%,d", total)).append(" checked");
		}
		// Nothing when the search is merely paused: it stops once it has enough to fill the list and
		// starts again the moment you scroll, so saying so was a running commentary on something
		// that needs no attention.
		if (feed.isLoading()) {
			sb.append("  •  searching...");
		} else if (feed.isExhausted()) {
			sb.append("  •  every address checked");
		}
		return Component.literal(sb.toString());
	}

	// --- Actions --------------------------------------------------------------------------

	private void joinSelected() {
		ScannerEntry selected = list.getSelected();
		if (selected != null) join(selected);
	}

	private void join(ScannerEntry entry) {
		ServerData info = entry.getServerInfo();
		// These servers never pass through the vanilla list, so ViaFabricPlus has had no chance to
		// be told what this one speaks. Without that it falls back to the global target version and
		// the server turns us away as an incompatible version. No-op when it is not installed.
		if (entry.getServer().bedrock) {
			com.serverscanner.local.ViaFabricPlusBridge.forceBedrock(info);
		} else {
			com.serverscanner.local.ViaFabricPlusBridge.forceVersion(info, entry.getServer().protocol());
		}
		JoinedServers.get().record(entry.getServer().address(), entry.displayName(),
				entry.getServer().versionName());
		// If we are hosting a party, everyone comes with us.
		PartyManager.announceTravel(entry.getServer().address());
		releaseIcons();
		ConnectScreen.startConnecting(this, this.minecraft, ServerAddress.parseString(info.ip), info, false, null);
	}

	/** Opens vanilla's Direct Connect with the selected address already filled in. */
	private void openDirectConnect() {
		ScannerEntry selected = list.getSelected();
		if (selected == null) return;

		String address = selected.getServer().address();
		String name = selected.displayName();
		ServerData target = new ServerData(name, address, ServerData.Type.OTHER);

		returningHere = true;
		this.minecraft.setScreenAndShow(new DirectJoinServerScreen(this, confirmed -> {
			if (!confirmed) {
				this.minecraft.setScreenAndShow(this);
				return;
			}
			ServerList servers = new ServerList(this.minecraft);
			servers.load();
			ServerData existing = servers.get(target.ip);
			if (existing == null) {
				servers.add(target, true);
				servers.save();
				existing = target;
			}
			JoinedServers.get().record(target.ip, name, selected.getServer().versionName());
			releaseIcons();
			ConnectScreen.startConnecting(this, this.minecraft, ServerAddress.parseString(existing.ip), existing, false, null);
		}, target));
	}

	/** Adds the selected server to the normal multiplayer list so it is there next time. */
	private void saveToServerList() {
		ScannerEntry selected = list.getSelected();
		if (selected == null) return;

		String address = selected.getServer().address();
		ServerList servers = new ServerList(this.minecraft);
		servers.load();
		if (servers.get(address) != null) {
			return; // Already saved.
		}
		servers.add(new ServerData(selected.displayName(), address, ServerData.Type.OTHER), false);
		servers.save();
		savedToServerList = true;
	}

	private void releaseIcons() {
		if (list != null) list.close();
		list = null;
	}

	// --- Input ---------------------------------------------------------------------------

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		if (list.mouseClicked(click, doubled)) {
			updateButtonStates();
			return true;
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
		return list.mouseDragged(click, deltaX, deltaY) || super.mouseDragged(click, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent click) {
		return list.mouseReleased(click) || super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		return list.mouseScrolled(mouseX, mouseY, horizontal, vertical)
				|| super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}

	@Override
	public boolean keyPressed(KeyEvent input) {
		return list.handleNavigationKey(input.key()) || super.keyPressed(input);
	}

	@Override
	public void onClose() {
		if (savedToServerList && parent instanceof JoinMultiplayerScreen) {
			// A fresh one, because the retained parent will not re-read servers.dat. See the field.
			this.minecraft.setScreenAndShow(new JoinMultiplayerScreen(new TitleScreen()));
			return;
		}
		this.minecraft.setScreenAndShow(parent);
	}

	@Override
	public void removed() {
		// The feed deliberately outlives this screen; only the GPU textures are handed back.
		if (!returningHere) {
			ScannerSession.setScreenOpen(false);
			releaseIcons();
		}
		returningHere = false;
		config.save();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
