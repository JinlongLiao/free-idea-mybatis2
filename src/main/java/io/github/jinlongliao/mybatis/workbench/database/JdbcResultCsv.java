package io.github.jinlongliao.mybatis.workbench.database;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;

/**
 * 将已读取的 JDBC 结果写成 UTF-8 CSV，字段内容按查询结果原样保留。
 *
 * @author liaojinlong
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4180.html">RFC 4180</a>
 * @since 2026/11/18 15:59
 */
public final class JdbcResultCsv {
	/**
	 * CSV 写入无需实例。
	 */
	private JdbcResultCsv() {
	}

	/**
	 * 创建一个新的 CSV 文件，包含表头和当前结果页。已有文件原样保留。
	 * 写入或关闭失败时尝试删除本次创建的不完整文件，并保留底层异常。
	 *
	 * @param path   用户选择的新文件
	 * @param result 已读取的查询结果，至少有一列
	 * @throws IOException              无法创建、写入或关闭目标文件
	 * @throws IllegalArgumentException 结果没有可导出的列
	 */
	public static void exportQueryResult(Path path, JdbcQueryResult result) throws IOException {
		if (result.columnLabels().isEmpty()) {
			throw new IllegalArgumentException("没有可导出的查询结果列");
		}
		Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
		try (writer) {
			writeCsvRecord(writer, result.columnLabels());
			for (List<String> row : result.rows()) {
				writeCsvRecord(writer, row);
			}
		} catch (IOException failure) {
			try {
				Files.deleteIfExists(path);
			} catch (IOException cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}
	}

	/**
	 * 写出一行 CSV，非空引用总是带引号，SQL NULL 使用无引号空字段。
	 *
	 * @param writer 当前 UTF-8 输出流
	 * @param values 一行原始字符串或空引用
	 * @throws IOException 写入失败
	 */
	private static void writeCsvRecord(Writer writer, List<String> values) throws IOException {
		for (int index = 0; index < values.size(); index++) {
			if (index > 0) {
				writer.write(',');
			}
			String value = values.get(index);
			if (Objects.nonNull(value)) {
				writer.write('"');
				writer.write(value.replace("\"", "\"\""));
				writer.write('"');
			}
		}
		writer.write("\r\n");
	}
}
