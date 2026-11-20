package io.github.jinlongliao.mybatis.workbench.database;

import org.mybatis.generator.api.intellij.IntellijColumnInfo;
import org.mybatis.generator.api.intellij.IntellijTableInfo;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.math.BigInteger;
import java.text.MessageFormat;
import java.util.*;
import java.util.function.BiFunction;

import io.github.jinlongliao.mybatis.workbench.model.DbType;

/**
 * 比较 JDBC 表结构，并为受支持的列变更生成待审核的迁移语句。
 *
 * @author liaojinlong
 * @since 2026/11/18 16:26
 */
public final class JdbcTableComparer {
	/** SQLite 严格表可直接移植且保持声明类型的常用列类型。 */
	private static final Set<String> SQLITE_STRICT_MIGRATION_TYPES = Set.of("INT", "INTEGER", "REAL", "TEXT", "BLOB");

	/**
	 * 工具类无需实例。
	 */
	private JdbcTableComparer() {
	}

	/**
	 * 同一方言下比较两个 JDBC 对象。可跨连接读取，草案使用目标连接的标识符引号。
	 * 生成列、主键与不支持的列变更仅报告差异。
	 *
	 * @param sourceConnection 参考结构连接
	 * @param sourceTable      参考表
	 * @param targetConnection 接收修改的目标连接
	 * @param targetTable      目标表
	 * @param type             两边共同的 JDBC 方言
	 * @return 结构差异和可审核的迁移草案
	 * @throws SQLException 目标表消失或数据库元数据读取失败
	 */
	public static JdbcTableDifferences compareTableStructure(Connection sourceConnection, JdbcTable sourceTable,
															 Connection targetConnection, JdbcTable targetTable, DbType type)
		throws SQLException {
		ResourceBundle messages = ResourceBundle.getBundle("messages.WorkbenchBundle");
		return compareTableStructure(sourceConnection, sourceTable, targetConnection, targetTable, type,
			(key, values) -> new MessageFormat(messages.getString(key), messages.getLocale()).format(values));
	}

