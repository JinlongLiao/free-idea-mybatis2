package io.github.jinlongliao.mybatis.workbench.generate;

import io.github.jinlongliao.mybatis.workbench.database.JdbcConnectionProfile;
import io.github.jinlongliao.mybatis.workbench.database.JdbcCsvImport;
import io.github.jinlongliao.mybatis.workbench.database.JdbcDriverConnection;
import io.github.jinlongliao.mybatis.workbench.database.JdbcQueryResult;
import io.github.jinlongliao.mybatis.workbench.database.JdbcResultCsv;
import io.github.jinlongliao.mybatis.workbench.database.JdbcTableMetadata;
import io.github.jinlongliao.mybatis.workbench.database.JdbcTableEditor;
import io.github.jinlongliao.mybatis.workbench.database.JdbcSqlCompletion;
import io.github.jinlongliao.mybatis.workbench.database.JdbcSqlStatementLocator;
import io.github.jinlongliao.mybatis.workbench.database.JdbcTable;
import io.github.jinlongliao.mybatis.workbench.database.JdbcTableDdl;
import io.github.jinlongliao.mybatis.workbench.database.JdbcTableComparer;
import io.github.jinlongliao.mybatis.workbench.database.JdbcTableRows;
import io.github.jinlongliao.mybatis.workbench.database.JdbcTableFilterOperator;
import io.github.jinlongliao.mybatis.workbench.database.JdbcTableQueryExpressionParser;
import io.github.jinlongliao.mybatis.workbench.database.JdbcTableQueryOptions;
import io.github.jinlongliao.mybatis.workbench.generate.plugin.DbRemarksCommentGenerator;
import io.github.jinlongliao.mybatis.workbench.model.Config;
import io.github.jinlongliao.mybatis.workbench.model.DbType;
import io.github.jinlongliao.mybatis.workbench.model.GenerationTable;
import io.github.jinlongliao.mybatis.workbench.database.MongoDocumentPage;
import io.github.jinlongliao.mybatis.workbench.database.WorkbenchMongoConnection;
import io.github.jinlongliao.mybatis.workbench.database.NoSqlConnectionProfile;
import io.github.jinlongliao.mybatis.workbench.database.NoSqlSourceType;
import io.github.jinlongliao.mybatis.workbench.setting.WorkbenchConnectionProfiles;
import io.github.jinlongliao.mybatis.workbench.ui.MybatisGeneratorMainUI;
import io.github.jinlongliao.mybatis.workbench.util.StringUtils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.generator.api.MyBatisGenerator;
import org.mybatis.generator.api.intellij.IntellijColumnInfo;
import org.mybatis.generator.api.intellij.IntellijTableInfo;
import org.mybatis.generator.config.ClientGeneratorConfiguration;
import org.mybatis.generator.config.CommentGeneratorConfiguration;
import org.mybatis.generator.config.Configuration;
import org.mybatis.generator.config.ConnectionFactoryConfiguration;
import org.mybatis.generator.config.Context;
import org.mybatis.generator.config.ModelGeneratorConfiguration;
import org.mybatis.generator.config.Property;
import org.mybatis.generator.config.SqlMapGeneratorConfiguration;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.regex.Pattern;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 IDEA 表结构接入生成器，以及重复生成时的 Mapper XML 写入边界。
 *
 * @author liaojinlong
 * @since 2026/11/18 13:29
 */
class MyBatisGeneratorCompatibilityTest {
	/**
	 * 首次生成保留主键、列映射和备注，写入 SQL 排除计算列。再生成时保留手写 SQL 并防止重复写入。
	 *
	 * @param workspace 每次测试独立的输出目录
	 * @throws Exception 样本生成或文件操作失败
	 */
	@Test
	void generatesAndPublishesMapperXml(@TempDir Path workspace) throws Exception {
		Path javaOutput = Files.createDirectory(workspace.resolve("java"));
		Path xmlOutput = Files.createDirectory(workspace.resolve("xml"));
		Path stage = Files.createDirectory(xmlOutput.resolve("stage"));
		var table = sampleTable();
		MyBatisGenerator generator = buildSampleGenerator(javaOutput, stage, new GenerationTable(table, DbType.H2, ""));
		assertTrue(IdeaTableConnectionFactory.generateMapperFiles(generator, table).isEmpty());

		Path model = javaOutput.resolve("fixture/Sample.java");
		String modelSource = Files.readString(model);
		assertTrue(modelSource.contains("LocalDateTime createdAt"));
		assertTrue(modelSource.contains("创建时间"));
		assertTrue(modelSource.contains("@Id"));
		assertEquals(2, modelSource.split("@GeneratedValue", -1).length);

		Path mapper = xmlOutput.resolve("fixture/SampleMapper.xml");
		Path stagedMapper = stage.resolve("fixture/SampleMapper.xml");
		String generated = Files.readString(stagedMapper);
		assertTrue(generated.contains("useGeneratedKeys=\"true\""));
		assertTrue(generated.contains("property=\"createdAt\""));
		assertTrue(generated.contains("property=\"computedValue\""));
		var writes = Pattern.compile("(?s)<(insert|update)\\b.*?</\\1>").matcher(generated).results().toList();
		assertTrue(writes.stream().anyMatch(write -> "insert".equals(write.group(1))));
		assertTrue(writes.stream().filter(write -> "insert".equals(write.group(1)))
			.noneMatch(write -> write.group().contains("#{id")));
		assertTrue(writes.stream().anyMatch(write -> "update".equals(write.group(1))));
		assertTrue(writes.stream().allMatch(write -> write.group().contains("created_at")
			&& !write.group().contains("computed_value")));
		MapperXmlPublisher.publishMapperXml(generator.getGeneratedXmlFiles(), stage, xmlOutput, false);

		String withManualSql = generated.replace("</mapper>", "<select id=\"manual\">SELECT 1</select></mapper>");
		Files.writeString(mapper, withManualSql);
		Files.writeString(stagedMapper, generated);
		MapperXmlPublisher.publishMapperXml(generator.getGeneratedXmlFiles(), stage, xmlOutput, false);
		String merged = Files.readString(mapper);
		assertTrue(merged.contains("id=\"manual\""));
		assertEquals(2, merged.split("id=\"insert\"", -1).length);

		String legacy = withManualSql.replace("<!-- @mbg.generated -->", "");
		Files.writeString(mapper, legacy);
		Files.writeString(stagedMapper, generated);
		assertThrows(IOException.class,
			() -> MapperXmlPublisher.publishMapperXml(generator.getGeneratedXmlFiles(), stage, xmlOutput, false));
		assertEquals(legacy, Files.readString(mapper));

		MapperXmlPublisher.publishMapperXml(generator.getGeneratedXmlFiles(), stage, xmlOutput, true);
		assertEquals(generated, Files.readString(mapper));
	}

	/**
	 * SQL Server 的 Schema 和表名需保留原始空格和保留字，并转义名称内的右方括号。
	 *
	 * @param workspace 测试输出目录
	 * @throws Exception 生成 Mapper 或读取文件失败
	 */
	@Test
	void delimitsSqlServerReservedTableAndSchema(@TempDir Path workspace) throws Exception {
		for (var sample : List.of(
			List.of("group", "order", "[group].[order]"),
			List.of("group] 2", "order] list", "[group]] 2].[order]] list]"))) {
			Path javaOutput = Files.createTempDirectory(workspace, "java-");
			Path xmlOutput = Files.createTempDirectory(workspace, "xml-");
			var table = sampleTable();
			table.setTableName(sample.get(1));
			var source = new GenerationTable(table, DbType.SqlServer, sample.getFirst());
			var generator = buildSampleGenerator(javaOutput, xmlOutput, source);
			assertTrue(IdeaTableConnectionFactory.generateMapperFiles(generator, table).isEmpty());
			String mapper = Files.readString(xmlOutput.resolve("fixture/SampleMapper.xml"));
			assertTrue(mapper.contains(sample.getLast()));
		}
	}

	/** 真实表名中的空格、标点及数字前缀不会写入非法的 Java 类型名。 */
	@Test
	void derivesValidJavaNamesFromDatabaseTables() {
		assertEquals("StableFixture", StringUtils.dbStringToCamelStyle("STABLE_FIXTURE"));
		assertEquals("OrderList", StringUtils.dbStringToCamelStyle("order\" list"));
		assertEquals("Table2订单", StringUtils.dbStringToCamelStyle("2-订单"));
		assertEquals("Table", StringUtils.dbStringToCamelStyle("__"));
	}

	/**
	 * 批量生成前识别跨模式同名表、规范化名称和派生 Example 类型冲突。
	 *
	 * @throws ReflectiveOperationException 无法调用批量生成预检时失败
	 */
	@Test
	void detectsConflictingBatchJavaNamesBeforeGeneration() throws ReflectiveOperationException {
		var first = sampleTable();
		first.setTableName("stable fixture");
		var second = sampleTable();
		second.setTableName("stable_fixture");
		var check = MybatisGeneratorMainUI.class.getDeclaredMethod("findConflictingBatchModelName", List.class, boolean.class, String.class);
		check.setAccessible(true);
		assertEquals("StableFixture", check.invoke(null, List.of(new GenerationTable(first, DbType.H2, "FIRST"),
			new GenerationTable(second, DbType.H2, "SECOND")), false, null));
		second.setTableName("stable fixture");
		assertEquals("StableFixture", check.invoke(null, List.of(new GenerationTable(first, DbType.H2, "FIRST"),
			new GenerationTable(second, DbType.H2, "SECOND")), false, null));
		second.setTableName("different_fixture");
		assertEquals("", check.invoke(null, List.of(new GenerationTable(first, DbType.H2, "FIRST"),
			new GenerationTable(second, DbType.H2, "SECOND")), false, null));
		second.setTableName("stable_fixture_example");
		var firstTable = new GenerationTable(first, DbType.H2, "FIRST");
		var secondTable = new GenerationTable(second, DbType.H2, "SECOND");
		assertEquals("", check.invoke(null, List.of(firstTable, secondTable), false, null));
		assertEquals("StableFixtureExample", check.invoke(null, List.of(firstTable, secondTable), true, null));
		assertEquals("StableFixtureExample", check.invoke(null, List.of(secondTable, firstTable), true, null));
		second.setTableName("stable_fixture_mapper");
		assertEquals("", check.invoke(null, List.of(firstTable, secondTable), false, null));
		assertEquals("StableFixtureMapper", check.invoke(null, List.of(firstTable, secondTable), false, "Mapper"));
	}

	/**
	 * Mapper 引用含空格的库、模式和表名，SQLite 文件库可省略模式前缀。
	 *
	 * @param workspace 测试输出目录
	 * @throws Exception 生成 Mapper 或读取文件失败
	 */
	@Test
	void escapesDatabaseAndTableIdentifierDelimiters(@TempDir Path workspace) throws Exception {
		for (DbType type : List.of(DbType.MySQL, DbType.MariaDB, DbType.PostgreSQL, DbType.H2, DbType.SQLite, DbType.Oracle)) {
			String delimiter = type == DbType.MySQL || type == DbType.MariaDB ? "`" : "\"";
			String nameDelimiter = type == DbType.Oracle ? "" : delimiter;
			Path javaOutput = Files.createTempDirectory(workspace, "java-");
			Path xmlOutput = Files.createTempDirectory(workspace, "xml-");
			var table = sampleTable();
			table.setTableName("order" + nameDelimiter + " list");
			String qualifier = type == DbType.SQLite ? null : "group" + nameDelimiter + " 2";
			var source = new GenerationTable(table, type, qualifier);
			var generator = buildSampleGenerator(javaOutput, xmlOutput, source);
			assertTrue(IdeaTableConnectionFactory.generateMapperFiles(generator, table).isEmpty());
			String mapper = Files.readString(xmlOutput.resolve("fixture/SampleMapper.xml"));
			String expected = delimiter + table.getTableName().replace(delimiter, delimiter.repeat(2)) + delimiter;
			if (Objects.nonNull(qualifier)) {
				expected = delimiter + qualifier.replace(delimiter, delimiter.repeat(2)) + delimiter + "." + expected;
			}
			assertTrue(mapper.contains(expected));
		}
	}

