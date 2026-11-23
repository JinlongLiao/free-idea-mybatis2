package io.github.jinlongliao.mybatis.workbench.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.ui.table.JBTable;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;

/**
 * 将当前文档查询页投影为只读字段网格。行顺序与服务端结果相同，
 * 调用方以所选行定位完整正文和写入快照。
 *
 * @author liaojinlong
 * @since 2026/11/22 14:03
 */
final class WorkbenchDocumentGrid extends JBTable {
	/** 限制异构文档同页产生的列数，额外字段仍可通过选中行查看完整正文。 */
	private static final int MAX_COLUMNS = 64;

	/** 单个单元格最多显示的字符数，正文保留完整值。 */
	private static final int MAX_CELL_CHARACTERS = 160;

	/** MongoDB 网格预览会展开 BSON 标量包装，原文仍用于详情和写入。 */
	private final boolean mongoExtendedJson;

	/** 仅供浏览的表模型，任何修改由调用方的文档编辑操作提交。 */
	private final DefaultTableModel documentModel = new DefaultTableModel() {
		/** {@inheritDoc} 网格不执行单元格修改。 */
		@Override
		public boolean isCellEditable(int row, int column) {
			return false;
		}
	};

	/**
	 * 初始化网格，保留服务端的行顺序。
	 * @param mongoExtendedJson 是否按 MongoDB Extended JSON 展示预览值
	 */
	WorkbenchDocumentGrid(boolean mongoExtendedJson) {
		this.mongoExtendedJson = mongoExtendedJson;
		setModel(documentModel);
		setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		setAutoResizeMode(AUTO_RESIZE_OFF);
	}

	/**
	 * 在 EDT 替换当前页。优先展示文档标识，其余列按首次出现的顺序收集。
	 * 数据库返回的缺失字段显示为空白，显式 JSON null 显示为 null。
	 *
	 * @param documents 当前一页的文档，顺序与查询结果一致
	 */
	void setDocuments(List<JsonObject> documents) {
		Set<String> fields = new LinkedHashSet<>(MAX_COLUMNS);
		if (documents.stream().anyMatch(document -> document.has("_id"))) {
			fields.add("_id");
		}
		for (JsonObject document : documents) {
			for (String field : document.keySet()) {
				fields.add(field);
				if (fields.size() >= MAX_COLUMNS) {
					break;
				}
			}
			if (fields.size() >= MAX_COLUMNS) {
				break;
			}
		}
		String[] columns = fields.toArray(String[]::new);
		var values = new String[documents.size()][columns.length];
		for (int row = 0; row < documents.size(); row++) {
			JsonObject document = documents.get(row);
			for (int column = 0; column < columns.length; column++) {
				values[row][column] = formatDocumentCell(document.get(columns[column]));
			}
		}
		documentModel.setDataVector(values, columns);
		for (int column = 0; column < columns.length; column++) {
			getColumnModel().getColumn(column).setPreferredWidth("_id".equals(columns[column]) ? 180 : 140);
		}
	}

	/**
	 * 生成简短的单元格文本。对象和数组保留类型提示，避免在网格内序列化整棵文档。
	 *
	 * @param value 文档字段，缺失时为 null
	 * @return 可直接显示的字段预览
	 * @see <a href="https://www.mongodb.com/docs/manual/reference/mongodb-extended-json/">MongoDB Extended JSON v2</a>
	 */
	private String formatDocumentCell(JsonElement value) {
		if (Objects.isNull(value)) {
			return "";
		}
		if (value.isJsonObject()) {
			JsonObject object = value.getAsJsonObject();
			if (mongoExtendedJson && object.size() == 1) {
				var field = object.entrySet().iterator().next();
				switch (field.getKey()) {
					case "$oid", "$numberInt", "$numberLong", "$numberDouble", "$numberDecimal" -> {
						if (field.getValue().isJsonPrimitive()) {
							return formatDocumentCell(field.getValue());
						}
					}
					case "$date" -> {
						JsonElement date = field.getValue();
						if (date.isJsonPrimitive()) {
							return formatDocumentCell(date);
						}
						if (date.isJsonObject()) {
							JsonObject wrapper = date.getAsJsonObject();
							JsonElement millis = wrapper.get("$numberLong");
							if (wrapper.size() == 1 && Objects.nonNull(millis) && millis.isJsonPrimitive()) {
								try {
									return Instant.ofEpochMilli(Long.parseLong(millis.getAsString())).toString();
								} catch (NumberFormatException ignored) {
									// 无效的包装仍按原对象展示，正文保留实际格式。
								}
							}
						}
					}
					default -> { }
				}
			}
			return object.isEmpty() ? "{}" : "{…}";
		}
		if (value.isJsonArray()) {
			return value.getAsJsonArray().isEmpty() ? "[]" : "[…]";
		}
		String text = value.isJsonNull() ? "null" : value.getAsString();
		if (text.length() > MAX_CELL_CHARACTERS) {
			return text.substring(0, MAX_CELL_CHARACTERS) + "…";
		}
		return text;
	}
}
