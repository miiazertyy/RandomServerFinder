package com.serverscanner.screen;

import com.serverscanner.config.JoinedServers;
import com.serverscanner.config.ScannerConfig;
import com.serverscanner.filter.FilterChip;
import com.serverscanner.filter.FilterIcons;
import com.serverscanner.local.LocalServerFeed;
import com.serverscanner.local.LoginProbe;
import com.serverscanner.local.ScannerSession;
import com.serverscanner.party.PartyManager;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.DirectConnectScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

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

	private ButtonWidget joinButton;
	private ButtonWidget directButton;
	private ButtonWidget saveButton;
	private ButtonWidget checkButton;
	private ButtonWidget autoJoinButton;

	/** Result of the last access check, shown under the buttons until another server is picked. */
	private String checkAddress;
	private Text checkResult;

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
		super(Text.translatable("randomserverfinder.random_server_finder"));
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

		joinButton = addDrawableChild(ButtonWidget.builder(Text.translatable("randomserverfinder.join"), b -> joinSelected())
				.dimensions(centre - 154, rowOne, 74, 20).build());

		directButton = addDrawableChild(ButtonWidget.builder(Text.translatable("randomserverfinder.direct"), b -> openDirectConnect())
				.tooltip(Tooltip.of(Text.translatable("randomserverfinder.open_direct_connect_with")))
				.dimensions(centre - 76, rowOne, 74, 20).build());

		saveButton = addDrawableChild(ButtonWidget.builder(Text.translatable("randomserverfinder.save"), b -> saveToServerList())
				.tooltip(Tooltip.of(Text.translatable("randomserverfinder.add_this_server_to_your")))
				.dimensions(centre + 2, rowOne, 74, 20).build());

		checkButton = addDrawableChild(ButtonWidget.builder(Text.translatable("randomserverfinder.check"), b -> probeSelected())
				.tooltip(Tooltip.of(Text.translatable("randomserverfinder.tip.ask_this_server_whether_it")))
				.dimensions(centre + 80, rowOne, 74, 20).build());

		addDrawableChild(ButtonWidget.builder(Text.translatable("randomserverfinder.filters"), b -> {
					returningHere = true;
					this.client.setScreen(new FilterScreen(this));
				})
				.dimensions(centre - 154, rowTwo, 74, 20).build());

		addDrawableChild(ButtonWidget.builder(partyButtonText(), b -> {
					returningHere = true;
					this.client.setScreen(new PartyScreen(this));
				})
				.tooltip(Tooltip.of(Text.translatable("randomserverfinder.hop_between_servers_together")))
				.dimensions(centre - 76, rowTwo, 74, 20).build());

		addDrawableChild(ButtonWidget.builder(Text.translatable("randomserverfinder.history"), b -> {
					returningHere = true;
					this.client.setScreen(new JoinHistoryScreen(this));
				})
				.tooltip(Tooltip.of(Text.translatable("randomserverfinder.servers_you_have_joined_from")))
				.dimensions(centre + 2, rowTwo, 74, 20).build());

		addDrawableChild(ButtonWidget.builder(Text.translatable("randomserverfinder.back"), b -> this.close())
				.dimensions(centre + 80, rowTwo, 74, 20).build());

		// Top-left, opposite auto-join and reshuffle. Filters decide which servers appear;
		// these decide how the mod behaves, which is a different question.
		addDrawableChild(ButtonWidget.builder(Text.translatable("randomserverfinder.settings"), b -> {
					returningHere = true;
					this.client.setScreen(new FilterScreen(this, FilterScreen.Page.SETTINGS));
				})
				.tooltip(Tooltip.of(Text.translatable("randomserverfinder.layout_streamer_mode_auto")))
				.dimensions(6, 6, 74, 20).build());

		autoJoinButton = addDrawableChild(ButtonWidget.builder(autoJoinText(), b -> toggleAutoJoin())
				.tooltip(Tooltip.of(Text.translatable("randomserverfinder.tip.keep_trying_random_servers")))
				.dimensions(this.width - 156, 6, 74, 20).build());
		// Kept out of the two main rows so they stay centred and fit at any GUI scale.
		addDrawableChild(ButtonWidget.builder(Text.translatable("randomserverfinder.reshuffle"), b -> {
					ScannerSession.restart();
					feed = null;
					if (list != null) list.close();
					list = null;
					this.clearAndInit();
				})
				.tooltip(Tooltip.of(Text.translatable("randomserverfinder.throw_these_results_away_and")))
				.dimensions(this.width - 78, 6, 72, 20).build());

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
	private List<OrderedText> checkLines() {
		if (checkResult == null) return List.of();
		List<OrderedText> lines = this.textRenderer.wrapLines(checkResult, this.width - 60);
		return lines.size() <= CHECK_MAX_LINES ? lines : lines.subList(0, CHECK_MAX_LINES);
	}

	private Text autoJoinText() {
		if (!com.serverscanner.AutoJoin.isActive()) return Text.translatable("randomserverfinder.auto_join");
		int attempts = com.serverscanner.AutoJoin.attempts();
		return Text.literal(attempts > 0 ? "Stop (" + attempts + ")" : "Stop");
	}

	private void toggleAutoJoin() {
		if (com.serverscanner.AutoJoin.isActive()) {
			com.serverscanner.AutoJoin.stop();
		} else {
			com.serverscanner.AutoJoin.start();
		}
		updateButtonStates();
	}

	private Text partyButtonText() {
		int members = PartyManager.getMembers().size();
		return Text.literal(PartyManager.isActive() ? "Party (" + members + ")" : "Party");
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
		checkResult = Text.translatable("randomserverfinder.checking_address", address).formatted(Formatting.GRAY);
		checkButton.active = false;

		String host = selected.getServer().ipString();
		int port = selected.getServer().port;
		String username = this.client.getSession().getUsername();
		int protocol = net.minecraft.SharedConstants.getGameVersion().getProtocolVersion();

		LoginProbe.probe(host, port, protocol, username).whenComplete((report, error) ->
				this.client.execute(() -> {
					// The user may have moved on while we waited.
					if (!address.equals(checkAddress)) return;
					if (error != null || report == null) {
						checkResult = Text.translatable("randomserverfinder.check_failed").formatted(Formatting.RED);
						return;
					}
					checkResult = Text.literal(report.result().label + ": " + report.reason())
							.formatted(colourFor(report.result()));
				}));
	}

	private static Formatting colourFor(LoginProbe.Result result) {
		return switch (result) {
			case CRACKED_OPEN -> Formatting.GREEN;
			case ONLINE_MODE -> Formatting.AQUA;
			case WHITELISTED, BANNED -> Formatting.RED;
			case WRONG_VERSION, FULL, NEEDS_MODS, REJECTED -> Formatting.GOLD;
			case UNKNOWN -> Formatting.GRAY;
		};
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);

		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, 0xFFFFFFFF);
		context.drawCenteredTextWithShadow(this.textRenderer, statusLine(), this.width / 2, 23, 0xFFA0A0A0);

		chips = config.filters.activeSummary();
		if (config.hideJoined) chips.add(FilterChip.flag(FilterIcons.JOINED, false));
		if (config.hideOffline) chips.add(FilterChip.flag(FilterIcons.REACHABLE, true));

		renderPartyLine(context);
		renderActiveFilters(context);

		layoutList();
		list.render(context, mouseX, mouseY);

		int checkY = this.height - FOOTER_HEIGHT - checkHeight() + 3;
		for (OrderedText line : checkLines()) {
			context.drawCenteredTextWithShadow(this.textRenderer, line, this.width / 2, checkY, 0xFFFFFFFF);
			checkY += CHECK_LINE;
		}

		String error = feed.getError();
		if (error != null) {
			context.drawCenteredTextWithShadow(this.textRenderer,
					Text.literal(error).formatted(Formatting.RED),
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
	private void renderEmptyState(DrawContext context) {
		boolean filtered = config.filters.activeCount() > 0 || config.hideJoined || config.hideOffline;
		int y = listTop() + 8;

		if (!filtered) {
			// With no filters at all, roughly nine in ten addresses answer. Hundreds in a row
			// answering nothing is not bad luck — it means the pings are not getting out.
			if (feed.checkedCount() > 400) {
				context.drawCenteredTextWithShadow(this.textRenderer,
						Text.literal("Checked " + String.format("%,d", feed.checkedCount())
								+ " addresses, none answered").formatted(Formatting.YELLOW),
						this.width / 2, y, 0xFFFFFF55);
				context.drawCenteredTextWithShadow(this.textRenderer,
						Text.translatable("randomserverfinder.normally_most_of_them_do_so"),
						this.width / 2, y + 12, 0xFFA0A0A0);
				context.drawCenteredTextWithShadow(this.textRenderer,
						Text.translatable("randomserverfinder.connections_a_firewall_vpn"),
						this.width / 2, y + 23, 0xFFA0A0A0);
				return;
			}
			context.drawCenteredTextWithShadow(this.textRenderer,
					Text.literal(feed.isExhausted() ? "No servers answered." : "Looking for servers..."),
					this.width / 2, y, 0xFFFFFFFF);
			return;
		}

		// Give the search a fair chance before blaming the filters.
		boolean searchedEnough = feed.isExhausted() || feed.checkedCount() > 1500;
		if (!searchedEnough) {
			context.drawCenteredTextWithShadow(this.textRenderer,
					Text.translatable("randomserverfinder.looking_for_servers_matching"),
					this.width / 2, y, 0xFFFFFFFF);
			return;
		}

		context.drawCenteredTextWithShadow(this.textRenderer,
				Text.translatable("randomserverfinder.no_servers_match_your").formatted(Formatting.YELLOW),
				this.width / 2, y, 0xFFFFFF55);
		context.drawCenteredTextWithShadow(this.textRenderer,
				Text.translatable("randomserverfinder.the_chips_above_show_what_is"),
				this.width / 2, y + 12, 0xFFA0A0A0);
		context.drawCenteredTextWithShadow(this.textRenderer,
				Text.translatable("randomserverfinder.open_filters_and_press_reset"),
				this.width / 2, y + 23, 0xFFA0A0A0);
	}

	/** Names everyone in the party, so it is clear who follows you when you join something. */
	private void renderPartyLine(DrawContext context) {
		if (!PartyManager.isActive()) return;

		java.util.List<String> members = PartyManager.getMembers();
		if (members.isEmpty()) {
			context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Party: waiting for friends to connect"),
					this.width / 2, 34, 0xFF7FD1A0);
			return;
		}

		// The crown goes in the line itself, just before whoever leads, so it is clear whose trips
		// everyone follows.
		int lead = Math.min(Math.max(PartyManager.getLeaderIndex(), 0), members.size() - 1);
		String before = "Party: " + String.join(", ", members.subList(0, lead)) + (lead > 0 ? ", " : "");
		String after = String.join(", ", members.subList(lead, members.size()));
		int crown = com.serverscanner.party.CrownIcon.WIDTH + 3;
		int x = this.width / 2 - (this.textRenderer.getWidth(before) + crown + this.textRenderer.getWidth(after)) / 2;
		context.drawTextWithShadow(this.textRenderer, before, x, 34, 0xFF7FD1A0);
		x += this.textRenderer.getWidth(before);
		Icons.drawCrown(context, x + 1, 34);
		context.drawTextWithShadow(this.textRenderer, after, x + crown, 34, 0xFF7FD1A0);
	}

	/**
	 * Draws the filters that are actually doing something as a row of chips under the title, so it
	 * is obvious why the results look the way they do without opening the filter screen.
	 */
	private void renderActiveFilters(DrawContext context) {
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
				context.drawTextWithShadow(this.textRenderer, chip.value(),
						x + CHIP_PAD + FilterIcons.SIZE + 3, y + 3, 0xFFD0D0D0);
			}
			x += w + CHIP_GAP;
		}
	}

	private int chipWidth(FilterChip chip) {
		int w = CHIP_PAD + FilterIcons.SIZE + CHIP_PAD;
		if (!chip.value().isEmpty()) w += 3 + this.textRenderer.getWidth(chip.value());
		return w;
	}

	private Text statusLine() {
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
		return Text.literal(sb.toString());
	}

	// --- Actions --------------------------------------------------------------------------

	private void joinSelected() {
		ScannerEntry selected = list.getSelected();
		if (selected != null) join(selected);
	}

	private void join(ScannerEntry entry) {
		ServerInfo info = entry.getServerInfo();
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
		ConnectScreen.connect(this, this.client, ServerAddress.parse(info.address), info, false, null);
	}

	/** Opens vanilla's Direct Connect with the selected address already filled in. */
	private void openDirectConnect() {
		ScannerEntry selected = list.getSelected();
		if (selected == null) return;

		String address = selected.getServer().address();
		String name = selected.displayName();
		ServerInfo target = new ServerInfo(name, address, ServerInfo.ServerType.OTHER);

		returningHere = true;
		this.client.setScreen(new DirectConnectScreen(this, confirmed -> {
			if (!confirmed) {
				this.client.setScreen(this);
				return;
			}
			ServerList servers = new ServerList(this.client);
			servers.loadFile();
			ServerInfo existing = servers.get(target.address);
			if (existing == null) {
				servers.add(target, true);
				servers.saveFile();
				existing = target;
			}
			JoinedServers.get().record(target.address, name, selected.getServer().versionName());
			releaseIcons();
			ConnectScreen.connect(this, this.client, ServerAddress.parse(existing.address), existing, false, null);
		}, target));
	}

	/** Adds the selected server to the normal multiplayer list so it is there next time. */
	private void saveToServerList() {
		ScannerEntry selected = list.getSelected();
		if (selected == null) return;

		String address = selected.getServer().address();
		ServerList servers = new ServerList(this.client);
		servers.loadFile();
		if (servers.get(address) != null) {
			return; // Already saved.
		}
		servers.add(new ServerInfo(selected.displayName(), address, ServerInfo.ServerType.OTHER), false);
		servers.saveFile();
		savedToServerList = true;
	}

	private void releaseIcons() {
		if (list != null) list.close();
		list = null;
	}

	// --- Input ---------------------------------------------------------------------------

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (list.mouseClicked(mouseX, mouseY, button, false)) {
			updateButtonStates();
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		return list.mouseDragged(mouseX, mouseY, button, deltaX, deltaY) || super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		return list.mouseReleased(mouseX, mouseY, button) || super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		return list.mouseScrolled(mouseX, mouseY, horizontal, vertical)
				|| super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		return list.handleNavigationKey(keyCode) || super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void close() {
		if (savedToServerList && parent instanceof MultiplayerScreen) {
			// A fresh one, because the retained parent will not re-read servers.dat. See the field.
			this.client.setScreen(new MultiplayerScreen(new TitleScreen()));
			return;
		}
		this.client.setScreen(parent);
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
	public boolean shouldPause() {
		return false;
	}
}
