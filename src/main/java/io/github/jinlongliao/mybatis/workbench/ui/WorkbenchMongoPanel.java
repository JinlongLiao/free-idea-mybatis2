package io.github.jinlongliao.mybatis.workbench.ui;

import static io.github.jinlongliao.mybatis.workbench.ui.WorkbenchBundle.message;

import com.intellij.DynamicBundle;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBScrollPane;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;

import org.bson.BsonDocument;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.table.DefaultTableModel;

import io.github.jinlongliao.mybatis.workbench.database.MongoDocumentPage;
import io.github.jinlongliao.mybatis.workbench.database.NoSqlConnectionProfile;
import io.github.jinlongliao.mybatis.workbench.database.WorkbenchMongoConnection;

/**
 * 在工作台浏览 MongoDB 副本集、数据库和集合，执行有界条件查询及单文档编辑。
 *
 * @author liaojinlong
 * @since 2026/11/18 15:40
 */
final class WorkbenchMongoPanel extends JPanel implements Disposable {
	/**
	 * 在界面任务边界记录连接或查询失败。
	 */
	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
	/**
	 * MongoDB 连接 URI，可包含多个种子节点及 replicaSet。
	 */
	private final JTextField uri = new JTextField("mongodb://localhost:27017/");
	/**
	 * MongoDB 用户名，可为空。
	 */
	private final JTextField username = new JTextField();
	/**
	 * 用户所属的认证库。
	 */
	private final JTextField authDatabase = new JTextField("admin");
	/** 当前已选数据源的本机证书校验策略，未选择配置时默认校验。 */
	private boolean verifyCertificate = true;
	/**
	 * 本次连接的密码，关闭窗口清空。
	 */
	private final JPasswordField password = new JPasswordField();
	/**
	 * 从当前连接取得的数据库。
	 */
	private final JComboBox<String> databases = new JComboBox<>();
	/**
	 * 从当前数据库取得的集合。
	 */
	private final JComboBox<String> collections = new JComboBox<>();
	/** 服务端排序字段路径；空值退回按 `_id` 排序。 */
	private final JTextField sortField = new JTextField("_id");
	/** 排序方向；默认升序。 */
	private final JComboBox<Boolean> sortDescending = new JComboBox<>(new Boolean[]{false, true});
	/**
	 * Extended JSON 查询条件。
	 */
	private final JTextArea filter = new JTextArea("{}", 3, 20);
	/**
	 * 当前页的 Extended JSON 结果。
	 */
	private final JTextArea output = new JTextArea();
	/** 当前服务端查询页的字段网格；行顺序与 MongoDB 返回顺序一致。 */
	private final WorkbenchDocumentGrid documentGrid = new WorkbenchDocumentGrid(true);
	/** 查询结果区域同时显示服务端文档网格和当前选择的完整正文。 */
	private final JPanel resultArea = new JPanel(new BorderLayout());
	/**
	 * 仅在结果还有下一页时启用。
	 */
	private final JButton nextPage = new JButton(message("next.page"));
	/** 返回上一段稳定排序范围。 */
	private final JButton previousPage = new JButton(message("previous.page"));
	/** 仅对查询开放；放弃迟到结果但保持任务槽直到当前 Mongo 调用返回。 */
	private final JButton cancelQueryWait = new JButton(message("mongo.cancel.query.wait"));
	/**
	 * 当前操作状态。
	 */
	private final JLabel status = new JLabel(message("connect.to.mongodb.then.select.a.database.and.collection"));
	/** 当前输出页的原始文档，用于定位光标及检测替换冲突。 */
	private MongoDocumentPage currentPage = new MongoDocumentPage(List.of(), false);
	/** 当前页的偏移，成功编辑后用原查询重新加载同一页。 */
	private int currentOffset;
	/** 仅当前结果页有文档时可编辑。 */
	private final JButton editDocument = new JButton(message("mongo.edit.document"));
	/** 在当前集合新增一份 Extended JSON 文档。 */
	private final JButton addDocument = new JButton(message("mongo.add.document"));
	/** 仅允许删除网格选中的当前页文档，并要求确认。 */
	private final JButton deleteDocument = new JButton(message("mongo.delete.document"));
	/** 仅当前结果页有文档时允许将该页导出为 JSON Lines。 */
	private final JButton exportDocuments = new JButton(message("mongo.export.json.lines"));
	/** 独立集合标签页复用相同查询和 CRUD 流程。 */
	private final JPanel form = new JPanel(new GridLayout(0, 2, 4, 4));
	/** 可在集合编辑器中隐藏的连接和集合发现入口。 */
	private final JButton connectButton = new JButton(message("connect.and.list.databases"));
	/** 可在集合编辑器中隐藏的集合枚举入口。 */
	private final JButton browseButton = new JButton(message("list.collections"));
	/** 工具窗口模式显示连接字段；独立标签仅保留集合、筛选和排序。 */
	private final JPanel header = new JPanel(new BorderLayout());

