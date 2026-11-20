package io.github.jinlongliao.mybatis.workbench.database;

import static io.github.jinlongliao.mybatis.workbench.database.DatabaseMessages.databaseMessage;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Pattern;

import io.github.jinlongliao.mybatis.workbench.model.DbType;

/**
 * 读取普通索引、外键和受支持方言的检查约束定义，供表结构比较。
 *
 * @author liaojinlong
 * @see DatabaseMetaData
 * @since 2026/11/18 17:14
 */
final class JdbcTableConstraints {
	/**
	 * 先识别注释和带引号的内容，避免将其中的 CHECK 或括号识别为语法。
	 */
	private static final Pattern SQLITE_DDL_TOKEN = Pattern.compile(
		"(?s)--[^\\r\\n]*|/\\*.*?\\*/|'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"|"
			+ "\\x60(?:\\x60\\x60|[^\\x60])*\\x60|\\[[^\\]]*\\]|[\\p{L}\\p{N}_$]+|\\S");

	/**
	 * PostgreSQL 反解析出的索引头部，匹配带引号及可能包含空格的对象名。
	 */
	private static final Pattern POSTGRESQL_INDEX_HEADER = Pattern.compile(
		"\\ACREATE (?:UNIQUE )?INDEX (?:\"(?:\"\"|[^\"])*\"|[^\"\\s]+) ON (?:ONLY )?"
			+ "(?:\"(?:\"\"|[^\"])*\"|[^\"\\s.]+)(?:\\.(?:\"(?:\"\"|[^\"])*\"|[^\"\\s.]+))? (?=USING )");

	/**
	 * 元数据读取不需要实例。
	 */
	private JdbcTableConstraints() {
	}

	/**
	 * 从 H2 2.x 信息模式读取指定表的检查约束及规范化表达式。
	 *
	 * @param connection 当前 H2 连接
	 * @param table      JDBC 返回的表与模式信息
	 * @return 按约束名排序的表达式
	 * @throws SQLException 无法读取 H2 信息模式
	 * @see <a href="https://www.h2database.com/html/systemtables.html#information_schema_check_constraints">H2 CHECK_CONSTRAINTS</a>
	 */
	static Map<String, List<String>> readH2CheckConstraints(Connection connection, JdbcTable table) throws SQLException {
		Map<String, List<String>> result = new LinkedHashMap<>();
		try (var query = connection.prepareStatement("""
			SELECT t.CONSTRAINT_NAME, c.CHECK_CLAUSE
			FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS t
			JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS c
			  ON c.CONSTRAINT_CATALOG = t.CONSTRAINT_CATALOG AND c.CONSTRAINT_SCHEMA = t.CONSTRAINT_SCHEMA
			  AND c.CONSTRAINT_NAME = t.CONSTRAINT_NAME
			WHERE t.TABLE_SCHEMA = ? AND t.TABLE_NAME = ? AND t.CONSTRAINT_TYPE = 'CHECK'
			ORDER BY t.CONSTRAINT_NAME
			""")) {
			query.setQueryTimeout(15);
			query.setString(1, table.schema());
			query.setString(2, table.name());
			try (var rows = query.executeQuery()) {
				while (rows.next()) {
					result.put(rows.getString(1), List.of(rows.getString(2)));
				}
			}
		}
		return result;
	}

	/**
	 * 读取 PostgreSQL 表的检查定义及验证状态，使用服务器还原的规范化表达式。
	 *
	 * @param connection 当前 PostgreSQL 连接
	 * @param table      JDBC 返回的表和模式
	 * @return 按约束名组织的定义及验证状态
	 * @throws SQLException 系统目录不可读取
	 * @see <a href="https://www.postgresql.org/docs/current/catalog-pg-constraint.html">PostgreSQL pg_constraint</a>
	 */
	static Map<String, List<String>> readPostgreSqlCheckConstraints(Connection connection, JdbcTable table)
		throws SQLException {
		Map<String, List<String>> result = new LinkedHashMap<>();
		try (var query = connection.prepareStatement("""
			SELECT k.conname, pg_catalog.pg_get_constraintdef(k.oid, false), k.convalidated
			FROM pg_catalog.pg_constraint k
			JOIN pg_catalog.pg_class c ON c.oid = k.conrelid
			JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
			WHERE n.nspname = ? AND c.relname = ? AND k.contype = 'c'
			ORDER BY k.conname
			""")) {
			query.setQueryTimeout(15);
			query.setString(1, table.schema());
			query.setString(2, table.name());
			try (var rows = query.executeQuery()) {
				while (rows.next()) {
					result.put(rows.getString(1), List.of(rows.getString(2), "VALIDATED=" + rows.getBoolean(3)));
				}
			}
		}
		return result;
	}

	/**
	 * 读取 SQL Server 的检查表达式及约束状态，供 DDL 预览和结构比较共用。
	 *
	 * @param connection 当前 SQL Server 连接
	 * @param table      JDBC 返回的表和模式
	 * @return 按名称排序的约束快照
	 * @throws SQLException 定义不可见或目录读取失败
	 * @see <a href="https://learn.microsoft.com/en-us/sql/relational-databases/system-catalog-views/sys-check-constraints-transact-sql">SQL Server 检查约束目录</a>
	 */
	static List<SqlServerCheckConstraint> readSqlServerCheckConstraints(Connection connection, JdbcTable table)
		throws SQLException {
		var constraints = new ArrayList<SqlServerCheckConstraint>();
		try (var statement = connection.prepareStatement("""
			SELECT checks.name, checks.definition, checks.is_disabled, checks.is_not_trusted, checks.is_not_for_replication
			FROM sys.check_constraints AS checks
			JOIN sys.tables AS tbl ON tbl.object_id = checks.parent_object_id
			JOIN sys.schemas AS s ON s.schema_id = tbl.schema_id
			WHERE s.name = ? AND tbl.name = ?
			ORDER BY checks.name
			""")) {
			statement.setQueryTimeout(15);
			statement.setNString(1, table.schema());
			statement.setNString(2, table.name());
			try (var rows = statement.executeQuery()) {
				while (rows.next()) {
					String name = rows.getString("name");
					String expression = rows.getString("definition");
					if (Objects.isNull(expression)) {
						throw new SQLException("SQL Server check definition is unavailable for " + name);
					}
					constraints.add(new SqlServerCheckConstraint(name, expression, rows.getBoolean("is_disabled"),
						rows.getBoolean("is_not_trusted"), rows.getBoolean("is_not_for_replication")));
				}
			}
		}
		return List.copyOf(constraints);
	}

