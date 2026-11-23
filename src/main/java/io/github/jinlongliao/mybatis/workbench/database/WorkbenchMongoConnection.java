package io.github.jinlongliao.mybatis.workbench.database;

import static io.github.jinlongliao.mybatis.workbench.database.DatabaseMessages.databaseMessage;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoCredential;
import com.mongodb.ReadPreference;
import com.mongodb.TransactionOptions;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Collation;
import com.mongodb.client.model.DeleteOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonType;
import org.bson.BsonObjectId;
import org.bson.Document;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.codecs.DecoderContext;
import org.bson.json.JsonMode;
import org.bson.json.JsonParseException;
import org.bson.json.JsonReader;
import org.bson.json.JsonWriterSettings;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.lang.invoke.MethodHandles;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 通过官方驱动浏览 MongoDB 副本集或单机，保持认证独立于连接 URI。
 *
 * @author liaojinlong
 * @see <a href="https://www.mongodb.com/docs/drivers/java/sync/current/">MongoDB Java 同步驱动</a>
 * @since 2026/11/18 15:37
 */
public final class WorkbenchMongoConnection implements AutoCloseable {
	/** 记录列库失败以及固定目标库回退路径。 */
	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	/** 一次 JSON Lines 导入最多读取 8 MiB，超过时在解析和写入前拒绝。 */
	private static final int MAX_MONGO_IMPORT_BYTES = 8 * 1024 * 1024;
	/** 一次导入事务最多写入 1000 条，限制内存、事务和锁持有范围。 */
	private static final int MAX_MONGO_IMPORT_DOCUMENTS = 1000;

	/** 当前连接用于格式化错误消息的宿主语言。 */
	private final Locale locale;

	/** URI 中允许直接浏览的目标数据库，未配置时为空。 */
	private final String uriDatabase;

	/**
	 * 保留日期、整型、ObjectId 和其他 BSON 类型在输出中的区别。
	 */
	private static final JsonWriterSettings EXTENDED_JSON = JsonWriterSettings.builder()
		.outputMode(JsonMode.EXTENDED).build();

	/**
	 * 管理已连接节点、监控任务和连接池的驱动客户端。
	 */
	private final MongoClient mongoClient;

	/**
	 * 创建客户端，驱动根据连接 URI 发现副本集和选主。
	 *
	 * @param uri          MongoDB 地址，可指定 replicaSet、TLS 等连接选项
	 * @param username     数据库用户名，可为空
	 * @param authDatabase 用户所在的认证库，空白时由 URI 与驱动默认规则决定
	 * @param password     独立传入的密码，不嵌入连接 URI
	 * @throws IllegalArgumentException URI 内含凭据或关闭 TLS 主机名验证
	 */
	public WorkbenchMongoConnection(String uri, String username, String authDatabase, char[] password) {
		this(uri, username, authDatabase, password, Locale.getDefault());
	}

	/**
	 * 创建客户端，驱动根据连接 URI 发现副本集和选主。
	 *
	 * @param uri          MongoDB 地址，可指定 replicaSet、TLS 等连接选项
	 * @param username     数据库用户名，可为空
	 * @param authDatabase 用户所在的认证库，空白时由 URI 与驱动默认规则决定
	 * @param password     独立传入的密码，不嵌入连接 URI
	 * @param locale 宿主界面语言，用于此连接生成的错误消息
	 * @throws IllegalArgumentException URI 内含凭据或关闭 TLS 主机名验证
	 */
	public WorkbenchMongoConnection(String uri, String username, String authDatabase, char[] password, Locale locale) {
		this(uri, username, authDatabase, password, true, locale);
	}

