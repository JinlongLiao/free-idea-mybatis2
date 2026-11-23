package io.github.jinlongliao.mybatis.workbench.database;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.net.Socket;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 创建数据源显式选择跳过证书链校验时使用的 TLS 上下文；调用方必须将其限定在单个连接客户端内。
 *
 * @author liaojinlong
 * @since 2026/11/21 23:07
 */
public final class TlsContexts {
	/** 工具类型不创建实例。 */
	private TlsContexts() {
	}

	/**
	 * 初始化继续加密传输但不验证对端证书链的 TLS 上下文。
	 *
	 * @return 供单个数据源客户端使用的 TLS 上下文
	 * @throws GeneralSecurityException TLS 提供者无法初始化
	 */
	public static SSLContext createUnverifiedTlsContext() throws GeneralSecurityException {
		X509TrustManager trustManager = new X509TrustManager() {
			/** {@inheritDoc} 当前客户端由数据源设置显式关闭对端证书链校验。 */
			@Override
			public void checkClientTrusted(X509Certificate[] chain, String authType) {
			}

			/** {@inheritDoc} TLS 会话仍然加密，但此设置不确认对端证书身份。 */
			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType) {
			}

			/** {@inheritDoc} 当前上下文不维护受信任证书清单。 */
			@Override
			public X509Certificate[] getAcceptedIssuers() {
				return new X509Certificate[0];
			}
		};
		SSLContext context = SSLContext.getInstance("TLS");
		context.init(null, new TrustManager[]{trustManager}, new SecureRandom());
		return context;
	}

	/**
	 * 为单个 JDK HttpClient 创建显式不校验证书链和主机名的 TLS 上下文。
	 *
	 * @return 仅供调用方所建 HttpClient 使用的 TLS 上下文
	 * @throws GeneralSecurityException TLS 提供者无法初始化
	 * @see <a href="https://docs.oracle.com/en/java/javase/25/docs/api/java.base/javax/net/ssl/X509ExtendedTrustManager.html">Java 25 X509ExtendedTrustManager</a>
	 */
	public static SSLContext createUnverifiedHttpClientTlsContext() throws GeneralSecurityException {
		X509ExtendedTrustManager trustManager = new X509ExtendedTrustManager() {
			/** {@inheritDoc} 当前 Elasticsearch 客户端由数据源设置显式关闭服务端身份校验。 */
			@Override
			public void checkClientTrusted(X509Certificate[] chain, String authType) {
			}

			/** {@inheritDoc} 当前 Elasticsearch 客户端由数据源设置显式关闭服务端身份校验。 */
			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType) {
			}

			/** {@inheritDoc} 客户端认证不在 Elasticsearch REST 请求范围内。 */
			@Override
			public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
			}

			/** {@inheritDoc} 当前客户端显式关闭校验，握手不按 socket 主机名校验证书。 */
			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
			}

			/** {@inheritDoc} 客户端认证不在 Elasticsearch REST 请求范围内。 */
			@Override
			public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
			}

			/** {@inheritDoc} 当前客户端显式关闭校验，握手不按 engine 主机名校验证书。 */
			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
			}

			/** {@inheritDoc} 当前上下文不维护受信任证书清单。 */
			@Override
			public X509Certificate[] getAcceptedIssuers() {
				return new X509Certificate[0];
			}
		};
		SSLContext context = SSLContext.getInstance("TLS");
		context.init(null, new TrustManager[]{trustManager}, new SecureRandom());
		return context;
	}
}
