package io.github.jinlongliao.mybatis.workbench.database;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Properties;
import java.util.UUID;

/**
 * 将连接配置序列化为可在 Git 中审阅的稳定文本。
 *
 * @author liaojinlong
 * @since 2026/11/18 17:00
 */
public final class ConnectionProfileProperties {
	/**
	 * 格式工具无需实例化。
	 */
	private ConnectionProfileProperties() {
	}

	/**
	 * 加载版本化交换字段，接受部分编辑器在 UTF-8 文件开头写入的 BOM。
	 *
	 * @param reader 已解码的配置文本，由调用方关闭
	 * @return 解析得到的字段
	 * @throws IOException 文本读取失败
	 */
	public static Properties readProfileProperties(Reader reader) throws IOException {
		PushbackReader input = new PushbackReader(reader);
		int first = input.read();
		if (first != -1 && first != 0xFEFF) {
			input.unread(first);
		}
		Properties properties = new Properties();
		properties.load(input);
		return properties;
	}

	/**
	 * 校验文件中的连接 ID，避免 UUID 解析器把缩写补零后关联到另一个配置。
	 *
	 * @param value 文件中声明的连接 ID
	 * @return 完整 UUID，允许十六进制字母大小写
	 * @throws IllegalArgumentException ID 缺失或格式不完整
	 */
	static UUID readProfileId(String value) {
		UUID id = UUID.fromString(value);
		if (!id.toString().equalsIgnoreCase(value)) {
			throw new IllegalArgumentException("Invalid UUID");
		}
		return id;
	}

	/**
	 * 去除 properties 时间戳并统一使用 LF，避免跨平台提交产生无关差异。
	 *
	 * @param properties 已选取的共享字段
	 * @param writer     目标文本输出
	 * @throws IOException 无法写入
	 */
	static void writeProfileProperties(Properties properties, Writer writer) throws IOException {
		StringWriter content = new StringWriter();
		properties.store(content, null);
		String serialized = content.toString();
		int start = serialized.indexOf('\n') + 1;
		// Properties 已转义字段值中的换行，这里只转换文件行尾。
		writer.write(serialized.substring(start).replace("\r\n", "\n"));
	}
}