	/**
	 * SQL Server 检查约束的目录快照，仅用于比较和拼接 DDL。
	 *
	 * @param name              原始约束名
	 * @param expression        数据库记录的表达式
	 * @param disabled          约束是否禁用
	 * @param untrusted         数据库是否未验证存量数据
	 * @param notForReplication 是否跳过复制代理写入的校验
	 * @author liaojinlong
	 * @since 2026/11/18 19:04
	 */
	record SqlServerCheckConstraint(String name, String expression, boolean disabled, boolean untrusted,
	                                boolean notForReplication) {
	}

	/**
	 * 读取 MySQL 8.0.16 及以上版本的检查表达式和执行状态。
	 * 更早版本没有可用的检查约束目录，比较保持原有范围。
	 *
	 * @param connection 当前 MySQL 连接
	 * @param table      JDBC 返回的表与数据库
	 * @return 按元数据名称排序的检查定义
	 * @throws SQLException 支持该目录的版本无法读取检查定义
	 * @see <a href="https://dev.mysql.com/doc/refman/8.0/en/information-schema-table-constraints-table.html">MySQL TABLE_CONSTRAINTS</a>
	 */
	static Map<String, List<String>> readMySqlCheckConstraints(Connection connection, JdbcTable table) throws SQLException {
		var metadata = connection.getMetaData();
		if (metadata.getDatabaseMajorVersion() < 8
			|| metadata.getDatabaseProductVersion().matches("8\\.0\\.(?:[0-9]|1[0-5])(?:\\D.*)?")) {
			return Map.of();
		}
		Map<String, List<String>> definitions = new LinkedHashMap<>();
		try (var statement = connection.prepareStatement("""
			SELECT t.CONSTRAINT_NAME, c.CHECK_CLAUSE, t.ENFORCED
			FROM information_schema.TABLE_CONSTRAINTS t
			JOIN information_schema.CHECK_CONSTRAINTS c
			  ON c.CONSTRAINT_SCHEMA = t.CONSTRAINT_SCHEMA AND c.CONSTRAINT_NAME = t.CONSTRAINT_NAME
			WHERE t.TABLE_SCHEMA = ? AND t.TABLE_NAME = ? AND t.CONSTRAINT_TYPE = 'CHECK'
			ORDER BY t.CONSTRAINT_NAME
			""")) {
			statement.setQueryTimeout(15);
			statement.setString(1, Objects.requireNonNullElse(table.catalog(), table.schema()));
			statement.setString(2, table.name());
			try (var rows = statement.executeQuery()) {
				while (rows.next()) {
					definitions.put(rows.getString(1), List.of(rows.getString(2), "ENFORCED=" + rows.getString(3)));
				}
			}
		}
		return definitions;
	}

	/**
	 * 为 H2 目标模式生成缺失显式索引的草案，跳过同名索引及引用不可用列的索引。
	 *
	 * @param sourceConnection 参考连接
	 * @param sourceTable 参考表
	 * @param targetConnection 目标连接
	 * @param targetTable 目标表
	 * @param targetColumns 目标表已有或本草案会先添加的列名
	 * @param quote 目标驱动使用的标识符引号
	 * @return 可审核的 CREATE INDEX 草案，保留列顺序及空值排序
	 * @throws SQLException 目录读取或标识符引用失败
	 */
	static List<String> draftH2Indexes(Connection sourceConnection, JdbcTable sourceTable, Connection targetConnection,
									JdbcTable targetTable, Set<String> targetColumns, String quote) throws SQLException {
		Set<String> reservedNames = new HashSet<>();
		try (var query = targetConnection.prepareStatement("SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE INDEX_SCHEMA = ?")) {
			query.setQueryTimeout(15);
			query.setString(1, targetTable.schema());
			try (var rows = query.executeQuery()) {
				while (rows.next()) {
					reservedNames.add(rows.getString(1));
				}
			}
		}
		Map<String, List<String>> definitions = new LinkedHashMap<>();
		Map<String, String> indexKinds = new HashMap<>();
		try (var query = sourceConnection.prepareStatement("""
			SELECT i.INDEX_NAME, c.COLUMN_NAME, c.ORDERING_SPECIFICATION, c.NULL_ORDERING, i.INDEX_TYPE_NAME, i.NULLS_DISTINCT
			FROM INFORMATION_SCHEMA.INDEXES i
			JOIN INFORMATION_SCHEMA.INDEX_COLUMNS c
			  ON c.INDEX_CATALOG = i.INDEX_CATALOG AND c.INDEX_SCHEMA = i.INDEX_SCHEMA AND c.INDEX_NAME = i.INDEX_NAME
			WHERE i.TABLE_SCHEMA = ? AND i.TABLE_NAME = ? AND i.INDEX_TYPE_NAME IN ('INDEX', 'UNIQUE INDEX') AND NOT i.IS_GENERATED
			ORDER BY i.INDEX_NAME, c.ORDINAL_POSITION
			""")) {
			query.setQueryTimeout(15);
			query.setString(1, sourceTable.schema());
			query.setString(2, sourceTable.name());
			try (var rows = query.executeQuery()) {
				while (rows.next()) {
					String name = rows.getString(1);
					String column = rows.getString(2);
					String direction = rows.getString(3);
					String nullOrder = rows.getString(4);
					String kind = "INDEX".equals(rows.getString(5)) ? "INDEX" : switch (rows.getString(6)) {
						case "YES" -> "UNIQUE NULLS DISTINCT INDEX";
						case "NO" -> "UNIQUE NULLS NOT DISTINCT INDEX";
						case "ALL" -> "UNIQUE NULLS ALL DISTINCT INDEX";
						case null, default -> null;
					};
					if (Objects.isNull(kind) || !targetColumns.contains(column) || !("ASC".equals(direction) || "DESC".equals(direction))
						|| !("FIRST".equals(nullOrder) || "LAST".equals(nullOrder))) {
						reservedNames.add(name);
					}
					if (!reservedNames.contains(name)) {
						indexKinds.put(name, kind);
						definitions.computeIfAbsent(name, ignored -> new ArrayList<>())
							.add(JdbcTableSql.quoteIdentifier(column, quote) + " " + direction + " NULLS " + nullOrder);
					}
				}
			}
		}
		List<String> drafts = new ArrayList<>();
		String schema = JdbcTableSql.quoteIdentifier(targetTable.schema(), quote);
		String table = JdbcTableSql.qualifiedTableName(DbType.H2, targetTable, quote);
		for (var entry : definitions.entrySet()) {
			if (!reservedNames.contains(entry.getKey())) {
				drafts.add("CREATE " + indexKinds.get(entry.getKey()) + " " + schema + "." + JdbcTableSql.quoteIdentifier(entry.getKey(), quote)
					+ " ON " + table + " (" + String.join(", ", entry.getValue()) + ");");
			}
		}
		return drafts;
	}

