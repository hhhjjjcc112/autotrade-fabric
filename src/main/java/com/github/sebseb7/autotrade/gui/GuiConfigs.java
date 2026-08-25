package com.github.sebseb7.autotrade.gui;

import com.github.sebseb7.autotrade.Reference;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.config.Hotkeys;
import com.github.sebseb7.autotrade.gui.widget.ItemIOTabList;
import com.github.sebseb7.autotrade.gui.widget.TradePairListConfigOptions;
import com.github.sebseb7.autotrade.gui.widget.TradePairListEntryWidget;
import com.github.sebseb7.autotrade.trade.data.TradePair;
import com.github.sebseb7.autotrade.trade.data.TradePairCache;
import com.github.sebseb7.autotrade.util.ItemStringHelper;
import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.options.ConfigString;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.GuiScrollBar;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.IKeybindConfigGui;
import fi.dy.masa.malilib.gui.widgets.WidgetConfigOption;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptions;
import fi.dy.masa.malilib.util.StringUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GuiConfigs extends GuiConfigsBase {
	private static ConfigGuiTab tab = ConfigGuiTab.GENERIC;
	/**
	 * 屏幕重建后待恢复的纵向滚动条位置（-1 = 不恢复）。同页刷新路径（启停/删除/新增交易对、编辑屏返回） 在重建前记录旧位置，initGui
	 * 中消费恢复；切选项卡前由 ButtonListener 清空（保持重置行为）。
	 */
	private static int pendingScrollRestore = -1;
	/** 切到 IO 页后待滚动定位的目标行物品编码串；null = 无跳转目标 */
	private static String pendingIoJumpItem = null;

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

		// 消费待恢复滚动条位置：同页刷新路径（启停/删除/新增交易对、编辑屏返回）在重建前记录旧位置，
		// 此处恢复（切选项卡前已被 ButtonListener 清空，保持切页重置行为）
		if (GuiConfigs.pendingScrollRestore >= 0 && this.getListWidget() != null
				&& this.getListWidget().getScrollbar() != null) {
			GuiScrollBar sb = this.getListWidget().getScrollbar();
			// 新列表的 maxValue 要等首次 draw 才计算：先临时抬高再 setValue，首次绘制时按真实条目数钳制
			sb.setMaxValue(GuiConfigs.pendingScrollRestore);
			sb.setValue(GuiConfigs.pendingScrollRestore);
			GuiConfigs.pendingScrollRestore = -1;
		}

		// 消费待跳转 IO 行：交易对页点击物品图标后切到对应 IO 页并滚动定位目标行（仅 IO 页的 ItemIOTabList 消费；行不存在时静默跳过）
		if (GuiConfigs.pendingIoJumpItem != null && this.getListWidget() instanceof ItemIOTabList ioList) {
			ioList.scrollToItem(GuiConfigs.pendingIoJumpItem);
			GuiConfigs.pendingIoJumpItem = null;
		}

		int x = this.width / 10;
		int y = 26;
		int gap = 2;
		int tabCount = ConfigGuiTab.VALUES.size();
		// 单按钮最大宽度：均分浏览器宽度，保证标签按钮组不超出滚动条区域
		// （8 个选项卡：GUI 200% 缩放下浏览器宽度 ≈ 512，(512 - 2*7) / 8 ≈ 62px，
		// 中文标签约 36px（「移动交易」）放得下；英文最长标签约 64px（「Static Trade」）略超会被钳制截断，仅极端 200% 缩放下可见）
		int maxTabWidth = (this.getBrowserWidth() - gap * (tabCount - 1)) / tabCount;

		for (ConfigGuiTab tab : ConfigGuiTab.VALUES) {
			int width = Math.min(this.getStringWidth(tab.getDisplayName()) + 10, maxTabWidth);
			x += this.createButton(x, y, width, tab);
		}

		// 底部按钮行：按当前页分支（GENERIC 页原「Manage Pairs」按钮已移除，交易对在选项卡内管理）
		if (GuiConfigs.tab == ConfigGuiTab.TRADE_PAIRS) {
			// 列表视图：新增交易对按钮（复用旧列表屏的键与添加逻辑；刷新走滚动条恢复路径）
			ButtonGeneric addBtn = new ButtonGeneric(this.width / 10, this.height - 24, 90, 20,
					StringUtils.translate("autotrade.gui.pair_list.add"));
			this.addButton(addBtn, (b, mb) -> {
				TradePairCache.add("minecraft:air", "minecraft:air", 1);
				this.refreshWithScrollRestore();
			});
		}
	}

	/** 同页刷新（启停/删除/新增交易对）：记录当前滚动条位置 → 重建屏幕 → initGui 消费恢复 */
	private void refreshWithScrollRestore() {
		if (this.getListWidget() != null && this.getListWidget().getScrollbar() != null) {
			GuiConfigs.pendingScrollRestore = this.getListWidget().getScrollbar().getValue();
		} else {
			GuiConfigs.pendingScrollRestore = -1;
		}
		this.initGui();
	}

	/** 交易对页物品图标点击跳转：切到对应方向（输入/输出）的 IO 页并滚动定位到该物品行 */
	public void jumpToItemIo(boolean isInput, String item) {
		GuiConfigs.pendingIoJumpItem = item;
		GuiConfigs.tab = isInput ? ConfigGuiTab.IO_INPUT : ConfigGuiTab.IO_OUTPUT;
		// 切页不恢复旧滚动条位置（保持切页重置行为）
		GuiConfigs.pendingScrollRestore = -1;
		this.reCreateListWidget();
		this.getListWidget().resetScrollbarPosition();
		this.initGui();
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
		// 期望宽度：值类型页（通用/静止交易/虚空交易/交易对/IO 页）取浏览器宽度的 2/5，快捷键页沿用 malilib 默认宽度
		boolean valueTab = tab != ConfigGuiTab.HOTKEYS;
		int desired = valueTab ? Math.max(200, browserWidth * 2 / 5) : super.getConfigWidth();
		// 预留空间：标签与控件间距 10 + 控件与重置按钮间距 2 + 重置按钮宽度（RESET 约 40，中文约 28）；
		// 快捷键行额外包含设置按钮 20 + 后续间距 22
		int reserved = valueTab ? 58 : 78;
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

		switch (tab) {
			case GENERIC -> configs = Configs.Generic.OPTIONS;
			case STATIC -> configs = Configs.Static.OPTIONS;
			case MOVING -> configs = Configs.Moving.OPTIONS;
			case VOID -> configs = Configs.Void.OPTIONS;
			case HOTKEYS -> configs = Hotkeys.HOTKEY_LIST;
			case TRADE_PAIRS -> {
				return getTradePairConfigs();
			}
			case IO_INPUT, IO_OUTPUT -> {
				// IO 页条目由 ItemIOTabList 内部派生渲染，父屏不提供配置行
				return Collections.emptyList();
			}
			default -> configs = Collections.emptyList();
		}

		return ConfigOptionWrapper.createFor(configs);
	}

	// 交易对页配置行：复制旧独立列表屏 getConfigs() 的行标签逻辑
	// （只读 ConfigString 行命名 pair_<i>，供 TradePairListEntryWidget 解析下标渲染）
	private List<ConfigOptionWrapper> getTradePairConfigs() {
		// 缓存访问器：行标签构建只读（仅 get，不改动交易对）
		List<TradePair> pairs = TradePairCache.getAll();
		if (pairs.isEmpty()) {
			// 空态提示行（复用旧列表屏空态键）
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
			// 行标签：give1 后跟 x{limit}；give2 非空时追加 x{give2Count}（0 = 未记录 → 不显示），再箭头 + get（get
			// 后跟 x{getCount}）
			String label = StringUtils.translate("autotrade.gui.pair_list.trade_number", i + 1) + ". "
					+ StringUtils.translate(statusKey) + " " + giveName + " "
					+ StringUtils.translate("autotrade.gui.pair_list.limit_prefix", p.getLimit());
			if (!p.getGiveItem2().isEmpty()) {
				label += " + " + ItemStringHelper.getItemId(p.getGiveItem2());
				if (p.getGive2Count() > 0)
					label += " " + StringUtils.translate("autotrade.gui.pair_list.limit_prefix", p.getGive2Count());
			}
			label += " " + StringUtils.translate("autotrade.gui.pair_list.arrow") + " " + getName;
			if (p.getGetCount() > 0)
				label += " " + StringUtils.translate("autotrade.gui.pair_list.limit_prefix", p.getGetCount());
			configs.add(new ConfigOptionWrapper(new ConfigString("pair_" + i, label, "")));
		}
		return configs;
	}

	// 交易对行「编辑」按钮回调：记录当前滚动条位置（编辑屏返回后恢复）并打开独立编辑屏
	private void onPairEdit(int pairIndex) {
		if (this.getListWidget() != null && this.getListWidget().getScrollbar() != null) {
			GuiConfigs.pendingScrollRestore = this.getListWidget().getScrollbar().getValue();
		}
		GuiBase.openGui(new PairEditScreen(pairIndex));
	}

	@Override
	protected WidgetListConfigOptions createListWidget(int listX, int listY) {
		ConfigGuiTab tab = GuiConfigs.tab;
		if (tab == ConfigGuiTab.TRADE_PAIRS) {
			// 交易对页：行渲染与操作委托 TradePairListConfigOptions，并注入行操作回调
			// （编辑 → 打开独立编辑屏；启停/删除 → 重建本屏刷新列表并恢复滚动条位置）
			return new TradePairListConfigOptions(listX, listY, this.getBrowserWidth(), this.getBrowserHeight(),
					this.getConfigWidth(), 0.f, this.useKeybindSearch(), this) {
				@Override
				protected WidgetConfigOption createListEntryWidget(int x, int y, int listIndex, boolean isOdd,
						ConfigOptionWrapper wrapper) {
					return new TradePairListEntryWidget(x, y, this.browserEntryWidth,
							this.getBrowserEntryHeightFor(wrapper), this.maxLabelWidth, this.configWidth, wrapper,
							listIndex, (IKeybindConfigGui) this.parent, this, GuiConfigs.this::onPairEdit,
							GuiConfigs.this::refreshWithScrollRestore, GuiConfigs.this::jumpToItemIo);
				}
			};
		}
		if (tab == ConfigGuiTab.IO_INPUT || tab == ConfigGuiTab.IO_OUTPUT) {
			// IO 页：自给自足的派生列表控件（方向参数化，条目由交易对自动派生）
			return new ItemIOTabList(listX, listY, this.getBrowserWidth(), this.getBrowserHeight(),
					this.getConfigWidth(), 0.f, this.useKeybindSearch(), this, tab == ConfigGuiTab.IO_INPUT);
		}
		// GENERIC/STATIC/MOVING/VOID/HOTKEYS：基础配置列表控件（原行为，与 malilib 默认构造一致）
		return new WidgetListConfigOptions(listX, listY, this.getBrowserWidth(), this.getBrowserHeight(),
				this.getConfigWidth(), 0.f, this.useKeybindSearch(), this);
	}

	private record ButtonListener(ConfigGuiTab tab, GuiConfigs parent) implements IButtonActionListener {

		@Override
		public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
			GuiConfigs.tab = this.tab;
			// 切换选项卡后重置滚动条（不恢复旧页位置；先清空待恢复标记，防止 initGui 误消费）
			GuiConfigs.pendingScrollRestore = -1;
			// 手动切页清空待跳转 IO 行目标，防止 initGui 误消费
			GuiConfigs.pendingIoJumpItem = null;

			this.parent.reCreateListWidget(); // apply the new config width
			this.parent.getListWidget().resetScrollbarPosition();
			this.parent.initGui();
		}
	}

	public enum ConfigGuiTab {
		GENERIC("autotrade.gui.button.config_gui.generic"), TRADE_PAIRS(
				"autotrade.gui.button.config_gui.pairs"), IO_INPUT(
						"autotrade.gui.button.config_gui.io_input"), IO_OUTPUT(
								"autotrade.gui.button.config_gui.io_output"), STATIC(
										"autotrade.gui.button.config_gui.static"), MOVING(
												"autotrade.gui.button.config_gui.moving"), VOID(
														"autotrade.gui.button.config_gui.void"), HOTKEYS(
																"autotrade.gui.button.config_gui.hotkeys");

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