	/**
	 * 比较表结构，由宿主提供文案解析，诊断文本与迁移 SQL 独立生成。
	 *
	 * @param sourceConnection 参考结构连接
	 * @param sourceTable      参考表
	 * @param targetConnection 接收修改的目标连接
	 * @param targetTable      目标表
	 * @param type             两边共同的 JDBC 方言
	 * @param messages         根据资源键和参数格式化差异文本
	 * @return 本地化差异和新增可空列草案
	 * @throws SQLException 无法读取任一表的元数据
	 */
	public static JdbcTableDifferences compareTableStructure(Connection sourceConnection, JdbcTable sourceTable,
															 Connection targetConnection, JdbcTable targetTable, DbType type,
															 BiFunction<String, Object[], String> messages) throws SQLException {
		var sourceMetadata = sourceConnection.getMetaData();
		var source = JdbcTableMetadata.readGenerationTable(sourceMetadata, type, sourceTable).tableInfo();
		var targetMetadata = targetConnection.getMetaData();
		var target = JdbcTableMetadata.readGenerationTable(targetMetadata, type, targetTable).tableInfo();
		boolean strictSqliteTarget = type == DbType.SQLite && isBaseTable(targetTable)
			&& (targetMetadata.getDatabaseMajorVersion() > 3 || targetMetadata.getDatabaseMinorVersion() >= 37)
			&& isStrictSqliteTable(targetConnection, targetTable);

		List<String> changes = new ArrayList<>();
		String sourceKind = comparableTableKind(sourceTable, type);
		String targetKind = comparableTableKind(targetTable, type);
		if (!sourceKind.equalsIgnoreCase(targetKind)) {
			changes.add(messages.apply("compare.table.kind.changed", new Object[]{sourceKind, targetKind}));
		}
		List<String> migrations = new ArrayList<>();
		if (type == DbType.PostgreSQL && ("PARTITIONED TABLE".equals(sourceTable.type())
			|| "PARTITIONED TABLE".equals(targetTable.type()))) {
			String sourcePartitionKey = PostgreSqlTableDdl.readPostgreSqlPartitionKey(sourceConnection, sourceTable,
				key -> messages.apply(key, new Object[0]));
			String targetPartitionKey = PostgreSqlTableDdl.readPostgreSqlPartitionKey(targetConnection, targetTable,
				key -> messages.apply(key, new Object[0]));
			if (!Objects.equals(sourcePartitionKey, targetPartitionKey)) {
				String unpartitioned = messages.apply("compare.partition.none", new Object[0]);
				changes.add(messages.apply("compare.partition.changed", new Object[]{
					Objects.toString(sourcePartitionKey, unpartitioned), Objects.toString(targetPartitionKey, unpartitioned)}));
			}
			if (Objects.nonNull(sourcePartitionKey) && Objects.nonNull(targetPartitionKey)) {
				appendConstraintChanges(changes, messages, messages.apply("compare.kind.partition", new Object[0]),
					PostgreSqlTableDdl.readPostgreSqlPartitionBounds(sourceConnection, sourceTable),
					PostgreSqlTableDdl.readPostgreSqlPartitionBounds(targetConnection, targetTable));
			}
		}
		List<String> sourceKey = primaryKeyNames(source);
		List<String> targetKey = primaryKeyNames(target);
		if (!sourceKey.equals(targetKey)) {
			changes.add(messages.apply("compare.primary.key", new Object[]{sourceKey, targetKey}));
		}

		// 仅目标表中的额外列按实际定义顺序输出。
		var remaining = LinkedHashMap.<String, IntellijColumnInfo>newLinkedHashMap(target.getColumnInfos().size());
		for (IntellijColumnInfo column : target.getColumnInfos()) {
			remaining.put(column.getName(), column);
		}
		// 索引语句排在列语句之后，只引用已有列或草案会先创建的列。
		Set<String> availableTargetColumns = new HashSet<>(remaining.keySet());
		String quote = targetMetadata.getIdentifierQuoteString();
		boolean mysqlFamilyBaseTables = (type == DbType.MySQL || type == DbType.MariaDB)
			&& isBaseTable(sourceTable) && isBaseTable(targetTable);
		Set<String> mysqlLiteralDefaults = type == DbType.MySQL && mysqlFamilyBaseTables
			? readMySqlLiteralDefaultColumns(sourceConnection, sourceTable) : Set.of();
		Set<String> targetMySqlLiteralDefaults = type == DbType.MySQL && mysqlFamilyBaseTables
			? readMySqlLiteralDefaultColumns(targetConnection, targetTable) : Set.of();
		Boolean standalonePostgreSqlTarget = null;
		for (IntellijColumnInfo sourceColumn : source.getColumnInfos()) {
			String name = sourceColumn.getName();
			IntellijColumnInfo targetColumn = remaining.remove(name);
			// 相同 COLUMN_DEF 文本仍可能来自不同的默认值种类，例如 DEFAULT 42 和 DEFAULT (42)。
			if (type == DbType.MySQL && mysqlFamilyBaseTables && Objects.nonNull(targetColumn)
				&& Objects.nonNull(sourceColumn.getColumnDefaultValue())
				&& Objects.equals(sourceColumn.getColumnDefaultValue(), targetColumn.getColumnDefaultValue())
				&& mysqlLiteralDefaults.contains(name) != targetMySqlLiteralDefaults.contains(name)) {
				String key = mysqlLiteralDefaults.contains(name)
					? "compare.column.target.default.expression" : "compare.column.source.default.expression";
				changes.add(messages.apply(key, new Object[]{name, sourceColumn.getColumnDefaultValue()}));
			}
			if (Objects.isNull(targetColumn)) {
				changes.add(messages.apply("compare.column.missing", new Object[]{name, describeColumn(sourceColumn, messages)}));
				String sqlType = migrationColumnType(type, sourceColumn);
				String defaultClause = migrationDefaultClause(type, sourceColumn);
				boolean defaultIsPortable = type != DbType.MySQL || Objects.isNull(sourceColumn.getColumnDefaultValue())
					|| mysqlLiteralDefaults.contains(name);
				Boolean nullable = sourceColumn.getNullable();
				// 这些方言已验证可用非空字面量默认值添加 NOT NULL 列。
				boolean canBackfill = (type == DbType.H2 || type == DbType.SQLite || type == DbType.MariaDB || type == DbType.MySQL
					|| type == DbType.PostgreSQL)
					&& Boolean.FALSE.equals(nullable)
					&& Objects.nonNull(defaultClause) && !defaultClause.isEmpty();
				if (Objects.nonNull(sqlType) && Objects.nonNull(defaultClause) && defaultIsPortable
					&& (!strictSqliteTarget || SQLITE_STRICT_MIGRATION_TYPES.contains(sqlType))
					&& (Boolean.TRUE.equals(nullable) || canBackfill) && !sourceKey.contains(name)
					&& isBaseTable(sourceTable) && isBaseTable(targetTable)) {
					String keyword = type == DbType.SqlServer || type == DbType.Oracle ? " ADD " : " ADD COLUMN ";
					String constraint = canBackfill ? " NOT NULL;" : " NULL;";
					migrations.add("ALTER TABLE " + JdbcTableSql.qualifiedTableName(type, targetTable, quote)
						+ keyword + JdbcTableSql.quoteIdentifier(name, quote) + " " + sqlType + defaultClause + constraint);
					availableTargetColumns.add(name);
				}
			} else if (!Arrays.equals(columnDefinition(sourceColumn), columnDefinition(targetColumn))) {
				changes.add(messages.apply("compare.column.changed",
					new Object[]{name, describeColumn(sourceColumn, messages), describeColumn(targetColumn, messages)}));
				if (type == DbType.H2 && isBaseTable(sourceTable) && isBaseTable(targetTable)) {
					String alterColumn = "ALTER TABLE " + JdbcTableSql.qualifiedTableName(type, targetTable, quote)
						+ " ALTER COLUMN " + JdbcTableSql.quoteIdentifier(name, quote);
					String widenedType = h2WidenedVarcharType(sourceColumn, targetColumn);
					// 新默认值可能超过旧长度，先扩容，再调整默认值。
					if (Objects.nonNull(widenedType)) {
						migrations.add(alterColumn + " SET DATA TYPE " + widenedType + ";");
					}
					String defaultChange = supportedColumnDefaultChange(type, sourceColumn, targetColumn, widenedType);
					if (Objects.nonNull(defaultChange)) {
						migrations.add(alterColumn + defaultChange + ";");
					}
					if (!targetKey.contains(name) && canDropColumnNotNull(type, sourceColumn, targetColumn, widenedType)) {
						migrations.add(alterColumn + " DROP NOT NULL;");
					}
				} else if (type == DbType.PostgreSQL && isBaseTable(sourceTable) && isBaseTable(targetTable)) {
					// 比较只涉及所选表，修改默认值时不递归到继承子表。
					String alterColumn = "ALTER TABLE ONLY " + JdbcTableSql.qualifiedTableName(type, targetTable, quote)
						+ " ALTER COLUMN " + JdbcTableSql.quoteIdentifier(name, quote);
					String defaultChange = supportedColumnDefaultChange(type, sourceColumn, targetColumn, null);
					if (Objects.nonNull(defaultChange)) {
						migrations.add(alterColumn + defaultChange + ";");
					}
					if (!targetKey.contains(name) && canDropColumnNotNull(type, sourceColumn, targetColumn, null)) {
						if (Objects.isNull(standalonePostgreSqlTarget)) {
							standalonePostgreSqlTarget = PostgreSqlTableDdl.isStandalonePostgreSqlTable(targetConnection, targetTable);
						}
						if (standalonePostgreSqlTarget) {
							migrations.add(alterColumn + " DROP NOT NULL;");
						}
					}
				} else if (mysqlFamilyBaseTables
					&& !Objects.equals(sourceColumn.getColumnDefaultValue(), targetColumn.getColumnDefaultValue())) {
					if (type == DbType.MySQL
						&& ((Objects.nonNull(sourceColumn.getColumnDefaultValue()) && !mysqlLiteralDefaults.contains(name))
						|| (Objects.nonNull(targetColumn.getColumnDefaultValue()) && !targetMySqlLiteralDefaults.contains(name)))) {
						continue;
					}
					String defaultChange = supportedColumnDefaultChange(type, sourceColumn, targetColumn, null);
					if (Objects.nonNull(defaultChange)) {
						migrations.add("ALTER TABLE " + JdbcTableSql.qualifiedTableName(type, targetTable, quote)
							+ " ALTER COLUMN " + JdbcTableSql.quoteIdentifier(name, quote) + defaultChange + ";");
					}
				}
			}
		}
		remaining.keySet().forEach(name -> changes.add(messages.apply("compare.column.extra", new Object[]{name})));
		if (type == DbType.PostgreSQL && sourceKind.equalsIgnoreCase(targetKind)
			&& ("VIEW".equalsIgnoreCase(sourceKind) || "MATERIALIZED VIEW".equalsIgnoreCase(sourceKind))) {
			String sourceQuery = PostgreSqlTableDdl.readPostgreSqlViewQuery(sourceConnection, sourceTable,
				key -> messages.apply(key, new Object[0]));
			String targetQuery = PostgreSqlTableDdl.readPostgreSqlViewQuery(targetConnection, targetTable,
				key -> messages.apply(key, new Object[0]));
			if (!Objects.equals(sourceQuery, targetQuery)) {
				changes.add(messages.apply("compare.view.definition.changed", new Object[]{sourceQuery, targetQuery}));
			}
		}
		// 物化视图可独立建索引，比较定义时仍只走只读目录查询。
		if (type == DbType.PostgreSQL && "MATERIALIZED VIEW".equalsIgnoreCase(sourceTable.type())
			&& "MATERIALIZED VIEW".equalsIgnoreCase(targetTable.type())) {
			appendConstraintChanges(changes, messages, messages.apply("compare.kind.index", new Object[0]),
				JdbcTableConstraints.readPostgreSqlIndexes(sourceConnection, sourceTable),
				JdbcTableConstraints.readPostgreSqlIndexes(targetConnection, targetTable));
		}
		// 触发器可能依赖其他对象且会改变写入行为，比较结果只供审阅。
		if (type == DbType.SQLite && sourceKind.equalsIgnoreCase(targetKind)) {
			if ("VIEW".equalsIgnoreCase(sourceKind)) {
				String sourceDefinition = JdbcTableDdl.readSqliteDdl(sourceConnection, sourceTable,
					sourceMetadata.getIdentifierQuoteString(), false, key -> messages.apply(key, new Object[0]));
				String targetDefinition = JdbcTableDdl.readSqliteDdl(targetConnection, targetTable,
					targetMetadata.getIdentifierQuoteString(), false, key -> messages.apply(key, new Object[0]));
				if (!sourceDefinition.equals(targetDefinition)) {
					changes.add(messages.apply("compare.view.definition.changed",
						new Object[]{sourceDefinition, targetDefinition}));
				}
			}
			appendConstraintChanges(changes, messages, messages.apply("compare.kind.trigger", new Object[0]),
				JdbcTableConstraints.readSqliteTriggers(sourceConnection, sourceTable),
				JdbcTableConstraints.readSqliteTriggers(targetConnection, targetTable));
		}
		if (isBaseTable(sourceTable) && isBaseTable(targetTable)) {
			var sourceIndexes = switch (type) {
				case SQLite -> JdbcTableConstraints.readSqliteIndexes(sourceConnection, sourceTable);
				case H2 -> JdbcTableConstraints.readH2Indexes(sourceConnection, sourceTable);
				case PostgreSQL -> JdbcTableConstraints.readPostgreSqlIndexes(sourceConnection, sourceTable);
				case MySQL, MariaDB ->
					JdbcTableConstraints.readMySqlFamilyIndexes(sourceConnection, sourceTable, type);
				case SqlServer -> sqlServerIndexDefinitions(sourceConnection, sourceTable);
				default -> JdbcTableConstraints.readIndexes(sourceMetadata, sourceTable, sourceKey);
			};
			var targetIndexes = switch (type) {
				case SQLite -> JdbcTableConstraints.readSqliteIndexes(targetConnection, targetTable);
				case H2 -> JdbcTableConstraints.readH2Indexes(targetConnection, targetTable);
				case PostgreSQL -> JdbcTableConstraints.readPostgreSqlIndexes(targetConnection, targetTable);
				case MySQL, MariaDB ->
					JdbcTableConstraints.readMySqlFamilyIndexes(targetConnection, targetTable, type);
				case SqlServer -> sqlServerIndexDefinitions(targetConnection, targetTable);
				default -> JdbcTableConstraints.readIndexes(targetMetadata, targetTable, targetKey);
			};
			appendConstraintChanges(changes, messages, messages.apply("compare.kind.index", new Object[0]),
				sourceIndexes, targetIndexes);
			if (type == DbType.MySQL || type == DbType.MariaDB) {
				migrations.addAll(JdbcTableConstraints.draftMySqlFamilyIndexes(sourceConnection, sourceTable, targetTable, type,
					targetMetadata, availableTargetColumns, targetIndexes.keySet(), quote));
			}
			appendConstraintChanges(changes, messages, messages.apply("compare.kind.foreign.key", new Object[0]),
				JdbcTableConstraints.readForeignKeys(sourceMetadata, sourceTable),
				JdbcTableConstraints.readForeignKeys(targetMetadata, targetTable));
			if (type == DbType.H2) {
				migrations.addAll(JdbcTableConstraints.draftH2Indexes(sourceConnection, sourceTable, targetConnection,
					targetTable, availableTargetColumns, quote));
				appendConstraintChanges(changes, messages, messages.apply("compare.kind.check.constraint", new Object[0]),
					JdbcTableConstraints.readH2CheckConstraints(sourceConnection, sourceTable),
					JdbcTableConstraints.readH2CheckConstraints(targetConnection, targetTable));
			} else if (type == DbType.PostgreSQL) {
				migrations.addAll(JdbcTableConstraints.draftPostgreSqlIndexes(sourceConnection, sourceTable, targetConnection,
					targetTable, availableTargetColumns, quote));
				appendConstraintChanges(changes, messages, messages.apply("compare.kind.check.constraint", new Object[0]),
					JdbcTableConstraints.readPostgreSqlCheckConstraints(sourceConnection, sourceTable),
					JdbcTableConstraints.readPostgreSqlCheckConstraints(targetConnection, targetTable));
			} else if (type == DbType.MySQL) {
				appendConstraintChanges(changes, messages, messages.apply("compare.kind.check.constraint", new Object[0]),
					JdbcTableConstraints.readMySqlCheckConstraints(sourceConnection, sourceTable),
					JdbcTableConstraints.readMySqlCheckConstraints(targetConnection, targetTable));
			} else if (type == DbType.MariaDB) {
				appendConstraintChanges(changes, messages, messages.apply("compare.kind.check.constraint", new Object[0]),
					JdbcTableConstraints.readMariaDbCheckConstraints(sourceConnection, sourceTable),
					JdbcTableConstraints.readMariaDbCheckConstraints(targetConnection, targetTable));
			} else if (type == DbType.SQLite) {
				migrations.addAll(JdbcTableConstraints.draftSqliteIndexes(sourceConnection, sourceTable, targetConnection,
					targetTable, availableTargetColumns, quote));
				appendConstraintChanges(changes, messages, messages.apply("compare.kind.check.constraint", new Object[0]),
					JdbcTableConstraints.readSqliteCheckConstraints(sourceConnection, sourceTable),
					JdbcTableConstraints.readSqliteCheckConstraints(targetConnection, targetTable));
			} else if (type == DbType.Oracle) {
				appendConstraintChanges(changes, messages, messages.apply("compare.kind.check.constraint", new Object[0]),
					JdbcTableConstraints.readOracleCheckConstraints(sourceConnection, sourceTable),
					JdbcTableConstraints.readOracleCheckConstraints(targetConnection, targetTable));
			} else if (type == DbType.SqlServer) {
				appendConstraintChanges(changes, messages, messages.apply("compare.kind.check.constraint", new Object[0]), sqlServerCheckDefinitions(sourceConnection, sourceTable),
					sqlServerCheckDefinitions(targetConnection, targetTable));
			}
		}
		return new JdbcTableDifferences(List.copyOf(changes), List.copyOf(migrations));
	}

