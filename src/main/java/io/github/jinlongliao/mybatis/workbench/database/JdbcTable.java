package io.github.jinlongliao.mybatis.workbench.database;

/**
 * JDBC 对象清单中一张表或视图的定位信息。名称按驱动返回的原始值保留。
 *
 * @param catalog 所属目录，可以为空
 * @param schema  所属模式，可以为空
 * @param name    对象原始名称
 * @param type    驱动报告的表类型
 * @param remarks 对象备注，可以为空
 * @author liaojinlong
 * @since 2026/11/18 14:44
 */
public record JdbcTable(String catalog, String schema, String name, String type, String remarks) {
}
