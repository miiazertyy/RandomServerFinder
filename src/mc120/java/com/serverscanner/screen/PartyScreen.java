package com.serverscanner.screen;

import com.serverscanner.config.ScannerConfig;
import com.serverscanner.party.PartyManager;
import com.serverscanner.party.PartyTravel;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

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
	 * Where each footer line sits, measured from the top of the block: the address beside its label,
	 * then the two-line explanation, which is one sentence and so sits closer together.
	 *
	 * <p>The address used to have a line of its own under the label. At the usual 240-pixel-tall GUI
	 * that left room for a single roster row, so the host could never see anyone to hand the crown to.
	 */
	private static final int[] FOOT_LINES = { 0, 14, 24 };

	/** Where the first member row sits, and the step between rows: room for a Give host button. */
	private static final int MEMBERS_TOP = 72;
	private static final int ROW = 14;
	private static final int GIVE_HEIGHT = 12;

	private static final int GIVE_WIDTH = 64;

	private final Screen parent;
	private final ScannerConfig config = ScannerConfig.get();

	private TextFieldWidget addressField;
	private TextFieldWidget portField;

	/** The roster the buttons were built for; a newer one means rebuilding them. */
	private int builtFor;

	public PartyScreen(Screen parent) {
		super(Text.translatable("randomserverfinder.party"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		builtFor = PartyManager.getRosterVersion();
		int centre = this.width / 2;
		int left = centre - PANEL_WIDTH / 2;

		addressField = new TextFieldWidget(this.textRenderer, left + 12, 96, PANEL_WIDTH - 24, 20,
				Text.translatable("randomserverfinder.host_address"));
		addressField.setMaxLength(128);
		addressField.setText(config.lastPartyAddress);
		addressField.setPlaceholder(Text.translatable("randomserverfinder.friend_s_ip_e_g_203_0_113_4")
				.formatted(Formatting.DARK_GRAY));
		addressField.setChangedListener(v -> config.lastPartyAddress = v);
		addDrawableChild(addressField);

		portField = new TextFieldWidget(this.textRenderer, left + 12, 96, 60, 20, Text.translatable("randomserverfinder.port"));
		portField.setMaxLength(5);
		portField.setText(Integer.toString(config.partyPort));
		portField.setChangedListener(v -> {
			try {
				config.partyPort = Math.max(1, Math.min(65535, Integer.parseInt(v.trim())));
			} catch (NumberFormatException ignored) {
				// Left as-is until it parses; the button re-reads it on press.
			}
		});
		addDrawableChild(portField);

		boolean active = PartyManager.isActive();
		addressField.visible = !active;
		portField.visible = !active;

		if (active) {
			if (PartyManager.isLeader()) addGiveButtons(left);

			boolean hosting = PartyManager.getMode() == PartyManager.Mode.HOSTING;

			// Two rows of buttons rather than three, so the panel above can hold more of the roster.
			// The host's three go side by side on the first; a member only has Join and Leave there.
			int rowX = hosting ? centre - 149 : centre - 100;

			if (hosting) {
				// Shares its row with Settings: both are host-only, and a member sees neither.
				String address = com.serverscanner.party.LocalAddress.withPort(config.partyPort);
				ButtonWidget copy = addDrawableChild(ButtonWidget.builder(Text.translatable("randomserverfinder.copy_address"), b -> {
							String value = com.serverscanner.party.LocalAddress.withPort(config.partyPort);
							if (value != null) this.client.keyboard.setClipboard(value);
						})
						.tooltip(Tooltip.of(Text.translatable("randomserverfinder.copies_your_address_so_you")))
						.dimensions(rowX, this.height - 52, 98, 20).build());
				copy.active = address != null;

				addDrawableChild(ButtonWidget.builder(Text.translatable("randomserverfinder.settings"),
								b -> this.client.setScreen(new PartySettingsScreen(this)))
						.tooltip(Tooltip.of(Text.translatable("randomserverfinder.how_the_party_behaves")))
						.dimensions(rowX + 100, this.height - 52, 98, 20).build());
				rowX += 200;
			}

			// Somewhere to go back to. A member who leaves the server lands in the menus with no way
			// back, because the host only announces a trip when they move, not while they sit still.
			String at = PartyManager.getHostServer();
			ButtonWidget rejoin = addDrawableChild(ButtonWidget.builder(Text.translatable("randomserverfinder.join_the_party"),
							b -> {
								String where = PartyManager.getHostServer();
								if (where != null) PartyTravel.connect(this.client, where);
							})
					.tooltip(Tooltip.of(Text.translatable("randomserverfinder.tip.join_the_server_the_party")))
					.dimensions(rowX, this.height - 52, 98, 20).build());
			rejoin.active = at != null && this.client.world == null;

			// The host's first row is full, so Leave drops down beside Back.
			addDrawableChild(ButtonWidget.builder(Text.translatable("randomserverfinder.leave_party"), b -> {
						PartyManager.leave();
						this.clearAndInit();
					})
					.dimensions(hosting ? centre - 100 : centre + 2, this.height - (hosting ? 28 : 52), 98, 20).build());

			addDrawableChild(ButtonWidget.builder(Text.translatable("randomserverfinder.back"), b -> this.close())
					.dimensions(hosting ? centre + 2 : centre - 100, this.height - 28, hosting ? 98 : 200, 20).build());
		} else {
			addDrawableChild(ButtonWidget.builder(Text.translatable("randomserverfinder.host_a_party"), b -> {
						PartyManager.startHosting(config.partyPort);
						config.save();
						this.clearAndInit();
					})
					.tooltip(Tooltip.of(Text.translatable("randomserverfinder.tip.opens_a_port_on_this_machine")))
					.dimensions(centre - 100, this.height - 76, 200, 20).build());

			addDrawableChild(ButtonWidget.builder(Text.translatable("randomserverfinder.join_a_friend"), b -> {
						PartyManager.joinParty(addressField.getText());
						config.save();
						this.clearAndInit();
					})
					.tooltip(Tooltip.of(Text.translatable("randomserverfinder.tip.connecting_by_address_rather")))
					.dimensions(centre - 100, this.height - 52, 200, 20).build());

			// Reachable before hosting too, so the party can be set up the way you want it first.
			addDrawableChild(ButtonWidget.builder(Text.translatable("randomserverfinder.settings"),
							b -> this.client.setScreen(new PartySettingsScreen(this)))
					.tooltip(Tooltip.of(Text.translatable("randomserverfinder.tip.how_the_party_behaves_once")))
					.dimensions(centre - 100, this.height - 28, 98, 20).build());

			addDrawableChild(ButtonWidget.builder(Text.translatable("randomserverfinder.back"), b -> this.close())
					.dimensions(centre + 2, this.height - 28, 98, 20).build());
		}
	}

	/** One Give host button beside everyone else on the roster, for whoever holds the crown. */
	private void addGiveButtons(int left) {
		List<String> members = PartyManager.getMembers();
		int shown = shownRows(members.size());
		for (int i = 0; i < shown; i++) {
			if (i == PartyManager.getSelfIndex()) continue;
			int index = i;
			addDrawableChild(ButtonWidget.builder(Text.translatable("randomserverfinder.give_host"),
							b -> PartyManager.giveLead(index))
					.tooltip(Tooltip.of(Text.translatable("randomserverfinder.tip.give_host", members.get(i))))
					.dimensions(left + PANEL_WIDTH - 12 - GIVE_WIDTH, rowY(i) - 2, GIVE_WIDTH, GIVE_HEIGHT).build());
		}
	}

	@Override
	public void tick() {
		// Someone joined, left or was crowned: the buttons belong to the old roster.
		if (builtFor != PartyManager.getRosterVersion()) this.clearAndInit();
	}

	private static int rowY(int i) {
		return MEMBERS_TOP + i * ROW;
	}

	/** A party only needs two rows of buttons below the panel; setting one up needs three. */
	private int panelBottom() {
		return this.height - (PartyManager.isActive() ? 68 : 92);
	}

	/** Top of the footer text under the roster, measured up from the panel's own bottom edge. */
	private int footTop() {
		int panelBottom = panelBottom();
		boolean hosting = PartyManager.getMode() == PartyManager.Mode.HOSTING;
		int lastLine = hosting ? FOOT_LINES[FOOT_LINES.length - 1] : 0;
		return panelBottom - FOOT_MARGIN - lastLine;
	}

	/**
	 * How many roster rows fit above the footer. When not all of them do, the last row that would
	 * fit is kept for "and N more", which used to be written over the footer below it instead.
	 */
	private int shownRows(int count) {
		int limit = footTop() - 6;
		int fit = 0;
		while (rowY(fit) + 8 <= limit) fit++;
		return count <= fit ? count : Math.max(0, fit - 1);
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.renderBackground(context, mouseX, mouseY, deltaTicks);

		// Here rather than in render: on these versions render draws the background itself before
		// the widgets, and a panel drawn after them dimmed every control inside it until it looked
		// disabled.
		int left = this.width / 2 - PANEL_WIDTH / 2;
		int panelBottom = panelBottom();
		context.fill(left, 44, left + PANEL_WIDTH, panelBottom, 0xB00E1014);
		context.fill(left, 44, left + PANEL_WIDTH, 45, 0xFF2E3440);
		context.fill(left, panelBottom - 1, left + PANEL_WIDTH, panelBottom, 0xFF2E3440);
		context.fill(left, 44, left + 1, panelBottom, 0xFF2E3440);
		context.fill(left + PANEL_WIDTH - 1, 44, left + PANEL_WIDTH, panelBottom, 0xFF2E3440);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		int centre = this.width / 2;
		int left = centre - PANEL_WIDTH / 2;

		int panelBottom = panelBottom();

		super.render(context, mouseX, mouseY, deltaTicks);

		context.drawCenteredTextWithShadow(this.textRenderer, this.title, centre, 14, 0xFFFFFFFF);

		boolean active = PartyManager.isActive();
		String subtitle = active
				? PartyManager.getStatus()
				: "Everyone follows the host between servers";
		context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(subtitle), centre, 28,
				active ? 0xFF7FD1A0 : 0xFF8A8F98);

		if (active) {
			renderMembers(context, left, panelBottom);
		} else {
			renderSetup(context, left, centre);
		}

		String error = PartyManager.getError();
		if (error != null) {
			context.drawCenteredTextWithShadow(this.textRenderer,
					Text.literal(error).formatted(Formatting.RED), centre, panelBottom + 4, 0xFFFF5555);
		}
	}

	private void renderMembers(DrawContext context, int left, int panelBottom) {
		context.drawTextWithShadow(this.textRenderer, Text.translatable("randomserverfinder.in_the_party"), left + 12, 54, 0xFF7FD1A0);

		boolean hosting = PartyManager.getMode() == PartyManager.Mode.HOSTING;

		// Measured up from the panel's own bottom edge rather than down from the screen's. Both used
		// to be worked out from the window height with offsets that had no relation to each other,
		// so the last line sat six pixels below the border it was meant to be inside.
		int footTop = footTop();

		renderMemberList(context, left);

		if (hosting) {
			String address = com.serverscanner.party.LocalAddress.withPort(config.partyPort);
			if (address == null) {
				address = "could not detect, check your network";
			} else if (com.serverscanner.config.Addresses.hidden()) {
				// Streamer mode exists precisely so an address is not on screen.
				address = "hidden by streamer mode";
			}

			Text label = Text.translatable("randomserverfinder.friends_connect_to");
			context.drawTextWithShadow(this.textRenderer, label,
					left + 12, footTop + FOOT_LINES[0], 0xFF8A8F98);
			context.drawTextWithShadow(this.textRenderer, Text.literal(address),
					left + 12 + this.textRenderer.getWidth(label) + 4, footTop + FOOT_LINES[0], 0xFFFFFFFF);
			context.drawTextWithShadow(this.textRenderer,
					Text.translatable("randomserverfinder.that_is_your_address_on_this"),
					left + 12, footTop + FOOT_LINES[1], 0xFF8A8F98);
			context.drawTextWithShadow(this.textRenderer,
					Text.translatable("randomserverfinder.need_public_ip", config.partyPort),
					left + 12, footTop + FOOT_LINES[2], 0xFF8A8F98);
		} else {
			context.drawTextWithShadow(this.textRenderer,
					Text.translatable(PartyManager.isLeader()
							? "randomserverfinder.you_lead_the_party"
							: "randomserverfinder.you_will_follow_the_host"),
					left + 12, footTop, 0xFF8A8F98);
		}
	}

	private void renderMemberList(DrawContext context, int left) {
		List<String> members = PartyManager.getMembers();
		if (members.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer,
					Text.translatable("randomserverfinder.waiting_for_friends_to"), left + 12, MEMBERS_TOP, 0xFF8A8F98);
			return;
		}

		// Stops before the footer instead of writing over it: the roster grew without a limit, so a
		// large enough party ran straight through the address and out of the panel the same way.
		int shown = shownRows(members.size());
		int leader = PartyManager.getLeaderIndex();
		int nameX = left + 12 + com.serverscanner.party.CrownIcon.WIDTH + 4;
		for (int i = 0; i < shown; i++) {
			int y = rowY(i);
			if (i == leader) {
				Icons.drawCrown(context, left + 12, y);
			} else {
				context.drawTextWithShadow(this.textRenderer, Text.literal("•"), left + 14, y, 0xFF8A8F98);
			}
			int colour = i == leader ? 0xFFF2C037 : 0xFFDCDCDC;
			context.drawTextWithShadow(this.textRenderer, Text.literal(members.get(i)), nameX, y, colour);
		}
		if (shown < members.size()) {
			context.drawTextWithShadow(this.textRenderer,
					Text.translatable("randomserverfinder.and_n_more", members.size() - shown),
					left + 12, rowY(shown), 0xFF8A8F98);
		}
	}

	private void renderSetup(DrawContext context, int left, int centre) {
		context.drawTextWithShadow(this.textRenderer, Text.translatable("randomserverfinder.host"), left + 12, 54, 0xFF7FD1A0);
		context.drawTextWithShadow(this.textRenderer, Text.translatable("randomserverfinder.listen_on_port"), left + 12, 72, 0xFFDCDCDC);
		portField.setX(left + 12 + this.textRenderer.getWidth("Listen on port") + 8);
		portField.setY(66);
		portField.setWidth(56);

		context.drawTextWithShadow(this.textRenderer, Text.translatable("randomserverfinder.join_2"), left + 12, 96, 0xFF7FD1A0);
		addressField.setX(left + 12);
		addressField.setY(110);
		addressField.setWidth(PANEL_WIDTH - 24);

		// One line only — the panel bottom is close and the full explanation lives in the tooltip.
		context.drawTextWithShadow(this.textRenderer,
				Text.translatable("randomserverfinder.friends_connect_to_your"),
				left + 12, 136, 0xFF8A8F98);
	}

	@Override
	public void close() {
		config.save();
		this.client.setScreen(parent);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
