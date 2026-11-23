package io.github.jinlongliao.mybatis.workbench.generate;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;

import org.mybatis.generator.api.MyBatisGenerator;
import org.mybatis.generator.api.intellij.IntellijTableInfo;
import org.mybatis.generator.config.*;
import org.mybatis.generator.internal.types.DateForcingJavaTypeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import io.github.jinlongliao.mybatis.workbench.generate.plugin.*;
import io.github.jinlongliao.mybatis.workbench.model.Config;
import io.github.jinlongliao.mybatis.workbench.model.DbType;
import io.github.jinlongliao.mybatis.workbench.model.GenerationTable;
import io.github.jinlongliao.mybatis.workbench.setting.PersistentConfig;
import io.github.jinlongliao.mybatis.workbench.util.GeneratorCallback;
import io.github.jinlongliao.mybatis.workbench.util.StringUtils;

/**
 * 生成mybatis相关代码
 */
public class MybatisGenerator {
	/**
	 * 在生成入口记录失败，保留源表和完整异常堆栈。
	 */
	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
	private Project project;
	private PersistentConfig persistentConfig;//持久化的配置
	private final Config config;//界面默认配置
	private DbType dbType;//数据库类型
	private IntellijTableInfo intellijTableInfo;

	public MybatisGenerator(Config config) {
		this.config = config;
	}

	/**
	 * 按已选中的表快照生成文件，必要时先保存生成配置。
	 *
	 * @param project         目标 IDEA 项目
	 * @param generationTable 当前选中表的结构和 SQL 方言信息
	 * @param saveConfig      是否记录本次生成配置
	 * @return 生成器报告的警告和失败信息
	 */
	public List<String> generateMapperFiles(Project project, GenerationTable generationTable, boolean saveConfig) {
		List<String> result = new ArrayList<>();
		this.project = project;
		this.persistentConfig = PersistentConfig.getInstance(project);
		intellijTableInfo = generationTable.tableInfo();
		dbType = generationTable.dbType();

		if (saveConfig) {
			saveConfig();
		}

		ApplicationManager.getApplication().runWriteAction(new Runnable() {
			@Override
			public void run() {

				Path stagedXmlDirectory = null;
				try {
					Path xmlTargetDirectory = Path.of(config.getProjectFolder(), config.getXmlMvnPath());
					createFolderForNeed(config);
					// 暂存 XML，待生成结束并完成合并校验后，再替换目标文件。
					stagedXmlDirectory = Files.createTempDirectory(xmlTargetDirectory, ".mybatis-");
					Configuration configuration = new Configuration();
					Context.Builder context = new Context.Builder()
						.withId("myid")
						.withDefaultModelType(ModelType.CONDITIONAL)
						.withTargetRuntime("MyBatis3")
						.withProperty(new Property("autoDelimitKeywords", "true"))
						.withProperty(new Property(PropertyRegistry.CONTEXT_JAVA_FILE_ENCODING, "UTF-8"))
						.withTableConfiguration(buildTableConfig(generationTable))
						.withModelGeneratorConfiguration(buildModelConfig())
						.withSqlMapGeneratorConfiguration(buildMapperXmlConfig(stagedXmlDirectory))
						.withClientGeneratorConfiguration(buildDaoConfig())
						.withCommentGeneratorConfiguration(buildCommentConfig())
						.withConnectionFactoryConfiguration(new ConnectionFactoryConfiguration.Builder()
							.withConfigurationType(IdeaTableConnectionFactory.class.getName())
							.build());

					configureIdentifierDelimiters(context, dbType);
					addPluginConfiguration(context);
					configuration.addContext(context.build());

					MyBatisGenerator generator = new MyBatisGenerator.Builder()
						.withConfiguration(configuration)
						.withProgressCallback(new GeneratorCallback())
						.withOverwriteEnabled(config.isOverrideJava())
						.build();
					result.addAll(IdeaTableConnectionFactory.generateMapperFiles(generator, intellijTableInfo));
					MapperXmlPublisher.publishMapperXml(generator.getGeneratedXmlFiles(), stagedXmlDirectory,
						xmlTargetDirectory, config.isOverrideXML());
				} catch (Exception e) {
					log.error("MyBatis 生成失败，table={}, model={}, mapper={}, result={}",
						config.getTableName(), config.getModelName(), config.getDaoName(), result, e);
					Messages.showMessageDialog(e.getMessage(), "MybatisGenerator Failure", Messages.getErrorIcon());
					result.add(e.getMessage());
				} finally {
					if (Objects.nonNull(stagedXmlDirectory) && !FileUtil.delete(stagedXmlDirectory.toFile())) {
						log.warn("MyBatis 生成结束后无法清理临时 XML 目录，table={}, directory={}",
							config.getTableName(), stagedXmlDirectory);
					}
				}
				// project.getBaseDir().refresh(true, true);
				// project.getBaseDir().refresh(false, true);
				VirtualFile baseDir = ProjectUtil.guessProjectDir(project);
				if (baseDir != null) {
					baseDir.refresh(true, true);
					baseDir.refresh(false, true);
				}
			}
		});
		return result;
	}

