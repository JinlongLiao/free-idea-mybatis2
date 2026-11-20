package io.github.jinlongliao.mybatis.workbench.database;

import java.util.List;

/**
 * 已读取的表数据页及环境，保留原值用来检测并发修改。
 *
 * @param table           当前连接中的目标表
 * @param result          列名、显示的原值和截断状态
 * @param primaryKeys     原始主键列顺序，保存时校验元数据是否变化
 * @param editableColumns 当前能够编辑的列名
 * @param hasNextPage     可用非空主键续读更靠后的数据
 * @param hasPreviousPage 可用非空主键续读更靠前的数据
 * @author liaojinlong
 * @since 2026/11/18 16:12
 */
public record JdbcTableRows(JdbcTable table, JdbcQueryResult result, List<String> primaryKeys,
                            List<String> editableColumns,
							boolean hasNextPage, boolean hasPreviousPage) {
}