	/**
	 * 从 MariaDB 检查目录读取指定表的表达式。旧服务器缺少该目录时，
	 * 仍允许比较 JDBC 提供的其他对象。
	 *
	 * @param connection 当前 MariaDB 连接
	 * @param table      JDBC 返回的表与数据库
	 * @return 按检查约束名称排序的表达式
	 * @throws SQLException 当前服务器存在该目录，但查询或读取失败
	 * @see <a href="https://mariadb.com/docs/server/reference/system-tables/information-schema/information-schema-tables/information-schema-check_constraints-table">MariaDB CHECK_CONSTRAINTS</a>
	 */
	static Map<String, List<String>> readMariaDbCheckConstraints(Connection connection, JdbcTable table)
		throws SQLException {
		Map<String, List<String>> definitions = new LinkedHashMap<>();
		try (var statement = connection.prepareStatement("""
			SELECT CONSTRAINT_NAME, CHECK_CLAUSE
			FROM information_schema.CHECK_CONSTRAINTS
			WHERE CONSTRAINT_SCHEMA = ? AND TABLE_NAME = ?
			ORDER BY CONSTRAINT_NAME
			""")) {
			statement.setQueryTimeout(15);
			statement.setString(1, Objects.requireNonNullElse(table.catalog(), table.schema()));
			statement.setString(2, table.name());
			try (var rows = statement.executeQuery()) {
				while (rows.next()) {
					definitions.put(rows.getString(1), List.of(rows.getString(2)));
				}
			}
		} catch (SQLException failure) {
			// MariaDB 用 ER_UNKNOWN_TABLE 表示 information_schema 没有该目录表。
			if (failure.getErrorCode() == 1109 && "42S02".equals(failure.getSQLState())) {
				return Map.of();
			}
			throw failure;
		}
		return definitions;
	}

	/**
	 * 读取 Oracle 检查约束。同一条件的系统命名约束按状态排序，保留重复定义的数量。
	 *
	 * @param connection 当前 Oracle 连接，应连接到表所在的容器
	 * @param table      JDBC 返回的表和所有者
	 * @return 按用户名称或系统命名约束的检查条件组织的定义
	 * @throws SQLException 无法读取字典或检查条件
	 * @see <a href="https://docs.oracle.com/en/database/oracle/oracle-database/19/refrn/ALL_CONSTRAINTS.html">Oracle ALL_CONSTRAINTS</a>
	 */
	static Map<String, List<String>> readOracleCheckConstraints(Connection connection, JdbcTable table) throws SQLException {
		Map<String, List<String>> definitions = new TreeMap<>();
		try (var statement = connection.prepareStatement("""
			SELECT CONSTRAINT_NAME, GENERATED, STATUS, VALIDATED, DEFERRABLE, DEFERRED, RELY, SEARCH_CONDITION
			FROM ALL_CONSTRAINTS
			WHERE OWNER = ? AND TABLE_NAME = ? AND CONSTRAINT_TYPE = 'C'
			""")) {
			statement.setQueryTimeout(15);
			statement.setString(1, table.schema());
			statement.setString(2, table.name());
			try (var rows = statement.executeQuery()) {
				while (rows.next()) {
					String name = rows.getString(1);
					boolean generated = "GENERATED NAME".equals(rows.getString(2));
					String status = rows.getString(3);
					String validated = rows.getString(4);
					String deferrable = rows.getString(5);
					String deferred = rows.getString(6);
					String rely = rows.getString(7);
					// 完整条件是 LONG，放在最后读取，避免 JDBC 流式读取影响其他列。
					String condition = rows.getString(8);
					if (Objects.isNull(condition)) {
						throw new SQLException("Oracle ALL_CONSTRAINTS.SEARCH_CONDITION is unavailable for " + name);
					}
					String state = String.join(" ", status, validated, deferrable, deferred,
						"NOT VALIDATED".equals(validated) ? Objects.requireNonNullElse(rely, "NORELY") : "").stripTrailing();
					if (generated) {
						definitions.computeIfAbsent("CHECK (" + condition.strip() + ")", ignored -> new ArrayList<>()).add(state);
					} else {
						definitions.put("\"" + name.replace("\"", "\"\"") + "\"", List.of(condition.strip(), state));
					}
				}
			}
		}
		// 用户命名的定义保留条件在前、状态在后的显示顺序。
		definitions.replaceAll((name, values) -> name.startsWith("CHECK ") ? values.stream().sorted().toList() : values);
		return definitions;
	}

	/**
	 * 从所选数据库读取附属于表或视图的触发器。保留原始 SQL，以免误归一化字符串字面量。
	 *
	 * @param connection 当前 SQLite 连接
	 * @param table      触发器所属的表或视图
	 * @return 按名称排序的触发器定义
	 * @throws SQLException 无法读取所选数据库的目录
	 * @see <a href="https://www.sqlite.org/schematab.html">SQLite Schema Table</a>
	 */
	static Map<String, List<String>> readSqliteTriggers(Connection connection, JdbcTable table) throws SQLException {
		String schema = JdbcTableSql.quoteIdentifier(JdbcTableSql.sqliteSchema(table),
			connection.getMetaData().getIdentifierQuoteString());
		Map<String, List<String>> definitions = new TreeMap<>();
		try (var statement = connection.prepareStatement(
			"SELECT name, sql FROM " + schema + ".sqlite_schema WHERE type = 'trigger' AND tbl_name = ?")) {
			statement.setQueryTimeout(15);
			statement.setString(1, table.name());
			try (var rows = statement.executeQuery()) {
				while (rows.next()) {
					definitions.put(rows.getString(1), List.of(rows.getString(2)));
				}
			}
		}
		return definitions;
	}

