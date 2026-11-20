package io.github.jinlongliao.mybatis.workbench.ui;

import io.github.jinlongliao.mybatis.workbench.database.NoSqlConnectionProfile;
import io.github.jinlongliao.mybatis.workbench.database.NoSqlSourceType;
import io.github.jinlongliao.mybatis.workbench.setting.WorkbenchConnectionProfiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证导出覆盖和并发变更时实际落盘的配置内容。
 *
 * @author liaojinlong
 * @since 2026/11/19 13:11
 */
class WorkbenchConnectionProfileFileTest {
	/**
	 * 中文按 UTF-8 字节计数，恰好达到限制时仍能保存和读取，超限时保留原文件。
	 *
	 * @param directory JUnit 管理的临时目录
	 * @throws IOException 文件操作失败
	 */
	@Test
	void keepsExportWithinTheImportByteLimit(@TempDir Path directory) throws IOException {
		Path file = directory.resolve("connection.properties");
		String header = "format=mybatis-workbench-jdbc/1\nid=00000000-0000-4000-8000-000000000001\nname=";
		int available = 65_536 - header.getBytes(StandardCharsets.UTF_8).length;
		String boundary = header + "中".repeat(available / 3) + "a".repeat(available % 3);
		assertTrue(WorkbenchDatabasePanel.writePortableProfileFile(file, boundary, () -> fail("新文件无需确认")));
		assertEquals(65_536, Files.size(file));
		assertTrue(WorkbenchDatabasePanel.writePortableProfileFile(file, boundary, () -> fail("相同正文无需确认")));
		assertThrows(IOException.class, () -> WorkbenchDatabasePanel.writePortableProfileFile(file,
			boundary + "中", () -> fail("超限时不得确认覆盖")));
		assertEquals(boundary, Files.readString(file));
	}

	/**
	 * 新建、取消覆盖、拒绝异 ID 和确认覆盖都保持预期的文件内容。
	 *
	 * @param directory JUnit 管理的临时目录
	 * @throws IOException 文件操作失败
	 */
	@Test
	void updatesOnlyTheConfirmedSameConnectionFile(@TempDir Path directory) throws IOException {
		Path file = directory.resolve("connection.properties");
		String initial = "format=mybatis-workbench-jdbc/1\nid=00000000-0000-4000-8000-000000000001\nname=原连接\n";
		String updated = initial.replace("原连接", "新连接");
		assertTrue(WorkbenchDatabasePanel.writePortableProfileFile(file, initial, () -> fail("首次导出无需确认")));
		assertTrue(WorkbenchDatabasePanel.writePortableProfileFile(file, initial, () -> fail("内容相同无需确认")));
		String initialWithBom = "\uFEFF" + initial;
		Files.writeString(file, initialWithBom);
		assertFalse(WorkbenchDatabasePanel.writePortableProfileFile(file, updated, () -> false));
		assertThrows(IOException.class, () -> WorkbenchDatabasePanel.writePortableProfileFile(file,
			updated.replace("000000000001", "000000000002"), () -> fail("异 ID 文件不得请求覆盖")));
		assertEquals(initialWithBom, Files.readString(file));
		assertTrue(WorkbenchDatabasePanel.writePortableProfileFile(file, updated, () -> true));
		assertEquals(updated, Files.readString(file));
	}

	/**
	 * 覆盖确认期间即使只移除了 BOM，也必须保留编辑器写入的版本，且失败后不留下待替换文件。
	 *
	 * @param directory JUnit 管理的临时目录
	 * @throws IOException 文件操作失败
	 */
	@Test
	void preservesChangesMadeDuringExportConfirmation(@TempDir Path directory) throws IOException {
		Path file = directory.resolve("connection.properties");
		String initial = "format=mybatis-workbench-jdbc/1\nid=00000000-0000-4000-8000-000000000001\nname=原连接\n";
		String concurrent = initial;
		Files.writeString(file, "\uFEFF" + initial);
		assertThrows(IOException.class, () -> WorkbenchDatabasePanel.writePortableProfileFile(file,
			initial.replace("原连接", "待导出连接"), () -> {
				try {
					Files.writeString(file, concurrent);
				} catch (IOException failure) {
					throw new UncheckedIOException(failure);
				}
				return true;
			}));
		assertEquals(concurrent, Files.readString(file));
		try (var files = Files.list(directory)) {
			assertEquals(List.of(file), files.toList());
		}
	}

	/** 从同步设置恢复 Redis 连接时，改名保留本机凭据键，切换 TLS 使用不同的键。 */
	@Test
	void bindsRedisCredentialsToTransportMode() {
		var id = UUID.randomUUID();
		var plain = new NoSqlConnectionProfile(id, "Redis", NoSqlSourceType.REDIS,
			"localhost:6379", "reader", "", false, false, 0);
		var tls = new NoSqlConnectionProfile(id, "Redis", NoSqlSourceType.REDIS,
			"localhost:6379", "reader", "", false, true, 0);
		var renamed = new NoSqlConnectionProfile(id, "改名后的 Redis", NoSqlSourceType.REDIS,
			"localhost:6379", "reader", "", false, false, 0);
		var remote = new WorkbenchConnectionProfiles();
		var local = new WorkbenchConnectionProfiles();
		var originalKey = WorkbenchNoSqlSourceEditor.noSqlCredentialKey(plain);
		remote.saveNoSqlProfile(renamed);
		local.loadState(remote.getState());
		assertEquals(originalKey, WorkbenchNoSqlSourceEditor.noSqlCredentialKey(local.listNoSqlProfiles().getFirst()));
		remote.saveNoSqlProfile(tls);
		local.loadState(remote.getState());
		var restoredTlsKey = WorkbenchNoSqlSourceEditor.noSqlCredentialKey(local.listNoSqlProfiles().getFirst());
		assertEquals(WorkbenchNoSqlSourceEditor.noSqlCredentialKey(tls), restoredTlsKey);
		assertFalse(originalKey.equals(restoredTlsKey));
	}

}