	/**
	 * 使用与 DDL 预览同一来源比较行存储索引，包括键顺序、包含列、过滤谓词及选项。
	 *
	 * @param connection 当前 SQL Server 连接
	 * @param table      当前连接读取的表
	 * @return 以索引名组织的相对定义
	 * @throws SQLException 无法读取当前表的索引
	 */
	private static Map<String, List<String>> sqlServerIndexDefinitions(Connection connection, JdbcTable table)
		throws SQLException {
		Map<String, List<String>> definitions = new LinkedHashMap<>();
		String quote = connection.getMetaData().getIdentifierQuoteString();
		SqlServerTableDdl.readSqlServerRowstoreIndexes(connection, table, null, quote)
			.forEach((name, definition) -> definitions.put(name, List.of(definition)));
		return definitions;
	}

	/**
	 * 将 SQL Server 检查表达式和状态转换为可比较的定义。
	 *
	 * @param connection 当前数据库连接
	 * @param table      当前连接读取的表
	 * @return 按约束名组织的定义和状态
	 * @throws SQLException 检查约束元数据读取失败
	 */
	private static Map<String, List<String>> sqlServerCheckDefinitions(Connection connection, JdbcTable table)
		throws SQLException {
		Map<String, List<String>> definitions = new LinkedHashMap<>();
		for (var check : JdbcTableConstraints.readSqlServerCheckConstraints(connection, table)) {
			definitions.put(check.name(), List.of(check.expression(), "DISABLED=" + check.disabled(),
				"TRUSTED=" + !check.untrusted(), "NOT_FOR_REPLICATION=" + check.notForReplication()));
		}
		return definitions;
	}

