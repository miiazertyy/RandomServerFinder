package com.serverscanner.screen;

import com.serverscanner.config.ScannerConfig;
import com.serverscanner.party.PartyManager;
import com.serverscanner.party.PartyTravel;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.List;

/**
 * Set up or join a party, so a group can hop between random servers together.
 *
 * <p>One person hosts and the others connect to their address. Whenever the host joins a server,
 * everyone follows.
 */
public class PartyScreen extends Screen {
	private static final int PANEL_WIDTH = 320;

	/** Gap left between the last line of the footer and the panel's bottom edge. */
	private static final int FOOT_MARGIN = 14;

	/**
	 * Where each footer line sits, measured from the top of the block.
	 *
	 * <p>Even steps, with a slightly wider one before the explanation so the address stays visually
	 * attached to the label above it. The old positions stepped 12, then 14, then 10.
	 */
	private static final int[] FOOT_LINES = { 0, 12, 26, 38 };

	private final Screen parent;
	private final ScannerConfig config = ScannerConfig.get();

	private EditBox addressField;
	private EditBox portField;

	public PartyScreen(Screen parent) {
		super(Component.translatable("randomserverfinder.party"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int centre = this.width / 2;
		int left = centre - PANEL_WIDTH / 2;

		addressField = new EditBox(this.font, left + 12, 96, PANEL_WIDTH - 24, 20,
				Component.translatable("randomserverfinder.host_address"));
		addressField.setMaxLength(128);
		addressField.setValue(config.lastPartyAddress);
		addressField.setHint(Component.translatable("randomserverfinder.friend_s_ip_e_g_203_0_113_4")
				.withStyle(ChatFormatting.DARK_GRAY));
		addressField.setResponder(v -> config.lastPartyAddress = v);
		addRenderableWidget(addressField);

		portField = new EditBox(this.font, left + 12, 96, 60, 20, Component.translatable("randomserverfinder.port"));
		portField.setMaxLength(5);
		portField.setValue(Integer.toString(config.partyPort));
		portField.setResponder(v -> {
			try {
				config.partyPort = Math.max(1, Math.min(65535, Integer.parseInt(v.trim())));
			} catch (NumberFormatException ignored) {
				// Left as-is until it parses; the button re-reads it on press.
			}
		});
		addRenderableWidget(portField);

		boolean active = PartyManager.isActive();
		addressField.visible = !active;
		portField.visible = !active;

		if (active) {
			if (PartyManager.getMode() == PartyManager.Mode.HOSTING) {
				String address = com.serverscanner.party.LocalAddress.withPort(config.partyPort);
				Button copy = addRenderableWidget(Button.builder(Component.translatable("randomserverfinder.copy_address"), b -> {
							String value = com.serverscanner.party.LocalAddress.withPort(config.partyPort);
							if (value != null) this.minecraft.keyboardHandler.setClipboard(value);
						})
						.tooltip(Tooltip.create(Component.translatable("randomserverfinder.copies_your_address_so_you")))
						.bounds(centre - 100, this.height - 76, 98, 20).build());
				copy.active = address != null;

				addRenderableWidget(Button.builder(Component.translatable("randomserverfinder.settings"),
								b -> this.minecraft.setScreenAndShow(new PartySettingsScreen(this)))
						.tooltip(Tooltip.create(Component.translatable("randomserverfinder.how_the_party_behaves")))
						.bounds(centre + 2, this.height - 76, 98, 20).build());
			}

			// Somewhere to go back to. A member who leaves the server lands in the menus with no way
			// back, because the host only announces a trip when they move, not while they sit still.
			String at = PartyManager.getHostServer();
			Button rejoin = addRenderableWidget(Button.builder(Component.translatable("randomserverfinder.join_the_party"),
							b -> {
								String where = PartyManager.getHostServer();
								if (where != null) PartyTravel.connect(this.minecraft, where);
							})
					.tooltip(Tooltip.create(Component.translatable("randomserverfinder.tip.join_the_server_the_party")))
					.bounds(centre - 100, this.height - 52, 98, 20).build());
			rejoin.active = at != null && this.minecraft.level == null;

			addRenderableWidget(Button.builder(Component.translatable("randomserverfinder.leave_party"), b -> {
						PartyManager.leave();
						this.rebuildWidgets();
					})
					.bounds(centre + 2, this.height - 52, 98, 20).build());
		} else {
			addRenderableWidget(Button.builder(Component.translatable("randomserverfinder.host_a_party"), b -> {
						PartyManager.startHosting(config.partyPort);
						config.save();
						this.rebuildWidgets();
					})
					.tooltip(Tooltip.create(Component.translatable("randomserverfinder.tip.opens_a_port_on_this_machine")))
					.bounds(centre - 100, this.height - 76, 200, 20).build());

			addRenderableWidget(Button.builder(Component.translatable("randomserverfinder.join_a_friend"), b -> {
						PartyManager.joinParty(addressField.getValue());
						config.save();
						this.rebuildWidgets();
					})
					.tooltip(Tooltip.create(Component.translatable("randomserverfinder.tip.connecting_by_address_rather")))
					.bounds(centre - 100, this.height - 52, 200, 20).build());

			// Reachable before hosting too, so the party can be set up the way you want it first.
			addRenderableWidget(Button.builder(Component.translatable("randomserverfinder.settings"),
							b -> this.minecraft.setScreenAndShow(new PartySettingsScreen(this)))
					.tooltip(Tooltip.create(Component.translatable("randomserverfinder.tip.how_the_party_behaves_once")))
					.bounds(centre - 100, this.height - 28, 98, 20).build());

			addRenderableWidget(Button.builder(Component.translatable("randomserverfinder.back"), b -> this.onClose())
					.bounds(centre + 2, this.height - 28, 98, 20).build());
			return;
		}

		addRenderableWidget(Button.builder(Component.translatable("randomserverfinder.back"), b -> this.onClose())
				.bounds(centre - 100, this.height - 28, 200, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
		super.extractRenderState(context, mouseX, mouseY, deltaTicks);

		int centre = this.width / 2;
		int left = centre - PANEL_WIDTH / 2;

		com.serverscanner.compat.Draw.centered(context, this.font, this.title, centre, 14, 0xFFFFFFFF);

		boolean active = PartyManager.isActive();
		String subtitle = active
				? PartyManager.getStatus()
				: "Everyone follows the host between servers";
		com.serverscanner.compat.Draw.centered(context, this.font, Component.literal(subtitle), centre, 28,
				active ? 0xFF7FD1A0 : 0xFF8A8F98);

		int panelBottom = this.height - 92;
		context.fill(left, 44, left + PANEL_WIDTH, panelBottom, 0xB00E1014);
		context.fill(left, 44, left + PANEL_WIDTH, 45, 0xFF2E3440);
		context.fill(left, panelBottom - 1, left + PANEL_WIDTH, panelBottom, 0xFF2E3440);
		context.fill(left, 44, left + 1, panelBottom, 0xFF2E3440);
		context.fill(left + PANEL_WIDTH - 1, 44, left + PANEL_WIDTH, panelBottom, 0xFF2E3440);

		if (active) {
			renderMembers(context, left, panelBottom);
		} else {
			renderSetup(context, left, centre);
		}

		String error = PartyManager.getError();
		if (error != null) {
			com.serverscanner.compat.Draw.centered(context, this.font,
					Component.literal(error).withStyle(ChatFormatting.RED), centre, this.height - 96, 0xFFFF5555);
		}
	}

	private void renderMembers(GuiGraphicsExtractor context, int left, int panelBottom) {
		context.text(this.font, Component.translatable("randomserverfinder.in_the_party"), left + 12, 56, 0xFF7FD1A0);

		boolean hosting = PartyManager.getMode() == PartyManager.Mode.HOSTING;

		// Measured up from the panel's own bottom edge rather than down from the screen's. Both used
		// to be worked out from the window height with offsets that had no relation to each other,
		// so the last line sat six pixels below the border it was meant to be inside.
		int lastLine = hosting ? FOOT_LINES[FOOT_LINES.length - 1] : 0;
		int footTop = panelBottom - FOOT_MARGIN - lastLine;

		renderMemberList(context, left, footTop);

		if (hosting) {
			String address = com.serverscanner.party.LocalAddress.withPort(config.partyPort);
			if (address == null) {
				address = "could not detect, check your network";
			} else if (com.serverscanner.config.Addresses.hidden()) {
				// Streamer mode exists precisely so an address is not on screen.
				address = "hidden by streamer mode";
			}

			context.text(this.font, Component.translatable("randomserverfinder.friends_connect_to"),
					left + 12, footTop + FOOT_LINES[0], 0xFF8A8F98);
			context.text(this.font, Component.literal(address),
					left + 12, footTop + FOOT_LINES[1], 0xFFFFFFFF);
			context.text(this.font,
					Component.translatable("randomserverfinder.that_is_your_address_on_this"),
					left + 12, footTop + FOOT_LINES[2], 0xFF8A8F98);
			context.text(this.font,
					Component.translatable("randomserverfinder.need_public_ip", config.partyPort),
					left + 12, footTop + FOOT_LINES[3], 0xFF8A8F98);
		} else {
			context.text(this.font,
					Component.translatable("randomserverfinder.you_will_follow_the_host"),
					left + 12, footTop, 0xFF8A8F98);
		}
	}

	private void renderMemberList(GuiGraphicsExtractor context, int left, int footTop) {
		List<String> members = PartyManager.getMembers();
		if (members.isEmpty()) {
			context.text(this.font,
					Component.translatable("randomserverfinder.waiting_for_friends_to"), left + 12, 74, 0xFF8A8F98);
			return;
		}

		// Stops before the footer instead of writing over it: the roster grew without a limit, so a
		// large enough party ran straight through the address and out of the panel the same way.
		int limit = footTop - 12;
		int y = 74;
		for (int i = 0; i < members.size(); i++) {
			if (y + 8 > limit) {
				context.text(this.font,
						Component.translatable("randomserverfinder.and_n_more", members.size() - i), left + 12, y, 0xFF8A8F98);
				return;
			}
			context.text(this.font, Component.literal("• " + members.get(i)),
					left + 12, y, 0xFFDCDCDC);
			y += 12;
		}
	}

	private void renderSetup(GuiGraphicsExtractor context, int left, int centre) {
		context.text(this.font, Component.translatable("randomserverfinder.host"), left + 12, 54, 0xFF7FD1A0);
		context.text(this.font, Component.translatable("randomserverfinder.listen_on_port"), left + 12, 72, 0xFFDCDCDC);
		portField.setX(left + 12 + this.font.width("Listen on port") + 8);
		portField.setY(66);
		portField.setWidth(56);

		context.text(this.font, Component.translatable("randomserverfinder.join_2"), left + 12, 96, 0xFF7FD1A0);
		addressField.setX(left + 12);
		addressField.setY(110);
		addressField.setWidth(PANEL_WIDTH - 24);

		// One line only — the panel bottom is close and the full explanation lives in the tooltip.
		context.text(this.font,
				Component.translatable("randomserverfinder.friends_connect_to_your"),
				left + 12, 136, 0xFF8A8F98);
	}

	@Override
	public void onClose() {
		config.save();
		this.minecraft.setScreenAndShow(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
