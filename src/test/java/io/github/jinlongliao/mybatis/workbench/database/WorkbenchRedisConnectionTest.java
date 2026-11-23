package io.github.jinlongliao.mybatis.workbench.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Redis 字符串编辑、TTL 与删除的原值冲突保护。测试仅启动回环地址上的临时服务。
 *
 * @author liaojinlong
 * @since 2026/11/21 23:49
 */
class WorkbenchRedisConnectionTest {
	/**
	 * 验证单次 SCAN 超量返回时，硬展示分页不会漏键或把一页扩展到 100 个以上。
	 */
	@Test
	void splitsOverReturnedRedisScanKeysIntoBoundedPages() {
		List<String> keys = IntStream.range(0, 237).mapToObj(index -> "key-" + index).toList();
		RedisKeyPage scanPage = new RedisKeyPage("0", keys);
		List<String> displayed = new ArrayList<>();
		int offset = 0;
		int pageCount = 0;
		while (offset < keys.size()) {
			int end = scanPage.nextPageEnd(offset, 100);
			List<String> visiblePage = scanPage.keys().subList(offset, end);
			assertTrue(visiblePage.size() <= 100);
			displayed.addAll(visiblePage);
			offset = end;
			pageCount++;
		}
		assertEquals(keys, displayed);
		assertEquals(3, pageCount);
		assertEquals(237, offset);
		assertEquals("0", scanPage.cursor());
	}

