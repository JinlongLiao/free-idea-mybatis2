package io.github.jinlongliao.mybatis.workbench.database;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * 使用调用方指定的语言读取独立数据库核心的错误消息。
 *
 * @author liaojinlong
 * @since 2026/11/19 14:19
 */
final class DatabaseMessages {
	/** 禁止实例化，仅提供资源查找。 */
	private DatabaseMessages() {
	}

	/**
	 * 获取数据库操作错误文案，并按指定语言替换参数。
	 *
	 * @param locale 调用方指定的界面语言
	 * @param key 中英文资源中的消息键
	 * @param arguments 用于填充消息占位符的值
	 * @return 当前语言对应的消息，未提供该语言时使用英文
	 */
	static String databaseMessage(Locale locale, String key, Object... arguments) {
		// 语言包缺失时直接回退到基础英文资源，避免取到另一种 JVM 默认语言。
		String pattern = ResourceBundle.getBundle("messages.WorkbenchBundle", locale,
			ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)).getString(key);
		return arguments.length == 0 ? pattern : new MessageFormat(pattern, locale).format(arguments);
	}
}
