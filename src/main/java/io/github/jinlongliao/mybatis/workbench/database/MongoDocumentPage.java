package io.github.jinlongliao.mybatis.workbench.database;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * MongoDB 查询页，文档保留 BSON 类型的 Extended JSON 表示。
 *
 * @param documents 本页文档
 * @param hasNext   查询存在下一页
 * @author liaojinlong
 * @since 2026/11/18 15:37
 */
public record MongoDocumentPage(List<String> documents, boolean hasNext) {
	/**
	 * 将查询生成的紧凑 Extended JSON 原文逐行写入新文件，保留 BSON 类型标记。
	 * 已有文件不会被覆盖，写入失败时尝试清除本次创建的不完整文件。
	 *
	 * @param path 目标新文件
	 * @throws IOException 无法创建、写入或关闭文件
	 */
	public void exportMongoJsonLines(Path path) throws IOException {
		var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
		try (writer) {
			for (String document : documents) {
				writer.write(document);
				writer.write('\n');
			}
		} catch (IOException failure) {
			try {
				Files.deleteIfExists(path);
			} catch (IOException cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}
	}
}