	/**
	 * 报告同名定义变更和两边缺少的索引或约束。
	 *
	 * @param changes  既有结构差异
	 * @param messages 解析当前语言的差异文案
	 * @param kind     比较对象的本地化名称
	 * @param source   参考表定义
	 * @param target   目标表定义
	 */
	private static void appendConstraintChanges(List<String> changes, BiFunction<String, Object[], String> messages,
												String kind, Map<String, List<String>> source,
												Map<String, List<String>> target) {
		source.forEach((name, definition) -> {
			List<String> targetDefinition = target.get(name);
			if (Objects.isNull(targetDefinition)) {
				changes.add(messages.apply("compare.object.missing", new Object[]{kind, name, definition}));
			} else if (!definition.equals(targetDefinition)) {
				changes.add(messages.apply("compare.object.changed", new Object[]{kind, name, definition, targetDefinition}));
			}
		});
		target.keySet().stream().filter(name -> !source.containsKey(name))
			.forEach(name -> changes.add(messages.apply("compare.object.extra", new Object[]{kind, name})));
	}

	/**
	 * 列出主键定义中的列及顺序。
	 *
	 * @param table JDBC 表快照
	 * @return 主键顺序
	 */
	private static List<String> primaryKeyNames(IntellijTableInfo table) {
		return table.getPrimaryKeyColumns().stream().map(IntellijColumnInfo::getName).toList();
	}