	/**
	 * 创建所需目录
	 */
	private void createFolderForNeed(Config config) {

		String modelMvnPath = config.getModelMvnPath();
		String daoMvnPath = config.getDaoMvnPath();
		String xmlMvnPath = config.getXmlMvnPath();

		String modelPath = config.getProjectFolder() + "/" + modelMvnPath + "/";
		String daoPath = config.getProjectFolder() + "/" + daoMvnPath + "/";
		String xmlPath = config.getProjectFolder() + "/" + xmlMvnPath + "/";

		File modelFile = new File(modelPath);
		if (!modelFile.exists() && !modelFile.isDirectory()) {
			modelFile.mkdirs();
		}

		File daoFile = new File(daoPath);
		if (!daoFile.exists() && !daoFile.isDirectory()) {
			daoFile.mkdirs();
		}

		File xmlFile = new File(xmlPath);
		if (!xmlFile.exists() && !xmlFile.isDirectory()) {
			xmlFile.mkdirs();
		}

	}


	/**
	 * 保存当前配置到历史记录
	 */
	private void saveConfig() {
		Map<String, Config> historyConfigList = persistentConfig.getHistoryConfigList();
		if (historyConfigList == null) {
			historyConfigList = new HashMap<>();
		}

		String daoName = config.getDaoName();
		String modelName = config.getModelName();
		String daoPostfix = daoName.replace(modelName, "");
		config.setDaoPostfix(daoPostfix);

		historyConfigList.put(config.getName(), config);
		persistentConfig.setHistoryConfigList(historyConfigList);

	}


