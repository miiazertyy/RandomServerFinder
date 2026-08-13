package com.serverscanner.screen;

import com.serverscanner.config.Addresses;
import com.serverscanner.config.JoinedServers;
import com.serverscanner.config.ScannerConfig;
import com.serverscanner.filter.ScannerFilters;
import com.serverscanner.local.LocalIpList;
import com.serverscanner.local.LocalServerFeed;

import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Every server joined through the finder.
 *
 * <p>Worth having because random results are effectively unrepeatable: once a server scrolls past
 * you will almost certainly never be offered it again, so without this a good find is lost.
 *
 * <p>Drawn with the finder's own list rather than a second one of its own. History used to store
 * only an address, a name and a date, so it could show only those, and it ignored the card layout
 * because it had no idea one existed. Feeding the same widget from the same kind of feed means the
 * icons, names, versions and player counts all arrive the usual way, from a live ping, and the
 * layout setting applies here for free.
 */
public class JoinHistoryScreen extends Screen {
	private static final int HEADER_HEIGHT = 36;
	private static final int FOOTER_HEIGHT = 34;

	private final Screen parent;
	private final ScannerConfig config = ScannerConfig.get();

	/** Keyed by address, so a row can be traced back to when it was first joined. */
	private final java.util.Map<String, JoinedServers.Entry> byAddress = new java.util.HashMap<>();

	private LocalServerFeed feed;
	private ScannerListWidget list;

	private Button joinButton;
	private Button saveButton;
	private Button forgetButton;