	/**
	 * 从 SQLite 建表文本读取列级和表级检查条件。仅比较普通表的声明，
	 * 同名重复约束保留全部条件，条件内容保留原始 SQL。
	 *
	 * @param connection 当前 SQLite 连接
	 * @param table      JDBC 返回的表
	 * @return 按显式名称或未命名条件组织的表达式
	 * @throws SQLException 读取表定义失败
	 * @see <a href="https://www.sqlite.org/schematab.html">SQLite Schema Table</a>
	 * @see <a href="https://www.sqlite.org/lang_createtable.html#check_constraints">SQLite CHECK constraints</a>
	 */
	static Map<String, List<String>> readSqliteCheckConstraints(Connection connection, JdbcTable table) throws SQLException {
		String ddl = JdbcTableDdl.readSqliteDdl(connection, table, connection.getMetaData().getIdentifierQuoteString(),
			false, key -> databaseMessage(Locale.getDefault(), key));
		var tokens = SQLITE_DDL_TOKEN.matcher(ddl).results()
			.filter(token -> !token.group().startsWith("--") && !token.group().startsWith("/*"))
			.toList();
		if (tokens.size() < 2 || !"CREATE".equalsIgnoreCase(tokens.get(0).group())
			|| !"TABLE".equalsIgnoreCase(tokens.get(1).group())) {
			return Map.of();
		}
		Map<String, List<String>> definitions = new TreeMap<>();
		int depth = 0;
		int checkStart = -1;
		String constraintName = null;
		for (int index = 2; index < tokens.size(); index++) {
			var token = tokens.get(index);
			String text = token.group();
			if (depth == 1 && "CHECK".equalsIgnoreCase(text) && index + 1 < tokens.size()
				&& "(".equals(tokens.get(index + 1).group())) {
				checkStart = tokens.get(index + 1).end();
				constraintName = index >= 4 && "CONSTRAINT".equalsIgnoreCase(tokens.get(index - 2).group())
					? tokens.get(index - 1).group() : null;
			} else if ("(".equals(text)) {
				depth++;
			} else if (")".equals(text)) {
				depth--;
				if (depth == 1 && checkStart >= 0) {
					String expression = ddl.substring(checkStart, token.start()).strip();
					String label = Objects.requireNonNullElse(constraintName, "CHECK (" + expression + ")");
					definitions.computeIfAbsent(label, ignored -> new ArrayList<>()).add(expression);
					checkStart = -1;
				}
			}
		}
		definitions.replaceAll((name, expressions) -> expressions.stream().sorted().toList());
		return definitions;
	}

	/**
	 * 以 H2 目录区分主键、用户索引与自动索引。自动名称在不同数据库中可能变化，按定义比较。
	 *
	 * @param connection 当前 H2 连接
	 * @param table      JDBC 返回的表与模式
	 * @return 按显式索引名或自动索引定义组织的索引
	 * @throws SQLException 无法读取 H2 索引目录
	 * @see <a href="https://www.h2database.com/html/systemtables.html#information_schema_indexes">H2 INDEXES</a>
	 */
	static Map<String, List<String>> readH2Indexes(Connection connection, JdbcTable table) throws SQLException {
		var indexes = new LinkedHashMap<String, TreeMap<Integer, String>>();
		var generated = new HashSet<String>();
		try (var statement = connection.prepareStatement("""
			SELECT i.INDEX_NAME, i.IS_GENERATED, c.ORDINAL_POSITION, c.COLUMN_NAME,
			       c.ORDERING_SPECIFICATION, c.NULL_ORDERING, c.IS_UNIQUE, i.NULLS_DISTINCT
			FROM INFORMATION_SCHEMA.INDEXES i
			JOIN INFORMATION_SCHEMA.INDEX_COLUMNS c
			  ON c.INDEX_CATALOG = i.INDEX_CATALOG AND c.INDEX_SCHEMA = i.INDEX_SCHEMA AND c.INDEX_NAME = i.INDEX_NAME
			WHERE i.TABLE_SCHEMA = ? AND i.TABLE_NAME = ? AND i.INDEX_TYPE_NAME <> 'PRIMARY KEY'
			ORDER BY i.INDEX_NAME, c.ORDINAL_POSITION
			""")) {
			statement.setQueryTimeout(15);
			statement.setString(1, table.schema());
			statement.setString(2, table.name());
			try (var rows = statement.executeQuery()) {
				while (rows.next()) {
					String name = rows.getString(1);
					if (rows.getBoolean(2)) {
						generated.add(name);
					}
					String definition = rows.getString(4) + " " + rows.getString(5)
						+ " NULLS " + rows.getString(6) + " [unique=" + rows.getBoolean(7) + "]";
					if (rows.getBoolean(7)) {
						definition += " [nulls distinct=" + rows.getString(8) + "]";
					}
					indexes.computeIfAbsent(name, ignored -> new TreeMap<>()).put(rows.getInt(3), definition);
				}
			}
		}
		Map<String, List<String>> definitions = new LinkedHashMap<>();
		orderedDefinitions(indexes).forEach((name, columns) -> {
			if (generated.contains(name)) {
				definitions.computeIfAbsent("GENERATED " + columns, ignored -> new ArrayList<>()).add(columns.toString());
			} else {
				definitions.put(name, columns);
			}
		});
		return definitions;
	}

