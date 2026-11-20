package io.github.jinlongliao.mybatis.workbench.database;

import org.mybatis.generator.api.intellij.IntellijColumnInfo;
import org.mybatis.generator.api.intellij.IntellijTableInfo;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import io.github.jinlongliao.mybatis.workbench.model.DbType;
import io.github.jinlongliao.mybatis.workbench.model.GenerationTable;

/**
 * 将 JDBC 元数据转换为 MyBatis 生成器所需的表结构。
 *
 * @author liaojinlong
 * @since 2026/11/18 14:36
 */
public final class JdbcTableMetadata {
	/**
	 * 工具类无需实例化。
	 */
	private JdbcTableMetadata() {
	}

	/**
	 * 读取指定范围的表和视图清单，包含 H2 的 BASE TABLE 和 PostgreSQL 的物化视图。
	 *
	 * @param metadata 当前连接的元数据
	 * @param catalog  目录原名，空引用表示允许所有目录
	 * @param schema   模式原名，空引用表示允许所有模式
	 * @return 由驱动排序的对象清单
	 * @throws SQLException 驱动无法读取对象清单
	 */
	public static List<JdbcTable> listTables(DatabaseMetaData metadata, String catalog, String schema)
		throws SQLException {
		List<JdbcTable> tables = new ArrayList<>();
		String schemaPattern = escapeMetadataPattern(schema, metadata.getSearchStringEscape());
		// 仅 PostgreSQL 的 DDL 读取支持物化视图定义，其余驱动维持原有对象范围。
		String[] objectTypes = "PostgreSQL".equals(metadata.getDatabaseProductName())
			? new String[]{"TABLE", "BASE TABLE", "PARTITIONED TABLE", "VIEW", "MATERIALIZED VIEW"}
			: new String[]{"TABLE", "BASE TABLE", "PARTITIONED TABLE", "VIEW"};
		try (ResultSet rows = metadata.getTables(catalog, schemaPattern, "%", objectTypes)) {
			while (rows.next()) {
				tables.add(new JdbcTable(rows.getString("TABLE_CAT"), rows.getString("TABLE_SCHEM"),
					rows.getString("TABLE_NAME"), rows.getString("TABLE_TYPE"), rows.getString("REMARKS")));
			}
		}
		return List.copyOf(tables);
	}

	/**
	 * 读取已选表的结构。参数应来自同一连接的表清单，连接生命周期由调用方管理。
	 * 列查询使用 JDBC 模式转义，避免同名表或名称中包含通配符时串读。
	 *
	 * @param metadata 当前连接的元数据
	 * @param dbType   当前数据库的方言
	 * @param table    同一连接的表清单中选中的对象
	 * @return 独立于连接生命周期的表结构
	 * @throws SQLException 元数据不可读或表已不存在
	 */
	public static GenerationTable readGenerationTable(DatabaseMetaData metadata, DbType dbType, JdbcTable table)
		throws SQLException {
		if (dbType == DbType.SQLite) {
			assertSqliteMetadataUnshadowed(metadata, table);
		}
		String catalog = table.catalog();
		String schema = table.schema();
		String tableName = table.name();
		String escape = metadata.getSearchStringEscape();
		List<IntellijColumnInfo> columns = new ArrayList<>();
		try (ResultSet rows = metadata.getColumns(catalog, escapeMetadataPattern(schema, escape),
			escapeMetadataPattern(tableName, escape), null)) {
			while (rows.next()) {
				if (tableName.equals(rows.getString("TABLE_NAME"))
					&& Objects.equals(catalog, rows.getString("TABLE_CAT"))
					&& Objects.equals(schema, rows.getString("TABLE_SCHEM"))) {
					columns.add(readColumn(rows));
				}
			}
		}
		if (columns.isEmpty()) {
			throw new SQLException("JDBC metadata returned no columns for table " + tableName);
		}
		if (dbType == DbType.MySQL && columns.stream().anyMatch(column ->
			column.isGeneratedColumn() && Objects.nonNull(column.getColumnDefaultValue()))) {
			correctMySqlGeneratedColumnFlags(metadata, table, columns);
		}

		Map<String, IntellijColumnInfo> columnsByName = new HashMap<>(columns.size());
		for (IntellijColumnInfo column : columns) {
			columnsByName.put(column.getName(), column);
		}
		List<IntellijColumnInfo> primaryKeys = new ArrayList<>();
		try (ResultSet rows = metadata.getPrimaryKeys(catalog, schema, tableName)) {
			while (rows.next()) {
				String name = rows.getString("COLUMN_NAME");
				IntellijColumnInfo key = columnsByName.get(name);
				if (Objects.isNull(key)) {
					throw new SQLException("JDBC primary key metadata references an unknown column: " + name);
				}
				key.setKeySeq(rows.getShort("KEY_SEQ"));
				primaryKeys.add(key);
			}
		}
		// JDBC 按列名返回主键元数据，生成器需要约束定义中的列顺序。
		primaryKeys.sort(Comparator.comparingInt(IntellijColumnInfo::getKeySeq));

		IntellijTableInfo tableInfo = new IntellijTableInfo();
		tableInfo.setTableName(tableName);
		tableInfo.setDatabaseType(dbType.name());
		tableInfo.setTableType(table.type());
		tableInfo.setTableRemark(table.remarks());
		tableInfo.setColumnInfos(List.copyOf(columns));
		tableInfo.setPrimaryKeyColumns(List.copyOf(primaryKeys));

		String qualifier = Objects.nonNull(schema) && !schema.isEmpty() ? schema : catalog;
		return new GenerationTable(tableInfo, dbType, qualifier);
	}

