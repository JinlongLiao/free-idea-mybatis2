package io.github.jinlongliao.mybatis.workbench.database;

import java.util.List;

/**
 * 比较两张表的 JDBC 列结构和主键后得到的差异及可审阅 SQL 草案。
 *
 * @param changes        面向用户的结构差异
 * @param migrationDraft 可选的新增简单可空列语句，调用者仅展示
 * @author liaojinlong
 * @since 2026/11/18 16:26
 */
public record JdbcTableDifferences(List<String> changes, List<String> migrationDraft) {
}