	/**
	 * 提取参与比较的列属性，保留空值及原始类型，使比较独立于界面语言。
	 *
	 * @param column JDBC 表列快照
	 * @return 按列定义文案参数排列的属性
	 */
	private static Object[] columnDefinition(IntellijColumnInfo column) {
		return new Object[]{Objects.toString(column.getTypeName(), ""), column.getDataType(), column.getSize(),
			column.getDecimalDigits(), column.getNullable(), column.getColumnDefaultValue(), column.isAutoIncrement(),
			column.isGeneratedColumn(), column.getRemarks()};
	}

	/**
	 * 格式化差异所需的列定义。
	 *
	 * @param column   JDBC 表列快照
	 * @param messages 当前语言的文案解析
	 * @return 展示用的列摘要
	 */
	private static String describeColumn(IntellijColumnInfo column, BiFunction<String, Object[], String> messages) {
		return messages.apply("compare.column.definition", Arrays.stream(columnDefinition(column))
			.map(String::valueOf).toArray());
	}

	/**
	 * 生成白名单中的简单类型。供应方扩展类型及带表达式的列都需要人工迁移。
	 *
	 * @param type   两边共用的 JDBC 方言
	 * @param column 参考表列
	 * @return 可用于新建列的 SQL 类型，其他情况返回空
	 * @see <a href="https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlqr/Data-Types.html">Oracle 数据类型精度与范围</a>
	 */
	private static String migrationColumnType(DbType type, IntellijColumnInfo column) {
		if (column.isAutoIncrement() || column.isGeneratedColumn()) {
			return null;
		}
		String name = Objects.toString(column.getTypeName(), "").toUpperCase(Locale.ROOT);
		if (type == DbType.SQLite && SQLITE_STRICT_MIGRATION_TYPES.contains(name)) {
			// INT 与 INTEGER 在结构比较中保留各自的声明名称。
			return name;
		}
		if (type == DbType.PostgreSQL && "TEXT".equals(name)) {
			return "TEXT";
		}
		if (type == DbType.Oracle) {
			// Oracle JDBC 的 DATE 可能映射为 TIMESTAMP，生成草案时以数据库类型名为准。
			if ("DATE".equals(name)) {
				return "DATE";
			}
			int precision = column.getSize();
			int scale = column.getDecimalDigits();
			return "NUMBER".equals(name) && precision >= 1 && precision <= 38 && scale >= -84 && scale <= 127
				? "NUMBER(" + precision + ", " + scale + ")" : null;
		}
		return switch (column.getDataType()) {
			case Types.INTEGER ->
				"INTEGER".equals(name) || "INT".equals(name) || "INT4".equals(name) ? "INTEGER" : null;
			case Types.BIGINT -> "BIGINT".equals(name) || "INT8".equals(name) ? "BIGINT" : null;
			case Types.VARCHAR -> ("VARCHAR".equals(name) || "CHARACTER VARYING".equals(name))
				&& column.getSize() > 0 && column.getSize() <= 4_000 ? "VARCHAR(" + column.getSize() + ")" : null;
			case Types.DATE -> type != DbType.SQLite && "DATE".equals(name) ? "DATE" : null;
			case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> {
				int precision = column.getDecimalDigits();
				// H2 支持 9 位小数秒，PostgreSQL 支持 6 位。其余方言需分别验证。
				if ((type != DbType.H2 && type != DbType.PostgreSQL)
					|| precision < 0 || precision > (type == DbType.H2 ? 9 : 6)) {
					yield null;
				}
				String timestamp = "TIMESTAMP(" + precision + ")";
				yield switch (name) {
					case "TIMESTAMP" -> timestamp;
					// PostgreSQL JDBC 也用 Types.TIMESTAMP 表示 timestamptz，以类型名区分。
					case "TIMESTAMP WITH TIME ZONE", "TIMESTAMPTZ" -> timestamp + " WITH TIME ZONE";
					default -> null;
				};
			}
			case Types.BOOLEAN, Types.BIT -> (type == DbType.H2 || type == DbType.PostgreSQL)
				&& ("BOOL".equals(name) || "BOOLEAN".equals(name)) ? "BOOLEAN" : null;
			case Types.DECIMAL, Types.NUMERIC ->
				type != DbType.SQLite && ("DECIMAL".equals(name) || "NUMERIC".equals(name))
					&& column.getSize() > 0 && column.getDecimalDigits() >= 0 && column.getDecimalDigits() <= column.getSize()
					? name + "(" + column.getSize() + ", " + column.getDecimalDigits() + ")" : null;
			default -> null;
		};
	}

