package io.github.jinlongliao.mybatis.workbench.database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * 根据 SQL Server 系统目录生成普通表的结构预览。
 *
 * @author liaojinlong
 * @see <a href="https://learn.microsoft.com/en-us/sql/relational-databases/system-catalog-views/sys-columns-transact-sql">SQL Server 列元数据</a>
 * @since 2026/11/18 17:31
 */
final class SqlServerTableDdl {
	/**
	 * 表定义读取不保留会话状态。
	 */
	private SqlServerTableDdl() {
	}

	/**
	 * 读取当前库中表的列、行存储索引、主键、唯一约束、检查约束和外键，拼接供审阅的建表脚本。
	 *
	 * @param connection 当前数据库连接
	 * @param table      从该连接浏览到的表
	 * @param qualified  带模式名和引号的表名
	 * @param quote      驱动的标识符引号
	 * @return 包含列、行存储索引、主键、唯一约束、检查约束和外键的结构预览
	 * @throws SQLException 表不可见或元数据不完整
	 */
	static String readSqlServerTableDdl(Connection connection, JdbcTable table, String qualified, String quote)
		throws SQLException {
		List<String> clauses = new ArrayList<>();
		try (var statement = connection.prepareStatement("""
			SELECT c.name, t.name AS type_name, SCHEMA_NAME(t.schema_id) AS type_schema,
			       t.is_user_defined, c.max_length, c.precision, c.scale, c.collation_name,
			       c.is_nullable, c.is_identity, c.is_computed,
			       CONVERT(varchar(40), i.seed_value) AS seed, CONVERT(varchar(40), i.increment_value) AS increment_value,
			       i.is_not_for_replication, cc.definition AS computed_definition, cc.is_persisted,
			       dc.name AS default_name, dc.definition AS default_definition
			FROM sys.tables AS tbl
			JOIN sys.schemas AS s ON s.schema_id = tbl.schema_id
			JOIN sys.columns AS c ON c.object_id = tbl.object_id
			JOIN sys.types AS t ON t.user_type_id = c.user_type_id
			LEFT JOIN sys.identity_columns AS i ON i.object_id = c.object_id AND i.column_id = c.column_id
			LEFT JOIN sys.computed_columns AS cc ON cc.object_id = c.object_id AND cc.column_id = c.column_id
			LEFT JOIN sys.default_constraints AS dc ON dc.object_id = c.default_object_id
			WHERE s.name = ? AND tbl.name = ?
			ORDER BY c.column_id
			""")) {
			statement.setQueryTimeout(15);
			statement.setNString(1, table.schema());
			statement.setNString(2, table.name());
			try (var columns = statement.executeQuery()) {
				while (columns.next()) {
					clauses.add(readSqlServerColumnDdl(columns, quote));
				}
			}
		}
		if (clauses.isEmpty()) {
			throw new SQLException("SQL Server returned no visible columns for " + qualified);
		}
		String indexes = String.join("", readSqlServerRowstoreIndexes(connection, table, qualified, quote).values());
		String createTable = "CREATE TABLE " + qualified + " (\n    " + String.join(",\n    ", clauses) + "\n);";
		return createTable + indexes + readSqlServerCheckConstraints(connection, table, qualified, quote)
			+ readSqlServerForeignKeys(connection, table, qualified, quote);
	}

