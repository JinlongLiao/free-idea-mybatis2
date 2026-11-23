package io.github.jinlongliao.mybatis.workbench.database;

import static io.github.jinlongliao.mybatis.workbench.database.DatabaseMessages.databaseMessage;

import com.google.gson.*;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;

/**
 * 通过 Elasticsearch REST API 校验 HTTPS、浏览索引、搜索和并发安全地维护文档。
 *
 * @author liaojinlong
 * @see <a href="https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-indices-resolve-index">Resolve indices API</a>
 * @see <a href="https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.html">Java 25 HTTP cancellation</a>
 * @see <a href="https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.Builder.html">Java 25 HTTP client TLS settings</a>
 * @since 2026/11/18 15:42
 */
public final class WorkbenchElasticsearchConnection implements AutoCloseable {
	/** 当前连接用于格式化错误消息的宿主语言。 */
	private final Locale locale;

	/**
	 * 单次请求最多读取 8 MiB，防止大量 Mapping 或结果使 IDE 内存失控。
	 */
	private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
	/** 单次 REST 请求（包含响应体）的总等待上限。 */
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
	/** 请求尚未开始；用户取消可以先设置标记阻止稍后发送。 */
	private static final int REQUEST_NOT_STARTED = 0;
	/** 请求正在建立或等待完整响应。 */
	private static final int REQUEST_ACTIVE = 1;
	/** 当前 HTTP 响应已完整处理或请求失败。 */
	private static final int REQUEST_COMPLETED = 2;

	/**
	 * JSON 优先保留用户与服务端的原始字段，仅调整显示缩进。
	 */
	private static final Gson PRETTY_JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/**
	 * HTTPS 入口，保留反向代理配置的上下文路径。
	 */
	private final String baseUrl;

	/**
	 * 当前请求使用的认证头，凭据不拼入 URL。
	 */
	private final String authorization;

	/**
	 * 当前连接可选的私有 CA 或不校验证书链 TLS 上下文；为空时使用 JVM 默认信任库。
	 */
	private final SSLContext sslContext;

	/** 当前请求是否验证证书链和目标主机名。 */
	private final boolean verifyCertificate;
	/** 单连接内复用的客户端，支持 future 取消、总请求超时和有界响应体消费。 */
	private final HttpClient httpClient;
	/** 当前 REST 请求 future；仅当前操作可取消该请求，不代表撤销已到达服务端的写操作。 */
	private final AtomicReference<CompletableFuture<HttpResponse<byte[]>>> activeRequest = new AtomicReference<>();
	/** 取消标记先于 future 发布，覆盖按钮点击与请求创建之间的竞态。 */
	private final AtomicBoolean cancellationRequested = new AtomicBoolean();
	/** 请求生命周期用于区分发送前可取消、网络请求中和响应结束后的状态。 */
	private final AtomicInteger requestLifecycle = new AtomicInteger(REQUEST_NOT_STARTED);

	/**
	 * 建立 Elasticsearch HTTP 客户端。传入私有 CA 时只使用该 CA 文件建立信任。
	 *
	 * @param endpoint HTTPS 服务基础地址，可包含部署路径
	 * @param apiKey   Elasticsearch 返回的 encoded API Key
	 * @param caFile   信任的 PEM 证书链文件，可以为空
	 * @throws GeneralSecurityException CA 文件无法作为 TLS 信任源
	 * @throws IOException              无法读取 CA 文件
	 * @throws IllegalArgumentException 地址或 key 格式无效
	 */
	public WorkbenchElasticsearchConnection(String endpoint, char[] apiKey, Path caFile)
		throws GeneralSecurityException, IOException {
		this(endpoint, apiKey, caFile, true, Locale.getDefault());
	}

	/**
	 * 建立仅使用 API Key 的 HTTPS 客户端，默认校验证书链和主机名。
	 *
	 * @param endpoint HTTPS 服务地址，可包含部署前缀
	 * @param apiKey Elasticsearch 返回的 encoded API Key
	 * @param caFile 可选的私有 CA 证书链文件
	 * @param locale 界面错误提示的语言
	 * @throws IOException 无法读取 CA 文件
	 * @throws GeneralSecurityException 无法初始化 TLS
	 * @throws IllegalArgumentException 地址或认证配置无效
	 */
	public WorkbenchElasticsearchConnection(String endpoint, char[] apiKey, Path caFile, Locale locale)
		throws GeneralSecurityException, IOException {
		this(endpoint, apiKey, caFile, true, locale);
	}