	/**
	 * 区分驱动报告的表达式默认值和真正的生成列。仅修正 MySQL 目录明确标为表达式默认值的列，
	 * 使写入和生成器可以按普通列处理，同时保留虚拟和存储生成列的只读属性。
	 *
	 * @param metadata 当前连接的 JDBC 元数据
	 * @param table 当前连接上的对象
	 * @param columns 已完成 JDBC 映射的列，原地修正标记
	 * @throws SQLException 无法核对列的目录定义
	 * @see <a href="https://dev.mysql.com/doc/refman/8.4/en/information-schema-columns-table.html">MySQL COLUMNS</a>
	 */
	private static void correctMySqlGeneratedColumnFlags(DatabaseMetaData metadata, JdbcTable table,
														List<IntellijColumnInfo> columns) throws SQLException {
		Set<String> expressionDefaults = new HashSet<>();
		try (var statement = metadata.getConnection().prepareStatement("""
			SELECT COLUMN_NAME FROM information_schema.COLUMNS
			WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
			  AND LOCATE('DEFAULT_GENERATED', EXTRA) > 0
			""")) {
			statement.setQueryTimeout(15);
			statement.setString(1, Objects.requireNonNullElse(table.catalog(), table.schema()));
			statement.setString(2, table.name());
			try (var rows = statement.executeQuery()) {
				while (rows.next()) {
					expressionDefaults.add(rows.getString(1));
				}
			}
		}
		for (IntellijColumnInfo column : columns) {
			if (expressionDefaults.contains(column.getName())) {
				column.setGeneratedColumn(false);
			}
		}
	}

	/**
	 * 将列元数据映射为生成器字段，驱动未报告的可空性和列属性保持保守默认值。
	 *
	 * @param row 已定位的 JDBC 列结果行
	 * @return 列结构
	 * @throws SQLException 驱动无法读取列属性
	 */
	private static IntellijColumnInfo readColumn(ResultSet row) throws SQLException {
		IntellijColumnInfo column = new IntellijColumnInfo();
		column.setName(row.getString("COLUMN_NAME"));
		column.setDataType(row.getInt("DATA_TYPE"));
		column.setTypeName(row.getString("TYPE_NAME"));
		column.setSize(row.getInt("COLUMN_SIZE"));
		column.setDecimalDigits(row.getInt("DECIMAL_DIGITS"));
		column.setNullable(row.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
		column.setRemarks(row.getString("REMARKS"));
		column.setColumnDefaultValue(row.getString("COLUMN_DEF"));
		column.setAutoIncrement("YES".equals(row.getString("IS_AUTOINCREMENT")));
		column.setGeneratedColumn("YES".equals(row.getString("IS_GENERATEDCOLUMN")));
		return column;
	}

	/**
	 * 按驱动约定转义 JDBC 查询模式。无转义符的驱动仍由调用方做精确结果过滤。
	 *
	 * @param identifier 元数据中返回的名称，可以为空
	 * @param escape     驱动报告的转义符
	 * @return 用于 JDBC 元数据查询的模式
	 */
	private static String escapeMetadataPattern(String identifier, String escape) {
		if (Objects.isNull(identifier) || Objects.isNull(escape) || escape.isEmpty()) {
			return identifier;
		}
		return identifier.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
	}
	/**
	 * 拒绝读取被同名临时对象遮蔽的主库元数据。SQLite JDBC 的列查询在这种情况下可能返回临时表的列。
	 *
	 * @param metadata 当前 SQLite 连接的 JDBC 元数据
	 * @param table 待查询的表
	 * @throws SQLException 存在同名临时对象或无法读取临时目录
	 */
	private static void assertSqliteMetadataUnshadowed(DatabaseMetaData metadata, JdbcTable table) throws SQLException {
		if (!"main".equalsIgnoreCase(JdbcTableSql.sqliteSchema(table))) {
			return;
		}
		try (var query = metadata.getConnection().prepareStatement(
			"SELECT 1 FROM temp.sqlite_temp_master WHERE name = ? COLLATE NOCASE AND type IN ('table', 'view') LIMIT 1")) {
			query.setQueryTimeout(15);
			query.setString(1, table.name());
			try (var rows = query.executeQuery()) {
				if (rows.next()) {
					throw new SQLException("SQLite temporary object shadows main." + table.name());
				}
			}
		}
	}

}
