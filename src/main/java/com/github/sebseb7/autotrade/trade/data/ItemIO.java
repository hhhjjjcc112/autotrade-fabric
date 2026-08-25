package com.github.sebseb7.autotrade.trade.data;

/**
 * 表示一条物品容器 IO 配置：指定某个物品（{@code item}）从输入容器取出或放入输出容器， 并带有容器坐标、补货阈值与单次取放数量。
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
	private int x;
	private int y;
	private int z;
	/** 补货阈值（占用槽位数，默认 1 = 剩 1 组时补货） */
	private int threshold = 1;
	/** 单次取放数量（默认 6） */
	private int takeAmount = 6;
	/** 条目启用开关（默认 true；旧配置文件缺失该字段时读取为启用，无需迁移） */
	private boolean enabled = true;

	/** 默认构造：threshold 默认 1、takeAmount 默认 6 */
	public ItemIO() {
	}

	/** 全参构造：直接指定全部字段 */
	public ItemIO(String item, boolean isInput, int x, int y, int z, int threshold, int takeAmount) {
		this.item = item;
		this.isInput = isInput;
		this.x = x;
		this.y = y;
		this.z = z;
		this.threshold = threshold;
		this.takeAmount = takeAmount;
	}

	/** 拷贝构造：复制全部 8 个字段（默认值语义经拷贝保留原值；String 为不可变对象，直接引用复制即可） */
	public ItemIO(ItemIO other) {
		this.item = other.item;
		this.isInput = other.isInput;
		this.x = other.x;
		this.y = other.y;
		this.z = other.z;
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

	public int getX() {
		return x;
	}

	public void setX(int v) {
		this.x = v;
	}

	public int getY() {
		return y;
	}

	public void setY(int v) {
		this.y = v;
	}

	public int getZ() {
		return z;
	}

	public void setZ(int v) {
		this.z = v;
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