	/**
	 * 仅允许已经验证可从 JDBC 元数据无歧义还原的默认值。
	 *
	 * @param type 当前数据库方言
	 * @param column 参考表列
	 * @return SQL 默认值子句，无默认值时为空字符串，不支持时为空引用
	 */
	private static String migrationDefaultClause(DbType type, IntellijColumnInfo column) {
		String value = column.getColumnDefaultValue();
		// MariaDB 将隐式 SQL NULL 默认值报告为不带引号的 NULL 文本。
		if (Objects.isNull(value) || (type == DbType.MariaDB && "NULL".equalsIgnoreCase(value))) {
			return "";
		}
		if (type == DbType.SQLite) {
			String declaredType = Objects.toString(column.getTypeName(), "").toUpperCase(Locale.ROOT);
			boolean literal = switch (declaredType) {
				case "INT", "INTEGER", "BIGINT" -> value.matches("[+-]?[0-9]{1,19}")
					&& new BigInteger(value).bitLength() <= 63;
				case "TEXT", "VARCHAR" -> value.matches("'(?:[^']|'')*+'");
				default -> false;
			};
			return literal ? " DEFAULT " + value : null;
		}
		if (type == DbType.PostgreSQL) {
			// JDBC 返回 PostgreSQL 反解析后的规范化字面量，负整数可能带显式类型转换。
			boolean literal = switch (column.getDataType()) {
				case Types.BOOLEAN, Types.BIT -> "true".equals(value) || "false".equals(value);
				case Types.INTEGER -> value.matches("(?:[0-9]{1,10}|'-?[0-9]{1,10}'::integer)");
				case Types.BIGINT -> value.matches("(?:[0-9]{1,19}|'-?[0-9]{1,19}'::bigint)");
				case Types.VARCHAR -> value.indexOf('\\') < 0 && value.matches("'(?:[^']|'')*+'::(?:character varying|text)");
				case Types.NUMERIC, Types.DECIMAL -> value.matches("(?:[0-9]+(?:\\.[0-9]+)?|'-?[0-9]+(?:\\.[0-9]+)?'::numeric)");
				default -> false;
			};
			return literal ? " DEFAULT " + value : null;
		}
		if (type == DbType.MariaDB || type == DbType.MySQL) {
			// MySQL 目录返回未加引号的文本，MariaDB JDBC 返回数据库引用后的字面量。
			if (type == DbType.MySQL && column.getDataType() == Types.VARCHAR) {
				return value.indexOf('\\') < 0 && value.codePoints().noneMatch(Character::isISOControl)
					? " DEFAULT '" + value.replace("'", "''") + "'" : null;
			}
			boolean literal = switch (column.getDataType()) {
				case Types.INTEGER, Types.BIGINT -> value.matches("[+-]?[0-9]{1,19}")
					&& new BigInteger(value).bitLength() <= (column.getDataType() == Types.INTEGER ? 31 : 63);
				// 反斜杠的解释取决于 NO_BACKSLASH_ESCAPES，跨库草案仅保留成对单引号的字面量。
				case Types.VARCHAR -> value.indexOf('\\') < 0 && value.matches("'(?:[^']|'')*+'");
				default -> false;
			};
			return literal ? " DEFAULT " + value : null;
		}
		if (type != DbType.H2) {
			return null;
		}
		boolean literal = switch (column.getDataType()) {
			case Types.BOOLEAN -> "TRUE".equals(value) || "FALSE".equals(value);
			case Types.INTEGER -> value.matches("-?[0-9]{1,10}");
			// H2 会用 U& 字面量报告 Unicode 默认值，两种形式都以成对单引号转义。
			case Types.VARCHAR -> value.matches("(?:U&)?'(?:[^']|'')*+'");
			default -> false;
		};
		return literal ? " DEFAULT " + value : null;
	}

