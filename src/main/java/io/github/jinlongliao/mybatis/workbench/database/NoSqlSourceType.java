package io.github.jinlongliao.mybatis.workbench.database;

/**
 * 非 JDBC 数据源使用的协议。
 *
 * @author liaojinlong
 * @since 2026/11/18 16:50
 */
public enum NoSqlSourceType {
	/**
	 * Redis 单机或 Cluster。
	 */
	REDIS,
	/**
	 * MongoDB 单机或副本集。
	 */
	MONGODB,
	/**
	 * Elasticsearch HTTPS API。
	 */
	ELASTICSEARCH
}
