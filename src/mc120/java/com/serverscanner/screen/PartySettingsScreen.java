package com.serverscanner.screen;

import com.serverscanner.config.ScannerConfig;
import com.serverscanner.filter.FilterIcons;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * How the party behaves, as decided by whoever is hosting it.
 *
 * <p>Its own screen rather than more rows on the filter screen, because these are not filters and
 * they are not personal preferences either: they are the host telling everyone else's client what
 * to do. Reached from the party screen, which is where you are when any of it is on your mind.
 */
public class PartySettingsScreen extends Screen {
	private static final int PANEL_WIDTH = 320;
	private static final int ROW_HEIGHT = 26;
	private static final int CONTROL_WIDTH = 90;

	private static final int PANEL_BG = 0xB00E1014;
	private static final int PANEL_BORDER = 0xFF2E3440;
	private static final int LABEL = 0xFFDCDCDC;
	private static final int SUBTITLE = 0xFF8A8F98;

	private final Screen parent;
	private final ScannerConfig config = ScannerConfig.get();

	private final List<Row> rows = new ArrayList<>();

	/** A pictogram and label drawn beside the control that changes the setting. */
	private record Row(String[] icon, String label, int y) {
	}

	public PartySettingsScreen(Screen parent) {
		super(Text.translatable("randomserverfinder.party_settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		rows.clear();

		int centre = this.width / 2;
		int left = centre - PANEL_WIDTH / 2;
		int controlX = left + PANEL_WIDTH - 12 - CONTROL_WIDTH;
		int y = 62;

		y = toggle(controlX, y, FilterIcons.PARTY, "randomserverfinder.follow_me_onto_servers",
				"Take everyone along to each server you join.\n"
						+ "Turn this off to move around without dragging anyone with you.",
				() -> config.partyFollowJoin, v -> config.partyFollowJoin = v);

		y = toggle(controlX, y, FilterIcons.PARTY_LEAVE, "randomserverfinder.follow_me_out",
				"Disconnect everyone when you leave a server, instead of\n"
						+ "leaving them behind somewhere they only came for the company.\n"
						+ "Moving to another server does not count as leaving.",
				() -> config.partyFollowLeave, v -> config.partyFollowLeave = v);

		y = toggle(controlX, y, FilterIcons.PARTY_LOCK, "randomserverfinder.lock_the_party",
				"Turn away anyone else who tries to connect, without\n"
						+ "having to stop hosting or change the port.\n"
						+ "Friends already in the party stay in it.",
				() -> config.partyLocked, v -> config.partyLocked = v);

		TextFieldWidget size = new TextFieldWidget(this.textRenderer, controlX, y + 3,
				CONTROL_WIDTH, 18, Text.translatable("randomserverfinder.party_size"));
		size.setMaxLength(2);
		size.setText(Integer.toString(config.partyMaxMembers));
		size.setTooltip(Tooltip.of(Text.translatable("randomserverfinder.tip.how_many_friends_may_be")));
		size.setChangedListener(value -> {
			try {
				// Clamped on the way in, so a stray keystroke cannot lock everyone out.
				config.partyMaxMembers = Math.max(1, Math.min(16, Integer.parseInt(value.trim())));
			} catch (NumberFormatException e) {
				// Half-typed; the previous value stands until it parses.
			}
		});
		addDrawableChild(size);
		rows.add(new Row(FilterIcons.PLAYER_CAP, "Party size", y));
		y += ROW_HEIGHT;

		addDrawableChild(ButtonWidget.builder(Text.translatable("randomserverfinder.done"), b -> this.close())
				.dimensions(centre - 100, this.height - 28, 200, 20).build());
	}

	/** Adds one on/off row and returns the y for the next one. */
	private int toggle(int controlX, int y, String[] icon, String labelKey, String tooltipKey,
			Supplier<Boolean> get, java.util.function.Consumer<Boolean> set) {
		String label = net.minecraft.client.resource.language.I18n.translate(labelKey);
		String tooltip = net.minecraft.client.resource.language.I18n.translate(tooltipKey);
		ButtonWidget[] holder = new ButtonWidget[1];
		holder[0] = ButtonWidget.builder(onOff(get.get()), b -> {
					boolean next = !get.get();
					set.accept(next);
					holder[0].setMessage(onOff(next));
				})
				.tooltip(Tooltip.of(Text.literal(tooltip)))
				.dimensions(controlX, y, CONTROL_WIDTH, 20)
				.build();
		addDrawableChild(holder[0]);
		rows.add(new Row(icon, label, y));
		return y + ROW_HEIGHT;
	}

	private static Text onOff(boolean value) {
		return Text.literal(value ? "On" : "Off");
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.renderBackground(context, mouseX, mouseY, deltaTicks);

		// Here rather than in render: on these versions render draws the background itself before
		// the widgets, and a panel drawn after them dimmed every control inside it until it looked
		// disabled.
		int left = this.width / 2 - PANEL_WIDTH / 2;
		int panelBottom = this.height - 40;
		context.fill(left, 44, left + PANEL_WIDTH, panelBottom, PANEL_BG);
		context.fill(left, 44, left + PANEL_WIDTH, 45, PANEL_BORDER);
		context.fill(left, panelBottom - 1, left + PANEL_WIDTH, panelBottom, PANEL_BORDER);
		context.fill(left, 44, left + 1, panelBottom, PANEL_BORDER);
		context.fill(left + PANEL_WIDTH - 1, 44, left + PANEL_WIDTH, panelBottom, PANEL_BORDER);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		int centre = this.width / 2;
		int left = centre - PANEL_WIDTH / 2;

		super.render(context, mouseX, mouseY, deltaTicks);

		context.drawCenteredTextWithShadow(this.textRenderer, this.title, centre, 14, 0xFFFFFFFF);
		context.drawCenteredTextWithShadow(this.textRenderer,
				Text.translatable("randomserverfinder.these_apply_to_everyone_only"),
				centre, 28, SUBTITLE);

		for (Row row : rows) {
			int iconY = row.y() + (20 - FilterIcons.SIZE) / 2;
			Icons.draw(context, row.icon(), left + 12, iconY);
			context.drawTextWithShadow(this.textRenderer, Text.literal(row.label()),
					left + 12 + FilterIcons.SIZE + 6, row.y() + 6, LABEL);
		}
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