	/**
	 * 验证 Redis 原子字符串快照、并发覆盖拒绝、TTL 保留和清除，以及删除确认后的单键边界。
	 *
	 * @throws Exception 临时 Redis 启动、连接或操作失败
	 */
	@Test
	void protectsRedisStringMutationsWithOriginalValue() throws Exception {
		Process version = new ProcessBuilder("redis-server", "--version")
			.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		Assumptions.assumeTrue(version.waitFor() == 0, "redis-server is required for the isolated mutation test");
		int port;
		try (var socket = new ServerSocket(0)) {
			port = socket.getLocalPort();
		}
		Process server = new ProcessBuilder("redis-server", "--bind", "127.0.0.1", "--port", Integer.toString(port),
			"--save", "", "--appendonly", "no", "--protected-mode", "no")
			.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		try {
			WorkbenchRedisConnection connection = awaitRedisConnection(port);
			try (connection) {
				String createdKey = "workbench:isolated:created";
				assertTrue(connection.createRedisStringKey(createdKey, "initial", 30));
				assertFalse(connection.createRedisStringKey(createdKey, "must-not-replace", 0));
				var createdDetails = connection.readRedisKeyDetails(createdKey);
				assertEquals("initial", createdDetails.editableStringSnapshot());
				assertTrue(createdDetails.ttlSeconds() > 0);
				assertTrue(connection.deleteRedisKey(createdKey, "initial"));
				assertThrows(IllegalArgumentException.class,
					() -> connection.createRedisStringKey("", "value", 0));

				String aggregateHashKey = "workbench:isolated:new-hash";
				assertTrue(connection.createRedisAggregateKey(aggregateHashKey, "hash", "field", "value"));
				assertEquals("hash", connection.readRedisKeyDetails(aggregateHashKey).type());
				assertFalse(connection.createRedisAggregateKey(aggregateHashKey, "hash", "other", "value"));
				String aggregateSetKey = "workbench:isolated:new-set";
				assertTrue(connection.createRedisAggregateKey(aggregateSetKey, "set", "member", ""));
				assertEquals("member", connection.readRedisKeyDetails(aggregateSetKey).setPreview().getFirst());
				String aggregateSortedSetKey = "workbench:isolated:new-zset";
				assertTrue(connection.createRedisAggregateKey(aggregateSortedSetKey, "zset", "member", "1.5"));
				assertEquals(1.5, connection.readRedisKeyDetails(aggregateSortedSetKey).sortedSetPreview().getFirst().score(), 0.0);
				String aggregateListKey = "workbench:isolated:new-list";
				assertTrue(connection.createRedisAggregateKey(aggregateListKey, "list-right", "value", ""));
				assertEquals(List.of("value"), connection.readRedisKeyDetails(aggregateListKey).listPreview());
				String aggregateStreamKey = "workbench:isolated:new-stream";
				assertTrue(connection.createRedisAggregateKey(aggregateStreamKey, "stream", "field", "value"));
				assertEquals("value", connection.readRedisKeyDetails(aggregateStreamKey).streamPreview()
					.getFirst().fields().getFirst().getValue());
				for (String[] aggregate : List.of(
					new String[] {"hash", "hash"}, new String[] {"set", "set"},
					new String[] {"zset", "zset"}, new String[] {"list", "list-right"},
					new String[] {"stream", "stream"})) {
					String key = "workbench:isolated:delete:" + aggregate[0];
					String initialValue = "zset".equals(aggregate[0]) ? "1" : "value";
					assertTrue(connection.createRedisAggregateKey(key, aggregate[1], "field", initialValue));
					var snapshot = connection.readRedisKeyDeletionSnapshot(key);
					assertEquals(WorkbenchRedisConnection.RedisKeyDeletionSnapshotStatus.READY, snapshot.status());
					assertTrue(connection.deleteRedisKey(key, snapshot));
					assertEquals(0L, ((Number) connection.executeRedisCommand(key, "EXISTS", List.of(key))).longValue());
				}

				String staleHashKey = "workbench:isolated:delete:stale-hash";
				assertTrue(connection.createRedisAggregateKey(staleHashKey, "hash", "field", "original"));
				var staleHashSnapshot = connection.readRedisKeyDeletionSnapshot(staleHashKey);
				assertTrue(connection.addRedisHashField(staleHashKey, "concurrent", "change"));
				assertFalse(connection.deleteRedisKey(staleHashKey, staleHashSnapshot));
				assertEquals("change", connection.executeRedisCommand(staleHashKey, "HGET",
					List.of(staleHashKey, "concurrent")) instanceof byte[] bytes
					? new String(bytes, StandardCharsets.UTF_8) : "");

				String groupedStreamKey = "workbench:isolated:delete:grouped-stream";
				assertTrue(connection.createRedisAggregateKey(groupedStreamKey, "stream", "field", "value"));
				var ungroupedStreamSnapshot = connection.readRedisKeyDeletionSnapshot(groupedStreamKey);
				assertEquals(WorkbenchRedisConnection.RedisKeyDeletionSnapshotStatus.READY,
					ungroupedStreamSnapshot.status());
				assertEquals("OK", connection.executeRedisCommand(groupedStreamKey, "XGROUP",
					List.of("CREATE", groupedStreamKey, "consumer-group", "$", "MKSTREAM")) instanceof byte[] bytes
					? new String(bytes, StandardCharsets.UTF_8) : "");
				assertFalse(connection.deleteRedisKey(groupedStreamKey, ungroupedStreamSnapshot));
				assertEquals(1L, ((Number) connection.executeRedisCommand(groupedStreamKey, "EXISTS",
					List.of(groupedStreamKey))).longValue());
				assertEquals(WorkbenchRedisConnection.RedisKeyDeletionSnapshotStatus.STREAM_HAS_GROUPS,
					connection.readRedisKeyDeletionSnapshot(groupedStreamKey).status());

				String expiringHashKey = "workbench:isolated:delete:expiring-hash";
				assertTrue(connection.createRedisAggregateKey(expiringHashKey, "hash", "field", "value"));
				connection.executeRedisCommand(expiringHashKey, "PEXPIRE", List.of(expiringHashKey, "120000"));
				var expiringHashSnapshot = connection.readRedisKeyDeletionSnapshot(expiringHashKey);
				if (expiringHashSnapshot.status() == WorkbenchRedisConnection.RedisKeyDeletionSnapshotStatus.READY) {
					connection.executeRedisCommand(expiringHashKey, "PEXPIRE", List.of(expiringHashKey, "60000"));
					assertFalse(connection.deleteRedisKey(expiringHashKey, expiringHashSnapshot));
					assertEquals(1L, ((Number) connection.executeRedisCommand(expiringHashKey, "EXISTS",
						List.of(expiringHashKey))).longValue());
				} else {
					assertEquals(WorkbenchRedisConnection.RedisKeyDeletionSnapshotStatus.EXPIRATION_UNSUPPORTED,
						expiringHashSnapshot.status());
				}

				String oversizedHashKey = "workbench:isolated:delete:oversized-hash";
				assertEquals(1L, ((Number) connection.executeRedisCommand(oversizedHashKey, "HSET",
					List.of(oversizedHashKey, "field", "x".repeat(65 * 1024)))).longValue());
				assertEquals(WorkbenchRedisConnection.RedisKeyDeletionSnapshotStatus.TOO_LARGE,
					connection.readRedisKeyDeletionSnapshot(oversizedHashKey).status());
				assertEquals(1L, ((Number) connection.executeRedisCommand(oversizedHashKey, "EXISTS",
					List.of(oversizedHashKey))).longValue());
				assertThrows(IllegalArgumentException.class,
					() -> connection.createRedisAggregateKey("invalid-zset", "zset", "member", "NaN"));

				String pagedListKey = "workbench:isolated:paged-list";
				assertTrue(connection.createRedisAggregateKey(pagedListKey, "list-right", "item-0", ""));
				for (int index = 1; index < 45; index++) {
					connection.pushRedisListValue(pagedListKey, "item-" + index, false);
				}
				var listFirstPage = connection.readRedisKeyDetails(pagedListKey);
				assertEquals(20, listFirstPage.listPreview().size());
				assertEquals(0L, listFirstPage.memberPageOffset());
				assertTrue(listFirstPage.nextMemberPageCursor().startsWith("list:"));
				var listSecondPage = connection.readRedisKeyDetails(pagedListKey, listFirstPage.nextMemberPageCursor());
				assertEquals(20, listSecondPage.listPreview().size());
				assertEquals("item-20", listSecondPage.listPreview().getFirst());
				assertEquals(20, listSecondPage.memberPageOffset());
				var listLastPage = connection.readRedisKeyDetails(pagedListKey, listSecondPage.nextMemberPageCursor());
				assertEquals(5, listLastPage.listPreview().size());
				assertEquals("", listLastPage.nextMemberPageCursor());
				assertEquals("", connection.readRedisKeyDetails(pagedListKey, "list:45:100")
					.nextMemberPageCursor());
				connection.pushRedisListValue(pagedListKey, "new-head", true);
				var changedListPage = connection.readRedisKeyDetails(pagedListKey,
					listFirstPage.nextMemberPageCursor());
				assertEquals(0L, changedListPage.memberPageOffset());
				assertEquals("new-head", changedListPage.listPreview().getFirst());
				String replacedListKey = "workbench:isolated:paged-list-replaced";
				assertTrue(connection.createRedisAggregateKey(replacedListKey, "list-right", "item-0", ""));
				for (int index = 1; index < 21; index++) {
					assertEquals(index + 1L,
						connection.pushRedisListValue(replacedListKey, "item-" + index, false));
				}
				var replacedListFirstPage = connection.readRedisKeyDetails(replacedListKey);
				assertTrue(replacedListFirstPage.nextMemberPageCursor().startsWith("list:"));
				connection.executeRedisCommand(replacedListKey, "DEL", List.of(replacedListKey));
				connection.executeRedisCommand(replacedListKey, "SET", List.of(replacedListKey, "new-string-value"));
				var replacedListDetails = connection.readRedisKeyDetails(replacedListKey,
					replacedListFirstPage.nextMemberPageCursor());
				assertEquals("string", replacedListDetails.type());
				assertEquals("new-string-value", replacedListDetails.stringPreview());

				String pagedHashKey = "workbench:isolated:paged-hash";
				for (int index = 0; index < 57; index++) {
					connection.executeRedisCommand(pagedHashKey, "HSET",
						List.of(pagedHashKey, "field-" + index, "value-" + index));
				}
				var hashFirstPage = connection.readRedisKeyDetails(pagedHashKey);
				assertEquals(20, hashFirstPage.hashPreview().size());
				assertEquals(37, hashFirstPage.hashBufferedPreview().size());
				assertEquals("hash-buffer:1", hashFirstPage.nextMemberPageCursor());

				String pagedSetKey = "workbench:isolated:paged-set";
				for (int index = 0; index < 57; index++) {
					connection.executeRedisCommand(pagedSetKey, "SADD", List.of(pagedSetKey, "member-" + index));
				}
				var setFirstPage = connection.readRedisKeyDetails(pagedSetKey);
				assertEquals(20, setFirstPage.setPreview().size());
				assertEquals(37, setFirstPage.setBufferedPreview().size());
				assertEquals("set-buffer:1", setFirstPage.nextMemberPageCursor());

				String pagedSortedSetKey = "workbench:isolated:paged-zset";
				assertTrue(connection.createRedisAggregateKey(pagedSortedSetKey, "zset", "member-0", "0"));
				for (int index = 1; index < 45; index++) {
					connection.addRedisSortedSetMember(pagedSortedSetKey, "member-" + index, index);
				}
				var sortedFirstPage = connection.readRedisKeyDetails(pagedSortedSetKey);
				assertTrue(sortedFirstPage.nextMemberPageCursor().startsWith("zset:"));
				var sortedSecondPage = connection.readRedisKeyDetails(pagedSortedSetKey,
					sortedFirstPage.nextMemberPageCursor());
				assertEquals(20, sortedSecondPage.sortedSetPreview().size());
				assertEquals("member-20", sortedSecondPage.sortedSetPreview().getFirst().member());
				var sortedLastPage = connection.readRedisKeyDetails(pagedSortedSetKey,
					sortedSecondPage.nextMemberPageCursor());
				assertEquals(5, sortedLastPage.sortedSetPreview().size());
				assertEquals("", sortedLastPage.nextMemberPageCursor());
				assertEquals("member-0", connection.readRedisKeyDetails(pagedSortedSetKey, "stream:stale")
					.sortedSetPreview().getFirst().member());
				assertEquals("", connection.readRedisKeyDetails(pagedSortedSetKey, "zset:100")
					.nextMemberPageCursor());

				String pagedStreamKey = "workbench:isolated:paged-stream";
				assertTrue(connection.createRedisAggregateKey(pagedStreamKey, "stream", "field", "value-0"));
				for (int index = 1; index < 45; index++) {
					connection.appendRedisStreamEntry(pagedStreamKey, "field", "value-" + index);
				}
				var streamFirstPage = connection.readRedisKeyDetails(pagedStreamKey);
				assertEquals(20, streamFirstPage.streamPreview().size());
				assertTrue(streamFirstPage.nextMemberPageCursor().startsWith("stream:"));
				assertTrue(compareRedisStreamIds("1000-10", "1000-9") > 0);
				var streamSecondPage = connection.readRedisKeyDetails(pagedStreamKey,
					streamFirstPage.nextMemberPageCursor());
				assertEquals(20, streamSecondPage.streamPreview().size());
				assertTrue(compareRedisStreamIds(streamSecondPage.streamPreview().getFirst().id(),
					streamFirstPage.streamPreview().getLast().id()) > 0);
				var streamLastPage = connection.readRedisKeyDetails(pagedStreamKey,
					streamSecondPage.nextMemberPageCursor());
				assertEquals(5, streamLastPage.streamPreview().size());
				assertEquals("", streamLastPage.nextMemberPageCursor());

				String oversizedStreamKey = "workbench:isolated:oversized-stream";
				Object oversizedReply = connection.executeRedisCommand(oversizedStreamKey, "XADD",
					List.of(oversizedStreamKey, "*", "field", "x".repeat(65 * 1024)));
				String oversizedStreamId = new String((byte[]) oversizedReply, StandardCharsets.UTF_8);
				var oversizedMessage = connection.readRedisKeyDetails(oversizedStreamKey).streamPreview().getFirst();
				assertEquals(oversizedStreamId, oversizedMessage.id());
				assertFalse(oversizedMessage.completeSnapshot());
				assertThrows(IllegalArgumentException.class,
					() -> connection.deleteRedisStreamMessage(oversizedStreamKey, oversizedMessage));
				assertThrows(IllegalArgumentException.class,
					() -> connection.appendRedisStreamEntry("workbench:isolated:too-large-stream", "field",
						"x".repeat(65 * 1024)));

				String hashKey = "workbench:isolated:hash";
				assertTrue(connection.createRedisAggregateKey(hashKey, "hash", "field", "original"));
				assertFalse(connection.addRedisHashField(hashKey, "field", "ignored"));
				assertTrue(connection.replaceRedisHashField(hashKey, "field", "original", "updated"));
				assertFalse(connection.replaceRedisHashField(hashKey, "field", "original", "stale"));
				assertTrue(connection.addRedisHashField(hashKey, "extra", "value"));
				assertTrue(connection.deleteRedisHashField(hashKey, "extra", "value"));
				assertTrue(connection.deleteRedisHashField(hashKey, "field", "updated"));
				assertFalse(connection.deleteRedisHashField(hashKey, "field", "updated"));
				assertFalse(connection.addRedisHashField(hashKey, "field", "after-delete"));
				assertEquals(0L, ((Number) connection.executeRedisCommand(hashKey, "EXISTS", List.of(hashKey))).longValue());

				String setKey = "workbench:isolated:set";
				assertTrue(connection.createRedisAggregateKey(setKey, "set", "old", ""));
				connection.executeRedisCommand(setKey, "EXPIRE", List.of(setKey, "30"));
				assertFalse(connection.addRedisSetMember(setKey, "old"));
				assertTrue(connection.replaceRedisSetMember(setKey, "old", "renamed"));
				assertTrue(connection.readRedisKeyDetails(setKey).ttlSeconds() > 0);
				assertTrue(connection.addRedisSetMember(setKey, "extra"));
				assertTrue(connection.deleteRedisSetMember(setKey, "extra"));
				assertFalse(connection.replaceRedisSetMember(setKey, "old", "stale"));
				assertFalse(connection.deleteRedisSetMember(setKey, "old"));
				assertTrue(connection.deleteRedisSetMember(setKey, "renamed"));
				assertFalse(connection.addRedisSetMember(setKey, "after-delete"));
				assertEquals(0L, ((Number) connection.executeRedisCommand(setKey, "EXISTS", List.of(setKey))).longValue());

				String sortedSetKey = "workbench:isolated:zset";
				assertTrue(connection.createRedisAggregateKey(sortedSetKey, "zset", "member", "1.5"));
				assertFalse(connection.addRedisSortedSetMember(sortedSetKey, "member", 9));
				assertTrue(connection.replaceRedisSortedSetScore(sortedSetKey, "member", 1.5, 2.5));
				assertFalse(connection.replaceRedisSortedSetScore(sortedSetKey, "member", 1.5, 3));
				assertTrue(connection.addRedisSortedSetMember(sortedSetKey, "extra", 4));
				assertTrue(connection.deleteRedisSortedSetMember(sortedSetKey, "extra", 4));
				assertFalse(connection.deleteRedisSortedSetMember(sortedSetKey, "member", 1.5));
				assertTrue(connection.deleteRedisSortedSetMember(sortedSetKey, "member", 2.5));
				assertFalse(connection.addRedisSortedSetMember(sortedSetKey, "after-delete", 3));
				assertEquals(0L, ((Number) connection.executeRedisCommand(sortedSetKey, "EXISTS", List.of(sortedSetKey))).longValue());

				String listKey = "workbench:isolated:list";
				assertTrue(connection.createRedisAggregateKey(listKey, "list-right", "first", ""));
				assertEquals(2, connection.pushRedisListValue(listKey, "duplicate", false));
				assertEquals(3, connection.pushRedisListValue(listKey, "duplicate", false));
				assertTrue(connection.replaceRedisListValue(listKey, 1, 3, "duplicate", "updated"));
				assertFalse(connection.replaceRedisListValue(listKey, 1, 3, "duplicate", "stale"));
				assertFalse(connection.replaceRedisListValue(listKey, 1, 2, "updated", "stale-length"));
				assertEquals(-2, connection.deleteRedisListValue(listKey, 1, 3, "updated"));
				assertEquals(1, connection.deleteRedisListValue(listKey, 2, 3, "duplicate"));
				assertEquals(List.of("first", "updated"),
					connection.readRedisKeyDetails(listKey).listPreview());
				connection.executeRedisCommand(listKey, "DEL", List.of(listKey));
				assertEquals(0, connection.pushRedisListValue(listKey, "after-delete", false));
				assertEquals(0L, ((Number) connection.executeRedisCommand(listKey, "EXISTS", List.of(listKey))).longValue());

				String streamKey = "workbench:isolated:stream";
				assertTrue(connection.createRedisAggregateKey(streamKey, "stream", "field", "original"));
				String streamId = connection.readRedisKeyDetails(streamKey).streamPreview().getFirst().id();
				var streamSnapshot = new RedisStreamMessage(streamId, List.of(Map.entry("field", "original")));
				String appendedStreamId = connection.appendRedisStreamEntry(streamKey, "extra", "value");
				assertTrue(connection.deleteRedisStreamMessage(streamKey,
					new RedisStreamMessage(appendedStreamId, List.of(Map.entry("extra", "value")))));
				assertTrue(connection.deleteRedisStreamMessage(streamKey, streamSnapshot));
				assertFalse(connection.deleteRedisStreamMessage(streamKey, streamSnapshot));
				connection.executeRedisCommand(streamKey, "DEL", List.of(streamKey));
				assertNull(connection.appendRedisStreamEntry(streamKey, "field", "after-delete"));
				assertEquals(0L, ((Number) connection.executeRedisCommand(streamKey, "EXISTS", List.of(streamKey))).longValue());

				String key = "workbench:isolated:string";
				connection.executeRedisCommand(key, "SET", List.of(key, "original"));
				var details = connection.readRedisKeyDetails(key);
				assertEquals("original", details.editableStringSnapshot());
				assertTrue(connection.replaceRedisStringValue(key, details.editableStringSnapshot(), "updated"));
				assertFalse(connection.replaceRedisStringValue(key, "original", "stale"));
				assertEquals("updated", connection.executeRedisCommand(key, "GET", List.of(key)) instanceof byte[] bytes
					? new String(bytes, StandardCharsets.UTF_8) : "");
				assertFalse(connection.setRedisKeyTtl(key, "original", 30));
				assertTrue(connection.setRedisKeyTtl(key, "updated", 30));
				assertTrue(connection.readRedisKeyDetails(key).ttlSeconds() >= 0);
				assertTrue(connection.clearRedisKeyTtl(key, "updated"));
				assertEquals(-1, connection.readRedisKeyDetails(key).ttlSeconds());
				assertFalse(connection.deleteRedisKey(key, "original"));
				assertNotNull(connection.readRedisKeyDetails(key).editableStringSnapshot());
				assertTrue(connection.deleteRedisKey(key, "updated"));
				assertNull(connection.readRedisKeyDetails(key).editableStringSnapshot());

				String largeKey = "workbench:isolated:large";
				connection.executeRedisCommand(largeKey, "SET", List.of(largeKey, "x".repeat(65 * 1024)));
				var large = connection.readRedisKeyDetails(largeKey);
				assertNull(large.editableStringSnapshot());
				assertEquals(4_096, large.stringPreview().length());
			}
		} finally {
			server.destroy();
			if (!server.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
				server.destroyForcibly();
				server.waitFor();
			}
		}
	}

