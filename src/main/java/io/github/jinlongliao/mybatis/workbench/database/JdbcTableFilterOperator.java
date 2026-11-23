package io.github.jinlongliao.mybatis.workbench.database;

/**
 * JDBC 表数据筛选支持的固定比较运算符；SQL 片段由代码定义，不接受用户提供的运算符文本。
 *
 * @author liaojinlong
 * @since 2026/11/21 22:00
 */
public enum JdbcTableFilterOperator {
	/** 精确相等比较。 */
	EQUALS("="),
	/** 不相等比较。 */
	NOT_EQUALS("<>"),
	/** 大于比较。 */
	GREATER_THAN(">"),
	/** 大于或等于比较。 */
	GREATER_THAN_OR_EQUALS(">="),
	/** 小于比较。 */
	LESS_THAN("<"),
	/** 小于或等于比较。 */
	LESS_THAN_OR_EQUALS("<="),
	/** 以绑定参数表达的字面前缀匹配；输入解析器只允许末尾一个百分号通配符。 */
	PREFIX_LIKE("LIKE"),
	/** 单列、有限个绑定值的成员匹配；占位符由 JDBC 数据层按列表大小生成。 */
	IN("IN"),
	/** 匹配 SQL NULL。 */
	IS_NULL("IS NULL"),
	/** 不匹配 SQL NULL。 */
	IS_NOT_NULL("IS NOT NULL");

	/** 固定的 SQL 操作符。 */
	private final String sql;

	/**
	 * 保存一个源码内固定的比较片段。
	 *
	 * @param sql 不含用户输入的 SQL 运算符
	 */
	JdbcTableFilterOperator(String sql) {
		this.sql = sql;
	}

	/**
	 * 返回用于白名单 SQL 的固定运算符。
	 *
	 * @return 运算符 SQL
	 */
	public String sql() {
		return sql;
	}

	/**
	 * 判断运算符是否需要绑定比较值。
	 *
	 * @return 普通比较为 true，NULL 检查为 false
	 */
	public boolean requiresValue() {
		return this != IS_NULL && this != IS_NOT_NULL;
	}
}