	/**
	 * 读取 MySQL 和 MariaDB 索引的列顺序、前缀、方向和类型。
	 * MySQL 8 支持可见性和函数键，MariaDB 10.6 起支持优化器忽略状态。
	 *
	 * @param connection 当前 MySQL 或 MariaDB 连接
	 * @param table      JDBC 返回的表和数据库
	 * @param type       MySQL 或 MariaDB 方言
	 * @return 按索引名组织的索引定义，主键由列及顺序比较处理
	 * @throws SQLException 无法读取索引目录
	 * @see <a href="https://dev.mysql.com/doc/refman/8.4/en/information-schema-statistics-table.html">MySQL STATISTICS</a>
	 * @see <a href="https://mariadb.com/docs/server/reference/system-tables/information-schema/information-schema-tables/information-schema-statistics-table">MariaDB STATISTICS</a>
	 */
	static Map<String, List<String>> readMySqlFamilyIndexes(Connection connection, JdbcTable table, DbType type)
		throws SQLException {
		var metadata = connection.getMetaData();
		int major = metadata.getDatabaseMajorVersion();
		boolean visibilitySupported = type == DbType.MySQL && major >= 8;
		String visibility = mysqlIndexVisibility(metadata, type);
		boolean expressionSupported = visibilitySupported
			&& !metadata.getDatabaseProductVersion().matches("8\\.0\\.(?:[0-9]|1[0-2])(?:\\D.*)?");
		var indexes = new LinkedHashMap<String, TreeMap<Integer, String>>();
		try (var statement = connection.prepareStatement("""
			SELECT INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME, SUB_PART, COLLATION, NON_UNIQUE, INDEX_TYPE,
			       %s AS VISIBLE_STATE, %s AS FUNCTIONAL_KEY
			FROM information_schema.STATISTICS
			WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND INDEX_NAME <> 'PRIMARY'
			ORDER BY INDEX_NAME, SEQ_IN_INDEX
			""".formatted(visibility, expressionSupported ? "EXPRESSION" : "NULL"))) {
			statement.setQueryTimeout(15);
			statement.setString(1, Objects.requireNonNullElse(table.catalog(), table.schema()));
			statement.setString(2, table.name());
			try (var rows = statement.executeQuery()) {
				while (rows.next()) {
					String column = rows.getString(3);
					String key = Objects.nonNull(column) ? column : "(" + rows.getString(9) + ")";
					String prefix = rows.getString(4);
					if (Objects.nonNull(prefix)) {
						key += "(" + prefix + ")";
					}
					String definition = key + " [direction=" + Objects.toString(rows.getString(5), "")
						+ ", unique=" + !rows.getBoolean(6) + ", method=" + rows.getString(7)
						+ ", visible=" + rows.getString(8) + "]";
					indexes.computeIfAbsent(rows.getString(1), ignored -> new TreeMap<>()).put(rows.getInt(2), definition);
				}
			}
		}
		return orderedDefinitions(indexes);
	}

	/**
	 * 解析索引是否参与优化的目录表达式，兼容没有该字段的旧版本。
	 * @param metadata 源连接的版本信息
	 * @param type 源方言
	 * @return 返回 YES 或 NO 的 SQL 表达式
	 * @throws SQLException 无法读取数据库版本
	 */
	private static String mysqlIndexVisibility(DatabaseMetaData metadata, DbType type) throws SQLException {
		int major = metadata.getDatabaseMajorVersion();
		if (type == DbType.MySQL && major >= 8) {
			return "IS_VISIBLE";
		}
		if (type == DbType.MariaDB && (major > 10 || (major == 10 && metadata.getDatabaseMinorVersion() >= 6))) {
			return "CASE WHEN IGNORED = 'YES' THEN 'NO' ELSE 'YES' END";
		}
		return "'YES'";
	}

	/**
	 * 生成 MySQL 系列中缺失的可见列索引，保留顺序、前缀和唯一性。
	 * @param sourceConnection 源连接
	 * @param sourceTable 参考表
	 * @param targetTable 目标表
	 * @param type 两边的方言
	 * @param targetMetadata 目标连接版本信息
	 * @param targetColumns 目标已有或本草案将先添加的列名
	 * @param reservedNames 目标表已有索引名
	 * @param quote 目标连接的标识符引号
	 * @return 索引迁移草案
	 * @throws SQLException 无法读取元数据或引用标识符
	 */
	static List<String> draftMySqlFamilyIndexes(Connection sourceConnection, JdbcTable sourceTable, JdbcTable targetTable,
											DbType type, DatabaseMetaData targetMetadata, Set<String> targetColumns,
											Set<String> reservedNames, String quote) throws SQLException {
		Set<String> unavailable = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		unavailable.addAll(reservedNames);
		Map<String, List<String>> keys = new LinkedHashMap<>();
		Set<String> unique = new HashSet<>();
		int major = targetMetadata.getDatabaseMajorVersion();
		boolean supportsDescending = type == DbType.MySQL ? major >= 8
			: major > 10 || (major == 10 && targetMetadata.getDatabaseMinorVersion() >= 8);
		try (var statement = sourceConnection.prepareStatement("""
			SELECT INDEX_NAME, COLUMN_NAME, SUB_PART, COLLATION, NON_UNIQUE, %s AS VISIBLE_STATE
			FROM information_schema.STATISTICS
			WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND INDEX_NAME <> 'PRIMARY' AND INDEX_TYPE = 'BTREE'
			ORDER BY INDEX_NAME, SEQ_IN_INDEX
			""".formatted(mysqlIndexVisibility(sourceConnection.getMetaData(), type)))) {
			statement.setQueryTimeout(15);
			statement.setString(1, Objects.requireNonNullElse(sourceTable.catalog(), sourceTable.schema()));
			statement.setString(2, sourceTable.name());
			try (var rows = statement.executeQuery()) {
				while (rows.next()) {
					String name = rows.getString(1);
					String column = rows.getString(2);
					long prefix = rows.getLong(3);
					boolean hasPrefix = !rows.wasNull();
					String direction = rows.getString(4);
					if (!"YES".equals(rows.getString(6)) || Objects.isNull(column) || !targetColumns.contains(column)
						|| (hasPrefix && prefix <= 0) || !("A".equals(direction) || (supportsDescending && "D".equals(direction)))) {
						unavailable.add(name);
					}
					if (!unavailable.contains(name)) {
						if (!rows.getBoolean(5)) {
							unique.add(name);
						}
						keys.computeIfAbsent(name, ignored -> new ArrayList<>()).add(JdbcTableSql.quoteIdentifier(column, quote)
							+ (hasPrefix ? "(" + prefix + ")" : "") + ("D".equals(direction) ? " DESC" : " ASC"));
					}
				}
			}
		}
		List<String> drafts = new ArrayList<>();
		String table = JdbcTableSql.qualifiedTableName(type, targetTable, quote);
		for (var entry : keys.entrySet()) {
			String name = entry.getKey();
			if (!unavailable.contains(name)) {
				drafts.add((unique.contains(name) ? "CREATE UNIQUE INDEX " : "CREATE INDEX ")
					+ JdbcTableSql.quoteIdentifier(name, quote) + " USING BTREE ON " + table
					+ " (" + String.join(", ", entry.getValue()) + ");");
			}
		}
		return drafts;
	}

