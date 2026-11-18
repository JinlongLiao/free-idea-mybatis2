package com.wuzhizhan.mybatis2.generate;

import org.mybatis.generator.api.GeneratedXmlFile;
import org.mybatis.generator.exception.MergeException;
import org.mybatis.generator.merge.xml.XmlFileMergerJaxp;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 合并或覆盖生成的 Mapper XML，在内容完整且无重复标识后替换目标文件。
 *
 * @author liaojinlong
 * @see XmlFileMergerJaxp
 * @since 2026/11/18 13:28
 */
final class MapperXmlPublisher {
	/**
	 * 文件发布方法由本类统一提供。
	 */
	private MapperXmlPublisher() {
	}

	/**
	 * 按当前 XML 配置发布已生成文件。临时目录须位于目标目录所在文件系统。
	 *
	 * @param generatedXmlFiles 已生成文件的名称和包路径
	 * @param stagedDirectory   本次生成的临时目录
	 * @param targetDirectory   XML 输出根目录
	 * @param overwrite         是否直接替换已有 XML
	 * @throws IOException 合并、校验或原子替换失败，当前目标仍保留原文件
	 */
	static void publishMapperXml(List<GeneratedXmlFile> generatedXmlFiles, Path stagedDirectory,
								 Path targetDirectory, boolean overwrite) throws IOException {
		for (GeneratedXmlFile generatedXmlFile : generatedXmlFiles) {
			Path relativeFile = Path.of(generatedXmlFile.getTargetPackage().replace('.', '/'))
				.resolve(generatedXmlFile.getFileName());
			Path stagedFile = stagedDirectory.resolve(relativeFile);
			Path targetFile = targetDirectory.resolve(relativeFile);
			if (!overwrite && Files.exists(targetFile)) {
				mergeMapperXml(stagedFile, targetFile);
			}
			Files.createDirectories(targetFile.getParent());
			Files.move(stagedFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * 使用 MBG 合并规则保留手写节点。旧版生成文件缺少合并标记且发生冲突时保留原文件。
	 *
	 * @param stagedFile 新生成的 XML
	 * @param targetFile 已存在的 XML
	 * @throws IOException 文件读写失败，或合并结果包含重复标识
	 */
	private static void mergeMapperXml(Path stagedFile, Path targetFile) throws IOException {
		try {
			String merged = XmlFileMergerJaxp.getMergedSource(Files.readString(stagedFile), targetFile.toFile());
			validateMapperIds(merged, targetFile);
			Files.writeString(stagedFile, merged);
		} catch (MergeException | XMLStreamException e) {
			throw new IOException("无法合并 Mapper XML: " + targetFile, e);
		}
	}

	/**
	 * 校验顶层节点标识。同名 SQL 语句允许按 databaseId 区分。
	 *
	 * @param mapperXml  合并后的 XML
	 * @param targetFile 用于错误定位的文件路径
	 * @throws XMLStreamException XML 无法解析
	 * @throws IOException        存在冲突的 Mapper 标识
	 */
	private static void validateMapperIds(String mapperXml, Path targetFile) throws XMLStreamException, IOException {
		XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
		factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
		factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
		XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(mapperXml));
		try {
			Set<List<String>> ids = new HashSet<>();
			int depth = 0;
			while (reader.hasNext()) {
				int event = reader.next();
				if (event == XMLStreamConstants.END_ELEMENT) {
					depth--;
				} else if (event == XMLStreamConstants.START_ELEMENT && ++depth == 2) {
					String id = reader.getAttributeValue(null, "id");
					if (Objects.isNull(id)) {
						continue;
					}
					String group = switch (reader.getLocalName()) {
						case "select", "insert", "update", "delete" -> "statement";
						default -> reader.getLocalName();
					};
					String databaseId = Objects.toString(reader.getAttributeValue(null, "databaseId"), "");
					if (!ids.add(List.of(group, id, databaseId))) {
						throw new IOException("Mapper XML 存在重复标识 " + id + "，请先处理冲突: " + targetFile);
					}
				}
			}
		} finally {
			reader.close();
		}
	}
}
