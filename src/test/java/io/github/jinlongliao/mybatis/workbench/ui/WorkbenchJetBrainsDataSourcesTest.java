package io.github.jinlongliao.mybatis.workbench.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.List;
import java.util.OptionalLong;

import javax.swing.JCheckBox;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.SortOrder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;

import io.github.jinlongliao.mybatis.workbench.database.JdbcQueryResult;
import io.github.jinlongliao.mybatis.workbench.database.NoSqlConnectionProfile;
import io.github.jinlongliao.mybatis.workbench.database.NoSqlSourceType;
import io.github.jinlongliao.mybatis.workbench.model.DbType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 JetBrains 数据源文件迁移的协议识别和凭据边界。
 *
 * @author liaojinlong
 * @since 2026/11/19 11:38
 */
class WorkbenchJetBrainsDataSourcesTest {
	/** 解析完整 MongoDB URL，保留目标库和连接选项，认证库遵循 authSource。 */
	@Test
	void splitsMongoUriCredentialsBeforeSharing() throws IOException {
		var name = new JTextField();
		var address = new JTextField();
		var username = new JTextField();
		var authDatabase = new JTextField();
		var password = new JPasswordField();
		WorkbenchNoSqlSourceEditor.applyMongoUri(
			"mongodb://reader:p%40ss%3Aword@localhost:27017/catalog?authSource=accounts&replicaSet=rs0",
			name, address, username, authDatabase, password);
		assertEquals("mongodb://localhost:27017/catalog?authSource=accounts&replicaSet=rs0", address.getText());
		assertEquals("reader", username.getText());
		assertEquals("accounts", authDatabase.getText());
		assertEquals("p@ss:word", new String(password.getPassword()));

		name.setText("");
		WorkbenchNoSqlSourceEditor.applyMongoUri(
			"mongodb://reader:fixture==@node-a.invalid:3717,node-b.invalid:3717/catalog?replicaSet=rs0",
			name, address, username, authDatabase, password);
		assertEquals("mongodb://node-a.invalid:3717,node-b.invalid:3717/catalog?replicaSet=rs0", address.getText());
		assertEquals("reader", username.getText());
		assertEquals("catalog", authDatabase.getText());
		assertEquals("fixture==", new String(password.getPassword()));
		assertEquals("catalog", name.getText());
		var shared = new NoSqlConnectionProfile(UUID.randomUUID(), name.getText(), NoSqlSourceType.MONGODB,
			address.getText(), username.getText(), authDatabase.getText(), false, false, 0);
		var exported = new StringWriter();
		shared.writeProfile(exported);
		assertFalse(exported.toString().contains("fixture=="));
		name.setText("Custom MongoDB");


		WorkbenchNoSqlSourceEditor.applyMongoUri("mongodb+srv://reader:fixture@discovery.invalid/catalog",
			name, address, username, authDatabase, password);
		assertEquals("mongodb+srv://discovery.invalid/catalog", address.getText());
		assertEquals("", authDatabase.getText());
		assertEquals("Custom MongoDB", name.getText());
		assertEquals("fixture", new String(password.getPassword()));
	}

	/** JetBrains Redis URL 和标准 Redis URL 都可转换为无凭据的种子地址。 */
	@Test
	void splitsRedisUriCredentialsAndDatabase() {
		var address = new JTextField("jdbc:redis://localhost:6380/4");
		var username = new JTextField();
		var password = new JPasswordField("manual");
		var database = new JSpinner();
		var tls = new JCheckBox();
		WorkbenchNoSqlSourceEditor.applyRedisUri(address, username, password, database, false, tls);
		assertEquals("localhost:6380", address.getText());
		assertEquals(4, database.getValue());
		assertEquals("manual", new String(password.getPassword()));

		address.setText("rediss://reader:local%3Asecret%40@[::1]/2");
		WorkbenchNoSqlSourceEditor.applyRedisUri(address, username, password, database, false, tls);
		assertEquals("[::1]:6379", address.getText());
		assertEquals("reader", username.getText());
		assertEquals("local:secret@", new String(password.getPassword()));
		assertEquals(2, database.getValue());
		assertTrue(tls.isSelected());
	}