	/**
	 * 读取 PostgreSQL 非主键索引，比较服务器还原的键、表达式、谓词和存储参数。
	 * 移除建索引语句中的表名和索引名，跨模式比较时保留索引本身的定义。
	 *
	 * @param connection 当前 PostgreSQL 连接
	 * @param table      JDBC 返回的表与模式
	 * @return 按索引名排列的索引定义
	 * @throws SQLException 无法读取系统目录
	 * @see <a href="https://www.postgresql.org/docs/current/functions-info.html">PostgreSQL pg_get_indexdef</a>
	 */
	static Map<String, List<String>> readPostgreSqlIndexes(Connection connection, JdbcTable table) throws SQLException {
		Map<String, List<String>> indexes = new LinkedHashMap<>();
		try (var statement = connection.prepareStatement("""
			SELECT c.relname, i.indisunique, pg_catalog.pg_get_indexdef(i.indexrelid)
			FROM pg_catalog.pg_index i
			JOIN pg_catalog.pg_class t ON t.oid = i.indrelid
			JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
			JOIN pg_catalog.pg_class c ON c.oid = i.indexrelid
			WHERE n.nspname = ? AND t.relname = ? AND NOT i.indisprimary
			ORDER BY c.relname
			""")) {
			statement.setQueryTimeout(15);
			statement.setString(1, table.schema());
			statement.setString(2, table.name());
			try (var rows = statement.executeQuery()) {
				while (rows.next()) {
					String ddl = POSTGRESQL_INDEX_HEADER.matcher(rows.getString(3)).replaceFirst("");
					indexes.put(rows.getString(1), List.of("UNIQUE=" + rows.getBoolean(2), ddl));
				}
			}
		}
		return indexes;
	}

	/**
	 * 为 PostgreSQL 显式列索引生成迁移草案，复用数据库反解析后的排序、唯一性和 INCLUDE 定义。
	 *
	 * @param sourceConnection 参考连接
	 * @param sourceTable 参考表
	 * @param targetConnection 目标连接
	 * @param targetTable 目标表
	 * @param targetColumns 目标已有或本草案会先添加的列
	 * @param quote 目标驱动使用的标识符引号
	 * @return 可审核的索引草案，跳过表达式、部分索引、约束索引及依赖自定义对象的索引
	 * @throws SQLException 读取索引目录或引用标识符失败
	 * @see <a href="https://www.postgresql.org/docs/current/catalog-pg-index.html">PostgreSQL pg_index</a>
	 */
	static List<String> draftPostgreSqlIndexes(Connection sourceConnection, JdbcTable sourceTable, Connection targetConnection,
											  JdbcTable targetTable, Set<String> targetColumns, String quote) throws SQLException {
		Set<String> reservedNames = new HashSet<>();
		try (var query = targetConnection.prepareStatement("""
			SELECT c.relname FROM pg_catalog.pg_class c
			JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ?
			""")) {
			query.setQueryTimeout(15);
			query.setString(1, targetTable.schema());
			try (var rows = query.executeQuery()) {
				while (rows.next()) {
					reservedNames.add(rows.getString(1));
				}
			}
		}
		List<String> drafts = new ArrayList<>();
		String targetName = JdbcTableSql.qualifiedTableName(DbType.PostgreSQL, targetTable, quote);
		int targetVersion = targetConnection.getMetaData().getDatabaseMajorVersion();
		try (var query = sourceConnection.prepareStatement("""
			SELECT c.relname, i.indisunique, pg_catalog.pg_get_indexdef(i.indexrelid),
			       ARRAY(SELECT a.attname::text FROM pg_catalog.pg_attribute a
			             WHERE a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey))
			FROM pg_catalog.pg_index i
			JOIN pg_catalog.pg_class t ON t.oid = i.indrelid
			JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
			JOIN pg_catalog.pg_class c ON c.oid = i.indexrelid
			JOIN pg_catalog.pg_am m ON m.oid = c.relam
			WHERE n.nspname = ? AND t.relname = ? AND t.relkind = 'r' AND m.amname = 'btree'
			  AND i.indisvalid AND NOT i.indisprimary AND i.indpred IS NULL AND i.indexprs IS NULL
			  AND c.reloptions IS NULL AND c.reltablespace = 0
			  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_collation cl
			                  JOIN pg_catalog.pg_namespace cn ON cn.oid = cl.collnamespace
			                  WHERE cl.oid = ANY(i.indcollation) AND cn.nspname <> 'pg_catalog')
			  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_opclass op
			                  JOIN pg_catalog.pg_namespace pn ON pn.oid = op.opcnamespace
			                  WHERE op.oid = ANY(i.indclass) AND pn.nspname <> 'pg_catalog')
			  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_constraint k WHERE k.conindid = i.indexrelid
			                  AND k.conrelid = i.indrelid AND k.contype IN ('p', 'u', 'x'))
			ORDER BY c.relname
			""")) {
			query.setQueryTimeout(15);
			query.setString(1, sourceTable.schema());
			query.setString(2, sourceTable.name());
			try (var rows = query.executeQuery()) {
				while (rows.next()) {
					String name = rows.getString(1);
					if (reservedNames.contains(name)) {
						continue;
					}
					var columns = rows.getArray(4);
					try {
						if (!targetColumns.containsAll(Arrays.asList((String[]) columns.getArray()))) {
							continue;
						}
					} finally {
						columns.free();
					}
					String definition = rows.getString(3);
					// 保留唯一索引的 NULL 判重语义，不能降级成旧版本的默认行为。
					if (targetVersion < 15 && definition.endsWith(" NULLS NOT DISTINCT")) {
						continue;
					}
					var header = POSTGRESQL_INDEX_HEADER.matcher(definition);
					if (header.find()) {
						drafts.add((rows.getBoolean(2) ? "CREATE UNIQUE INDEX " : "CREATE INDEX ")
							+ JdbcTableSql.quoteIdentifier(name, quote) + " ON " + targetName + " " + definition.substring(header.end()) + ";");
					}
				}
			}
		}
		return drafts;
	}