	/**
	 * 下一页开始位置，由 EDT 更新。
	 */
	private int nextOffset;
	/**
	 * 输入变化后递增，通过其丢弃异步旧结果。
	 */
	private long revision;
	/**
	 * 当前后台查询任务，最多一个。
	 */
	private SwingWorker<?, ?> mongoTask;
	/** 当前后台任务是否为可取消结果的只读文档查询。 */
	private boolean mongoQueryWaitCancellable;
	/** 请求丢弃查询结果；后台连接建立后会据此阻止尚未发送的查询。 */
	private volatile boolean mongoQueryWaitCancellationRequested;
	/** 排序列头更新控件期间暂缓 reset，保留上一页直到新查询成功。 */
	private boolean suppressQueryReset;

	/**
	 * 布置集合选择和查询控制区，连接参数从数据源载入。
	 */
	WorkbenchMongoPanel() {
		super(new BorderLayout(4, 4));
		addMongoField(form, message("database"), databases);
		addMongoField(form, message("collection"), collections);
		addMongoField(form, message("filter.extended.json"), new JBScrollPane(filter));
		addMongoField(form, message("sort.field"), sortField);
		addMongoField(form, message("sort.direction"), sortDescending);

		DocumentAdapter connectionListener = new DocumentAdapter() {
			/** {@inheritDoc} 改变连接时清空旧数据库及集合。 */
			@Override
			protected void textChanged(DocumentEvent event) {
				databases.removeAllItems();
				collections.removeAllItems();
				resetMongoQuery();
			}
		};
		documentGrid.getSelectionModel().addListSelectionListener(event -> {
			if (!event.getValueIsAdjusting()) {
				displaySelectedMongoDocument();
			}
		});
		documentGrid.addMouseListener(new MouseAdapter() {
			/** 双击字段弹出值编辑器，维持查询网格的服务端顺序。 */
			@Override
			public void mouseClicked(MouseEvent event) {
				if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() == 2) {
					editMongoDocumentField(documentGrid.rowAtPoint(event.getPoint()),
						documentGrid.columnAtPoint(event.getPoint()));
				}
			}
		});
		documentGrid.getTableHeader().setToolTipText(message("mongo.grid.sort.help"));
		documentGrid.getTableHeader().addMouseListener(new MouseAdapter() {
			/** 列头点击发起对应 Mongo 字段的服务端排序，不改变当前页内顺序。 */
			@Override
			public void mouseClicked(MouseEvent event) {
				if (event.getButton() != MouseEvent.BUTTON1 || event.getClickCount() != 1) {
					return;
				}
				int viewColumn = documentGrid.columnAtPoint(event.getPoint());
				if (viewColumn < 0 || Objects.nonNull(mongoTask)) {
					return;
				}
				int modelColumn = documentGrid.getColumnModel().getColumn(viewColumn).getModelIndex();
				String selectedSortField = documentGrid.getModel().getColumnName(modelColumn);
				boolean descending = selectedSortField.equals(sortField.getText().strip())
					? !Boolean.TRUE.equals(sortDescending.getSelectedItem()) : false;
				String previousSortField = sortField.getText();
				boolean previousDescending = Boolean.TRUE.equals(sortDescending.getSelectedItem());
				suppressQueryReset = true;
				try {
					sortField.setText(selectedSortField);
					sortDescending.setSelectedItem(descending);
				} finally {
					suppressQueryReset = false;
				}
				queryMongoPage(0, () -> restoreMongoSortAfterCancelledQuery(previousSortField, previousDescending));
			}
		});
		for (JTextField field : List.of(uri, username, authDatabase, password)) {
			field.getDocument().addDocumentListener(connectionListener);
		}
		databases.addActionListener(event -> {
			collections.removeAllItems();
			resetMongoQuery();
		});
		collections.addActionListener(event -> {
			resetMongoQuery();
			updateMongoDocumentActions();
		});
		filter.getDocument().addDocumentListener(new DocumentAdapter() {
			/** {@inheritDoc} 改变查询条件后重新从第一页查询。 */
			@Override
			protected void textChanged(DocumentEvent event) {
				if (!suppressQueryReset) {
					resetMongoQuery();
				}
			}
		});
		sortField.getDocument().addDocumentListener(new DocumentAdapter() {
			/** {@inheritDoc} 改变排序字段后下次读取从首页开始。 */
			@Override
			protected void textChanged(DocumentEvent event) {
				if (!suppressQueryReset) {
					resetMongoQuery();
				}
			}
		});
		sortDescending.addActionListener(event -> {
			if (!suppressQueryReset) {
				resetMongoQuery();
			}
		});
		sortDescending.setRenderer(new DefaultListCellRenderer() {
			/** {@inheritDoc} 将布尔方向显示为本地化排序方向。 */
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index,
				boolean selected, boolean focus) {
				super.getListCellRendererComponent(list, value, index, selected, focus);
				setText(Boolean.TRUE.equals(value) ? message("sort.descending") : message("sort.ascending"));
				return this;
			}
		});

		JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
		connectButton.addActionListener(event -> runMongoOperation(message("connect"), connection -> {
			connection.pingMongoServer();
			return connection.listMongoDatabases();
		}, names -> {
			if (refreshMongoSelection(databases, names)) {
				collections.removeAllItems();
				resetMongoQuery();
			}
		}));
		actions.add(connectButton);
		browseButton.addActionListener(event -> loadMongoCollections());
		actions.add(browseButton);
		JButton query = new JButton(message("find.first.page"));
		query.addActionListener(event -> queryMongoPage(0));
		actions.add(query);
		cancelQueryWait.setToolTipText(message("mongo.cancel.query.wait.help"));
		cancelQueryWait.setEnabled(false);
		cancelQueryWait.addActionListener(event -> cancelMongoQueryResultWait());
		actions.add(cancelQueryWait);
		nextPage.setEnabled(false);
		previousPage.setEnabled(false);
		nextPage.addActionListener(event -> queryMongoPage(nextOffset));
		actions.add(nextPage);
		previousPage.setEnabled(false);
		previousPage.addActionListener(event -> queryMongoPage(Math.max(0, currentOffset - 50)));
		actions.add(previousPage);
		addDocument.setEnabled(false);
		addDocument.addActionListener(event -> addMongoDocument());
		actions.add(addDocument);
		editDocument.setEnabled(false);
		editDocument.addActionListener(event -> editMongoDocumentAtCaret());
		actions.add(editDocument);
		deleteDocument.setEnabled(false);
		deleteDocument.addActionListener(event -> deleteMongoDocumentAtCaret());
		actions.add(deleteDocument);
		exportDocuments.setEnabled(false);
		exportDocuments.addActionListener(event -> exportMongoCurrentPage());
		actions.add(exportDocuments);
		JButton importDocuments = new JButton(message("mongo.import.json.lines"));
		importDocuments.addActionListener(event -> importMongoDocuments());
		actions.add(importDocuments);

		header.add(actions, BorderLayout.NORTH);
		header.add(form, BorderLayout.CENTER);
		add(header, BorderLayout.NORTH);
		output.setEditable(false);
		JSplitPane documentAndBody = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
			new JBScrollPane(documentGrid), new JBScrollPane(output));
		documentAndBody.setResizeWeight(0.45);
		resultArea.add(documentAndBody, BorderLayout.CENTER);
		add(resultArea, BorderLayout.CENTER);
		add(status, BorderLayout.SOUTH);
	}

	/**
	 * 从统一数据源树打开已保存连接，在请求前填充配置。
	 *
	 * @param profile    选择的连接设置
	 * @param secret     当前设备保存的认证凭据
	 * @param database   从对象树选中的数据库，空字符串表示未选中
	 * @param collection 从对象树选中的集合，空字符串表示未选中
	 */
	void openSourceProfile(NoSqlConnectionProfile profile, String secret, String database, String collection) {
		verifyCertificate = WorkbenchNoSqlSourceEditor.readNoSqlCertificateVerification(profile);
		uri.setText(profile.endpoint());
		username.setText(profile.username());
		authDatabase.setText(profile.authenticationDatabase());
		password.setText(secret);
		databases.removeAllItems();
		if (!database.isEmpty()) {
			databases.addItem(database);
		}
		if (!collection.isEmpty()) {
			collections.addItem(collection);
		}
		updateMongoDocumentActions();
	}

	/**
	 * 配置独立集合标签页并立即读取首页；连接配置仍只存在于当前编辑器实例。
	 *
	 * @param profile 已保存 MongoDB 连接
	 * @param secret 本机凭据
	 * @param database 树中选中的数据库
	 * @param collection 树中选中的集合
	 */
	void openMongoCollectionEditor(NoSqlConnectionProfile profile, String secret, String database, String collection) {
		openSourceProfile(profile, secret, database, collection);
		connectButton.setVisible(false);
		browseButton.setVisible(false);
		form.removeAll();
		addMongoField(form, message("collection"), new JLabel(database + "." + collection));
		addMongoField(form, message("filter.extended.json"), new JBScrollPane(filter));
		addMongoField(form, message("sort.field"), sortField);
		addMongoField(form, message("sort.direction"), sortDescending);
		form.revalidate();
		form.repaint();
		queryMongoPage(0);
	}

	/**
	 * 增加带关联标签的配置字段。
	 *
	 * @param form  连接表单
	 * @param label 可访问的字段名
	 * @param field 输入组件
	 */
	private void addMongoField(JPanel form, String label, JComponent field) {
		JLabel name = new JLabel(label);
		name.setLabelFor(field);
		form.add(name);
		form.add(field);
	}

	/**
	 * 清除旧分页并使进行中的旧查询结果失效。
	 */
	private void resetMongoQuery() {
		revision++;
		nextOffset = 0;
		currentOffset = 0;
		currentPage = new MongoDocumentPage(List.of(), false);
		documentGrid.setDocuments(List.of());
		exportDocuments.setEnabled(false);
		editDocument.setEnabled(false);
		deleteDocument.setEnabled(false);
		nextPage.setEnabled(false);
		previousPage.setEnabled(false);
		output.setText("");
		updateMongoDocumentActions();
	}

	/**
	 * 仅在当前选择有效时开放集合新增，并在结果页有文档时开放光标删除。
	 */
	private void updateMongoDocumentActions() {
		boolean selectedCollection = collections.getSelectedItem() instanceof String;
		addDocument.setEnabled(selectedCollection);
		deleteDocument.setEnabled(selectedCollection && !currentPage.documents().isEmpty());
	}

	/**
	 * 加载选定数据库中当前账号可见的集合。
	 */
	private void loadMongoCollections() {
		if (!(databases.getSelectedItem() instanceof String database)) {
			status.setText(message("select.a.database.first"));
			return;
		}
		runMongoOperation(message("list.collections"), connection -> connection.listMongoCollections(database),
			names -> {
				if (refreshMongoSelection(collections, names)) {
					resetMongoQuery();
				}
			});
	}

	/**
	 * 刷新 MongoDB 对象清单，原目标消失时等待用户重新选择。
	 *
	 * @param choices 要更新的数据库或集合下拉框
	 * @param names 当前连接可见的对象名称
	 * @return 当前选择是否发生变化，调用方据此清除旧结果
	 */
	private boolean refreshMongoSelection(JComboBox<String> choices, List<String> names) {
		Object previous = choices.getSelectedItem();
		var model = new DefaultComboBoxModel<>(names.toArray(String[]::new));
		if (Objects.nonNull(previous)) {
			model.setSelectedItem(names.contains(previous) ? previous : null);
		}
		choices.setModel(model);
		return !Objects.equals(previous, model.getSelectedItem());
	}

	/**
	 * 使用当前选中的集合及条件查询指定页面。
	 *
	 * @param offset 查询页从零开始的偏移
	 */
	private void queryMongoPage(int offset) {
		queryMongoPage(offset, () -> {
		});
	}

	/** 查询指定页；取消时保留当前成功页，排序表头操作还会恢复旧排序条件。 */
	private void queryMongoPage(int offset, Runnable queryStateRestoreCallback) {
		if (!(databases.getSelectedItem() instanceof String database)
			|| !(collections.getSelectedItem() instanceof String collection)) {
			status.setText(message("select.a.database.and.collection.first"));
			return;
		}
		String query = filter.getText();
		String orderField = sortField.getText();
		boolean descending = Boolean.TRUE.equals(sortDescending.getSelectedItem());
		runCancellableMongoRead(message("find.documents"), connection ->
			connection.queryMongoDocuments(database, collection, query, orderField, descending, offset, 50), page -> {
			currentPage = page;
			currentOffset = offset;
			List<JsonObject> documents = page.documents().stream()
				.map(json -> JsonParser.parseString(json).getAsJsonObject()).toList();
			documentGrid.setDocuments(documents);
			if (!documents.isEmpty()) {
				documentGrid.setRowSelectionInterval(0, 0);
			} else {
				output.setText("");
			}
			editDocument.setEnabled(!page.documents().isEmpty());
			deleteDocument.setEnabled(!page.documents().isEmpty());
			exportDocuments.setEnabled(!page.documents().isEmpty());
			output.setCaretPosition(0);
			nextOffset = offset + page.documents().size();
			nextPage.setEnabled(page.hasNext() && nextOffset <= 10_000);
			previousPage.setEnabled(offset > 0);
			status.setText(message("mongo.query.completed.status", page.documents().size()));
		}, queryStateRestoreCallback);
	}

	/** 已取消表头排序查询时恢复旧排序控件，避免旧网格被误认为使用了新排序。 */
	private void restoreMongoSortAfterCancelledQuery(String previousSortField, boolean previousDescending) {
		suppressQueryReset = true;
		try {
			sortField.setText(previousSortField);
			sortDescending.setSelectedItem(previousDescending);
		} finally {
			suppressQueryReset = false;
		}
	}

	/** 选中网格行后显示完整 Extended JSON 正文，写操作仍使用原始分页快照。 */
	private void displaySelectedMongoDocument() {
		int selected = documentGrid.getSelectedRow();
		if (selected >= 0 && selected < currentPage.documents().size()) {
			output.setText(new GsonBuilder().setPrettyPrinting().create().toJson(
				JsonParser.parseString(currentPage.documents().get(selected))));
			output.setCaretPosition(0);
		}
	}

	/**
	 * 选择并确认追加导入当前集合。文件选择和确认期间若连接变化，不再写入原目标。
	 */
	private void importMongoDocuments() {
		if (Objects.nonNull(mongoTask)) {
			return;
		}
		if (!(databases.getSelectedItem() instanceof String database)
			|| !(collections.getSelectedItem() instanceof String collection)) {
			status.setText(message("select.a.database.and.collection.first"));
			return;
		}
		long importRevision = revision;
		JFileChooser chooser = new JFileChooser();
		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION || importRevision != revision) {
			return;
		}
		Path source = chooser.getSelectedFile().toPath();
		int choice = JOptionPane.showConfirmDialog(this,
			message("mongo.import.confirm", source.toString(), database + "." + collection),
			message("mongo.import.json.lines"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		if (choice != JOptionPane.OK_OPTION || importRevision != revision) {
			return;
		}
		runMongoOperation(message("mongo.import.json.lines"), connection -> {
			try {
				return connection.importMongoJsonLines(database, collection, source);
			} catch (IOException failure) {
				throw new UncheckedIOException(failure.getMessage(), failure);
			}
		}, count -> {
			resetMongoQuery();
			status.setText(message("mongo.import.completed", count));
		});
	}

	/**
	 * 捕获当前查询页并在后台写入新文件。窗口或查询变化时旧导出仍可完成，状态不覆盖当前页面。
	 */
	private void exportMongoCurrentPage() {
		MongoDocumentPage page = currentPage;
		if (page.documents().isEmpty()) {
			return;
		}
		long exportRevision = revision;
		JFileChooser chooser = new JFileChooser();
		if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		Path target = chooser.getSelectedFile().toPath();
		ApplicationManager.getApplication().executeOnPooledThread(() -> {
			String outcome;
			try {
				page.exportMongoJsonLines(target);
				outcome = message("exported") + page.documents().size() + message("documents");
			} catch (IOException failure) {
				log.warn("导出 MongoDB 结果失败，阶段=写入，目标文件={}，文档数={}", target, page.documents().size(), failure);
				outcome = message("mongo.export.failed", failure.getMessage());
			}
			String result = outcome;
			SwingUtilities.invokeLater(() -> {
				if (exportRevision == revision && page == currentPage) {
					status.setText(result);
				}
			});
		});
	}

	/**
	 * 提供逐字段 Extended JSON 与原始文档两种新增方式；只有用户确认且目标集合未变化时才提交。
	 */
	private void addMongoDocument() {
		if (Objects.nonNull(mongoTask) || !(databases.getSelectedItem() instanceof String database)
			|| !(collections.getSelectedItem() instanceof String collection)) {
			status.setText(message("select.a.database.and.collection.first"));
			return;
		}
		long addRevision = revision;
		Object[] modes = {message("mongo.add.fields"), message("mongo.add.raw.json")};
		int mode = JOptionPane.showOptionDialog(this, message("mongo.add.choose.mode"), message("mongo.add.document"),
			JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, modes, modes[0]);
		if (mode < 0) {
			return;
		}
		String document;
		if (mode == 0) {
			JTable fields = createMongoDocumentFieldsTable();
			JLabel validation = new JLabel(" ");
			JPanel fieldForm = createMongoDocumentFieldsForm(fields, validation);
			while (true) {
				int confirmed = JOptionPane.showConfirmDialog(this, fieldForm, message("mongo.add.fields"),
					JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
				if (confirmed != JOptionPane.OK_OPTION) {
					return;
				}
				if (!commitMongoFieldRow(fields)) {
					validation.setText(message("mongo.field.add.invalid.cell"));
					continue;
				}
				try {
					document = buildMongoDocumentFromFields(fields);
					break;
				} catch (IllegalArgumentException invalidField) {
					validation.setText(invalidField.getMessage());
				}
			}
		} else {
			JTextArea json = new JTextArea("{}", 14, 60);
			JPanel jsonForm = new JPanel(new BorderLayout(4, 4));
			jsonForm.add(new JLabel(message("mongo.document.json.input")), BorderLayout.NORTH);
			jsonForm.add(new JBScrollPane(json), BorderLayout.CENTER);
			int confirmed = JOptionPane.showConfirmDialog(this, jsonForm, message("mongo.add.raw.json"),
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
			if (confirmed != JOptionPane.OK_OPTION) {
				return;
			}
			document = json.getText();
		}
		if (addRevision != revision
			|| !Objects.equals(collections.getSelectedItem(), collection)
			|| !Objects.equals(databases.getSelectedItem(), database)) {
			return;
		}
		String documentJson = document;
		runMongoOperation(message("mongo.add.document"), connection ->
			connection.insertMongoDocument(database, collection, documentJson), inserted -> {
			resetMongoQuery();
			status.setText(message("mongo.add.completed"));
			queryMongoPage(0);
		});
	}

	/**
	 * 创建动态字段名与 Extended JSON 值的输入表格，每行最终转换为一个 BSON 字段。
	 *
	 * @return 初始包含一行空草稿的字段表格
	 */
	private JTable createMongoDocumentFieldsTable() {
		DefaultTableModel model = new DefaultTableModel(
			new Object[]{message("mongo.field.name"), message("mongo.field.extended.json.value")}, 0);
		model.addRow(new Object[]{"", ""});
		return new JTable(model);
	}

	/**
	 * 组装逐字段表格和本地草稿行操作；加行或删行不会触达 MongoDB。
	 *
	 * @param fields 字段名和值表格
	 * @param validation 用于在保留草稿的重试中显示行级校验错误
	 * @return 带输入说明和行操作按钮的弹窗内容
	 */
	private JPanel createMongoDocumentFieldsForm(JTable fields, JLabel validation) {
		JPanel panel = new JPanel(new BorderLayout(4, 4));
		JPanel notices = new JPanel(new GridLayout(0, 1, 2, 2));
		notices.add(new JLabel(message("mongo.add.fields.help")));
		notices.add(validation);
		panel.add(notices, BorderLayout.NORTH);
		panel.add(new JBScrollPane(fields), BorderLayout.CENTER);
		JPanel rowActions = new JPanel(new FlowLayout(FlowLayout.LEFT));
		JButton addRow = new JButton(message("mongo.field.add.row"));
		addRow.addActionListener(event -> ((DefaultTableModel) fields.getModel()).addRow(new Object[]{"", ""}));
		rowActions.add(addRow);
		JButton removeRow = new JButton(message("mongo.field.remove.row"));
		removeRow.addActionListener(event -> {
			int selectedRow = fields.getSelectedRow();
			if (selectedRow >= 0) {
				((DefaultTableModel) fields.getModel()).removeRow(fields.convertRowIndexToModel(selectedRow));
			}
		});
		rowActions.add(removeRow);
		panel.add(rowActions, BorderLayout.SOUTH);
		panel.setPreferredSize(new Dimension(620, 340));
		return panel;
	}

	/**
	 * 在确认提交前结束活动单元格编辑，确保最后一次输入进入文档。
	 *
	 * @param fields 字段名和值表格
	 * @return 编辑器已结束或无需结束时返回 true
	 */
	private boolean commitMongoFieldRow(JTable fields) {
		return !fields.isEditing() || fields.getCellEditor().stopCellEditing();
	}

	/**
	 * 校验字段名非空且唯一，保留用户输入的字段名，再将有效值序列化给既有 BSON 插入接口。
	 *
	 * @param fields 已确认编辑结束的字段表格
	 * @return 完整文档的 Extended JSON 文本
	 * @throws IllegalArgumentException 字段重复、缺失或 Extended JSON 无效
	 */
	private String buildMongoDocumentFromFields(JTable fields) {
		DefaultTableModel model = (DefaultTableModel) fields.getModel();
		if (model.getRowCount() == 0) {
			throw new IllegalArgumentException(message("mongo.field.add.invalid.empty"));
		}
		JsonObject document = new JsonObject();
		var strictGson = new GsonBuilder().setStrictness(Strictness.STRICT).create();
		for (int row = 0; row < model.getRowCount(); row++) {
			String fieldName = Objects.toString(model.getValueAt(row, 0), "");
			String rawValue = Objects.toString(model.getValueAt(row, 1), "").strip();
			if (fieldName.isBlank()) {
				throw new IllegalArgumentException(message("mongo.field.add.invalid.name", row + 1));
			}
			if (document.has(fieldName)) {
				throw new IllegalArgumentException(message("mongo.field.add.invalid.duplicate", row + 1, fieldName));
			}
			if (rawValue.isEmpty()) {
				throw new IllegalArgumentException(message("mongo.field.add.invalid.value.required", row + 1));
			}
			JsonElement value;
			try {
				value = strictGson.fromJson(rawValue, JsonElement.class);
			} catch (JsonParseException invalidJson) {
				throw new IllegalArgumentException(message("mongo.field.add.invalid.json", row + 1));
			}
			if (Objects.isNull(value)) {
				throw new IllegalArgumentException(message("mongo.field.add.invalid.json", row + 1));
			}
			try {
				BsonDocument.parse("{\"_workbench_value\":" + value + "}");
			} catch (RuntimeException invalidBsonValue) {
				throw new IllegalArgumentException(message("mongo.field.add.invalid.bson", row + 1));
			}
			document.add(fieldName, value);
		}
		String json = document.toString();
		try {
			BsonDocument.parse(json);
		} catch (RuntimeException invalidBsonDocument) {
			throw new IllegalArgumentException(message("mongo.field.add.invalid.document"));
		}
		return json;
	}

	/**
	 * 二次确认后删除光标所在的当前页文档。服务端按原始 BSON 文档做原子冲突检查。
	 */
	private void deleteMongoDocumentAtCaret() {
		if (Objects.nonNull(mongoTask) || !(databases.getSelectedItem() instanceof String database)
			|| !(collections.getSelectedItem() instanceof String collection)) {
			status.setText(message("select.a.database.and.collection.first"));
			return;
		}
		String original = mongoDocumentAtCaret();
		if (Objects.isNull(original)) {
			status.setText(message("mongo.edit.select.document"));
			return;
		}
		long deleteRevision = revision;
		int confirmed = JOptionPane.showConfirmDialog(this,
			message("mongo.delete.confirm", database + "." + collection), message("mongo.delete.document"),
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		if (confirmed != JOptionPane.OK_OPTION || deleteRevision != revision
			|| !Objects.equals(collections.getSelectedItem(), collection)
			|| !Objects.equals(databases.getSelectedItem(), database)
			|| !currentPage.documents().contains(original)) {
			return;
		}
		int pageOffset = currentOffset;
		runMongoOperation(message("mongo.delete.document"), connection ->
			connection.deleteMongoDocument(database, collection, original), deleted -> {
			if (deleted) {
				resetMongoQuery();
				status.setText(message("mongo.delete.completed"));
				queryMongoPage(Math.min(pageOffset, 10_000));
			} else {
				status.setText(message("mongo.edit.conflict"));
			}
		});
	}

	/**
	 * 按文档网格选中行定位当前页中的原始 Extended JSON 文档。
	 *
	 * @return 网格所选文档的原始 Extended JSON；未选择行时为空
	 */
	private String mongoDocumentAtCaret() {
		int selected = documentGrid.getSelectedRow();
		return selected >= 0 && selected < currentPage.documents().size()
			? currentPage.documents().get(selected) : null;
	}

	/**
	 * 从所点击的文档和字段读取完整 Extended JSON 值，提交前校验页面版本，保存时复用整文档冲突保护。
	 *
	 * @param row 被点击的视图行
	 * @param column 被点击的视图列
	 * @see <a href="https://www.mongodb.com/docs/manual/reference/mongodb-extended-json/">MongoDB Extended JSON</a>
	 */
	private void editMongoDocumentField(int row, int column) {
		if (row < 0 || column < 0 || Objects.nonNull(mongoTask)
			|| !(databases.getSelectedItem() instanceof String database)
			|| !(collections.getSelectedItem() instanceof String collection)) {
			return;
		}
		int modelColumn = documentGrid.convertColumnIndexToModel(column);
		String field = documentGrid.getModel().getColumnName(modelColumn);
		if ("_id".equals(field)) {
			status.setText(message("mongo.field.id.read.only"));
			return;
		}
		String original = currentPage.documents().get(documentGrid.convertRowIndexToModel(row));
		JsonObject updated = JsonParser.parseString(original).getAsJsonObject();
		JsonElement existing = updated.get(field);
		long editRevision = revision;
		int pageOffset = currentOffset;
		String input = JOptionPane.showInputDialog(this, message("mongo.field.edit.help", field),
			Objects.isNull(existing) ? "" : existing.toString());
		if (Objects.isNull(input) || editRevision != revision || Objects.nonNull(mongoTask)) {
			return;
		}
		try {
			if (input.isBlank()) {
				status.setText(message("mongo.field.edit.invalid"));
				return;
			}
			JsonElement value = new GsonBuilder().setStrictness(Strictness.STRICT).create().fromJson(input, JsonElement.class);
			// 比较序列化值，避免 Gson 把 1 和 1.0 视为相同而丢掉 BSON 类型修改。
			if (Objects.isNull(existing) || !existing.toString().equals(value.toString())) {
				updated.add(field, value);
				saveMongoDocumentReplacement(database, collection, original, updated.toString(), pageOffset);
			}
		} catch (JsonParseException invalidValue) {
			status.setText(message("mongo.field.edit.invalid"));
		}
	}

	/**
	 * 编辑光标所在文档。确认后依据原始文档进行有条件的整条替换，成功则刷新原结果页。
	 */
	private void editMongoDocumentAtCaret() {
		if (Objects.nonNull(mongoTask) || !(databases.getSelectedItem() instanceof String database)
			|| !(collections.getSelectedItem() instanceof String collection)) {
			return;
		}
		String original = mongoDocumentAtCaret();
		if (Objects.isNull(original)) {
			status.setText(message("mongo.edit.select.document"));
			return;
		}
		long editRevision = revision;
		int pageOffset = currentOffset;
		JTextArea json = new JTextArea(original, 16, 62);
		JPanel editForm = new JPanel(new BorderLayout(4, 4));
		editForm.add(new JLabel(message("mongo.edit.replace.notice")), BorderLayout.NORTH);
		editForm.add(new JBScrollPane(json), BorderLayout.CENTER);
		int confirmed = JOptionPane.showConfirmDialog(this, editForm, message("mongo.edit.document"),
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (confirmed != JOptionPane.OK_OPTION || editRevision != revision || original.equals(json.getText())
			|| !Objects.equals(collections.getSelectedItem(), collection)
			|| !Objects.equals(databases.getSelectedItem(), database)) {
			return;
		}
		saveMongoDocumentReplacement(database, collection, original, json.getText(), pageOffset);
	}

	/**
	 * 原子校验原文后替换文档，成功时按原查询条件刷新原页。
	 *
	 * @param database 已核对的数据库
	 * @param collection 已核对的集合
	 * @param original 查询时的 BSON 快照
	 * @param replacement 用户确认的新文档
	 * @param pageOffset 修改前的分页位置
	 */
	private void saveMongoDocumentReplacement(String database, String collection, String original,
											  String replacement, int pageOffset) {
		runMongoOperation(message("mongo.edit.document"), connection ->
			connection.replaceMongoDocument(database, collection, original, replacement), saved -> {
			resetMongoQuery();
			if (saved) {
				queryMongoPage(pageOffset);
			} else {
				status.setText(message("mongo.edit.conflict"));
			}
		});
	}

	/**
	 * 在后台连接并执行单个 MongoDB 操作，返回 EDT 后只更新当前配置的结果。
	 *
	 * @param stage     操作阶段
	 * @param operation 获取数据的后台操作
	 * @param callback  界面更新回调
	 * @param <T>       后台结果类型
	 */
	private <T> void runMongoOperation(String stage, Function<WorkbenchMongoConnection, T> operation,
									   Consumer<T> callback) {
		runMongoOperation(stage, operation, callback, false, () -> {
		});
	}

	/** 只读文档查询可以丢弃结果，但不会中断驱动网络调用或提前释放任务槽。 */
	private <T> void runCancellableMongoRead(String stage, Function<WorkbenchMongoConnection, T> operation,
										 Consumer<T> callback, Runnable queryStateRestoreCallback) {
		runMongoOperation(stage, operation, callback, true, queryStateRestoreCallback);
	}

	/**
	 * 在后台执行 Mongo 操作；仅显式只读查询允许取消结果，写入操作不暴露取消入口。
	 *
	 * @param stage 操作阶段
	 * @param operation 后台 Mongo 操作
	 * @param callback 成功结果回调
	 * @param cancellableRead 是否为只读查询
	 * @param queryStateRestoreCallback 查询失败或结果被取消后的界面状态恢复动作
	 * @param <T> 操作结果类型
	 */
	private <T> void runMongoOperation(String stage, Function<WorkbenchMongoConnection, T> operation,
								   Consumer<T> callback, boolean cancellableRead, Runnable queryStateRestoreCallback) {
		if (Objects.nonNull(mongoTask)) {
			return;
		}
		String address = uri.getText().strip();
		String user = username.getText();
		String auth = authDatabase.getText().strip();
		char[] secret = password.getPassword();
		boolean verifyServerCertificate = verifyCertificate;
		long currentRevision = revision;
		mongoQueryWaitCancellable = cancellableRead;
		mongoQueryWaitCancellationRequested = false;
		cancelQueryWait.setEnabled(cancellableRead);
		status.setText(stage + message("in.progress"));
		mongoTask = new SwingWorker<T, Void>() {
			/** {@inheritDoc} 客户端用于本次请求，完成后关闭。 */
			@Override
			protected T doInBackground() {
				try (var connection = new WorkbenchMongoConnection(address, user, auth, secret,
					verifyServerCertificate, DynamicBundle.getLocale())) {
					if (cancellableRead && mongoQueryWaitCancellationRequested) {
						return null;
					}
					return operation.apply(connection);
				} finally {
					Arrays.fill(secret, '\0');
				}
			}

			/** {@inheritDoc} 忽略旧连接或已取消的请求。 */
			@Override
			protected void done() {
				mongoTask = null;
				boolean resultCancelled = mongoQueryWaitCancellationRequested;
				mongoQueryWaitCancellable = false;
				mongoQueryWaitCancellationRequested = false;
				cancelQueryWait.setEnabled(false);
				if (isCancelled() || currentRevision != revision) {
					return;
				}
				try {
					T result = get();
					if (resultCancelled) {
						queryStateRestoreCallback.run();
						status.setText(message("mongo.query.cancelled"));
						return;
					}
					status.setText(stage + message("completed"));
					callback.accept(result);
				} catch (InterruptedException failure) {
					Thread.currentThread().interrupt();
				} catch (ExecutionException failure) {
					Throwable cause = failure.getCause();
					log.warn("MongoDB 操作失败，阶段={}，认证库={}", stage, auth, cause);
					if (resultCancelled || cancellableRead) {
						queryStateRestoreCallback.run();
					}
					status.setText(resultCancelled ? message("mongo.query.cancelled")
						: stage + message("failed") + cause.getMessage());
				}
			}
		};
		mongoTask.execute();
	}

	/** 放弃当前只读查询的结果，保留上一成功页且等待驱动 I/O 返回后释放任务槽。 */
	private void cancelMongoQueryResultWait() {
		if (Objects.isNull(mongoTask) || !mongoQueryWaitCancellable || mongoQueryWaitCancellationRequested) {
			return;
		}
		mongoQueryWaitCancellationRequested = true;
		cancelQueryWait.setEnabled(false);
		status.setText(message("mongo.query.cancel.requested"));
	}

	/**
	 * 关闭窗口时请求取消任务，并释放密码字段。
	 */
	@Override
	public void dispose() {
		revision++;
		mongoQueryWaitCancellable = false;
		cancelQueryWait.setEnabled(false);
		if (Objects.nonNull(mongoTask)) {
			mongoTask.cancel(true);
		}
		password.setText("");
	}
}
