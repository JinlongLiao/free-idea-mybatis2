package io.github.jinlongliao.mybatis.workbench.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.Locale;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证 Elasticsearch HTTPS/TLS 行为、查询取消和响应大小上限。
 *
 * @author liaojinlong
 * @since 2026/11/21 23:00
 */
class WorkbenchElasticsearchConnectionTest {
	/** 本次测试独占目录，保存临时服务端证书库。 */
	@TempDir
	Path temporaryDirectory;

	/**
	 * 只在关闭证书校验时接受自签名且主机名不匹配的 HTTPS 服务，并继续拒绝 HTTP 地址。
	 *
	 * @throws Exception 测试服务或证书初始化失败
	 * @see <a href="https://docs.oracle.com/en/java/javase/25/docs/api/java.base/javax/net/ssl/HttpsURLConnection.html">Java 25 per-connection hostname verifier</a>
	 */
	@Test
	void skipsCertificateIdentityChecksOnlyWhenConfigured() throws Exception {
		Path keyStorePath = temporaryDirectory.resolve("elasticsearch-test.p12");
		String password = "test-only-password";
		Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
			"-genkeypair", "-noprompt", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
			"-dname", "CN=wrong.example", "-ext", "SAN=dns:wrong.example", "-storetype", "PKCS12", "-keystore",
			keyStorePath.toString(), "-storepass", password, "-keypass", password).redirectErrorStream(true).start();
		byte[] keytoolOutput = keytool.getInputStream().readAllBytes();
		if (keytool.waitFor() != 0) {
			throw new IOException("生成测试证书失败：" + new String(keytoolOutput));
		}
		KeyStore keyStore = KeyStore.getInstance("PKCS12");
		try (var input = Files.newInputStream(keyStorePath)) {
			keyStore.load(input, password.toCharArray());
		}
		KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		keyManagers.init(keyStore, password.toCharArray());
		SSLContext serverContext = SSLContext.getInstance("TLS");
		serverContext.init(keyManagers.getKeyManagers(), null, null);
		HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
		server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
		AtomicReference<String> authorization = new AtomicReference<>();
		AtomicReference<String> request = new AtomicReference<>();
		CountDownLatch partialResponseSent = new CountDownLatch(1);
		CountDownLatch releasePartialResponse = new CountDownLatch(1);
		server.createContext("/", exchange -> {
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			request.set(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " " + body);
			int status = 200;
			String responseText = exchange.getRequestURI().getPath().contains("/_doc/")
				? "{\"_index\":\"sample-index\",\"_id\":\"doc/1\",\"_seq_no\":4,\"_primary_term\":2,"
					+ "\"_source\":{\"name\":\"full\"}}"
				: "{\"indices\":[],\"aliases\":[],\"result\":\"updated\"}";
			if (body.contains("oversized-response")) {
				responseText = "x".repeat(8 * 1024 * 1024 + 2);
			}
			if (body.contains("conflict")) {
				status = 409;
				responseText = "{\"error\":\"version_conflict_engine_exception\"}";
			}
			byte[] response = responseText.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			if (body.contains("stall-response")) {
				exchange.sendResponseHeaders(200, 0);
				try (var output = exchange.getResponseBody()) {
					output.write('{');
					output.flush();
					partialResponseSent.countDown();
					try {
						releasePartialResponse.await(10, TimeUnit.SECONDS);
					} catch (InterruptedException failure) {
						Thread.currentThread().interrupt();
					}
					output.write("\"indices\":[],\"aliases\":[]}".getBytes(StandardCharsets.UTF_8));
				} catch (IOException expectedClientCancellation) {
					// 客户端取消会关闭 HTTP/1.1 响应流；该断开是此用例的预期结果。
				}
				return;
			}
			exchange.sendResponseHeaders(status, response.length);
			try (var output = exchange.getResponseBody()) {
				output.write(response);
			}
		});
		server.start();
		try {
			String endpoint = "https://127.0.0.1:" + server.getAddress().getPort();
			assertThrows(IOException.class, () -> {
				try (var connection = new WorkbenchElasticsearchConnection(endpoint, "dGVzdA==".toCharArray(), null,
					true, Locale.ENGLISH)) {
					connection.listElasticsearchObjects();
				}
			});
			try (var connection = new WorkbenchElasticsearchConnection(endpoint, "dGVzdA==".toCharArray(), null,
				false, Locale.ENGLISH)) {
				assertTrue(connection.cancelCurrentRequest());
				assertThrows(InterruptedException.class, connection::listElasticsearchObjects);
				assertNull(request.get());
			}
			try (var connection = new WorkbenchElasticsearchConnection(endpoint, "dGVzdA==".toCharArray(), null,
				false, Locale.ENGLISH)) {
				ElasticsearchObjects objects = connection.listElasticsearchObjects();
				assertEquals(List.of(), objects.indices());
				assertEquals(List.of(), objects.aliases());
				assertEquals("ApiKey dGVzdA==", authorization.get());
			}
			String expectedBasic = "Basic " + Base64.getEncoder().encodeToString(
				"alice:päss".getBytes(StandardCharsets.UTF_8));
			try (var connection = new WorkbenchElasticsearchConnection(endpoint, "alice", "päss".toCharArray(), null,
				false, Locale.ENGLISH)) {
				connection.listElasticsearchObjects();
				assertEquals(expectedBasic, authorization.get());
				String documentPage = "{\"query\":{\"term\":{\"status\":\"ready\"}},"
					+ "\"sort\":[{\"created_at\":\"desc\"}],\"from\":50,\"size\":50}";
				connection.searchElasticsearchDocuments("search-alias", documentPage);
				assertEquals("POST /search-alias/_search?seq_no_primary_term=true "
					+ documentPage.substring(0, documentPage.length() - 1)
					+ ",\"_source\":true,\"stored_fields\":[\"_routing\"]}", request.get());
				connection.searchElasticsearchDocuments("search-alias", "{\"_source\":false,\"size\":1}");
				assertEquals("POST /search-alias/_search?seq_no_primary_term=true "
					+ "{\"_source\":false,\"size\":1,\"stored_fields\":[\"_routing\"]}",
					request.get());
				connection.searchElasticsearchDocuments("search-alias",
					"{\"_source\":{\"includes\":[\"name\"]},\"stored_fields\":[\"title\"],\"size\":1}");
				assertEquals("POST /search-alias/_search?seq_no_primary_term=true "
					+ "{\"_source\":{\"includes\":[\"name\"]},\"stored_fields\":[\"title\",\"_routing\"],\"size\":1}",
					request.get());
				connection.searchElasticsearchDocuments("search-alias", "{\"size\":1}");
				assertEquals("POST /search-alias/_search?seq_no_primary_term=true "
					+ "{\"size\":1,\"_source\":true,\"stored_fields\":[\"_routing\"]}", request.get());
				assertFalse(connection.cancelCurrentRequest());
				connection.searchElasticsearchDocuments("search-alias",
					"{\"_source\":false,\"stored_fields\":\"title,_routing\",\"size\":1}");
				assertEquals("POST /search-alias/_search?seq_no_primary_term=true "
					+ "{\"_source\":false,\"stored_fields\":[\"title\",\"_routing\"],\"size\":1}", request.get());
				connection.searchElasticsearchDocuments("search-alias",
					"{\"_source\":false,\"stored_fields\":[\"_none_\"],\"size\":1}");
				assertEquals("POST /search-alias/_search?seq_no_primary_term=true "
					+ "{\"_source\":false,\"stored_fields\":[\"_none_\"],\"size\":1}", request.get());
				connection.searchElasticsearchDocuments("search-alias",
					"{\"_source\":false,\"stored_fields\":\"title,_none_\",\"size\":1}");
				assertEquals("POST /search-alias/_search?seq_no_primary_term=true "
					+ "{\"_source\":false,\"stored_fields\":\"title,_none_\",\"size\":1}", request.get());
				String fullDocument = connection.readElasticsearchDocumentForUpdate("sample-index", "doc/1", "tenant/a");
				assertEquals("full", JsonParser.parseString(fullDocument).getAsJsonObject()
					.getAsJsonObject("_source").get("name").getAsString());
				assertEquals("GET /sample-index/_doc/doc%2F1?routing=tenant%2Fa ", request.get());
				connection.indexElasticsearchDocument("sample-index", "{\"name\":\"new\"}");
				assertEquals("POST /sample-index/_doc?refresh=wait_for {\"name\":\"new\"}", request.get());
				connection.replaceElasticsearchDocument("sample-index", "doc/1", "tenant/a", 4, 2,
					"{\"name\":\"changed\"}");
				assertEquals("PUT /sample-index/_doc/doc%2F1?if_seq_no=4&if_primary_term=2&refresh=wait_for"
					+ "&routing=tenant%2Fa {\"name\":\"changed\"}", request.get());
				connection.deleteElasticsearchDocument("sample-index", "doc/1", "tenant/a", 4, 2);
				assertEquals("DELETE /sample-index/_doc/doc%2F1?if_seq_no=4&if_primary_term=2&refresh=wait_for"
					+ "&routing=tenant%2Fa ",
					request.get());
				IOException conflict = assertThrows(IOException.class, () -> connection.replaceElasticsearchDocument(
					"sample-index", "doc/1", "tenant/a", 4, 2, "{\"name\":\"conflict\"}"));
				assertTrue(conflict.getMessage().contains("409"));
				IOException oversized = assertThrows(IOException.class, () -> connection.searchElasticsearchDocuments(
					"sample-index", "{\"query\":{\"term\":{\"name\":\"oversized-response\"}}}"));
				assertEquals(DatabaseMessages.databaseMessage(Locale.ENGLISH, "search.response.limit"), oversized.getMessage());

				ExecutorService searchExecutor = Executors.newSingleThreadExecutor();
				try {
					Future<String> pendingSearch = searchExecutor.submit(() -> connection.searchElasticsearchDocuments(
						"sample-index", "{\"query\":{\"term\":{\"name\":\"stall-response\"}}}"));
					assertTrue(partialResponseSent.await(5, TimeUnit.SECONDS));
					assertTrue(connection.cancelCurrentRequest());
					ExecutionException cancelled = assertThrows(ExecutionException.class,
						() -> pendingSearch.get(2, TimeUnit.SECONDS));
					assertTrue(cancelled.getCause() instanceof InterruptedException);
				} finally {
					releasePartialResponse.countDown();
					searchExecutor.shutdownNow();
				}
			}
			assertThrows(IllegalArgumentException.class, () -> new WorkbenchElasticsearchConnection(endpoint, "bad:user",
				"secret".toCharArray(), null, false, Locale.ENGLISH));
			try (var connection = new WorkbenchElasticsearchConnection(endpoint, "alice", "secret".toCharArray(), null,
				false, Locale.ENGLISH)) {
				assertThrows(IllegalArgumentException.class,
					() -> connection.indexElasticsearchDocument("sample-index", "[]"));
				assertThrows(IllegalArgumentException.class,
					() -> connection.replaceElasticsearchDocument("sample-index", "id", -1, 1, "{}"));
			}
			assertThrows(IllegalArgumentException.class, () -> new WorkbenchElasticsearchConnection(
				"http://127.0.0.1:" + server.getAddress().getPort(), "dGVzdA==".toCharArray(), null, false, Locale.ENGLISH));
		} finally {
			server.stop(0);
		}
	}
}