	/**
	 * 等待临时 Redis 完成启动，并返回已经验证过 PING 的连接。
	 *
	 * @param port 仅绑定本机回环的服务端口
	 * @return 临时连接
	 * @throws Exception 服务未能在 5 秒内就绪
	 */
	private static WorkbenchRedisConnection awaitRedisConnection(int port) throws Exception {
		Exception lastFailure = null;
		for (int attempt = 0; attempt < 50; attempt++) {
			var connection = new WorkbenchRedisConnection(List.of("127.0.0.1:" + port), false, false, 0,
				"", "", Locale.ENGLISH);
			try {
				connection.pingRedis();
				return connection;
			} catch (RuntimeException failure) {
				lastFailure = failure;
				connection.close();
				Thread.sleep(100);
			}
		}
		throw new IllegalStateException("Temporary Redis did not become ready", lastFailure);
	}

	/**
	 * 按 Redis Stream ID 的毫秒和序列号数值比较，避免 `-9` 与 `-10` 的字符串字典序误判。
	 *
	 * @param left 左侧 ID
	 * @param right 右侧 ID
	 * @return ID 数值顺序
	 */
	private static int compareRedisStreamIds(String left, String right) {
		String[] leftParts = left.split("-", 2);
		String[] rightParts = right.split("-", 2);
		int timestampOrder = Long.compare(Long.parseLong(leftParts[0]), Long.parseLong(rightParts[0]));
		if (timestampOrder != 0) {
			return timestampOrder;
		}
		return Long.compare(Long.parseLong(leftParts[1]), Long.parseLong(rightParts[1]));
	}
}
