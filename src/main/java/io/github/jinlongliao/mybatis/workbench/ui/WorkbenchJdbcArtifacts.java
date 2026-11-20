package io.github.jinlongliao.mybatis.workbench.ui;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.apache.maven.settings.building.DefaultSettingsBuilderFactory;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingException;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import io.github.jinlongliao.mybatis.workbench.model.DbType;

/**
 * 通过 Maven Resolver 下载 JDBC 驱动及运行时依赖，复用用户配置的 Maven 本地仓库。
 *
 * @see <a href="https://maven.apache.org/resolver/archives/resolver-1.9.24/third-party-integrations.html">在 Maven 之外使用 Resolver</a>
 *
 * @author liaojinlong
 * @since 2026/11/18 15:18
 */
final class WorkbenchJdbcArtifacts {
	/**
	 * 解析器由静态方法提供。
	 */
	private WorkbenchJdbcArtifacts() {
	}

	/**
	 * 返回各数据库兼容的官方 JDBC 驱动版本。
	 *
	 * @param type 数据库类型
	 * @return Maven Central 坐标
	 */
	static String defaultDriverCoordinates(DbType type) {
		return switch (type) {
			case MySQL -> "com.mysql:mysql-connector-j:26.7.0";
			case PostgreSQL -> "org.postgresql:postgresql:42.7.14";
			case MariaDB -> "org.mariadb.jdbc:mariadb-java-client:3.5.10";
			case SQLite -> "org.xerial:sqlite-jdbc:3.54.1.0";
			case SqlServer -> "com.microsoft.sqlserver:mssql-jdbc:13.2.0.jre11";
			case Oracle -> "com.oracle.database.jdbc:ojdbc11:23.26.4.0.0";
			case H2 -> "com.h2database:h2:2.5.252";
		};
	}

	/**
	 * 返回对应标准 JDBC 入口类。
	 *
	 * @param type 数据库类型
	 * @return 驱动实现类名
	 */
	static String defaultDriverClass(DbType type) {
		return switch (type) {
			case MySQL -> "com.mysql.cj.jdbc.Driver";
			case PostgreSQL -> "org.postgresql.Driver";
			case MariaDB -> "org.mariadb.jdbc.Driver";
			case SQLite -> "org.sqlite.JDBC";
			case SqlServer -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
			case Oracle -> "oracle.jdbc.OracleDriver";
			case H2 -> "org.h2.Driver";
		};
	}

	/**
	 * 在后台解析一个固定版本的 Maven 坐标。填写仓库时只向所填仓库解析。
	 *
	 * @param coordinates groupId:artifactId:version
	 * @param repository  自定义 HTTP(S) Maven 仓库，留空使用 Maven Central
	 * @return 驱动及其传递依赖的本地路径
	 * @throws IOException              未获取到二进制驱动
	 * @throws IllegalArgumentException 坐标或仓库地址无效
	 */
	static List<Path> resolveDriverFiles(String coordinates, String repository) throws IOException {
		if (!coordinates.matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*:[A-Za-z0-9_][A-Za-z0-9_.-]*:[0-9][A-Za-z0-9_.-]*")
			|| coordinates.endsWith(":LATEST") || coordinates.endsWith(":RELEASE")) {
			throw new IllegalArgumentException(WorkbenchBundle.message("driver.coordinates.fixed.version"));
		}
		String repositoryId = "central";
		String repositoryUrl = "https://repo.maven.apache.org/maven2";
		if (!repository.isBlank()) {
			URI address;
			try {
				address = URI.create(repository);
			} catch (IllegalArgumentException failure) {
				throw new IllegalArgumentException(WorkbenchBundle.message("jdbc.profile.invalid.repository"), failure);
			}
			if ((!"https".equalsIgnoreCase(address.getScheme()) && !"http".equalsIgnoreCase(address.getScheme()))
				|| Objects.isNull(address.getHost()) || Objects.nonNull(address.getUserInfo())
				|| Objects.nonNull(address.getQuery()) || Objects.nonNull(address.getFragment())) {
				throw new IllegalArgumentException(WorkbenchBundle.message("jdbc.profile.invalid.repository"));
			}
			repositoryUrl = address.toASCIIString();
			// 本地仓库按仓库 ID 记录来源，切换服务器时隔离缓存来源。
			repositoryId = "mybatis-workbench-" + UUID.nameUUIDFromBytes(repositoryUrl.getBytes(StandardCharsets.UTF_8));
		}
		var remote = new RemoteRepository.Builder(repositoryId, "default", repositoryUrl).build();
		var artifact = new Dependency(new DefaultArtifact(coordinates), JavaScopes.RUNTIME);
		var request = new DependencyRequest(new CollectRequest(artifact, List.of(remote)),
			DependencyFilterUtils.classpathFilter(JavaScopes.RUNTIME));
		var system = new RepositorySystemSupplier().get();
		try {
			var session = MavenRepositorySystemUtils.newSession();
			session.setIgnoreArtifactDescriptorRepositories(true);
			session.setLocalRepositoryManager(system.newLocalRepositoryManager(session,
				new LocalRepository(resolveMavenLocalRepository().toFile())));
			return system.resolveDependencies(session, request).getArtifactResults().stream()
				.map(result -> result.getArtifact().getFile().toPath()).toList();
		} catch (DependencyResolutionException | SettingsBuildingException failure) {
			throw new IOException(WorkbenchBundle.message("driver.jar.unresolved", coordinates), failure);
		} finally {
			system.shutdown();
		}
	}

	/**
	 * @return 用户 settings.xml 中的本地仓库路径，缺省时返回 Maven 标准路径
	 * @throws SettingsBuildingException 本机 Maven 设置无法解析
	 */
	private static Path resolveMavenLocalRepository() throws SettingsBuildingException {
		Path mavenDirectory = Path.of(System.getProperty("user.home"), ".m2");
		Path settings = mavenDirectory.resolve("settings.xml");
		if (Files.isRegularFile(settings)) {
			var request = new DefaultSettingsBuildingRequest().setUserSettingsFile(settings.toFile())
				.setSystemProperties(System.getProperties());
			String configured = new DefaultSettingsBuilderFactory().newInstance()
				.build(request).getEffectiveSettings().getLocalRepository();
			if (Objects.nonNull(configured) && !configured.isBlank()) {
				return Path.of(configured);
			}
		}
		return mavenDirectory.resolve("repository");
	}
}