	/**
	 * 读取 MySQL 字面量默认值列。只看 JDBC 的 COLUMN_DEF 无法区分表达式 DEFAULT (42) 和 DEFAULT 42。
	 *
	 * @param connection 参考表连接
	 * @param table 参考普通表
	 * @return 有字面量默认值的列名
	 * @throws SQLException 目录读取失败
	 * @see <a href="https://dev.mysql.com/doc/refman/8.4/en/information-schema-columns-table.html">MySQL COLUMNS</a>
	 */
	private static Set<String> readMySqlLiteralDefaultColumns(Connection connection, JdbcTable table) throws SQLException {
		Set<String> columns = new HashSet<>();
		try (var query = connection.prepareStatement("""
			SELECT COLUMN_NAME FROM information_schema.COLUMNS
			WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_DEFAULT IS NOT NULL
			  AND LOCATE('DEFAULT_GENERATED', EXTRA) = 0
			""")) {
			query.setQueryTimeout(15);
			query.setString(1, Objects.requireNonNullElse(table.catalog(), table.schema()));
			query.setString(2, table.name());
			try (var rows = query.executeQuery()) {
				while (rows.next()) {
					columns.add(rows.getString(1));
				}
			}
		}
		return columns;
	}

	/**
	 * 生成现有普通列的默认值调整片段，要求应用扩容语句后类型一致，且可空性一致。
	 *
	 * @param type 已验证的方言
	 * @param sourceColumn 期望的列定义
	 * @param targetColumn 要调整的列定义
	 * @param widenedType 本草案先应用的类型，未扩容时为空引用
	 * @return 调整默认值的 SQL 片段，无变化或不支持时返回空引用
	 * @see <a href="https://dev.mysql.com/doc/refman/8.4/en/alter-table.html">MySQL ALTER TABLE</a>
	 * @see <a href="https://mariadb.com/docs/server/reference/sql-statements/data-definition/alter/alter-table">MariaDB ALTER TABLE</a>
	 */
	private static String supportedColumnDefaultChange(DbType type, IntellijColumnInfo sourceColumn,
		IntellijColumnInfo targetColumn, String widenedType) {
		String sourceType = migrationColumnType(type, sourceColumn);
		String targetType = Objects.nonNull(widenedType) ? widenedType : migrationColumnType(type, targetColumn);
		if (Objects.isNull(sourceType) || !sourceType.equals(targetType)
			|| !Objects.equals(sourceColumn.getNullable(), targetColumn.getNullable())) {
			return null;
		}
		String expectedDefault = migrationDefaultClause(type, sourceColumn);
		String existingDefault = migrationDefaultClause(type, targetColumn);
		if (Objects.isNull(expectedDefault) || Objects.isNull(existingDefault) || expectedDefault.equals(existingDefault)) {
			return null;
		}
		if (type == DbType.MySQL && expectedDefault.isEmpty() && Boolean.TRUE.equals(sourceColumn.getNullable())) {
			// MySQL 8.4 实测 DROP DEFAULT 后省略该可空列仍可能触发无默认值错误。
			return " SET DEFAULT NULL";
		}
		return expectedDefault.isEmpty() ? " DROP DEFAULT" : " SET" + expectedDefault;
	}