	/**
	 * 建立 Elasticsearch HTTPS 连接；关闭证书校验时仍通过 TLS 加密传输，但不验证证书链和主机名。
	 *
	 * @param endpoint HTTPS 服务地址，可包含部署前缀
	 * @param apiKey Elasticsearch 返回的 encoded API Key
	 * @param caFile 可选的私有 CA 证书链文件，关闭证书校验时不使用
	 * @param verifyCertificate 是否验证服务端证书链和主机名
	 * @param locale 界面错误提示的语言
	 * @throws IOException 无法读取 CA 文件
	 * @throws GeneralSecurityException 无法初始化 TLS
	 * @throws IllegalArgumentException 地址或认证配置无效
	 */
	public WorkbenchElasticsearchConnection(String endpoint, char[] apiKey, Path caFile, boolean verifyCertificate,
		Locale locale) throws GeneralSecurityException, IOException {
		this(endpoint, "", apiKey, caFile, verifyCertificate, locale);
	}

	/**
	 * 建立 Elasticsearch HTTPS 连接，按是否提供用户名选择 Basic 或 encoded API Key 认证。
	 * 凭据仅用于本连接的 Authorization 请求头，不进入地址或错误消息。
	 *
	 * @param endpoint HTTPS 服务地址，可包含部署前缀
	 * @param username Basic 认证用户名；为空时按 API Key 认证
	 * @param secret Basic 密码或 Elasticsearch 返回的 encoded API Key
	 * @param caFile 可选的私有 CA 证书链文件
	 * @param verifyCertificate 是否验证服务端证书链和主机名
	 * @param locale 错误消息使用的语言
	 * @throws IOException 无法读取 CA 文件
	 * @throws GeneralSecurityException 无法初始化 TLS
	 * @throws IllegalArgumentException 地址或认证配置无效
	 */
	public WorkbenchElasticsearchConnection(String endpoint, String username, char[] secret, Path caFile,
		boolean verifyCertificate, Locale locale) throws GeneralSecurityException, IOException {
		this.locale = locale;
		this.verifyCertificate = verifyCertificate;
		URI address = URI.create(endpoint);
		if (!"https".equalsIgnoreCase(address.getScheme()) || Objects.isNull(address.getHost())
			|| Objects.nonNull(address.getRawUserInfo()) || Objects.nonNull(address.getRawQuery())
			|| Objects.nonNull(address.getRawFragment())) {
			throw new IllegalArgumentException(databaseMessage(locale, "search.https.endpoint"));
		}
		if (username.isEmpty()) {
			String value = new String(secret);
			if (!value.matches("[A-Za-z0-9+/=_-]+")) {
				throw new IllegalArgumentException(databaseMessage(locale, "search.encoded.key"));
			}
			authorization = "ApiKey " + value;
		} else {
			if (username.indexOf(':') >= 0 || username.indexOf('\r') >= 0 || username.indexOf('\n') >= 0) {
				throw new IllegalArgumentException(databaseMessage(locale, "search.basic.username"));
			}
			String basic = username + ":" + new String(secret);
			authorization = "Basic " + Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8));
		}
		baseUrl = address.toASCIIString().replaceFirst("/+$", "");
		sslContext = !verifyCertificate ? TlsContexts.createUnverifiedHttpClientTlsContext()
			: Objects.nonNull(caFile) ? loadElasticsearchTrust(caFile) : null;
		HttpClient.Builder clientBuilder = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.followRedirects(HttpClient.Redirect.NEVER)
			.version(HttpClient.Version.HTTP_1_1);
		if (Objects.nonNull(sslContext)) {
			clientBuilder.sslContext(sslContext);
		}
		if (verifyCertificate) {
			SSLParameters sslParameters = new SSLParameters();
			sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
			clientBuilder.sslParameters(sslParameters);
		}
		httpClient = clientBuilder.build();
	}

	/**
	 * 读取当前 key 可见的开放索引与别名。该操作可用来验证 HTTPS 和认证。
	 *
	 * @return 索引和别名名称列表
	 * @throws IOException          请求失败或返回结构无效
	 * @throws InterruptedException 请求被取消
	 */
	public ElasticsearchObjects listElasticsearchObjects() throws IOException, InterruptedException {
		JsonObject result = sendElasticsearchRequest("GET", "/_resolve/index/*?expand_wildcards=open", null);
		return new ElasticsearchObjects(readObjectNames(result, "indices"), readObjectNames(result, "aliases"));
	}

	/**
	 * 读取所选索引或别名可见的 Mapping。
	 *
	 * @param index 待查看的索引或别名
	 * @return 格式化过的 JSON
	 * @throws IOException          请求失败
	 * @throws InterruptedException 请求被取消
	 */
	public String readElasticsearchMapping(String index) throws IOException, InterruptedException {
		return PRETTY_JSON.toJson(sendElasticsearchRequest("GET", "/" + encodeElasticsearchName(index) + "/_mapping", null));
	}

	/**
	 * 执行 Query DSL 搜索。请求最多允许返回 1000 个命中文档。
	 *
	 * @param index 目标索引或别名
	 * @param dsl   包含 query 等搜索参数的 JSON 对象
	 * @return 格式化过的 Elasticsearch 搜索响应
	 * @throws IOException              请求失败
	 * @throws InterruptedException     请求被取消
	 * @throws IllegalArgumentException 搜索 JSON 或 size 无效
	 */
	public String searchElasticsearchDocuments(String index, String dsl) throws IOException, InterruptedException {
		JsonObject query;
		try {
			query = JsonParser.parseString(dsl).getAsJsonObject();
			if (!query.has("stored_fields") && !query.has("_source")) {
				query.addProperty("_source", true);
			}
			ensureRoutingStoredField(query);
			if (query.has("size")) {
				int size = query.get("size").getAsBigDecimal().intValueExact();
				if (size < 0 || size > 1000) {
					throw new IllegalArgumentException(databaseMessage(locale, "search.size.bounds"));
				}
			}
		} catch (RuntimeException failure) {
			throw new IllegalArgumentException(databaseMessage(locale, "search.invalid.query"), failure);
		}
		return PRETTY_JSON.toJson(sendElasticsearchRequest("POST", "/" + encodeElasticsearchName(index)
			+ "/_search?seq_no_primary_term=true", query.toString()));
	}

	/**
	 * 搜索命中需要携带 routing，后续按精确命中执行 GET/PUT/DELETE 时才能访问原分片；保留调用方请求的其他 stored fields。
	 *
	 * @param query 已解析的 Query DSL 对象
	 * @throws IllegalArgumentException stored_fields 不是字符串或字符串数组
	 * @see <a href="https://www.elastic.co/guide/en/elasticsearch/reference/8.19/search-fields.html">Elasticsearch 8.19 search fields</a>
	 */
	private void ensureRoutingStoredField(JsonObject query) {
		JsonArray storedFields = new JsonArray();
		if (query.has("stored_fields")) {
			JsonElement configuredFields = query.get("stored_fields");
			if (configuredFields.isJsonArray()) {
				for (JsonElement field : configuredFields.getAsJsonArray()) {
					if (!field.isJsonPrimitive() || !field.getAsJsonPrimitive().isString()) {
						throw new IllegalArgumentException(databaseMessage(locale, "search.invalid.query"));
					}
					storedFields.add(field);
				}
			} else if (configuredFields.isJsonPrimitive() && configuredFields.getAsJsonPrimitive().isString()) {
				for (String field : configuredFields.getAsString().split(",", -1)) {
					storedFields.add(field.trim());
				}
			} else {
				throw new IllegalArgumentException(databaseMessage(locale, "search.invalid.query"));
			}
		}
		for (JsonElement field : storedFields) {
			if ("_none_".equals(field.getAsString())) {
				return;
			}
		}
		for (JsonElement field : storedFields) {
			if ("_routing".equals(field.getAsString())) {
				query.add("stored_fields", storedFields);
				return;
			}
		}
		storedFields.add("_routing");
		query.add("stored_fields", storedFields);
	}

	/**
	 * 向所选索引新增 JSON 文档，由 Elasticsearch 生成文档 ID，并等待 refresh 后返回结果。
	 *
	 * @param index 目标索引或别名
	 * @param json 文档对象 JSON
	 * @return 包含新文档 ID 的 Elasticsearch 响应
	 * @throws IOException 请求失败或 JSON 超出限制
	 * @throws InterruptedException 请求被取消
	 * @see <a href="https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-index">Elasticsearch Index API</a>
	 */
	public String indexElasticsearchDocument(String index, String json) throws IOException, InterruptedException {
		JsonObject document = parseElasticsearchDocument(json);
		return PRETTY_JSON.toJson(sendElasticsearchRequest("POST", "/" + encodeElasticsearchName(index)
			+ "/_doc?refresh=wait_for", document.toString()));
	}

	/**
	 * 按文档 ID 读取完整 `_source` 和并发令牌，用于在编辑前不受搜索 DSL 字段裁剪影响。
	 *
	 * @param index 目标索引或别名
	 * @param id 搜索命中中的文档 ID
	 * @return 文档正文、seq_no 和 primary_term；若索引禁用了 `_source`，响应不会包含正文
	 * @throws IOException 请求失败
	 * @throws InterruptedException 请求被取消
	 */
	public String readElasticsearchDocumentForUpdate(String index, String id) throws IOException, InterruptedException {
		return readElasticsearchDocumentForUpdate(index, id, null);
	}

	/**
	 * 使用搜索命中对应的具体索引、ID 和可选 routing 读取完整文档及版本令牌。
	 *
	 * @param index 搜索命中的具体 `_index`，不能传多索引别名
	 * @param id 搜索命中的 `_id`
	 * @param routing 搜索命中的可选 `_routing`
	 * @return 带完整 `_source` 与并发版本的响应
	 * @throws IOException 请求失败
	 * @throws InterruptedException 请求被取消
	 * @see <a href="https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-get">Elasticsearch Get API</a>
	 */
	public String readElasticsearchDocumentForUpdate(String index, String id, String routing)
		throws IOException, InterruptedException {
		String route = routingQueryParameter(routing);
		return PRETTY_JSON.toJson(sendElasticsearchRequest("GET", "/" + encodeElasticsearchName(index)
			+ "/_doc/" + encodeElasticsearchName(id) + (route.isEmpty() ? "" : "?" + route.substring(1)), null));
	}

	/**
	 * 仅当搜索时读取的 seq_no 与 primary_term 未变化时替换完整文档。
	 *
	 * @param index 目标索引或别名
	 * @param id 搜索结果中的文档 ID
	 * @param sequenceNumber 搜索结果中的 seq_no
	 * @param primaryTerm 搜索结果中的 primary_term
	 * @param json 新文档对象 JSON
	 * @return Elasticsearch 更新响应；并发冲突以 HTTP 409 明确返回
	 * @throws IOException 请求失败、JSON 无效或发生并发冲突
	 * @throws InterruptedException 请求被取消
	 */
	public String replaceElasticsearchDocument(String index, String id, long sequenceNumber, long primaryTerm,
		String json) throws IOException, InterruptedException {
		return replaceElasticsearchDocument(index, id, null, sequenceNumber, primaryTerm, json);
	}

	/**
	 * 按搜索命中的具体索引与 routing 原子替换完整文档，routing 缺失时沿用 Elasticsearch 默认路由。
	 *
	 * @param index 搜索命中的具体 `_index`
	 * @param id 搜索命中的 `_id`
	 * @param routing 搜索命中的可选 `_routing`
	 * @param sequenceNumber 搜索或 GET 返回的 `_seq_no`
	 * @param primaryTerm 搜索或 GET 返回的 `_primary_term`
	 * @param json 完整 JSON 对象正文
	 * @return Elasticsearch 更新响应
	 * @throws IOException 请求失败或并发令牌已变化
	 * @throws InterruptedException 请求被取消
	 * @see <a href="https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-index">Elasticsearch Index API conditional writes</a>
	 */
	public String replaceElasticsearchDocument(String index, String id, String routing, long sequenceNumber,
		long primaryTerm, String json) throws IOException, InterruptedException {
		JsonObject document = parseElasticsearchDocument(json);
		String path = "/" + encodeElasticsearchName(index) + "/_doc/" + encodeElasticsearchName(id)
			+ "?if_seq_no=" + requireNonNegativeToken(sequenceNumber) + "&if_primary_term="
			+ requireNonNegativeToken(primaryTerm) + "&refresh=wait_for" + routingQueryParameter(routing);
		return PRETTY_JSON.toJson(sendElasticsearchRequest("PUT", path, document.toString()));
	}

	/**
	 * 仅当搜索时读取的 seq_no 与 primary_term 未变化时删除单个文档。
	 *
	 * @param index 目标索引或别名
	 * @param id 搜索结果中的文档 ID
	 * @param sequenceNumber 搜索结果中的 seq_no
	 * @param primaryTerm 搜索结果中的 primary_term
	 * @return Elasticsearch 删除响应；并发冲突以 HTTP 409 明确返回
	 * @throws IOException 请求失败或发生并发冲突
	 * @throws InterruptedException 请求被取消
	 */
	public String deleteElasticsearchDocument(String index, String id, long sequenceNumber, long primaryTerm)
		throws IOException, InterruptedException {
		return deleteElasticsearchDocument(index, id, null, sequenceNumber, primaryTerm);
	}

	/**
	 * 按搜索命中的具体索引与 routing 原子删除单个文档。
	 *
	 * @param index 搜索命中的具体 `_index`
	 * @param id 搜索命中的 `_id`
	 * @param routing 搜索命中的可选 `_routing`
	 * @param sequenceNumber 搜索命中的 `_seq_no`
	 * @param primaryTerm 搜索命中的 `_primary_term`
	 * @return Elasticsearch 删除响应
	 * @throws IOException 请求失败或并发令牌已变化
	 * @throws InterruptedException 请求被取消
	 * @see <a href="https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-delete">Elasticsearch Delete API</a>
	 */
	public String deleteElasticsearchDocument(String index, String id, String routing, long sequenceNumber,
		long primaryTerm) throws IOException, InterruptedException {
		String path = "/" + encodeElasticsearchName(index) + "/_doc/" + encodeElasticsearchName(id)
			+ "?if_seq_no=" + requireNonNegativeToken(sequenceNumber) + "&if_primary_term="
			+ requireNonNegativeToken(primaryTerm) + "&refresh=wait_for" + routingQueryParameter(routing);
		return PRETTY_JSON.toJson(sendElasticsearchRequest("DELETE", path, null));
	}

	/**
	 * 编码仅在搜索命中中存在时添加的 routing 查询参数。
	 *
	 * @param routing Elasticsearch 文档 routing
	 * @return 空字符串或 URL 编码参数
	 */
	private String routingQueryParameter(String routing) {
		return Objects.isNull(routing) || routing.isEmpty() ? ""
			: "&routing=" + URLEncoder.encode(routing, StandardCharsets.UTF_8).replace("+", "%20");
	}

	/**
	 * 串行发送 Elasticsearch API 请求，避免同一短连接并发发出多个操作；取消入口不获取此锁，可在后台取消 future。
	 * 调用者传入的路径必须是固定 API 或经过编码的单个路径段。
	 *
	 * @param method GET、POST、PUT 或 DELETE
	 * @param path API 路径
	 * @param json 可选 JSON 请求体
	 * @return Elasticsearch JSON 对象
	 * @throws IOException          HTTP、响应大小或 JSON 解析失败
	 * @throws InterruptedException 请求被取消
	 */
	private synchronized JsonObject sendElasticsearchRequest(String method, String path, String json) throws IOException, InterruptedException {
		requestLifecycle.set(REQUEST_ACTIVE);
		try {
			return sendElasticsearchRequestInternal(method, path, json);
		} finally {
			requestLifecycle.set(REQUEST_COMPLETED);
		}
	}

	/**
	 * 执行当前已登记生命周期内的异步 HTTP 请求；同步等待处于后台操作线程，future 可由取消入口并发终止。
	 *
	 * @param method HTTP 方法
	 * @param path 已固定或编码的 API 路径
	 * @param json 可选 JSON 请求正文
	 * @return Elasticsearch JSON 响应对象
	 * @throws IOException HTTP、响应大小或 JSON 解析失败
	 * @throws InterruptedException 当前 future 被取消或等待线程中断
	 */
	private JsonObject sendElasticsearchRequestInternal(String method, String path, String json)
		throws IOException, InterruptedException {
		if (cancellationRequested.get()) {
			throw new InterruptedException(databaseMessage(locale, "search.request.cancelled"));
		}
		HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(baseUrl + path))
			.header("Authorization", authorization)
			.header("Accept", "application/json");
		if (Objects.nonNull(json)) {
			requestBuilder.header("Content-Type", "application/json");
			requestBuilder.method(method, HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
		} else {
			requestBuilder.method(method, HttpRequest.BodyPublishers.noBody());
		}
		HttpRequest request = requestBuilder.build();
		AtomicBoolean responseLimitExceeded = new AtomicBoolean();
		CompletableFuture<HttpResponse<byte[]>> requestFuture = httpClient.sendAsync(request, responseInfo ->
			new ElasticsearchResponseBodySubscriber(MAX_RESPONSE_BYTES + 1,
				() -> responseLimitExceeded.set(true)));
		if (!activeRequest.compareAndSet(null, requestFuture)) {
			requestFuture.cancel(true);
			throw new IOException(databaseMessage(locale, "search.request.concurrent"));
		}
		if (cancellationRequested.get()) {
			requestFuture.cancel(true);
		}
		try {
			HttpResponse<byte[]> response = requestFuture.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			int statusCode = response.statusCode();
			byte[] bytes = response.body();
			if (bytes.length > MAX_RESPONSE_BYTES) {
				throw new IOException(databaseMessage(locale, "search.response.limit"));
			}
			String data = new String(bytes, StandardCharsets.UTF_8);
			if (statusCode < 200 || statusCode >= 300) {
				throw new IOException(databaseMessage(locale, "search.http.status", Integer.toString(statusCode), data));
			}
			try {
				return JsonParser.parseString(data).getAsJsonObject();
			} catch (RuntimeException failure) {
				throw new IOException(databaseMessage(locale, "search.response.object"), failure);
			}
		} catch (TimeoutException failure) {
			requestFuture.cancel(true);
			throw new IOException(databaseMessage(locale, "search.request.timeout"), failure);
		} catch (InterruptedException failure) {
			requestFuture.cancel(true);
			throw failure;
		} catch (CancellationException failure) {
			InterruptedException cancelled = new InterruptedException(databaseMessage(locale, "search.request.cancelled"));
			cancelled.initCause(failure);
			throw cancelled;
		} catch (ExecutionException failure) {
			Throwable cause = failure.getCause();
			if (responseLimitExceeded.get()) {
				throw new IOException(databaseMessage(locale, "search.response.limit"), cause);
			}
			if (cancellationRequested.get() || cause instanceof CancellationException) {
				InterruptedException cancelled = new InterruptedException(databaseMessage(locale, "search.request.cancelled"));
				cancelled.initCause(cause);
				throw cancelled;
			}
			if (cause instanceof IOException ioFailure) {
				throw ioFailure;
			}
			throw new IOException(databaseMessage(locale, "search.request.failed"), cause);
		} finally {
			activeRequest.compareAndSet(requestFuture, null);
		}
	}

	/**
	 * 请求取消当前 REST 请求。future 发布前的取消标记会阻止后续请求继续发送。
	 * 仅用于读取取消或关闭客户端；若服务端已收到写请求，此方法不代表撤销该写入。
	 *
	 * @return true 表示本次调用首次记录了取消请求，包含 future 尚未发布的时序
	 */
	public boolean cancelCurrentRequest() {
		CompletableFuture<HttpResponse<byte[]>> requestFuture = activeRequest.get();
		if (Objects.nonNull(requestFuture)) {
			return requestFuture.cancel(true);
		}
		return requestLifecycle.get() != REQUEST_COMPLETED && cancellationRequested.compareAndSet(false, true);
	}

	/**
	 * 限制写入为不超过 1 MiB 的 JSON 对象，避免误将 DSL 数组或超大正文提交到索引。
	 *
	 * @param json 用户提供的文档 JSON
	 * @return 解析后的 JSON 对象
	 * @throws IllegalArgumentException 非对象 JSON 或超出长度上限
	 */
	private JsonObject parseElasticsearchDocument(String json) {
		if (json.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) {
			throw new IllegalArgumentException(databaseMessage(locale, "search.document.size"));
		}
		try {
			return JsonParser.parseString(json).getAsJsonObject();
		} catch (RuntimeException failure) {
			throw new IllegalArgumentException(databaseMessage(locale, "search.invalid.document"), failure);
		}
	}

	/**
	 * 检查服务端并发令牌非负，避免把自由文本拼接进 URL 参数。
	 *
	 * @param token Elasticsearch 返回的序号令牌
	 * @return 已校验令牌
	 * @throws IllegalArgumentException 令牌为负数
	 */
	private String requireNonNegativeToken(long token) {
		if (token < 0) {
			throw new IllegalArgumentException(databaseMessage(locale, "search.document.token"));
		}
		return Long.toString(token);
	}

	/**
	 * 抽取 Resolve Index 的名称数组，并明确区分缺失字段和返回空数组。
	 *
	 * @param result Resolve Index 响应
	 * @param field  indices 或 aliases 字段
	 * @return 名称列表
	 * @throws IOException 字段或名称不符合 Elasticsearch API 响应契约
	 */
	private List<String> readObjectNames(JsonObject result, String field) throws IOException {
		try {
			var values = result.getAsJsonArray(field);
			List<String> names = new ArrayList<>(values.size());
			for (JsonElement value : values) {
				names.add(value.getAsJsonObject().get("name").getAsString());
			}
			return List.copyOf(names);
		} catch (RuntimeException failure) {
			throw new IOException(databaseMessage(locale, "search.missing.field", field), failure);
		}
	}

	/**
	 * 构造索引路径段，索引名中的保留字符不会改变请求路径或参数。
	 *
	 * @param index 索引或别名
	 * @return URI 编码后的路径段
	 */
	private String encodeElasticsearchName(String index) {
		if (index.isBlank() || ".".equals(index) || "..".equals(index)) {
			throw new IllegalArgumentException(databaseMessage(locale, "search.select.target"));
		}
		return URLEncoder.encode(index, StandardCharsets.UTF_8).replace("+", "%20");
	}

	/**
	 * 由给定 PEM 文件构造独立信任库，不改变 IDE 的全局 TLS 设置。
	 *
	 * @param caFile CA PEM 文件
	 * @return 仅对当前 HTTP 客户端生效的 TLS 上下文
	 * @throws IOException              无法读取文件
	 * @throws GeneralSecurityException 证书或信任库初始化失败
	 */
	private SSLContext loadElasticsearchTrust(Path caFile) throws IOException, GeneralSecurityException {
		var factory = CertificateFactory.getInstance("X.509");
		var store = KeyStore.getInstance(KeyStore.getDefaultType());
		store.load(null, null);
		try (var input = Files.newInputStream(caFile)) {
			var certificates = factory.generateCertificates(input);
			if (certificates.isEmpty()) {
				throw new GeneralSecurityException(databaseMessage(locale, "search.ca.certificate"));
			}
			int index = 0;
			for (var certificate : certificates) {
				store.setCertificateEntry("elasticsearch-ca-" + index++, certificate);
			}
		}
		var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		trust.init(store);
		var context = SSLContext.getInstance("TLS");
		context.init(null, trust.getTrustManagers(), null);
		return context;
	}

	/**
	 * {@inheritDoc} 连接客户端只服务于当前操作；关闭时释放客户端线程和仍存活的网络交换。
	 */
	@Override
	public void close() {
		httpClient.close();
	}
}
