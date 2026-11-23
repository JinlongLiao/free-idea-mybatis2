package io.github.jinlongliao.mybatis.workbench.database;

import static io.github.jinlongliao.mybatis.workbench.database.DatabaseMessages.databaseMessage;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import org.mybatis.generator.api.intellij.IntellijColumnInfo;
import org.mybatis.generator.api.intellij.IntellijTableInfo;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.function.Function;

import io.github.jinlongliao.mybatis.workbench.model.DbType;

/**
 * 依据当前连接的表元数据浏览、筛选、排序和安全维护表数据。写入操作会比较读取快照并限制目标行。
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
		return previewTableRows(database, type, table, JdbcTableQueryOptions.none(), messages);
	}

	/**
	 * 使用元数据列白名单构建有界 AND 服务端筛选和单列排序，并继续有界读取首页。
	 * 空筛选仍只读取固定的首页行数，不执行无界结果查询。
	 *
	 * @param database 当前专用连接
	 * @param type JDBC 方言
	 * @param table 已选表或视图
	 * @param options 有界 AND 筛选与单列排序设置
	 * @param messages 宿主提供的错误文案查询函数
	 * @return 原值预览、可编辑列、分页标志及查询设置
	 * @throws SQLException 元数据无效、查询设置不受支持或数据库查询失败
	 */
	public static JdbcTableRows previewTableRows(JdbcDriverConnection database, DbType type, JdbcTable table,
		JdbcTableQueryOptions options, Function<String, String> messages) throws SQLException {
		return previewTableRows(database, type, table, null, false, options, messages);
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
		return previewTableRows(database, type, previous.table(), previous, false, previous.queryOptions(), messages);
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
		return previewTableRows(database, type, current.table(), current, true, current.queryOptions(), messages);
	}

	/**
	 * 按当前表结构白名单新增一行。JSON 中省略的列继续使用数据库默认值，JSON null 写入 SQL NULL。
	 * 主键可显式提供；自增列和生成列始终由数据库计算。失败时回滚整个事务。
	 *
	 * @param connection 当前操作独占的自动提交连接
	 * @param type 当前数据库方言
	 * @param page 用户确认操作时的表页快照
	 * @param json 含列名和值的 JSON 对象
	 * @param messages 宿主语言错误提示
	 * @throws SQLException 表结构已变更、字段无效、约束拒绝或写入失败
	 */
	public static void insertTableRow(Connection connection, DbType type, JdbcTableRows page, String json,
		Function<String, String> messages) throws SQLException {
		if (!connection.getAutoCommit() || !connection.getMetaData().supportsTransactions() || !isBaseTable(page.table())
			|| page.primaryKeys().isEmpty()) {
			throw new SQLException(messages.apply("table.row.insert.requires.transactional.table"));
		}
		JsonElement parsed;
		try {
			parsed = JsonParser.parseString(json);
		} catch (RuntimeException failure) {
			throw new SQLException(messages.apply("table.row.insert.invalid.json"), failure);
		}
		if (!parsed.isJsonObject() || parsed.getAsJsonObject().entrySet().isEmpty()) {
			throw new SQLException(messages.apply("table.row.insert.empty"));
		}
		if (parsed.getAsJsonObject().entrySet().size() > 1000) {
			throw new SQLException(messages.apply("table.row.insert.too.many.columns"));
		}
		var metadata = connection.getMetaData();
		var info = JdbcTableMetadata.readGenerationTable(metadata, type, page.table()).tableInfo();
		List<IntellijColumnInfo> keys = info.getPrimaryKeyColumns();
		List<String> keyNames = keys.stream().map(IntellijColumnInfo::getName).toList();
		List<String> currentInsertable = info.getColumnInfos().stream()
			.filter(column -> isScalarColumn(column) && !column.isGeneratedColumn() && !column.isAutoIncrement())
			.map(IntellijColumnInfo::getName).toList();
		if (!keyNames.equals(page.primaryKeys()) || !currentInsertable.equals(page.insertableColumns())) {
			throw new SQLException(messages.apply("table.edit.structure.changed"));
		}
		Map<String, IntellijColumnInfo> columns = new HashMap<>(info.getColumnInfos().size());
		for (IntellijColumnInfo column : info.getColumnInfos()) {
			columns.put(column.getName(), column);
		}
		List<IntellijColumnInfo> selectedColumns = new ArrayList<>();
		List<String> values = new ArrayList<>();
		List<Boolean> nullValues = new ArrayList<>();
		for (var entry : parsed.getAsJsonObject().entrySet()) {
			IntellijColumnInfo column = columns.get(entry.getKey());
			if (Objects.isNull(column) || !page.insertableColumns().contains(entry.getKey()) || !isScalarColumn(column)) {
				throw new SQLException(messages.apply("table.row.insert.invalid.column"));
			}
			JsonElement value = entry.getValue();
			if (!value.isJsonNull() && !value.isJsonPrimitive()) {
				throw new SQLException(messages.apply("table.row.insert.invalid.value"));
			}
			selectedColumns.add(column);
			nullValues.add(value.isJsonNull());
			values.add(value.isJsonNull() ? null : value.getAsString());
		}
		String quote = metadata.getIdentifierQuoteString();
		String table = JdbcTableSql.qualifiedTableName(type, page.table(), quote);
		List<String> identifiers = new ArrayList<>(selectedColumns.size());
		for (IntellijColumnInfo column : selectedColumns) {
			identifiers.add(JdbcTableSql.quoteIdentifier(column.getName(), quote));
		}
		String placeholders = String.join(", ", Collections.nCopies(selectedColumns.size(), "?"));
		String sql = "INSERT INTO " + table + " (" + String.join(", ", identifiers) + ") VALUES (" + placeholders + ")";
		connection.setAutoCommit(false);
		try {
			JdbcTableSql.assertTransactionalTable(connection, type, page.table(),
				"SELECT " + identifiers.getFirst() + " FROM " + table + " WHERE 1 = 0");
			try (var insert = connection.prepareStatement(sql)) {
				insert.setQueryTimeout(15);
				for (int index = 0; index < selectedColumns.size(); index++) {
					int jdbcType = selectedColumns.get(index).getDataType();
					if (nullValues.get(index)) {
						insert.setNull(index + 1, jdbcType);
					} else {
						insert.setObject(index + 1, values.get(index), jdbcType);
					}
				}
				if (insert.executeUpdate() != 1) {
					throw new SQLException(messages.apply("table.row.insert.unexpected.count"));
				}
			}
			connection.commit();
			connection.setAutoCommit(true);
		} catch (SQLException | RuntimeException failure) {
			rollbackAndRestoreAutoCommit(connection, failure);
			throw failure;
		}
	}

	/**
	 * 按所选行的完整标量快照和主键执行单行删除，数据库中任何字段已变化时拒绝删除。
	 * 比较谓词中的数据值全部绑定；列过多导致比较参数超过安全上限时不执行写入。
	 *
	 * @param connection 当前操作独占的自动提交连接
	 * @param type 当前数据库方言
	 * @param page 用户确认操作时的表页快照
	 * @param rowIndex 结果页模型行索引
	 * @param messages 宿主语言错误提示
	 * @throws SQLException 表结构或原始行已变更、目标不唯一或数据库拒绝写入
	 */
	public static void deleteTableRow(Connection connection, DbType type, JdbcTableRows page, int rowIndex,
		Function<String, String> messages) throws SQLException {
		if (!connection.getAutoCommit() || !connection.getMetaData().supportsTransactions() || !isBaseTable(page.table())
			|| page.primaryKeys().isEmpty()) {
			throw new SQLException(messages.apply("table.row.delete.requires.transactional.table"));
		}
		var metadata = connection.getMetaData();
		var info = JdbcTableMetadata.readGenerationTable(metadata, type, page.table()).tableInfo();
		List<IntellijColumnInfo> keys = info.getPrimaryKeyColumns();
		if (!keys.stream().map(IntellijColumnInfo::getName).toList().equals(page.primaryKeys())) {
			throw new SQLException(messages.apply("table.edit.structure.changed"));
		}
		List<IntellijColumnInfo> comparedColumns = info.getColumnInfos().stream().filter(JdbcTableEditor::isScalarColumn).toList();
		if (comparedColumns.isEmpty() || comparedColumns.size() != info.getColumnInfos().size() || comparedColumns.size() > 1000
			|| !comparedColumns.stream().map(IntellijColumnInfo::getName).toList().equals(page.result().columnLabels())
			|| rowIndex < 0 || rowIndex >= page.result().rows().size()) {
			throw new SQLException(messages.apply(comparedColumns.size() != info.getColumnInfos().size()
				? "table.row.delete.unsupported.columns" : "table.row.delete.structure.changed"));
		}
		List<String> original = page.result().rows().get(rowIndex);
		if (keys.stream().anyMatch(key -> {
			int position = page.result().columnLabels().indexOf(key.getName());
			return position < 0 || Objects.isNull(original.get(position));
		})) {
			throw new SQLException(messages.apply("table.edit.missing.primary.key"));
		}
		List<String> predicates = new ArrayList<>(comparedColumns.size());
		List<Object> values = new ArrayList<>(comparedColumns.size());
		List<Integer> types = new ArrayList<>(comparedColumns.size());
		String quote = metadata.getIdentifierQuoteString();
		for (IntellijColumnInfo column : comparedColumns) {
			String identifier = JdbcTableSql.quoteIdentifier(column.getName(), quote);
			String value = original.get(page.result().columnLabels().indexOf(column.getName()));
			if (Objects.isNull(value)) {
				predicates.add(identifier + " IS NULL");
			} else {
				predicates.add(identifier + " = ?");
				values.add(value);
				types.add(column.getDataType());
			}
		}
		String table = JdbcTableSql.qualifiedTableName(type, page.table(), quote);
		connection.setAutoCommit(false);
		try {
			JdbcTableSql.assertTransactionalTable(connection, type, page.table(),
				"SELECT " + JdbcTableSql.quoteIdentifier(comparedColumns.getFirst().getName(), quote)
					+ " FROM " + table + " WHERE 1 = 0");
			try (var delete = connection.prepareStatement("DELETE FROM " + table + " WHERE " + String.join(" AND ", predicates))) {
				delete.setQueryTimeout(15);
				for (int index = 0; index < values.size(); index++) {
					delete.setObject(index + 1, values.get(index), types.get(index));
				}
				if (delete.executeUpdate() != 1) {
					throw new SQLException(messages.apply("table.row.delete.original.value.changed"));
				}
			}
			connection.commit();
			connection.setAutoCommit(true);
		} catch (SQLException | RuntimeException failure) {
			rollbackAndRestoreAutoCommit(connection, failure);
			throw failure;
		}
	}

	/**
	 * 构建有界预览 SQL，续读时按复合主键的字典序绑定等值和范围条件。
	 *
	 * @param database 当前专用连接
	 * @param type     JDBC 方言
	 * @param table    已选表或视图
	 * @param previous 正显示的页面，首次预览为空
	 * @param backward 是否从首行逆序读取
	 * @param options 服务端筛选和排序设置；续读时必须与页面快照一致
	 * @param messages 宿主提供的错误文案查询函数
	 * @return 原值预览及编辑、续读状态
	 * @throws SQLException 读取元数据失败、主键变化或查询失败
	 */
	private static JdbcTableRows previewTableRows(JdbcDriverConnection database, DbType type, JdbcTable table,
							  JdbcTableRows previous, boolean backward, JdbcTableQueryOptions options,
		Function<String, String> messages) throws SQLException {
		var metadata = database.connection().getMetaData();
		IntellijTableInfo info = JdbcTableMetadata.readGenerationTable(metadata, type, table).tableInfo();
		var scalarColumns = info.getColumnInfos().stream().filter(JdbcTableEditor::isScalarColumn).toList();
		List<String> columns = scalarColumns.stream().map(IntellijColumnInfo::getName).toList();
		if (columns.isEmpty()) {
			throw new SQLException(messages.apply("table.page.no.scalar.columns"));
		}
		List<IntellijColumnInfo> keys = info.getPrimaryKeyColumns();
		boolean writable = isBaseTable(table) && !keys.isEmpty() && keys.stream().allMatch(JdbcTableEditor::isScalarColumn);
		JdbcTableQueryOptions queryOptions = Objects.isNull(options) ? JdbcTableQueryOptions.none() : options;
		if (queryOptions.filters().size() > JdbcTableQueryOptions.MAX_FILTER_CONDITIONS) {
			throw new SQLException(messages.apply("table.filter.conditions.bounds"));
		}
		int filterParameterCount = 0;
		List<IntellijColumnInfo> filterColumns = new ArrayList<>(queryOptions.filters().size());
		for (JdbcTableFilterCondition filter : queryOptions.filters()) {
			IntellijColumnInfo filterColumn = findScalarColumn(scalarColumns, filter.column());
			if (Objects.isNull(filterColumn)) {
				throw new SQLException(messages.apply("table.filter.invalid.column"));
			}
			if (Objects.isNull(filter.operator())) {
				throw new SQLException(messages.apply("table.filter.invalid.operator"));
			}
			if (filter.operator() == JdbcTableFilterOperator.IN
				&& (filter.values().isEmpty() || filter.values().size() > JdbcTableQueryOptions.MAX_IN_VALUES)) {
				throw new SQLException(messages.apply("table.filter.in.bounds"));
			}
			if (filter.operator() != JdbcTableFilterOperator.IN && !filter.values().isEmpty()) {
				throw new SQLException(messages.apply("table.filter.invalid.operator"));
			}
			if (filter.operator() == JdbcTableFilterOperator.PREFIX_LIKE && !isTextColumn(filterColumn)) {
				throw new SQLException(messages.apply("table.filter.like.requires.text.column"));
			}
			filterParameterCount += filter.operator() == JdbcTableFilterOperator.IN
				? filter.values().size() : filter.operator().requiresValue() ? 1 : 0;
			filterColumns.add(filterColumn);
		}
		if (filterParameterCount > JdbcTableQueryOptions.MAX_FILTER_PARAMETERS) {
			throw new SQLException(messages.apply("table.filter.parameters.bounds"));
		}
		if (queryOptions.hasSort()) {
			IntellijColumnInfo sortColumn = findScalarColumn(scalarColumns, queryOptions.sortColumn());
			if (Objects.isNull(sortColumn) || !Boolean.FALSE.equals(sortColumn.getNullable())) {
				throw new SQLException(messages.apply("table.sort.requires.non.null.scalar.column"));
			}
		}
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
		List<IntellijColumnInfo> orderColumns = new ArrayList<>();
		if (queryOptions.hasSort()) {
			orderColumns.add(findScalarColumn(scalarColumns, queryOptions.sortColumn()));
		}
		for (IntellijColumnInfo key : keys) {
			if (orderColumns.stream().noneMatch(column -> column.getName().equals(key.getName()))) {
				orderColumns.add(key);
			}
		}
		List<String> boundary = new ArrayList<>(orderColumns.size());
		if (Objects.nonNull(previous)) {
			if (!canContinue || !keyNames.equals(previous.primaryKeys()) || !queryOptions.equals(previous.queryOptions())) {
				throw new SQLException(messages.apply("table.page.primary.key.changed"));
			}
			var existing = previous.result().rows();
			var boundaryRow = backward ? existing.getFirst() : existing.getLast();
			for (IntellijColumnInfo orderColumn : orderColumns) {
				String name = orderColumn.getName();
				String value = boundaryRow.get(previous.result().columnLabels().indexOf(name));
				if (Objects.isNull(value)) {
					throw new SQLException(messages.apply("table.page.primary.key.incomplete"));
				}
				boundary.add(value);
			}
		}
		List<Object> parameterValues = new ArrayList<>();
		List<Integer> parameterTypes = new ArrayList<>();
		List<String> where = new ArrayList<>();
		for (int index = 0; index < queryOptions.filters().size(); index++) {
			JdbcTableFilterCondition filter = queryOptions.filters().get(index);
			IntellijColumnInfo filterColumn = filterColumns.get(index);
			String filterIdentifier = JdbcTableSql.quoteIdentifier(filterColumn.getName(), quote);
			if (filter.operator() == JdbcTableFilterOperator.IN) {
				String placeholders = String.join(", ", Collections.nCopies(filter.values().size(), "?"));
				where.add(filterIdentifier + " IN (" + placeholders + ")");
				for (String filterValue : filter.values()) {
					parameterValues.add(filterValue);
					parameterTypes.add(filterColumn.getDataType());
				}
			} else {
				where.add(filterIdentifier + " " + filter.operator().sql());
				if (filter.operator().requiresValue()) {
					where.set(where.size() - 1, where.getLast() + " ?");
					parameterValues.add(filter.value());
					parameterTypes.add(filterColumn.getDataType());
				}
			}
		}
		if (!boundary.isEmpty()) {
			boolean descending = queryOptions.hasSort() && queryOptions.sortDescending();
			boolean tupleBoundary = (type == DbType.SQLite || type == DbType.PostgreSQL)
				&& (!descending || orderColumns.size() == 1);
			boolean greater = (backward == descending);
			if (tupleBoundary) {
				List<String> order = new ArrayList<>(orderColumns.size());
				for (IntellijColumnInfo orderColumn : orderColumns) {
					order.add(JdbcTableSql.quoteIdentifier(orderColumn.getName(), quote));
				}
				String placeholders = String.join(", ", Collections.nCopies(order.size(), "?"));
				where.add("(" + String.join(", ", order) + ")" + (greater ? " > " : " < ")
					+ "(" + placeholders + ")");
				for (int index = 0; index < orderColumns.size(); index++) {
					parameterValues.add(boundary.get(index));
					parameterTypes.add(orderColumns.get(index).getDataType());
				}
			} else {
				List<String> ranges = new ArrayList<>(orderColumns.size());
				for (int last = 0; last < orderColumns.size(); last++) {
					List<String> terms = new ArrayList<>(last + 1);
					for (int prefix = 0; prefix <= last; prefix++) {
						IntellijColumnInfo orderColumn = orderColumns.get(prefix);
						String identifier = JdbcTableSql.quoteIdentifier(orderColumn.getName(), quote);
						boolean termDescending = prefix == 0 && descending;
						boolean termGreater = (backward == termDescending);
						terms.add(identifier + (prefix == last ? (termGreater ? " > ?" : " < ?") : " = ?"));
						parameterValues.add(boundary.get(prefix));
						parameterTypes.add(orderColumn.getDataType());
					}
					ranges.add("(" + String.join(" AND ", terms) + ")");
				}
				where.add("(" + String.join(" OR ", ranges) + ")");
			}
		}
		if (!where.isEmpty()) {
			sql.append(" WHERE ").append(String.join(" AND ", where));
		}
		if (!orderColumns.isEmpty()) {
			List<String> order = new ArrayList<>(orderColumns.size());
			for (int index = 0; index < orderColumns.size(); index++) {
				IntellijColumnInfo orderColumn = orderColumns.get(index);
				boolean descending = index == 0 && queryOptions.hasSort() && queryOptions.sortDescending();
				if (backward) {
					descending = !descending;
				}
				order.add(JdbcTableSql.quoteIdentifier(orderColumn.getName(), quote) + (descending ? " DESC" : " ASC"));
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
		List<String> insertable = writable ? info.getColumnInfos().stream()
			.filter(column -> isScalarColumn(column) && !column.isGeneratedColumn() && !column.isAutoIncrement())
			.map(IntellijColumnInfo::getName).toList() : List.of();
		JdbcQueryResult result = database.executePreparedQuery(sql.toString(), parameterValues, parameterTypes,
			PREVIEW_ROWS, 15);
		boolean readRows = !result.rows().isEmpty();
		boolean hasPrevious = canContinue && readRows && (backward ? result.truncated() : Objects.nonNull(previous));
		boolean hasNext = canContinue && readRows && (backward || result.truncated());
		if (backward) {
			result = new JdbcQueryResult(result.columnLabels(), List.copyOf(result.rows().reversed()),
				result.truncated(), result.updateCount());
		}
		return new JdbcTableRows(table, result, scalarColumns.stream().map(IntellijColumnInfo::getDataType).toList(),
			keyNames, editable, hasNext, hasPrevious, queryOptions, insertable);
	}

	/**
	 * 从元数据标量列中精确查找用户选择的列，所有表单标识符必须经过此白名单。
	 *
	 * @param columns 当前表的标量列
	 * @param name 表单提供的列名
	 * @return 匹配列或空
	 */
	private static IntellijColumnInfo findScalarColumn(List<IntellijColumnInfo> columns, String name) {
		if (Objects.isNull(name) || name.isEmpty()) {
			return null;
	}
		return columns.stream().filter(column -> column.getName().equals(name)).findFirst().orElse(null);
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
			connection.setAutoCommit(true);
			return storedValue;
		} catch (SQLException | RuntimeException failure) {
			rollbackAndRestoreAutoCommit(connection, failure);
			throw failure;
		}
	}

	/**
	 * 回滚失败写入并恢复调用前已确认的自动提交模式，清理错误作为主失败的 suppressed cause 保留。
	 *
	 * @param connection 当前事务连接
	 * @param failure 已发生的 SQL 或运行时异常
	 */
	private static void rollbackAndRestoreAutoCommit(Connection connection, Throwable failure) {
		try {
			connection.rollback();
		} catch (SQLException rollbackFailure) {
			failure.addSuppressed(rollbackFailure);
		}
		try {
			connection.setAutoCommit(true);
		} catch (SQLException restoreFailure) {
			failure.addSuppressed(restoreFailure);
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

	/**
	 * Prefix LIKE is limited to JDBC character columns so drivers do not apply dialect-dependent numeric conversions.
	 *
	 * @param column validated scalar column metadata
	 * @return true for JDBC character types supported by this editor
	 */
	private static boolean isTextColumn(IntellijColumnInfo column) {
		return switch (column.getDataType()) {
			case Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR -> true;
			default -> false;
		};
	}
}
