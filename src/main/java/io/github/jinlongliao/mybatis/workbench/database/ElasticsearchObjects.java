package io.github.jinlongliao.mybatis.workbench.database;

import java.util.List;

/**
 * Elasticsearch 账号可以浏览的索引和别名。
 *
 * @param indices 索引名称
 * @param aliases 可作为查询目标的别名名称
 * @author liaojinlong
 * @since 2026/11/18 15:42
 */
public record ElasticsearchObjects(List<String> indices, List<String> aliases) {
}
