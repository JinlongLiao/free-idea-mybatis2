package io.github.jinlongliao.mybatis.workbench.database;

import static io.github.jinlongliao.mybatis.workbench.database.DatabaseMessages.databaseMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

import javax.net.ssl.SSLParameters;

import redis.clients.jedis.*;
import redis.clients.jedis.commands.ProtocolCommand;
import redis.clients.jedis.exceptions.JedisDataException;
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
	/** 记录服务端拒绝读取逻辑库数量的原因。 */
	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	/** 当前连接用于格式化错误消息的宿主语言。 */
	private final Locale locale;
	/** 绑定复合键快照的 Redis 节点集合、模式及逻辑库，禁止跨连接或跨库误删同名键。 */
	private final String sourceIdentity;
	/** 值编辑器最多读取的字符串字节数，避免将大型 Redis 值完整载入界面。 */
	private static final long MAX_REDIS_EDIT_BYTES = 64 * 1024;
	/** 整键删除最多快照的复合成员数，避免大量命令参数和比较工作。 */
	private static final int MAX_REDIS_DELETE_SNAPSHOT_ITEMS = 256;
	/** 整键删除快照最多传输的 UTF-8 内容字节数。 */
	private static final int MAX_REDIS_DELETE_SNAPSHOT_BYTES = 64 * 1024;
	/** Stream 删除快照额外限制字段值对数量，防止空值字段形成过多对象和参数。 */
	private static final int MAX_REDIS_DELETE_SNAPSHOT_STREAM_FIELDS = 512;
	/** Stream 单条消息快照参与脚本参数比较时的最大 UTF-8 字节数。 */
	private static final int MAX_REDIS_STREAM_MUTATION_BYTES = 64 * 1024;
	/** 单页 Stream RESP2 响应的字节预算，包含外层数组和前瞻消息。 */
	private static final int MAX_REDIS_STREAM_PAGE_BYTES = 64 * 1024;
	/** 详情页每次最多返回的复合类型成员数。 */
	private static final int REDIS_MEMBER_PAGE_SIZE = 20;
	/** 单条 Stream 预览最多保留的字段数，避免畸形消息形成大量 Java 对象。 */
	private static final int MAX_REDIS_STREAM_PREVIEW_FIELDS = 128;
	/** 原子取得 List 长度与页内容；长度变化时从首页重新开始。 */
	private static final String READ_REDIS_LIST_PAGE_SCRIPT = """
		local length = redis.call('LLEN', KEYS[1])
	local expectedLength = tonumber(ARGV[1])
	local offset = tonumber(ARGV[2])
	if expectedLength >= 0 and expectedLength ~= length then offset = 0 end
	return {length, offset, redis.call('LRANGE', KEYS[1], offset, offset + tonumber(ARGV[3]) - 1)}
	""";
	/** List 续页游标已明确类型时，将类型、TTL、长度校验和 LRANGE 合并为单次按键路由请求。 */
	private static final String READ_REDIS_LIST_CONTINUATION_SCRIPT = """
		local keyType = redis.call('TYPE', KEYS[1]).ok
		local ttl = redis.call('TTL', KEYS[1])
		if keyType ~= 'list' then return {keyType, ttl, 0} end
		local length = redis.call('LLEN', KEYS[1])
		local expectedLength = tonumber(ARGV[1])
		local offset = tonumber(ARGV[2])
		if expectedLength >= 0 and expectedLength ~= length then offset = 0 end
		return {'list', ttl, 1, length, offset,
			redis.call('LRANGE', KEYS[1], offset, offset + tonumber(ARGV[3]) - 1)}
		""";
	/** 在单个原子 Redis 脚本中读取有界字符串快照，超限时只返回短预览。 */
	private static final String READ_REDIS_STRING_SNAPSHOT_SCRIPT = """
		local keyType = redis.call('TYPE', KEYS[1]).ok
		if keyType ~= 'string' then return {keyType, 'not-string', ''} end
		local size = redis.call('STRLEN', KEYS[1])
		if size <= tonumber(ARGV[1]) then return {'string', 'editable', redis.call('GET', KEYS[1])} end
		return {'string', 'too-large', redis.call('GETRANGE', KEYS[1], 0, 4095)}
		""";
	/** 比较原字符串并写回新值的 Lua 脚本；单次脚本运行避免并发覆盖并保留键剩余 TTL。 */
	private static final String REPLACE_REDIS_STRING_SCRIPT = """
		local keyType = redis.call('TYPE', KEYS[1]).ok
		if keyType ~= 'string' then return 0 end
		local current = redis.call('GET', KEYS[1])
		if not current or current ~= ARGV[1] then return 0 end
		local ttl = redis.call('PTTL', KEYS[1])
		if ttl == -2 or ttl == 0 then return 0 end
		redis.call('SET', KEYS[1], ARGV[2])
		if ttl >= 0 then redis.call('PEXPIRE', KEYS[1], ttl) end
		return 1
		""";
	/** 仅当当前 key 类型仍与确认时一致时更新其过期时间。 */
	private static final String UPDATE_REDIS_KEY_TTL_SCRIPT = """
		if redis.call('TYPE', KEYS[1]).ok ~= 'string' then return 0 end
		if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
		return redis.call('EXPIRE', KEYS[1], ARGV[2])
		""";
	/** 仅当当前 key 类型仍与确认时一致时清除过期时间。 */
	private static final String CLEAR_REDIS_KEY_TTL_SCRIPT = """
		if redis.call('TYPE', KEYS[1]).ok ~= 'string' then return 0 end
		if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
		return redis.call('PERSIST', KEYS[1])
		""";
	/** 仅当当前 key 类型仍与确认时一致时删除该键。 */
	private static final String DELETE_REDIS_KEY_SCRIPT = """
		if redis.call('TYPE', KEYS[1]).ok ~= 'string' then return 0 end
		if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
		return redis.call('DEL', KEYS[1])
		""";
	/** 比较 Hash 字段原值后原子替换，字段不存在或键类型变化时拒绝。 */
	private static final String UPDATE_REDIS_HASH_FIELD_SCRIPT = """
		if redis.call('TYPE', KEYS[1]).ok ~= 'hash' then return 0 end
		local current = redis.call('HGET', KEYS[1], ARGV[1])
		if not current or current ~= ARGV[2] then return 0 end
		redis.call('HSET', KEYS[1], ARGV[1], ARGV[3])
		return 1
		""";
	/** 仅在 Hash 字段仍等于预览值时删除该字段。 */
	private static final String DELETE_REDIS_HASH_FIELD_SCRIPT = """
		if redis.call('TYPE', KEYS[1]).ok ~= 'hash' then return 0 end
		local current = redis.call('HGET', KEYS[1], ARGV[1])
		if not current or current ~= ARGV[2] then return 0 end
		return redis.call('HDEL', KEYS[1], ARGV[1])
		""";
	/** 在 Set 类型内原子改名成员；新名称已存在时拒绝合并。 */
	private static final String UPDATE_REDIS_SET_MEMBER_SCRIPT = """
		if redis.call('TYPE', KEYS[1]).ok ~= 'set' then return 0 end
		if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 0 then return 0 end
		if ARGV[1] ~= ARGV[2] and redis.call('SISMEMBER', KEYS[1], ARGV[2]) == 1 then return 0 end
		if ARGV[1] == ARGV[2] then return 1 end
		redis.call('SADD', KEYS[1], ARGV[2])
		redis.call('SREM', KEYS[1], ARGV[1])
		return 1
		""";
	/** 比较 ZSet 成员原分数后更新，避免覆盖并发改分。 */
	private static final String UPDATE_REDIS_ZSET_SCORE_SCRIPT = """
		if redis.call('TYPE', KEYS[1]).ok ~= 'zset' then return 0 end
		local current = redis.call('ZSCORE', KEYS[1], ARGV[1])
		if not current or tonumber(current) ~= tonumber(ARGV[2]) then return 0 end
		redis.call('ZADD', KEYS[1], 'XX', ARGV[3], ARGV[1])
		return 1
		""";
	/** 比较 ZSet 成员原分数后删除该成员。 */
	private static final String DELETE_REDIS_ZSET_MEMBER_SCRIPT = """
		if redis.call('TYPE', KEYS[1]).ok ~= 'zset' then return 0 end
		local current = redis.call('ZSCORE', KEYS[1], ARGV[1])
		if not current or tonumber(current) ~= tonumber(ARGV[2]) then return 0 end
		return redis.call('ZREM', KEYS[1], ARGV[1])
		""";
	/** 按预览中的绝对位置和原值检查后更新 List 元素。 */
	private static final String UPDATE_REDIS_LIST_VALUE_SCRIPT = """
		if redis.call('TYPE', KEYS[1]).ok ~= 'list' then return 0 end
		if redis.call('LLEN', KEYS[1]) ~= tonumber(ARGV[1]) then return 0 end
		local current = redis.call('LINDEX', KEYS[1], ARGV[2])
		if not current or current ~= ARGV[3] then return 0 end
		redis.call('LSET', KEYS[1], ARGV[2], ARGV[4])
		return 1
		""";
	/** 仅按预览时的列表长度和值删除首项或末项，避免对重复值误删及不安全的整表重建。 */
	private static final String DELETE_REDIS_LIST_VALUE_SCRIPT = """
		if redis.call('TYPE', KEYS[1]).ok ~= 'list' then return 0 end
		local length = redis.call('LLEN', KEYS[1])
		if length ~= tonumber(ARGV[1]) then return 0 end
		local index = tonumber(ARGV[2])
		if index ~= 0 and index ~= length - 1 then return -2 end
		local current = redis.call('LINDEX', KEYS[1], index)
		if not current or current ~= ARGV[3] then return 0 end
		if index == 0 then redis.call('LPOP', KEYS[1]) else redis.call('RPOP', KEYS[1]) end
		return 1
		""";
	/** 比较 Stream 消息完整字段快照后按稳定消息 ID 删除。 */
	private static final String DELETE_REDIS_STREAM_MESSAGE_SCRIPT = """
		if redis.call('TYPE', KEYS[1]).ok ~= 'stream' then return 0 end
		local rows = redis.call('XRANGE', KEYS[1], ARGV[1], ARGV[1])
		if #rows ~= 1 then return 0 end
		local current = rows[1][2]
		if #current ~= tonumber(ARGV[2]) then return 0 end
		for index = 1, #current do
			if current[index] ~= ARGV[index + 2] then return 0 end
		end
		return redis.call('XDEL', KEYS[1], ARGV[1])
		""";
	/** 对已选键执行成员级操作前核对类型，避免键在预览后过期时由写命令意外重建。 */
	private static final String ADD_REDIS_MEMBER_SCRIPT = """
		if redis.call('TYPE', KEYS[1]).ok ~= ARGV[1] then return 0 end
		local operation = ARGV[2]
		if operation == 'HSETNX' then return redis.call('HSETNX', KEYS[1], ARGV[3], ARGV[4]) end
		if operation == 'SADD' then return redis.call('SADD', KEYS[1], ARGV[3]) end
		if operation == 'ZADD' then return redis.call('ZADD', KEYS[1], 'NX', ARGV[4], ARGV[3]) end
		if operation == 'LPUSH' then return redis.call('LPUSH', KEYS[1], ARGV[3]) end
		if operation == 'RPUSH' then return redis.call('RPUSH', KEYS[1], ARGV[3]) end
		if operation == 'XADD' then
			return redis.call('XADD', KEYS[1], '*', ARGV[3], ARGV[4])
		end
		return redis.error_reply('Unsupported Redis member operation')
		""";
	/** 新建非 String 结构时先在 Lua 内检查键不存在，再创建首个字段/成员，避免覆盖或并发合并到同名结构。 */
	private static final String CREATE_REDIS_AGGREGATE_KEY_SCRIPT = """
		if redis.call('EXISTS', KEYS[1]) ~= 0 then return 0 end
		local valueType = ARGV[1]
		if valueType == 'hash' then redis.call('HSET', KEYS[1], ARGV[2], ARGV[3])
		elseif valueType == 'set' then redis.call('SADD', KEYS[1], ARGV[2])
		elseif valueType == 'zset' then redis.call('ZADD', KEYS[1], ARGV[3], ARGV[2])
		elseif valueType == 'list-left' then redis.call('LPUSH', KEYS[1], ARGV[2])
		elseif valueType == 'list-right' then redis.call('RPUSH', KEYS[1], ARGV[2])
		elseif valueType == 'stream' then redis.call('XADD', KEYS[1], '*', ARGV[2], ARGV[3])
		else return redis.error_reply('Unsupported Redis aggregate type') end
		return 1
		""";

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
		this(seedAddresses, cluster, tls, database, username, password, true, locale);
	}

	/**
	 * 打开 Redis 连接；TLS 始终保持加密，可按本机配置选择是否校验服务端证书。
	 *
	 * @param seedAddresses Redis 种子节点地址
	 * @param cluster 是否以集群模式连接
	 * @param tls 是否启用 TLS 加密
	 * @param database 单机逻辑库编号，集群必须为 0
	 * @param username ACL 用户名，可为空
	 * @param password Redis 密码，可为空
	 * @param verifyCertificate 是否验证证书链及主机名
	 * @param locale 宿主界面语言
	 * @since 2026/11/21 23:07
	 */
	public WorkbenchRedisConnection(List<String> seedAddresses, boolean cluster, boolean tls, int database,
									String username, String password, boolean verifyCertificate, Locale locale) {
		this.locale = locale;
		List<HostAndPort> seeds = parseRedisSeeds(seedAddresses, cluster, database, locale);
		this.sourceIdentity = (cluster ? "cluster" : "standalone") + "|"
			+ String.join(",", seeds.stream().map(HostAndPort::toString).sorted().toList()) + "|" + database;
		var config = DefaultJedisClientConfig.builder().connectionTimeoutMillis(5_000).socketTimeoutMillis(5_000)
			.database(database);
		if (tls) {
			// TLS 加密始终开启；INSECURE 只跳过证书身份校验，不关闭 TLS。
			SSLParameters sslParameters = new SSLParameters();
			sslParameters.setEndpointIdentificationAlgorithm(verifyCertificate ? "HTTPS" : null);
			config.sslOptions(SslOptions.builder().sslVerifyMode(verifyCertificate ? SslVerifyMode.FULL : SslVerifyMode.INSECURE)
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
	 * 读取单机服务器配置的逻辑库范围，账号无 CONFIG 权限或服务商禁用命令时保留已配置的库。
	 *
	 * @param configuredDatabase 连接配置指定的默认逻辑库
	 * @return 从零开始的逻辑库列表，集群仅有 0 号库
	 */
	public List<Integer> listRedisLogicalDatabases(int configuredDatabase) {
		if (Objects.nonNull(clusterConnectionProvider)) {
			return List.of(0);
		}
		try {
			Object response = executeRedisCommand("", "CONFIG", List.of("GET", "databases"));
			if (response instanceof List<?> values && values.size() == 2
				&& values.get(1) instanceof byte[] count) {
				int total = Integer.parseInt(new String(count, StandardCharsets.US_ASCII));
				if (total > 0) {
					return IntStream.range(0, total).boxed().toList();
				}
			}
			return List.of(configuredDatabase);
		} catch (JedisDataException failure) {
			String reason = Objects.toString(failure.getMessage(), "").toLowerCase(Locale.ROOT);
			if (!reason.startsWith("noperm") && !reason.contains("unknown command")) {
				throw failure;
			}
			log.warn("读取 Redis 逻辑库数量失败，阶段=CONFIG GET databases，节点={}，默认库={}，回退到默认库",
				standaloneNode, configuredDatabase, failure);
			return List.of(configuredDatabase);
		}
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
	 * 读取原始键的类型、TTL 与对应类型的值预览。Hash 和 Set 单次扫描并最多展示 20 项。所有调用均为只读，
	 * 通过 Jedis 的键指令路由，键名不会经过命令文本的分行处理。
	 *
	 * @param key 从 SCAN 返回的键名，允许空串或换行
	 * @return 当前键的类型、TTL 与对应类型的预览
	 */
	public RedisKeyDetails readRedisKeyDetails(String key) {
		return readRedisKeyDetails(key, "");
	}

	/**
	 * 按 Redis 原生类型游标读取一页键详情；List/ZSet 使用有界 offset，Stream 使用独占消息 ID。
	 * List 续页游标可安全地将类型、TTL 和成员读取合并为单次请求；若键已换类型，使用该次响应继续普通详情读取。
	 *
	 * @param key 从 SCAN 返回的键名
	 * @param pageCursor 当前类型的内部续页游标；List/ZSet 含类型前缀和 offset，Stream 含类型前缀和消息 ID
	 * @return 当前页的类型、TTL 与有界成员预览，并携带下一页游标
	 * @see <a href="https://redis.io/docs/latest/commands/lrange/">Redis LRANGE</a>
	 * @see <a href="https://redis.io/docs/latest/commands/zrange/">Redis ZRANGE</a>
	 * @see <a href="https://redis.io/docs/latest/commands/xrange/">Redis XRANGE</a>
	 */
	public RedisKeyDetails readRedisKeyDetails(String key, String pageCursor) {
		String type;
		long ttlSeconds;
		boolean listPageLoaded = false;
		long listLength = 0;
		List<String> listPreview = List.of();
		long pageOffset = 0;
		if (Objects.nonNull(pageCursor) && pageCursor.startsWith("list:")) {
			List<Long> listCursor = parseRedisListPageCursor(pageCursor);
			List<?> page = (List<?>) executeRedisCommand(key, "EVAL", List.of(READ_REDIS_LIST_CONTINUATION_SCRIPT,
				"1", key, Long.toString(listCursor.get(0)), Long.toString(listCursor.get(1)),
				Integer.toString(REDIS_MEMBER_PAGE_SIZE)));
			type = decodeRedisText(page.get(0));
			ttlSeconds = ((Number) page.get(1)).longValue();
			if (((Number) page.get(2)).longValue() == 1) {
				listPageLoaded = true;
				listLength = ((Number) page.get(3)).longValue();
				pageOffset = ((Number) page.get(4)).longValue();
				listPreview = ((List<?>) page.get(5)).stream()
					.map(WorkbenchRedisConnection::decodeRedisText).toList();
			}
		} else {
			type = unifiedJedis.type(key);
			ttlSeconds = unifiedJedis.ttl(key);
		}
		String stringSnapshot = null;
		String preview = "";
		String nextPageCursor = "";
		List<Map.Entry<String, String>> hashOverflow = List.of();
		List<String> setOverflow = List.of();
		String hashNextScanCursor = "0";
		String setNextScanCursor = "0";
		if ("string".equals(type)) {
			List<?> snapshot = (List<?>) executeRedisCommand(key, "EVAL", List.of(READ_REDIS_STRING_SNAPSHOT_SCRIPT,
				"1", key, Long.toString(MAX_REDIS_EDIT_BYTES)));
			type = decodeRedisText(snapshot.getFirst());
			String state = decodeRedisText(snapshot.get(1));
			String value = decodeRedisText(snapshot.get(2));
			if ("editable".equals(state)) {
				stringSnapshot = value;
				preview = value.substring(0, Math.min(value.length(), 4_096));
			} else {
				preview = value;
			}
		}
		if ("list".equals(type) && !listPageLoaded) {
			List<Long> listCursor = parseRedisListPageCursor(pageCursor);
			List<?> page = (List<?>) executeRedisCommand(key, "EVAL", List.of(READ_REDIS_LIST_PAGE_SCRIPT, "1", key,
				Long.toString(listCursor.get(0)), Long.toString(listCursor.get(1)),
				Integer.toString(REDIS_MEMBER_PAGE_SIZE)));
			listLength = ((Number) page.get(0)).longValue();
			pageOffset = ((Number) page.get(1)).longValue();
			listPreview = ((List<?>) page.get(2)).stream().map(WorkbenchRedisConnection::decodeRedisText).toList();
		}
		if ("list".equals(type) && !listPreview.isEmpty() && pageOffset + listPreview.size() < listLength) {
			nextPageCursor = "list:" + listLength + ":" + (pageOffset + listPreview.size());
		}
		if ("zset".equals(type)) {
			pageOffset = parseRedisPageOffset(pageCursor, "zset");
		}
		List<RedisScoredMember> sortedSetPreview = "zset".equals(type)
			? unifiedJedis.zrangeWithScores(key, pageOffset, pageOffset + REDIS_MEMBER_PAGE_SIZE - 1).stream()
				.map(member -> new RedisScoredMember(member.getElement(), member.getScore())).toList()
			: List.of();
		if ("zset".equals(type) && !sortedSetPreview.isEmpty()
			&& pageOffset + sortedSetPreview.size() < unifiedJedis.zcard(key)) {
			nextPageCursor = "zset:" + (pageOffset + sortedSetPreview.size());
		}
		List<Map.Entry<String, String>> hashPreview = List.of();
		if ("hash".equals(type)) {
			String scanCursor = parseRedisScanCursor(pageCursor, "hash");
			ScanResult<Map.Entry<String, String>> result = unifiedJedis.hscan(key, scanCursor,
				new ScanParams().count(REDIS_MEMBER_PAGE_SIZE));
			List<Map.Entry<String, String>> scanEntries = result.getResult().stream()
				.map(entry -> Map.entry(entry.getKey(), entry.getValue())).toList();
			int visibleCount = Math.min(scanEntries.size(), REDIS_MEMBER_PAGE_SIZE);
			hashPreview = List.copyOf(scanEntries.subList(0, visibleCount));
			hashOverflow = List.copyOf(scanEntries.subList(visibleCount, scanEntries.size()));
			hashNextScanCursor = result.getCursor();
			nextPageCursor = !hashOverflow.isEmpty() ? "hash-buffer:1"
				: !"0".equals(hashNextScanCursor) ? "hash:" + hashNextScanCursor : "";
		}
		List<String> setPreview = List.of();
		if ("set".equals(type)) {
			String scanCursor = parseRedisScanCursor(pageCursor, "set");
			ScanResult<String> result = unifiedJedis.sscan(key, scanCursor, new ScanParams().count(REDIS_MEMBER_PAGE_SIZE));
			List<String> scanMembers = List.copyOf(result.getResult());
			int visibleCount = Math.min(scanMembers.size(), REDIS_MEMBER_PAGE_SIZE);
			setPreview = List.copyOf(scanMembers.subList(0, visibleCount));
			setOverflow = List.copyOf(scanMembers.subList(visibleCount, scanMembers.size()));
			setNextScanCursor = result.getCursor();
			nextPageCursor = !setOverflow.isEmpty() ? "set-buffer:1"
				: !"0".equals(setNextScanCursor) ? "set:" + setNextScanCursor : "";
		}
		List<RedisStreamMessage> streamPreview = List.of();
		if ("stream".equals(type)) {
			Map.Entry<List<RedisStreamMessage>, String> streamPage = readRedisStreamPreview(key, pageCursor);
			streamPreview = streamPage.getKey();
			nextPageCursor = streamPage.getValue();
		}
		return new RedisKeyDetails(type, ttlSeconds, preview, listPreview, sortedSetPreview, hashPreview, setPreview,
			streamPreview, listLength, stringSnapshot, pageCursor, nextPageCursor, pageOffset, hashOverflow,
			setOverflow, hashNextScanCursor, setNextScanCursor, 0);
	}

	/** 解析 HSCAN/SSCAN 游标；缓冲页 token 仅由 UI 消耗，不可当作服务器游标发送。 */
	private String parseRedisScanCursor(String pageCursor, String expectedType) {
		String prefix = expectedType + ":";
		if (Objects.isNull(pageCursor) || !pageCursor.startsWith(prefix)) {
			return "0";
		}
		String cursorValue = pageCursor.substring(prefix.length());
		return cursorValue.matches("[0-9]+") ? cursorValue : "0";
	}

	/** 仅解析指定类型的本地页游标；换类型时从首屏读取，不把游标拼接为 Redis 命令。 */
	private long parseRedisPageOffset(String pageCursor, String expectedType) {
		String prefix = expectedType + ":";
		if (Objects.isNull(pageCursor) || !pageCursor.startsWith(prefix)) {
			return 0;
		}
		try {
			long offset = Long.parseLong(pageCursor.substring(prefix.length()));
			return offset < 0 || offset > Long.MAX_VALUE - REDIS_MEMBER_PAGE_SIZE ? 0 : offset;
		} catch (NumberFormatException ignored) {
			return 0;
		}
	}

	/** 解析 List 长度快照与零基 offset；未知格式从首页开始。 */
	private List<Long> parseRedisListPageCursor(String pageCursor) {
		if (Objects.isNull(pageCursor) || !pageCursor.startsWith("list:")) {
			return List.of(-1L, 0L);
		}
		String[] components = pageCursor.split(":", -1);
		if (components.length != 3) {
			return List.of(-1L, 0L);
		}
		try {
			long expectedLength = Long.parseLong(components[1]);
			long offset = Long.parseLong(components[2]);
			if (expectedLength < 0 || offset < 0 || offset > Long.MAX_VALUE - REDIS_MEMBER_PAGE_SIZE) {
				return List.of(-1L, 0L);
			}
			return List.of(expectedLength, offset);
		} catch (NumberFormatException ignored) {
			return List.of(-1L, 0L);
		}
	}

	/**
	 * 将 Jedis 的 Lua 字符串响应统一按 Redis UTF-8 字符串约定转换。
	 *
	 * @param value Lua 返回的 RESP 字符串
	 * @return 文本值
	 */
	private static String decodeRedisText(Object value) {
		return new String((byte[]) value, StandardCharsets.UTF_8);
	}

	/**
	 * 原子比较当前字符串后写入新值，并保留剩余过期时间。
	 *
	 * @param key 原始 Redis 键，用于 Cluster 哈希槽路由
	 * @param expectedValue 编辑器打开时读取的完整原值
	 * @param replacement 用户确认的新值
	 * @return 原值仍匹配并完成写回时为 true；键过期或值已变化时为 false
	 */
	public boolean replaceRedisStringValue(String key, String expectedValue, String replacement) {
		Object result = executeRedisCommand(key, "EVAL", List.of(REPLACE_REDIS_STRING_SCRIPT, "1", key,
			expectedValue, replacement));
		return Long.valueOf(1).equals(result);
	}

	/**
	 * 创建新字符串键并拒绝覆盖同名键。使用 SET NX [EX] 按键路由以支持 Cluster，避免对账号额外要求 EVAL 权限；值最大 UTF-8 长度为 64 KiB。
	 *
	 * @param key 非空 Redis 键名
	 * @param value 初始字符串值
	 * @param ttlSeconds 正数表示设置秒级过期时间，0 表示不过期
	 * @return 键不存在且创建成功时为 true；同名键已存在时为 false
	 * @throws IllegalArgumentException 键为空、TTL 为负数或值超过编辑上限
	 */
	public boolean createRedisStringKey(String key, String value, long ttlSeconds) {
		if (Objects.isNull(key) || Objects.isNull(value) || key.isEmpty() || ttlSeconds < 0
			|| value.getBytes(StandardCharsets.UTF_8).length > MAX_REDIS_EDIT_BYTES) {
			throw new IllegalArgumentException(databaseMessage(locale, "redis.create.string.invalid"));
		}
		List<String> arguments = ttlSeconds == 0
			? List.of(key, value, "NX")
			: List.of(key, value, "NX", "EX", Long.toString(ttlSeconds));
		Object result = executeRedisCommand(key, "SET", arguments);
		return result instanceof byte[] reply && "OK".equals(new String(reply, StandardCharsets.UTF_8));
	}

	/**
	 * 原子创建非 String 结构键并写入首个成员；不覆盖已经存在的任意类型键。
	 *
	 * @param key 非空 Redis 键名
	 * @param type Redis 结构类型：hash、set、zset、list-left、list-right 或 stream
	 * @param firstValue Hash 字段、集合成员、列表值或 Stream 字段名
	 * @param secondValue Hash/Stream 字段值或 ZSet 分数；其他类型传空字符串
	 * @return 键不存在且首个成员写入成功时为 true；键已存在时为 false
	 * @throws IllegalArgumentException 参数无效或首个成员超过 64 KiB
	 */
	public boolean createRedisAggregateKey(String key, String type, String firstValue, String secondValue) {
		List<String> supportedTypes = List.of("hash", "set", "zset", "list-left", "list-right", "stream");
		long payloadSize = Objects.isNull(firstValue) || Objects.isNull(secondValue) ? Long.MAX_VALUE
			: (long) firstValue.getBytes(StandardCharsets.UTF_8).length
			+ secondValue.getBytes(StandardCharsets.UTF_8).length;
		if (Objects.isNull(key) || key.isEmpty() || !supportedTypes.contains(type)
			|| payloadSize > MAX_REDIS_EDIT_BYTES) {
			throw new IllegalArgumentException(databaseMessage(locale, "redis.create.aggregate.invalid"));
		}
		if ("zset".equals(type)) {
			try {
				validateRedisSortedSetScore(Double.parseDouble(secondValue));
			} catch (NumberFormatException failure) {
				throw new IllegalArgumentException(databaseMessage(locale, "redis.zset.score.invalid"), failure);
			}
		}
		Object result = executeRedisCommand(key, "EVAL", List.of(CREATE_REDIS_AGGREGATE_KEY_SCRIPT, "1", key,
			type, firstValue, secondValue));
		return Long.valueOf(1).equals(result);
	}

	/**
	 * 以 HSETNX 添加 Hash 字段，字段已存在时不覆盖其值。
	 * @param key 原始键名，用于 Cluster 槽位路由
	 * @param field 新字段名
	 * @param value 新字段值
	 * @return 新字段写入成功时为 true；字段已存在时为 false
	 */
	public boolean addRedisHashField(String key, String field, String value) {
		Object result = executeRedisCommand(key, "EVAL", List.of(ADD_REDIS_MEMBER_SCRIPT, "1", key,
			"hash", "HSETNX", field, value));
		return Long.valueOf(1).equals(result);
	}

	/**
	 * 按字段原值条件更新 Hash，避免覆盖预览后由其他客户端修改的字段。
	 * @param key 原始键名，用于 Cluster 槽位路由
	 * @param field 要更新的字段名
	 * @param expectedValue 打开编辑器时的字段值
	 * @param replacement 用户提交的新字段值
	 * @return 旧值仍匹配并完成更新时为 true
	 */
	public boolean replaceRedisHashField(String key, String field, String expectedValue, String replacement) {
		Object result = executeRedisCommand(key, "EVAL", List.of(UPDATE_REDIS_HASH_FIELD_SCRIPT, "1", key,
			field, expectedValue, replacement));
		return Long.valueOf(1).equals(result);
	}

	/**
	 * 按字段和值的快照条件删除 Hash 字段。
	 * @param key 原始键名，用于 Cluster 槽位路由
	 * @param field 要删除的字段名
	 * @param expectedValue 用户确认时的字段值
	 * @return 字段仍匹配并删除时为 true
	 */
	public boolean deleteRedisHashField(String key, String field, String expectedValue) {
		Object result = executeRedisCommand(key, "EVAL", List.of(DELETE_REDIS_HASH_FIELD_SCRIPT, "1", key,
			field, expectedValue));
		return Long.valueOf(1).equals(result);
	}

	/**
	 * 向 Set 添加一个成员；Redis 使用成员值保证幂等，不会重复存储相同成员。
	 * @param key 原始键名，用于 Cluster 槽位路由
	 * @param member 要添加的完整成员值
	 * @return 新成员插入时为 true；成员已存在时为 false
	 */
	public boolean addRedisSetMember(String key, String member) {
		Object result = executeRedisCommand(key, "EVAL", List.of(ADD_REDIS_MEMBER_SCRIPT, "1", key,
			"set", "SADD", member, ""));
		return Long.valueOf(1).equals(result);
	}

	/**
	 * 删除选中的精确 Set 成员；SREM 按集合成员身份操作，不受预览行顺序变化影响。
	 * @param key 原始键名，用于 Cluster 槽位路由
	 * @param member 用户选中的完整成员值
	 * @return 成员存在并删除时为 true
	 */
	public boolean deleteRedisSetMember(String key, String member) {
		Object result = executeRedisCommand(key, "SREM", List.of(key, member));
		return Long.valueOf(1).equals(result);
	}

	/**
	 * 按旧成员身份原子重命名 Set 成员，新成员已存在时拒绝合并。
	 * @param key 原始键名，用于 Cluster 槽位路由
	 * @param expectedMember 当前预览成员
	 * @param replacement 用户提交的新成员值
	 * @return 旧成员仍存在且替换成功时为 true
	 */
	public boolean replaceRedisSetMember(String key, String expectedMember, String replacement) {
		Object result = executeRedisCommand(key, "EVAL", List.of(UPDATE_REDIS_SET_MEMBER_SCRIPT, "1", key,
			expectedMember, replacement));
		return Long.valueOf(1).equals(result);
	}

	/**
	 * 以 ZADD NX 添加有序集合成员，不覆盖已存在成员的分数。
	 * @param key 原始键名，用于 Cluster 槽位路由
	 * @param member 要添加的完整成员值
	 * @param score 有限数值分数
	 * @return 新成员插入时为 true；成员已存在时为 false
	 * @throws IllegalArgumentException 分数为 NaN 或无穷大
	 */
	public boolean addRedisSortedSetMember(String key, String member, double score) {
		validateRedisSortedSetScore(score);
		Object result = executeRedisCommand(key, "EVAL", List.of(ADD_REDIS_MEMBER_SCRIPT, "1", key,
			"zset", "ZADD", member, Double.toString(score)));
		return Long.valueOf(1).equals(result);
	}

	/**
	 * 按成员和预览分数条件更新有序集合分数。
	 * @param key 原始键名，用于 Cluster 槽位路由
	 * @param member 要更新的完整成员值
	 * @param expectedScore 打开编辑器时读取的分数
	 * @param replacementScore 用户提交的新分数
	 * @return 当前分数仍匹配并完成更新时为 true
	 * @throws IllegalArgumentException 分数为 NaN 或无穷大
	 */
	public boolean replaceRedisSortedSetScore(String key, String member, double expectedScore, double replacementScore) {
		validateRedisSortedSetScore(expectedScore);
		validateRedisSortedSetScore(replacementScore);
		Object result = executeRedisCommand(key, "EVAL", List.of(UPDATE_REDIS_ZSET_SCORE_SCRIPT, "1", key,
			member, Double.toString(expectedScore), Double.toString(replacementScore)));
		return Long.valueOf(1).equals(result);
	}

	/**
	 * 按成员和预览分数条件删除有序集合成员。
	 * @param key 原始键名，用于 Cluster 槽位路由
	 * @param member 用户选中的完整成员值
	 * @param expectedScore 确认时读取的分数
	 * @return 当前分数仍匹配并完成删除时为 true
	 * @throws IllegalArgumentException 分数为 NaN 或无穷大
	 */
	public boolean deleteRedisSortedSetMember(String key, String member, double expectedScore) {
		validateRedisSortedSetScore(expectedScore);
		Object result = executeRedisCommand(key, "EVAL", List.of(DELETE_REDIS_ZSET_MEMBER_SCRIPT, "1", key,
			member, Double.toString(expectedScore)));
		return Long.valueOf(1).equals(result);
	}

	/**
	 * 在 List 左侧或右侧追加单个值；这遵循序列插入语义而不伪装成稳定行号。
	 * @param key 原始键名，用于 Cluster 槽位路由
	 * @param value 要追加的字符串值
	 * @param left true 表示 LPUSH，false 表示 RPUSH
	 * @return 写入后的列表长度
	 */
	public long pushRedisListValue(String key, String value, boolean left) {
		Object result = executeRedisCommand(key, "EVAL", List.of(ADD_REDIS_MEMBER_SCRIPT, "1", key,
			"list", left ? "LPUSH" : "RPUSH", value, ""));
		return ((Number) result).longValue();
	}

	/**
	 * 仅当绝对位置当前值仍与预览一致时编辑 List 该位置；操作针对位置，不宣称追踪稳定元素身份。
	 * @param key 原始键名，用于 Cluster 槽位路由
	 * @param index 列表绝对零基位置
	 * @param expectedLength 详情读取时的列表总长度
	 * @param expectedValue 当前预览值
	 * @param replacement 用户提交的新值
	 * @return 位置值仍匹配并完成写回时为 true
	 * @throws IllegalArgumentException index 为负数
	 */
	public boolean replaceRedisListValue(String key, long index, long expectedLength, String expectedValue,
		String replacement) {
		if (index < 0 || expectedLength < 1) {
			throw new IllegalArgumentException(databaseMessage(locale, "redis.list.index.invalid"));
		}
		Object result = executeRedisCommand(key, "EVAL", List.of(UPDATE_REDIS_LIST_VALUE_SCRIPT, "1", key,
			Long.toString(expectedLength), Long.toString(index), expectedValue, replacement));
		return Long.valueOf(1).equals(result);
	}

	/**
	 * 按绝对位置和值快照删除首项或末项。List 不提供索引删除，其他位置明确不执行删除。
	 * @param key 原始键名，用于 Cluster 槽位路由
	 * @param index 列表绝对零基位置
	 * @param expectedLength 详情读取时的列表总长度
	 * @param expectedValue 当前预览值
	 * @return 1 表示已删除，0 表示快照已变化，-2 表示该位置不是首项或末项
	 * @throws IllegalArgumentException index 为负数
	 */
	public long deleteRedisListValue(String key, long index, long expectedLength, String expectedValue) {
		if (index < 0 || expectedLength < 1) {
			throw new IllegalArgumentException(databaseMessage(locale, "redis.list.index.invalid"));
		}
		Object result = executeRedisCommand(key, "EVAL", List.of(DELETE_REDIS_LIST_VALUE_SCRIPT, "1", key,
			Long.toString(expectedLength), Long.toString(index), expectedValue));
		return ((Number) result).longValue();
	}

	/**
	 * 在 Stream 尾部追加一个 field/value 对，由 Redis 生成消息 ID。
	 * @param key 原始键名，用于 Cluster 槽位路由
	 * @param field 字段名
	 * @param value 字段值
	 * @return Redis 生成的 Stream 消息 ID
	 */
	public String appendRedisStreamEntry(String key, String field, String value) {
		if (Objects.isNull(field) || Objects.isNull(value)
			|| field.getBytes(StandardCharsets.UTF_8).length + (long) value.getBytes(StandardCharsets.UTF_8).length
			> MAX_REDIS_STREAM_MUTATION_BYTES) {
			throw new IllegalArgumentException(databaseMessage(locale, "redis.stream.entry.too.large"));
		}
		Object result = executeRedisCommand(key, "EVAL", List.of(ADD_REDIS_MEMBER_SCRIPT, "1", key,
			"stream", "XADD", field, value));
		return result instanceof byte[] streamId ? new String(streamId, StandardCharsets.UTF_8) : null;
	}

	/**
	 * 按稳定消息 ID 和完整字段快照删除 Stream 消息，字段变化或消息消失时拒绝。
	 * @param key 原始键名，用于 Cluster 槽位路由
	 * @param expectedMessage 用户确认删除的完整消息快照
	 * @return 字段未变化且消息已删除时为 true
	 */
	public boolean deleteRedisStreamMessage(String key, RedisStreamMessage expectedMessage) {
		if (!expectedMessage.completeSnapshot()) {
			throw new IllegalArgumentException(databaseMessage(locale, "redis.stream.entry.too.large"));
		}
		long snapshotSize = expectedMessage.id().getBytes(StandardCharsets.UTF_8).length;
		for (Map.Entry<String, String> field : expectedMessage.fields()) {
			snapshotSize += field.getKey().getBytes(StandardCharsets.UTF_8).length;
			snapshotSize += field.getValue().getBytes(StandardCharsets.UTF_8).length;
			if (snapshotSize > MAX_REDIS_STREAM_MUTATION_BYTES
				|| expectedMessage.fields().size() > MAX_REDIS_STREAM_PREVIEW_FIELDS) {
				throw new IllegalArgumentException(databaseMessage(locale, "redis.stream.entry.too.large"));
			}
		}
		List<String> arguments = new ArrayList<>(4 + expectedMessage.fields().size() * 2);
		arguments.add(DELETE_REDIS_STREAM_MESSAGE_SCRIPT);
		arguments.add("1");
		arguments.add(key);
		arguments.add(expectedMessage.id());
		arguments.add(Integer.toString(expectedMessage.fields().size() * 2));
		for (Map.Entry<String, String> field : expectedMessage.fields()) {
			arguments.add(field.getKey());
			arguments.add(field.getValue());
		}
		Object result = executeRedisCommand(key, "EVAL", arguments);
		return Long.valueOf(1).equals(result);
	}

	/** 拒绝 NaN 和无穷大分数，以免输入不能稳定往返或超出 Redis ZSet 数值语义。 */
	private void validateRedisSortedSetScore(double score) {
		if (!Double.isFinite(score)) {
			throw new IllegalArgumentException(databaseMessage(locale, "redis.zset.score.invalid"));
		}
	}

	/**
	 * 在确认时读取的字符串值仍匹配时设置秒级 TTL，防止把过期操作应用到同名新键。
	 *
	 * @param key 原始 Redis 键，用于 Cluster 哈希槽路由
	 * @param expectedValue 用户确认时读取的完整字符串原值
	 * @param ttlSeconds 新 TTL，必须大于零
	 * @return 键仍存在且更新成功时为 true
	 * @throws IllegalArgumentException TTL 不为正数
	 */
	public boolean setRedisKeyTtl(String key, String expectedValue, long ttlSeconds) {
		if (ttlSeconds < 1) {
			throw new IllegalArgumentException(databaseMessage(locale, "redis.ttl.positive"));
		}
		Object result = executeRedisCommand(key, "EVAL", List.of(UPDATE_REDIS_KEY_TTL_SCRIPT, "1", key,
			expectedValue, Long.toString(ttlSeconds)));
		return Long.valueOf(1).equals(result);
	}

	/**
	 * 在确认时读取的字符串值仍匹配时将其设为永久存活。
	 *
	 * @param key 原始 Redis 键，用于 Cluster 哈希槽路由
	 * @param expectedValue 用户确认时读取的完整字符串原值
	 * @return 已清除 TTL 时为 true；键已过期或没有 TTL 时为 false
	 */
	public boolean clearRedisKeyTtl(String key, String expectedValue) {
		Object result = executeRedisCommand(key, "EVAL", List.of(CLEAR_REDIS_KEY_TTL_SCRIPT, "1", key, expectedValue));
		return Long.valueOf(1).equals(result);
	}

	/**
	 * 仅在字符串值仍匹配用户确认时执行单键删除。
	 *
	 * @param key 用户确认删除的原始 Redis 键，用于 Cluster 哈希槽路由
	 * @param expectedValue 确认时读取的完整字符串原值
	 * @return 键仍存在且被删除时为 true
	 */
	public boolean deleteRedisKey(String key, String expectedValue) {
		Object result = executeRedisCommand(key, "EVAL", List.of(DELETE_REDIS_KEY_SCRIPT, "1", key, expectedValue));
		return Long.valueOf(1).equals(result);
	}

	/**
	 * 在 Redis 服务端读取完整但有界的复合键快照。超出成员数或字节预算时仅返回状态，不将超限正文传回 JVM。
	 *
	 * @param key 作为集群路由键和快照对象标识的原始键名
	 * @return 带来源标识、状态、计数和完整内容的不可变快照；不支持的数据编码会被拒绝
	 */
	public RedisKeyDeletionSnapshot readRedisKeyDeletionSnapshot(String key) {
		Object response = executeRedisCommand(key, "EVAL", List.of(loadRedisKeyDeletionSnapshotScript(), "1", key,
			"read", Integer.toString(MAX_REDIS_DELETE_SNAPSHOT_ITEMS),
			Integer.toString(MAX_REDIS_DELETE_SNAPSHOT_BYTES),
			Integer.toString(MAX_REDIS_DELETE_SNAPSHOT_STREAM_FIELDS)));
		List<?> values = (List<?>) response;
		int statusCode = ((Number) values.get(0)).intValue();
		String type = decodeRedisText(values.get(1));
		long memberCount = ((Number) values.get(2)).longValue();
		long byteCount = ((Number) values.get(3)).longValue();
		long expireAtMillis = ((Number) values.get(4)).longValue();
		RedisKeyDeletionSnapshotStatus status = switch (statusCode) {
			case 0 -> RedisKeyDeletionSnapshotStatus.MISSING;
			case 1 -> RedisKeyDeletionSnapshotStatus.UNSUPPORTED_TYPE;
			case 2 -> RedisKeyDeletionSnapshotStatus.TOO_LARGE;
			case 3 -> RedisKeyDeletionSnapshotStatus.READY;
			case 4 -> RedisKeyDeletionSnapshotStatus.SCAN_LIMIT;
			case 5 -> RedisKeyDeletionSnapshotStatus.EXPIRATION_UNSUPPORTED;
			case 6 -> RedisKeyDeletionSnapshotStatus.STREAM_INFO_UNSUPPORTED;
			case 7 -> RedisKeyDeletionSnapshotStatus.STREAM_HAS_GROUPS;
			default -> throw new IllegalStateException("Unexpected Redis deletion snapshot status: " + statusCode);
		};
		if (status != RedisKeyDeletionSnapshotStatus.READY) {
			return new RedisKeyDeletionSnapshot(key, sourceIdentity, type, List.of(), memberCount, byteCount,
				expireAtMillis, status);
		}
		try {
			List<?> payload = (List<?>) values.get(5);
			List<String> snapshotValues = new ArrayList<>();
			if ("stream".equals(type)) {
				if (payload.size() != memberCount) {
					throw new IllegalStateException("Incomplete Redis Stream deletion snapshot");
				}
				for (Object item : payload) {
					List<?> entry = (List<?>) item;
					String id = decodeRedisSnapshotText(entry.get(0));
					int fieldCount = ((Number) entry.get(1)).intValue();
					if (entry.size() != fieldCount * 2 + 2) {
						throw new IllegalStateException("Malformed Redis Stream deletion snapshot");
					}
					snapshotValues.add(id);
					snapshotValues.add(Integer.toString(fieldCount));
					for (int index = 2; index < entry.size(); index++) {
						snapshotValues.add(decodeRedisSnapshotText(entry.get(index)));
					}
				}
			} else {
				for (Object item : payload) {
					snapshotValues.add(decodeRedisSnapshotText(item));
				}
			}
			return new RedisKeyDeletionSnapshot(key, sourceIdentity, type, snapshotValues, memberCount, byteCount,
				expireAtMillis, status);
		} catch (IllegalArgumentException invalidEncoding) {
			return new RedisKeyDeletionSnapshot(key, sourceIdentity, type, List.of(), memberCount, byteCount,
				expireAtMillis, RedisKeyDeletionSnapshotStatus.UNSUPPORTED_ENCODING);
		}
	}

	/**
	 * 原子比较完整复合键快照后删除。快照只能用于读取它的 Redis 来源和逻辑库；任何类型、成员、顺序或值变化均拒绝删除。
	 *
	 * @param key 待删的原始键名
	 * @param snapshot 用户确认时读取的完整快照
	 * @return 快照仍完全匹配且键已删除时为 true
	 * @throws IllegalArgumentException 快照未完整、来源不符或超出删除限额
	 */
	public boolean deleteRedisKey(String key, RedisKeyDeletionSnapshot snapshot) {
		if (Objects.isNull(snapshot) || snapshot.status() != RedisKeyDeletionSnapshotStatus.READY
			|| !Objects.equals(key, snapshot.key()) || !snapshot.belongsTo(sourceIdentity)
			|| snapshot.memberCount() > MAX_REDIS_DELETE_SNAPSHOT_ITEMS
			|| snapshot.byteCount() > MAX_REDIS_DELETE_SNAPSHOT_BYTES
			|| snapshot.values().size() > MAX_REDIS_DELETE_SNAPSHOT_ITEMS * 2
				+ MAX_REDIS_DELETE_SNAPSHOT_STREAM_FIELDS * 2) {
			throw new IllegalArgumentException(databaseMessage(locale, "redis.key.delete.snapshot.not.ready"));
		}
		List<String> arguments = new ArrayList<>(snapshot.values().size() + 8);
		arguments.add(loadRedisKeyDeletionSnapshotScript());
		arguments.add("1");
		arguments.add(key);
		arguments.add("delete");
		arguments.add(snapshot.type());
		arguments.add(Long.toString(snapshot.memberCount()));
		arguments.add(Long.toString(snapshot.byteCount()));
	arguments.add(Long.toString(snapshot.expireAtMillis()));
		arguments.addAll(snapshot.values());
		Object result = executeRedisCommand(key, "EVAL", arguments);
		return Long.valueOf(1).equals(result);
	}

	/** 将快照字段按 UTF-8 无损往返解码；不可表示的二进制数据不能参与字符串 CAS。 */
	private static String decodeRedisSnapshotText(Object value) {
		byte[] bytes = (byte[]) value;
		String decoded = new String(bytes, StandardCharsets.UTF_8);
		if (!Arrays.equals(bytes, decoded.getBytes(StandardCharsets.UTF_8))) {
			throw new IllegalArgumentException("Redis value is not valid round-trippable UTF-8");
		}
		return decoded;
	}

	/**
	 * 在 Redis 服务端限制 Stream 页响应的字节数。字段按协议顺序保留，超限消息仍保留 ID，
	 * 但没有可用于条件删除的完整快照。服务端仍需读取原始消息。
	 *
	 * @param key 原始 Stream 键，用于 Cluster 哈希槽路由
	 * @param afterId 上一页最后消息 ID；空值表示第一页
	 * @return 本页消息与下一页游标；空游标表示没有后续页
	 */
	private Map.Entry<List<RedisStreamMessage>, String> readRedisStreamPreview(String key, String afterId) {
		String messageId = Objects.nonNull(afterId) && afterId.startsWith("stream:")
			? afterId.substring("stream:".length()) : "";
		String start = messageId.isEmpty() ? "-" : "(" + messageId;
		var reply = (List<?>) executeRedisCommand(key, "EVAL", List.of(loadRedisStreamPageScript(), "1", key,
			start, Integer.toString(REDIS_MEMBER_PAGE_SIZE + 1), Integer.toString(MAX_REDIS_STREAM_PAGE_BYTES)));
		int displayedCount = Math.min(reply.size(), REDIS_MEMBER_PAGE_SIZE);
		var messages = new ArrayList<RedisStreamMessage>(displayedCount);
		for (int rowIndex = 0; rowIndex < displayedCount; rowIndex++) {
			var entry = (List<?>) reply.get(rowIndex);
			boolean completeSnapshot = Long.valueOf(1).equals(entry.get(1));
			var values = (List<?>) entry.get(2);
			var fields = new ArrayList<Map.Entry<String, String>>(values.size() / 2);
			for (int field = 0; field < values.size(); field += 2) {
				fields.add(Map.entry(decodeRedisText(values.get(field)), decodeRedisText(values.get(field + 1))));
			}
			messages.add(new RedisStreamMessage(decodeRedisText(entry.get(0)),
				List.copyOf(fields), completeSnapshot));
		}
		String nextPageCursor = reply.size() > REDIS_MEMBER_PAGE_SIZE
			? "stream:" + messages.getLast().id() : "";
		return Map.entry(List.copyOf(messages), nextPageCursor);
	}

	/** 仅在浏览 Stream 时加载打包资源，缺失时直接结束该请求。 */
	private String loadRedisStreamPageScript() {
		String resource = "/database/redis/read-stream-page.lua";
		try (var input = WorkbenchRedisConnection.class.getResourceAsStream(resource)) {
			if (Objects.isNull(input)) {
				throw new IOException(resource);
			}
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException failure) {
			throw new UncheckedIOException("Cannot load Redis Stream page script", failure);
		}
	}

	/** 仅在首次复合键整删时加载服务端有界快照脚本；脚本缺失时停止操作，不退化为无条件删除。 */
	private String loadRedisKeyDeletionSnapshotScript() {
		String resource = "/database/redis/redis-key-deletion-snapshot.lua";
		try (var input = WorkbenchRedisConnection.class.getResourceAsStream(resource)) {
			if (Objects.isNull(input)) {
				throw new IOException(resource);
			}
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException failure) {
			throw new UncheckedIOException("Cannot load Redis key deletion snapshot script", failure);
		}
	}

	/**
	 * 复合键整键删除的边界状态；只有完整、可比较且支持过期保护的快照可用于确认删除。
	 *
	 * @author liaojinlong
	 * @since 2026/11/22 17:50
	 */
	public enum RedisKeyDeletionSnapshotStatus {
		/** 完整快照已读取，可进入用户确认流程。 */
		READY,
		/** 读取时键已不存在。 */
		MISSING,
		/** 当前键类型不属于受支持的复合类型。 */
		UNSUPPORTED_TYPE,
		/** 成员数或内容字节数超过服务端快照预算。 */
		TOO_LARGE,
		/** HSCAN/SSCAN 在有界迭代轮次内未完成。 */
		SCAN_LIMIT,
		/** 快照内容无法无损表示为 UTF-8 文本。 */
		UNSUPPORTED_ENCODING,
		/** 服务器不支持绝对过期时间读取，且键具有 TTL。 */
		EXPIRATION_UNSUPPORTED,
		/** 服务器无法安全读取 Stream 消费组元数据。 */
		STREAM_INFO_UNSUPPORTED,
		/** Stream 已有消费组；删除整键会连带移除消费状态，因此拒绝。 */
		STREAM_HAS_GROUPS
	}

	/**
	 * 用于二次确认的完整复合键快照。来源标识限制连接和逻辑库，绝对到期时间及成员值由 Lua 再次比较。
	 *
	 * @author liaojinlong
	 * @since 2026/11/22 17:50
	 */
	public static final class RedisKeyDeletionSnapshot {
		/** 快照对应的原始键名。 */
		private final String key;
		/** 快照生成连接的节点集合、模式与逻辑库标识。 */
		private final String sourceIdentity;
		/** 快照生成时的 Redis 类型。 */
		private final String type;
		/** 按类型编码的不可变原始文本值序列。 */
		private final List<String> values;
		/** 逻辑成员或 Stream 消息数量。 */
		private final long memberCount;
		/** 快照内容的 UTF-8 字节数。 */
		private final long byteCount;
		/** 绝对过期时间毫秒；-1 表示持久键，Redis 7+ 才接受有 TTL 的键。 */
		private final long expireAtMillis;
		/** 快照是否完整且可用于删除。 */
		private final RedisKeyDeletionSnapshotStatus status;

		/**
		 * 创建连接层内部使用的不可变快照。
		 *
		 * @param key 快照对应的原始键名
		 * @param sourceIdentity 来源连接和逻辑库的标识
		 * @param type Redis 类型
		 * @param values 按类型编码的完整值序列
		 * @param memberCount 成员或消息数量
		 * @param byteCount 内容 UTF-8 字节数
		 * @param expireAtMillis 绝对过期时间；-1 表示持久键
		 * @param status 快照完整性状态
		 */
		private RedisKeyDeletionSnapshot(String key, String sourceIdentity, String type, List<String> values,
										 long memberCount, long byteCount, long expireAtMillis,
										 RedisKeyDeletionSnapshotStatus status) {
			this.key = key;
			this.sourceIdentity = sourceIdentity;
			this.type = type;
			this.values = List.copyOf(values);
			this.memberCount = memberCount;
			this.byteCount = byteCount;
			this.expireAtMillis = expireAtMillis;
			this.status = status;
		}

		/** 返回快照键名。 */
		public String key() { return key; }
		/** 返回快照类型。 */
		public String type() { return type; }
		/** 返回不可变的类型专属值序列。 */
		public List<String> values() { return values; }
		/** 返回完整成员数。 */
		public long memberCount() { return memberCount; }
		/** 返回内容字节数。 */
		public long byteCount() { return byteCount; }
		/** 返回绝对过期时间毫秒，-1 表示持久键。 */
		public long expireAtMillis() { return expireAtMillis; }
		/** 返回快照读取状态。 */
		public RedisKeyDeletionSnapshotStatus status() { return status; }

		/** 避免将快照跨 Redis 连接、节点集合或逻辑库误用。 */
		private boolean belongsTo(String identity) {
			return Objects.equals(sourceIdentity, identity);
		}
	}

	/**
	 * 单个 Redis 键的只读信息。各字段来自独立请求，键可能在读取期间过期。
	 *
	 * @param type          Redis TYPE 返回值，none 表示读取时键不存在
	 * @param ttlSeconds    剩余秒数，-1 表示无过期时间，-2 表示键不存在
	 * @param stringPreview 字符串值的前 4096 字节，其他类型为空
	 * @param listPreview 列表的前 20 项，其他类型为空
	 * @param listLength 读取时的完整 List 长度，供原子位置编辑拒绝过期预览
	 * @param sortedSetPreview 有序集合按分数升序的前 20 项，其他类型为空
	 * @param hashPreview 一次扫描返回的最多 20 个 Hash 字段及其值
	 * @param setPreview 一次扫描返回的最多 20 个 Set 成员
	 * @param streamPreview 按 ID 升序的最多 20 条 Stream 消息
	 * @param editableStringSnapshot 完整字符串快照，仅在值不超过 64 KiB 时提供
	 * @param memberPageCursor 当前类型专用游标
	 * @param nextMemberPageCursor 下一页游标；空串表示当前页已到末尾
	 * @param memberPageOffset List/ZSet 当前页的零基 offset，其他类型为 0
	 * @param hashBufferedPreview HSCAN 单次响应中超过可见页的成员，先于服务端续页游标消费
	 * @param setBufferedPreview SSCAN 单次响应中超过可见页的成员，先于服务端续页游标消费
	 * @param hashNextScanCursor HSCAN 返回的服务器游标
	 * @param setNextScanCursor SSCAN 返回的服务器游标
	 * @param memberBufferOffset 当前缓冲批次中已展示的成员数
	 * @author liaojinlong
	 * @since 2026/11/18 18:27
	 */
	public record RedisKeyDetails(String type, long ttlSeconds, String stringPreview, List<String> listPreview,
								  List<RedisScoredMember> sortedSetPreview, List<Map.Entry<String, String>> hashPreview,
								  List<String> setPreview, List<RedisStreamMessage> streamPreview, long listLength,
								  String editableStringSnapshot, String memberPageCursor, String nextMemberPageCursor,
								  long memberPageOffset, List<Map.Entry<String, String>> hashBufferedPreview,
								  List<String> setBufferedPreview, String hashNextScanCursor, String setNextScanCursor,
								  int memberBufferOffset) {
		/** 兼容原有无缓冲成员页构造入口。 */
		public RedisKeyDetails(String type, long ttlSeconds, String stringPreview, List<String> listPreview,
							   List<RedisScoredMember> sortedSetPreview, List<Map.Entry<String, String>> hashPreview,
							   List<String> setPreview, List<RedisStreamMessage> streamPreview, long listLength,
							   String editableStringSnapshot, String memberPageCursor, String nextMemberPageCursor,
							   long memberPageOffset) {
			this(type, ttlSeconds, stringPreview, listPreview, sortedSetPreview, hashPreview, setPreview,
				streamPreview, listLength, editableStringSnapshot, memberPageCursor, nextMemberPageCursor,
				memberPageOffset, List.of(), List.of(), "0", "0", 0);
		}

		/** 兼容显式成员缓冲入口；当前页从该批次起点展示。 */
		public RedisKeyDetails(String type, long ttlSeconds, String stringPreview, List<String> listPreview,
							   List<RedisScoredMember> sortedSetPreview, List<Map.Entry<String, String>> hashPreview,
							   List<String> setPreview, List<RedisStreamMessage> streamPreview, long listLength,
							   String editableStringSnapshot, String memberPageCursor, String nextMemberPageCursor,
							   long memberPageOffset, List<Map.Entry<String, String>> hashBufferedPreview,
							   List<String> setBufferedPreview, String hashNextScanCursor, String setNextScanCursor) {
			this(type, ttlSeconds, stringPreview, listPreview, sortedSetPreview, hashPreview, setPreview,
				streamPreview, listLength, editableStringSnapshot, memberPageCursor, nextMemberPageCursor,
				memberPageOffset, hashBufferedPreview, setBufferedPreview, hashNextScanCursor, setNextScanCursor, 0);
		}
		/** 兼容此前完整详情快照构造方式；未知总长度时仅保留已读列表数量。 */
		public RedisKeyDetails(String type, long ttlSeconds, String stringPreview, List<String> listPreview,
							   List<RedisScoredMember> sortedSetPreview, List<Map.Entry<String, String>> hashPreview,
							   List<String> setPreview, List<RedisStreamMessage> streamPreview, String editableStringSnapshot) {
			this(type, ttlSeconds, stringPreview, listPreview, sortedSetPreview, hashPreview, setPreview, streamPreview,
				listPreview.size(), editableStringSnapshot, "", "", 0);
		}

		/**
		 * 保留显式 List 总长度的旧详情构造入口；分页游标默认为首屏。
		 *
		 * @param type Redis TYPE 返回值
		 * @param ttlSeconds 键剩余存活秒数
		 * @param stringPreview 字符串预览
		 * @param listPreview 当前 List 页
		 * @param sortedSetPreview 当前 ZSet 页
		 * @param hashPreview Hash 预览
		 * @param setPreview Set 预览
		 * @param streamPreview 当前 Stream 页
		 * @param listLength 读取时 List 完整长度
		 * @param editableStringSnapshot 可编辑字符串的完整快照
		 */
		public RedisKeyDetails(String type, long ttlSeconds, String stringPreview, List<String> listPreview,
							   List<RedisScoredMember> sortedSetPreview, List<Map.Entry<String, String>> hashPreview,
							   List<String> setPreview, List<RedisStreamMessage> streamPreview, long listLength,
							   String editableStringSnapshot) {
			this(type, ttlSeconds, stringPreview, listPreview, sortedSetPreview, hashPreview, setPreview, streamPreview,
				listLength, editableStringSnapshot, "", "", 0);
		}

		/**
		 * 构造已有的非 Stream 值预览。
		 *
		 * @param type Redis TYPE 返回值
		 * @param ttlSeconds 键剩余存活秒数
		 * @param stringPreview 字符串预览
		 * @param listPreview 列表预览
		 * @param sortedSetPreview 有序集合预览
		 * @param hashPreview Hash 预览
		 * @param setPreview Set 预览
		 */
		public RedisKeyDetails(String type, long ttlSeconds, String stringPreview, List<String> listPreview,
							   List<RedisScoredMember> sortedSetPreview, List<Map.Entry<String, String>> hashPreview,
							   List<String> setPreview) {
			this(type, ttlSeconds, stringPreview, listPreview, sortedSetPreview, hashPreview, setPreview, List.of(),
				listPreview.size(), null);
		}

		/**
		 * 兼容旧调用方构造的 Stream 详情，不携带可编辑的完整字符串。
		 *
		 * @param type Redis TYPE 返回值
		 * @param ttlSeconds 键剩余存活秒数
		 * @param stringPreview 字符串预览
		 * @param listPreview 列表预览
		 * @param sortedSetPreview 有序集合预览
		 * @param hashPreview Hash 预览
		 * @param setPreview Set 预览
		 * @param streamPreview Stream 预览
		 */
		public RedisKeyDetails(String type, long ttlSeconds, String stringPreview, List<String> listPreview,
							   List<RedisScoredMember> sortedSetPreview, List<Map.Entry<String, String>> hashPreview,
							   List<String> setPreview, List<RedisStreamMessage> streamPreview) {
			this(type, ttlSeconds, stringPreview, listPreview, sortedSetPreview, hashPreview, setPreview, streamPreview,
				listPreview.size(), null, "", "", 0);
		}

		/**
		 * 构造原来支持的字符串、列表和有序集合预览。
		 *
		 * @param type Redis TYPE 返回值
		 * @param ttlSeconds 键剩余存活秒数
		 * @param stringPreview 字符串值预览
		 * @param listPreview 列表值预览
		 * @param sortedSetPreview 有序集合预览
		 */
		public RedisKeyDetails(String type, long ttlSeconds, String stringPreview, List<String> listPreview,
							   List<RedisScoredMember> sortedSetPreview) {
			this(type, ttlSeconds, stringPreview, listPreview, sortedSetPreview, List.of(), List.of());
		}

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
