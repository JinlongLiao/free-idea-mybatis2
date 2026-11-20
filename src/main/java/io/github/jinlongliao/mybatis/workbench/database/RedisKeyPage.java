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
}
