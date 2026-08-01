package com.github.sebseb7.autotrade.gui;

import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.config.TradePairList;
import com.github.sebseb7.autotrade.gui.widget.ItemIconWidget;
import com.github.sebseb7.autotrade.trade.TradePair;
import com.github.sebseb7.autotrade.util.ItemStringHelper;
import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.options.ConfigString;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.interfaces.IKeybindConfigGui;
import fi.dy.masa.malilib.gui.widgets.WidgetConfigOption;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptions;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptionsBase;
import fi.dy.masa.malilib.util.StringUtils;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;

public class PairListScreen extends GuiConfigsBase {
	private List<TradePair> pairs = ImmutableList.of();

	public PairListScreen() {
		super(10, 50, "autotrade", null, "autotrade.gui.title.pair_list");
	}

	@Override
	protected int getBrowserWidth() {
		return this.width * 8 / 10;
	}

	@Override
	protected int getBrowserHeight() {
		return this.height - 52;
	}

	@Override
	protected int getConfigWidth() {
		return Math.max(200, getBrowserWidth() * 4 / 10);
	}

	@Override
	public void initGui() {
		this.setListPosition(this.width / 10, 26);
		this.reCreateListWidget();
		pairs = TradePairList.fromJson(Configs.Generic.TRADE_PAIRS.getStringValue());
		super.initGui();

		// [+ Add Pair] and [Back] at bottom
		ButtonGeneric addBtn = new ButtonGeneric(this.width / 10, this.height - 24, 90, 20,
				StringUtils.translate("autotrade.gui.pair_list.add"));
		this.addButton(addBtn, (b, mb) -> {
			String json = Configs.Generic.TRADE_PAIRS.getStringValue();
			Configs.Generic.TRADE_PAIRS
					.setValueFromString(TradePairList.addPair(json, "minecraft:air", "minecraft:air", 1));
			Configs.saveToFile();
			this.initGui();
		});

		ButtonGeneric backBtn = new ButtonGeneric(this.width / 10 + 94, this.height - 24, 60, 20,
				StringUtils.translate("autotrade.gui.pair_list.back"));
		this.addButton(backBtn, (b, mb) -> GuiBase.openGui(new GuiConfigs()));
	}

	@Override
	public List<ConfigOptionWrapper> getConfigs() {
		if (pairs.isEmpty()) {
			return ImmutableList.of(new ConfigOptionWrapper(StringUtils.translate("autotrade.gui.pair_list.empty")));
		}
		List<ConfigOptionWrapper> configs = new ArrayList<>();
		for (int i = 0; i < pairs.size(); i++) {
			TradePair p = pairs.get(i);
			String giveName = ItemStringHelper.getItemId(p.getGiveItem());
			String getName = ItemStringHelper.getItemId(p.getGetItem());
			String statusKey = p.isEnabled()
					? "autotrade.gui.pair_list.status_on"
					: "autotrade.gui.pair_list.status_off";
			String label = StringUtils.translate("autotrade.gui.pair_list.trade_number", i + 1) + ". "
					+ StringUtils.translate(statusKey) + " " + giveName + " "
					+ StringUtils.translate("autotrade.gui.pair_list.arrow") + " " + getName + " "
					+ StringUtils.translate("autotrade.gui.pair_list.limit_prefix", p.getLimit());
			configs.add(new ConfigOptionWrapper(new ConfigString("pair_" + i, label, "")));
		}
		return configs;
	}

	@Override
	protected WidgetListConfigOptions createListWidget(int listX, int listY) {
		return new CustomWidgetListConfigOptions(listX, listY, this.getBrowserWidth(), this.getBrowserHeight(),
				this.getConfigWidth(), 0.f, this.useKeybindSearch(), this);
	}

	// ---- Custom widget classes ----

	private static class CustomWidgetListConfigOptions extends WidgetListConfigOptions {
		private static final int ENTRY_HEIGHT = 40;

		public CustomWidgetListConfigOptions(int x, int y, int width, int height, int configWidth, float zLevel,
				boolean useKeybindSearch, GuiConfigsBase parent) {
			super(x, y, width, height, configWidth, zLevel, useKeybindSearch, parent);
			this.browserEntryHeight = ENTRY_HEIGHT;
		}

