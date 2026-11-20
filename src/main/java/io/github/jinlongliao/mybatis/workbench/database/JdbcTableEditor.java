package io.github.jinlongliao.mybatis.workbench.database;

import static io.github.jinlongliao.mybatis.workbench.database.DatabaseMessages.databaseMessage;

import org.mybatis.generator.api.intellij.IntellijColumnInfo;
import org.mybatis.generator.api.intellij.IntellijTableInfo;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.function.Function;

import io.github.jinlongliao.mybatis.workbench.model.DbType;

/**
 * 依据当前连接的表元数据浏览和更新数据。保存使用主键定位并核对显示时的原值。
 *
 * @author liaojinlong
 * @since 2026/11/18 16:12
 */
public final class JdbcTableEditor {
	/**
	 * 在结果格展示的最大行数，读取额外一行判断是否还有数据。
	 */
	private static final int PREVIEW_ROWS = 100;

	/**
	 * 表数据操作不需要实例。
	 */
	private JdbcTableEditor() {
	}

	/**
	 * 有界读取标量列。存在可比较的主键时按主键排序并开放普通标量列编辑。
	 *
	 * @param database 当前专用连接
	 * @param type     JDBC 方言
	 * @param table    已选表或视图
	 * @return 原始数据预览及允许编辑的列
	 * @throws SQLException 读取元数据或查询失败
	 */
	public static JdbcTableRows previewTableRows(JdbcDriverConnection database, DbType type, JdbcTable table)
		throws SQLException {
		return previewTableRows(database, type, table, key -> databaseMessage(Locale.getDefault(), key));
	}

	/**
	 * 有界读取标量列。存在可比较的主键时按主键排序并开放普通标量列编辑。
	 *
	 * @param database 当前专用连接
	 * @param type     JDBC 方言
	 * @param table    已选表或视图
	 * @param messages 宿主提供的错误文案查询函数
	 * @return 原始数据预览及允许编辑的列
	 * @throws SQLException 读取元数据或查询失败
	 */
	public static JdbcTableRows previewTableRows(JdbcDriverConnection database, DbType type, JdbcTable table,
		Function<String, String> messages)
		throws SQLException {
		return previewTableRows(database, type, table, null, false, messages);
	}

	/**
	 * 从上一页末行的主键继续读取，表结构不一致时要求重新浏览。
	 *
	 * @param database 当前专用连接
	 * @param type     JDBC 方言
	 * @param previous 上一次读取的页面
	 * @return 后续数据页
	 * @throws SQLException 无法续读、表结构变化或查询失败
	 */
	public static JdbcTableRows readNextTableRows(JdbcDriverConnection database, DbType type, JdbcTableRows previous)
		throws SQLException {
		return readNextTableRows(database, type, previous, key -> databaseMessage(Locale.getDefault(), key));
	}

	/**
	 * 从上一页末行的主键继续读取，表结构不一致时要求重新浏览。
	 *
	 * @param database 当前专用连接
	 * @param type     JDBC 方言
	 * @param previous 上一次读取的页面
	 * @param messages 宿主提供的错误文案查询函数
	 * @return 后续数据页
	 * @throws SQLException 无法续读、表结构变化或查询失败
	 */
	public static JdbcTableRows readNextTableRows(JdbcDriverConnection database, DbType type, JdbcTableRows previous,
		Function<String, String> messages)
		throws SQLException {
		if (!previous.hasNextPage()) {
			throw new SQLException(messages.apply("table.page.no.next"));
		}
		return previewTableRows(database, type, previous.table(), previous, false, messages);
	}

	/**
	 * 以当前页首行作为边界重新读取靠前的数据。
	 *
	 * @param database 当前专用连接
	 * @param type     JDBC 方言
	 * @param current  正显示的页面
	 * @return 按正序展示的上一页
	 * @throws SQLException 当前无上一页、表结构变化或查询失败
	 */
	public static JdbcTableRows readPreviousTableRows(JdbcDriverConnection database, DbType type, JdbcTableRows current)
		throws SQLException {
		return readPreviousTableRows(database, type, current, key -> databaseMessage(Locale.getDefault(), key));
	}

	/**
	 * 以当前页首行作为边界重新读取靠前的数据。
	 *
	 * @param database 当前专用连接
	 * @param type     JDBC 方言
	 * @param current  正显示的页面
	 * @param messages 宿主提供的错误文案查询函数
	 * @return 按正序展示的上一页
	 * @throws SQLException 当前无上一页、表结构变化或查询失败
	 */
	public static JdbcTableRows readPreviousTableRows(JdbcDriverConnection database, DbType type, JdbcTableRows current,
		Function<String, String> messages)
		throws SQLException {
		if (!current.hasPreviousPage()) {
			throw new SQLException(messages.apply("table.page.no.previous"));
		}
		return previewTableRows(database, type, current.table(), current, true, messages);
	}