	/**
	 * 构建选中表的生成规则。保留生成列的读取映射，禁止在生成的写入语句中为其赋值。
	 *
	 * @param table 当前数据源提供的列和主键快照
	 * @return 根据当前表快照和界面选项构建的 MyBatis Generator 表配置
	 * @see ColumnOverride.Builder#withGeneratedAlways(boolean)
	 */
	TableConfiguration buildTableConfig(GenerationTable table) {
		TableConfiguration.Builder tableConfig = new TableConfiguration.Builder();
		tableConfig.withTableName(config.getTableName());
		tableConfig.withDomainObjectName(config.getModelName());
		// 根据数据源提供的实际模式，只有启用前缀时才将其写入生成的 SQL。
		if (config.isUseSchemaPrefix() && !StringUtils.isEmpty(table.qualifier())) {
			tableConfig.withSchema(table.qualifier());
		}

		if (!config.isUseExample()) {
			tableConfig.withUpdateByExampleStatementEnabled(false);
			tableConfig.withCountByExampleStatementEnabled(false);
			tableConfig.withDeleteByExampleStatementEnabled(false);
			tableConfig.withSelectByExampleStatementEnabled(false);
		}

		String delimiter = switch (table.dbType()) {
			case MySQL, MariaDB -> "`";
			case PostgreSQL, H2, SQLite, Oracle -> "\"";
			case SqlServer -> "]";
			case null, default -> "";
		};
		if (!delimiter.isEmpty()) {
			tableConfig.withDelimitIdentifiers(true);
			// 元数据读取使用原始名称，生成 SQL 时双写名称中的结束定界符。
			String escapedDelimiter = delimiter.repeat(2);
			tableConfig.withProperty(new Property(PropertyRegistry.TABLE_RUNTIME_TABLE_NAME,
				config.getTableName().replace(delimiter, escapedDelimiter)));
			if (config.isUseSchemaPrefix() && !StringUtils.isEmpty(table.qualifier())) {
				tableConfig.withProperty(new Property(PropertyRegistry.TABLE_RUNTIME_SCHEMA,
					table.qualifier().replace(delimiter, escapedDelimiter)));
			}
		}

		if (!StringUtils.isEmpty(config.getPrimaryKey())) {
			// 通过 JDBC getGeneratedKeys 回填主键，避免额外执行 SelectKey。
			tableConfig.withGeneratedKey(new GeneratedKey(config.getPrimaryKey(), "JDBC", true));
		}

		if (config.isUseActualColumnNames()) {
			tableConfig.withProperty(new Property("useActualColumnNames", "true"));
		}

		if (config.isUseTableNameAlias()) {
			tableConfig.withAlias(config.getTableName());
		}

		for (var column : table.tableInfo().getColumnInfos()) {
			// 自增列继续走现有 GeneratedKey 路径，原生数据源可能同时给出两个标记。
			if (column.isGeneratedColumn() && !column.isAutoIncrement()) {
				tableConfig.withColumnOverride(new ColumnOverride.Builder()
					.withColumnName(column.getName())
					.withGeneratedAlways(true)
					.build());
			}
		}

		tableConfig.withMapperName(config.getDaoName());
		return tableConfig.build();
	}

	/**
	 * 设置生成 SQL 使用的标识符定界符。SQL Server 方括号不依赖会话的引号设置。
	 *
	 * @param context 即将构建的 MyBatis Generator 上下文
	 * @param type 当前表所属的 JDBC 方言
	 */
	static void configureIdentifierDelimiters(Context.Builder context, DbType type) {
		if (DbType.MySQL.equals(type) || DbType.MariaDB.equals(type)) {
			context.withProperty(new Property(PropertyRegistry.CONTEXT_BEGINNING_DELIMITER, "`"));
			context.withProperty(new Property(PropertyRegistry.CONTEXT_ENDING_DELIMITER, "`"));
		} else if (DbType.SqlServer.equals(type)) {
			context.withProperty(new Property(PropertyRegistry.CONTEXT_BEGINNING_DELIMITER, "["));
			context.withProperty(new Property(PropertyRegistry.CONTEXT_ENDING_DELIMITER, "]"));
		}
	}


	/**
	 * 生成实体类配置
	 *
	 * @return
	 */
	private ModelGeneratorConfiguration buildModelConfig() {
		String projectFolder = config.getProjectFolder();
		String modelPackage = config.getModelPackage();
		String modelPackageTargetFolder = config.getModelTargetFolder();
		String modelMvnPath = config.getModelMvnPath();

		ModelGeneratorConfiguration.Builder modelConfig = new ModelGeneratorConfiguration.Builder();

		if (!StringUtils.isEmpty(modelPackage)) {
			modelConfig.withTargetPackage(modelPackage);
		} else {
			modelConfig.withTargetPackage("generator");
		}

		if (!StringUtils.isEmpty(modelPackageTargetFolder)) {
			modelConfig.withTargetProject(modelPackageTargetFolder + "/" + modelMvnPath + "/");
		} else {
			modelConfig.withTargetProject(projectFolder + "/" + modelMvnPath + "/");
		}
		return modelConfig.build();
	}

	/**
	 * 构建本次生成使用的 Mapper XML 暂存配置。
	 *
	 * @param targetDirectory 本次生成的 XML 输出根目录
	 * @return XML 包名和输出目录配置
	 */
	private SqlMapGeneratorConfiguration buildMapperXmlConfig(Path targetDirectory) {
		String xmlPackage = config.getXmlPackage();
		return new SqlMapGeneratorConfiguration.Builder()
			.withTargetPackage(StringUtils.isEmpty(xmlPackage) ? "generator" : xmlPackage)
			.withTargetProject(targetDirectory.toString())
			.build();
	}