	/**
	 * 按真实来源读取 SQLite 索引，排除已由主键比较覆盖的内部索引。
	 * UNIQUE 创建的内部名称随表名变化，因此以索引列、方向及排序规则比较。
	 * 部分索引和表达式索引还比较 SQLite 保存的键与谓词 SQL。
	 *
	 * @param connection 当前 SQLite 连接
	 * @param table      JDBC 返回的表及归属
	 * @return 按显式索引名或 UNIQUE 定义组织的索引
	 * @throws SQLException 无法读取 SQLite 索引元数据
	 * @see <a href="https://www.sqlite.org/pragma.html#pragma_index_list">SQLite index_list</a>
	 * @see <a href="https://www.sqlite.org/pragma.html#pragma_index_xinfo">SQLite index_xinfo</a>
	 */
	static Map<String, List<String>> readSqliteIndexes(Connection connection, JdbcTable table) throws SQLException {
		String schema = JdbcTableSql.sqliteSchema(table);
		var indexes = new LinkedHashMap<String, TreeMap<Integer, String>>();
		var automatic = new HashSet<String>();
		var advancedDefinitions = new HashMap<String, String>();
		String quotedSchema = JdbcTableSql.quoteIdentifier(schema, connection.getMetaData().getIdentifierQuoteString());
		try (var statement = connection.prepareStatement("""
			SELECT i.name, i.origin, i."unique", i.partial, c.seqno, c.name, c."desc", c.coll,
			       CASE WHEN i.partial = 1 OR c.cid = -2 THEN s.sql END AS definition
			FROM pragma_index_list(?, ?) i
			JOIN pragma_index_xinfo(i.name, ?) c ON c."key" = 1
			LEFT JOIN %s.sqlite_schema s ON s.type = 'index' AND s.name = i.name
			WHERE i.origin <> 'pk'
			ORDER BY i.name, c.seqno
			""".formatted(quotedSchema))) {
			statement.setQueryTimeout(15);
			statement.setString(1, table.name());
			statement.setString(2, schema);
			statement.setString(3, schema);
			try (var rows = statement.executeQuery()) {
				while (rows.next()) {
					String name = rows.getString(1);
					if ("u".equals(rows.getString(2))) {
						automatic.add(name);
					}
					String ddl = rows.getString(9);
					if (Objects.nonNull(ddl)) {
						// 排除索引头部的名称和所属表。引号内的左括号不属于键列表。
						advancedDefinitions.computeIfAbsent(name, ignored -> SQLITE_DDL_TOKEN.matcher(ddl).results()
							.filter(token -> "(".equals(token.group())).findFirst()
							.map(token -> ddl.substring(token.start()).strip()).orElse(ddl));
					}
					String column = Objects.toString(rows.getString(6), "<expression>");
					String definition = column + (rows.getBoolean(7) ? " DESC" : " ASC")
						+ " COLLATE " + rows.getString(8) + " [unique=" + rows.getBoolean(3)
						+ ", partial=" + rows.getBoolean(4) + "]";
					indexes.computeIfAbsent(name, ignored -> new TreeMap<>()).put(rows.getInt(5), definition);
				}
			}
		}
		Map<String, List<String>> definitions = new LinkedHashMap<>();
		orderedDefinitions(indexes).forEach((name, columns) -> {
			if (automatic.contains(name)) {
				definitions.computeIfAbsent("UNIQUE " + columns, ignored -> new ArrayList<>()).add(columns.toString());
			} else {
				String ddl = advancedDefinitions.get(name);
				if (Objects.isNull(ddl)) {
					definitions.put(name, columns);
				} else {
					List<String> definition = new ArrayList<>(columns.size() + 1);
					definition.addAll(columns);
					definition.add(ddl);
					definitions.put(name, definition);
				}
			}
		});
		return definitions;
	}

	/**
	 * 为 SQLite 显式且基于列的非部分索引生成草案。保留排序规则和唯一性，避开同名对象。
	 *
	 * @param sourceConnection 参考连接
	 * @param sourceTable 参考表
	 * @param targetConnection 目标连接
	 * @param targetTable 目标表
	 * @param targetColumns 目标已有或本次草案先添加的列名
	 * @param quote 目标标识符引号
	 * @return 可审核的索引迁移语句
	 * @throws SQLException 索引目录读取失败
	 */
	static List<String> draftSqliteIndexes(Connection sourceConnection, JdbcTable sourceTable, Connection targetConnection,
									  JdbcTable targetTable, Set<String> targetColumns, String quote) throws SQLException {
		String schema = JdbcTableSql.quoteIdentifier(JdbcTableSql.sqliteSchema(targetTable), quote);
		Set<String> unavailable = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		try (var query = targetConnection.prepareStatement(
			"SELECT name FROM " + schema + ".sqlite_schema WHERE type IN ('index', 'table', 'view')")) {
			query.setQueryTimeout(15);
			try (var rows = query.executeQuery()) {
				while (rows.next()) {
					unavailable.add(rows.getString(1));
				}
			}
		}
		Map<String, List<String>> definitions = new LinkedHashMap<>();
		Set<String> unique = new HashSet<>();
		String sourceSchema = JdbcTableSql.sqliteSchema(sourceTable);
		try (var query = sourceConnection.prepareStatement("""
			SELECT i.name, i."unique", c.cid, c.name, c."desc", c.coll
			FROM pragma_index_list(?, ?) i
			JOIN pragma_index_xinfo(i.name, ?) c ON c."key" = 1
			WHERE i.origin = 'c' AND i.partial = 0
			ORDER BY i.name, c.seqno
			""")) {
			query.setQueryTimeout(15);
			query.setString(1, sourceTable.name());
			query.setString(2, sourceSchema);
			query.setString(3, sourceSchema);
			try (var rows = query.executeQuery()) {
				while (rows.next()) {
					String name = rows.getString(1);
					String column = rows.getString(4);
					String collation = Objects.toString(rows.getString(6), "");
					// SQLite 目录保留声明大小写，按原样生成以便迁移后直接复查。
					if (rows.getInt(3) < 0 || !targetColumns.contains(column)
						|| !collation.matches("(?i:BINARY|NOCASE|RTRIM)")) {
						unavailable.add(name);
					}
					if (!unavailable.contains(name)) {
						if (rows.getBoolean(2)) {
							unique.add(name);
						}
						definitions.computeIfAbsent(name, ignored -> new ArrayList<>()).add(
							JdbcTableSql.quoteIdentifier(column, quote) + " COLLATE " + collation + (rows.getBoolean(5) ? " DESC" : " ASC"));
					}
				}
			}
		}
		List<String> drafts = new ArrayList<>();
		String table = JdbcTableSql.quoteIdentifier(targetTable.name(), quote);
		for (var entry : definitions.entrySet()) {
			String name = entry.getKey();
			if (!unavailable.contains(name)) {
				// SQLite 的 CREATE INDEX 用索引名上的模式定位表，ON 后的表名不带模式。
				drafts.add((unique.contains(name) ? "CREATE UNIQUE INDEX " : "CREATE INDEX ") + schema + "."
					+ JdbcTableSql.quoteIdentifier(name, quote) + " ON " + table + " (" + String.join(", ", entry.getValue()) + ");");
			}
		}
		return drafts;
	}

