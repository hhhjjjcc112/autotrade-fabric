package com.github.sebseb7.autotrade.gui;

import com.github.sebseb7.autotrade.Reference;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.config.Hotkeys;
import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.util.StringUtils;
import java.util.Collections;
import java.util.List;

public class GuiConfigs extends GuiConfigsBase {
	private static ConfigGuiTab tab = ConfigGuiTab.GENERIC;

	public GuiConfigs() {
		super(10, 50, Reference.MOD_ID, null, "autotrade.gui.title.configs");
	}

	@Override
	protected int getBrowserWidth() {
		return this.width * 8 / 10;
	}

	@Override
	public void initGui() {
		this.setListPosition(this.width / 10, this.getListY());
		this.reCreateListWidget();
		super.initGui();
		this.clearOptions();

		int x = this.width / 10;
		int y = 26;
		int gap = 2;
		int tabCount = ConfigGuiTab.VALUES.size();
		// 单按钮最大宽度：均分浏览器宽度，保证标签按钮组不超出滚动条区域
		int maxTabWidth = (this.getBrowserWidth() - gap * (tabCount - 1)) / tabCount;

		for (ConfigGuiTab tab : ConfigGuiTab.VALUES) {
			int width = Math.min(this.getStringWidth(tab.getDisplayName()) + 10, maxTabWidth);
			x += this.createButton(x, y, width, tab);
		}

		// Add "Manage Pairs" button on GENERIC tab, below the config list
		if (GuiConfigs.tab == ConfigGuiTab.GENERIC) {
			ButtonGeneric manageBtn = new ButtonGeneric(this.width / 10, this.height - 30, 120, 20,
					StringUtils.translate("autotrade.gui.button.manage_pairs"));
			this.addButton(manageBtn, (button, mouseButton) -> {
				GuiBase.openGui(new PairListScreen());
			});
		}
	}

	private int createButton(int x, int y, int width, ConfigGuiTab tab) {
		ButtonGeneric button = new ButtonGeneric(x, y, width, 20, tab.getDisplayName());
		button.setEnabled(GuiConfigs.tab != tab);
		this.addButton(button, new ButtonListener(tab, this));

		return button.getWidth() + 2;
	}

	@Override
	protected int getConfigWidth() {
		ConfigGuiTab tab = GuiConfigs.tab;
		int browserWidth = this.getBrowserWidth();
		// 期望宽度：通用页取浏览器宽度的 2/5，快捷键页沿用 malilib 默认宽度
		int desired = tab == ConfigGuiTab.GENERIC ? Math.max(200, browserWidth * 2 / 5) : super.getConfigWidth();
		// 预留空间：标签与控件间距 10 + 控件与重置按钮间距 2 + 重置按钮宽度（RESET 约 40，中文约 28）；
		// 快捷键行额外包含设置按钮 20 + 后续间距 22
		int reserved = tab == ConfigGuiTab.GENERIC ? 58 : 78;
		// 最大标签宽度（与 malilib 内部 maxLabelWidth 计算方式一致）
		int maxLabel = 0;
		for (ConfigOptionWrapper wrapper : this.getConfigs()) {
			if (wrapper.getType() == ConfigOptionWrapper.Type.CONFIG && wrapper.getConfig() != null) {
				maxLabel = Math.max(maxLabel, this.getStringWidth(wrapper.getConfig().getConfigGuiDisplayName()));
			}
		}
		// 收窄值控件宽度，保证「标签 + 值控件 + 重置按钮」不超出浏览器区域（滚动条），任何 GUI 缩放下都不溢出
		return Math.max(120, Math.min(desired, browserWidth - 14 - maxLabel - reserved));
	}

	@Override
	public List<ConfigOptionWrapper> getConfigs() {
		List<? extends IConfigBase> configs;
		ConfigGuiTab tab = GuiConfigs.tab;

		if (tab == ConfigGuiTab.GENERIC) {
			configs = Configs.Generic.OPTIONS;
		} else if (tab == ConfigGuiTab.HOTKEYS) {
			configs = Hotkeys.HOTKEY_LIST;
		} else {
			return Collections.emptyList();
		}

		return ConfigOptionWrapper.createFor(configs);
	}

	private record ButtonListener(ConfigGuiTab tab, GuiConfigs parent) implements IButtonActionListener {

		@Override
		public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
			GuiConfigs.tab = this.tab;

			this.parent.reCreateListWidget(); // apply the new config width
			this.parent.getListWidget().resetScrollbarPosition();
			this.parent.initGui();
		}
	}

	public enum ConfigGuiTab {
		GENERIC("autotrade.gui.button.config_gui.generic"), HOTKEYS("autotrade.gui.button.config_gui.hotkeys");

		private final String translationKey;

		public static final ImmutableList<ConfigGuiTab> VALUES = ImmutableList.copyOf(values());

		ConfigGuiTab(String translationKey) {
			this.translationKey = translationKey;
		}

		public String getDisplayName() {
			return StringUtils.translate(this.translationKey);
		}
	}
}
