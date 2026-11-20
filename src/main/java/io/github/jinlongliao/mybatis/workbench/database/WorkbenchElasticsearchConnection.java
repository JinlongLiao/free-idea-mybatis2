package io.github.jinlongliao.mybatis.workbench.database;

import static io.github.jinlongliao.mybatis.workbench.database.DatabaseMessages.databaseMessage;

import com.google.gson.*;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * 通过 Elasticsearch REST API 校验 HTTPS、浏览索引和执行只读搜索。
 *
 * @author liaojinlong
 * @see <a href="https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-indices-resolve-index">Resolve indices API</a>
 * @since 2026/11/18 15:42
 */
public final class WorkbenchElasticsearchConnection implements AutoCloseable {
	/** 当前连接用于格式化错误消息的宿主语言。 */
	private final Locale locale;

	/**
	 * 单次请求最多读取 8 MiB，防止大量 Mapping 或结果使 IDE 内存失控。
	 */
	private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

	/**
	 * JSON 优先保留用户与服务端的原始字段，仅调整显示缩进。
	 */
	private static final Gson PRETTY_JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/**
	 * HTTPS 入口，保留反向代理配置的上下文路径。
	 */
	private final String baseUrl;

	/**
	 * 当前请求使用的认证头，API Key 不拼入 URL。
	 */
	private final String authorization;

	/**
	 * 针对本连接的 HTTPS 客户端，始终校验证书和主机名。
	 */
	private final HttpClient httpClient;

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
		this(endpoint, apiKey, caFile, Locale.getDefault());
	}

	/**
	 * 建立 Elasticsearch HTTP 客户端。传入私有 CA 时只使用该 CA 文件建立信任。
	 *
	 * @param endpoint HTTPS 服务基础地址，可包含部署路径
	 * @param apiKey   Elasticsearch 返回的 encoded API Key
	 * @param caFile   信任的 PEM 证书链文件，可以为空
	 * @param locale 宿主界面语言，用于此连接生成的错误消息
	 * @throws GeneralSecurityException CA 文件无法作为 TLS 信任源
	 * @throws IOException              无法读取 CA 文件
	 * @throws IllegalArgumentException 地址或 key 格式无效
	 */
	public WorkbenchElasticsearchConnection(String endpoint, char[] apiKey, Path caFile, Locale locale)
		throws GeneralSecurityException, IOException {
		this.locale = locale;
		URI address = URI.create(endpoint);
		if (!"https".equalsIgnoreCase(address.getScheme()) || Objects.isNull(address.getHost())
			|| Objects.nonNull(address.getRawUserInfo()) || Objects.nonNull(address.getRawQuery())
			|| Objects.nonNull(address.getRawFragment())) {
			throw new IllegalArgumentException(databaseMessage(locale, "search.https.endpoint"));
		}
		String encoded = new String(apiKey);
		if (!encoded.matches("[A-Za-z0-9+/=_-]+")) {
			throw new IllegalArgumentException(databaseMessage(locale, "search.encoded.key"));
		}
		baseUrl = address.toASCIIString().replaceFirst("/+$", "");
		authorization = "ApiKey " + encoded;
		var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
			.followRedirects(HttpClient.Redirect.NEVER);
		if (Objects.nonNull(caFile)) {
			client.sslContext(loadElasticsearchTrust(caFile));
		}
		httpClient = client.build();
	}

	/**
	 * 读取当前 key 可见的开放索引与别名。该操作可用来验证 HTTPS 和认证。
	 *
	 * @return 索引和别名名称列表
	 * @throws IOException          请求失败或返回结构无效
	 * @throws InterruptedException 请求被取消
	 */
	public ElasticsearchObjects listElasticsearchObjects() throws IOException, InterruptedException {
		JsonObject result = sendElasticsearchRequest("/_resolve/index/*?expand_wildcards=open", null);
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
		return PRETTY_JSON.toJson(sendElasticsearchRequest("/" + encodeElasticsearchName(index) + "/_mapping", null));
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
			if (query.has("size")) {
				int size = query.get("size").getAsBigDecimal().intValueExact();
				if (size < 0 || size > 1000) {
					throw new IllegalArgumentException(databaseMessage(locale, "search.size.bounds"));
				}
			}
		} catch (RuntimeException failure) {
			throw new IllegalArgumentException(databaseMessage(locale, "search.invalid.query"), failure);
		}
		return PRETTY_JSON.toJson(sendElasticsearchRequest("/" + encodeElasticsearchName(index) + "/_search",
			query.toString()));
	}

	/**
	 * 发送 GET 或查询 POST。调用者传入的路径必须是固定 API 或经过编码的单个索引段。
	 *
	 * @param path API 路径
	 * @param json 查询体，为空时使用 GET
	 * @return Elasticsearch JSON 对象
	 * @throws IOException          HTTP、响应大小或 JSON 解析失败
	 * @throws InterruptedException 请求被取消
	 */
	private JsonObject sendElasticsearchRequest(String path, String json) throws IOException, InterruptedException {
		var request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(20))
			.header("Authorization", authorization).header("Accept", "application/json");
		if (Objects.nonNull(json)) {
			request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
		}
		var result = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
		try (var body = result.body()) {
			byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
			if (bytes.length > MAX_RESPONSE_BYTES) {
				throw new IOException(databaseMessage(locale, "search.response.limit"));
			}
			String data = new String(bytes, StandardCharsets.UTF_8);
			if (result.statusCode() < 200 || result.statusCode() >= 300) {
				throw new IOException(databaseMessage(locale, "search.http.status", Integer.toString(result.statusCode()), data));
			}
			try {
				return JsonParser.parseString(data).getAsJsonObject();
			} catch (RuntimeException failure) {
				throw new IOException(databaseMessage(locale, "search.response.object"), failure);
			}
		}
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
	 * {@inheritDoc} 关闭该 HTTP 客户端和底层连接。
	 */
	@Override
	public void close() {
		httpClient.close();
	}
}
