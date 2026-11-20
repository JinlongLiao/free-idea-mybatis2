package io.github.jinlongliao.mybatis.workbench.database;

import static io.github.jinlongliao.mybatis.workbench.database.DatabaseMessages.databaseMessage;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

import io.github.jinlongliao.mybatis.workbench.model.DbType;

/**
 * 读取数据库返回的建表或建视图定义，供工作台查看及复制。
 *
 * @author liaojinlong
 * @see <a href="https://dev.mysql.com/doc/refman/8.4/en/show-create-table.html">MySQL SHOW CREATE TABLE</a>
 * @see <a href="https://www.sqlite.org/schematab.html">SQLite schema table</a>
 * @see <a href="https://h2database.com/html/commands.html#script">H2 SCRIPT</a>
 * @since 2026/11/18 16:16
 */
public final class JdbcTableDdl {
	/**
	 * H2 的 SCRIPT 可能包含用户和权限，只接受对象定义及表列注释，保留唯一索引的 NULL 判重规则。
	 */
	private static final Pattern H2_OBJECT_DDL = Pattern.compile(
		"^\\s*(?:CREATE (?:MEMORY |CACHED )?TABLE |CREATE (?:(?:OR REPLACE|FORCE) )*VIEW "
			+ "|CREATE (?:UNIQUE (?:NULLS (?:NOT |ALL )?DISTINCT )?)?INDEX |ALTER TABLE |COMMENT ON (?:TABLE|COLUMN) )",
		Pattern.CASE_INSENSITIVE);

	/**
	 * DDL 读取使用静态方法。
	 */
	private JdbcTableDdl() {
	}

	/**
	 * 从选定数据库读取目标对象的建表或建视图 DDL。目标名来自当前连接的对象树。
	 * 各数据库能返回的附属对象不同，结果用于审阅和复制。
	 *
	 * @param connection 当前数据源的 JDBC 连接
	 * @param type       数据库方言
	 * @param table      对象树选择的表或视图
	 * @return 数据库返回的对象定义
	 * @throws SQLException JDBC 读取失败、没有权限或对象已不存在
	 */
	public static String readTableDdl(Connection connection, DbType type, JdbcTable table) throws SQLException {
		return readTableDdl(connection, type, table, key -> databaseMessage(Locale.getDefault(), key));
	}

	/**
	 * 从选定数据库读取目标对象的建表或建视图 DDL。目标名来自当前连接的对象树。
	 * 各数据库能返回的附属对象不同，结果用于审阅和复制。
	 *
	 * @param connection 当前数据源的 JDBC 连接
	 * @param type       数据库方言
	 * @param table      对象树选择的表或视图
	 * @param messages 宿主提供的错误文案查询函数
	 * @return 数据库返回的对象定义
	 * @throws SQLException JDBC 读取失败、没有权限或对象已不存在
	 */
	public static String readTableDdl(Connection connection, DbType type, JdbcTable table,
		Function<String, String> messages) throws SQLException {
		String quote = connection.getMetaData().getIdentifierQuoteString();
		String qualified = JdbcTableSql.qualifiedTableName(type, table, quote);
		boolean view = "VIEW".equalsIgnoreCase(table.type());
		return switch (type) {
			case MySQL, MariaDB ->
				readShowCreate(connection, (view ? "SHOW CREATE VIEW " : "SHOW CREATE TABLE ") + qualified, messages);
			case SQLite -> readSqliteDdl(connection, table, quote, true, messages);
			case H2 -> readH2Ddl(connection, qualified, messages);
			case Oracle -> readOracleDdl(connection, table, view, messages);
			case PostgreSQL -> PostgreSqlTableDdl.readPostgreSqlDdl(connection, table, qualified, messages);
			case SqlServer -> view ? readSqlServerViewDdl(connection, table, messages)
				: SqlServerTableDdl.readSqlServerTableDdl(connection, table, qualified, quote);
		};
	}

	/**
	 * 读取 MySQL/MariaDB SHOW CREATE 的定义列。
	 *
	 * @param connection 当前连接
	 * @param sql        仅包含元数据对象名的 SHOW CREATE 语句
	 * @param messages 宿主提供的错误文案查询函数
	 * @return DDL 文本
	 * @throws SQLException 缺少定义或驱动错误
	 */
	private static String readShowCreate(Connection connection, String sql,
		Function<String, String> messages) throws SQLException {
		try (var statement = connection.createStatement()) {
			statement.setQueryTimeout(15);
			try (var result = statement.executeQuery(sql)) {
				return readDefinition(result, 2, messages);
			}
		}
	}

