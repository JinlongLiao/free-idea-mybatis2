package io.github.jinlongliao.mybatis.workbench.database;

import java.util.List;
import java.util.Objects;

/**
 * 有界数据库端筛选与单列排序设置；筛选条件按输入顺序以 AND 组合，并随结果页保留供安全续读。
 *
 * @param filters 已按 AND 组合的不可变筛选谓词
 * @param sortColumn 元数据中的排序列；空字符串表示按主键排序
 * @param sortDescending true 表示排序列降序，主键仍用作稳定的次级排序键
 * @author liaojinlong
 * @since 2026/11/21 22:00
 */
public record JdbcTableQueryOptions(List<JdbcTableFilterCondition> filters, String sortColumn,
	boolean sortDescending) {
	/** 单个 IN 谓词允许的最大绑定值数量，单位为列表项，合法范围为 1 至 100。 */
	public static final int MAX_IN_VALUES = 100;
	/** 一个筛选表达式最多包含的条件数，限制解析和 SQL 生成工作量。 */
	public static final int MAX_FILTER_CONDITIONS = 10;
	/** 一个筛选表达式总绑定参数上限，包含所有比较条件和 IN 列表项。 */
	public static final int MAX_FILTER_PARAMETERS = 100;

	/** 默认无筛选，沿用主键升序。 */
	private static final JdbcTableQueryOptions NONE = new JdbcTableQueryOptions(List.of(), "", false);

	/**
	 * 冻结筛选条件并规范化排序列，保证分页快照比较不受调用方后续集合修改影响。
	 *
	 * @param filters 筛选条件，最多 {@value #MAX_FILTER_CONDITIONS} 项
	 * @param sortColumn 排序列，可为空
	 * @param sortDescending 是否降序
	 */
	public JdbcTableQueryOptions {
		filters = Objects.isNull(filters) ? List.of() : List.copyOf(filters);
		sortColumn = Objects.toString(sortColumn, "").strip();
	}

	/**
	 * 保持既有单筛选调用点兼容；IN 的值列表独立保存，其余值运算符使用单值参数。
	 *
	 * @param filterColumn 筛选列，可为空
	 * @param filterOperator 固定筛选运算符
	 * @param filterValue 单值绑定文本
	 * @param sortColumn 排序列，可为空
	 * @param sortDescending 是否降序
	 */
	public JdbcTableQueryOptions(String filterColumn, JdbcTableFilterOperator filterOperator, String filterValue,
		String sortColumn, boolean sortDescending) {
		this(legacyFilters(filterColumn, filterOperator, filterValue, List.of()), sortColumn, sortDescending);
	}

	/**
	 * 保持既有 IN 单筛选调用点兼容。
	 *
	 * @param filterColumn 筛选列，可为空
	 * @param filterOperator 固定筛选运算符
	 * @param filterValue 单值绑定文本
	 * @param filterValues IN 条件绑定值
	 * @param sortColumn 排序列，可为空
	 * @param sortDescending 是否降序
	 */
	public JdbcTableQueryOptions(String filterColumn, JdbcTableFilterOperator filterOperator, String filterValue,
		List<String> filterValues, String sortColumn, boolean sortDescending) {
		this(legacyFilters(filterColumn, filterOperator, filterValue, filterValues), sortColumn, sortDescending);
	}

	/**
	 * 将旧单条件参数转换为新条件列表；空列但仍带 IN 值时保留一个无效条件，让执行层拒绝而非放宽查询。
	 *
	 * @param column 旧版筛选列
	 * @param operator 旧版运算符
	 * @param value 旧版单值
	 * @param values 旧版 IN 列表
	 * @return 兼容的零项或单项条件列表
	 */
	private static List<JdbcTableFilterCondition> legacyFilters(String column, JdbcTableFilterOperator operator,
		String value, List<String> values) {
		String normalizedColumn = Objects.toString(column, "").strip();
		List<String> normalizedValues = Objects.isNull(values) ? List.of() : List.copyOf(values);
		if (normalizedColumn.isEmpty() && normalizedValues.isEmpty()) {
			return List.of();
		}
		return List.of(new JdbcTableFilterCondition(normalizedColumn, operator,
			Objects.toString(value, ""), normalizedValues));
	}

	/**
	 * 创建无筛选、默认主键升序的查询设置。
	 *
	 * @return 共享的不可变默认设置
	 */
	public static JdbcTableQueryOptions none() {
		return NONE;
	}

	/**
	 * 兼容旧单筛选 API，返回首个谓词列；新代码应读取 {@link #filters()}。
	 *
	 * @return 首个筛选列或空字符串
	 */
	public String filterColumn() {
		return filters.isEmpty() ? "" : filters.getFirst().column();
	}

	/**
	 * 兼容旧单筛选 API，返回首个谓词运算符。
	 *
	 * @return 首个运算符或等于运算符
	 */
	public JdbcTableFilterOperator filterOperator() {
		return filters.isEmpty() ? JdbcTableFilterOperator.EQUALS : filters.getFirst().operator();
	}

	/**
	 * 兼容旧单筛选 API，返回首个谓词的单值。
	 *
	 * @return 首个单值或空字符串
	 */
	public String filterValue() {
		return filters.isEmpty() ? "" : filters.getFirst().value();
	}

	/**
	 * 兼容旧单筛选 API，返回首个谓词的 IN 值列表。
	 *
	 * @return 首个 IN 列表或空列表
	 */
	public List<String> filterValues() {
		return filters.isEmpty() ? List.of() : filters.getFirst().values();
	}

	/**
	 * 表示当前设置是否带有数据库端筛选条件。
	 *
	 * @return 至少存在一个筛选谓词时为 true
	 */
	public boolean hasFilter() {
		return !filters.isEmpty();
	}

	/**
	 * 表示当前设置是否指定了额外排序列。
	 *
	 * @return 排序列非空时为 true
	 */
	public boolean hasSort() {
		return !sortColumn.isEmpty();
	}
}
