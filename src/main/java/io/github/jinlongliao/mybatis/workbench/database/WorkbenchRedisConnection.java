package io.github.jinlongliao.mybatis.workbench.database;

import static io.github.jinlongliao.mybatis.workbench.database.DatabaseMessages.databaseMessage;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;


import javax.net.ssl.SSLParameters;

import redis.clients.jedis.*;
import redis.clients.jedis.commands.ProtocolCommand;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.providers.ClusterConnectionProvider;
import redis.clients.jedis.resps.ScanResult;

/**
 * 提供 Redis 单机和 Cluster 的连接、按主节点浏览及键路由。
 *
 * @author liaojinlong
 * @see <a href="https://github.com/redis/jedis">Jedis 客户端</a>
 * @see <a href="https://redis.io/docs/latest/commands/scan/">Redis SCAN 命令</a>
 * @since 2026/11/18 15:33
 */
public final class WorkbenchRedisConnection implements AutoCloseable {
	/** 当前连接用于格式化错误消息的宿主语言。 */
	private final Locale locale;

	/**
	 * 用于执行命令的会话，Cluster 模式由 Jedis 管理哈希槽和迁移重定向。
	 */
	private final UnifiedJedis unifiedJedis;

	/**
	 * Cluster 客户端共享的槽位拓扑，单机模式为空。
	 */
	private final ClusterConnectionProvider clusterConnectionProvider;

	/**
	 * 单机时显示的节点名，集群节点由当前拓扑获取。
	 */
	private final String standaloneNode;

	/**
	 * 打开 Redis 连接。调用方不跨线程共享本对象，使用后关闭。
	 *
	 * @param seedAddresses 单机填写一个 host:port，集群可提供多个种子地址
	 * @param cluster       是否使用 Redis Cluster
	 * @param tls           是否启用 TLS 并校验服务端证书及主机名
	 * @param database      单机库编号，集群只支持 0
	 * @param username      ACL 用户名，空字符串表示默认用户
	 * @param password      Redis 密码，空字符串表示不认证
	 * @throws IllegalArgumentException 节点数量或库编号无效
	 */
	public WorkbenchRedisConnection(List<String> seedAddresses, boolean cluster, boolean tls, int database,
									String username, String password) {
		this(seedAddresses, cluster, tls, database, username, password, Locale.getDefault());
	}

	/**
	 * 打开 Redis 连接。调用方不跨线程共享本对象，使用后关闭。
	 *
	 * @param seedAddresses 单机填写一个 host:port，集群可提供多个种子地址
	 * @param cluster       是否使用 Redis Cluster
	 * @param tls           是否启用 TLS 并校验服务端证书及主机名
	 * @param database      单机库编号，集群只支持 0
	 * @param username      ACL 用户名，空字符串表示默认用户
	 * @param password      Redis 密码，空字符串表示不认证
	 * @param locale 宿主界面语言，用于此连接生成的错误消息
	 * @throws IllegalArgumentException 节点数量或库编号无效
	 */
	public WorkbenchRedisConnection(List<String> seedAddresses, boolean cluster, boolean tls, int database,
									String username, String password, Locale locale) {
		this.locale = locale;
		List<HostAndPort> seeds = parseRedisSeeds(seedAddresses, cluster, database, locale);
		var config = DefaultJedisClientConfig.builder().connectionTimeoutMillis(5_000).socketTimeoutMillis(5_000)
			.database(database);
		if (tls) {
			// TLS 同时校验证书链和连接主机名，集群节点连接复用同一配置。
			SSLParameters sslParameters = new SSLParameters();
			sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
			config.sslOptions(SslOptions.builder().sslVerifyMode(SslVerifyMode.FULL)
				.sslParameters(sslParameters).build());
		}
		if (!username.isEmpty()) {
			config.user(username);
		}
		if (!password.isEmpty()) {
			config.password(password);
		}
		if (cluster) {
			var clientConfig = config.build();
			var nodes = Set.copyOf(seeds);
			clusterConnectionProvider = new ClusterConnectionProvider(nodes, clientConfig);
			int maxAttempts = RedisClusterClient.DEFAULT_MAX_ATTEMPTS;
			// 命令路由与节点扫描使用同一个拓扑，关闭客户端时一并释放连接池。
			unifiedJedis = RedisClusterClient.builder().nodes(nodes).clientConfig(clientConfig)
				.connectionProvider(clusterConnectionProvider).maxAttempts(maxAttempts)
				.maxTotalRetriesDuration(Duration.ofMillis((long) clientConfig.getSocketTimeoutMillis() * maxAttempts))
				.build();
			standaloneNode = null;
		} else {
			clusterConnectionProvider = null;
			HostAndPort seed = seeds.getFirst();
			standaloneNode = seed.toString();
			unifiedJedis = RedisClient.builder().hostAndPort(seed).clientConfig(config.build()).build();
		}
	}


