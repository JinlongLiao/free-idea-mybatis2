package io.github.jinlongliao.mybatis.workbench.database;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * 有界收集 Elasticsearch 响应正文；超过字节数后立即取消订阅，避免先完整缓冲超大响应。
 *
 * @author liaojinlong
 * @since 2026/11/22 08:33
 * @see <a href="https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpResponse.BodySubscriber.html">Java 25 HttpResponse.BodySubscriber</a>
 */
final class ElasticsearchResponseBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
	/** 已收集的响应正文，容量始终不超过 byteLimit。 */
	private final ByteArrayOutputStream responseBytes;
	/** 本次响应允许保留的最大字节数。 */
	private final int byteLimit;
	/** 告知调用方发生了限流，便于展示稳定的本地化错误。 */
	private final Runnable limitExceeded;
	/** sendAsync 在正文接收完毕或失败后取得该阶段。 */
	private final CompletableFuture<byte[]> body = new CompletableFuture<>();
	/** 上游 HTTP 响应订阅，用于逐批请求并在超限时停止正文传输。 */
	private Flow.Subscription subscription;

	/**
	 * 创建限定字节数的正文订阅器。
	 *
	 * @param byteLimit 正文最大字节数，必须为正数
	 * @param limitExceeded 达到上限后调用的通知动作
	 */
	ElasticsearchResponseBodySubscriber(int byteLimit, Runnable limitExceeded) {
		if (byteLimit < 1) {
			throw new IllegalArgumentException("Response body byte limit must be positive");
		}
		this.byteLimit = byteLimit;
		this.limitExceeded = limitExceeded;
		responseBytes = new ByteArrayOutputStream(Math.min(byteLimit, 8 * 1024));
	}

	/**
	 * {@inheritDoc} 获得响应订阅后按批读取，不能在 EDT 上运行。
	 *
	 * @return 正文完成阶段
	 */
	@Override
	public CompletionStage<byte[]> getBody() {
		return body;
	}

	/**
	 * {@inheritDoc} 只保存当前请求的订阅并发出首个批次请求。
	 *
	 * @param subscription HTTP 客户端提供的响应订阅
	 */
	@Override
	public void onSubscribe(Flow.Subscription subscription) {
		this.subscription = subscription;
		subscription.request(1);
	}

	/**
	 * {@inheritDoc} 先计算批次大小；超过上限时取消订阅，不复制该批正文。
	 *
	 * @param buffers HTTP 客户端交付的只读正文缓冲区
	 */
	@Override
	public void onNext(List<ByteBuffer> buffers) {
		long incomingBytes = 0;
		for (ByteBuffer buffer : buffers) {
			incomingBytes += buffer.remaining();
			if (incomingBytes > byteLimit - responseBytes.size()) {
				break;
			}
		}
		if (incomingBytes > byteLimit - responseBytes.size()) {
			subscription.cancel();
			limitExceeded.run();
			body.completeExceptionally(new IOException("Elasticsearch response body exceeds configured limit"));
			return;
		}
		for (ByteBuffer buffer : buffers) {
			byte[] chunk = new byte[buffer.remaining()];
			buffer.get(chunk);
			responseBytes.writeBytes(chunk);
		}
		subscription.request(1);
	}

	/**
	 * {@inheritDoc} 保留上游传输或协议失败。
	 *
	 * @param failure HTTP 客户端报告的失败
	 */
	@Override
	public void onError(Throwable failure) {
		body.completeExceptionally(failure);
	}

	/**
	 * {@inheritDoc} 返回已收集的完整响应正文。
	 */
	@Override
	public void onComplete() {
		body.complete(responseBytes.toByteArray());
	}
}