	/**
	 * 构建有界预览 SQL，续读时按复合主键的字典序绑定等值和范围条件。
	 *
	 * @param database 当前专用连接
	 * @param type     JDBC 方言
	 * @param table    已选表或视图
	 * @param previous 正显示的页面，首次预览为空
	 * @param backward 是否从首行逆序读取
	 * @param messages 宿主提供的错误文案查询函数
	 * @return 原值预览及编辑、续读状态
	 * @throws SQLException 读取元数据失败、主键变化或查询失败
	 */
	private static JdbcTableRows previewTableRows(JdbcDriverConnection database, DbType type, JdbcTable table,
												  JdbcTableRows previous, boolean backward,
		Function<String, String> messages) throws SQLException {
		var metadata = database.connection().getMetaData();
		IntellijTableInfo info = JdbcTableMetadata.readGenerationTable(metadata, type, table).tableInfo();
		List<String> columns = info.getColumnInfos().stream().filter(JdbcTableEditor::isScalarColumn)
			.map(IntellijColumnInfo::getName).toList();
		if (columns.isEmpty()) {
			throw new SQLException(messages.apply("table.page.no.scalar.columns"));
		}
		List<IntellijColumnInfo> keys = info.getPrimaryKeyColumns();
		boolean writable = isBaseTable(table) && !keys.isEmpty() && keys.stream().allMatch(JdbcTableEditor::isScalarColumn);
		String quote = metadata.getIdentifierQuoteString();
		List<String> projected = new ArrayList<>(columns.size());
		for (String column : columns) {
			projected.add(JdbcTableSql.quoteIdentifier(column, quote));
		}
		StringBuilder sql = new StringBuilder(type == DbType.SqlServer ? "SELECT TOP (101) " : "SELECT ");
		sql.append(String.join(", ", projected)).append(" FROM ")
			.append(JdbcTableSql.qualifiedTableName(type, table, quote));
		boolean canContinue = writable && keys.stream().allMatch(key -> Boolean.FALSE.equals(key.getNullable()));
		var keyNames = keys.stream().map(IntellijColumnInfo::getName).toList();
		List<String> boundary = new ArrayList<>(keys.size());
		if (Objects.nonNull(previous)) {
			if (!canContinue || !keyNames.equals(previous.primaryKeys())) {
				throw new SQLException(messages.apply("table.page.primary.key.changed"));
			}
			var existing = previous.result().rows();
			var boundaryRow = backward ? existing.getFirst() : existing.getLast();
			for (String name : keyNames) {
				String value = boundaryRow.get(previous.result().columnLabels().indexOf(name));
				if (Objects.isNull(value)) {
					throw new SQLException(messages.apply("table.page.primary.key.incomplete"));
				}
				boundary.add(value);
			}
		}
		String comparison = backward ? " < " : " > ";
		boolean tupleBoundary = (type == DbType.SQLite || type == DbType.PostgreSQL) && !boundary.isEmpty();
		if (writable) {
			List<String> order = new ArrayList<>(keys.size());
			for (IntellijColumnInfo key : keys) {
				order.add(JdbcTableSql.quoteIdentifier(key.getName(), quote));
			}
			if (tupleBoundary) {
				// SQLite 和 PostgreSQL 可将行值比较直接用作复合索引范围，避免逐行过滤起点以前的记录。
				String placeholders = String.join(", ", Collections.nCopies(order.size(), "?"));
				sql.append(" WHERE (").append(String.join(", ", order)).append(')').append(comparison).append('(').append(placeholders).append(')');
			} else if (!boundary.isEmpty()) {
				List<String> ranges = new ArrayList<>(order.size());
				List<String> equalities = new ArrayList<>(order.size());
				for (String key : order) {
					String prefix = equalities.isEmpty() ? "" : String.join(" AND ", equalities) + " AND ";
					ranges.add("(" + prefix + key + comparison + "?)");
					equalities.add(key + " = ?");
				}
				sql.append(" WHERE ").append(String.join(" OR ", ranges));
			}
			if (backward) {
				order.replaceAll(key -> key + " DESC");
			}
			sql.append(" ORDER BY ").append(String.join(", ", order));
		}
		switch (type) {
			case PostgreSQL, MySQL, MariaDB, SQLite, H2 -> sql.append(" LIMIT ").append(PREVIEW_ROWS + 1);
			case Oracle -> sql.append(" FETCH FIRST ").append(PREVIEW_ROWS + 1).append(" ROWS ONLY");
			case SqlServer -> {
				// 行数已经通过 TOP 限制。
			}
		}
		List<String> editable = writable ? info.getColumnInfos().stream()
			.filter(column -> isScalarColumn(column) && !column.isGeneratedColumn() && !column.isAutoIncrement()
				&& keys.stream().noneMatch(key -> key.getName().equals(column.getName())))
			.map(IntellijColumnInfo::getName).toList() : List.of();
		JdbcQueryResult result;
		try (var statement = database.connection().prepareStatement(sql.toString())) {
			statement.setQueryTimeout(15);
			int parameter = 1;
			if (tupleBoundary) {
				for (int key = 0; key < boundary.size(); key++) {
					statement.setObject(parameter++, boundary.get(key), keys.get(key).getDataType());
				}
			} else {
				for (int last = 0; last < boundary.size(); last++) {
					for (int key = 0; key <= last; key++) {
						statement.setObject(parameter++, boundary.get(key), keys.get(key).getDataType());
					}
				}
			}
			try (var rows = statement.executeQuery()) {
				result = JdbcDriverConnection.readSqlResult(rows, PREVIEW_ROWS);
			}
		}
		boolean readRows = !result.rows().isEmpty();
		boolean hasPrevious = canContinue && readRows && (backward ? result.truncated() : Objects.nonNull(previous));
		boolean hasNext = canContinue && readRows && (backward || result.truncated());
		if (backward) {
			result = new JdbcQueryResult(result.columnLabels(), List.copyOf(result.rows().reversed()),
				result.truncated(), result.updateCount());
		}
		return new JdbcTableRows(table, result, keyNames, editable, hasNext, hasPrevious);
	}

