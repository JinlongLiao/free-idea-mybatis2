package io.github.jinlongliao.mybatis.workbench.ui;

import com.intellij.DynamicBundle;

import org.jetbrains.annotations.PropertyKey;

/**
 * 数据库工作台的界面文案，使用 IDEA 当前界面语言解析资源。
 *
 * @author liaojinlong
 * @since 2026/11/18 16:41
 */
public final class WorkbenchBundle extends DynamicBundle {
	/**
	 * 与 messages 目录中的资源名称对应。
	 */
	private static final String BUNDLE = "messages.WorkbenchBundle";

	/**
	 * 随 IDE 语言切换使用对应资源。
	 */
	private static final WorkbenchBundle INSTANCE = new WorkbenchBundle();

	/**
	 * 将文案解析绑定到当前插件类加载器。
	 */
	private WorkbenchBundle() {
		super(WorkbenchBundle.class, BUNDLE);
	}

	/**
	 * 获取指定界面语言的文本。
	 *
	 * @param key    文案键
	 * @param values 文案中的占位参数
	 * @return 当前语言文本
	 */
	public static String message(@PropertyKey(resourceBundle = BUNDLE) String key, Object... values) {
		return INSTANCE.getMessage(key, values);
	}
}
