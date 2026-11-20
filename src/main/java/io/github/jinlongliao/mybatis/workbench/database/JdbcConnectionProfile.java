package io.github.jinlongliao.mybatis.workbench.database;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

import io.github.jinlongliao.mybatis.workbench.model.DbType;

/**
 * 可在不同 IDE 之间交换的 JDBC 连接配置。凭据由调用方单独保管。
 *
 * @param id          跨设备稳定的连接标识
 * @param name        界面显示名称
 * @param dbType      数据库方言
 * @param url         JDBC 地址，凭据必须使用独立字段
 * @param username    数据库用户名，可以为空字符串
 * @param coordinates 驱动的 Maven 坐标
 * @param driverClass JDBC 驱动类名
 * @param repository  自定义 Maven 仓库地址，留空使用 Central
 * @author liaojinlong
 * @since 2026/11/18 15:22
 */
public record JdbcConnectionProfile(UUID id, String name, DbType dbType, String url, String username,
									String coordinates, String driverClass, String repository) {
	/**
	 * 常见 JDBC 密码、令牌和客户端私钥口令参数，以及 URL 中的 userinfo 形式。
	 *
	 * @see <a href="https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-authentication.html">MySQL Connector/J 认证属性</a>
	 * @see <a href="https://jdbc.postgresql.org/documentation/use/#connection-parameters">PostgreSQL JDBC 连接参数</a>
	 * @see <a href="https://learn.microsoft.com/en-us/sql/connect/jdbc/setting-the-connection-properties">SQL Server JDBC 连接属性</a>
	 * @see <a href="https://docs.oracle.com/en/database/oracle/oracle-database/26/jajdb/oracle/jdbc/OracleConnection.html">Oracle JDBC 连接属性</a>
	 */
	private static final Pattern INLINE_SECRET = Pattern.compile(
		"(?i)(?:[?;&(]\\s*(?:password[123]?|pwd|accessToken|token|apiKey|secret|sslpassword|clientKeyPassword|"
			+ "keyStoreSecret|aadSecurePrincipalSecret|(?:javax\\.net\\.ssl\\.)?(?:keyStorePassword|trustStorePassword)|"
			+ "(?:oracle\\.net\\.)?wallet_password)\\s*=|//[^/?#;]*@)");

	/**
	 * 检查共享配置，确保常见的内嵌凭据格式不能进入交换文件。
	 *
	 * @throws IllegalArgumentException 连接缺少必要字段或地址中包含凭据
	 */
	public void validateShareableProfile() {
		validateShareableProfile(key -> ResourceBundle.getBundle("messages.WorkbenchBundle", Locale.getDefault()).getString(key));
	}

	/**
	 * 校验同一套共享配置规则，调用方可以提供与应用界面语言一致的文案。
	 *
	 * @param validationMessage 从工作台资源键解析错误提示
	 * @throws IllegalArgumentException 连接缺少必要字段、地址不合法或包含凭据
	 */
	public void validateShareableProfile(Function<String, String> validationMessage) {
		if (name.isBlank() || !url.startsWith("jdbc:") || coordinates.isBlank() || driverClass.isBlank()) {
			throw new IllegalArgumentException(validationMessage.apply("jdbc.profile.required.fields"));
		}
		if (INLINE_SECRET.matcher(url).find() || containsEncodedMySqlCredential()
			|| url.matches("(?i)jdbc:oracle:[^:]+:[^@]+/[^@]+@.+")) {
			throw new IllegalArgumentException(validationMessage.apply("jdbc.profile.inline.credentials"));
		}
		if (!repository.isBlank()) {
			URI address;
			try {
				address = new URI(repository);
			} catch (URISyntaxException failure) {
				throw new IllegalArgumentException(validationMessage.apply("jdbc.profile.invalid.repository"), failure);
			}
			if (!("https".equalsIgnoreCase(address.getScheme()) || "http".equalsIgnoreCase(address.getScheme()))
				|| Objects.isNull(address.getHost()) || Objects.nonNull(address.getRawUserInfo()) || Objects.nonNull(address.getRawQuery())
				|| Objects.nonNull(address.getRawFragment())) {
				throw new IllegalArgumentException(validationMessage.apply("jdbc.profile.invalid.repository"));
			}
		}
	}

	/**
	 * MySQL Connector/J 会解码查询参数名。只检查键，避免把路径或参数值识别成凭据。
	 *
	 * @return 是否含编码后的认证字段名
	 * @throws IllegalArgumentException 查询参数名的百分号编码无效
	 */
	private boolean containsEncodedMySqlCredential() {
		if (!url.regionMatches(true, 0, "jdbc:mysql:", 0, "jdbc:mysql:".length())) {
			return false;
		}
		int queryStart = url.indexOf('?');
		if (queryStart < 0) {
			return false;
		}
		for (String option : url.substring(queryStart + 1).split("&")) {
			int separator = option.indexOf('=');
			if (separator > 0) {
				String key = option.substring(0, separator);
				if (key.indexOf('%') >= 0 && INLINE_SECRET.matcher(
					"?" + URLDecoder.decode(key, StandardCharsets.UTF_8) + "=").matches()) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * 将配置写成版本化的 Java properties 文本，调用者负责关闭写入器。
	 *
	 * @param writer UTF-8 文件或同步存储的写入器
	 * @throws IOException 无法写入配置
	 */
	public void writeProfile(Writer writer) throws IOException {
		ResourceBundle messages = ResourceBundle.getBundle("messages.WorkbenchBundle", Locale.getDefault());
		writeProfile(writer, messages::getString);
	}

	/**
	 * 按宿主界面语言校验配置，校验成功后将其写入版本化交换文本。
	 *
	 * @param writer 配置输出器，由调用方关闭
	 * @param validationMessage 从资源键解析当前界面语言的错误提示
	 * @throws IOException 写入失败
	 * @throws IllegalArgumentException 配置无效或包含不可共享的凭据
	 */
	public void writeProfile(Writer writer, Function<String, String> validationMessage) throws IOException {
		validateShareableProfile(validationMessage);
		Properties properties = new Properties();
		properties.setProperty("format", "mybatis-workbench-jdbc/1");
		properties.setProperty("id", id.toString());
		properties.setProperty("name", name);
		properties.setProperty("database", dbType.name());
		properties.setProperty("url", url);
		properties.setProperty("username", username);
		properties.setProperty("driver.coordinates", coordinates);
		properties.setProperty("driver.class", driverClass);
		properties.setProperty("driver.repository", repository);
		ConnectionProfileProperties.writeProfileProperties(properties, writer);
	}

	/**
	 * 从版本化的交换文本读取一份连接配置，调用者负责关闭读取器。
	 *
	 * @param reader UTF-8 文件或同步存储中的配置文本
	 * @return 可直接填充连接表单的配置
	 * @throws IOException 文件不兼容、配置无效或读取失败
	 */
	public static JdbcConnectionProfile readProfile(Reader reader) throws IOException {
		ResourceBundle messages = ResourceBundle.getBundle("messages.WorkbenchBundle", Locale.getDefault());
		return readProfile(reader, messages::getString);
	}

	/**
	 * 使用宿主界面语言读取连接配置并验证格式版本。
	 *
	 * @param reader 配置文本输入器，由调用方关闭
	 * @param validationMessage 从资源键解析当前界面语言的错误提示
	 * @return 已校验的连接配置
	 * @throws IOException 格式或配置无效，或读取失败
	 */
	public static JdbcConnectionProfile readProfile(Reader reader, Function<String, String> validationMessage) throws IOException {
		try {
			Properties properties = ConnectionProfileProperties.readProfileProperties(reader);
			if (!"mybatis-workbench-jdbc/1".equals(properties.getProperty("format"))) {
				throw new IOException(validationMessage.apply("jdbc.profile.unsupported.format"));
			}
			JdbcConnectionProfile profile = new JdbcConnectionProfile(
				ConnectionProfileProperties.readProfileId(properties.getProperty("id", "")), properties.getProperty("name", ""),
				DbType.valueOf(properties.getProperty("database", "")), properties.getProperty("url", ""),
				properties.getProperty("username", ""), properties.getProperty("driver.coordinates", ""),
				properties.getProperty("driver.class", ""), properties.getProperty("driver.repository", ""));
			profile.validateShareableProfile(validationMessage);
			return profile;
		} catch (IllegalArgumentException failure) {
			throw new IOException(validationMessage.apply("jdbc.profile.invalid") + failure.getMessage(), failure);
		}
	}
}
