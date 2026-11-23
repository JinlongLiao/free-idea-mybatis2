package io.github.jinlongliao.mybatis.workbench.database;

import java.util.List;
import java.util.Objects;

/**
 * MongoDB 服务端当前可见的数据库候选、首次预选范围和 URI 默认库。
 *
 * @param names 当前账号可见的候选数据库
 * @param initialSelection 首次选择时预选的数据库
 * @param defaultName URI 指定的默认数据库；未指定时为空字符串
 * @author liaojinlong
 * @since 2026/11/22 09:09
 */
public record MongoDatabaseChoices(List<String> names, List<String> initialSelection, String defaultName) {
	/**
	 * 建立不可变快照，避免后台结果被 UI 后续修改。
	 *
	 * @param names 当前账号可见的候选数据库
	 * @param initialSelection 首次选择时预选的数据库
	 * @param defaultName URI 默认数据库名
	 */
	public MongoDatabaseChoices {
		names = List.copyOf(names);
		initialSelection = List.copyOf(initialSelection);
		defaultName = Objects.requireNonNullElse(defaultName, "");
	}
}