	/**
	 * 按所选 SQLite 数据库名和参数绑定的对象名读取 sqlite_schema。
	 *
	 * @param connection 当前连接
	 * @param table      表或视图
	 * @param quote      标识符引号
	 * @param includeDependentObjects 是否附加同一数据库中属于该表或视图的显式索引和触发器
	 * @param messages 宿主提供的错误文案查询函数
	 * @return SQLite 定义及所请求的索引和触发器定义
	 * @throws SQLException 对象不存在或元数据查询失败
	 */
	static String readSqliteDdl(Connection connection, JdbcTable table, String quote, boolean includeDependentObjects,
		Function<String, String> messages) throws SQLException {
		String schema = JdbcTableSql.quoteIdentifier(JdbcTableSql.sqliteSchema(table), quote);
		boolean view = "VIEW".equalsIgnoreCase(table.type());
		try (var statement = connection.prepareStatement(
			"SELECT sql FROM " + schema + ".sqlite_schema WHERE sql IS NOT NULL AND "
				+ "((type = ? AND name = ?) OR (type IN ('index', 'trigger') AND tbl_name = ? AND ?)) "
				+ "ORDER BY CASE type WHEN 'index' THEN 1 WHEN 'trigger' THEN 2 ELSE 0 END, rowid")) {
			statement.setQueryTimeout(15);
			statement.setString(1, view ? "view" : "table");
			statement.setString(2, table.name());
			statement.setString(3, table.name());
			statement.setBoolean(4, includeDependentObjects);
			try (var result = statement.executeQuery()) {
				String definition = readDefinition(result, 1, messages);
				List<String> definitions = new ArrayList<>();
				definitions.add(definition);
				while (result.next()) {
					definitions.add(result.getString(1));
				}
				return String.join(";\n", definitions);
			}
		}
	}

	/**
	 * 读取 H2 的单表脚本，保留表、列注释和关联约束，过滤连接用户及数据库设置。
	 *
	 * @param connection 当前连接
	 * @param qualified  带引号的表名
	 * @param messages 宿主提供的错误文案查询函数
	 * @return 目标表的 DDL
	 * @throws SQLException 脚本权限不足或未找到选定对象
	 */
	private static String readH2Ddl(Connection connection, String qualified,
		Function<String, String> messages) throws SQLException {
		List<String> ddl = new ArrayList<>();
		try (var statement = connection.createStatement()) {
			statement.setQueryTimeout(15);
			try (var result = statement.executeQuery("SCRIPT NODATA NOPASSWORDS NOSETTINGS TABLE " + qualified)) {
				while (result.next()) {
					String line = result.getString(1);
					if (H2_OBJECT_DDL.matcher(line).find()) {
						ddl.add(line);
					}
				}
			}
		}
		if (ddl.isEmpty()) {
			throw new SQLException(messages.apply("table.ddl.h2.missing"));
		}
		return String.join("\n", ddl);
	}

	/**
	 * 将 Oracle 的对象类型、名称和所有者分别绑定给 DBMS_METADATA.GET_DDL。
	 *
	 * @param connection 当前连接
	 * @param table      目标对象
	 * @param view       是否读取视图
	 * @param messages 宿主提供的错误文案查询函数
	 * @return 对象 DDL
	 * @throws SQLException 缺少对象或需要额外的读取权限
	 * @see <a href="https://docs.oracle.com/en/database/oracle/oracle-database/19/arpls/DBMS_METADATA.html">Oracle DBMS_METADATA</a>
	 */
	private static String readOracleDdl(Connection connection, JdbcTable table, boolean view,
		Function<String, String> messages) throws SQLException {
		try (var statement = connection.prepareStatement("SELECT DBMS_METADATA.GET_DDL(?, ?, ?) FROM dual")) {
			statement.setQueryTimeout(15);
			statement.setString(1, view ? "VIEW" : "TABLE");
			statement.setString(2, table.name());
			statement.setString(3, table.schema());
			try (var result = statement.executeQuery()) {
				return readDefinition(result, 1, messages);
			}
		}
	}

	/**
	 * 从当前数据库的系统目录取得 SQL Server 视图原始定义。
	 *
	 * @param connection 当前数据源的 JDBC 连接
	 * @param table      从该连接读取的视图
	 * @param messages 宿主提供的错误文案查询函数
	 * @return SQL Server 保存的视图定义
	 * @throws SQLException 定义不可见、视图已经删除或读取失败
	 * @see <a href="https://learn.microsoft.com/en-us/sql/relational-databases/system-catalog-views/sys-sql-modules-transact-sql">SQL Server sys.sql_modules</a>
	 */
	private static String readSqlServerViewDdl(Connection connection, JdbcTable table,
		Function<String, String> messages) throws SQLException {
		try (var statement = connection.prepareStatement("""
			SELECT modules.definition
			FROM sys.views AS views
			JOIN sys.schemas AS schemas ON schemas.schema_id = views.schema_id
			JOIN sys.sql_modules AS modules ON modules.object_id = views.object_id
			WHERE schemas.name = ? AND views.name = ?
			""")) {
			statement.setQueryTimeout(15);
			statement.setNString(1, table.schema());
			statement.setNString(2, table.name());
			try (var result = statement.executeQuery()) {
				return readDefinition(result, 1, messages);
			}
		}
	}

	/**
	 * 读取数据库返回的一条非空定义。
	 *
	 * @param rows   定义查询的结果
	 * @param column 结果中的定义列，从 1 开始
	 * @param messages 宿主提供的错误文案查询函数
	 * @return DDL 文本
	 * @throws SQLException 找不到对象或查询失败
	 */
	private static String readDefinition(ResultSet rows, int column,
		Function<String, String> messages) throws SQLException {
		if (rows.next()) {
			String definition = rows.getString(column);
			if (Objects.nonNull(definition) && !definition.isBlank()) {
				return definition;
			}
		}
		throw new SQLException(messages.apply("table.ddl.missing"));
	}
}