	/**
	 * 在真实 H2 库中确认估算计划不会执行 DELETE，并按 SQL 词法处理字符串分号、前置注释和多语句输入。
	 *
	 * @throws Exception JDBC 初始化、查询或执行计划失败
	 */
	@Test
	void estimatesSqlWithoutExecutingDelete() throws Exception {
		String driverClass = "org.h2.Driver";
		Path driverJar = Path.of(System.getProperty("workbench.estimatedPlanTestDriver"));
		try (var database = new JdbcDriverConnection(List.of(driverJar), driverClass, "jdbc:h2:mem:estimated-plan",
			new Properties())) {
			database.executeSql("CREATE TABLE sample (id INTEGER PRIMARY KEY)", 10, 5);
			database.executeSql("INSERT INTO sample (id) VALUES (1)", 10, 5);

			assertTrue(!database.explainSql(DbType.H2, "DELETE FROM sample WHERE id = 1;", 10, 5).rows().isEmpty());
			assertTrue(!database.explainSql(DbType.H2, "-- leading comment\nSELECT 'a;b' FROM sample", 10, 5)
				.rows().isEmpty());
			assertEquals("SELECT 'a;b' FROM sample", JdbcSqlStatementLocator.singleStatementForExplain(DbType.H2,
				"/* leading comment */ SELECT 'a;b' FROM sample;"));
			assertTrue(!database.explainSql(DbType.H2, "/* leading comment */ SELECT 'a;b' FROM sample;", 10, 5)
				.rows().isEmpty());
			assertThrows(IllegalArgumentException.class,
				() -> JdbcSqlStatementLocator.singleStatementForExplain(DbType.H2, "SELECT 1;;"));
			var english = ResourceBundle.getBundle("messages.WorkbenchBundle", Locale.ROOT);
			var chinese = ResourceBundle.getBundle("messages.WorkbenchBundle", Locale.SIMPLIFIED_CHINESE);
			String script = "SELECT id FROM sample; DELETE FROM sample";
			assertEquals("Select one SELECT, WITH, INSERT, UPDATE, or DELETE statement that supports an estimated plan. Multiple statements and client-side script delimiters are not supported.",
				assertThrows(IllegalArgumentException.class,
					() -> database.explainSql(DbType.H2, script, 10, 5, english::getString)).getMessage());
			assertEquals("请选择一条支持估算计划的 SELECT、WITH、INSERT、UPDATE 或 DELETE 语句。不支持多条语句或客户端脚本分隔指令。",
				assertThrows(IllegalArgumentException.class,
					() -> database.explainSql(DbType.H2, script, 10, 5, chinese::getString)).getMessage());
			assertEquals(List.of(List.of("1")), database.executeSql("SELECT id FROM sample", 10, 5).rows());
		String cursorScript = "SELECT id FROM sample; INSERT INTO sample VALUES (2);";
		String selectedFirst = JdbcSqlStatementLocator.locateAtCaret(DbType.H2, cursorScript,
			cursorScript.indexOf("SELECT"));
		assertEquals("SELECT id FROM sample", selectedFirst);
		database.executeSql(selectedFirst, 10, 5);
		assertEquals(List.of(List.of("1")), database.executeSql("SELECT id FROM sample ORDER BY id", 10, 5).rows());
		String selectedSecond = JdbcSqlStatementLocator.locateAtCaret(DbType.H2, cursorScript,
			cursorScript.indexOf("INSERT"));
		assertEquals("INSERT INTO sample VALUES (2)", selectedSecond);
		}
	}

	/**
	 * 验证无选区控制台定位只返回光标所在 SQL，词法引号与注释中的分号不切句，脚本语法边界拒绝猜测。
	 */
	@Test
	void locatesOnlyTheSqlStatementAtCaret() {
		String script = "SELECT 'a;b' AS value;\n  INSERT INTO sample VALUES (1);";
		int firstSeparator = script.indexOf(";\n");
		int secondStart = script.indexOf("INSERT");
		assertEquals("SELECT 'a;b' AS value", JdbcSqlStatementLocator.locateAtCaret(DbType.H2, script,
			script.indexOf("SELECT")));
		assertEquals("SELECT 'a;b' AS value", JdbcSqlStatementLocator.locateAtCaret(DbType.H2, script,
			firstSeparator));
		assertThrows(IllegalArgumentException.class,
			() -> JdbcSqlStatementLocator.locateAtCaret(DbType.H2, script, firstSeparator + 1));
		assertEquals("INSERT INTO sample VALUES (1)", JdbcSqlStatementLocator.locateAtCaret(DbType.H2, script,
			secondStart));
		assertEquals("INSERT INTO sample VALUES (1)", JdbcSqlStatementLocator.locateAtCaret(DbType.H2, script,
			script.length()));

		String commented = "-- ; leading comment\nSELECT 1 /* ; inline comment */; -- gap\nSELECT 2";
		assertEquals("-- ; leading comment\nSELECT 1 /* ; inline comment */",
			JdbcSqlStatementLocator.locateAtCaret(DbType.H2, commented, commented.indexOf("SELECT 1")));
		assertThrows(IllegalArgumentException.class,
			() -> JdbcSqlStatementLocator.locateAtCaret(DbType.H2, commented, commented.indexOf("-- gap") + 2));
		assertEquals("-- gap\nSELECT 2", JdbcSqlStatementLocator.locateAtCaret(DbType.H2, commented,
			commented.lastIndexOf("SELECT 2")));
		assertEquals("SELECT $$a;b$$", JdbcSqlStatementLocator.locateAtCaret(DbType.PostgreSQL,
			"SELECT $$a;b$$; SELECT 2", 2));
		assertEquals("SELECT q'[a;b]' FROM dual", JdbcSqlStatementLocator.locateAtCaret(DbType.Oracle,
			"SELECT q'[a;b]' FROM dual; SELECT 2", 2));
		assertEquals("SELECT [a;b]", JdbcSqlStatementLocator.locateAtCaret(DbType.SqlServer,
			"SELECT [a;b]; SELECT 2", 2));
		assertEquals("SELECT `a;b`", JdbcSqlStatementLocator.locateAtCaret(DbType.MySQL,
			"SELECT `a;b`; SELECT 2", 2));
		assertThrows(IllegalArgumentException.class,
			() -> JdbcSqlStatementLocator.locateAtCaret(DbType.SqlServer, "SELECT 1\nGO\nSELECT 2", 1));
		assertThrows(IllegalArgumentException.class,
			() -> JdbcSqlStatementLocator.locateAtCaret(DbType.MySQL, "DELIMITER //\nSELECT 1//", 15));
		assertThrows(IllegalArgumentException.class,
			() -> JdbcSqlStatementLocator.locateAtCaret(DbType.MySQL, "SELECT /*! ; DELETE FROM sample */ 1", 2));
		assertThrows(IllegalArgumentException.class,
			() -> JdbcSqlStatementLocator.locateAtCaret(DbType.MariaDB, "SELECT /*M! ; DELETE FROM sample */ 1", 2));
		assertThrows(IllegalArgumentException.class,
			() -> JdbcSqlStatementLocator.locateAtCaret(DbType.SQLite,
				"CREATE TRIGGER sample_trigger AFTER INSERT ON sample BEGIN SELECT 1; SELECT 2; END;", 20));
		assertThrows(IllegalArgumentException.class,
			() -> JdbcSqlStatementLocator.locateAtCaret(DbType.H2, "SELECT 'backslash\\' ; SELECT 2", 2));
	}

	/**
	 * 验证跨越复合主键分组后继续读取，末页不会再提供续读入口。
	 *
	 * @throws Exception H2 连接或分页查询失败
	 */
	@Test
	void continuesTablePreviewByCompositePrimaryKey() throws Exception {
		Path driver = Path.of(System.getProperty("workbench.estimatedPlanTestDriver"));
		try (var database = new JdbcDriverConnection(List.of(driver), "org.h2.Driver", "jdbc:h2:mem:table-pages",
			new Properties())) {
			database.executeSql("CREATE TABLE sample (bucket INTEGER, item INTEGER, PRIMARY KEY (bucket, item))", 10, 5);
			database.executeSql("INSERT INTO sample (bucket, item) SELECT CASE WHEN X <= 103 THEN 1 ELSE 2 END, X FROM SYSTEM_RANGE(1, 205)",
				10, 5);
			var connection = database.connection();
			var table = JdbcTableMetadata.listTables(connection.getMetaData(), connection.getCatalog(), "PUBLIC").stream()
				.filter(candidate -> candidate.name().equals("SAMPLE")).findFirst().orElseThrow();
			database.executeSql("CREATE TABLE multi_sample (id INTEGER PRIMARY KEY, label VARCHAR(40), bucket INTEGER NOT NULL)",
				10, 5);
		database.executeSql("INSERT INTO multi_sample SELECT X, CASE WHEN MOD(X, 2) = 0 THEN 'tea AND cake' "
			+ "ELSE 'other' END, CASE WHEN X <= 120 THEN 1 ELSE 2 END FROM SYSTEM_RANGE(1, 220)", 10, 5);
		var multiTable = JdbcTableMetadata.listTables(connection.getMetaData(), connection.getCatalog(), "PUBLIC").stream()
			.filter(candidate -> candidate.name().equals("MULTI_SAMPLE")).findFirst().orElseThrow();
		var multiOptions = JdbcTableQueryExpressionParser.parse(
			"ID > 0 AND LABEL = 'tea AND cake' AND BUCKET >= 1", "ID ASC",
			JdbcTableMetadata.readGenerationTable(connection.getMetaData(), DbType.H2, multiTable).tableInfo()
				.getColumnInfos().stream().map(IntellijColumnInfo::getName).toList(), key -> key);
		var multiFirst = JdbcTableEditor.previewTableRows(database, DbType.H2, multiTable, multiOptions, key -> key);
		assertEquals(100, multiFirst.result().rows().size());
		assertEquals(List.of("2", "tea AND cake", "1"), multiFirst.result().rows().getFirst());
		assertTrue(multiFirst.hasNextPage());
		var multiSecond = JdbcTableEditor.readNextTableRows(database, DbType.H2, multiFirst, key -> key);
		assertEquals(10, multiSecond.result().rows().size());
		assertFalse(multiSecond.hasNextPage());
		assertEquals(List.of("220", "tea AND cake", "2"), multiSecond.result().rows().getLast());
		assertEquals(multiFirst.result().rows(), JdbcTableEditor.readPreviousTableRows(database, DbType.H2,
			multiSecond, key -> key).result().rows());
			var first = JdbcTableEditor.previewTableRows(database, DbType.H2, table);
			assertEquals(List.of("1", "100"), first.result().rows().getLast());
			var second = JdbcTableEditor.readNextTableRows(database, DbType.H2, first);
			assertEquals(List.of("1", "101"), second.result().rows().getFirst());
			assertEquals(List.of("2", "200"), second.result().rows().getLast());
			var third = JdbcTableEditor.readNextTableRows(database, DbType.H2, second);
			assertEquals(List.of(List.of("2", "201"), List.of("2", "202"), List.of("2", "203"),
				List.of("2", "204"), List.of("2", "205")), third.result().rows());
			assertFalse(third.hasNextPage());
			assertFalse(first.hasPreviousPage());
			var backToSecond = JdbcTableEditor.readPreviousTableRows(database, DbType.H2, third);
			assertEquals(second.result().rows(), backToSecond.result().rows());
			var backToFirst = JdbcTableEditor.readPreviousTableRows(database, DbType.H2, backToSecond);
			assertEquals(first.result().rows(), backToFirst.result().rows());
			assertFalse(backToFirst.hasPreviousPage());
			assertTrue(backToFirst.hasNextPage());
			assertThrows(SQLException.class, () -> JdbcTableEditor.readNextTableRows(database, DbType.H2, third));
			database.executeSql("DELETE FROM sample WHERE bucket = 2 AND item > 200", 10, 5);
			assertTrue(JdbcTableEditor.readNextTableRows(database, DbType.H2, second).result().rows().isEmpty());
		}
	}

