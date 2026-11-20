package io.github.jinlongliao.mybatis.workbench.database;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.QuoteMode;
import org.mybatis.generator.api.intellij.IntellijColumnInfo;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.util.*;
import java.util.function.BiFunction;

import io.github.jinlongliao.mybatis.workbench.model.DbType;

/**
 * 按 UTF-8 CSV 表头将数据追加到指定数据库表，参数及 SQL NULL 由 JDBC 绑定。
 *
 * @author liaojinlong
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4180.html">RFC 4180</a>
 * @since 2026/11/18 16:07
 */
public final class JdbcCsvImport {
	/**
	 * 单次导入最多读 8 MiB 文件，批量事务最多写入 1000 行。
	 */
	private static final int MAX_CSV_BYTES = 8 * 1024 * 1024;
	/**
	 * 单次导入的最大行数。
	 */
	private static final int MAX_CSV_ROWS = 1_000;
	/**
	 * 未加引号的空字段为 SQL NULL，带引号的空字符串仍为字符串。
	 */
	private static final CSVFormat FORMAT = CSVFormat.RFC4180.builder()
		.setNullString("").setQuoteMode(QuoteMode.ALL_NON_NULL).get();

	/**
	 * 导入器无需实例。
	 */
	private JdbcCsvImport() {
	}

	/**
	 * 将文件的数据行追加到选定表。调用方须提供一个独占、自动提交的新连接并负责关闭。
	 * 文件先解析和校验，再开启事务。任一行失败均回滚；提交成功后返回行数。
	 *
	 * @param connection 本次导入专用连接
	 * @param dbType     当前数据库方言
	 * @param table      从当前连接读取的目标表定位信息
	 * @param file       要导入的 UTF-8 CSV，表头需使用数据库中实际列名
	 * @return 成功提交的行数
	 * @throws IOException  文件超过限制、格式无效或读取失败
	 * @throws SQLException 目标不支持事务、列不匹配或 JDBC 写入失败
	 */
	public static int importCsvRows(Connection connection, DbType dbType, JdbcTable table, Path file)
		throws IOException, SQLException {
		ResourceBundle bundle = ResourceBundle.getBundle("messages.WorkbenchBundle");
		return importCsvRows(connection, dbType, table, file,
			(key, arguments) -> new MessageFormat(bundle.getString(key), bundle.getLocale()).format(arguments));
	}

	/**
	 * 使用宿主语言导入 CSV。连接、校验顺序、提交和回滚约束与默认入口相同。
	 *
	 * @param connection 本次导入专用连接
	 * @param dbType 当前数据库方言
	 * @param table 目标表定位信息
	 * @param file 要导入的 UTF-8 CSV
	 * @param messages 以宿主界面语言格式化导入错误
	 * @return 成功提交的行数
	 * @throws IOException 文件超过限制、格式无效或读取失败
	 * @throws SQLException 目标不支持事务、列不匹配或 JDBC 写入失败
	 */
	public static int importCsvRows(Connection connection, DbType dbType, JdbcTable table, Path file,
								   BiFunction<String, Object[], String> messages) throws IOException, SQLException {
		if (!connection.getAutoCommit() || !connection.getMetaData().supportsTransactions()) {
			throw new SQLException(messages.apply("csv.import.requires.transaction", new Object[0]));
		}
		if (!"TABLE".equalsIgnoreCase(table.type()) && !"BASE TABLE".equalsIgnoreCase(table.type())) {
			throw new SQLException(messages.apply("csv.import.requires.table", new Object[0]));
		}
		List<CSVRecord> records = readCsvRecords(file, messages);
		DatabaseMetaData metadata = connection.getMetaData();
		var snapshot = JdbcTableMetadata.readGenerationTable(metadata, dbType, table);
		List<IntellijColumnInfo> columns = bindCsvColumns(records.getFirst(), snapshot.tableInfo().getColumnInfos(), messages);
		if (records.size() == 1) {
			return 0;
		}
		List<String> columnNames = new ArrayList<>(columns.size());
		String quote = metadata.getIdentifierQuoteString();
		for (IntellijColumnInfo column : columns) {
			columnNames.add(JdbcTableSql.quoteIdentifier(column.getName(), quote));
		}
		String qualifiedTable = JdbcTableSql.qualifiedTableName(dbType, table, quote);
		String placeholders = "?,".repeat(columns.size() - 1) + "?";
		String insertSql = "INSERT INTO " + qualifiedTable + " (" + String.join(", ", columnNames)
			+ ") VALUES (" + placeholders + ")";
		String probeSql = "SELECT " + columnNames.getFirst() + " FROM " + qualifiedTable + " WHERE 1 = 0";

		connection.setAutoCommit(false);
		try {
			// MySQL 系列的空查询在当前事务锁定表元数据，防止并发 DDL 替换存储引擎。
			JdbcTableSql.assertTransactionalTable(connection, dbType, table, probeSql);
			try (var statement = connection.prepareStatement(insertSql)) {
				statement.setQueryTimeout(15);
				for (CSVRecord row : records.subList(1, records.size())) {
					try {
						for (int index = 0; index < columns.size(); index++) {
							int jdbcType = columns.get(index).getDataType();
							String value = row.get(index);
							if (Objects.isNull(value)) {
								statement.setNull(index + 1, jdbcType);
							} else {
								statement.setObject(index + 1, value, jdbcType);
							}
						}
						if (statement.executeUpdate() != 1) {
							throw new SQLException(messages.apply("csv.import.unexpected.update.count", new Object[0]));
						}
					} catch (SQLException failure) {
						throw new SQLException(messages.apply("csv.import.record.failed", new Object[]{row.getRecordNumber()}), failure);
					}
				}
			}
			connection.commit();
			return records.size() - 1;
		} catch (SQLException | RuntimeException failure) {
			try {
				connection.rollback();
			} catch (SQLException rollbackFailure) {
				failure.addSuppressed(rollbackFailure);
			}
			throw failure;
		}
	}

