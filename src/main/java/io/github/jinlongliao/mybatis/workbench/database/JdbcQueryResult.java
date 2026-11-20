package io.github.jinlongliao.mybatis.workbench.database;

import java.util.List;
import java.util.OptionalLong;

/**
 * 一条 JDBC 语句的首个结果，供 IDE 界面展示。结果集与数据库连接的生命周期独立。
 *
 * @param columnLabels 按结果列顺序排列的显示名称，允许重复
 * @param rows         按结果列顺序排列的显示值，SQL NULL 保留为空引用
 * @param truncated    结果集还有未返回的行
 * @param updateCount  无结果集时驱动报告的影响行数，驱动未报告时为空
 * @author liaojinlong
 * @since 2026/11/18 14:47
 */
public record JdbcQueryResult(List<String> columnLabels, List<List<String>> rows, boolean truncated,
							  OptionalLong updateCount) {
}
