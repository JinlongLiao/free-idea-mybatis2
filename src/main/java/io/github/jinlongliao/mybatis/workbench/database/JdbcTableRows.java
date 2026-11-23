package io.github.jinlongliao.mybatis.workbench.database;

import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 已读取的表数据页及环境，保留原值用来检测并发修改。
 *
 * @param table           当前连接中的目标表
 * @param result          列名、显示的原值和截断状态
 * @param columnTypes     与结果列逐项对应的 JDBC 类型
 * @param primaryKeys     原始主键列顺序，保存时校验元数据是否变化
 * @param editableColumns 当前能够编辑的列名
 * @param insertableColumns 当前能够在新增行时提交的标量列；未给出的列使用数据库默认值
 * @param hasNextPage     可用非空主键续读更靠后的数据
 * @param hasPreviousPage 可用非空主键续读更靠前的数据
 * @param queryOptions    读取此页所用的服务端筛选与排序设置
 * @author liaojinlong
 * @since 2026/11/18 16:12
 */
public record JdbcTableRows(JdbcTable table, JdbcQueryResult result, List<Integer> columnTypes, List<String> primaryKeys,
                            List<String> editableColumns,
							boolean hasNextPage, boolean hasPreviousPage, JdbcTableQueryOptions queryOptions,
							List<String> insertableColumns) {
	/**
	 * 兼容未显式传入数据库端查询设置的旧调用方。
	 *
	 * @param table 当前连接中的目标表
	 * @param result 列名、显示的原值和截断状态
	 * @param columnTypes 与结果列逐项对应的 JDBC 类型
	 * @param primaryKeys 原始主键列顺序
	 * @param editableColumns 当前可编辑的列名
	 * @param hasNextPage 是否可读取下一页
	 * @param hasPreviousPage 是否可读取上一页
	 */
	public JdbcTableRows(JdbcTable table, JdbcQueryResult result, List<Integer> columnTypes, List<String> primaryKeys,
		List<String> editableColumns, boolean hasNextPage, boolean hasPreviousPage) {
		this(table, result, columnTypes, primaryKeys, editableColumns, hasNextPage, hasPreviousPage,
			JdbcTableQueryOptions.none(), List.of());
	}
	/**
	 * 兼容显式查询设置但未携带新增列能力的旧调用方。
	 *
	 * @param table 当前连接中的目标表
	 * @param result 查询结果与原始值
	 * @param columnTypes 按列顺序排列的 JDBC 类型
	 * @param primaryKeys 按数据库声明顺序排列的主键列
	 * @param editableColumns 可编辑列
	 * @param hasNextPage 是否可读取下一页
	 * @param hasPreviousPage 是否可读取上一页
	 * @param queryOptions 此页的筛选与排序
	 */
	public JdbcTableRows(JdbcTable table, JdbcQueryResult result, List<Integer> columnTypes, List<String> primaryKeys,
		List<String> editableColumns, boolean hasNextPage, boolean hasPreviousPage, JdbcTableQueryOptions queryOptions) {
		this(table, result, columnTypes, primaryKeys, editableColumns, hasNextPage, hasPreviousPage, queryOptions, List.of());
	}
	/**
	 * 构造未携带 JDBC 列类型的旧格式快照，结果列继续按文本显示与排序。
	 *
	 * @param table 当前连接中的目标表
	 * @param result 查询结果与原始值
	 * @param primaryKeys 按数据库声明顺序排列的主键列
	 * @param editableColumns 可编辑列
	 * @param hasNextPage 是否可按主键读取下一页
	 * @param hasPreviousPage 是否可按主键读取上一页
	 */
	public JdbcTableRows(JdbcTable table, JdbcQueryResult result, List<String> primaryKeys, List<String> editableColumns,
						 boolean hasNextPage, boolean hasPreviousPage) {
		this(table, result, Collections.nCopies(result.columnLabels().size(), Types.VARCHAR), primaryKeys,
			editableColumns, hasNextPage, hasPreviousPage, JdbcTableQueryOptions.none(), List.of());
	}

	/**
	 * 将数据库确认写入的值回填到原页快照，保留 SQL NULL 和翻页边界。
	 *
	 * @param row 模型中的行号
	 * @param column 模型中的列号
	 * @param value 数据库返回的存储值
	 * @return 已更新的新数据页
	 */
	public JdbcTableRows withStoredCell(int row, int column, String value) {
		List<List<String>> rows = new ArrayList<>(result.rows());
		List<String> updated = new ArrayList<>(rows.get(row));
		updated.set(column, value);
		rows.set(row, Collections.unmodifiableList(updated));
		var refreshed = new JdbcQueryResult(result.columnLabels(), List.copyOf(rows), result.truncated(), result.updateCount());
		return new JdbcTableRows(table, refreshed, columnTypes, primaryKeys, editableColumns, hasNextPage,
			hasPreviousPage, queryOptions, insertableColumns);
	}
}