	/**
	 * 限定字节数和记录数后读取所有数据，确保结构错误发生在数据库写入前。
	 *
	 * @param file 要读取的新输入文件
	 * @param messages 用于格式化校验错误
	 * @return 第一条为表头的 CSV 记录
	 * @throws IOException 无表头、数据不完整或存在非法 UTF-8
	 */
	private static List<CSVRecord> readCsvRecords(Path file, BiFunction<String, Object[], String> messages)
		throws IOException {
		byte[] content;
		try (var input = Files.newInputStream(file)) {
			content = input.readNBytes(MAX_CSV_BYTES + 1);
		}
		if (content.length > MAX_CSV_BYTES) {
			throw new IOException(messages.apply("csv.import.file.limit", new Object[0]));
		}
		try (var reader = new PushbackReader(new InputStreamReader(new ByteArrayInputStream(content),
			StandardCharsets.UTF_8.newDecoder()))) {
			int firstCharacter = reader.read();
			if (firstCharacter >= 0 && firstCharacter != 0xFEFF) {
				reader.unread(firstCharacter);
			}
			try (var csv = FORMAT.parse(reader)) {
				List<CSVRecord> records = new ArrayList<>();
				for (CSVRecord row : csv) {
					if (records.size() > MAX_CSV_ROWS) {
						throw new IOException(messages.apply("csv.import.row.limit", new Object[0]));
					}
					if (!records.isEmpty() && row.size() != records.getFirst().size()) {
						throw new IOException(messages.apply("csv.import.record.width", new Object[]{row.getRecordNumber()}));
					}
					records.add(row);
				}
				if (records.isEmpty()) {
					throw new IOException(messages.apply("csv.import.missing.header", new Object[0]));
				}
				return records;
			} catch (UncheckedIOException failure) {
				throw failure.getCause();
			}
		}
	}

	/**
	 * 在 JDBC 元数据中查找每个表头对应的目标列。拒绝重复、未知及数据库生成列。
	 *
	 * @param header       第一条 CSV 记录
	 * @param tableColumns 目标表的实际列
	 * @param messages 用于格式化表头错误
	 * @return 按文件列顺序排列的目标列
	 * @throws SQLException 表头字段不合法
	 */
	private static List<IntellijColumnInfo> bindCsvColumns(CSVRecord header, List<IntellijColumnInfo> tableColumns,
													 BiFunction<String, Object[], String> messages)
		throws SQLException {
		var known = HashMap.<String, IntellijColumnInfo>newHashMap(tableColumns.size());
		for (IntellijColumnInfo column : tableColumns) {
			known.put(column.getName(), column);
		}
		var used = HashSet.<String>newHashSet(header.size());
		List<IntellijColumnInfo> columns = new ArrayList<>(header.size());
		for (String label : header) {
			IntellijColumnInfo column = known.get(label);
			if (Objects.isNull(column) || !used.add(label) || column.isGeneratedColumn()) {
				throw new SQLException(messages.apply("csv.import.invalid.column", new Object[]{label}));
			}
			columns.add(column);
		}
		return columns;
	}
}
