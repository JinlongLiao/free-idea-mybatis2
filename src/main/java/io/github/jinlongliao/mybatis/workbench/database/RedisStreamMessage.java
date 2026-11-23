package io.github.jinlongliao.mybatis.workbench.database;

import java.util.List;
import java.util.Map;

/**
 * Redis Stream 消息预览，保留字段原始顺序与重复字段名；字段过多或快照超限时只保存样本且禁止条件删除。
 *
 * @param id Redis 返回的消息 ID
 * @param fields 按协议顺序排列的 UTF-8 字段和值
 * @param completeSnapshot 是否保留了整条消息字段快照；false 时只能展示，不能用于条件删除
 * @author liaojinlong
 * @since 2026/11/20 16:20
 */
public record RedisStreamMessage(String id, List<Map.Entry<String, String>> fields, boolean completeSnapshot) {
	/**
	 * 构造完整字段快照，供删除 CAS 与已有调用方使用。
	 *
	 * @param id Redis 返回的消息 ID
	 * @param fields 按协议顺序排列的字段和值
	 */
	public RedisStreamMessage(String id, List<Map.Entry<String, String>> fields) {
		this(id, fields, true);
	}
}