	/**
	 * 返回用于扩大 H2 普通 VARCHAR 列长度的类型，保留目标列的非空性和默认值。
	 *
	 * @param sourceColumn 期望的列定义
	 * @param targetColumn 要调整的列定义
	 * @return 加宽后的类型，缩短、改类型或不支持时返回空引用
	 */
	private static String h2WidenedVarcharType(IntellijColumnInfo sourceColumn, IntellijColumnInfo targetColumn) {
		if (sourceColumn.getDataType() != Types.VARCHAR || targetColumn.getDataType() != Types.VARCHAR
			|| sourceColumn.getSize() <= targetColumn.getSize()
			|| Objects.isNull(migrationColumnType(DbType.H2, targetColumn))) {
			return null;
		}
		return migrationColumnType(DbType.H2, sourceColumn);
	}

	/**
	 * 判断 H2 或 PostgreSQL 普通列是否可以放宽为可空，要求类型一致且无需更改默认值。
	 *
	 * @param type 当前方言
	 * @param sourceColumn 期望的列定义
	 * @param targetColumn 要调整的列定义
	 * @param widenedType 草案先应用的类型，未扩容时为空引用
	 * @return 是否可生成放宽非空约束的语句，调用方需排除目标主键列
	 */
	private static boolean canDropColumnNotNull(DbType type, IntellijColumnInfo sourceColumn,
		IntellijColumnInfo targetColumn, String widenedType) {
		if (!Boolean.TRUE.equals(sourceColumn.getNullable()) || !Boolean.FALSE.equals(targetColumn.getNullable())
			|| !Objects.equals(sourceColumn.getColumnDefaultValue(), targetColumn.getColumnDefaultValue())) {
			return false;
		}
		String sourceType = migrationColumnType(type, sourceColumn);
		String targetType = Objects.nonNull(widenedType) ? widenedType : migrationColumnType(type, targetColumn);
		return Objects.nonNull(sourceType) && sourceType.equals(targetType);
	}

	/**
	 * 读取目标 SQLite 表的 STRICT 标记。无目录信息时按主库解析。
	 *
	 * @param connection 目标连接
	 * @param table 目标普通表
	 * @return 目标表是否要求严格类型
	 * @throws SQLException 无法读取表目录或目标表已不存在
	 * @see <a href="https://www.sqlite.org/pragma.html#pragma_table_list">SQLite PRAGMA table_list</a>
	 */
	private static boolean isStrictSqliteTable(Connection connection, JdbcTable table) throws SQLException {
		try (var query = connection.prepareStatement("SELECT strict FROM pragma_table_list WHERE schema = ? AND name = ?")) {
			query.setQueryTimeout(15);
			query.setString(1, JdbcTableSql.sqliteSchema(table));
			query.setString(2, table.name());
			try (var rows = query.executeQuery()) {
				if (!rows.next()) {
					throw new SQLException("SQLite table no longer exists: " + table.name());
				}
				return rows.getBoolean(1);
			}
		}
	}

	/**
	 * 统一普通表的 JDBC 类型别名。PostgreSQL 分区表的具体规则由分区比较报告。
	 *
	 * @param table JDBC 对象定位
	 * @param type 数据库方言
	 * @return 用于结构比较的对象类型
	 */
	private static String comparableTableKind(JdbcTable table, DbType type) {
		if (isBaseTable(table) || (type == DbType.PostgreSQL && "PARTITIONED TABLE".equalsIgnoreCase(table.type()))) {
			return "TABLE";
		}
		return Objects.toString(table.type(), "");
	}

	/**
	 * 判断目录内对象是否为普通表。
	 *
	 * @param table 对象信息
	 * @return 普通表时为 true
	 */
	private static boolean isBaseTable(JdbcTable table) {
		return "TABLE".equalsIgnoreCase(table.type()) || "BASE TABLE".equalsIgnoreCase(table.type());
	}
}