	/**
	 * 读取行存储索引。DDL 包含主键并保留创建顺序。比较时以原始索引名为键，省略表名和已比较的主键。
	 *
	 * @param connection 当前数据库连接
	 * @param table      从该连接浏览到的表
	 * @param qualified  生成 DDL 所用的完整表名，比较结构时为空
	 * @param quote      驱动的标识符引号
	 * @return 按读取顺序排列的索引或键约束定义
	 * @throws SQLException 系统目录读取失败或过滤表达式不可见
	 * @see <a href="https://learn.microsoft.com/en-us/sql/relational-databases/system-catalog-views/sys-index-columns-transact-sql">SQL Server 索引列目录</a>
	 */
	static Map<String, String> readSqlServerRowstoreIndexes(Connection connection, JdbcTable table, String qualified,
															String quote) throws SQLException {
		Map<String, String> definitions = new LinkedHashMap<>();
		try (var statement = connection.prepareStatement("""
			SELECT i.index_id, i.name, i.type, i.is_unique, i.is_primary_key, i.is_unique_constraint,
			       i.has_filter, i.filter_definition, c.name AS column_name, ic.is_descending_key,
			       ic.is_included_column, i.fill_factor, i.is_padded, i.ignore_dup_key,
			       i.allow_row_locks, i.allow_page_locks
			FROM sys.tables AS tbl
			JOIN sys.schemas AS s ON s.schema_id = tbl.schema_id
			JOIN sys.indexes AS i ON i.object_id = tbl.object_id
			JOIN sys.index_columns AS ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
			JOIN sys.columns AS c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
			WHERE s.name = ? AND tbl.name = ? AND i.type IN (1, 2) AND i.is_hypothetical = 0
			  AND (? = 1 OR i.is_primary_key = 0)
			  AND (ic.key_ordinal > 0 OR ic.is_included_column = 1)
			ORDER BY i.type, i.index_id, ic.key_ordinal, ic.index_column_id
			""")) {
			statement.setQueryTimeout(15);
			statement.setNString(1, table.schema());
			statement.setNString(2, table.name());
			statement.setInt(3, Objects.nonNull(qualified) ? 1 : 0);
			try (var rows = statement.executeQuery()) {
				boolean hasRow = rows.next();
				while (hasRow) {
					int indexId = rows.getInt("index_id");
					String name = rows.getString("name");
					String clustering = rows.getInt("type") == 1 ? "CLUSTERED" : "NONCLUSTERED";
					boolean primary = rows.getBoolean("is_primary_key");
					boolean uniqueConstraint = rows.getBoolean("is_unique_constraint");
					boolean unique = rows.getBoolean("is_unique");
					String filter = rows.getString("filter_definition");
					if (rows.getBoolean("has_filter") && Objects.isNull(filter)) {
						throw new SQLException("SQL Server index filter is unavailable for " + name);
					}
					String options = readSqlServerIndexOptions(rows);
					List<String> keys = new ArrayList<>();
					List<String> included = new ArrayList<>();
					do {
						String column = JdbcTableSql.quoteIdentifier(rows.getString("column_name"), quote);
						if (rows.getBoolean("is_included_column")) {
							included.add(column);
						} else {
							keys.add(column + (rows.getBoolean("is_descending_key") ? " DESC" : " ASC"));
						}
						hasRow = rows.next();
					} while (hasRow && rows.getInt("index_id") == indexId);

					var ddl = new StringBuilder();
					if (Objects.isNull(qualified)) {
						ddl.append(uniqueConstraint ? "UNIQUE CONSTRAINT " : unique ? "UNIQUE INDEX " : "INDEX ")
							.append(clustering);
					} else {
						String quotedName = JdbcTableSql.quoteIdentifier(name, quote);
						if (primary || uniqueConstraint) {
							ddl.append("\nALTER TABLE ").append(qualified).append(" ADD CONSTRAINT ").append(quotedName)
								.append(primary ? " PRIMARY KEY " : " UNIQUE ").append(clustering);
						} else {
							ddl.append("\nCREATE ").append(unique ? "UNIQUE " : "").append(clustering)
								.append(" INDEX ").append(quotedName).append(" ON ").append(qualified);
						}
					}
					ddl.append(" (").append(String.join(", ", keys)).append(')');
					if (!included.isEmpty()) {
						ddl.append(" INCLUDE (").append(String.join(", ", included)).append(')');
					}
					if (Objects.nonNull(filter)) {
						ddl.append(" WHERE ").append(filter);
					}
					ddl.append(options).append(';');
					definitions.put(name, ddl.toString());
				}
			}
		}
		return definitions;
	}

	/**
	 * 保留两个建索引入口共同支持的持久化行存储选项。
	 *
	 * @param index 当前索引的目录行，游标须仍在该索引内
	 * @return 索引定义中的 WITH 子句
	 * @throws SQLException 无法读取索引选项
	 * @see <a href="https://learn.microsoft.com/en-us/sql/t-sql/statements/alter-table-index-option-transact-sql">SQL Server 键约束索引选项</a>
	 */
	private static String readSqlServerIndexOptions(ResultSet index) throws SQLException {
		var options = new StringBuilder(" WITH (PAD_INDEX = ")
			.append(index.getBoolean("is_padded") ? "ON" : "OFF");
		int fillFactor = index.getInt("fill_factor");
		if (fillFactor > 0) {
			options.append(", FILLFACTOR = ").append(fillFactor);
		}
		if (index.getBoolean("ignore_dup_key")) {
			options.append(", IGNORE_DUP_KEY = ON");
		}
		return options.append(", ALLOW_ROW_LOCKS = ").append(index.getBoolean("allow_row_locks") ? "ON" : "OFF")
			.append(", ALLOW_PAGE_LOCKS = ").append(index.getBoolean("allow_page_locks") ? "ON" : "OFF")
			.append(')').toString();
	}