	/**
	 * 生成dao接口文件配置
	 *
	 * @return
	 */
	private ClientGeneratorConfiguration buildDaoConfig() {

		String projectFolder = config.getProjectFolder();
		String daoPackage = config.getDaoPackage();
		String daoTargetFolder = config.getDaoTargetFolder();
		String daoMvnPath = config.getDaoMvnPath();

		ClientGeneratorConfiguration.Builder daoConfig = new ClientGeneratorConfiguration.Builder();
		daoConfig.withLegacyClientType(ClientGeneratorConfiguration.LegacyClientType.XML_MAPPER);

		if (!StringUtils.isEmpty(daoPackage)) {
			daoConfig.withTargetPackage(daoPackage);
		} else {
			daoConfig.withTargetPackage("generator");
		}

		if (!StringUtils.isEmpty(daoTargetFolder)) {
			daoConfig.withTargetProject(daoTargetFolder + "/" + daoMvnPath + "/");
		} else {
			daoConfig.withTargetProject(projectFolder + "/" + daoMvnPath + "/");
		}

		return daoConfig.build();
	}

	/**
	 * 生成注释配置
	 *
	 * @return
	 */
	private CommentGeneratorConfiguration buildCommentConfig() {
		CommentGeneratorConfiguration.Builder commentConfig = new CommentGeneratorConfiguration.Builder();
		commentConfig.withConfigurationType(DbRemarksCommentGenerator.class.getName());

		if (config.isAnnotation()) {
			commentConfig.withProperty(new Property("annotations", "true"));
		}

		return commentConfig.build();
	}

	/**
	 * 保留各生成选项及扩展执行顺序。未开启 JSR-310 时继续使用旧版日期类型。
	 *
	 * @param context 当前生成器的上下文配置
	 */
	private void addPluginConfiguration(Context.Builder context) {
		addGeneratorPlugin(context, "org.mybatis.generator.plugins.SerializablePlugin");
		if (config.isNeedToStringHashcodeEquals()) {
			addGeneratorPlugin(context, "org.mybatis.generator.plugins.EqualsHashCodePlugin");
			addGeneratorPlugin(context, "org.mybatis.generator.plugins.ToStringPlugin");
		}

		boolean supportsSqlExtensions = DbType.MySQL.equals(dbType) || DbType.PostgreSQL.equals(dbType);
		if (config.isOffsetLimit() && supportsSqlExtensions) {
			addGeneratorPlugin(context, MySQLLimitPlugin.class.getName());
		}
		if (!config.isJsr310Support()) {
			context.withJavaTypeResolverConfiguration(new JavaTypeResolverConfiguration.Builder()
				.withConfigurationType(DateForcingJavaTypeResolver.class.getName()).build());
		}
		if (config.isNeedForUpdate() && supportsSqlExtensions) {
			addGeneratorPlugin(context, MySQLForUpdatePlugin.class.getName());
		}
		if (config.isAnnotationDAO() && supportsSqlExtensions) {
			addGeneratorPlugin(context, RepositoryPlugin.class.getName());
		}
		if (config.isUseDAOExtendStyle() && supportsSqlExtensions) {
			addGeneratorPlugin(context, CommonDAOInterfacePlugin.class.getName());
		}
		if (config.isUseLombokPlugin()) {
			addGeneratorPlugin(context, LombokPlugin.class.getName());
		}
		if (config.isUseSwaggerPlugin()) {
			addGeneratorPlugin(context, GeneratorSwagger2DocPlugin.class.getName());
		}
	}

	/**
	 * 按注册顺序附加一个生成插件。
	 *
	 * @param context         当前生成器的上下文配置
	 * @param pluginClassName 生成插件类的全限定名
	 */
	private void addGeneratorPlugin(Context.Builder context, String pluginClassName) {
		context.withPluginConfiguration(new PluginConfiguration.Builder()
			.withConfigurationType(pluginClassName).build());
	}

}
