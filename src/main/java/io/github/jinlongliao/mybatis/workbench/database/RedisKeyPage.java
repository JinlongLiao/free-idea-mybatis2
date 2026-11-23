package io.github.jinlongliao.mybatis.workbench.database;

import java.util.List;

/**
 * Redis 节点的增量浏览结果。游标只能在返回该页的节点继续使用。
 *
 * @param cursor 下次使用的 Redis SCAN 游标，0 表示本轮已结束
 * @param keys   当前扫描批次的键
 * @author liaojinlong
 * @since 2026/11/18 15:33
 */
public record RedisKeyPage(String cursor, List<String> keys) {
	/**
	 * 返回从指定下标开始、最多 pageSize 项后的列表边界，用于把 SCAN 响应拆成硬展示页。
	 *
	 * @param offset 当前响应中已展示的键数，范围为 0 至键总数
	 * @param pageSize 页面最大键数，必须为正数
	 * @return 下一展示页的结束下标，不包含该下标
	 */
	public int nextPageEnd(int offset, int pageSize) {
		if (offset < 0 || offset > keys.size() || pageSize < 1) {
			throw new IllegalArgumentException("Redis page offset or size is invalid");
		}
		return offset + Math.min(pageSize, keys.size() - offset);
	}
}
