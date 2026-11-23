package io.github.jinlongliao.mybatis.workbench.model;

import org.mybatis.generator.api.intellij.IntellijColumnInfo;
import org.mybatis.generator.api.intellij.IntellijTableInfo;

/**
 * 生成器使用的表结构快照及其 SQL 限定名，由原生数据库工具或独立数据源提供。
 *
 * @param tableInfo 列、主键和表备注
 * @param dbType    生成 SQL 所用的数据库类型
 * @param qualifier 生成带前缀的 SQL 时使用的模式名或目录名，可以为空
 * @author liaojinlong
 * @since 2026/11/18 14:35
 */
public record GenerationTable(IntellijTableInfo tableInfo, DbType dbType, String qualifier) {
	/**
	 * 查找可由数据库生成的主键列。手工赋值的主键仍参与映射和 INSERT。
	 *
	 * @return 第一列自增主键的名称，无自增主键时为空字符串
	 */
	public String defaultGeneratedKeyColumn() {
		return tableInfo.getPrimaryKeyColumns().stream()
			.filter(IntellijColumnInfo::isAutoIncrement)
			.findFirst().map(IntellijColumnInfo::getName).orElse("");
	}
}
