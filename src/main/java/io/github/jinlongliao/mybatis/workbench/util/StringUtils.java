package io.github.jinlongliao.mybatis.workbench.util;

import java.util.Locale;
import java.util.Objects;

/**
 * Created by Owen on 6/18/16.
 */
public class StringUtils {

	/**
	 * 将数据库表名转为默认 Java 类型名，SQL 层继续使用原始表名。
	 *
	 * @param str 数据库中的表名，可为空
	 * @return 驼峰类型名，空引用返回空引用，无可用字符时返回 Table
	 */
	public static String dbStringToCamelStyle(String str) {
		if (Objects.isNull(str)) {
			return null;
		}
		String name = str.toLowerCase(Locale.ROOT);
		StringBuilder javaName = new StringBuilder(name.length());
		boolean startWord = true;
		for (int index = 0; index < name.length();) {
			int character = name.codePointAt(index);
			index += Character.charCount(character);
			if (character == '_' || !Character.isJavaIdentifierPart(character)
				|| Character.isIdentifierIgnorable(character)) {
				startWord = true;
				continue;
			}
			if (javaName.isEmpty() && !Character.isJavaIdentifierStart(character)) {
				javaName.append("Table");
			}
			javaName.appendCodePoint(startWord ? Character.toUpperCase(character) : character);
			startWord = false;
		}
		return javaName.isEmpty() ? "Table" : javaName.toString();
	}

	public static boolean isEmpty(Object str) {
		return str == null || "".equals(str);
	}
}
