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

/**
 * 可在设备之间共享的非 JDBC 数据源配置。
 *
 * @param id                     连接的稳定标识
 * @param name                   数据源显示名称
 * @param type                   连接协议
 * @param endpoint               节点地址或 URI，不含密钥
 * @param username               Redis、MongoDB 或 Elasticsearch Basic 认证用户；留空时 Elasticsearch 使用 API Key
 * @param authenticationDatabase MongoDB 用户所属数据库
 * @param cluster                Redis Cluster 模式
 * @param tls                    Redis TLS 开关，MongoDB 和 Elasticsearch 由连接地址决定
 * @param logicalDatabase        Redis 逻辑库编号
 * @author liaojinlong
 * @since 2026/11/18 16:50
 */
public record NoSqlConnectionProfile(UUID id, String name, NoSqlSourceType type, String endpoint, String username,
									 String authenticationDatabase, boolean cluster, boolean tls, int logicalDatabase) {
	/**
	 * 当前文件交换契约，导入只接受该版本。
	 */
	public static final String PORTABLE_FORMAT = "mybatis-workbench-nosql/1";

	/**
	 * 允许同步的 MongoDB URI 选项，排除可承载令牌或本地私钥路径的字段。
	 */
	private static final Set<String> SHARED_MONGO_OPTIONS = Set.of("replicaset", "tls", "ssl", "directconnection",
		"serverselectiontimeoutms", "connecttimeoutms", "sockettimeoutms", "readpreference", "retryreads",
		"retrywrites", "authsource", "appname");

	/**
	 * 校验将进入同步设置的连接参数，认证凭据由 IDE 独立保管。
	 *
	 * @throws IllegalArgumentException 地址格式无效或含常见的内嵌凭据
	 */
	public void validateShareableProfile() {
		validateShareableProfile(key -> ResourceBundle.getBundle("messages.WorkbenchBundle", Locale.getDefault()).getString(key));
	}

	/**
	 * 检查可同步的连接字段，由调用方按宿主界面语言解析提示。
	 *
	 * @param validationMessage 工作台资源键到界面提示的映射
	 * @throws IllegalArgumentException 地址或字段格式无效，或数据中包含内嵌凭据
	 */
	public void validateShareableProfile(Function<String, String> validationMessage) {
		if (name.isBlank() || endpoint.isBlank()) {
			throw new IllegalArgumentException(validationMessage.apply("nosql.profile.required.fields"));
		}
		switch (type) {
			case REDIS -> {
				try {
					WorkbenchRedisConnection.parseRedisSeeds(
						Arrays.stream(endpoint.split(",", -1)).map(String::strip).toList(), cluster, logicalDatabase);
				} catch (IllegalArgumentException failure) {
					throw new IllegalArgumentException(validationMessage.apply("nosql.profile.redis.address"), failure);
				}
			}
			case MONGODB -> {
				String lower = endpoint.toLowerCase(Locale.ROOT);
				if ((!lower.startsWith("mongodb://") && !lower.startsWith("mongodb+srv://"))
					|| lower.matches(".*://[^/?#]*@.*")) {
					throw new IllegalArgumentException(validationMessage.apply("nosql.profile.mongodb.uri"));
				}
				int start = endpoint.indexOf('?');
				if (start >= 0) {
					for (String option : endpoint.substring(start + 1).split("[&;]")) {
						String name;
						try {
							name = URLDecoder.decode(option.split("=", 2)[0], StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
						} catch (IllegalArgumentException failure) {
							throw new IllegalArgumentException(validationMessage.apply("nosql.profile.mongodb.invalid.option"), failure);
						}
						if (!SHARED_MONGO_OPTIONS.contains(name)) {
							throw new IllegalArgumentException(validationMessage.apply("nosql.profile.mongodb.unsupported.option") + name);
						}
					}
				}
			}
			case ELASTICSEARCH -> {
				URI address;
				try {
					address = new URI(endpoint);
				} catch (URISyntaxException failure) {
					throw new IllegalArgumentException(validationMessage.apply("nosql.profile.elasticsearch.url"), failure);
				}
				if (!"https".equalsIgnoreCase(address.getScheme()) || Objects.isNull(address.getHost())
					|| Objects.nonNull(address.getRawUserInfo()) || Objects.nonNull(address.getRawQuery())
					|| Objects.nonNull(address.getRawFragment())) {
					throw new IllegalArgumentException(validationMessage.apply("nosql.profile.elasticsearch.url"));
				}
				if (username.indexOf(':') >= 0 || username.indexOf('\r') >= 0 || username.indexOf('\n') >= 0) {
					throw new IllegalArgumentException(validationMessage.apply("nosql.profile.elasticsearch.username"));
				}
			}
		}
	}

	/**
	 * 将当前配置写成版本化 UTF-8 properties 文本。密码、API Key 和本机文件路径没有对应字段。
	 *
	 * @param writer 调用方提供的输出器
	 * @throws IOException 输出失败
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
		Properties fields = new Properties();
		fields.setProperty("format", PORTABLE_FORMAT);
		fields.setProperty("id", id.toString());
		fields.setProperty("name", name);
		fields.setProperty("type", type.name());
		fields.setProperty("endpoint", endpoint);
		fields.setProperty("username", username);
		fields.setProperty("authenticationDatabase", authenticationDatabase);
		fields.setProperty("cluster", Boolean.toString(cluster));
		fields.setProperty("tls", Boolean.toString(tls));
		fields.setProperty("logicalDatabase", Integer.toString(logicalDatabase));
		ConnectionProfileProperties.writeProfileProperties(fields, writer);
	}

	/**
	 * 解析已经从文件读取的字段，检查格式版本、协议和不应共享的输入。
	 *
	 * @param reader 配置文本输入器
	 * @return 已校验的可共享配置
	 * @throws IOException 格式错误、版本未知或输入读取失败
	 */
	public static NoSqlConnectionProfile readProfile(Reader reader) throws IOException {
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
	public static NoSqlConnectionProfile readProfile(Reader reader, Function<String, String> validationMessage) throws IOException {
		try {
			Properties fields = ConnectionProfileProperties.readProfileProperties(reader);
			if (!PORTABLE_FORMAT.equals(fields.getProperty("format"))) {
				throw new IOException(validationMessage.apply("nosql.profile.unsupported.format"));
			}
			var profile = new NoSqlConnectionProfile(ConnectionProfileProperties.readProfileId(fields.getProperty("id", "")),
				fields.getProperty("name", ""), NoSqlSourceType.valueOf(fields.getProperty("type", "")),
				fields.getProperty("endpoint", ""), fields.getProperty("username", ""),
				fields.getProperty("authenticationDatabase", ""), parseProfileBoolean(fields, "cluster"),
				parseProfileBoolean(fields, "tls"), Integer.parseInt(fields.getProperty("logicalDatabase", "0")));
			profile.validateShareableProfile(validationMessage);
			return profile;
		} catch (IllegalArgumentException failure) {
			throw new IOException(validationMessage.apply("nosql.profile.invalid") + failure.getMessage(), failure);
		}
	}

	/**
	 * 拒绝拼写不正确的布尔值，避免导入时静默改变 TLS 或集群模式。
	 *
	 * @param fields 文件配置字段
	 * @param key    布尔配置字段名
	 * @return 配置中的值
	 */
	private static boolean parseProfileBoolean(Properties fields, String key) {
		return switch (fields.getProperty(key, "false")) {
			case "true" -> true;
			case "false" -> false;
			default -> throw new IllegalArgumentException("Invalid boolean field: " + key);
		};
	}

}