		@Override
		protected int getBrowserEntryHeightFor(ConfigOptionWrapper entry) {
			return ENTRY_HEIGHT;
		}

		@Override
		protected WidgetConfigOption createListEntryWidget(int x, int y, int listIndex, boolean isOdd,
				ConfigOptionWrapper wrapper) {
			return new CustomPairListWidget(x, y, this.browserEntryWidth, ENTRY_HEIGHT, this.maxLabelWidth,
					this.configWidth, wrapper, listIndex, (IKeybindConfigGui) this.parent, this);
		}
	}

	private static class CustomPairListWidget extends WidgetConfigOption {
		private final PairListScreen parentScreen;
		private ItemStack giveStack = ItemStack.EMPTY;
		private ItemStack getStack = ItemStack.EMPTY;

		public CustomPairListWidget(int x, int y, int width, int height, int labelWidth, int configWidth,
				ConfigOptionWrapper wrapper, int listIndex, IKeybindConfigGui host,
				WidgetListConfigOptionsBase<?, ?> parent) {
			super(x, y, width, height, labelWidth, configWidth, wrapper, listIndex, host, parent);
			if (host instanceof PairListScreen) {
				this.parentScreen = (PairListScreen) host;
			} else {
				this.parentScreen = null;
			}
		}

		@Override
		protected void addConfigOption(int x, int y, float zLevel, int labelWidth, int configWidth,
				IConfigBase config) {
			String name = config.getName();
			if (name != null && name.startsWith("pair_")) {
				int idx;
				try {
					idx = Integer.parseInt(name.substring(5));
				} catch (NumberFormatException e) {
					super.addConfigOption(x, y, zLevel, labelWidth, configWidth, config);
					return;
				}
				List<TradePair> pairs = TradePairList.fromJson(Configs.Generic.TRADE_PAIRS.getStringValue());
				if (idx < 0 || idx >= pairs.size()) {
					super.addConfigOption(x, y, zLevel, labelWidth, configWidth, config);
					return;
				}
				TradePair p = pairs.get(idx);

				// 解码物品栈
				giveStack = ItemStringHelper.decode(p.getGiveItem());
				getStack = ItemStringHelper.decode(p.getGetItem());
				String noteText = p.getNote() != null ? p.getNote() : "";

				String inputText = p.isInputEnabled()
						? StringUtils.translate("autotrade.gui.pair_list.input_enabled", p.getInputX(), p.getInputY(),
								p.getInputZ())
						: StringUtils.translate("autotrade.gui.pair_list.input_disabled");
				String outputText = p.isOutputEnabled()
						? StringUtils.translate("autotrade.gui.pair_list.output_enabled", p.getOutputX(),
								p.getOutputY(), p.getOutputZ())
						: StringUtils.translate("autotrade.gui.pair_list.output_disabled");
				String ioText = inputText + "  " + outputText;

				int cx = x + 2;

				String indexText = StringUtils.translate("autotrade.gui.pair_list.trade_number", idx + 1);
				this.addLabel(cx, y + 6, this.getStringWidth(indexText), 8, 0xFFFFFFFF, indexText);
				cx += this.getStringWidth(indexText) + 6;

				String statusText = StringUtils.translate(
						p.isEnabled() ? "autotrade.gui.pair_list.status_on" : "autotrade.gui.pair_list.status_off");
				int statusWidth = this.getStringWidth(statusText);
				this.addLabel(cx, y + 6, statusWidth, 8, 0xFFFFFFFF, statusText);
				cx += statusWidth + 6;

				// 物品悬浮提示由 ItemIconWidget 自行处理
				if (!giveStack.isEmpty()) {
					this.addWidget(new ItemIconWidget(cx, y + 1, giveStack));
				}
				cx += 22;

				String limitLabel = StringUtils.translate("autotrade.gui.pair_list.limit_prefix", p.getLimit());
				int limitWidth = this.getStringWidth(limitLabel);
				this.addLabel(cx, y + 6, limitWidth, 8, 0xFFFFFFFF, limitLabel);
				cx += limitWidth + 6;

				String arrow = StringUtils.translate("autotrade.gui.pair_list.arrow");
				int arrowWidth = this.getStringWidth(arrow);
				this.addLabel(cx, y + 6, arrowWidth, 8, 0xFFFFFFFF, arrow);
				cx += arrowWidth + 6;

				if (!getStack.isEmpty()) {
					this.addWidget(new ItemIconWidget(cx, y + 1, getStack));
				}
				cx += 22;

				if (!noteText.isEmpty()) {
					String displayNote = noteText;
					String fullNote = StringUtils.translate("autotrade.gui.pair_list.note_label", displayNote);
					int maxNoteWidth = this.width - cx - 8;
					if (this.getStringWidth(fullNote) > maxNoteWidth) {
						while (this.getStringWidth(StringUtils.translate("autotrade.gui.pair_list.note_label",
								displayNote + "...")) > maxNoteWidth && !displayNote.isEmpty()) {
							displayNote = displayNote.substring(0, displayNote.length() - 1);
						}
						fullNote = StringUtils.translate("autotrade.gui.pair_list.note_label", displayNote + "...");
					}
					int noteX = this.width - this.getStringWidth(fullNote) - 4;
					if (noteX > cx) {
						this.addLabel(noteX, y + 6, this.getStringWidth(fullNote), 8, 0xFFAAAAAA, fullNote);
					}
				}

				int row2Y = y + 20;
				cx = x + 2;

				// 计算按钮宽度：3 个按钮 (6:4:5 比例) + 2 个 4px 间隔，保证不超出 entry 宽度
				int gap = 4;
				int availableW = (this.x + this.width) - cx - gap; // 从 cx 到 entry 右边距的可用宽度
				int toggleW = Math.min(60, Math.max(40, availableW * 60 / 158));
				int editW = Math.min(40, Math.max(28, availableW * 40 / 158));
				int removeW = Math.min(50, Math.max(36, availableW * 50 / 158));

				String enableLabel = StringUtils.translate(
						p.isEnabled() ? "autotrade.gui.pair_list.disable_btn" : "autotrade.gui.pair_list.enable_btn");
				ButtonGeneric toggleBtn = new ButtonGeneric(cx, row2Y, toggleW, 20, enableLabel);
				this.addButton(toggleBtn, (button, mouseButton) -> {
					String json = Configs.Generic.TRADE_PAIRS.getStringValue();
					Configs.Generic.TRADE_PAIRS.setValueFromString(TradePairList.togglePair(json, idx));
					Configs.saveToFile();
					if (parentScreen != null)
						parentScreen.initGui();
				});
				cx += toggleW + gap;

				// Edit 按钮
				ButtonGeneric editBtn = new ButtonGeneric(cx, row2Y, editW, 20,
						StringUtils.translate("autotrade.gui.pair_list.edit"));
				this.addButton(editBtn, (button, mouseButton) -> {
					GuiBase.openGui(new PairEditScreen(idx));
				});
				cx += editW + gap;

				// Remove 按钮
				ButtonGeneric removeBtn = new ButtonGeneric(cx, row2Y, removeW, 20,
						StringUtils.translate("autotrade.gui.pair_list.remove"));
				this.addButton(removeBtn, (button, mouseButton) -> {
					String json = Configs.Generic.TRADE_PAIRS.getStringValue();
					Configs.Generic.TRADE_PAIRS.setValueFromString(TradePairList.removePair(json, idx));
					Configs.saveToFile();
					if (parentScreen != null)
						parentScreen.initGui();
				});
				cx += removeW + gap;

				// 输入/输出状态文本（右对齐）
				int ioWidth = this.getStringWidth(ioText);
				int ioX = this.width - ioWidth - 4;
				if (ioX > cx + 10) {
					this.addLabel(ioX, row2Y + 6, ioWidth, 8, 0xFF888888, ioText);
				}
			} else {
				super.addConfigOption(x, y, zLevel, labelWidth, configWidth, config);
			}
		}

		@Override
		public void render(int mouseX, int mouseY, boolean selected, DrawContext drawContext) {
			super.render(mouseX, mouseY, selected, drawContext);
			// 分隔线
			drawContext.fill(this.x, this.y + this.height - 1, this.x + this.width, this.y + this.height, 0xFF555555);
		}

	}
}
