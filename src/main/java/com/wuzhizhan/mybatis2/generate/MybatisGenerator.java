package com.wuzhizhan.mybatis2.generate;

import com.intellij.database.model.RawConnectionConfig;
import com.intellij.database.psi.DbTable;
import com.intellij.database.util.DasUtil;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.wuzhizhan.mybatis2.generate.plugin.*;
import com.wuzhizhan.mybatis2.model.Config;
import com.wuzhizhan.mybatis2.model.DbType;
import com.wuzhizhan.mybatis2.setting.PersistentConfig;
import com.wuzhizhan.mybatis2.util.DbToolsUtils;
import com.wuzhizhan.mybatis2.util.GeneratorCallback;
import com.wuzhizhan.mybatis2.util.StringUtils;

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

/**
 * 生成mybatis相关代码
 */
public class MybatisGenerator {
	/**
	 * 在生成入口记录失败，保留源表和完整异常堆栈。
	 */
	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
	/**
	 * IDEA 返回的表模式，未区分模式的数据库可使用目录作为 SQL 前缀。
	 */
	private String tableQualifier;
	private Project project;
	private PersistentConfig persistentConfig;//持久化的配置
	private final Config config;//界面默认配置
	private DbType dbType;//数据库类型
	private IntellijTableInfo intellijTableInfo;

	public MybatisGenerator(Config config) {
		this.config = config;
	}

	/**
	 * 自动生成的主逻辑
	 */
	public List<String> execute(final AnActionEvent anActionEvent, boolean saveConfig) throws Exception {
		List<String> result = new ArrayList<>();
		this.project = anActionEvent.getProject();
		this.persistentConfig = PersistentConfig.getInstance(project);

		if (saveConfig) {
			saveConfig();//执行前 先保存一份当前配置
		}
		final PsiElement[] psiElements = anActionEvent.getData(LangDataKeys.PSI_ELEMENT_ARRAY);

		if (psiElements == null || psiElements.length == 0) {
			result.add("can not generate! \nplease select table");
			return result;
		}
		PsiElement psiElement = psiElements[0];
		if (!(psiElement instanceof DbTable selectedTable)) {
			result.add("can not generate! \nplease select table");
			return result;
		}

		intellijTableInfo = DbToolsUtils.buildIntellijTableInfo(selectedTable);
		tableQualifier = DasUtil.getSchema(selectedTable.getDasObject());
		if (StringUtils.isEmpty(tableQualifier)) {
			tableQualifier = DasUtil.getCatalog(selectedTable.getDasObject());
		}

		RawConnectionConfig connectionConfig = selectedTable.getDataSource().getConnectionConfig();

		String driverClass = connectionConfig.getDriverClass();
		if (driverClass.contains("mysql")) {
			dbType = DbType.MySQL;
		} else if (driverClass.contains("oracle")) {
			dbType = DbType.Oracle;
		} else if (driverClass.contains("postgresql")) {
			dbType = DbType.PostgreSQL;
		} else if (driverClass.contains("sqlserver")) {
			dbType = DbType.SqlServer;
		} else if (driverClass.contains("mariadb")) {
			dbType = DbType.MariaDB;
		} else {
			String failMessage = String.format("db type not support!" +
					"\n your driver class:%s" +
					"\n current support db type:mysql，mariadb，oracle,postgresql",
				driverClass);
			Messages.showMessageDialog(project, failMessage,
				"Test Connection Error", Messages.getInformationIcon());
			return result;
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
						.withTableConfiguration(buildTableConfig())
						.withModelGeneratorConfiguration(buildModelConfig())
						.withSqlMapGeneratorConfiguration(buildMapperXmlConfig(stagedXmlDirectory))
						.withClientGeneratorConfiguration(buildDaoConfig())
						.withCommentGeneratorConfiguration(buildCommentConfig())
						.withConnectionFactoryConfiguration(new ConnectionFactoryConfiguration.Builder()
							.withConfigurationType(IdeaTableConnectionFactory.class.getName())
							.build());

					if (DbType.MySQL.equals(dbType) || DbType.MariaDB.equals(dbType)) {
						context.withProperty(new Property("beginningDelimiter", "`"));
						context.withProperty(new Property("endingDelimiter", "`"));
					}
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
	 * 生成table配置
	 */
	private TableConfiguration buildTableConfig() {
		TableConfiguration.Builder tableConfig = new TableConfiguration.Builder();
		tableConfig.withTableName(config.getTableName());
		tableConfig.withDomainObjectName(config.getModelName());
		// 从 IDEA 读取实际模式，只有启用前缀时才将其写入生成的 SQL。
		if (config.isUseSchemaPrefix() && !StringUtils.isEmpty(tableQualifier)) {
			tableConfig.withSchema(tableQualifier);
		}

		if (!config.isUseExample()) {
			tableConfig.withUpdateByExampleStatementEnabled(false);
			tableConfig.withCountByExampleStatementEnabled(false);
			tableConfig.withDeleteByExampleStatementEnabled(false);
			tableConfig.withSelectByExampleStatementEnabled(false);
		}

		if (DbType.PostgreSQL.equals(dbType)) {
			tableConfig.withDelimitIdentifiers(true);
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

//        if (ignoredColumns != null) {
//            ignoredColumns.stream().forEach(new Consumer<IgnoredColumn>() {
//                @Override
//                public void accept(IgnoredColumn ignoredColumn) {
//                    tableConfig.addIgnoredColumn(ignoredColumn);
//                }
//            });
//        }
//        if (columnOverrides != null) {
//            for (ColumnOverride columnOverride : columnOverrides) {
//                tableConfig.addColumnOverride(columnOverride);
//            }
//        }

		tableConfig.withMapperName(config.getDaoName());
		return tableConfig.build();
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