	/**
	 * 读取检查约束，并在建表后保留原有校验和启用状态。
	 *
	 * @param connection 当前数据库连接
	 * @param table      从该连接浏览到的表
	 * @param qualified  带模式名和引号的表名
	 * @param quote      驱动的标识符引号
	 * @return 追加到建表语句后的约束脚本
	 * @throws SQLException 无法读取检查表达式或执行元数据查询
	 * @see <a href="https://learn.microsoft.com/en-us/sql/relational-databases/system-catalog-views/sys-check-constraints-transact-sql">SQL Server 检查约束目录</a>
	 */
	private static String readSqlServerCheckConstraints(Connection connection, JdbcTable table, String qualified,
														String quote) throws SQLException {
		var statements = new StringBuilder();
		for (var check : JdbcTableConstraints.readSqlServerCheckConstraints(connection, table)) {
			String name = JdbcTableSql.quoteIdentifier(check.name(), quote);
			statements.append("\nALTER TABLE ").append(qualified)
				.append(check.disabled() || check.untrusted() ? " WITH NOCHECK" : " WITH CHECK")
				.append(" ADD CONSTRAINT ").append(name).append(" CHECK");
			if (check.notForReplication()) {
				statements.append(" NOT FOR REPLICATION");
			}
			statements.append(" (").append(check.expression()).append(");");
			// WITH NOCHECK 只跳过存量校验，禁用约束还须单独执行 NOCHECK CONSTRAINT。
			if (check.disabled()) {
				statements.append("\nALTER TABLE ").append(qualified).append(" NOCHECK CONSTRAINT ").append(name).append(';');
			}
		}
		return statements.toString();
	}

	/**
	 * 按外键和原始列序读取引用关系，并保留级联规则及约束状态。
	 *
	 * @param connection 当前数据库连接
	 * @param table      从该连接浏览到的表
	 * @param qualified  带模式名和引号的表名
	 * @param quote      驱动的标识符引号
	 * @return 追加到建表语句后的外键定义
	 * @throws SQLException 系统目录读取失败
	 * @see <a href="https://learn.microsoft.com/en-us/sql/relational-databases/system-catalog-views/sys-foreign-keys-transact-sql">SQL Server 外键目录</a>
	 */
	private static String readSqlServerForeignKeys(Connection connection, JdbcTable table, String qualified,
												   String quote) throws SQLException {
		var ddl = new StringBuilder();
		try (var statement = connection.prepareStatement("""
			SELECT fk.object_id, fk.name, rs.name AS reference_schema, rt.name AS reference_table,
			       pc.name AS parent_column, rc.name AS reference_column,
			       fk.delete_referential_action_desc, fk.update_referential_action_desc,
			       fk.is_disabled, fk.is_not_trusted, fk.is_not_for_replication
			FROM sys.foreign_keys AS fk
			JOIN sys.tables AS pt ON pt.object_id = fk.parent_object_id
			JOIN sys.schemas AS ps ON ps.schema_id = pt.schema_id
			JOIN sys.tables AS rt ON rt.object_id = fk.referenced_object_id
			JOIN sys.schemas AS rs ON rs.schema_id = rt.schema_id
			JOIN sys.foreign_key_columns AS fc ON fc.constraint_object_id = fk.object_id
			JOIN sys.columns AS pc ON pc.object_id = pt.object_id AND pc.column_id = fc.parent_column_id
			JOIN sys.columns AS rc ON rc.object_id = rt.object_id AND rc.column_id = fc.referenced_column_id
			WHERE ps.name = ? AND pt.name = ?
			ORDER BY fk.object_id, fc.constraint_column_id
			""")) {
			statement.setQueryTimeout(15);
			statement.setNString(1, table.schema());
			statement.setNString(2, table.name());
			try (var rows = statement.executeQuery()) {
				boolean hasRow = rows.next();
				while (hasRow) {
					int keyId = rows.getInt("object_id");
					String name = JdbcTableSql.quoteIdentifier(rows.getString("name"), quote);
					String reference = JdbcTableSql.quoteIdentifier(rows.getString("reference_schema"), quote) + "."
						+ JdbcTableSql.quoteIdentifier(rows.getString("reference_table"), quote);
					String onDelete = rows.getString("delete_referential_action_desc").replace('_', ' ');
					String onUpdate = rows.getString("update_referential_action_desc").replace('_', ' ');
					boolean disabled = rows.getBoolean("is_disabled");
					boolean skipValidation = disabled || rows.getBoolean("is_not_trusted");
					boolean notForReplication = rows.getBoolean("is_not_for_replication");

					List<String> parentColumns = new ArrayList<>();
					List<String> referenceColumns = new ArrayList<>();
					do {
						parentColumns.add(JdbcTableSql.quoteIdentifier(rows.getString("parent_column"), quote));
						referenceColumns.add(JdbcTableSql.quoteIdentifier(rows.getString("reference_column"), quote));
						hasRow = rows.next();
					} while (hasRow && rows.getInt("object_id") == keyId);

					ddl.append("\nALTER TABLE ").append(qualified).append(skipValidation ? " WITH NOCHECK" : " WITH CHECK")
						.append(" ADD CONSTRAINT ").append(name).append(" FOREIGN KEY (")
						.append(String.join(", ", parentColumns)).append(") REFERENCES ").append(reference).append(" (")
						.append(String.join(", ", referenceColumns)).append(") ON DELETE ").append(onDelete)
						.append(" ON UPDATE ").append(onUpdate);
					if (notForReplication) {
						ddl.append(" NOT FOR REPLICATION");
					}
					ddl.append(';');
					if (disabled) {
						ddl.append("\nALTER TABLE ").append(qualified).append(" NOCHECK CONSTRAINT ").append(name).append(';');
					}
				}
			}
		}
		return ddl.toString();
	}

