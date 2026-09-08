package com.github.sebseb7.autotrade.trade.io;

/**
 * 条目级 IO 键（物品编码 + 容器位置键）：ioKey 格式 item#dim,x,y,z#isInput 的强类型载体。
 *
 * <p>
 * 键格式是跨模块契约——MOVING 饥饿记账键（跨条目实例稳定、同容器不同物品独立记账）与 9.8 去重依赖该字符串， 必须字节稳定（物品编码为 Gson
 * JSON，可含任意字符，键仅作整体比较/存储，不做分隔符解析）。
 * </p>
 */
public record IoKey(String item, ContainerLocKey locKey) {

	/** 序列化为 ioKey 格式（物品编码 + # + containerKey）；与旧 ContainerCandidate.ioKey() 字节一致 */
	public String format() {
		return item + "#" + locKey.format();
	}
}