	/**
	 * 校验节点数量、端口和逻辑库，并使用 Jedis 解析地址，不建立连接。
	 *
	 * @param seedAddresses 已移除两端空白的节点地址
	 * @param cluster       是否为 Redis Cluster
	 * @param database      请求的 Redis 逻辑库编号
	 * @return 解析后的节点
	 * @throws IllegalArgumentException 地址、节点数量或逻辑库不符合连接规则
	 */
	static List<HostAndPort> parseRedisSeeds(List<String> seedAddresses, boolean cluster, int database) {
		return parseRedisSeeds(seedAddresses, cluster, database, Locale.getDefault());
	}

	/**
	 * 按指定宿主语言校验并解析 Redis 节点。
	 *
	 * @param seedAddresses 已移除两端空白的节点地址
	 * @param cluster 是否为 Redis Cluster
	 * @param database 请求的 Redis 逻辑库编号
	 * @param locale 校验错误使用的语言
	 * @return 解析后的节点
	 * @throws IllegalArgumentException 节点数量、地址或逻辑库无效
	 */
	private static List<HostAndPort> parseRedisSeeds(List<String> seedAddresses, boolean cluster, int database, Locale locale) {
		if (seedAddresses.isEmpty() || (!cluster && seedAddresses.size() != 1) || database < 0
			|| (cluster && database != 0)) {
			throw new IllegalArgumentException(databaseMessage(locale, "redis.invalid.nodes"));
		}
		return seedAddresses.stream().map(address -> {
			if (address.lastIndexOf(':') < 1 || !address.matches("[a-zA-Z0-9_.:%\\[\\]-]+:[0-9]+")) {
				throw new IllegalArgumentException("Invalid Redis seed address");
			}
			HostAndPort seed = HostAndPort.from(address);
			if (seed.getPort() < 1 || seed.getPort() > 65_535) {
				throw new IllegalArgumentException("Invalid Redis seed port");
			}
			return seed;
		}).toList();
	}

	/**
	 * 测试当前连接。
	 *
	 * @return Redis 服务器对 PING 的回复
	 */
	public String pingRedis() {
		return unifiedJedis.ping();
	}

	/**
	 * 列出用于 SCAN 的当前主节点。每节点使用自己的游标，跳过副本。
	 *
	 * @return 节点地址，按名称排序
	 */
	public List<String> listPrimaryNodes() {
		if (Objects.isNull(clusterConnectionProvider)) {
			return List.of(standaloneNode);
		}
		// 槽位发现已经区分主从，避免再次连接不参与扫描的副本。
		return clusterConnectionProvider.getPrimaryNodes().keySet().stream().sorted().toList();
	}

	/**
	 * 读取一个节点的一次增量扫描结果，空批次也可能有下一页。
	 *
	 * @param nodeName 由 {@link #listPrimaryNodes()} 返回的节点地址
	 * @param cursor   初次使用 0，否则使用该节点上次返回的游标
	 * @param pattern  Redis glob 模式
	 * @param hint     SCAN COUNT 提示值，范围为 1 至 1000，实际批量由 Redis 决定
	 * @return 保存下一游标和本次结果的页面
	 */
	public RedisKeyPage scanRedisKeys(String nodeName, String cursor, String pattern, int hint) {
		if (hint < 1 || hint > 1_000) {
			throw new IllegalArgumentException(databaseMessage(locale, "redis.scan.count"));
		}
		ScanParams params = new ScanParams().count(hint).match(pattern);
		if (Objects.isNull(clusterConnectionProvider)) {
			return toRedisKeyPage(unifiedJedis.scan(cursor, params));
		}
		ConnectionPool pool = clusterConnectionProvider.getPrimaryNodes().get(nodeName);
		if (Objects.isNull(pool)) {
			throw new IllegalArgumentException(databaseMessage(locale, "redis.node.changed"));
		}
		try (Jedis node = new Jedis(pool.getResource())) {
			return toRedisKeyPage(node.scan(cursor, params));
		}
	}