	/**
	 * 读取 JDBC 暴露的索引，略过统计行以及已由主键比较覆盖的唯一键结构。
	 *
	 * @param metadata   当前连接元数据
	 * @param table      目标表
	 * @param primaryKey 按定义排列的主键列
	 * @return 按索引名组织的可比较元数据
	 * @throws SQLException 驱动读取索引失败
	 */
	static Map<String, List<String>> readIndexes(DatabaseMetaData metadata, JdbcTable table, List<String> primaryKey)
		throws SQLException {
		var indexes = new LinkedHashMap<String, TreeMap<Integer, String>>();
		var keyColumns = new LinkedHashMap<String, TreeMap<Integer, String>>();
		try (var rows = metadata.getIndexInfo(table.catalog(), table.schema(), table.name(), false, true)) {
			while (rows.next()) {
				String name = rows.getString("INDEX_NAME");
				if (Objects.isNull(name) || rows.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) {
					continue;
				}
				boolean nonUnique = rows.getBoolean("NON_UNIQUE");
				int ordinal = rows.getInt("ORDINAL_POSITION");
				String column = Objects.toString(rows.getString("COLUMN_NAME"), "<expression unavailable>");
				String direction = Objects.toString(rows.getString("ASC_OR_DESC"), "");
				String predicate = Objects.toString(rows.getString("FILTER_CONDITION"), "");
				indexes.computeIfAbsent(name, ignored -> new TreeMap<>()).put(ordinal,
					List.of(column, direction, Boolean.toString(!nonUnique), predicate).toString());
				if (!nonUnique) {
					keyColumns.computeIfAbsent(name, ignored -> new TreeMap<>()).put(ordinal, column);
				}
			}
		}
		if (!primaryKey.isEmpty()) {
			keyColumns.forEach((name, columns) -> {
				if (primaryKey.equals(List.copyOf(columns.values()))) {
					indexes.remove(name);
				}
			});
		}
		return orderedDefinitions(indexes);
	}

	/**
	 * 读取指向其他表的外键。对本库本模式引用使用相对表名，避免跨连接比较时误判。
	 *
	 * @param metadata 当前连接元数据
	 * @param table    目标表
	 * @return 按外键名组织的列映射、删除及更新规则
	 * @throws SQLException 驱动读取外键失败
	 */
	static Map<String, List<String>> readForeignKeys(DatabaseMetaData metadata, JdbcTable table) throws SQLException {
		var keys = new LinkedHashMap<String, TreeMap<Integer, String>>();
		int unnamed = 0;
		try (var rows = metadata.getImportedKeys(table.catalog(), table.schema(), table.name())) {
			while (rows.next()) {
				String name = rows.getString("FK_NAME");
				int ordinal = rows.getInt("KEY_SEQ");
				if (Objects.isNull(name)) {
					if (ordinal <= 1 || unnamed == 0) {
						unnamed++;
					}
					name = "<unnamed " + unnamed + ">";
				}
				String catalog = relativeQualifier(table.catalog(), rows.getString("PKTABLE_CAT"));
				String schema = relativeQualifier(table.schema(), rows.getString("PKTABLE_SCHEM"));
				String reference = catalog + schema + rows.getString("PKTABLE_NAME");
				keys.computeIfAbsent(name, ignored -> new TreeMap<>()).put(ordinal,
					rows.getString("FKCOLUMN_NAME") + " → " + reference + "." + rows.getString("PKCOLUMN_NAME")
						+ " [update=" + rows.getShort("UPDATE_RULE") + ", delete=" + rows.getShort("DELETE_RULE")
						+ ", deferrability=" + rows.getShort("DEFERRABILITY") + "]");
			}
		}
		return orderedDefinitions(keys);
	}

	/**
	 * 将对象的 JDBC 序号映射转成可比较的定义序列。
	 *
	 * @param definitions 按对象名称和序号整理的定义
	 * @return 按输入对象顺序保留的定义表
	 */
	private static Map<String, List<String>> orderedDefinitions(Map<String, TreeMap<Integer, String>> definitions) {
		Map<String, List<String>> result = LinkedHashMap.newLinkedHashMap(definitions.size());
		definitions.forEach((name, parts) -> result.put(name, List.copyOf(parts.values())));
		return result;
	}

	/**
	 * 保留跨目录或跨模式引用的限定符。
	 *
	 * @param local      目标表的目录或模式
	 * @param referenced 被引用表的目录或模式
	 * @return 跨边界时返回带点号的限定符
	 */
	private static String relativeQualifier(String local, String referenced) {
		return Objects.isNull(referenced) || referenced.isEmpty() || Objects.equals(local, referenced) ? "" : referenced + ".";
	}
}
