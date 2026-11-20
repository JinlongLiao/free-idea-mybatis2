package io.github.jinlongliao.mybatis.workbench.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

import io.github.jinlongliao.mybatis.workbench.model.DbType;

/**
 * JDBC 表操作共用的对象定位、标识符引用和事务引擎检查。
 *
 * @author liaojinlong
 * @since 2026/11/18 16:24
 */
final class JdbcTableSql {
	/**
	 * 表 SQL 工具不需要实例。
	 */
	private JdbcTableSql() {
	}

	/**
	 * 将可信元数据中的 catalog、schema 和表名按方言组合为查询或写入的目标。
	 *
	 * @param dbType 当前数据库
	 * @param table  JDBC 返回的定位信息
	 * @param quote  驱动提供的标识符引号
	 * @return 已分段引用的目标表名
	 * @throws SQLException 驱动没有提供可用标识符引用
	 */
	static String qualifiedTableName(DbType dbType, JdbcTable table, String quote) throws SQLException {
		String name = quoteIdentifier(table.name(), quote);
		String qualifier = switch (dbType) {
			case MySQL, MariaDB -> Objects.nonNull(table.catalog()) ? table.catalog() : table.schema();
			case SQLite -> sqliteSchema(table);
			default -> table.schema();
		};
		if (Objects.nonNull(qualifier) && !qualifier.isEmpty()) {
			return quoteIdentifier(qualifier, quote) + "." + name;
		}
		return name;
	}

	/**
	 * 解析 SQLite 表的数据库名。驱动未返回归属时，已列出的普通表归入主库。
	 *
	 * @param table JDBC 表对象
	 * @return 数据库名
	 */
	static String sqliteSchema(JdbcTable table) {
		String schema = Objects.toString(table.schema(), "");
		if (schema.isEmpty()) {
			schema = Objects.toString(table.catalog(), "");
		}
		return schema.isEmpty() ? "main" : schema;
	}

	/**
	 * 对一个由 JDBC 返回的标识符进行引用，引用其中的引号字符。
	 *
	 * @param identifier 数据库对象或列名
	 * @param quote      驱动使用的引号字符
	 * @return 在当前方言中带引号的标识符
	 * @throws SQLException 无法安全引用标识符
	 */
	static String quoteIdentifier(String identifier, String quote) throws SQLException {
		if (!"\"".equals(quote) && !"`".equals(quote)) {
			throw new SQLException("当前驱动不提供受支持的标识符引号");
		}
		return quote + identifier.replace(quote, quote + quote) + quote;
	}

	/**
	 * 在 MySQL 和 MariaDB 中限制引擎为 InnoDB，防止写入中途失败时无法回滚。
	 *
	 * @param connection 已关闭自动提交的连接
	 * @param dbType     数据库方言
	 * @param table      真实目标表
	 * @param probeSql   用于在事务内打开目标表的空查询
	 * @throws SQLException 非事务引擎、目标找不到或无法查询元数据
	 */
	static void assertTransactionalTable(Connection connection, DbType dbType, JdbcTable table,
										 String probeSql) throws SQLException {
		if (dbType != DbType.MySQL && dbType != DbType.MariaDB) {
			return;
		}
		try (var statement = connection.createStatement()) {
			statement.setQueryTimeout(15);
			try (var ignored = statement.executeQuery(probeSql)) {
				// 目标表在当前事务持有元数据锁。
			}
		}
		String database = Objects.nonNull(table.catalog()) ? table.catalog() : table.schema();
		try (var statement = connection.prepareStatement(
			"SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
			statement.setQueryTimeout(15);
			statement.setString(1, database);
			statement.setString(2, table.name());
			try (var rows = statement.executeQuery()) {
				if (!rows.next() || !"InnoDB".equalsIgnoreCase(rows.getString(1))) {
					throw new SQLException("MySQL 或 MariaDB 的事务写入要求 InnoDB 表");
				}
			}
		}
	}
}
