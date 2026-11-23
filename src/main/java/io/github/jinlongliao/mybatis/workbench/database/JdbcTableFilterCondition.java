package io.github.jinlongliao.mybatis.workbench.database;

import java.util.List;
import java.util.Objects;

/**
 * 一个表数据 WHERE 谓词；列名由当前表元数据校验，值仅用于 JDBC 参数绑定。
 *
 * @param column 当前表中的列名
 * @param operator 从固定操作符枚举中选择的比较方式
 * @param value 单值操作符的绑定文本；IN 和 NULL 检查不使用该字段
 * @param values IN 操作符的有序绑定值；其他操作符使用空列表
 *
 * @author liaojinlong
 * @since 2026/11/22 18:20
 */
public record JdbcTableFilterCondition(String column, JdbcTableFilterOperator operator, String value,
	List<String> values) {
	/**
	 * 规范化谓词字段并冻结 IN 参数；列白名单、操作符与参数总量由解析器和 JDBC 执行层校验。
	 *
	 * @param column 表元数据列名
	 * @param operator 固定 SQL 比较运算符
	 * @param value 单值运算符的绑定文本
	 * @param values IN 运算符的绑定列表
	 */
	public JdbcTableFilterCondition {
		column = Objects.toString(column, "").strip();
		value = Objects.toString(value, "");
		values = Objects.isNull(values) ? List.of() : List.copyOf(values);
	}
}
