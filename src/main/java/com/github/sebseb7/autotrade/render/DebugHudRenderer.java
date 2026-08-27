package com.github.sebseb7.autotrade.render;

import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.runtime.AutoTradeClientTick;
import com.github.sebseb7.autotrade.trade.io.ContainerIOTask;
import com.github.sebseb7.autotrade.trade.machine.AbstractTradeMachine;
import com.github.sebseb7.autotrade.trade.machine.TradingMachine;
import com.github.sebseb7.autotrade.trade.mode.TradeMode;
import com.github.sebseb7.autotrade.trade.mode.movingmode.MovingTradeMachine;
import com.github.sebseb7.autotrade.trade.mode.staticmode.StaticTradeMachine;
import com.github.sebseb7.autotrade.trade.mode.voidmode.VoidTradeMachine;
import com.github.sebseb7.autotrade.trade.stats.TradeStats;
import com.github.sebseb7.autotrade.trade.task.BlockTriggerTask;
import com.github.sebseb7.autotrade.trade.task.Task;
import com.github.sebseb7.autotrade.trade.task.TradeTask;
import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * 调试 HUD 渲染器：在游戏叠加层上绘制交易状态面板（开关/机器/任务/统计/模式特有信息）。 实现 malilib 的
 * {@link IRenderer}，仅覆写 {@link #onRenderGameOverlayPost(DrawContext)}， 其余方法为接口
 * default 实现。单例模式，经 InitHandler 注册到 malilib 渲染分发器。
 */
public class DebugHudRenderer implements IRenderer {

	/** 面板距屏幕边缘的边距（像素） */
	private static final int MARGIN = 2;
	/** 文本颜色（近白，高 alpha） */
	private static final int TEXT_COLOR = 0xE0FFFFFF;
	/** 背景色（半透明黑） */
	private static final int BG_COLOR = 0x90000000;

	private static final DebugHudRenderer INSTANCE = new DebugHudRenderer();

	private DebugHudRenderer() {
	}

	public static DebugHudRenderer getInstance() {
		return INSTANCE;
	}

	/**
	 * 游戏叠加层渲染回调（原版 HUD 绘制完成后调用）：开关开启且玩家/世界就绪时绘制调试面板。 本方法只做只读状态组装与绘制，不执行任何
	 * tick/交易操作，保证渲染线程安全。
	 */
	@Override
	public void onRenderGameOverlayPost(DrawContext drawContext) {
		if (!Configs.Generic.DEBUG_HUD.getBooleanValue()) {
			return;
		}
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.world == null) {
			return;
		}
		// 10 参 renderText：位置 = MARGIN 边距、缩放 1.0、背景与阴影均开启
		RenderUtils.renderText(MARGIN, MARGIN, 1.0, TEXT_COLOR, BG_COLOR,
				((HudPosition) Configs.Generic.DEBUG_HUD_POSITION.getOptionListValue()).toHudAlignment(), true, true,
				buildLines(mc), drawContext);
	}

	/**
	 * 组装调试面板文本行（最多 7 行）：标题 / 开关与模式 / 机器状态 / 当前任务 / 会话与累计成交 / IO 计数 / 模式特有行。 mod
	 * 关闭（机器为 null）时只返回前两行，不显示机器与计数信息。
	 */
	private List<String> buildLines(MinecraftClient mc) {
		List<String> lines = new ArrayList<>();
		boolean enabled = Configs.Generic.ENABLED.getBooleanValue();
		lines.add(StringUtils.translate("autotrade.debug.title"));
		lines.add(StringUtils.translate("autotrade.debug.status",
				StringUtils.translate(enabled ? "autotrade.debug.on" : "autotrade.debug.off"),
				((TradeMode) Configs.Generic.TRADE_MODE.getOptionListValue()).getDisplayName()));

		TradingMachine machine = AutoTradeClientTick.getInstance().getActiveMachine();
		if (!(machine instanceof AbstractTradeMachine am)) {
			// mod 关闭时 getActiveMachine 返回 null：不显示机器/计数行
			return lines;
		}

		lines.add(StringUtils.translate("autotrade.debug.machine", am.getStateName(), am.getTaskTicks(),
				am.getInventoryPauseCooldown()));

		// 任务状态经 instanceof 链取原始枚举名（不翻译，直接展示），未知任务类型显示 "-"。
		// BlockTriggerTask/ContainerIOTask 的 State 为 private 嵌套枚举，javac 禁止对私有类型表达式
		// 直接做成员访问（"defined in an inaccessible class"），统一经可访问的 Enum<?> 宽化转换取 name()
		Task task = am.getCurrentTask();
		String taskState;
		if (task instanceof TradeTask ts) {
			taskState = ((Enum<?>) ts.getState()).name();
		} else if (task instanceof BlockTriggerTask bt) {
			taskState = ((Enum<?>) bt.getState()).name();
		} else if (task instanceof ContainerIOTask io) {
			taskState = ((Enum<?>) io.getState()).name();
		} else {
			taskState = "-";
		}
		lines.add(StringUtils.translate("autotrade.debug.task", task == null ? "-" : task.getClass().getSimpleName(),
				taskState));

		lines.add(StringUtils.translate("autotrade.debug.trades", TradeStats.getInstance().getLastSessionTrades(),
				TradeStats.getInstance().getTotalTrades()));
		lines.add(StringUtils.translate("autotrade.debug.io", TradeStats.getInstance().getIoInputOps(),
				TradeStats.getInstance().getIoOutputOps()));

		// 模式特有行：三种模式互斥，只取其一（依次判断）
		if (am instanceof StaticTradeMachine sm) {
			lines.add(StringUtils.translate("autotrade.debug.static_line", sm.getProcessedCount(), sm.getTargetCount(),
					sm.getTradeCooldown(), sm.getContainerIOCooldown()));
		} else if (am instanceof MovingTradeMachine mm) {
			lines.add(StringUtils.translate("autotrade.debug.moving_line", mm.getProcessedCount(),
					mm.getStarvationCount()));
		} else if (am instanceof VoidTradeMachine vm) {
			lines.add(StringUtils.translate("autotrade.debug.void_line", StringUtils
					.translate(vm.isReturnTriggerConfigured() ? "autotrade.debug.on" : "autotrade.debug.off")));
		}

		return lines;
	}
}
