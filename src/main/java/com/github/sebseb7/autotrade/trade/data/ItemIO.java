package com.github.sebseb7.autotrade.trade.data;

import java.util.ArrayList;
import java.util.List;

/**
 * 表示一条物品容器 IO 配置：指定某个物品（{@code item}）从输入容器取出或放入输出容器，
 * 并带有一组容器位置记录（{@link ItemIOLocation}，每条含维度/坐标/启用）、行级补货阈值、 单次取放数量与行级总开关。
 *
 * <p>
 * {@code item} 使用 {@link com.github.sebseb7.autotrade.util.ItemStringHelper}
 * 的编码格式 （与 {@link TradePair#getGiveItem()} 相同），例如
 * {@code {"id":"minecraft:nether_star"}}。
 * </p>
 *
 * <p>
 * {@code isInput} 为 true 表示从容器取物品（输入），false 表示向容器放物品（输出）。
 * </p>
 */
public final class ItemIO {
	private String item;
	private boolean isInput;
	/** 容器位置记录列表（每条含维度 + 坐标 + 启用开关；同一物品可配置多个容器） */
	private List<ItemIOLocation> locations = new ArrayList<>();
	/** 补货/清出阈值（单位：组 = 槽位数；输入方向：该物品占用 ≤ N 组时补货；输出方向：占用 ≥ N 组时清出。默认 1 = 剩 1 组时补货） */
	private int threshold = 1;
	/** 单次取放数量（单位：组 = 槽位数，每次容器 IO 最多搬运的组数；仅输入方向生效，默认 6） */
	private int takeAmount = 6;
	/** 条目启用开关（默认 true；旧配置文件缺失该字段时读取为启用，无需迁移） */
	private boolean enabled = true;

	/** 默认构造：threshold 默认 1、takeAmount 默认 6 */
	public ItemIO() {
	}

	/** 全参构造：直接指定全部字段（enabled 保持默认 true） */
	public ItemIO(String item, boolean isInput, List<ItemIOLocation> locations, int threshold, int takeAmount) {
		this.item = item;
		this.isInput = isInput;
		this.locations = locations;
		this.threshold = threshold;
		this.takeAmount = takeAmount;
	}

	/** 拷贝构造：复制全部字段；locations 为可变对象列表，逐条深拷贝以防 UI 副本与缓存条目共享引用 */
	public ItemIO(ItemIO other) {
		this.item = other.item;
		this.isInput = other.isInput;
		this.locations = new ArrayList<>();
		for (ItemIOLocation loc : other.locations) {
			this.locations.add(new ItemIOLocation(loc));
		}
		this.threshold = other.threshold;
		this.takeAmount = other.takeAmount;
		this.enabled = other.enabled;
	}

	public String getItem() {
		return item;
	}

	public void setItem(String v) {
		this.item = v;
	}

	public boolean isInput() {
		return isInput;
	}

	public void setInput(boolean v) {
		this.isInput = v;
	}

	/** 返回内部位置记录列表引用（调用方约定只读，不得直接增删改元素） */
	public List<ItemIOLocation> getLocations() {
		return locations;
	}

	/** 整体替换位置记录列表 */
	public void setLocations(List<ItemIOLocation> v) {
		this.locations = v;
	}

	/** 位置记录条数 */
	public int locationCount() {
		return locations.size();
	}

	public int getThreshold() {
		return threshold;
	}

	public void setThreshold(int v) {
		this.threshold = v;
	}

	public int getTakeAmount() {
		return takeAmount;
	}

	public void setTakeAmount(int v) {
		this.takeAmount = v;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean v) {
		this.enabled = v;
	}
}