	/**
	 * 验证服务端条件值绑定、非主键排序的复合 keyset 续读、排序列边界和空条件有界读取。
	 *
	 * @throws Exception 测试专用 H2 查询或元数据读取失败
	 */
	@Test
	void filtersAndSortsTablePreviewWithBoundedKeysetPages() throws Exception {
		Path driver = Path.of(System.getProperty("workbench.estimatedPlanTestDriver"));
		try (var database = new JdbcDriverConnection(List.of(driver), "org.h2.Driver", "jdbc:h2:mem:table-query-options",
			new Properties())) {
			database.executeSql("CREATE TABLE sample (id INTEGER PRIMARY KEY, bucket INTEGER NOT NULL, label VARCHAR(20))",
				10, 5);
			database.executeSql("INSERT INTO sample SELECT X, CASE WHEN X <= 120 THEN 1 ELSE 2 END, "
				+ "CASE WHEN MOD(X, 2) = 0 THEN 'even' ELSE 'odd' END FROM SYSTEM_RANGE(1, 220)", 10, 5);
			var connection = database.connection();
			var table = JdbcTableMetadata.listTables(connection.getMetaData(), connection.getCatalog(), "PUBLIC").stream()
				.filter(candidate -> candidate.name().equals("SAMPLE")).findFirst().orElseThrow();
			var options = new JdbcTableQueryOptions("LABEL", JdbcTableFilterOperator.EQUALS, "odd", "BUCKET", true);
			var first = JdbcTableEditor.previewTableRows(database, DbType.H2, table, options, key -> key);
			assertEquals(100, first.result().rows().size());
		assertEquals(List.of("121", "2", "odd"), first.result().rows().getFirst());
		assertEquals(List.of("99", "1", "odd"), first.result().rows().getLast());
			assertTrue(first.hasNextPage());
			var second = JdbcTableEditor.readNextTableRows(database, DbType.H2, first, key -> key);
		assertEquals(List.of("101", "1", "odd"), second.result().rows().getFirst());
		assertEquals(List.of("119", "1", "odd"), second.result().rows().getLast());
			assertFalse(second.hasNextPage());
			assertEquals(options, second.queryOptions());
			assertEquals(first.result().rows(), JdbcTableEditor.readPreviousTableRows(database, DbType.H2, second,
				key -> key).result().rows());

			var injected = new JdbcTableQueryOptions("LABEL", JdbcTableFilterOperator.EQUALS,
				"odd' OR 1=1 --", "", false);
			assertTrue(JdbcTableEditor.previewTableRows(database, DbType.H2, table, injected, key -> key).result().rows().isEmpty());
			var prefixLike = JdbcTableQueryExpressionParser.parse("LABEL LIKE 'od%'", "",
				first.result().columnLabels(), key -> key);
			var prefixLikePage = JdbcTableEditor.previewTableRows(database, DbType.H2, table, prefixLike, key -> key);
			assertEquals(100, prefixLikePage.result().rows().size());
			assertTrue(prefixLikePage.hasNextPage());
			assertTrue(prefixLikePage.result().rows().stream().allMatch(row -> row.getLast().equals("odd")));
			var prefixLikeNextPage = JdbcTableEditor.readNextTableRows(database, DbType.H2, prefixLikePage, key -> key);
			assertEquals(10, prefixLikeNextPage.result().rows().size());
			assertFalse(prefixLikeNextPage.hasNextPage());
			assertTrue(prefixLikeNextPage.result().rows().stream().allMatch(row -> row.getLast().equals("odd")));
			assertEquals(prefixLikePage.result().rows(), JdbcTableEditor.readPreviousTableRows(database, DbType.H2,
				prefixLikeNextPage, key -> key).result().rows());
			var inOptions = JdbcTableQueryExpressionParser.parse("LABEL IN ('odd', 'missing')", "ID ASC",
				first.result().columnLabels(), key -> key);
			var inFirstPage = JdbcTableEditor.previewTableRows(database, DbType.H2, table, inOptions, key -> key);
			assertEquals(100, inFirstPage.result().rows().size());
			assertTrue(inFirstPage.hasNextPage());
			assertTrue(inFirstPage.result().rows().stream().allMatch(row -> row.getLast().equals("odd")));
			var inSecondPage = JdbcTableEditor.readNextTableRows(database, DbType.H2, inFirstPage, key -> key);
			assertEquals(10, inSecondPage.result().rows().size());
			assertFalse(inSecondPage.hasNextPage());
			assertEquals(inFirstPage.result().rows(), JdbcTableEditor.readPreviousTableRows(database, DbType.H2,
				inSecondPage, key -> key).result().rows());
			var inInjection = JdbcTableQueryExpressionParser.parse("LABEL IN ('odd'' OR 1=1 --')", "",
				first.result().columnLabels(), key -> key);
			assertTrue(JdbcTableEditor.previewTableRows(database, DbType.H2, table, inInjection, key -> key)
				.result().rows().isEmpty());
			var numericPrefixLike = JdbcTableQueryExpressionParser.parse("BUCKET LIKE '1%'", "",
				first.result().columnLabels(), key -> key);
			assertEquals("table.filter.like.requires.text.column", assertThrows(SQLException.class,
				() -> JdbcTableEditor.previewTableRows(database, DbType.H2, table, numericPrefixLike, key -> key))
				.getMessage());
			var noSortNullable = new JdbcTableQueryOptions("", JdbcTableFilterOperator.EQUALS, "", "LABEL", false);
			assertEquals("table.sort.requires.non.null.scalar.column", assertThrows(SQLException.class,
				() -> JdbcTableEditor.previewTableRows(database, DbType.H2, table, noSortNullable, key -> key))
				.getMessage());
			var noFilter = JdbcTableEditor.previewTableRows(database, DbType.H2, table);
			assertEquals(100, noFilter.result().rows().size());
			assertTrue(noFilter.hasNextPage());

		database.executeSql("CREATE TABLE null_sample (id INTEGER PRIMARY KEY, text_value VARCHAR(20))", 10, 5);
		database.executeSql("INSERT INTO null_sample VALUES (1, NULL), (2, ''), (3, 'present')", 10, 5);
		var nullTable = JdbcTableMetadata.listTables(connection.getMetaData(), connection.getCatalog(), "PUBLIC").stream()
			.filter(candidate -> candidate.name().equals("NULL_SAMPLE")).findFirst().orElseThrow();
		var nullFilter = new JdbcTableQueryOptions("TEXT_VALUE", JdbcTableFilterOperator.IS_NULL, "", "", false);
		assertEquals(List.of("1"), JdbcTableEditor.previewTableRows(database, DbType.H2, nullTable, nullFilter,
			key -> key).result().rows().stream().map(row -> row.getFirst()).toList());
		var notNullFilter = new JdbcTableQueryOptions("TEXT_VALUE", JdbcTableFilterOperator.IS_NOT_NULL, "", "", false);
		assertEquals(List.of("2", "3"), JdbcTableEditor.previewTableRows(database, DbType.H2, nullTable, notNullFilter,
			key -> key).result().rows().stream().map(row -> row.getFirst()).toList());
		var emptyValueFilter = new JdbcTableQueryOptions("TEXT_VALUE", JdbcTableFilterOperator.EQUALS, "", "", false);
		assertEquals(List.of("2"), JdbcTableEditor.previewTableRows(database, DbType.H2, nullTable, emptyValueFilter,
			key -> key).result().rows().stream().map(row -> row.getFirst()).toList());
		var inEmptyString = JdbcTableQueryExpressionParser.parse("TEXT_VALUE IN ('')", "",
			List.of("ID", "TEXT_VALUE"), key -> key);
		assertEquals(List.of("2"), JdbcTableEditor.previewTableRows(database, DbType.H2, nullTable, inEmptyString,
			key -> key).result().rows().stream().map(row -> row.getFirst()).toList());

		database.executeSql("CREATE TABLE temporal_sample (id INTEGER PRIMARY KEY, event_date DATE NOT NULL, "
			+ "event_time TIME NOT NULL, event_timestamp TIMESTAMP NOT NULL)", 10, 5);
		try (var insert = connection.prepareStatement("INSERT INTO temporal_sample VALUES (?, ?, ?, ?)")) {
			for (int index = 0; index < 220; index++) {
				insert.setInt(1, index + 1);
				insert.setDate(2, Date.valueOf(LocalDate.of(2026, 1, 1).plusDays(index)));
				insert.setTime(3, Time.valueOf(LocalTime.MIDNIGHT.plusSeconds(index)));
				insert.setTimestamp(4, Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 0, 0).plusSeconds(index)));
				insert.executeUpdate();
			}
		}
		var temporalTable = JdbcTableMetadata.listTables(connection.getMetaData(), connection.getCatalog(), "PUBLIC").stream()
			.filter(candidate -> candidate.name().equals("TEMPORAL_SAMPLE")).findFirst().orElseThrow();
		for (String temporalColumn : List.of("EVENT_DATE", "EVENT_TIME", "EVENT_TIMESTAMP")) {
			var temporalOptions = new JdbcTableQueryOptions("", JdbcTableFilterOperator.EQUALS, "", temporalColumn, true);
			var temporalFirst = JdbcTableEditor.previewTableRows(database, DbType.H2, temporalTable, temporalOptions,
				key -> key);
			assertEquals("220", temporalFirst.result().rows().getFirst().getFirst());
			assertEquals("121", temporalFirst.result().rows().getLast().getFirst());
			var temporalSecond = JdbcTableEditor.readNextTableRows(database, DbType.H2, temporalFirst, key -> key);
			assertEquals("120", temporalSecond.result().rows().getFirst().getFirst());
			assertEquals(temporalFirst.result().rows(), JdbcTableEditor.readPreviousTableRows(database, DbType.H2,
				temporalSecond, key -> key).result().rows());
		}
		}
	}

	/**
	 * 验证工作台 WHERE/ORDER BY 文本只解析受支持的有界 AND 条件，并保留参数绑定与列白名单边界。
	 */
	@Test
	void parsesBoundedTableQueryExpressions() {
		List<String> columns = List.of("NAME", "QUANTITY", "CREATED_AT", "tick`name", "bracket]name", "double\"name",
			"and name");
		var escapedValue = JdbcTableQueryExpressionParser.parse("name = 'O''Brien'", "created_at DESC",
			columns, key -> key);
		assertEquals("NAME", escapedValue.filterColumn());
		assertEquals(JdbcTableFilterOperator.EQUALS, escapedValue.filterOperator());
		assertEquals("O'Brien", escapedValue.filterValue());
		assertEquals("CREATED_AT", escapedValue.sortColumn());
		assertTrue(escapedValue.sortDescending());

		var nullCondition = JdbcTableQueryExpressionParser.parse("NAME IS NOT NULL", "", columns, key -> key);
		assertEquals(JdbcTableFilterOperator.IS_NOT_NULL, nullCondition.filterOperator());
		var ascending = JdbcTableQueryExpressionParser.parse("QUANTITY >= 10", "CREATED_AT", columns, key -> key);
		assertEquals("10", ascending.filterValue());
		assertFalse(ascending.sortDescending());
		var mysqlQuoted = JdbcTableQueryExpressionParser.parse("`tick``name` = 'value'", "[bracket]]name] DESC",
			columns, key -> key);
		assertEquals("tick`name", mysqlQuoted.filterColumn());
		assertEquals("bracket]name", mysqlQuoted.sortColumn());
		assertTrue(mysqlQuoted.sortDescending());
		var sqlServerQuoted = JdbcTableQueryExpressionParser.parse("[bracket]]name] = 'x'", "\"double\"\"name\" ASC",
			columns, key -> key);
		assertEquals("bracket]name", sqlServerQuoted.filterColumn());
		assertEquals("double\"name", sqlServerQuoted.sortColumn());
		var prefixLike = JdbcTableQueryExpressionParser.parse("NAME LIKE 'O''Brien%'", "", columns, key -> key);
		assertEquals(JdbcTableFilterOperator.PREFIX_LIKE, prefixLike.filterOperator());
		assertEquals("O'Brien%", prefixLike.filterValue());
		var inList = JdbcTableQueryExpressionParser.parse("NAME IN ('O''Brien', 'comma,value', 12)", "",
			columns, key -> key);
		assertEquals(JdbcTableFilterOperator.IN, inList.filterOperator());
		assertEquals(List.of("O'Brien", "comma,value", "12"), inList.filterValues());
		assertEquals(inList, JdbcTableQueryExpressionParser.parse(
			JdbcTableQueryExpressionParser.formatWhereExpression(inList), "", columns, key -> key));
		assertThrows(UnsupportedOperationException.class, () -> inList.filterValues().add("mutated"));
		var inListWithEmptyString = JdbcTableQueryExpressionParser.parse("NAME IN ('', 'value')", "",
			columns, key -> key);
		assertEquals(List.of("", "value"), inListWithEmptyString.filterValues());
		assertEquals(inListWithEmptyString, JdbcTableQueryExpressionParser.parse(
			JdbcTableQueryExpressionParser.formatWhereExpression(inListWithEmptyString), "", columns, key -> key));
		var multiCondition = JdbcTableQueryExpressionParser.parse(
			"NAME = 'tea AND cake' AND QUANTITY IN (1, '2') AND CREATED_AT IS NOT NULL", "",
			columns, key -> key);
		assertEquals(3, multiCondition.filters().size());
		assertEquals("tea AND cake", multiCondition.filters().getFirst().value());
		assertEquals(List.of("1", "2"), multiCondition.filters().get(1).values());
		assertEquals(multiCondition, JdbcTableQueryExpressionParser.parse(
			JdbcTableQueryExpressionParser.formatWhereExpression(multiCondition), "", columns, key -> key));
		var quotedAnd = JdbcTableQueryExpressionParser.parse(
			"[bracket]]name] = 'a'' AND b' AND `tick``name` = 'left AND right' AND \"and name\" = 'x'",
			"", columns, key -> key);
		assertEquals("a' AND b", quotedAnd.filters().getFirst().value());
		assertEquals("left AND right", quotedAnd.filters().get(1).value());
		assertEquals("and name", quotedAnd.filters().getLast().column());

		assertThrows(IllegalArgumentException.class,
			() -> JdbcTableQueryExpressionParser.parse("unknown = 'x'", "", columns, key -> key));
		assertThrows(IllegalArgumentException.class,
			() -> JdbcTableQueryExpressionParser.parse("QUANTITY > 1 AND 1=1", "", columns, key -> key));
		for (String invalidAnd : List.of("AND NAME = 'x'", "NAME = 'x' AND", "NAME = 'x' AND AND QUANTITY > 1",
			"NAME = 'x' OR QUANTITY > 1", "(NAME = 'x') AND QUANTITY > 1", "NAME = 'x'; AND QUANTITY > 1",
			"NAME = 'unterminated AND QUANTITY > 1", "NAME = 'x' -- comment\n AND QUANTITY > 1")) {
			assertThrows(IllegalArgumentException.class,
				() -> JdbcTableQueryExpressionParser.parse(invalidAnd, "", columns, key -> key));
		}
		String oversizedCombinedIn = "NAME IN (" + String.join(",", Collections.nCopies(51, "'x'"))
			+ ") AND QUANTITY IN (" + String.join(",", Collections.nCopies(50, "'y'")) + ")";
		assertThrows(IllegalArgumentException.class,
			() -> JdbcTableQueryExpressionParser.parse(oversizedCombinedIn, "", columns, key -> key));
		String tooManyConditions = String.join(" AND ", Collections.nCopies(
			JdbcTableQueryOptions.MAX_FILTER_CONDITIONS + 1, "NAME IS NOT NULL"));
		assertThrows(IllegalArgumentException.class,
			() -> JdbcTableQueryExpressionParser.parse(tooManyConditions, "", columns, key -> key));
		assertThrows(IllegalArgumentException.class,
			() -> JdbcTableQueryExpressionParser.parse("", "CREATED_AT DESC, NAME", columns, key -> key));
		assertThrows(IllegalArgumentException.class,
			() -> JdbcTableQueryExpressionParser.parse("`tick` = 'x'", "", columns, key -> key));
		assertThrows(IllegalArgumentException.class,
			() -> JdbcTableQueryExpressionParser.parse("", "[bracket]name DESC", columns, key -> key));
		for (String invalidLike : List.of("NAME LIKE '%prefix'", "NAME LIKE '%'", "NAME LIKE 'pre%fix%'",
			"NAME LIKE 'pre_fix%'", "NAME LIKE '[ab]%'", "NAME LIKE '[^a]%'", "NAME LIKE 'pre\\\\fix%'",
			"NAME LIKE prefix%")) {
			assertThrows(IllegalArgumentException.class,
				() -> JdbcTableQueryExpressionParser.parse(invalidLike, "", columns, key -> key));
		}
		for (String invalidIn : List.of("NAME IN ()", "NAME IN ('a',, 'b')", "NAME IN ('a',)",
			"NAME IN (NULL)", "NAME IN ('unterminated)", "NAME IN ('x'")) {
			assertThrows(IllegalArgumentException.class,
				() -> JdbcTableQueryExpressionParser.parse(invalidIn, "", columns, key -> key));
		}
		String oversizedIn = "NAME IN (" + String.join(", ", Collections.nCopies(
			JdbcTableQueryOptions.MAX_IN_VALUES + 1, "'value'")) + ")";
		assertThrows(IllegalArgumentException.class,
			() -> JdbcTableQueryExpressionParser.parse(oversizedIn, "", columns, key -> key));
		String maximumIn = "NAME IN (" + String.join(", ", Collections.nCopies(
			JdbcTableQueryOptions.MAX_IN_VALUES, "'value'")) + ")";
		assertEquals(JdbcTableQueryOptions.MAX_IN_VALUES,
			JdbcTableQueryExpressionParser.parse(maximumIn, "", columns, key -> key).filterValues().size());
	}

	/**
	 * 验证 SQLite JDBC 对 INTEGER PRIMARY KEY 的 nullable 元数据偏差不会关闭后续有界分页。
	 *
	 * @throws Exception SQLite 测试驱动或分页查询失败
	 */
	@Test
	void continuesSqliteIntegerPrimaryKeyTablePages() throws Exception {
		Path driver = Path.of(System.getProperty("workbench.sqliteMigrationTestDriver"));
		try (var database = new JdbcDriverConnection(List.of(driver), "org.sqlite.JDBC", "jdbc:sqlite::memory:",
			new Properties())) {
			database.executeSql("CREATE TABLE sample (id INTEGER PRIMARY KEY, bucket INTEGER NOT NULL, label TEXT)", 10, 5);
			database.executeSql("WITH RECURSIVE seq(value) AS (SELECT 1 UNION ALL SELECT value + 1 FROM seq WHERE value < 220) "
				+ "INSERT INTO sample SELECT value, value % 3, CASE WHEN value % 2 = 0 THEN 'even' ELSE 'odd' END FROM seq",
				10, 5);
			var connection = database.connection();
			var table = JdbcTableMetadata.listTables(connection.getMetaData(), connection.getCatalog(), null).stream()
				.filter(candidate -> candidate.name().equals("sample")).findFirst().orElseThrow();
			var options = new JdbcTableQueryOptions("label", JdbcTableFilterOperator.EQUALS, "odd", "bucket", true);
			var first = JdbcTableEditor.previewTableRows(database, DbType.SQLite, table, options, key -> key);
			assertEquals(100, first.result().rows().size());
			assertTrue(first.hasNextPage());
			var second = JdbcTableEditor.readNextTableRows(database, DbType.SQLite, first, key -> key);
			assertTrue(second.result().rows().size() > 0);
		}
	}

	/**
	 * 验证新增行的默认值、空字符串和 SQL NULL 语义、重复主键拒绝、旧快照删除保护及事务状态恢复。
	 *
	 * @throws Exception H2 隔离数据库初始化或写入失败
	 */
	@Test
	void insertsAndDeletesTableRowsWithSnapshotProtection() throws Exception {
		Path driver = Path.of(System.getProperty("workbench.estimatedPlanTestDriver"));
		try (var database = new JdbcDriverConnection(List.of(driver), "org.h2.Driver", "jdbc:h2:mem:table-row-mutations",
			new Properties())) {
			database.executeSql("CREATE TABLE sample (id INTEGER PRIMARY KEY, label VARCHAR(20) DEFAULT 'fallback', "
				+ "quantity INTEGER NOT NULL)", 10, 5);
			database.executeSql("INSERT INTO sample (id, label, quantity) VALUES (1, 'old', 1), (2, 'keep', 2)", 10, 5);
			var connection = database.connection();
			var table = JdbcTableMetadata.listTables(connection.getMetaData(), connection.getCatalog(), "PUBLIC").stream()
				.filter(candidate -> candidate.name().equals("SAMPLE")).findFirst().orElseThrow();
			var page = JdbcTableEditor.previewTableRows(database, DbType.H2, table);
			JdbcTableEditor.insertTableRow(connection, DbType.H2, page, "{\"ID\":3,\"QUANTITY\":9}", key -> key);
			assertTrue(connection.getAutoCommit());
			var inserted = JdbcTableEditor.previewTableRows(database, DbType.H2, table);
			assertEquals(List.of("3", "fallback", "9"), inserted.result().rows().getLast());
			JdbcTableEditor.insertTableRow(connection, DbType.H2, inserted, "{\"ID\":4,\"LABEL\":null,\"QUANTITY\":10}",
				key -> key);
			var withNull = JdbcTableEditor.previewTableRows(database, DbType.H2, table);
			assertEquals(Arrays.asList("4", null, "10"), withNull.result().rows().getLast());
			JdbcTableEditor.insertTableRow(connection, DbType.H2, withNull,
				"{\"ID\":\"5\",\"LABEL\":\"\",\"QUANTITY\":\"11\"}", key -> key);
			var withEmptyText = JdbcTableEditor.previewTableRows(database, DbType.H2, table);
			assertEquals(List.of("5", "", "11"), withEmptyText.result().rows().getLast());
			assertThrows(SQLException.class,
				() -> JdbcTableEditor.insertTableRow(connection, DbType.H2, withNull, "{\"ID\":3,\"QUANTITY\":11}", key -> key));
			assertTrue(connection.getAutoCommit());

			var stale = withNull;
			database.executeSql("UPDATE sample SET label = 'changed' WHERE id = 1", 10, 5);
			assertThrows(SQLException.class, () -> JdbcTableEditor.deleteTableRow(connection, DbType.H2, stale, 0, key -> key));
			assertTrue(connection.getAutoCommit());
			assertEquals(1, database.executeSql("SELECT id FROM sample WHERE id = 1", 10, 5).rows().size());

			var fresh = JdbcTableEditor.previewTableRows(database, DbType.H2, table);
			int rowToDelete = fresh.result().rows().stream().map(row -> row.getFirst()).toList().indexOf("2");
			JdbcTableEditor.deleteTableRow(connection, DbType.H2, fresh, rowToDelete, key -> key);
			assertTrue(connection.getAutoCommit());
			assertTrue(database.executeSql("SELECT id FROM sample WHERE id = 2", 10, 5).rows().isEmpty());
		}
	}

	/**
	 * 验证 CSV 转义、不覆盖现有文件以及编码失败后的文件清理。
	 *
	 * @param workspace 当前测试的临时目录
	 * @throws IOException 写入或读取测试文件失败
	 */
	@Test
	void exportsCsvWithoutReplacingExistingFile(@TempDir Path workspace) throws IOException {
		var result = new JdbcQueryResult(List.of("标题", "空串", "空值"),
			List.of(Arrays.asList("含,引号\"及\n换行", "", null)), false, OptionalLong.empty());
		Path csv = workspace.resolve("result.csv");
		JdbcResultCsv.exportQueryResult(csv, result);
		String expected = "\"标题\",\"空串\",\"空值\"\r\n\"含,引号\"\"及\n换行\",\"\",\r\n";
		assertEquals(expected, Files.readString(csv));
		assertThrows(FileAlreadyExistsException.class, () -> JdbcResultCsv.exportQueryResult(csv, result));
		assertEquals(expected, Files.readString(csv));

		Path partial = workspace.resolve("partial.csv");
		var invalidUtf16 = new JdbcQueryResult(List.of("列"), List.of(List.of("\uD800")),
			false, OptionalLong.empty());
		assertThrows(IOException.class, () -> JdbcResultCsv.exportQueryResult(partial, invalidUtf16));
		assertFalse(Files.exists(partial));
	}

	/**
	 * 验证真实表的 CSV 往返、空值语义和整批失败时的回滚。
	 *
	 * @param workspace 测试专用目录
	 * @throws Exception 文件或真实 H2 连接失败
	 */
	@Test
	void importsCsvAndRollsBackPriorRowsAfterLaterFailure(@TempDir Path workspace) throws Exception {
		Path driverJar = Path.of(System.getProperty("workbench.estimatedPlanTestDriver"));
		try (var database = new JdbcDriverConnection(List.of(driverJar), "org.h2.Driver", "jdbc:h2:mem:csv-import",
			new Properties())) {
			database.executeSql("CREATE TABLE sample (id INTEGER PRIMARY KEY, value_text VARCHAR(50), note VARCHAR(50))",
				10, 5);
			var connection = database.connection();
			var table = JdbcTableMetadata.listTables(connection.getMetaData(), connection.getCatalog(), "PUBLIC")
				.stream().filter(item -> "SAMPLE".equals(item.name())).findFirst().orElseThrow();

			Path csv = workspace.resolve("valid.csv");
			Files.writeString(csv, "\uFEFFID,VALUE_TEXT,NOTE\r\n1,\"\",\r\n");
			assertEquals(1, JdbcCsvImport.importCsvRows(connection, DbType.H2, table, csv));
			assertEquals(List.of(Arrays.asList("1", "", null)),
				database.executeSql("SELECT id, value_text, note FROM sample ORDER BY id", 10, 5).rows());

			connection.setAutoCommit(true);
			Path duplicate = workspace.resolve("duplicate.csv");
			Files.writeString(duplicate, "ID,VALUE_TEXT\n2,first-write\n1,duplicate-primary-key\n");
			assertThrows(SQLException.class, () -> JdbcCsvImport.importCsvRows(connection, DbType.H2, table, duplicate));
			assertEquals(List.of(List.of("1")), database.executeSql("SELECT id FROM sample ORDER BY id", 10, 5).rows());

			connection.setAutoCommit(true);
			database.executeSql("CREATE TABLE restored (id INTEGER PRIMARY KEY, value_text VARCHAR(50), note VARCHAR(50))", 10, 5);
			var restored = JdbcTableMetadata.listTables(connection.getMetaData(), connection.getCatalog(), "PUBLIC").stream()
				.filter(item -> "RESTORED".equals(item.name())).findFirst().orElseThrow();
			String multiline = "中文,含\"引号\r\n和换行";
			try (var insert = connection.prepareStatement("INSERT INTO sample (id, value_text, note) VALUES (?, ?, ?)")) {
				insert.setInt(1, 2);
				insert.setString(2, multiline);
				insert.setString(3, "");
				assertEquals(1, insert.executeUpdate());
			}
			var original = database.executeSql("SELECT id, value_text, note FROM sample ORDER BY id", 10, 5);
			Path roundTrip = workspace.resolve("round-trip.csv");
			JdbcResultCsv.exportQueryResult(roundTrip, original);
			assertEquals(2, JdbcCsvImport.importCsvRows(connection, DbType.H2, restored, roundTrip));
			assertEquals(List.of(Arrays.asList("1", "", null), List.of("2", multiline, "")),
				database.executeSql("SELECT id, value_text, note FROM restored ORDER BY id", 10, 5).rows());
		}
	}

	/**
	 * 验证通过完整复合主键更新，以及其他连接修改后拒绝覆盖当前值。
	 *
	 * @param workspace H2 文件库的临时目录
	 * @throws Exception JDBC 连接、写入或元数据查询失败
	 */
	@Test
	void editsOnlyMatchingCompositeKeyAndRejectsStaleValue(@TempDir Path workspace) throws Exception {
		Path driverJar = Path.of(System.getProperty("workbench.estimatedPlanTestDriver"));
		String url = "jdbc:h2:file:" + workspace.resolve("table-editor");
		JdbcTableRows original;
		try (var database = new JdbcDriverConnection(List.of(driverJar), "org.h2.Driver", url, new Properties())) {
			database.executeSql("CREATE TABLE sample (tenant_id INTEGER, id INTEGER, label VARCHAR(50), fixed_label CHAR(4), "
				+ "PRIMARY KEY (tenant_id, id))", 10, 5);
			database.executeSql("INSERT INTO sample (tenant_id, id, label) VALUES (1, 1, 'before'), (1, 2, 'other')",
				10, 5);
			var connection = database.connection();
			var table = JdbcTableMetadata.listTables(connection.getMetaData(), connection.getCatalog(), "PUBLIC")
				.stream().filter(item -> "SAMPLE".equals(item.name())).findFirst().orElseThrow();
			original = JdbcTableEditor.previewTableRows(database, DbType.H2, table);
			assertEquals(List.of("TENANT_ID", "ID"), original.primaryKeys());
			assertEquals(List.of("LABEL", "FIXED_LABEL"), original.editableColumns());
		}

		try (var database = new JdbcDriverConnection(List.of(driverJar), "org.h2.Driver", url, new Properties())) {
			database.executeSql("UPDATE sample SET label = 'external' WHERE tenant_id = 1 AND id = 1", 10, 5);
		}
		try (var database = new JdbcDriverConnection(List.of(driverJar), "org.h2.Driver", url, new Properties())) {
			var connection = database.connection();
			var englishMessages = ResourceBundle.getBundle("messages.WorkbenchBundle", Locale.ROOT);
			var failure = assertThrows(SQLException.class,
				() -> JdbcTableEditor.updateTableCell(connection, DbType.H2, original, 0, "LABEL", "stale",
					englishMessages::getString));
			assertEquals("The original value has changed or the row no longer exists. Browse the table again.",
				failure.getMessage());
			assertEquals(List.of(List.of("external"), List.of("other")),
				database.executeSql("SELECT label FROM sample ORDER BY tenant_id, id", 10, 5).rows());
			connection.setAutoCommit(true);
			var current = JdbcTableEditor.previewTableRows(database, DbType.H2, original.table());
			assertNull(JdbcTableEditor.updateTableCell(connection, DbType.H2, current, 0, "LABEL", null));
			assertEquals(List.of(Arrays.asList((String) null), List.of("other")),
				database.executeSql("SELECT label FROM sample ORDER BY tenant_id, id", 10, 5).rows());
			connection.setAutoCommit(true);
			var afterNull = JdbcTableEditor.previewTableRows(database, DbType.H2, original.table());
			assertEquals("X   ", JdbcTableEditor.updateTableCell(connection, DbType.H2, afterNull, 0, "FIXED_LABEL", "X"));
			assertEquals(List.of(List.of("X   ")), database.executeSql(
				"SELECT fixed_label FROM sample WHERE tenant_id = 1 AND id = 1", 10, 5).rows());
		}
	}

	/**
	 * H2 定义保留所选表的生成列、约束及索引的 NULL 判重规则，同时过滤 SCRIPT 返回的账号和其他对象。
	 *
	 * @throws Exception 文件依赖、数据库或 DDL 查询失败
	 */
	@Test
	void extractsOnlyH2ObjectDdlWithoutAccounts() throws Exception {
		Path driverJar = Path.of(System.getProperty("workbench.estimatedPlanTestDriver"));
		try (var database = new JdbcDriverConnection(List.of(driverJar), "org.h2.Driver", "jdbc:h2:mem:ddl",
			new Properties())) {
			database.executeSql("CREATE TABLE sample (id INTEGER PRIMARY KEY, doubled INTEGER GENERATED ALWAYS AS (id * 2))", 10, 5);
			database.executeSql("CREATE UNIQUE NULLS NOT DISTINCT INDEX sample_unique ON sample(doubled)", 10, 5);
			database.executeSql("CREATE TABLE unrelated (id INTEGER PRIMARY KEY)", 10, 5);
			database.executeSql("CREATE USER \"TABLE USER\" PASSWORD 'test-only'", 10, 5);
			database.executeSql("COMMENT ON TABLE sample IS 'fixture remark'", 10, 5);
			var connection = database.connection();
			var table = JdbcTableMetadata.listTables(connection.getMetaData(), connection.getCatalog(), "PUBLIC")
				.stream().filter(item -> "SAMPLE".equals(item.name())).findFirst().orElseThrow();
			String ddl = JdbcTableDdl.readTableDdl(connection, DbType.H2, table);
			assertTrue(ddl.contains("GENERATED ALWAYS AS"));
			assertTrue(ddl.contains("PRIMARY KEY"));
			assertTrue(ddl.contains("CREATE UNIQUE NULLS NOT DISTINCT INDEX"));
			assertTrue(ddl.contains("fixture remark"));
			assertFalse(ddl.contains("UNRELATED"));
			assertFalse(ddl.contains("TABLE USER"));
			assertFalse(ddl.contains("CREATE USER"));
		}
	}

	/** SQL Server 补全按完整方括号标识符匹配，同时允许名称包含左右括号。 */
	@Test
	void completesSqlServerIdentifiersWithoutMatchingUnknownNameSuffixes() {
		var table = new JdbcTable("catalog", "team ] one", "order ] log", "TABLE", "");
		String known = "SELECT '[literal]' FROM [team ]] one].or";
		String unknown = "SELECT 1 FROM [unknown[team ]] one].or";
		assertEquals(List.of("[order ]] log]"), JdbcSqlCompletion.suggestSqlIdentifiers(
			DbType.SqlServer, known, known.length(), List.of(table), null, List.of()));
		assertTrue(JdbcSqlCompletion.suggestSqlIdentifiers(DbType.SqlServer, unknown, unknown.length(),
			List.of(table), null, List.of()).isEmpty());
		var column = new IntellijColumnInfo();
		column.setName("amount");
		String aliased = "SELECT [o].am FROM [team ]] one].[order ]] log] AS [o]";
		int aliasCaret = aliased.indexOf(" FROM");
		assertEquals(List.of("[amount]"), JdbcSqlCompletion.suggestSqlIdentifiers(
			DbType.SqlServer, aliased, aliasCaret, List.of(table), table, List.of(column)));
		String implicitAlias = "SELECT o.am FROM tenants JOIN [team ]] one].[order ]] log] o ON o.id=tenants.id";
		assertEquals(List.of("[amount]"), JdbcSqlCompletion.suggestSqlIdentifiers(DbType.SqlServer, implicitAlias,
			implicitAlias.indexOf(" FROM"), List.of(table), table, List.of(column)));
		String clause = "SELECT [WHERE].am FROM [team ]] one].[order ]] log] WHERE amount > 1";
		assertTrue(JdbcSqlCompletion.suggestSqlIdentifiers(DbType.SqlServer, clause, clause.indexOf(" FROM"),
			List.of(table), table, List.of(column)).isEmpty());
		String batch = "SELECT id FROM before_table o; " + aliased.replace("SELECT", "SELECT ';',")
			+ "; SELECT id FROM after_table o";
		assertEquals(List.of("[amount]"), JdbcSqlCompletion.suggestSqlIdentifiers(DbType.SqlServer, batch,
			batch.indexOf(".am") + ".am".length(), List.of(table), table, List.of(column)));
		String nested = aliased + " WHERE EXISTS (SELECT o.am FROM other_table o)";
		assertEquals(List.of("[amount]"), JdbcSqlCompletion.suggestSqlIdentifiers(DbType.SqlServer, nested,
			aliasCaret, List.of(table), table, List.of(column)));
		assertTrue(JdbcSqlCompletion.suggestSqlIdentifiers(DbType.SqlServer, nested,
			nested.lastIndexOf(".am") + ".am".length(), List.of(table), table, List.of(column)).isEmpty());
		String correlated = nested.replace("other_table o", "other_table x");
		assertEquals(List.of("[amount]"), JdbcSqlCompletion.suggestSqlIdentifiers(DbType.SqlServer, correlated,
			correlated.lastIndexOf(".am") + ".am".length(), List.of(table), table, List.of(column)));
		var other = new JdbcTable("catalog", "other", table.name(), "TABLE", "");
		assertEquals(table, JdbcSqlCompletion.resolveSqlCompletionTable(DbType.SqlServer, "SELECT [order ]] log].am",
			"SELECT [order ]] log].am".length(), List.of(table), null));
		assertEquals(null, JdbcSqlCompletion.resolveSqlCompletionTable(DbType.SqlServer, "SELECT [order ]] log].am",
			"SELECT [order ]] log].am".length(), List.of(other, table), other));
		assertEquals(table, JdbcSqlCompletion.resolveSqlCompletionTable(
			DbType.SqlServer, aliased, aliasCaret, List.of(other, table), other));
		String unqualified = aliased.replace("[team ]] one].", "");
		assertEquals(null, JdbcSqlCompletion.resolveSqlCompletionTable(DbType.SqlServer, unqualified,
			aliasCaret, List.of(other, table), other));
		String shadowedTable = "SELECT [order ]] log].am FROM other_table AS [order ]] log]";
		int shadowedCaret = shadowedTable.indexOf(" FROM");
		assertEquals(null, JdbcSqlCompletion.resolveSqlCompletionTable(DbType.SqlServer,
			shadowedTable, shadowedCaret, List.of(table), table));
		assertTrue(JdbcSqlCompletion.suggestSqlIdentifiers(DbType.SqlServer, shadowedTable,
			shadowedCaret, List.of(table), table, List.of(column)).isEmpty());
		String misleading = aliased.replace("[team ]] one]", "[other]");
		assertTrue(JdbcSqlCompletion.suggestSqlIdentifiers(
			DbType.SqlServer, misleading, misleading.indexOf(" FROM"), List.of(table), table, List.of(column)).isEmpty());
		String longLiteral = "SELECT '" + "x''".repeat(10_000) + "' FROM [team ]] one].or";
		assertEquals(List.of("[order ]] log]"), JdbcSqlCompletion.suggestSqlIdentifiers(
			DbType.SqlServer, longLiteral, longLiteral.length(), List.of(table), null, List.of()));
	}

	/** 同名表按控制台绑定的库或模式解析，显式限定名仍可跨范围。 */
	@Test
	void resolvesSqlCompletionInConsoleCatalog() {
		var first = new JdbcTable("first_db", null, "note", "TABLE", "");
		var second = new JdbcTable("second_db", null, "note", "TABLE", "");
		String local = "SELECT n.am FROM note n";
		String explicit = "SELECT n.am FROM first_db.note n";
		String withoutAlias = "SELECT note.am FROM first_db.note WHERE note.id = 1";
		int caret = local.indexOf(" FROM");
		for (DbType type : List.of(DbType.MySQL, DbType.MariaDB)) {
			assertEquals(second, JdbcSqlCompletion.resolveSqlCompletionTable(type, local, caret,
				List.of(first, second), first, "second_db"));
			assertEquals(first, JdbcSqlCompletion.resolveSqlCompletionTable(type, explicit, caret,
				List.of(first, second), second, "second_db"));
			assertEquals(first, JdbcSqlCompletion.resolveSqlCompletionTable(type, withoutAlias, withoutAlias.indexOf(" FROM"),
				List.of(first, second), second, "second_db"));
			assertEquals(null, JdbcSqlCompletion.resolveSqlCompletionTable(type, local, caret,
				List.of(first), first, "second_db"));
		}
		var firstSchema = new JdbcTable("test", "first_db", "note", "TABLE", "");
		var secondSchema = new JdbcTable("test", "second_db", "note", "TABLE", "");
		String ambiguous = "SELECT note.am FROM first_db.note JOIN second_db.note ON 1 = 1";
		assertEquals(null, JdbcSqlCompletion.resolveSqlCompletionTable(DbType.MySQL, ambiguous,
			ambiguous.indexOf(" FROM"), List.of(first, second), second, "second_db"));
		var column = new IntellijColumnInfo();
		column.setName("amount");
		assertTrue(JdbcSqlCompletion.suggestSqlIdentifiers(DbType.MySQL, ambiguous,
			ambiguous.indexOf(" FROM"), List.of(first, second), second, List.of(column)).isEmpty());
		for (DbType type : List.of(DbType.PostgreSQL, DbType.H2)) {
			assertEquals(secondSchema, JdbcSqlCompletion.resolveSqlCompletionTable(type, local, caret,
				List.of(firstSchema, secondSchema), firstSchema, "second_db"));
			assertEquals(firstSchema, JdbcSqlCompletion.resolveSqlCompletionTable(type, explicit, caret,
				List.of(firstSchema, secondSchema), secondSchema, "second_db"));
			assertEquals(firstSchema, JdbcSqlCompletion.resolveSqlCompletionTable(type, withoutAlias, withoutAlias.indexOf(" FROM"),
				List.of(firstSchema, secondSchema), secondSchema, "second_db"));
		}
	}

	/** PostgreSQL dollar-quoted 字符串里的别名与分号不参与补全。 */
	@Test
	void ignoresPostgreSqlDollarQuotedAliases() {
		var table = new JdbcTable("catalog", "public", "note", "TABLE", "");
		var column = new IntellijColumnInfo();
		column.setName("amount");
		String sql = "SELECT n.am, $Tag$ ; FROM other n $tag$ ; FROM another n $Tag$ FROM public.note n";
		assertEquals(List.of("\"amount\""), JdbcSqlCompletion.suggestSqlIdentifiers(DbType.PostgreSQL, sql,
			sql.indexOf("n.am") + "n.am".length(), List.of(table), table, List.of(column)));
		var mixedCase = new JdbcTable("catalog", "public", "Note", "TABLE", "");
		String quoted = "SELECT \"N\".am FROM public.\"Note\" AS \"N\"";
		assertEquals(mixedCase, JdbcSqlCompletion.resolveSqlCompletionTable(DbType.PostgreSQL, quoted,
			quoted.indexOf(" FROM"), List.of(table, mixedCase), table));
		String folded = "SELECT N.am FROM PUBLIC.NOTE AS N";
		assertEquals(table, JdbcSqlCompletion.resolveSqlCompletionTable(DbType.PostgreSQL, folded,
			folded.indexOf(" FROM"), List.of(table, mixedCase), mixedCase));
	}

	/**
	 * 验证同列普通表和视图仍报告对象类型差异，且不为视图生成迁移草案。
	 *
	 * @throws Exception 准备 H2 样本或读取对象结构失败
	 */
	@Test
	void reportsDifferentObjectKindsWithIdenticalColumns() throws Exception {
		Path driverJar = Path.of(System.getProperty("workbench.estimatedPlanTestDriver"));
		try (var database = new JdbcDriverConnection(List.of(driverJar), "org.h2.Driver", "jdbc:h2:mem:compare-kinds",
			new Properties())) {
			database.executeSql("CREATE TABLE SAMPLE_TABLE(ID INTEGER)", 10, 5);
			database.executeSql("CREATE VIEW SAMPLE_VIEW AS SELECT ID FROM SAMPLE_TABLE", 10, 5);
			var connection = database.connection();
			var objects = JdbcTableMetadata.listTables(connection.getMetaData(), connection.getCatalog(), "PUBLIC");
			var table = objects.stream().filter(object -> object.name().equals("SAMPLE_TABLE")).findFirst().orElseThrow();
			var view = objects.stream().filter(object -> object.name().equals("SAMPLE_VIEW")).findFirst().orElseThrow();
			var difference = JdbcTableComparer.compareTableStructure(connection, table, connection, view, DbType.H2,
				(key, arguments) -> key);
			assertEquals(List.of("compare.table.kind.changed"), difference.changes());
			assertTrue(difference.migrationDraft().isEmpty());
		}
	}

	/**
	 * 使用两个隔离 H2 连接中的同名表比较，验证草案只更新目标库并保留目标数据。
	 *
	 * @throws Exception 数据库初始化、结构读取或草案验收失败
	 */
	@Test
	void draftsOnlySupportedMissingColumnsForMigration() throws Exception {
		Path driverJar = Path.of(System.getProperty("workbench.estimatedPlanTestDriver"));
		try (var sourceDb = new JdbcDriverConnection(List.of(driverJar), "org.h2.Driver", "jdbc:h2:mem:compare-source",
				new Properties());
			var targetDb = new JdbcDriverConnection(List.of(driverJar), "org.h2.Driver", "jdbc:h2:mem:compare-target",
				new Properties())) {
			sourceDb.executeSql("CREATE TABLE sample (id INTEGER PRIMARY KEY, "
				+ "\"extra\"\"note\" VARCHAR(12), required_value INTEGER DEFAULT 0 NOT NULL, "
				+ "derived INTEGER GENERATED ALWAYS AS (id * 2))", 10, 5);
			targetDb.executeSql("CREATE TABLE sample (id INTEGER PRIMARY KEY, target_only INTEGER)", 10, 5);
			targetDb.executeSql("INSERT INTO sample (id, target_only) VALUES (1, 42)", 10, 5);
			sourceDb.executeSql("INSERT INTO sample (id, \"extra\"\"note\") VALUES (1, 'reference')", 10, 5);
			var sourceConnection = sourceDb.connection();
			var targetConnection = targetDb.connection();
			var source = JdbcTableMetadata.listTables(sourceConnection.getMetaData(), sourceConnection.getCatalog(), "PUBLIC")
				.stream().filter(table -> "SAMPLE".equals(table.name())).findFirst().orElseThrow();
			var target = JdbcTableMetadata.listTables(targetConnection.getMetaData(), targetConnection.getCatalog(), "PUBLIC")
				.stream().filter(table -> "SAMPLE".equals(table.name())).findFirst().orElseThrow();

			var comparison = JdbcTableComparer.compareTableStructure(sourceConnection, source, targetConnection, target,
				DbType.H2);
			assertEquals(4, comparison.changes().size());
			assertEquals(List.of("ALTER TABLE \"PUBLIC\".\"SAMPLE\" ADD COLUMN \"extra\"\"note\" VARCHAR(12) NULL;",
				"ALTER TABLE \"PUBLIC\".\"SAMPLE\" ADD COLUMN \"REQUIRED_VALUE\" INTEGER DEFAULT 0 NOT NULL;"),
				comparison.migrationDraft());
			// 用同一对真实 JDBC 快照验证两个语言包，避免用户切换界面语言后得到不同的迁移草案。
			for (Locale locale : List.of(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE)) {
				ResourceBundle messages = ResourceBundle.getBundle("messages.WorkbenchBundle", locale,
					ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
				var localized = JdbcTableComparer.compareTableStructure(sourceConnection, source, targetConnection, target,
					DbType.H2, (key, values) -> new MessageFormat(messages.getString(key), locale).format(values));
				String missingColumn = Locale.ENGLISH.equals(locale) ? "Target is missing column" : "目标缺少列";
				assertTrue(localized.changes().stream().anyMatch(change -> change.startsWith(missingColumn)
					&& change.contains("extra\"note")));
				assertEquals(comparison.changes().size(), localized.changes().size());
				assertEquals(comparison.migrationDraft(), localized.migrationDraft());
			}
			for (String migration : comparison.migrationDraft()) {
				targetDb.executeSql(migration, 10, 5);
			}
			assertEquals(List.of(Arrays.asList("42", null, "0")),
				targetDb.executeSql("SELECT target_only, \"extra\"\"note\", required_value FROM sample", 10, 5).rows());
			assertEquals(List.of(List.of("reference")),
				sourceDb.executeSql("SELECT \"extra\"\"note\" FROM sample", 10, 5).rows());
			var remaining = JdbcTableComparer.compareTableStructure(sourceConnection, source, targetConnection, target,
				DbType.H2);
			assertEquals(2, remaining.changes().size());
			assertTrue(remaining.migrationDraft().isEmpty());

			sourceDb.executeSql("CREATE INDEX fixture_order ON sample (id, \"extra\"\"note\")", 10, 5);
			targetDb.executeSql("CREATE INDEX fixture_order ON sample (\"extra\"\"note\", id)", 10, 5);
			for (var database : List.of(sourceDb, targetDb)) {
				database.executeSql("CREATE TABLE parent_fixture (id INTEGER PRIMARY KEY)", 10, 5);
				database.executeSql("INSERT INTO parent_fixture (id) VALUES (1)", 10, 5);
			}
			sourceDb.executeSql("ALTER TABLE sample ADD CONSTRAINT fixture_parent FOREIGN KEY (id) "
				+ "REFERENCES parent_fixture (id) ON DELETE CASCADE", 10, 5);
			targetDb.executeSql("ALTER TABLE sample ADD CONSTRAINT fixture_parent FOREIGN KEY (id) "
				+ "REFERENCES parent_fixture (id) ON DELETE RESTRICT", 10, 5);
			sourceDb.executeSql("ALTER TABLE sample ADD CONSTRAINT fixture_check CHECK (id > 0)", 10, 5);
			sourceDb.executeSql("ALTER TABLE sample ADD CONSTRAINT fixture_required CHECK (required_value >= 0)", 10, 5);
			targetDb.executeSql("ALTER TABLE sample ADD CONSTRAINT fixture_check CHECK (id >= 0)", 10, 5);
			var indexed = JdbcTableComparer.compareTableStructure(sourceConnection, source, targetConnection, target,
				DbType.H2);
			assertTrue(indexed.changes().stream().anyMatch(change -> change.contains("FIXTURE_ORDER")));
			assertTrue(indexed.changes().stream().anyMatch(change -> change.contains("FIXTURE_PARENT")));
			assertTrue(indexed.changes().stream().anyMatch(change -> change.contains("FIXTURE_CHECK") && change.contains("> 0") && change.contains(">= 0")));
			assertTrue(indexed.changes().stream().anyMatch(change -> change.contains("FIXTURE_REQUIRED")));
			assertTrue(indexed.migrationDraft().isEmpty());
			// 两边的 UNIQUE 约束名称不同，H2 为其生成的物理索引应按定义比较。
			sourceDb.executeSql("CREATE TABLE source_indexes (id INT PRIMARY KEY, value_code INT, "
				+ "CONSTRAINT source_unique UNIQUE(value_code))", 10, 5);
			targetDb.executeSql("CREATE TABLE target_indexes (id INT PRIMARY KEY, value_code INT, "
				+ "CONSTRAINT target_unique UNIQUE(value_code))", 10, 5);
			var sourceIndexes = JdbcTableMetadata.listTables(sourceConnection.getMetaData(), sourceConnection.getCatalog(), "PUBLIC")
				.stream().filter(table -> "SOURCE_INDEXES".equals(table.name())).findFirst().orElseThrow();
			var targetIndexes = JdbcTableMetadata.listTables(targetConnection.getMetaData(), targetConnection.getCatalog(), "PUBLIC")
				.stream().filter(table -> "TARGET_INDEXES".equals(table.name())).findFirst().orElseThrow();
			assertTrue(JdbcTableComparer.compareTableStructure(sourceConnection, sourceIndexes, targetConnection, targetIndexes,
				DbType.H2).changes().isEmpty());
			targetDb.executeSql("CREATE UNIQUE INDEX explicit_pk_columns ON target_indexes(id DESC)", 10, 5);
			var explicitIndex = JdbcTableComparer.compareTableStructure(sourceConnection, sourceIndexes, targetConnection,
				targetIndexes, DbType.H2).changes();
			assertEquals(1, explicitIndex.size());
			assertTrue(explicitIndex.getFirst().contains("EXPLICIT_PK_COLUMNS"));
			sourceDb.executeSql("ALTER TABLE source_indexes ADD (amount DECIMAL(12, 4), effective_on DATE, active BOOLEAN DEFAULT TRUE, attempts INTEGER DEFAULT -2, "
				+ "recorded_at TIMESTAMP(3), received_at TIMESTAMP(9) WITH TIME ZONE, "
				+ "label VARCHAR(30) DEFAULT '中文O''Brien' NOT NULL)", 10, 5);
			var typedDraft = JdbcTableComparer.compareTableStructure(sourceConnection, sourceIndexes, targetConnection,
				targetIndexes, DbType.H2).migrationDraft();
			assertEquals(7, typedDraft.size());
			assertTrue(typedDraft.stream().anyMatch(sql -> sql.contains("DECIMAL(12, 4)") || sql.contains("NUMERIC(12, 4)")));
			assertTrue(typedDraft.stream().anyMatch(sql -> sql.contains("DATE NULL")));
			assertTrue(typedDraft.stream().anyMatch(sql -> sql.contains("BOOLEAN DEFAULT TRUE NULL")));
			assertTrue(typedDraft.stream().anyMatch(sql -> sql.contains("INTEGER DEFAULT -2 NULL")));
			assertTrue(typedDraft.stream().anyMatch(sql -> sql.contains("TIMESTAMP(3) NULL")));
			assertTrue(typedDraft.stream().anyMatch(sql -> sql.contains("TIMESTAMP(9) WITH TIME ZONE NULL")));
			targetDb.executeSql("INSERT INTO target_indexes(id) VALUES (9)", 10, 5);
			for (String migration : typedDraft) {
				targetDb.executeSql(migration, 10, 5);
			}
			var afterTypedMigration = JdbcTableComparer.compareTableStructure(sourceConnection, sourceIndexes,
				targetConnection, targetIndexes, DbType.H2);
			assertEquals(explicitIndex, afterTypedMigration.changes());
			assertTrue(afterTypedMigration.migrationDraft().isEmpty());
			assertEquals(List.of(List.of("9", "TRUE", "-2", "中文O'Brien")), targetDb.executeSql(
				"SELECT id, active, attempts, label FROM target_indexes WHERE id = 9", 10, 5).rows());
			sourceDb.executeSql("ALTER TABLE source_indexes ALTER COLUMN attempts DROP DEFAULT", 10, 5);
			sourceDb.executeSql("ALTER TABLE source_indexes ALTER COLUMN active SET DEFAULT FALSE", 10, 5);
			var defaultsDraft = JdbcTableComparer.compareTableStructure(sourceConnection, sourceIndexes, targetConnection,
				targetIndexes, DbType.H2).migrationDraft();
			assertEquals(2, defaultsDraft.size());
			for (String migration : defaultsDraft) {
				targetDb.executeSql(migration, 10, 5);
			}
			assertEquals(explicitIndex, JdbcTableComparer.compareTableStructure(sourceConnection, sourceIndexes,
				targetConnection, targetIndexes, DbType.H2).changes());
			targetDb.executeSql("INSERT INTO target_indexes(id) VALUES (10)", 10, 5);
			assertEquals(List.of(List.of("9", "TRUE", "-2"), Arrays.asList("10", "FALSE", null)), targetDb.executeSql(
				"SELECT id, active, attempts FROM target_indexes WHERE id IN (9, 10) ORDER BY id", 10, 5).rows());
			sourceDb.executeSql("ALTER TABLE source_indexes ALTER COLUMN label SET DATA TYPE VARCHAR(80)", 10, 5);
			String longerDefault = "改".repeat(35);
			sourceDb.executeSql("ALTER TABLE source_indexes ALTER COLUMN label SET DEFAULT '" + longerDefault + "'", 10, 5);
			var wideningDraft = JdbcTableComparer.compareTableStructure(sourceConnection, sourceIndexes, targetConnection,
				targetIndexes, DbType.H2).migrationDraft();
			assertEquals(2, wideningDraft.size());
			assertTrue(wideningDraft.get(1).contains(" SET DEFAULT "));
			assertTrue(wideningDraft.getFirst().contains("SET DATA TYPE VARCHAR(80)"));
			assertTrue(JdbcTableComparer.compareTableStructure(targetConnection, targetIndexes, sourceConnection,
				sourceIndexes, DbType.H2).migrationDraft().stream().noneMatch(sql -> sql.startsWith("ALTER TABLE")));
			for (String migration : wideningDraft) {
				targetDb.executeSql(migration, 10, 5);
			}
			targetDb.executeSql("INSERT INTO target_indexes(id) VALUES (11)", 10, 5);
			assertEquals(explicitIndex, JdbcTableComparer.compareTableStructure(sourceConnection, sourceIndexes,
				targetConnection, targetIndexes, DbType.H2).changes());
			assertEquals(List.of(List.of("中文O'Brien"), List.of(longerDefault)), targetDb.executeSql(
				"SELECT label FROM target_indexes WHERE id IN (9, 11) ORDER BY id", 10, 5).rows());
			sourceDb.executeSql("ALTER TABLE source_indexes ALTER COLUMN label DROP NOT NULL", 10, 5);
			var relaxDraft = JdbcTableComparer.compareTableStructure(sourceConnection, sourceIndexes, targetConnection,
				targetIndexes, DbType.H2).migrationDraft();
			assertEquals(1, relaxDraft.size());
			assertTrue(relaxDraft.getFirst().endsWith(" DROP NOT NULL;"));
			assertTrue(JdbcTableComparer.compareTableStructure(targetConnection, targetIndexes,
				sourceConnection, sourceIndexes, DbType.H2).migrationDraft().stream().noneMatch(sql -> sql.startsWith("ALTER TABLE")));
			targetDb.executeSql(relaxDraft.getFirst(), 10, 5);
			targetDb.executeSql("INSERT INTO target_indexes(id, label) VALUES (12, NULL)", 10, 5);
			assertEquals(List.of(List.of("9", "中文O'Brien"), Arrays.asList("12", null)), targetDb.executeSql(
				"SELECT id, label FROM target_indexes WHERE id IN (9, 12) ORDER BY id", 10, 5).rows());
			assertEquals(explicitIndex, JdbcTableComparer.compareTableStructure(sourceConnection, sourceIndexes,
				targetConnection, targetIndexes, DbType.H2).changes());
			sourceDb.executeSql("ALTER TABLE source_indexes ADD COLUMN generated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL", 10, 5);
			var expressionDefault = JdbcTableComparer.compareTableStructure(sourceConnection, sourceIndexes,
				targetConnection, targetIndexes, DbType.H2);
			assertTrue(expressionDefault.changes().stream().anyMatch(change -> change.contains("GENERATED_AT")));
			assertTrue(expressionDefault.migrationDraft().isEmpty());
			sourceDb.executeSql("CREATE INDEX fixture_page ON source_indexes(active DESC NULLS LAST, label ASC NULLS FIRST)", 10, 5);
			sourceDb.executeSql("CREATE INDEX fixture_conflict ON source_indexes(label)", 10, 5);
			targetDb.executeSql("CREATE TABLE other_indexes (label VARCHAR(80))", 10, 5);
			targetDb.executeSql("CREATE INDEX fixture_conflict ON other_indexes(label)", 10, 5);
			var indexDraft = JdbcTableComparer.compareTableStructure(sourceConnection, sourceIndexes, targetConnection,
				targetIndexes, DbType.H2).migrationDraft();
			assertEquals(List.of("CREATE INDEX \"PUBLIC\".\"FIXTURE_PAGE\" ON \"PUBLIC\".\"TARGET_INDEXES\" "
				+ "(\"ACTIVE\" DESC NULLS LAST, \"LABEL\" ASC NULLS FIRST);"), indexDraft);
			targetDb.executeSql(indexDraft.getFirst(), 10, 5);
			var indexedTarget = JdbcTableComparer.compareTableStructure(sourceConnection, sourceIndexes, targetConnection,
				targetIndexes, DbType.H2);
			assertTrue(indexedTarget.changes().stream().noneMatch(change -> change.contains("FIXTURE_PAGE")));
			assertTrue(indexedTarget.changes().stream().anyMatch(change -> change.contains("FIXTURE_CONFLICT")));
			assertTrue(indexedTarget.migrationDraft().isEmpty());
			sourceDb.executeSql("ALTER TABLE source_indexes ADD COLUMN new_score INTEGER", 10, 5);
			sourceDb.executeSql("CREATE INDEX fixture_new_column ON source_indexes(new_score)", 10, 5);
			sourceDb.executeSql("CREATE INDEX fixture_unsupported_column ON source_indexes(generated_at)", 10, 5);
			var dependentDraft = JdbcTableComparer.compareTableStructure(sourceConnection, sourceIndexes, targetConnection,
				targetIndexes, DbType.H2).migrationDraft();
			assertEquals(2, dependentDraft.size());
			assertTrue(dependentDraft.getFirst().contains("ADD COLUMN \"NEW_SCORE\""));
			assertTrue(dependentDraft.get(1).contains("CREATE INDEX \"PUBLIC\".\"FIXTURE_NEW_COLUMN\""));
			for (String migration : dependentDraft) {
				targetDb.executeSql(migration, 10, 5);
			}
			var remainingIndexes = JdbcTableComparer.compareTableStructure(sourceConnection, sourceIndexes,
				targetConnection, targetIndexes, DbType.H2);
			assertTrue(remainingIndexes.changes().stream().noneMatch(change -> change.contains("FIXTURE_NEW_COLUMN")));
			assertTrue(remainingIndexes.changes().stream().anyMatch(change -> change.contains("FIXTURE_UNSUPPORTED_COLUMN")));
			assertTrue(remainingIndexes.migrationDraft().isEmpty());
			sourceDb.executeSql("CREATE UNIQUE NULLS NOT DISTINCT INDEX fixture_unique ON source_indexes(new_score)", 10, 5);
			var uniqueDraft = JdbcTableComparer.compareTableStructure(sourceConnection, sourceIndexes, targetConnection,
				targetIndexes, DbType.H2).migrationDraft();
			assertEquals(1, uniqueDraft.size());
			assertTrue(uniqueDraft.getFirst().startsWith("CREATE UNIQUE NULLS NOT DISTINCT INDEX"));
			var duplicates = assertThrows(SQLException.class, () -> targetDb.executeSql(uniqueDraft.getFirst(), 10, 5));
			assertEquals("23505", duplicates.getSQLState());
			assertEquals(List.of(List.of("4")), targetDb.executeSql("SELECT COUNT(*) FROM target_indexes", 10, 5).rows());
			targetDb.executeSql("UPDATE target_indexes SET new_score = id WHERE id IN (9, 10, 11, 12)", 10, 5);
			targetDb.executeSql(uniqueDraft.getFirst(), 10, 5);
			assertTrue(JdbcTableComparer.compareTableStructure(sourceConnection, sourceIndexes, targetConnection,
				targetIndexes, DbType.H2).changes().stream().noneMatch(change -> change.contains("FIXTURE_UNIQUE")));
		}
	}

	/**
	 * 跨 SQLite 库保留索引列的顺序、内置排序规则及其声明，并跳过占用名称。
	 *
	 * @throws Exception 初始化或执行真实 SQLite 草案失败
	 */
	@Test
	void draftsSqliteIndexAfterItsMissingColumnWithoutOverwritingObjects() throws Exception {
		Path driver = Path.of(System.getProperty("workbench.sqliteMigrationTestDriver"));
		try (var sourceDb = new JdbcDriverConnection(List.of(driver), "org.sqlite.JDBC", "jdbc:sqlite::memory:", new Properties());
			var targetDb = new JdbcDriverConnection(List.of(driver), "org.sqlite.JDBC", "jdbc:sqlite::memory:", new Properties())) {
			sourceDb.executeSql("CREATE TABLE fixture (id INTEGER PRIMARY KEY, label TEXT, score INT)", 10, 5);
			sourceDb.executeSql("CREATE UNIQUE INDEX fixture_order ON fixture (label COLLATE nocase DESC, score ASC)", 10, 5);
			sourceDb.executeSql("CREATE INDEX fixture_taken ON fixture (label)", 10, 5);
			sourceDb.executeSql("""
				CREATE TRIGGER fixture_insert AFTER INSERT ON fixture
				BEGIN UPDATE fixture SET score = 1 WHERE id = NEW.id AND score IS NULL; END
				""", 10, 5);
			targetDb.executeSql("CREATE TABLE fixture (id INTEGER PRIMARY KEY, label TEXT) STRICT", 10, 5);
			targetDb.executeSql("CREATE VIEW FIXTURE_TAKEN AS SELECT 1 AS value", 10, 5);
			targetDb.executeSql("INSERT INTO fixture (id, label) VALUES (7, '旧行')", 10, 5);
			var sourceConnection = sourceDb.connection();
			var targetConnection = targetDb.connection();
			var source = JdbcTableMetadata.listTables(sourceConnection.getMetaData(), null, null).stream()
				.filter(table -> "fixture".equals(table.name())).findFirst().orElseThrow();
			var target = JdbcTableMetadata.listTables(targetConnection.getMetaData(), null, null).stream()
				.filter(table -> "fixture".equals(table.name())).findFirst().orElseThrow();
			String aliasSql = "SELECT f.la FROM main.fixture AS f";
			int caret = aliasSql.indexOf(" FROM");
			var columns = JdbcTableMetadata.readGenerationTable(sourceConnection.getMetaData(), DbType.SQLite, source)
				.tableInfo().getColumnInfos();
			assertEquals(List.of("\"label\""), JdbcSqlCompletion.suggestSqlIdentifiers(
				DbType.SQLite, aliasSql, caret, List.of(source), source, columns));
			String ddl = JdbcTableDdl.readTableDdl(sourceConnection, DbType.SQLite, source);
			assertTrue(ddl.contains(";\nCREATE UNIQUE INDEX fixture_order"));
			assertTrue(ddl.contains("CREATE INDEX fixture_taken"));
			int triggerStart = ddl.indexOf("CREATE TRIGGER fixture_insert");
			assertTrue(triggerStart > ddl.indexOf("CREATE INDEX fixture_taken"));
			var plan = JdbcTableComparer.compareTableStructure(sourceConnection, source, targetConnection, target, DbType.SQLite);
			assertEquals(2, plan.migrationDraft().size());
			assertTrue(plan.migrationDraft().getFirst().contains("ADD COLUMN"));
			assertTrue(plan.migrationDraft().get(1).contains("COLLATE nocase DESC"));
			for (String statement : plan.migrationDraft()) {
				targetDb.executeSql(statement, 10, 5);
			}
			var remaining = JdbcTableComparer.compareTableStructure(sourceConnection, source, targetConnection, target, DbType.SQLite);
			assertEquals(2, remaining.changes().size());
			assertTrue(remaining.changes().stream().anyMatch(change -> change.contains("fixture_taken")));
			assertTrue(remaining.changes().stream().anyMatch(change -> change.contains("fixture_insert")));
			assertTrue(remaining.migrationDraft().isEmpty());
			assertEquals(List.of(List.of("旧行")), targetDb.executeSql("SELECT label FROM fixture WHERE id = 7", 10, 5).rows());
			assertEquals(List.of(List.of("1")), targetDb.executeSql("SELECT value FROM FIXTURE_TAKEN", 10, 5).rows());
			assertTrue(plan.migrationDraft().getFirst().contains("\"main\".\"fixture\""));
			targetDb.executeSql(ddl.substring(triggerStart), 10, 5);
			var replayed = JdbcTableComparer.compareTableStructure(sourceConnection, source, targetConnection, target, DbType.SQLite);
			assertEquals(1, replayed.changes().size());
			targetDb.executeSql("DROP TRIGGER fixture_insert", 10, 5);
			targetDb.executeSql(ddl.substring(triggerStart).replace("score = 1", "score = 2"), 10, 5);
			targetDb.executeSql("CREATE TRIGGER extra_trigger AFTER UPDATE ON fixture BEGIN SELECT NEW.id; END", 10, 5);
			var modified = JdbcTableComparer.compareTableStructure(sourceConnection, source, targetConnection, target, DbType.SQLite);
			assertEquals(3, modified.changes().size());
			assertTrue(modified.changes().stream().anyMatch(change -> change.contains("score = 2")));
			assertTrue(modified.changes().stream().anyMatch(change -> change.contains("extra_trigger")));
			assertTrue(modified.migrationDraft().isEmpty());
			targetDb.executeSql("INSERT INTO fixture (id, label) VALUES (8, '触发器')", 10, 5);
			assertEquals(List.of(List.of("2")), targetDb.executeSql("SELECT score FROM fixture WHERE id = 8", 10, 5).rows());
			targetDb.executeSql("CREATE TEMP TABLE FIXTURE (shadow TEXT)", 10, 5);
			var shadowed = assertThrows(SQLException.class, () ->
				JdbcTableMetadata.readGenerationTable(targetConnection.getMetaData(), DbType.SQLite, target));
			assertTrue(shadowed.getMessage().contains("shadows main.fixture"));
			sourceDb.executeSql("CREATE VIEW fixture_view AS SELECT id FROM main.fixture WHERE id > 1", 10, 5);
			targetDb.executeSql("CREATE VIEW fixture_view AS SELECT id FROM main.fixture WHERE id > 2", 10, 5);
			var sourceView = JdbcTableMetadata.listTables(sourceConnection.getMetaData(), null, null).stream()
				.filter(table -> "fixture_view".equals(table.name())).findFirst().orElseThrow();
			var targetView = JdbcTableMetadata.listTables(targetConnection.getMetaData(), null, null).stream()
				.filter(table -> "fixture_view".equals(table.name())).findFirst().orElseThrow();
			var viewDiff = JdbcTableComparer.compareTableStructure(sourceConnection, sourceView, targetConnection,
				targetView, DbType.SQLite, (key, arguments) -> key);
			assertEquals(List.of("compare.view.definition.changed"), viewDiff.changes());
			assertTrue(viewDiff.migrationDraft().isEmpty());
		}
	}

	/** 验证非 JDBC 配置往返，并拒绝 MongoDB URI 中可能携带凭据的选项。 */
	@Test
	void storesNoSqlProfilesWithoutEmbeddedCredentials() {
		var store = new WorkbenchConnectionProfiles();
		var mongo = new NoSqlConnectionProfile(UUID.randomUUID(), "Fixture", NoSqlSourceType.MONGODB,
			"mongodb://localhost:27017/?replicaSet=fixture&tls=true", "user", "admin", false, false, 0);
		store.saveNoSqlProfile(mongo);
		var restored = new WorkbenchConnectionProfiles();
		restored.loadState(store.getState());
		assertEquals(List.of(mongo), restored.listNoSqlProfiles());

		for (String address : List.of("mongodb://user:test@localhost/", "mongodb://localhost/?proxy%50assword=test",
			"mongodb://localhost/?authMechanismProperties=AWS_SESSION_TOKEN:test")) {
			var invalid = new NoSqlConnectionProfile(UUID.randomUUID(), "Invalid", NoSqlSourceType.MONGODB,
				address, "", "", false, false, 0);
			assertThrows(IllegalArgumentException.class, () -> store.saveNoSqlProfile(invalid));
		}
		assertEquals(List.of(mongo), store.listNoSqlProfiles());
	}

	/** 同步快照重载后移除远端删除的条目，并保护同 ID 的新配置免受过期确认删除。 */
	@Test
	void appliesSyncedChangesWithoutRemovingNewerDataSourceVersions() {
		var local = new WorkbenchConnectionProfiles();
		var remote = new WorkbenchConnectionProfiles();
		UUID jdbcId = UUID.randomUUID();
		var previous = new JdbcConnectionProfile(jdbcId, "old", DbType.H2, "jdbc:h2:mem:old",
			"", "com.h2database:h2:2.5.252", "org.h2.Driver", "");
		var current = new JdbcConnectionProfile(jdbcId, "current", DbType.H2, "jdbc:h2:mem:current",
			"", "com.h2database:h2:2.5.252", "org.h2.Driver", "");
		var redis = new NoSqlConnectionProfile(UUID.randomUUID(), "cache", NoSqlSourceType.REDIS,
			"localhost:6379", "", "", false, false, 0);
		var currentRedis = new NoSqlConnectionProfile(redis.id(), "new cache", NoSqlSourceType.REDIS,
			"localhost:6380", "", "", false, false, 0);
		var deleted = new NoSqlConnectionProfile(UUID.randomUUID(), "removed cache", NoSqlSourceType.REDIS,
			"localhost:6381", "", "", false, false, 0);
		assertTrue(local.replaceImportedProfile(null, previous));
		assertTrue(local.replaceImportedNoSqlProfile(null, redis));
		assertFalse(local.replaceImportedProfile(null, current));
		assertFalse(local.replaceImportedNoSqlProfile(null, currentRedis));
		local.saveNoSqlProfile(deleted);
		remote.loadState(local.getState());
		assertTrue(remote.replaceImportedProfile(previous, current));
		assertTrue(remote.replaceImportedNoSqlProfile(redis, currentRedis));
		assertTrue(remote.removeNoSqlProfile(deleted));

		local.loadState(remote.getState());
		assertFalse(local.replaceImportedProfile(previous, previous));
		assertFalse(local.replaceImportedNoSqlProfile(redis, redis));
		assertFalse(local.removeProfile(previous));
		assertFalse(local.removeNoSqlProfile(redis));
		assertEquals(List.of(current), local.listProfiles());
		assertEquals(List.of(currentRedis), local.listNoSqlProfiles());

		assertTrue(local.removeProfile(current));
		assertTrue(local.removeNoSqlProfile(currentRedis));
		remote.loadState(local.getState());
		assertTrue(remote.listProfiles().isEmpty());
		assertTrue(remote.listNoSqlProfiles().isEmpty());
	}

	/**
	 * 验证非 JDBC 配置文件对 Redis 集群、MongoDB 副本集及 Elasticsearch HTTPS 的兼容性。
	 *
	 * @throws IOException 配置写入或读取失败
	 */
	@Test
	void roundTripsPortableNoSqlProfiles() throws IOException {
		var profiles = List.of(
			new NoSqlConnectionProfile(UUID.randomUUID(), "缓存", NoSqlSourceType.REDIS,
				"localhost:6379,localhost:6380", "user", "", true, true, 0),
			new NoSqlConnectionProfile(UUID.randomUUID(), "文档", NoSqlSourceType.MONGODB,
				"mongodb://localhost:27017/?replicaSet=fixture", "user", "admin", false, false, 0),
			new NoSqlConnectionProfile(UUID.randomUUID(), "搜索", NoSqlSourceType.ELASTICSEARCH,
				"https://localhost:9200/es", "search-reader", "", false, false, 0));
		for (var profile : profiles) {
			var writer = new StringWriter();
			profile.writeProfile(writer);
			String encoded = writer.toString();
			assertEquals(profile, NoSqlConnectionProfile.readProfile(new StringReader(encoded)));
			assertEquals(profile, NoSqlConnectionProfile.readProfile(new StringReader("\uFEFF" + encoded)));
			assertThrows(IOException.class, () -> NoSqlConnectionProfile.readProfile(
				new StringReader(encoded.replace(profile.id().toString(), "1-1-1-1-1"))));
			var repeated = new StringWriter();
			profile.writeProfile(repeated);
			assertEquals(encoded, repeated.toString());
			assertFalse(encoded.contains("password"));
			assertFalse(encoded.contains("apiKey"));
			assertFalse(encoded.contains("caPath"));
			assertThrows(IOException.class, () -> NoSqlConnectionProfile.readProfile(
				new StringReader(encoded.replace("nosql/1", "nosql/99"))));
		}
		assertThrows(IOException.class, () -> NoSqlConnectionProfile.readProfile(new StringReader(
			"format=mybatis-workbench-nosql/1\nid=" + profiles.getFirst().id() + "\ntype=REDIS\ntls=unknown")));
	}

	/**
	 * JSON Lines 导出按文档分行，保留 BSON 类型标记，目标已存在时维持原文件字节。
	 *
	 * @param directory JUnit 临时目录
	 * @throws IOException 无法写入或读取结果
	 */
	@Test
	void exportsMongoPageWithoutOverwritingExistingFile(@TempDir Path directory) throws IOException {
		var documents = List.of(
			"{\"_id\":{\"$oid\":\"507f1f77bcf86cd799439011\"},\"label\":\"中文\\n值\"}",
			"{\"value\":{\"$numberLong\":\"9007199254740993\"}}");
		var page = new MongoDocumentPage(documents, true);
		Path file = directory.resolve("result.jsonl");
		page.exportMongoJsonLines(file);
		assertEquals(String.join("\n", documents) + "\n", Files.readString(file));
		assertThrows(IOException.class, () -> new MongoDocumentPage(List.of("{}"), false).exportMongoJsonLines(file));
		assertEquals(documents, Files.readAllLines(file));
	}

	/**
	 * JSON Lines 的任一非空行带额外内容时，在访问数据库前拒绝整个文件。
	 *
	 * @param directory JUnit 临时目录
	 * @throws IOException 无法写入导入样本
	 */
	@Test
	void rejectsExtraContentOnMongoImportLine(@TempDir Path directory) throws IOException {
		Path file = directory.resolve("invalid.jsonl");
		try (var connection = new WorkbenchMongoConnection("mongodb://127.0.0.1:1", "", "", new char[0], Locale.ENGLISH)) {
			for (String trailing : List.of("{\"_id\":3}", "null", " invalid")) {
				Files.writeString(file, "{\"_id\":1}  \n{\"_id\":2}" + trailing + "\n");
				var failure = assertThrows(IllegalArgumentException.class,
					() -> connection.importMongoJsonLines("fixture", "notes", file));
				assertEquals("Each non-empty line must contain exactly one Extended JSON document.", failure.getMessage());
			}
		}
	}

	/**
	 * 含 JDBC 私钥口令或多因素认证密码的配置须在保存、导入和导出时被拒绝，普通 TLS 配置保持可交换。
	 */
	@Test
	void rejectsJdbcClientSecretsAcrossProfileEntryPoints() throws IOException {
		var safe = new JdbcConnectionProfile(UUID.randomUUID(), "fixture", DbType.PostgreSQL,
			"jdbc:postgresql://localhost/db?sslmode=verify-full", "user", "group:driver:1", "Driver", "");
		var safeWriter = new StringWriter();
		safe.writeProfile(safeWriter);
		assertEquals(safe, JdbcConnectionProfile.readProfile(new StringReader(safeWriter.toString())));
		assertEquals(safe, JdbcConnectionProfile.readProfile(new StringReader("\uFEFF" + safeWriter.toString())));
		assertThrows(IOException.class, () -> JdbcConnectionProfile.readProfile(
			new StringReader(safeWriter.toString().replace(safe.id().toString(), "1-1-1-1-1"))));
		var store = new WorkbenchConnectionProfiles();
		store.saveProfile(safe);

		var urls = List.of(
			"jdbc:mysql://localhost/db?password1=sample",
			"jdbc:mysql://localhost/db?password2=sample",
			"jdbc:mysql://localhost/db?password3=sample",
			"jdbc:mysql://localhost/db?%70assword2=sample",
			"jdbc:postgresql://localhost/db?sslpassword=sample",
			"jdbc:sqlserver://localhost;clientKeyPassword=sample",
			"jdbc:sqlserver://localhost;keyStoreAuthentication=KeyVaultClientSecret;keyStoreSecret={sample}",
			"jdbc:sqlserver://localhost;AADSecurePrincipalSecret={sample}",
			"jdbc:oracle:thin:@localhost/db?oracle.net.wallet_password=sample");
		var types = List.of(DbType.MySQL, DbType.MySQL, DbType.MySQL, DbType.MySQL,
			DbType.PostgreSQL, DbType.SqlServer, DbType.SqlServer, DbType.SqlServer, DbType.Oracle);
		for (int index = 0; index < urls.size(); index++) {
			var profile = new JdbcConnectionProfile(safe.id(), safe.name(), types.get(index), urls.get(index),
				safe.username(), safe.coordinates(), safe.driverClass(), safe.repository());
			var writer = new StringWriter();
			assertThrows(IllegalArgumentException.class, () -> profile.writeProfile(writer));
			assertTrue(writer.toString().isEmpty());
			assertThrows(IllegalArgumentException.class, () -> store.saveProfile(profile));

			var editedProfile = new Properties();
			editedProfile.load(new StringReader(safeWriter.toString()));
			editedProfile.setProperty("database", profile.dbType().name());
			editedProfile.setProperty("url", profile.url());
			editedProfile.store(writer, null);
			var failure = assertThrows(IOException.class, () -> JdbcConnectionProfile.readProfile(new StringReader(writer.toString())));
			assertTrue(failure.getCause() instanceof IllegalArgumentException);
		}
		assertEquals(List.of(safe), store.listProfiles());
	}

	/**
	 * 手工赋值的复合主键应进入 INSERT，并保持主键映射顺序。
	 *
	 * @param workspace 本次生成使用的临时输出目录
	 * @throws Exception 代码生成或读取失败
	 */
	@Test
	void keepsAssignedCompositeKeysInGeneratedInsert(@TempDir Path workspace) throws Exception {
		var table = sampleTable();
		var id = table.getPrimaryKeyColumns().getFirst();
		id.setAutoIncrement(false);
		id.setKeySeq((short) 2);
		var tenant = new IntellijColumnInfo();
		tenant.setName("tenant_id");
		tenant.setDataType(Types.BIGINT);
		tenant.setKeySeq((short) 1);
		table.setColumnInfos(List.of(id, tenant));
		table.setPrimaryKeyColumns(List.of(tenant, id));

		var generator = buildSampleGenerator(workspace, workspace, new GenerationTable(table, DbType.H2, ""));
		assertTrue(IdeaTableConnectionFactory.generateMapperFiles(generator, table).isEmpty());
		String mapper = Files.readString(workspace.resolve("fixture/SampleMapper.xml"));
		assertFalse(mapper.contains("useGeneratedKeys"));
		var insert = Pattern.compile("(?s)<insert id=\"insert\"[^>]*>(.*?)</insert>").matcher(mapper);
		assertTrue(insert.find(), mapper);
		assertTrue(insert.group(1).contains("#{id,jdbcType=BIGINT}"), insert.group(1));
		assertTrue(insert.group(1).contains("#{tenantId,jdbcType=BIGINT}"), insert.group(1));
		int tenantMapping = mapper.indexOf("<id column=\"tenant_id\"");
		assertTrue(tenantMapping >= 0 && tenantMapping < mapper.indexOf("<id column=\"id\""), mapper);
	}

	/**
	 * 创建使用本项目元数据和列备注扩展的 MyBatis3 生成上下文。
	 *
	 * @param javaOutput Java 输出目录
	 * @param xmlOutput  XML 暂存目录
	 * @param table 与生产入口共用配置构建的表结构
	 * @return 生成样本代码的 MBG 实例
	 */
	private MyBatisGenerator buildSampleGenerator(Path javaOutput, Path xmlOutput, GenerationTable table) {
		var settings = new Config();
		settings.setTableName(table.tableInfo().getTableName());
		settings.setUseSchemaPrefix(Objects.nonNull(table.qualifier()) && !table.qualifier().isEmpty());
		settings.setModelName("Sample");
		settings.setDaoName("SampleMapper");
		settings.setPrimaryKey(table.defaultGeneratedKeyColumn());
		settings.setUseExample(true);
		Configuration configuration = new Configuration();
		var context = new Context.Builder().withId("test").withTargetRuntime("MyBatis3")
			.withConnectionFactoryConfiguration(new ConnectionFactoryConfiguration.Builder()
				.withConfigurationType(IdeaTableConnectionFactory.class.getName()).build())
			.withTableConfiguration(new MybatisGenerator(settings).buildTableConfig(table))
			.withModelGeneratorConfiguration(new ModelGeneratorConfiguration.Builder()
				.withTargetProject(javaOutput.toString()).withTargetPackage("fixture").build())
			.withClientGeneratorConfiguration(new ClientGeneratorConfiguration.Builder()
				.withLegacyClientType(ClientGeneratorConfiguration.LegacyClientType.XML_MAPPER)
				.withTargetProject(javaOutput.toString()).withTargetPackage("fixture").build())
			.withSqlMapGeneratorConfiguration(new SqlMapGeneratorConfiguration.Builder()
				.withTargetProject(xmlOutput.toString()).withTargetPackage("fixture").build())
			.withCommentGeneratorConfiguration(new CommentGeneratorConfiguration.Builder()
				.withConfigurationType(DbRemarksCommentGenerator.class.getName())
				.withProperty(new Property("annotations", "true")).build());
		MybatisGenerator.configureIdentifierDelimiters(context, table.dbType());
		configuration.addContext(context.build());
		return new MyBatisGenerator.Builder().withConfiguration(configuration).build();
	}

	/**
	 * 模拟表结构，包含自增主键、有默认值的普通列与计算列。
	 *
	 * @return 无需数据库连接即可使用的表样本
	 */
	private IntellijTableInfo sampleTable() {
		IntellijColumnInfo id = new IntellijColumnInfo();
		id.setName("id");
		id.setDataType(Types.BIGINT);
		id.setKeySeq((short) 1);
		id.setAutoIncrement(true);
		id.setNullable(false);

		IntellijColumnInfo created = new IntellijColumnInfo();
		created.setName("created_at");
		created.setDataType(Types.TIMESTAMP);
		created.setRemarks("创建时间");
		created.setNullable(true);
		created.setColumnDefaultValue("CURRENT_TIMESTAMP");

		IntellijColumnInfo computed = new IntellijColumnInfo();
		computed.setName("computed_value");
		computed.setDataType(Types.INTEGER);
		computed.setGeneratedColumn(true);
		computed.setNullable(true);

		IntellijTableInfo table = new IntellijTableInfo();
		table.setTableName("sample_table");
		table.setTableType("TABLE");
		table.setColumnInfos(List.of(id, created, computed));
		table.setPrimaryKeyColumns(List.of(id));
		return table;
	}
}