	/**
	 * 在独占连接上更新一个标量列。按完整主键锁定原行并检查选中单元格原值，
	 * 只有恰好一行匹配才提交。SQLite 的同一事务读快照随后写入，冲突由驱动拒绝。
	 *
	 * @param connection 当前自动提交的独占连接
	 * @param type       JDBC 方言
	 * @param page       由当前连接配置读取的原始页面
	 * @param rowIndex   预览结果中的行序号，从 0 开始
	 * @param columnName 要编辑的非主键列
	 * @param newValue   新值，空引用表示 SQL NULL
	 * @return 提交前在同一事务中读取的实际存储值
	 * @throws SQLException 数据已修改、目标表或列变化、转换错误或写入失败
	 */
	public static String updateTableCell(Connection connection, DbType type, JdbcTableRows page, int rowIndex,
										 String columnName, String newValue) throws SQLException {
		ResourceBundle bundle = ResourceBundle.getBundle("messages.WorkbenchBundle");
		return updateTableCell(connection, type, page, rowIndex, columnName, newValue, bundle::getString);
	}

	/**
	 * 使用宿主语言报告单元格保存错误。仍在同一事务核对原值、更新并读回。
	 *
	 * @param connection 本次编辑独占的自动提交连接
	 * @param type 当前数据库方言
	 * @param page 读取时的页面快照
	 * @param rowIndex 预览结果中的行序号，从 0 开始
	 * @param columnName 要更新的普通列
	 * @param newValue 新值，空引用表示 SQL NULL
	 * @param messages 以宿主语言解析编辑错误
	 * @return 提交前读回的实际存储值
	 * @throws SQLException 目标变化、转换错误或写入失败
	 */
	public static String updateTableCell(Connection connection, DbType type, JdbcTableRows page, int rowIndex,
										 String columnName, String newValue, Function<String, String> messages)
		throws SQLException {
		if (!connection.getAutoCommit() || !connection.getMetaData().supportsTransactions()
			|| !isBaseTable(page.table()) || !page.editableColumns().contains(columnName)) {
			throw new SQLException(messages.apply("table.edit.requires.transactional.table"));
		}
		var metadata = connection.getMetaData();
		var info = JdbcTableMetadata.readGenerationTable(metadata, type, page.table()).tableInfo();
		var keys = info.getPrimaryKeyColumns();
		var column = info.getColumnInfos().stream().filter(item -> columnName.equals(item.getName()))
			.findFirst().orElseThrow(() -> new SQLException(messages.apply("table.edit.missing.column")));
		if (keys.isEmpty() || !keys.stream().map(IntellijColumnInfo::getName).toList().equals(page.primaryKeys())
			|| !isScalarColumn(column) || column.isGeneratedColumn() || column.isAutoIncrement()
			|| keys.stream().anyMatch(key -> key.getName().equals(columnName))) {
			throw new SQLException(messages.apply("table.edit.structure.changed"));
		}
		List<String> original = page.result().rows().get(rowIndex);
		String oldValue = original.get(page.result().columnLabels().indexOf(columnName));
		String quote = metadata.getIdentifierQuoteString();
		String table = JdbcTableSql.qualifiedTableName(type, page.table(), quote);
		String target = JdbcTableSql.quoteIdentifier(columnName, quote);
		List<String> predicates = new ArrayList<>(keys.size());
		List<String> quotedKeys = new ArrayList<>(keys.size());
		List<String> keyValues = new ArrayList<>(keys.size());
		for (IntellijColumnInfo key : keys) {
			int position = page.result().columnLabels().indexOf(key.getName());
			if (position < 0 || !isScalarColumn(key) || Objects.isNull(original.get(position))) {
				throw new SQLException(messages.apply("table.edit.missing.primary.key"));
			}
			String quotedKey = JdbcTableSql.quoteIdentifier(key.getName(), quote);
			predicates.add(quotedKey + " = ?");
			quotedKeys.add(quotedKey);
			keyValues.add(original.get(position));
		}
		String where = " WHERE " + String.join(" AND ", predicates);
		String lockQuery = "SELECT " + target + ", " + String.join(", ", quotedKeys) + " FROM " + table + (type == DbType.SqlServer ? " WITH (UPDLOCK, HOLDLOCK)" : "")
			+ where + (type == DbType.SQLite || type == DbType.SqlServer ? "" : " FOR UPDATE");
		connection.setAutoCommit(false);
		try {
			JdbcTableSql.assertTransactionalTable(connection, type, page.table(),
				"SELECT " + target + " FROM " + table + " WHERE 1 = 0");
			try (var lock = connection.prepareStatement(lockQuery)) {
				lock.setQueryTimeout(15);
				for (int index = 0; index < keys.size(); index++) {
					lock.setObject(index + 1, keyValues.get(index), keys.get(index).getDataType());
				}
				try (var rows = lock.executeQuery()) {
					if (!rows.next() || !Objects.equals(oldValue, rows.getString(1))) {
						throw new SQLException(messages.apply("table.edit.original.value.changed"));
					}
					for (int index = 0; index < keys.size(); index++) {
						if (!Objects.equals(keyValues.get(index), rows.getString(index + 2))) {
							throw new SQLException(messages.apply("table.edit.primary.key.changed"));
						}
					}
					if (rows.next()) {
						throw new SQLException(messages.apply("table.edit.multiple.rows"));
					}
				}
			}
			try (var update = connection.prepareStatement("UPDATE " + table + " SET " + target + " = ?" + where)) {
				update.setQueryTimeout(15);
				if (Objects.isNull(newValue)) {
					update.setNull(1, column.getDataType());
				} else {
					update.setObject(1, newValue, column.getDataType());
				}
				for (int index = 0; index < keys.size(); index++) {
					update.setObject(index + 2, keyValues.get(index), keys.get(index).getDataType());
				}
				if (update.executeUpdate() != 1) {
					throw new SQLException(messages.apply("table.edit.unexpected.update.count"));
				}
			}
			String storedValue;
			try (var read = connection.prepareStatement("SELECT " + target + " FROM " + table + where)) {
				read.setQueryTimeout(15);
				for (int index = 0; index < keys.size(); index++) {
					read.setObject(index + 1, keyValues.get(index), keys.get(index).getDataType());
				}
				try (var rows = read.executeQuery()) {
					if (!rows.next()) {
						throw new SQLException(messages.apply("table.edit.readback.failed"));
					}
					storedValue = rows.getString(1);
				}
			}
			connection.commit();
			return storedValue;
		} catch (SQLException | RuntimeException failure) {
			try {
				connection.rollback();
			} catch (SQLException rollbackFailure) {
				failure.addSuppressed(rollbackFailure);
			}
			throw failure;
		}
	}

	/**
	 * 判断 JDBC 对象是否为可以编辑的普通表。
	 *
	 * @param table 对象元数据
	 * @return 普通表时为 true
	 */
	private static boolean isBaseTable(JdbcTable table) {
		return "TABLE".equalsIgnoreCase(table.type()) || "BASE TABLE".equalsIgnoreCase(table.type());
	}

	/**
	 * 仅接受可经 JDBC 字符串表示且无需加载 LOB 或供应方对象的列。
	 *
	 * @param column 数据库元数据中的列
	 * @return 支持当前浏览和绑定时为 true
	 */
	private static boolean isScalarColumn(IntellijColumnInfo column) {
		return switch (column.getDataType()) {
			case Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR, Types.BIGINT, Types.INTEGER, Types.SMALLINT,
				 Types.TINYINT, Types.DECIMAL, Types.NUMERIC, Types.REAL, Types.FLOAT, Types.DOUBLE, Types.BOOLEAN,
				 Types.BIT, Types.DATE, Types.TIME, Types.TIMESTAMP -> true;
			default -> false;
		};
	}
}
