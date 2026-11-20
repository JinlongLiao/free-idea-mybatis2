package io.github.jinlongliao.mybatis.workbench.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import io.github.jinlongliao.mybatis.workbench.database.NoSqlSourceType;
import io.github.jinlongliao.mybatis.workbench.model.DbType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 JetBrains 数据源文件迁移的协议识别和凭据边界。
 *
 * @author liaojinlong
 * @since 2026/11/19 11:38
 */
class WorkbenchJetBrainsDataSourcesTest {
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