	public JoinHistoryScreen(Screen parent) {
		super(Component.translatable("randomserverfinder.join_history"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		if (feed == null) {
			buildFeed();
		}
		layoutList();

		int centre = this.width / 2;
		int y = this.height - 27;

		joinButton = addRenderableWidget(Button.builder(Component.translatable("randomserverfinder.join"), b -> joinSelected())
				.bounds(centre - 154, y, 74, 20).build());
		saveButton = addRenderableWidget(Button.builder(Component.translatable("randomserverfinder.save"), b -> saveSelected())
				.bounds(centre - 76, y, 74, 20).build());
		forgetButton = addRenderableWidget(Button.builder(Component.translatable("randomserverfinder.forget"), b -> forgetSelected())
				.bounds(centre + 2, y, 74, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("randomserverfinder.back"), b -> this.onClose())
				.bounds(centre + 80, y, 74, 20).build());

		updateButtons();
	}

	/**
	 * Turns the stored addresses into a feed the list can walk.
	 *
	 * <p>Filters are deliberately not applied: this is a record of where you have been, and hiding
	 * rows out of it would only make it look like entries had gone missing.
	 */
	private void buildFeed() {
		List<JoinedServers.Entry> entries = JoinedServers.get().recent();
		List<Long> packed = new ArrayList<>(entries.size());

		for (JoinedServers.Entry entry : entries) {
			String address = entry.address;
			int port = 25565;
			int colon = address.lastIndexOf(':');
			if (colon > 0) {
				try {
					port = Integer.parseInt(address.substring(colon + 1).trim());
				} catch (NumberFormatException e) {
					continue;
				}
				address = address.substring(0, colon);
			}

			long ip = LocalIpList.parseAddress(address.trim());
			if (ip < 0) continue;

			// The Bedrock ports are the only clue left; nothing else was stored about the edition.
			boolean bedrock = port >= 19132 && port <= 19142;
			packed.add(LocalIpList.pack(ip, port, bedrock));
			byAddress.put(entry.address, entry);
		}

		long[] addresses = new long[packed.size()];
		for (int i = 0; i < addresses.length; i++) addresses[i] = packed.get(i);

		feed = LocalServerFeed.over(LocalIpList.of(addresses), new ScannerFilters(), config);
		feed.start();

		list = new ScannerListWidget(feed, config);
		// Everything here has been joined, so the hide-joined filter would empty the screen.
		list.setApplyListFilters(false);
		list.setOnJoin(this::join);
	}

	private void layoutList() {
		list.setBounds(20, HEADER_HEIGHT, this.width - 40,
				Math.max(40, this.height - HEADER_HEIGHT - FOOTER_HEIGHT));
	}

	@Override
	public void tick() {
		feed.tick();
		updateButtons();
	}

	private void updateButtons() {
		boolean has = list != null && list.getSelected() != null;
		if (joinButton != null) joinButton.active = has;
		if (saveButton != null) saveButton.active = has;
		if (forgetButton != null) forgetButton.active = has;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
		super.extractRenderState(context, mouseX, mouseY, deltaTicks);

		com.serverscanner.compat.Draw.centered(context, this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);
		com.serverscanner.compat.Draw.centered(context, this.font, Component.literal(subtitle()),
				this.width / 2, 23, 0xFFA0A0A0);

		layoutList();
		list.render(context, mouseX, mouseY);
	}

	/** Says how many are here, and when the selected one was first joined. */
	private String subtitle() {
		ScannerEntry selected = list == null ? null : list.getSelected();
		JoinedServers.Entry entry = selected == null ? null : byAddress.get(selected.getServer().address());
		if (entry == null) {
			int size = byAddress.size();
			return size + (size == 1 ? " server" : " servers");
		}

		String visits = entry.joinCount + (entry.joinCount == 1 ? " visit" : " visits");
		return "first joined " + absoluteDate(entry.firstJoined) + "  •  " + visits
				+ "  •  last " + relativeTime(entry.lastJoined);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		if (list != null && list.mouseClicked(click, doubled)) {
			updateButtons();
			return true;
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent click) {
		if (list != null && list.mouseReleased(click)) return true;
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
		if (list != null && list.mouseDragged(click, deltaX, deltaY)) return true;
		return super.mouseDragged(click, deltaX, deltaY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (list != null && list.mouseScrolled(mouseX, mouseY, horizontal, vertical)) return true;
		return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}

	private void joinSelected() {
		ScannerEntry selected = list.getSelected();
		if (selected != null) join(selected);
	}

	private void join(ScannerEntry entry) {
		ServerData info = entry.getServerInfo();
		if (entry.getServer().bedrock) {
			com.serverscanner.local.ViaFabricPlusBridge.forceBedrock(info);
		} else {
			com.serverscanner.local.ViaFabricPlusBridge.forceVersion(info, entry.getServer().protocol());
		}
		com.serverscanner.party.PartyManager.announceTravel(entry.getServer().address());
		ConnectScreen.startConnecting(this, this.minecraft, ServerAddress.parseString(info.ip), info, false, null);
	}

	private void saveSelected() {
		ScannerEntry selected = list.getSelected();
		if (selected == null) return;

		String address = selected.getServer().address();
		ServerList servers = new ServerList(this.minecraft);
		servers.load();
		if (servers.get(address) != null) return;

		servers.add(new ServerData(selected.displayName(), address, ServerData.Type.OTHER), false);
		servers.save();
		if (parent instanceof ServerScannerScreen finder) finder.markSavedToServerList();
	}

	private void forgetSelected() {
		ScannerEntry selected = list.getSelected();
		if (selected == null) return;

		JoinedServers.get().forget(selected.getServer().address());
		byAddress.remove(selected.getServer().address());

		// Rebuilt from what is left, so the row goes away rather than lingering until reopened.
		if (list != null) list.close();
		feed.cancel();
		feed = null;
		this.rebuildWidgets();
	}

	/**
	 * The day something was first joined, spelled out rather than as an age.
	 *
	 * <p>"312d ago" is not something anyone can place. A date is, and for a first visit the day is
	 * the interesting part; how long ago it was is not.
	 */
	private static String absoluteDate(long epochSeconds) {
		if (epochSeconds <= 0) return "unknown";
		return java.time.Instant.ofEpochSecond(epochSeconds)
				.atZone(java.time.ZoneId.systemDefault())
				.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"));
	}

	private static String relativeTime(long epochSeconds) {
		long age = System.currentTimeMillis() / 1000L - epochSeconds;
		if (age < 60) return "just now";
		if (age < 3600) return (age / 60) + "m ago";
		if (age < 86400) return (age / 3600) + "h ago";
		return (age / 86400) + "d ago";
	}

	@Override
	public void removed() {
		if (list != null) list.close();
		if (feed != null) feed.cancel();
	}

	@Override
	public void onClose() {
		this.minecraft.setScreenAndShow(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
