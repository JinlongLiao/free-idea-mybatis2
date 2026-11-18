package com.wuzhizhan.mybatis2.generate;

import com.wuzhizhan.mybatis2.generate.plugin.DbRemarksCommentGenerator;

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
import org.mybatis.generator.config.GeneratedKey;
import org.mybatis.generator.config.ModelGeneratorConfiguration;
import org.mybatis.generator.config.Property;
import org.mybatis.generator.config.SqlMapGeneratorConfiguration;
import org.mybatis.generator.config.TableConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
	 * 首次生成保留主键、列映射和备注，再生成时保留手写 SQL，并阻止旧版未标记节点被重复写入。
	 *
	 * @param workspace 每次测试独立的输出目录
	 * @throws Exception 样本生成或文件操作失败
	 */
	@Test
	void generatesAndPublishesMapperXml(@TempDir Path workspace) throws Exception {
		Path javaOutput = Files.createDirectory(workspace.resolve("java"));
		Path xmlOutput = Files.createDirectory(workspace.resolve("xml"));
		Path stage = Files.createDirectory(xmlOutput.resolve("stage"));
		MyBatisGenerator generator = buildSampleGenerator(javaOutput, stage);
		assertTrue(IdeaTableConnectionFactory.generateMapperFiles(generator, sampleTable()).isEmpty());

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
	 * 创建使用本项目元数据和列备注扩展的 MyBatis3 生成上下文。
	 *
	 * @param javaOutput Java 输出目录
	 * @param xmlOutput  XML 暂存目录
	 * @return 生成样本代码的 MBG 实例
	 */
	private MyBatisGenerator buildSampleGenerator(Path javaOutput, Path xmlOutput) {
		Configuration configuration = new Configuration();
		configuration.addContext(new Context.Builder().withId("test").withTargetRuntime("MyBatis3")
			.withConnectionFactoryConfiguration(new ConnectionFactoryConfiguration.Builder()
				.withConfigurationType(IdeaTableConnectionFactory.class.getName()).build())
			.withTableConfiguration(new TableConfiguration.Builder().withTableName("sample_table")
				.withSchema("app").withDomainObjectName("Sample").withMapperName("SampleMapper")
				.withGeneratedKey(new GeneratedKey("id", "JDBC", true)).build())
			.withModelGeneratorConfiguration(new ModelGeneratorConfiguration.Builder()
				.withTargetProject(javaOutput.toString()).withTargetPackage("fixture").build())
			.withClientGeneratorConfiguration(new ClientGeneratorConfiguration.Builder()
				.withLegacyClientType(ClientGeneratorConfiguration.LegacyClientType.XML_MAPPER)
				.withTargetProject(javaOutput.toString()).withTargetPackage("fixture").build())
			.withSqlMapGeneratorConfiguration(new SqlMapGeneratorConfiguration.Builder()
				.withTargetProject(xmlOutput.toString()).withTargetPackage("fixture").build())
			.withCommentGeneratorConfiguration(new CommentGeneratorConfiguration.Builder()
				.withConfigurationType(DbRemarksCommentGenerator.class.getName())
				.withProperty(new Property("annotations", "true")).build())
			.build());
		return new MyBatisGenerator.Builder().withConfiguration(configuration).build();
	}

	/**
	 * 模拟 IDEA 提供的表结构，包含自增主键与有备注的可空时间列。
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

		IntellijTableInfo table = new IntellijTableInfo();
		table.setTableName("sample_table");
		table.setTableType("TABLE");
		table.setColumnInfos(List.of(id, created));
		table.setPrimaryKeyColumns(List.of(id));
		return table;
	}
}