	/**
	 * 读取原始键的类型、TTL 和字符串、列表或有序集合预览。所有调用均为只读，
	 * 通过 Jedis 的键指令路由，键名不会经过命令文本的分行处理。
	 *
	 * @param key 从 SCAN 返回的键名，允许空串或换行
	 * @return 当前键的类型、TTL 与对应类型的预览
	 */
	public RedisKeyDetails readRedisKeyDetails(String key) {
		String type = unifiedJedis.type(key);
		long ttlSeconds = unifiedJedis.ttl(key);
		String preview = "string".equals(type) ? unifiedJedis.getrange(key, 0, 4095) : "";
		List<String> listPreview = "list".equals(type) ? List.copyOf(unifiedJedis.lrange(key, 0, 19)) : List.of();
		List<RedisScoredMember> sortedSetPreview = "zset".equals(type)
			? unifiedJedis.zrangeWithScores(key, 0, 19).stream()
				.map(member -> new RedisScoredMember(member.getElement(), member.getScore())).toList()
			: List.of();
		return new RedisKeyDetails(type, ttlSeconds, preview, listPreview, sortedSetPreview);
	}

	/**
	 * 单个 Redis 键的只读信息。各字段来自独立请求，键可能在读取期间过期。
	 *
	 * @param type          Redis TYPE 返回值，none 表示读取时键不存在
	 * @param ttlSeconds    剩余秒数，-1 表示无过期时间，-2 表示键不存在
	 * @param stringPreview 字符串值的前 4096 字节，其他类型为空
	 * @param listPreview 列表的前 20 项，其他类型为空
	 * @param sortedSetPreview 有序集合按分数升序的前 20 项，其他类型为空
	 * @author liaojinlong
	 * @since 2026/11/18 18:27
	 */
	public record RedisKeyDetails(String type, long ttlSeconds, String stringPreview, List<String> listPreview,
								  List<RedisScoredMember> sortedSetPreview) {
		/**
		 * 构造字符串或列表的键详情。
		 *
		 * @param type Redis TYPE 返回值
		 * @param ttlSeconds 键剩余存活秒数
		 * @param stringPreview 字符串值预览
		 * @param listPreview 列表值预览
		 */
		public RedisKeyDetails(String type, long ttlSeconds, String stringPreview, List<String> listPreview) {
			this(type, ttlSeconds, stringPreview, listPreview, List.of());
		}

		/**
		 * 保留只构造字符串预览的调用入口。
		 *
		 * @param type Redis TYPE 返回值
		 * @param ttlSeconds 键剩余存活秒数
		 * @param stringPreview 字符串值预览
		 */
		public RedisKeyDetails(String type, long ttlSeconds, String stringPreview) {
			this(type, ttlSeconds, stringPreview, List.of());
		}
	}

	/**
	 * 执行一条原生 Redis 命令。在集群为带键命令显式指定路由键，参数仍完整传给 Redis。
	 *
	 * @param routingKey Cluster 命令的路由键，节点级或单机命令可留空
	 * @param command    Redis 命令名，子命令通过参数传递
	 * @param arguments  不经 shell 解析的各个 Redis 参数
	 * @return Redis 协议回复，空值、数值、字节数组或嵌套列表等
	 */
	public Object executeRedisCommand(String routingKey, String command, List<String> arguments) {
		String name = command.toUpperCase(Locale.ROOT);
		if (!name.matches("[A-Z][A-Z0-9_.-]*")) {
			throw new IllegalArgumentException(databaseMessage(locale, "redis.invalid.command"));
		}
		ProtocolCommand protocolCommand = () -> name.getBytes(StandardCharsets.US_ASCII);
		CommandArguments commandArguments = Objects.isNull(clusterConnectionProvider)
			? new CommandArguments(protocolCommand)
			: new RoutedRedisCommandArguments(protocolCommand, routingKey);
		return unifiedJedis.executeCommand(commandArguments.addObjects(arguments));
	}

	/**
	 * 将可选路由键交给 Jedis 计算集群槽位，同时保持发给服务端的参数原样。
	 *
	 * @author liaojinlong
	 * @since 2026/11/19 09:31
	 */
	private static final class RoutedRedisCommandArguments extends ClusterCommandArguments {
		/**
		 * @param protocolCommand 要执行的 Redis 命令
		 * @param routingKey 用于计算槽位的键，留空时由 Jedis 选择节点
		 */
		private RoutedRedisCommandArguments(ProtocolCommand protocolCommand, String routingKey) {
			super(protocolCommand);
			if (!routingKey.isEmpty()) {
				processKey(routingKey);
			}
		}
	}

	/**
	 * 复制 Redis 返回的结果，关闭节点连接后仍可使用。
	 *
	 * @param result Jedis 扫描结果
	 * @return 扫描游标及只读键列表
	 */
	private static RedisKeyPage toRedisKeyPage(ScanResult<String> result) {
		return new RedisKeyPage(result.getCursor(), List.copyOf(result.getResult()));
	}

	/**
	 * {@inheritDoc} 关闭直接连接或集群维护的全部节点连接池。
	 */
	@Override
	public void close() {
		unifiedJedis.close();
	}
}
