package io.github.jinlongliao.mybatis.workbench.ui;

import com.mongodb.ConnectionString;

import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import io.github.jinlongliao.mybatis.workbench.database.JdbcConnectionProfile;
import io.github.jinlongliao.mybatis.workbench.database.NoSqlConnectionProfile;
import io.github.jinlongliao.mybatis.workbench.database.NoSqlSourceType;
import io.github.jinlongliao.mybatis.workbench.model.DbType;

/**
 * 读取 JetBrains 本地数据库配置。迁移支持的 JDBC、Redis、MongoDB 和 Elasticsearch 连接参数。
 *
 * @author liaojinlong
 * @see <a href="https://www.jetbrains.com/help/datagrip/managing-data-sources.html#share-data-sources">JetBrains 数据源分享</a>
 * @since 2026/11/18 15:26
 */
final class WorkbenchJetBrainsDataSources {
	/**
	 * XML 迁移使用静态方法。
	 */
	private WorkbenchJetBrainsDataSources() {
	}

	/**
	 * 读取数据源文件。拒绝外部实体和文档类型声明，不读取密码节点。
	 *
	 * @param content 有界的 XML 文件字节
	 * @return 可迁移的数据库连接及被拒绝的已支持条目数量
	 * @throws IOException XML 读取或解析失败
	 */
	static MigrationResult readDataSources(byte[] content) throws IOException {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			var builder = factory.newDocumentBuilder();
			builder.setErrorHandler(new DefaultHandler());
			var document = builder.parse(new ByteArrayInputStream(content));
			var sources = document.getElementsByTagName("data-source");
			List<JdbcConnectionProfile> profiles = new ArrayList<>(sources.getLength());
			List<NoSqlConnectionProfile> noSqlProfiles = new ArrayList<>();
			int rejected = 0;
			for (int index = 0; index < sources.getLength(); index++) {
				Element source = (Element) sources.item(index);
				String url = firstElementText(source, "jdbc-url");
				DbType type = inferJdbcDatabaseType(url);
				if (Objects.isNull(type)) {
					try {
						NoSqlConnectionProfile profile = readNoSqlDataSource(source, url);
						if (Objects.nonNull(profile)) {
							noSqlProfiles.add(profile);
						}
					} catch (IllegalArgumentException failure) {
						rejected++;
					}
					continue;
				}
				JdbcConnectionProfile profile = new JdbcConnectionProfile(UUID.randomUUID(),
					source.getAttribute("name"), type, url, firstElementText(source, "user-name"),
					WorkbenchJdbcArtifacts.defaultDriverCoordinates(type),
					WorkbenchJdbcArtifacts.defaultDriverClass(type), "");
				try {
					profile.validateShareableProfile();
					profiles.add(profile);
				} catch (IllegalArgumentException failure) {
					// 单条配置错误不影响同文件中其他受支持的连接。
					rejected++;
				}
			}
			return new MigrationResult(List.copyOf(profiles), List.copyOf(noSqlProfiles), rejected);
		} catch (SAXException | ParserConfigurationException failure) {
			throw new IOException(WorkbenchBundle.message("jetbrains.data.source.xml.invalid"), failure);
		}
	}

	/**
	 * 校验后的数据源迁移候选。
	 *
	 * @param profiles 可供用户确认的 JDBC 连接
	 * @param noSqlProfiles 可供用户确认的非关系库连接
	 * @param rejected 校验失败的受支持条目数，未知协议已跳过
	 * @author liaojinlong
	 * @since 2026/11/18 18:41
	 */
	record MigrationResult(List<JdbcConnectionProfile> profiles, List<NoSqlConnectionProfile> noSqlProfiles, int rejected) {
	}

	/**
	 * 转换已识别的 JetBrains 非关系库地址。仅导入可由工作台表示的配置，密码由用户在本机重新填写。
	 *
	 * @param source 数据源 XML 节点
	 * @param jdbcUrl 数据源原始地址
	 * @return 可迁移的非关系库配置，未知协议返回空
	 * @throws IllegalArgumentException 地址包含凭据或包含工作台无法保留的选项
	 */
	private static NoSqlConnectionProfile readNoSqlDataSource(Element source, String jdbcUrl) {
		String url = jdbcUrl.startsWith("jdbc:") ? jdbcUrl.substring("jdbc:".length()) : jdbcUrl;
		String name = source.getAttribute("name");
		String username = firstElementText(source, "user-name");
		UUID id = UUID.randomUUID();
		NoSqlConnectionProfile profile;
		if (url.startsWith("redis://") || url.startsWith("rediss://")) {
			URI address = URI.create(url);
			if (Objects.isNull(address.getHost()) || Objects.nonNull(address.getRawUserInfo())
				|| Objects.nonNull(address.getRawQuery()) || Objects.nonNull(address.getRawFragment())) {
				throw new IllegalArgumentException("Unsupported Redis data source address");
			}
			String path = address.getPath();
			int database = path.isEmpty() || "/".equals(path) ? 0 : Integer.parseInt(path.substring(1));
			int port = address.getPort() < 0 ? 6379 : address.getPort();
			profile = new NoSqlConnectionProfile(id, name, NoSqlSourceType.REDIS,
				address.getHost() + ":" + port, username, "", false, "rediss".equals(address.getScheme()), database);
		} else if (url.startsWith("mongodb://") || url.startsWith("mongodb+srv://")) {
			// 先用共享规则拒绝 URL 内嵌的用户信息和不支持交换的参数，再解析 MongoDB 地址。
			new NoSqlConnectionProfile(id, name, NoSqlSourceType.MONGODB, url, username, "", false, false, 0)
				.validateShareableProfile();
			profile = new NoSqlConnectionProfile(id, name, NoSqlSourceType.MONGODB, url, username,
				resolveMongoAuthenticationDatabase(url), false, false, 0);
		} else if (url.startsWith("es://") || url.startsWith("elasticsearch://")) {
			profile = new NoSqlConnectionProfile(id, name, NoSqlSourceType.ELASTICSEARCH,
				resolveElasticsearchHttpsEndpoint(url), "", "", false, true, 0);
		} else {
			return null;
		}
		profile.validateShareableProfile();
		return profile;
	}

	/**
	 * 将显式 HTTPS 的 Elasticsearch JDBC 地址转换为工作台的 REST 端点。
	 * 保留服务路径，只接受可直接迁移的无凭据地址。JDBC 地址省略端口时使用其默认端口 9200。
	 *
	 * @param url 已移除 jdbc 前缀的 Elasticsearch 地址
	 * @return 可供工作台验证的 HTTPS 端点
	 * @throws IllegalArgumentException 地址无法解析、含 JDBC 参数或不使用 HTTPS
	 * @see <a href="https://www.elastic.co/docs/reference/query-languages/sql/sql-jdbc">Elasticsearch SQL JDBC 地址</a>
	 */
	private static String resolveElasticsearchHttpsEndpoint(String url) {
		String endpoint = url.substring(url.indexOf("://") + 3);
		URI address = URI.create(endpoint);
		if (!"https".equalsIgnoreCase(address.getScheme()) || Objects.isNull(address.getHost())
			|| Objects.nonNull(address.getRawUserInfo()) || Objects.nonNull(address.getRawQuery())
			|| Objects.nonNull(address.getRawFragment())) {
			throw new IllegalArgumentException("Unsupported Elasticsearch JDBC data source address");
		}
		return address.getPort() < 0 ? "https://" + address.getHost() + ":9200" + address.getRawPath() : endpoint;
	}

	/**
	 * 提取 MongoDB 的认证库。显式 authSource 优先，其次使用连接地址中的数据库，缺省使用 admin。
	 *
	 * @param url 已通过共享配置校验的 MongoDB 地址
	 * @return 本机录入账号时使用的认证库
	 * @throws IllegalArgumentException MongoDB 地址或参数无效
	 */
	private static String resolveMongoAuthenticationDatabase(String url) {
		// 导入只读取文件内容。实际连接时由驱动解析 SRV 和 TXT。
		ConnectionString address = new ConnectionString(url, (name, recordType) -> List.of());
		int queryStart = url.indexOf('?');
		if (queryStart >= 0) {
			for (String option : url.substring(queryStart + 1).split("[&;]")) {
				String[] property = option.split("=", 2);
				if (property.length == 2 && "authSource".equalsIgnoreCase(URLDecoder.decode(property[0], StandardCharsets.UTF_8))) {
					return URLDecoder.decode(property[1], StandardCharsets.UTF_8);
				}
			}
		}
		return Objects.toString(address.getDatabase(), "admin");
	}

	/**
	 * 读取数据源的首个指定元素值，缺少元素时返回空字符串。
	 *
	 * @param source 当前数据源节点
	 * @param name   允许迁移的元素名称
	 * @return 元素内容
	 */
	private static String firstElementText(Element source, String name) {
		var node = source.getElementsByTagName(name).item(0);
		return Objects.isNull(node) ? "" : node.getTextContent().strip();
	}

	/**
	 * 按标准 JDBC URL 的子协议识别方言。
	 *
	 * @param url JDBC 地址
	 * @return 已支持的关系数据库，未知子协议返回空
	 */
	private static DbType inferJdbcDatabaseType(String url) {
		String[] parts = url.toLowerCase(Locale.ROOT).split(":", 3);
		if (parts.length != 3 || !"jdbc".equals(parts[0])) {
			return null;
		}
		return switch (parts[1]) {
			case "mysql" -> DbType.MySQL;
			case "postgresql" -> DbType.PostgreSQL;
			case "mariadb" -> DbType.MariaDB;
			case "sqlite" -> DbType.SQLite;
			case "sqlserver" -> DbType.SqlServer;
			case "oracle" -> DbType.Oracle;
			case "h2" -> DbType.H2;
			default -> null;
		};
	}
}