	/**
	 * 建立 MongoDB 客户端；URI 启用 TLS 时可仅跳过证书校验，数据传输仍使用 TLS。
	 *
	 * @param uri MongoDB URI，TLS 选项由 URI 决定
	 * @param username 用户名，可为空
	 * @param authDatabase 认证数据库，空白时采用 URI 推导结果
	 * @param password 独立存储的密码
	 * @param verifyCertificate 是否校验服务器证书链和主机名
	 * @param locale 用于错误信息的语言
	 * @since 2026/11/21 23:07
	 */
	public WorkbenchMongoConnection(String uri, String username, String authDatabase, char[] password,
									 boolean verifyCertificate, Locale locale) {
		this.locale = locale;
		ConnectionString connectionString = new ConnectionString(uri);
		uriDatabase = connectionString.getDatabase();
		if (Objects.nonNull(connectionString.getCredential())) {
			throw new IllegalArgumentException(databaseMessage(locale, "mongo.separate.credentials"));
		}
		if (Boolean.TRUE.equals(connectionString.getSslInvalidHostnameAllowed())) {
			throw new IllegalArgumentException(databaseMessage(locale, "mongo.tls.hostname"));
		}
		var settings = MongoClientSettings.builder()
			.applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(8, TimeUnit.SECONDS))
			.applyToSocketSettings(socket -> socket.connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS))
			.applyConnectionString(connectionString);
		boolean tlsEnabled = Boolean.TRUE.equals(connectionString.getSslEnabled())
			|| uri.regionMatches(true, 0, "mongodb+srv://", 0, "mongodb+srv://".length());
		if (tlsEnabled) {
			settings.inetAddressResolver(WorkbenchMongoConnection::resolveMongoTlsAddresses);
			if (!verifyCertificate) {
				try {
					var context = TlsContexts.createUnverifiedTlsContext();
					settings.applyToSslSettings(ssl -> ssl.context(context).invalidHostNameAllowed(true));
				} catch (GeneralSecurityException failure) {
					throw new IllegalStateException("Unable to initialize MongoDB TLS", failure);
				}
			}
		}
		if (!username.isEmpty()) {
			String source = authDatabase;
			if (source.isBlank()) {
				// 用非敏感占位用户名让驱动按 URI 路径、authSource 和 SRV TXT 计算真实认证库。
				int authority = uri.indexOf("://") + 3;
				var authentication = new ConnectionString(uri.substring(0, authority)
					+ "workbench-placeholder:placeholder@" + uri.substring(authority)).getCredential();
				source = authentication.getSource();
			}
			settings.credential(MongoCredential.createCredential(username, source, password));
		}
		mongoClient = MongoClients.create(settings.build());
	}

	/**
	 * 保留当前节点的原始地址名称。驱动以解析结果构造套接字地址，丢失名称时 JSSE
	 * 可能使用反向解析结果校验证书，导致 IP 连接误接受只有 DNS SAN 的证书。
	 *
	 * @param host URI 或副本集发现返回的节点名或 IP
	 * @return 按系统解析顺序保留名称和 IPv6 作用域的地址
	 * @throws UnknownHostException 节点地址解析失败
	 * @see com.mongodb.spi.dns.InetAddressResolver
	 */
	private static List<InetAddress> resolveMongoTlsAddresses(String host) throws UnknownHostException {
		InetAddress[] addresses = InetAddress.getAllByName(host);
		List<InetAddress> namedAddresses = new ArrayList<>(addresses.length);
		for (InetAddress address : addresses) {
			if (address instanceof Inet6Address ipv6 && Objects.nonNull(ipv6.getScopedInterface())) {
				namedAddresses.add(Inet6Address.getByAddress(host, ipv6.getAddress(), ipv6.getScopedInterface()));
			} else if (address instanceof Inet6Address ipv6) {
				namedAddresses.add(Inet6Address.getByAddress(host, ipv6.getAddress(), ipv6.getScopeId()));
			} else {
				namedAddresses.add(InetAddress.getByAddress(host, address.getAddress()));
			}
		}
		return namedAddresses;
	}

	/**
	 * 执行 ping，触发连接、认证和服务器选择。
	 */
	public void pingMongoServer() {
		mongoClient.getDatabase("admin").runCommand(new Document("ping", 1));
	}

	/**
	 * 获取当前账号可读取的数据库列表。
	 *
	 * @return BSON 数据库名列表
	 */
	public List<String> listMongoDatabases() {
		try {
			return List.copyOf(mongoClient.listDatabaseNames().into(new ArrayList<>()));
		} catch (MongoCommandException failure) {
			// 权限受限时仅回退到显式指定的库，并实际检查该账号可读取集合清单。
			if (failure.getErrorCode() != 13 || Objects.isNull(uriDatabase)) {
				throw failure;
			}
			log.warn("枚举 MongoDB 数据库被拒绝，阶段=listDatabases，改查 URI 指定数据库={}", uriDatabase, failure);
			listMongoCollections(uriDatabase);
			return List.of(uriDatabase);
		}
	}

	/**
	 * 确定首次浏览的数据库范围。URI 指定的库可见时优先展示该库。
	 *
	 * @param visibleDatabases 已按当前账号权限枚举的数据库名称
	 * @return URI 默认库或全部可见数据库
	 */
	public List<String> initialMongoDatabaseSelection(List<String> visibleDatabases) {
		if (Objects.nonNull(uriDatabase) && visibleDatabases.contains(uriDatabase)) {
			return List.of(uriDatabase);
		}
		return visibleDatabases;
	}

	/**
	 * 返回连接 URI 中明确指定的默认数据库，供选择器标识默认项。
	 *
	 * @return URI 默认数据库名；未指定时为空字符串
	 */
	public String defaultMongoDatabaseName() {
		return Objects.requireNonNullElse(uriDatabase, "");
	}

	/**
	 * 获取指定库中可见的集合。
	 *
	 * @param database 数据库名
	 * @return 集合名列表
	 */
	public List<String> listMongoCollections(String database) {
		return List.copyOf(mongoClient.getDatabase(database).listCollectionNames().into(new ArrayList<>()));
	}

	/**
	 * 通过 Extended JSON 过滤文档，按 _id 排序读取有界页面。
	 * 查找额外一条用于判断下一页，后端为查询设置执行超时。
	 *
	 * @param database   数据库名
	 * @param collection 集合名
	 * @param filter     Extended JSON 查询条件，空白表示全部
	 * @param offset     从 0 开始的偏移，允许最多跳过 10000 条
	 * @param pageSize   返回文档数，范围为 1 至 100
	 * @return 当前页文档及后续页标记
	 * @throws IllegalArgumentException 分页超出界限
	 */
	public MongoDocumentPage queryMongoDocuments(String database, String collection, String filter, int offset,
												 int pageSize) {
		return queryMongoDocuments(database, collection, filter, "_id", false, offset, pageSize);
	}

	/**
	 * 以服务端过滤、排序和有界偏移分页读取 MongoDB 文档；排序字段由 BSON 驱动编码，不拼成查询文本。
	 * 主键作为稳定的次级排序键，避免同值文档在相邻页间重复或跳过。
	 *
	 * @param database 数据库名
	 * @param collection 集合名
	 * @param filter Extended JSON 查询条件，空白表示全部
	 * @param sortField BSON 字段路径；空白时按 `_id` 排序
	 * @param descending 是否降序
	 * @param offset 从 0 开始的偏移，最多跳过 10000 条
	 * @param pageSize 页面大小，范围为 1 至 100
	 * @return 当前页文档及后续页标记
	 * @throws IllegalArgumentException 分页或排序字段无效
	 */
	public MongoDocumentPage queryMongoDocuments(String database, String collection, String filter, String sortField,
												 boolean descending, int offset, int pageSize) {
		if (offset < 0 || offset > 10_000 || pageSize < 1 || pageSize > 100) {
			throw new IllegalArgumentException(databaseMessage(locale, "mongo.page.bounds"));
		}
		String orderField = Objects.requireNonNullElse(sortField, "").strip();
		if (orderField.isEmpty()) {
			orderField = "_id";
		} else if (orderField.startsWith("$") || orderField.contains("\0")) {
			throw new IllegalArgumentException(databaseMessage(locale, "mongo.sort.field.invalid"));
		}
		Document query = filter.isBlank() ? new Document() : Document.parse(filter);
		var sort = orderField.equals("_id")
			? (descending ? Sorts.descending("_id") : Sorts.ascending("_id"))
			: Sorts.orderBy(descending ? Sorts.descending(orderField) : Sorts.ascending(orderField), Sorts.ascending("_id"));
		var matches = mongoClient.getDatabase(database).getCollection(collection).find(query)
			.sort(sort).skip(offset).limit(pageSize + 1).maxTime(5, TimeUnit.SECONDS);
		List<String> documents = new ArrayList<>(pageSize);
		try (MongoCursor<Document> cursor = matches.iterator()) {
			while (documents.size() < pageSize && cursor.hasNext()) {
				documents.add(cursor.next().toJson(EXTENDED_JSON));
			}
			return new MongoDocumentPage(List.copyOf(documents), cursor.hasNext());
		}
	}

	/**
	 * 按查询页的原始 BSON 文档替换一个现存文档。服务端原子匹配 _id 及文档内容，
	 * 并使用 simple collation 比较字符串，以免集合排序规则放宽并发检查。
	 *
	 * @param database 当前数据库
	 * @param collection 当前集合
	 * @param original 查询返回的 Extended JSON 原文
	 * @param replacement 用户确认后的 Extended JSON
	 * @return 匹配并完成替换时为 true，文档已变化或删除时为 false
	 * @throws IllegalArgumentException 文档无效、_id 被修改或连接未启用写入确认
	 * @see <a href="https://www.mongodb.com/docs/drivers/java/sync/current/crud/replace-documents/">MongoDB Java 驱动文档替换</a>
	 */
	public boolean replaceMongoDocument(String database, String collection, String original, String replacement) {
		BsonDocument expected = BsonDocument.parse(original);
		BsonDocument updated = BsonDocument.parse(replacement);
		var id = expected.get("_id");
		if (Objects.isNull(id) || !id.equals(updated.get("_id"))) {
			throw new IllegalArgumentException(databaseMessage(locale, "mongo.edit.id.unchanged"));
		}
		var documents = mongoClient.getDatabase(database).getCollection(collection, BsonDocument.class);
		if (!documents.getWriteConcern().isAcknowledged()) {
			throw new IllegalArgumentException(databaseMessage(locale, "mongo.edit.write.acknowledged"));
		}
		return documents.replaceOne(buildMongoExactDocumentFilter(expected), updated,
			new ReplaceOptions().collation(Collation.builder().locale("simple").build())).getMatchedCount() == 1;
	}

	/**
	 * 插入一份 Extended JSON 文档。缺少 `_id` 时在提交前分配 ObjectId，重复 `_id` 由 MongoDB 唯一约束拒绝。
	 *
	 * @param database 目标数据库
	 * @param collection 目标集合
	 * @param json 完整 Extended JSON 文档
	 * @return 实际插入的 Extended JSON，包含服务端使用的 `_id`
	 * @throws IllegalArgumentException 文档无效或当前写入确认级别不安全
	 */
	public String insertMongoDocument(String database, String collection, String json) {
		BsonDocument document = BsonDocument.parse(json);
		if (!document.containsKey("_id")) {
			document.put("_id", new BsonObjectId(new ObjectId()));
		}
		var documents = mongoClient.getDatabase(database).getCollection(collection, BsonDocument.class);
		if (!documents.getWriteConcern().isAcknowledged()) {
			throw new IllegalArgumentException(databaseMessage(locale, "mongo.edit.write.acknowledged"));
		}
		documents.insertOne(document);
		return document.toJson(EXTENDED_JSON);
	}

	/**
	 * 只删除页面中仍与原始 BSON 完全一致的文档，防止覆盖或删除期间被其他客户端修改的版本。
	 *
	 * @param database 当前数据库
	 * @param collection 当前集合
	 * @param original 页面中显示的 Extended JSON 文档
	 * @return 删除成功时为 true；文档已变化或已被删除时为 false
	 * @throws IllegalArgumentException 原文无效、缺少 `_id` 或写入未确认
	 */
	public boolean deleteMongoDocument(String database, String collection, String original) {
		BsonDocument expected = BsonDocument.parse(original);
		if (!expected.containsKey("_id")) {
			throw new IllegalArgumentException(databaseMessage(locale, "mongo.edit.id.unchanged"));
		}
		var documents = mongoClient.getDatabase(database).getCollection(collection, BsonDocument.class);
		if (!documents.getWriteConcern().isAcknowledged()) {
			throw new IllegalArgumentException(databaseMessage(locale, "mongo.edit.write.acknowledged"));
		}
		return documents.deleteOne(buildMongoExactDocumentFilter(expected),
			new DeleteOptions().collation(Collation.builder().locale("simple").build())).getDeletedCount() == 1;
	}

	/**
	 * 构造按 `_id` 与原始根文档精确匹配的过滤器，用 simple collation 保证字符串值按二进制语义比较。
	 *
	 * @param expected 必须包含 `_id` 的原始 BSON 文档
	 * @return MongoDB 删除或替换操作的单文档冲突过滤器
	 */
	private BsonDocument buildMongoExactDocumentFilter(BsonDocument expected) {
		var id = expected.get("_id");
		var exactMatch = new BsonDocument("$eq", new BsonArray(List.of(
			new BsonString("$$ROOT"), new BsonDocument("$literal", expected))));
		return new BsonDocument("_id", new BsonDocument("$eq", id)).append("$expr", exactMatch);
	}

	/**
	 * 将 UTF-8 Extended JSON Lines 追加到指定集合。所有记录先完成解析和校验。
	 * 单条使用原子插入，多条依赖服务器事务，重复键等写入失败会中止整个事务。
	 *
	 * @param database 目标数据库
	 * @param collection 目标集合
	 * @param path 待导入的新文档文件，每条均须显式声明 _id
	 * @return 完成提交的文档数
	 * @throws IOException 文件读取失败、不是合法 UTF-8 或超出字节上限
	 * @throws IllegalArgumentException 文件没有文档、文档缺少 _id、数量超限或非空行不是单份文档
	 * @see <a href="https://www.mongodb.com/docs/drivers/java/sync/current/crud/transactions/">MongoDB Java 驱动事务</a>
	 */
	public int importMongoJsonLines(String database, String collection, Path path) throws IOException {
		byte[] content;
		try (var input = Files.newInputStream(path)) {
			content = input.readNBytes(MAX_MONGO_IMPORT_BYTES + 1);
		}
		if (content.length > MAX_MONGO_IMPORT_BYTES) {
			throw new IOException(databaseMessage(locale, "mongo.import.bytes"));
		}
		String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString();
		if (text.startsWith("\uFEFF")) {
			text = text.substring(1);
		}
		var lines = text.lines().filter(line -> !line.isBlank()).limit(MAX_MONGO_IMPORT_DOCUMENTS + 1L).toList();
		if (lines.isEmpty() || lines.size() > MAX_MONGO_IMPORT_DOCUMENTS) {
			throw new IllegalArgumentException(databaseMessage(locale, "mongo.import.document.count"));
		}
		var batch = new ArrayList<BsonDocument>(lines.size());
		var codec = new BsonDocumentCodec();
		var decoderContext = DecoderContext.builder().build();
		for (String line : lines) {
			BsonDocument document;
			// 驱动的 BsonDocument.parse 不验证顶层文档后的内容，须读到 EOF 以免静默遗漏记录。
			try (var reader = new JsonReader(line)) {
				document = codec.decode(reader, decoderContext);
				if (reader.readBsonType() != BsonType.END_OF_DOCUMENT) {
					throw new IllegalArgumentException(databaseMessage(locale, "mongo.import.invalid.line"));
				}
			} catch (JsonParseException failure) {
				throw new IllegalArgumentException(databaseMessage(locale, "mongo.import.invalid.line"), failure);
			}
			if (!document.containsKey("_id")) {
				throw new IllegalArgumentException(databaseMessage(locale, "mongo.import.id.required"));
			}
			batch.add(document);
		}

		var documents = mongoClient.getDatabase(database).getCollection(collection, BsonDocument.class);
		if (!documents.getWriteConcern().isAcknowledged()) {
			throw new IllegalArgumentException(databaseMessage(locale, "mongo.edit.write.acknowledged"));
		}
		if (batch.size() == 1) {
			documents.insertOne(batch.getFirst());
		} else {
			var options = TransactionOptions.builder()
				.readPreference(ReadPreference.primary()).writeConcern(documents.getWriteConcern()).build();
			try (var session = mongoClient.startSession()) {
				session.withTransaction(() -> documents.insertMany(session, batch), options);
			}
		}
		return batch.size();
	}

	/**
	 * {@inheritDoc} 关闭客户端的连接池及拓扑监控。
	 */
	@Override
	public void close() {
		mongoClient.close();
	}
}