	/** 排序、筛选后的导出快照遵循网格可见顺序，并保留 SQL NULL 与截断状态。 */
	@Test
	void capturesSortedAndFilteredQueryResultView() {
		var model = new DefaultTableModel(new Object[]{"id", "label"}, 0);
		model.addRow(new Object[]{"2", "beta"});
		model.addRow(new Object[]{"1", null});
		model.addRow(new Object[]{"3", "alpha"});
		JTable table = new JTable(model);
		var sorter = new TableRowSorter<>(model);
		table.setRowSorter(sorter);
		sorter.setSortKeys(List.of(new javax.swing.RowSorter.SortKey(0, SortOrder.ASCENDING)));
		var original = new JdbcQueryResult(List.of("id", "label"),
			List.of(List.of("2", "beta"), java.util.Arrays.asList("1", null), List.of("3", "alpha")), true,
			OptionalLong.empty());

		var sorted = WorkbenchDatabasePanel.captureVisibleQueryResult(table, original);
		assertEquals(List.of("1", "2", "3"), sorted.rows().stream().map(row -> row.getFirst()).toList());
		assertNull(sorted.rows().getFirst().get(1));
		assertTrue(sorted.truncated());

		sorter.setRowFilter(RowFilter.regexFilter("alpha"));
		var filtered = WorkbenchDatabasePanel.captureVisibleQueryResult(table, original);
		assertEquals(List.of(List.of("3", "alpha")), filtered.rows());
	}

	/**
	 * 同一个文件可混合多种数据源。无效地址单独跳过，后续有效配置仍会导入。
	 *
	 * @throws IOException 合法样本无法读取或导出
	 */
	@Test
	void migratesMixedDataSourcesWithoutCredentials() throws IOException {
		String xml = """
			<project>
			  <data-source name="本地库"><jdbc-url>jdbc:h2:mem:fixture</jdbc-url></data-source>
			  <data-source name="无效 Redis"><jdbc-url>jdbc:redis://localhost/not-a-database</jdbc-url></data-source>
			  <data-source name="缓存">
			    <jdbc-url>jdbc:rediss://[::1]/4</jdbc-url><user-name>operator</user-name><password>fixture-secret</password>
			  </data-source>
			  <data-source name="无效 MongoDB"><jdbc-url>mongodb://localhost:abc</jdbc-url></data-source>
			  <data-source name="文档">
			    <jdbc-url>mongodb+srv://discovery.invalid/catalog?authSource=accounts</jdbc-url><user-name>reader</user-name>
			  </data-source>
			  <data-source name="内嵌密钥"><jdbc-url>jdbc:es://https://localhost/?apiKey=fixture-secret</jdbc-url></data-source>
			  <data-source name="搜索"><jdbc-url>jdbc:es://https://localhost/elastic</jdbc-url></data-source>
			</project>
			""";
		var result = WorkbenchJetBrainsDataSources.readDataSources(xml.getBytes(StandardCharsets.UTF_8));
		assertEquals(3, result.rejected());
		assertEquals(1, result.profiles().size());
		assertEquals(DbType.H2, result.profiles().getFirst().dbType());
		assertEquals(3, result.noSqlProfiles().size());
		var redis = result.noSqlProfiles().get(0);
		assertEquals(NoSqlSourceType.REDIS, redis.type());
		assertEquals("[::1]:6379", redis.endpoint());
		assertEquals(4, redis.logicalDatabase());
		assertEquals("operator", redis.username());
		assertTrue(redis.tls());
		var mongo = result.noSqlProfiles().get(1);
		assertEquals(NoSqlSourceType.MONGODB, mongo.type());
		assertEquals("accounts", mongo.authenticationDatabase());
		assertEquals("reader", mongo.username());
		var elasticsearch = result.noSqlProfiles().get(2);
		assertEquals(NoSqlSourceType.ELASTICSEARCH, elasticsearch.type());
		assertEquals("https://localhost:9200/elastic", elasticsearch.endpoint());
		var output = new StringWriter();
		redis.writeProfile(output);
		assertFalse(output.toString().contains("fixture-secret"));
	}

	/**
	 * 数据源由外部文件提供，解析时拒绝自定义文档类型和实体。
	 */
	@Test
	void rejectsDataSourceDocumentTypes() {
		String xml = """
			<!DOCTYPE project [<!ENTITY endpoint 'jdbc:h2:mem:fixture'>]>
			<project><data-source name="fixture"><jdbc-url>&endpoint;</jdbc-url></data-source></project>
			""";
		assertThrows(IOException.class, () -> WorkbenchJetBrainsDataSources.readDataSources(xml.getBytes(StandardCharsets.UTF_8)));
	}
}