	/**
	 * 格式化一个目录列，计算列使用服务器表达式，普通列保留类型和默认值。
	 *
	 * @param column 已定位到当前列的结果集
	 * @param quote  驱动的标识符引号
	 * @return 一条列定义
	 * @throws SQLException 所需的列元数据不可见
	 */
	private static String readSqlServerColumnDdl(ResultSet column, String quote) throws SQLException {
		String name = JdbcTableSql.quoteIdentifier(column.getString("name"), quote);
		if (column.getBoolean("is_computed")) {
			String expression = column.getString("computed_definition");
			if (Objects.isNull(expression)) {
				throw new SQLException("SQL Server computed definition is unavailable for " + name);
			}
			return name + " AS " + expression + (column.getBoolean("is_persisted")
				? " PERSISTED" + (column.getBoolean("is_nullable") ? "" : " NOT NULL") : "");
		}

		var definition = new StringBuilder(name).append(' ').append(readSqlServerColumnType(column, quote));
		String collation = column.getString("collation_name");
		if (Objects.nonNull(collation)) {
			definition.append(" COLLATE ").append(collation);
		}
		if (column.getBoolean("is_identity")) {
			definition.append(" IDENTITY(").append(column.getString("seed")).append(", ")
				.append(column.getString("increment_value")).append(')');
			if (column.getBoolean("is_not_for_replication")) {
				definition.append(" NOT FOR REPLICATION");
			}
		}
		definition.append(column.getBoolean("is_nullable") ? " NULL" : " NOT NULL");
		String defaultExpression = column.getString("default_definition");
		if (Objects.nonNull(defaultExpression)) {
			definition.append(" CONSTRAINT ").append(JdbcTableSql.quoteIdentifier(column.getString("default_name"), quote))
				.append(" DEFAULT ").append(defaultExpression);
		}
		return definition.toString();
	}

	/**
	 * 格式化内置类型的长度与精度，用户定义类型保留模式限定。
	 *
	 * @param column 已定位到当前列的结果集
	 * @param quote  驱动的标识符引号
	 * @return 适合 CREATE TABLE 的列类型
	 * @throws SQLException 类型元数据读取失败
	 */
	private static String readSqlServerColumnType(ResultSet column, String quote) throws SQLException {
		String type = column.getString("type_name");
		if (column.getBoolean("is_user_defined")) {
			return JdbcTableSql.quoteIdentifier(column.getString("type_schema"), quote) + "."
				+ JdbcTableSql.quoteIdentifier(type, quote);
		}
		int bytes = column.getInt("max_length");
		int scale = column.getInt("scale");
		return switch (type) {
			case "char", "varchar", "binary", "varbinary" -> type + "(" + (bytes < 0 ? "max" : bytes) + ")";
			case "nchar", "nvarchar" -> type + "(" + (bytes < 0 ? "max" : bytes / 2) + ")";
			case "decimal", "numeric" -> type + "(" + column.getInt("precision") + ", " + scale + ")";
			case "datetime2", "datetimeoffset", "time" -> type + "(" + scale + ")";
			case "float" -> type + "(" + column.getInt("precision") + ")";
			default -> type;
		};
	}
}
