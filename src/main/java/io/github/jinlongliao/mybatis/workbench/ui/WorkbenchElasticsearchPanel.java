package io.github.jinlongliao.mybatis.workbench.ui;

import static io.github.jinlongliao.mybatis.workbench.ui.WorkbenchBundle.message;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.intellij.DynamicBundle;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBScrollPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.swing.*;
import javax.swing.event.DocumentEvent;

import io.github.jinlongliao.mybatis.workbench.database.NoSqlConnectionProfile;
import io.github.jinlongliao.mybatis.workbench.database.WorkbenchElasticsearchConnection;

/**
 * Elasticsearch 工作台，支持 HTTPS API Key、索引和别名浏览、Mapping 与 Query DSL。
 *
 * @author liaojinlong
 * @since 2026/11/18 15:45
 */
final class WorkbenchElasticsearchPanel extends JPanel implements Disposable {
	/**
	 * 记录请求失败的阶段和根因。
	 */
	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	/**
	 * Elasticsearch HTTPS 基础地址。
	 */
	private final JTextField endpoint = new JTextField("https://localhost:9200");
	/** Basic 认证用户名；留空时密码字段填写 encoded API Key。 */
	private final JTextField username = new JTextField();
	/**
	 * Basic 密码或 encoded API Key，界面关闭时清空并由 PasswordSafe 关联存储。
	 */
	private final JPasswordField apiKey = new JPasswordField();
	/**
	 * 私有 CA PEM 文件，可留空使用默认信任库。
	 */
	private final JTextField caPath = new JTextField();
	/** 连接对应数据源的本机证书校验策略，打开保存配置时载入。 */
	private boolean verifyCertificate = true;
	/**
	 * 支持直接作为 Mapping 和 Search 目标的索引及别名。
	 */
	private final JComboBox<String> targets = new JComboBox<>();
	/** 当前搜索响应中的命中选择器；写操作令牌只能来自此响应。 */
	private final JComboBox<String> documentHits = new JComboBox<>();
	/** 用户显式用 stored_fields=_none_ 隐藏字段时，禁止无法携带 routing 的命中文档写操作。 */
	private boolean currentSearchAllowsDocumentMutation;
	/** 与命中选择器索引对应的搜索响应快照。 */
	private List<JsonObject> currentSearchHits = List.of();
	/**
	 * 用户编写的 Query DSL 对象。
	 */
	private final JTextArea query = new JTextArea("{\"query\":{\"match_all\":{}},\"size\":25}", 4, 20);
	/**
	 * Elasticsearch 响应 JSON。
	 */
	private final JTextArea output = new JTextArea();
	/** 独立索引编辑器当前服务端页面中的文档字段，行号与命中快照一致。 */
	private final WorkbenchDocumentGrid documentRows = new WorkbenchDocumentGrid(false);
	/** 搜索响应原文，用于在单文档预览期间仍导出完整 envelope。 */
	private String lastFullResponse = "";
	/** 当前响应页的所有命中，网格行号与写入令牌选择器保持一致。 */
	private List<JsonObject> currentEditorHits = List.of();
	/** 独立索引编辑器使用的服务端排序字段，空白时沿用 Query DSL 的排序。 */
	private final JTextField sortField = new JTextField();
	/** 服务器端排序方向。 */
	private final JComboBox<Boolean> sortDescending = new JComboBox<>(new Boolean[]{false, true});
	/** 索引编辑器每页请求的文档数，限制为 1 至 100。 */
	private final JSpinner pageSize = new JSpinner(new SpinnerNumberModel(50, 1, 100, 1));
	/** 独立索引编辑器的服务端分页按钮。 */
	private final JButton previousSearchPage = new JButton(message("previous.page"));
	private final JButton nextSearchPage = new JButton(message("next.page"));
	/** 当前服务端分页起点；筛选或排序改变时重置为零。 */
	private int searchFrom;
	/** 当前请求向用户展示的文档数；最后一页会按服务端 from+size 窗口缩小。 */
	private int currentPageLength = 50;
	/** 最近响应是否还有下一页。 */
	private boolean hasNextSearchPage;
	/** 由索引或别名树节点打开时启用有界分页和编辑器布局。 */
	private boolean standaloneTargetEditor;
	/** 索引和动作组件由独立标签页重排显示。 */
	private final JPanel form = new JPanel(new GridLayout(0, 2, 4, 4));
	private final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
	private final JButton connectButton = new JButton(message("connect.and.load.indices"));
	private final JButton mappingButton = new JButton(message("show.mapping"));
	private final JPanel header = new JPanel(new BorderLayout());
	/** 普通工具窗口显示 envelope，独立编辑器显示文档列表与 JSON 正文。 */
	private final JPanel resultArea = new JPanel(new BorderLayout());
	/**
	 * 保存当前显示的 Mapping 或 Search 响应，未收到响应时禁用。
	 */
	private final JButton exportResponse = new JButton(message("elasticsearch.export.json"));
	/**
	 * 操作执行状态和失败提示。
	 */
	private final JLabel status = new JLabel(message("enter.https.settings.and.credentials.then.load.indices"));
	/**
	 * 状态变化时使旧请求的 UI 结果失效。
	 */
	private long connectionRevision;
	/**
	 * 在 EDT 中跟踪当前后台操作。
	 */
	private SwingWorker<?, ?> elasticsearchTask;
	/** 当前普通只读搜索的取消入口；仅在 EDT 创建、读取和清理。 */
	private Runnable cancelSearchAction;
	/** 当前 UI 任务是否已经请求中断只读搜索。 */
	private final JButton cancelSearch = new JButton(message("elasticsearch.search.cancel"));

	/**
	 * 布置索引和 Query DSL 操作界面，连接参数从数据源载入。
	 */
	WorkbenchElasticsearchPanel() {
		super(new BorderLayout(4, 4));
		addElasticsearchField(form, message("index.or.alias"), targets);
		addElasticsearchField(form, message("elasticsearch.document.selected.hit"), documentHits);
		documentHits.setEnabled(false);
		documentRows.getSelectionModel().addListSelectionListener(event -> {
			if (!event.getValueIsAdjusting()) {
				displaySelectedElasticsearchDocument();
			}
		});
		documentRows.addMouseListener(new MouseAdapter() {
			/** 双击结果行，使用该行对应的命中快照打开完整文档编辑器。 */
			@Override
			public void mouseClicked(MouseEvent event) {
				if (!SwingUtilities.isLeftMouseButton(event) || event.getClickCount() != 2) {
					return;
				}
				int row = documentRows.rowAtPoint(event.getPoint());
				if (row >= 0) {
					documentRows.setRowSelectionInterval(row, row);
					mutateSelectedElasticsearchDocument(false);
				}
			}
		});
		addElasticsearchField(form, message("username"), username);
		addElasticsearchField(form, message("search.api.key.or.password"), apiKey);
		addElasticsearchField(form, "Query DSL", new JBScrollPane(query));
		query.getDocument().addDocumentListener(new DocumentAdapter() {
			/** {@inheritDoc} 搜索条件变化后使命中快照和并发令牌失效。 */
			@Override
			protected void textChanged(DocumentEvent event) {
				connectionRevision++;
				searchFrom = 0;
				hasNextSearchPage = false;
				updateSearchPagingButtons();
				clearSearchHits();
				if (standaloneTargetEditor) {
					output.setText("");
					lastFullResponse = "";
					exportResponse.setEnabled(false);
				}
			}
		});
		sortField.getDocument().addDocumentListener(new DocumentAdapter() {
			/** {@inheritDoc} 排序字段改变后从首屏重新查询。 */
			@Override
			protected void textChanged(DocumentEvent event) {
				searchFrom = 0;
				hasNextSearchPage = false;
				updateSearchPagingButtons();
				connectionRevision++;
				clearSearchHits();
				if (standaloneTargetEditor) {
					output.setText("");
					lastFullResponse = "";
					exportResponse.setEnabled(false);
				}
			}
		});
		sortDescending.addActionListener(event -> {
			searchFrom = 0;
			hasNextSearchPage = false;
			updateSearchPagingButtons();
			connectionRevision++;
			clearSearchHits();
			if (standaloneTargetEditor) {
				output.setText("");
				lastFullResponse = "";
				exportResponse.setEnabled(false);
			}
		});
		sortDescending.setRenderer(new DefaultListCellRenderer() {
			/** {@inheritDoc} 将排序方向转换为宿主语言。 */
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index,
				boolean selected, boolean focus) {
				super.getListCellRendererComponent(list, value, index, selected, focus);
				setText(Boolean.TRUE.equals(value) ? message("sort.descending") : message("sort.ascending"));
				return this;
			}
		});
		pageSize.addChangeListener(event -> {
			searchFrom = 0;
			currentPageLength = (Integer) pageSize.getValue();
			hasNextSearchPage = false;
			updateSearchPagingButtons();
			clearSearchHits();
			if (standaloneTargetEditor) {
				output.setText("");
				lastFullResponse = "";
				exportResponse.setEnabled(false);
			}
		});
		DocumentAdapter connections = new DocumentAdapter() {
			/** {@inheritDoc} 修改连接时清除旧目标，防止后续请求使用上一个集群的索引。 */
			@Override
			protected void textChanged(DocumentEvent event) {
				connectionRevision++;
				searchFrom = 0;
				currentPageLength = (Integer) pageSize.getValue();
				hasNextSearchPage = false;
				updateSearchPagingButtons();
				targets.removeAllItems();
				clearSearchHits();
				output.setText("");
				exportResponse.setEnabled(false);
				status.setText(message("connection.changed.reload.indices"));
			}
		};
		for (JTextField field : List.of(endpoint, username, apiKey, caPath)) {
			field.getDocument().addDocumentListener(connections);
		}
		targets.addActionListener(event -> {
			connectionRevision++;
			searchFrom = 0;
			currentPageLength = (Integer) pageSize.getValue();
			hasNextSearchPage = false;
			updateSearchPagingButtons();
			clearSearchHits();
			output.setText("");
			exportResponse.setEnabled(false);
		});

		connectButton.addActionListener(event -> {
			Object selectedTarget = targets.getSelectedItem();
			runElasticsearchOperation(message("load.indices"), WorkbenchElasticsearchConnection::listElasticsearchObjects,
				objects -> {
					var names = new DefaultComboBoxModel<String>();
					objects.indices().forEach(names::addElement);
					objects.aliases().forEach(names::addElement);
					// 从对象树打开别名后重新加载目标，不把后续请求切换到默认索引。
					if (names.getIndexOf(selectedTarget) >= 0) {
						names.setSelectedItem(selectedTarget);
					} else if (Objects.nonNull(selectedTarget)) {
						// 原目标已消失，清除其结果并要求用户重新选择，避免查询落到首个索引。
						names.setSelectedItem(null);
						connectionRevision++;
						output.setText("");
						exportResponse.setEnabled(false);
					}
					targets.setModel(names);
					status.setText(message("connected") + objects.indices().size() + message("indices")
						+ objects.aliases().size() + message("aliases"));
				});
		});
		buttons.add(connectButton);
		mappingButton.addActionListener(event -> showSelectedElasticsearchIndex(false));
		buttons.add(mappingButton);
		JButton search = new JButton(message("run.search"));
		search.addActionListener(event -> runElasticsearchSearchFromStart());
		buttons.add(search);
		cancelSearch.setEnabled(false);
		cancelSearch.addActionListener(event -> cancelSelectedElasticsearchSearch());
		buttons.add(cancelSearch);
		JButton addDocument = new JButton(message("elasticsearch.document.add"));
		addDocument.addActionListener(event -> addElasticsearchDocument());
		buttons.add(addDocument);
		JButton replaceDocument = new JButton(message("elasticsearch.document.replace"));
		replaceDocument.addActionListener(event -> mutateSelectedElasticsearchDocument(false));
		buttons.add(replaceDocument);
		JButton deleteDocument = new JButton(message("elasticsearch.document.delete"));
		deleteDocument.addActionListener(event -> mutateSelectedElasticsearchDocument(true));
		buttons.add(deleteDocument);
		exportResponse.setEnabled(false);
		exportResponse.addActionListener(event -> exportElasticsearchResponse());
		buttons.add(exportResponse);
		previousSearchPage.setEnabled(false);
		previousSearchPage.setVisible(false);
		previousSearchPage.addActionListener(event -> {
			searchFrom = Math.max(0, searchFrom - currentPageLength);
			showSelectedElasticsearchIndex(true);
		});
		buttons.add(previousSearchPage);
		nextSearchPage.setEnabled(false);
		nextSearchPage.setVisible(false);
		nextSearchPage.addActionListener(event -> {
			searchFrom += currentPageLength;
			updateSearchPagingButtons();
			showSelectedElasticsearchIndex(true);
		});
		buttons.add(nextSearchPage);
		header.add(buttons, BorderLayout.NORTH);
		header.add(form, BorderLayout.CENTER);
		add(header, BorderLayout.NORTH);
		output.setEditable(false);
		resultArea.add(new JBScrollPane(output), BorderLayout.CENTER);
		add(resultArea, BorderLayout.CENTER);
		add(status, BorderLayout.SOUTH);
	}

	/**
	 * 配置独立索引或别名标签页，首页大小为 50，查询 DSL、排序和分页参数均发送给服务端。
	 *
	 * @param profile 已保存 Elasticsearch 连接
	 * @param secret 本机 API Key 或 Basic 密码
	 * @param target 树中选择的索引或别名
	 */
	void openElasticsearchTargetEditor(NoSqlConnectionProfile profile, String secret, String target) {
		openSourceProfile(profile, secret, target);
		standaloneTargetEditor = true;
		connectButton.setVisible(false);
		mappingButton.setVisible(false);
		form.removeAll();
		addElasticsearchField(form, message("index.or.alias"), targets);
		addElasticsearchField(form, "Query DSL", new JBScrollPane(query));
		JPanel sortControls = new JPanel(new BorderLayout(4, 0));
		sortControls.add(sortField, BorderLayout.CENTER);
		sortControls.add(sortDescending, BorderLayout.EAST);
		sortField.getAccessibleContext().setAccessibleName(message("sort.field"));
		sortDescending.getAccessibleContext().setAccessibleName(message("sort.direction"));
		JPanel pageControls = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
		JLabel pageLabel = new JLabel(message("page.size"));
		pageLabel.setLabelFor(pageSize);
		pageControls.add(pageLabel);
		pageControls.add(pageSize);
		JPanel searchControls = new JPanel(new BorderLayout(8, 0));
		searchControls.add(sortControls, BorderLayout.CENTER);
		searchControls.add(pageControls, BorderLayout.EAST);
		addElasticsearchField(form, message("sort.field"), searchControls);
		query.getInputMap().put(
			KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
			"workbench.elasticsearch.search");
		query.getActionMap().put("workbench.elasticsearch.search", new AbstractAction() {
			/** {@inheritDoc} 使用工具栏相同的执行入口，重置到符合新查询的第一页。 */
			@Override
			public void actionPerformed(ActionEvent event) {
				runElasticsearchSearchFromStart();
			}
		});
		JSplitPane documentAndBody = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
			new JBScrollPane(documentRows), new JBScrollPane(output));
		documentAndBody.setResizeWeight(0.45);
		resultArea.removeAll();
		resultArea.add(documentAndBody, BorderLayout.CENTER);
		previousSearchPage.setVisible(true);
		nextSearchPage.setVisible(true);
		header.revalidate();
		header.repaint();
		searchFrom = 0;
		showSelectedElasticsearchIndex(true);
	}

	/**
	 * 从统一数据源树打开已保存连接，在请求前填充配置。
	 *
	 * @param profile 选择的连接设置
	 * @param secret  当前设备保存的认证凭据
	 * @param target  对象树选中的索引或别名，空字符串表示由用户选择
	 */
	void openSourceProfile(NoSqlConnectionProfile profile, String secret, String target) {
		endpoint.setText(profile.endpoint());
		caPath.setText(WorkbenchNoSqlSourceEditor.readLocalCaPath(profile));
		verifyCertificate = WorkbenchNoSqlSourceEditor.readElasticsearchCertificateVerification(profile);
		username.setText(profile.username());
		apiKey.setText(secret);
		targets.removeAllItems();
		if (!target.isEmpty()) {
			targets.addItem(target);
		}
	}

	/**
	 * 保存连接配置后，仅当认证、端点或本机 TLS 设置实际变化时清理旧结果并替换内存凭据。
	 * 不自动执行请求；用户下一次点击搜索或连接操作时使用新配置。
	 *
	 * @param profile 最新已保存的 Elasticsearch 配置
	 * @param secret PasswordSafe 中的 API Key 或 Basic 密码
	 * @return 配置与当前面板不同并已完成重绑定
	 */
	boolean refreshSavedConnectionIfChanged(NoSqlConnectionProfile profile, String secret) {
		char[] currentSecret = apiKey.getPassword();
		char[] latestSecret = Objects.requireNonNullElse(secret, "").toCharArray();
		boolean settingsChanged;
		try {
			settingsChanged = !Objects.equals(profile.endpoint(), endpoint.getText())
				|| !Objects.equals(profile.username(), username.getText())
				|| !Objects.equals(WorkbenchNoSqlSourceEditor.readLocalCaPath(profile), caPath.getText())
				|| WorkbenchNoSqlSourceEditor.readElasticsearchCertificateVerification(profile) != verifyCertificate
				|| !Arrays.equals(currentSecret, latestSecret);
		} finally {
			Arrays.fill(currentSecret, '\0');
			Arrays.fill(latestSecret, '\0');
		}
		if (!settingsChanged) {
			return false;
		}
		// 先推进请求代次，再替换控件；旧异步响应因此无法覆盖本次配置变化后的页面。
		connectionRevision++;
		String target = Objects.requireNonNullElse((String) targets.getSelectedItem(), "");
		openSourceProfile(profile, secret, target);
		searchFrom = 0;
		currentPageLength = (Integer) pageSize.getValue();
		hasNextSearchPage = false;
		updateSearchPagingButtons();
		clearSearchHits();
		output.setText("");
		lastFullResponse = "";
		exportResponse.setEnabled(false);
		status.setText(message("elasticsearch.connection.settings.changed"));
		return true;
	}

	/**
	 * 添加具有标签关联的输入项。
	 *
	 * @param form  当前表单
	 * @param label 字段显示名称
	 * @param field 输入组件
	 */
	private void addElasticsearchField(JPanel form, String label, JComponent field) {
		JLabel name = new JLabel(label);
		name.setLabelFor(field);
		form.add(name);
		form.add(field);
	}

	/** 执行显式查询时回到首页；分页按钮仍使用自己计算的起点。 */
	private void runElasticsearchSearchFromStart() {
		searchFrom = 0;
		showSelectedElasticsearchIndex(true);
	}

	/**
	 * 对选中目标读取 Mapping 或执行搜索。
	 *
	 * @param search 为 true 时执行 Query DSL
	 */
	private void showSelectedElasticsearchIndex(boolean search) {
		if (!(targets.getSelectedItem() instanceof String index)) {
			status.setText(message("select.an.index.or.alias"));
			return;
		}
		String dsl = query.getText();
		if (search && standaloneTargetEditor) {
			try {
				dsl = buildElasticsearchEditorSearchDsl(dsl);
			} catch (IllegalArgumentException failure) {
				status.setText(failure.getMessage());
				return;
			}
		}
		String requestDsl = dsl;
		if (search) {
			clearSearchHits();
			hasNextSearchPage = false;
			updateSearchPagingButtons();
			if (standaloneTargetEditor) {
				output.setText("");
				lastFullResponse = "";
				exportResponse.setEnabled(false);
			}
		}
		runElasticsearchOperation(search ? message("run.search") : message("load.mapping"), connection -> search
			? connection.searchElasticsearchDocuments(index, requestDsl) : connection.readElasticsearchMapping(index), result -> {
			lastFullResponse = result;
			if (!standaloneTargetEditor || !search) {
				output.setText(result);
				output.setCaretPosition(0);
			}
			if (search) {
				setSearchHits(result, !searchSuppressesStoredFields(requestDsl));
				if (standaloneTargetEditor) {
					updateElasticsearchSearchPaging(result);
				}
			} else {
				clearSearchHits();
			}
				exportResponse.setEnabled(!result.isBlank());
			}, search);
	}

	/**
	 * 将索引编辑器控件转换为服务端 Query DSL 参数；仅本组件产生 from、size 和排序字段。
	 *
	 * @param dsl 用户提供的过滤 Query DSL
	 * @return 合并分页及排序后的 JSON 请求
	 * @throws IllegalArgumentException DSL 无效或分页越过服务端窗口边界
	 */
	private String buildElasticsearchEditorSearchDsl(String dsl) {
		JsonObject request;
		try {
			request = JsonParser.parseString(dsl).getAsJsonObject();
		} catch (RuntimeException failure) {
			throw new IllegalArgumentException(message("search.invalid.query"), failure);
		}
		int requestedSize = (Integer) pageSize.getValue();
		int availableWindow = 10_000 - searchFrom - 1;
		if (searchFrom < 0 || availableWindow < 1) {
			throw new IllegalArgumentException(message("search.page.bounds"));
		}
		currentPageLength = Math.min(requestedSize, availableWindow);
		String orderField = sortField.getText().strip();
		if (orderField.contains("\0")) {
			throw new IllegalArgumentException(message("search.sort.field.invalid"));
		}
		request.addProperty("from", searchFrom);
		// 额外请求一条只用于确认 next-page，不展示给用户；因此 track_total_hits=false 仍可翻页。
		request.addProperty("size", currentPageLength + 1);
		if (!orderField.isEmpty()) {
			JsonObject order = new JsonObject();
			order.addProperty(orderField, Boolean.TRUE.equals(sortDescending.getSelectedItem()) ? "desc" : "asc");
			JsonArray sort = new JsonArray();
			sort.add(order);
			request.add("sort", sort);
		}
		return request.toString();
	}

	/** 根据服务端 total 与当前页边界决定分页状态，避免只凭“恰好满页”误报。 */
	private void updateElasticsearchSearchPaging(String response) {
		int pageLength = currentPageLength;
		try {
			JsonObject root = JsonParser.parseString(response).getAsJsonObject();
			JsonObject hits = root.getAsJsonObject("hits");
			int returnedHits = hits.getAsJsonArray("hits").size();
			hasNextSearchPage = returnedHits > pageLength;
			JsonElement total = hits.get("total");
			if (!hasNextSearchPage && Objects.nonNull(total) && !total.isJsonNull()) {
				long totalValue;
				boolean lowerBound;
				if (total.isJsonPrimitive()) {
					totalValue = total.getAsLong();
					lowerBound = false;
				} else {
					JsonObject count = total.getAsJsonObject();
					totalValue = count.get("value").getAsLong();
					lowerBound = count.has("relation") && count.get("relation").getAsString().equals("gte");
				}
				hasNextSearchPage = lowerBound ? totalValue >= searchFrom + pageLength
					: totalValue > searchFrom + pageLength;
			}
		} catch (RuntimeException failure) {
			hasNextSearchPage = false;
		}
		updateSearchPagingButtons();
		if (hasNextSearchPage && searchFrom + pageLength + 2 > 10_000) {
			status.setText(message("search.page.window.reached"));
		}
	}

	/** 更新独立编辑器分页按钮；服务端最大 from+size 为 10000。 */
	private void updateSearchPagingButtons() {
		int requestedSize = Math.max(1, currentPageLength);
		previousSearchPage.setEnabled(standaloneTargetEditor && searchFrom > 0);
		nextSearchPage.setEnabled(standaloneTargetEditor && hasNextSearchPage
			&& searchFrom + requestedSize + 2 <= 10_000);
	}

	/**
	 * 从已显示的搜索响应提取命中及并发令牌，切换查询结果时丢弃旧快照。
	 *
	 * @param response 本次搜索的 Elasticsearch JSON 响应
	 * @param allowDocumentMutation Search 请求包含 routing 元数据时允许命中写操作
	 */
	private void setSearchHits(String response, boolean allowDocumentMutation) {
		clearSearchHits();
		try {
			JsonObject root = JsonParser.parseString(response).getAsJsonObject();
			var hits = root.getAsJsonObject("hits").getAsJsonArray("hits");
			ArrayList<JsonObject> documents = new ArrayList<>(hits.size());
			int visibleHitCount = standaloneTargetEditor ? Math.min(currentPageLength, hits.size()) : hits.size();
			for (int index = 0; index < visibleHitCount; index++) {
				JsonElement hit = hits.get(index);
				JsonObject value = hit.getAsJsonObject();
				JsonObject snapshot = value.deepCopy();
				String routing = searchHitRouting(snapshot);
				if (Objects.nonNull(routing)) {
					snapshot.addProperty("_routing", routing);
				}
				documents.add(snapshot);
				documentHits.addItem(elasticsearchDocumentHitLabel(snapshot));
			}
			currentSearchHits = List.copyOf(documents);
			currentEditorHits = currentSearchHits;
			currentSearchAllowsDocumentMutation = allowDocumentMutation;
			documentHits.setEnabled(allowDocumentMutation && !currentSearchHits.isEmpty());
			if (standaloneTargetEditor) {
				documentRows.setDocuments(currentEditorHits.stream()
					.map(WorkbenchElasticsearchPanel::projectElasticsearchHitFields).toList());
				if (!currentEditorHits.isEmpty()) {
					documentRows.setRowSelectionInterval(0, 0);
				}
				status.setText(message("this.page") + visibleHitCount + message("documents"));
			}
		} catch (RuntimeException failure) {
			clearSearchHits();
			status.setText(message("elasticsearch.document.search.hits.unavailable"));
		}
	}

	/** Build selector label; missing concurrency metadata remains visible but mutation rejects that hit. */
	private String elasticsearchDocumentHitLabel(JsonObject hit) {
		String index = hit.has("_index") ? hit.get("_index").getAsString() : "?";
		String id = hit.has("_id") ? hit.get("_id").getAsString() : "?";
		return hit.has("_seq_no") && hit.has("_primary_term")
			? index + "/" + id + " (" + hit.get("_seq_no").getAsString() + "/"
				+ hit.get("_primary_term").getAsString() + ")" : index + "/" + id;
	}

	/**
	 * 将命中 ID 和返回字段投影为网格列。若请求禁用 {@code _source}，使用 Search 返回的 fields，
	 * 并解包单值数组，以便直接比较字段值。原始命中保留字段数组及 routing。
	 *
	 * @param hit 原始命中快照
	 * @return 当前行的字段视图
	 */
	private static JsonObject projectElasticsearchHitFields(JsonObject hit) {
		JsonObject row = new JsonObject();
		if (hit.has("_id")) {
			row.add("_id", hit.get("_id"));
		}
		JsonElement payload = hit.get("_source");
		boolean useFieldProjection = Objects.isNull(payload) || !payload.isJsonObject();
		if (useFieldProjection) {
			payload = hit.get("fields");
		}
		if (Objects.nonNull(payload) && payload.isJsonObject()) {
			payload.getAsJsonObject().entrySet().forEach(field -> {
				if (!row.has(field.getKey()) && !"_routing".equals(field.getKey())) {
					JsonElement value = field.getValue();
					if (useFieldProjection && value.isJsonArray() && value.getAsJsonArray().size() == 1) {
						value = value.getAsJsonArray().get(0);
					}
					row.add(field.getKey(), value);
				}
			});
		}
		return row;
	}

	/** 显示选中行对应的文档正文，完整搜索响应用于导出。 */
	private void displaySelectedElasticsearchDocument() {
		int selected = documentRows.getSelectedRow();
		if (selected < 0 || selected >= currentEditorHits.size()) {
			return;
		}
		documentHits.setSelectedIndex(selected);
		JsonObject hit = currentEditorHits.get(selected);
		JsonElement body = hit.has("_source") && !hit.get("_source").isJsonNull()
			? hit.get("_source") : hit;
		output.setText(new GsonBuilder().setPrettyPrinting().create().toJson(body));
		output.setCaretPosition(0);
	}

	/**
	 * 从 Search hit 的顶层元数据或 stored_fields 响应中读取 routing；重复值不一致时拒绝创建可写快照。
	 *
	 * @param hit Elasticsearch Search hit
	 * @return 文档 routing；未分配自定义 routing 时返回 null
	 * @throws IllegalStateException routing 字段格式异常或重复来源冲突
	 */
	private String searchHitRouting(JsonObject hit) {
		String topLevelRouting = hit.has("_routing") && !hit.get("_routing").isJsonNull()
			? requireRoutingString(hit.get("_routing")) : null;
		JsonObject fields = hit.has("fields") && hit.get("fields").isJsonObject()
			? hit.getAsJsonObject("fields") : null;
		if (Objects.isNull(fields) || !fields.has("_routing")) {
			return topLevelRouting;
		}
		JsonElement field = fields.get("_routing");
		String storedRouting;
		if (field.isJsonArray()) {
			JsonArray values = field.getAsJsonArray();
			if (values.size() != 1) {
				throw new IllegalStateException("Elasticsearch hit routing must have one value");
			}
			storedRouting = requireRoutingString(values.get(0));
		} else {
			storedRouting = requireRoutingString(field);
		}
		if (Objects.nonNull(topLevelRouting) && !topLevelRouting.equals(storedRouting)) {
			throw new IllegalStateException("Elasticsearch hit routing sources disagree");
		}
		return storedRouting;
	}

	/** 校验 routing 是字符串标量，避免把异常 Search 响应当作安全写入目标。 */
	private String requireRoutingString(JsonElement routing) {
		if (!routing.isJsonPrimitive() || !routing.getAsJsonPrimitive().isString()) {
			throw new IllegalStateException("Invalid Elasticsearch hit routing");
		}
		return routing.getAsString();
	}

	/** 清除搜索命中和选择器，防止令牌复用于其他响应。 */
	private void clearSearchHits() {
		currentSearchHits = List.of();
		currentEditorHits = List.of();
		documentRows.setDocuments(List.of());
		currentSearchAllowsDocumentMutation = false;
		documentHits.removeAllItems();
		documentHits.setEnabled(false);
	}

	/** 检测用户明确禁用 stored fields 的 DSL；此时保留搜索语义但不允许可能错路由的写入。 */
	private boolean searchSuppressesStoredFields(String dsl) {
		try {
			JsonElement configuredFields = JsonParser.parseString(dsl).getAsJsonObject().get("stored_fields");
			if (Objects.isNull(configuredFields)) {
				return false;
			}
			if (configuredFields.isJsonPrimitive()) {
				for (String field : configuredFields.getAsString().split(",")) {
					if (field.trim().equals("_none_")) {
						return true;
					}
				}
				return false;
			}
			if (configuredFields.isJsonArray()) {
				for (JsonElement field : configuredFields.getAsJsonArray()) {
					if (field.isJsonPrimitive() && field.getAsString().equals("_none_")) {
						return true;
					}
				}
			}
		} catch (RuntimeException ignored) {
			// 搜索连接层会为非法 DSL 提供错误；此检查只决定成功搜索后的写入边界。
		}
		return false;
	}

	/**
	 * 显示文档对象输入框，只有确认时数据源和查询上下文仍有效才向所选索引写入。
	 */
	private void addElasticsearchDocument() {
		if (!(targets.getSelectedItem() instanceof String index)) {
			status.setText(message("select.an.index.or.alias"));
			return;
		}
		long mutationRevision = connectionRevision;
		JTextArea document = new JTextArea("{}", 12, 60);
		int choice = JOptionPane.showConfirmDialog(this, new JBScrollPane(document),
			message("elasticsearch.document.add"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (choice != JOptionPane.OK_OPTION || mutationRevision != connectionRevision) {
			return;
		}
		String json = document.getText();
		String dsl = query.getText();
		runElasticsearchOperation(message("elasticsearch.document.add"),
			connection -> connection.indexElasticsearchDocument(index, json),
			result -> refreshElasticsearchSearchAfterMutation(index, dsl));
	}

	/**
	 * 更新先按命中 ID 重新读取完整正文和新并发令牌，避免 Query DSL 对正文的裁剪造成空文档覆盖。
	 * 删除使用当前搜索快照的令牌，并在二次确认后提交；两类写入都由服务端拒绝过期令牌。
	 *
	 * @param delete 为 true 时删除文档，否则替换文档
	 */
	private void mutateSelectedElasticsearchDocument(boolean delete) {
		if (!currentSearchAllowsDocumentMutation) {
			status.setText(message("elasticsearch.document.routing.unavailable"));
			return;
		}
		if (!(targets.getSelectedItem() instanceof String index)) {
			status.setText(message("select.an.index.or.alias"));
			return;
		}
		int selectedHit = documentHits.getSelectedIndex();
		if (selectedHit < 0 || selectedHit >= currentSearchHits.size()) {
			status.setText(message("elasticsearch.document.select.hit"));
			return;
		}
		JsonObject hit = currentSearchHits.get(selectedHit);
		String documentIndex;
		String documentId;
		String routing;
		long sequence;
		long term;
		try {
			documentIndex = hit.get("_index").getAsString();
			documentId = hit.get("_id").getAsString();
			if (hit.has("_routing") && !hit.get("_routing").isJsonNull() && !hit.get("_routing").isJsonPrimitive()) {
				throw new IllegalStateException("Invalid Elasticsearch hit routing");
			}
			routing = hit.has("_routing") && !hit.get("_routing").isJsonNull()
				? hit.get("_routing").getAsString() : null;
			sequence = hit.get("_seq_no").getAsLong();
			term = hit.get("_primary_term").getAsLong();
		} catch (RuntimeException failure) {
			status.setText(message("elasticsearch.document.search.hits.unavailable"));
			return;
		}
		long mutationRevision = connectionRevision;
		if (delete && JOptionPane.showConfirmDialog(this, message("elasticsearch.document.delete.confirm"),
			message("elasticsearch.document.delete"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
			!= JOptionPane.YES_OPTION) {
			return;
		}
		if (mutationRevision != connectionRevision) {
			return;
		}
		String dsl = query.getText();
		if (delete) {
			runElasticsearchOperation(message("elasticsearch.document.delete"),
				connection -> connection.deleteElasticsearchDocument(documentIndex, documentId, routing, sequence, term),
				result -> refreshElasticsearchSearchAfterMutation(index, dsl));
		} else {
			runElasticsearchOperation(message("elasticsearch.document.load.for.edit"),
				connection -> connection.readElasticsearchDocumentForUpdate(documentIndex, documentId, routing),
				response -> showElasticsearchDocumentEditor(index, documentIndex, documentId, routing, response, dsl));
		}
	}

	/**
	 * 只在 GET 返回完整 JSON `_source` 和当前版本令牌时打开替换框；索引禁用 `_source` 时不提供写入。
	 *
	 * @param refreshTarget 已选索引或别名，用于写入完成后的原查询刷新
	 * @param documentIndex 搜索 hit 的具体 `_index`
	 * @param expectedId 用户所选搜索命中的 ID
	 * @param routing hit 的可选 `_routing`
	 * @param response GET 文档响应
	 * @param dsl 本次搜索使用的 Query DSL，写入后用同一条件刷新
	 */
	private void showElasticsearchDocumentEditor(String refreshTarget, String documentIndex, String expectedId,
		String routing, String response, String dsl) {
		JsonObject snapshot;
		long sequence;
		long term;
		try {
			snapshot = JsonParser.parseString(response).getAsJsonObject();
			if (!snapshot.get("_index").getAsString().equals(documentIndex)
				|| !snapshot.get("_id").getAsString().equals(expectedId) || !snapshot.has("_seq_no")
				|| !snapshot.has("_primary_term") || !snapshot.has("_source") || !snapshot.get("_source").isJsonObject()) {
				status.setText(message("elasticsearch.document.full.source.unavailable"));
				return;
			}
			sequence = snapshot.get("_seq_no").getAsLong();
			term = snapshot.get("_primary_term").getAsLong();
		} catch (RuntimeException failure) {
			status.setText(message("elasticsearch.document.full.source.unavailable"));
			return;
		}
		JTextArea document = new JTextArea(new GsonBuilder().setPrettyPrinting().create().toJson(snapshot.getAsJsonObject("_source")),
			12, 60);
		JPanel form = new JPanel(new BorderLayout(4, 4));
		form.add(new JLabel(message("elasticsearch.document.selected.hit") + ": " + expectedId), BorderLayout.NORTH);
		form.add(new JBScrollPane(document), BorderLayout.CENTER);
		long mutationRevision = connectionRevision;
		if (JOptionPane.showConfirmDialog(this, form, message("elasticsearch.document.replace"),
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION
			|| mutationRevision != connectionRevision) {
			return;
		}
		String json = document.getText();
		runElasticsearchOperation(message("elasticsearch.document.replace"),
			connection -> connection.replaceElasticsearchDocument(documentIndex, expectedId, routing, sequence, term, json),
			result -> refreshElasticsearchSearchAfterMutation(refreshTarget, dsl));
	}

	/**
	 * 写操作成功后按原搜索条件刷新结果，令下一次编辑继续绑定新版本令牌。
	 *
	 * @param index 原写入目标
	 * @param dsl 写入前的搜索条件
	 */
	private void refreshElasticsearchSearchAfterMutation(String index, String dsl) {
		clearSearchHits();
		searchFrom = 0;
		if (standaloneTargetEditor) {
			output.setText("");
			lastFullResponse = "";
			exportResponse.setEnabled(false);
		}
		String requestDsl;
		try {
			requestDsl = standaloneTargetEditor ? buildElasticsearchEditorSearchDsl(dsl) : dsl;
		} catch (IllegalArgumentException failure) {
			status.setText(failure.getMessage());
			return;
		}
		runElasticsearchOperation(message("run.search"),
			connection -> connection.searchElasticsearchDocuments(index, requestDsl), result -> {
				lastFullResponse = result;
				if (!standaloneTargetEditor) {
					output.setText(result);
					output.setCaretPosition(0);
				}
				setSearchHits(result, !searchSuppressesStoredFields(requestDsl));
				if (standaloneTargetEditor) {
					updateElasticsearchSearchPaging(result);
				}
				exportResponse.setEnabled(!result.isBlank());
				status.setText(message("elasticsearch.document.operation.refreshed"));
			});
	}

	/**
	 * 保存打开选择器时显示的 JSON 响应。后台以 UTF-8 创建新文件，拒绝覆盖已有文件。
	 * 切换目标或开始新请求后，旧导出的状态不替换当前请求状态。
	 */
	private void exportElasticsearchResponse() {
		String response = lastFullResponse.isEmpty() ? output.getText() : lastFullResponse;
		String previousStatus = status.getText();
		long revision = connectionRevision;
		JFileChooser chooser = new JFileChooser();
		if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		Path target = chooser.getSelectedFile().toPath();
		ApplicationManager.getApplication().executeOnPooledThread(() -> {
			String outcome;
			try {
				Files.writeString(target, response, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
				outcome = message("elasticsearch.export.completed");
			} catch (IOException failure) {
				log.warn("Elasticsearch 导出失败，阶段=写入 JSON 响应，目标文件={}", target, failure);
				outcome = message("elasticsearch.export.failed", failure.getMessage());
			}
			String exportStatus = outcome;
			SwingUtilities.invokeLater(() -> {
				if (revision == connectionRevision && Objects.isNull(elasticsearchTask) && previousStatus.equals(status.getText())
					&& response.equals(output.getText())) {
					status.setText(exportStatus);
				}
			});
		});
	}

	/**
	 * 在后台执行请求，完成后仅更新当前连接及目标对应的界面。
	 *
	 * @param stage     操作阶段
	 * @param operation 后台任务
	 * @param callback  成功时更新界面
	 * @param <T>       任务返回类型
	 */
	private <T> void runElasticsearchOperation(String stage, ElasticsearchOperation<T> operation, Consumer<T> callback) {
		runElasticsearchOperation(stage, operation, callback, false);
	}

	/**
	 * 在后台执行请求；仅只读搜索开放 future 取消，不能把中断写请求解释为服务端回滚。
	 *
	 * @param stage 操作阶段
	 * @param operation 后台 HTTP 操作
	 * @param callback 成功时更新界面
	 * @param cancellable 是否为允许用户取消的普通只读查询
	 * @param <T> 操作返回类型
	 */
	private <T> void runElasticsearchOperation(String stage, ElasticsearchOperation<T> operation,
											Consumer<T> callback, boolean cancellable) {
		if (Objects.nonNull(elasticsearchTask)) {
			return;
		}
		String address = endpoint.getText().strip();
		String user = username.getText();
		String ca = caPath.getText().strip();
		boolean verifyServerCertificate = verifyCertificate;
		char[] secret = apiKey.getPassword();
		long revision = connectionRevision;
		AtomicBoolean cancellationRequested = new AtomicBoolean();
		AtomicReference<WorkbenchElasticsearchConnection> operationConnection = new AtomicReference<>();
		Runnable cancelAction = () -> {
			cancellationRequested.set(true);
			ApplicationManager.getApplication().executeOnPooledThread(() -> {
				WorkbenchElasticsearchConnection connection = operationConnection.get();
				if (Objects.nonNull(connection)) {
					connection.cancelCurrentRequest();
				}
			});
		};
		cancelSearchAction = cancellable ? cancelAction : null;
		cancelSearch.setEnabled(cancellable);
		status.setText(stage + message("in.progress"));
		elasticsearchTask = new SwingWorker<T, Void>() {
			/** {@inheritDoc} 请求结束后释放 HTTP 客户端和密码数组。 */
			@Override
			protected T doInBackground() throws Exception {
				try (var connection = new WorkbenchElasticsearchConnection(address, user, secret,
						ca.isEmpty() ? null : Path.of(ca), verifyServerCertificate, DynamicBundle.getLocale())) {
					operationConnection.set(connection);
					if (cancellationRequested.get()) {
						connection.cancelCurrentRequest();
					}
					return operation.run(connection);
				} finally {
					operationConnection.set(null);
					Arrays.fill(secret, '\0');
				}
			}

			/** {@inheritDoc} 忽略已取消或配置修改前发起的响应。 */
			@Override
			protected void done() {
				elasticsearchTask = null;
				if (cancelSearchAction == cancelAction) {
					cancelSearchAction = null;
					cancelSearch.setEnabled(false);
				}
				if (isCancelled() || revision != connectionRevision) {
					return;
				}
				try {
					T result = get();
					status.setText(cancellationRequested.get()
						? message("search.request.completed.before.cancel") : stage + message("completed"));
					callback.accept(result);
				} catch (InterruptedException failure) {
					Thread.currentThread().interrupt();
				} catch (ExecutionException failure) {
					Throwable cause = failure.getCause();
					if (cancellationRequested.get() && cause instanceof InterruptedException) {
						status.setText(message("search.request.cancelled"));
						return;
					}
					log.warn("Elasticsearch 操作失败，阶段={}，服务地址={}", stage, address, cause);
					status.setText(stage + message("failed") + cause.getMessage());
				}
			}
		};
		elasticsearchTask.execute();
	}

	/**
	 * 请求取消当前 Query DSL 读取，并把实际 HTTP future 取消安排到 pooled thread，避免 EDT 执行网络取消逻辑。
	 */
	private void cancelSelectedElasticsearchSearch() {
		Runnable cancelAction = cancelSearchAction;
		if (Objects.isNull(cancelAction)) {
			return;
		}
		cancelSearch.setEnabled(false);
		status.setText(message("search.request.cancel.requested"));
		cancelAction.run();
	}

	/**
	 * 关闭标签页时请求取消后台任务并清空凭据字段。
	 */
	@Override
	public void dispose() {
		if (Objects.nonNull(cancelSearchAction)) {
			cancelSearchAction.run();
		} else if (Objects.nonNull(elasticsearchTask)) {
			elasticsearchTask.cancel(true);
		}
		apiKey.setText("");
	}

	/**
	 * 接受当前一次性客户端的后台操作。
	 *
	 * @param <T> 响应类型
	 * @author liaojinlong
	 * @since 2026/11/18 15:45
	 */
	@FunctionalInterface
	private interface ElasticsearchOperation<T> {
		/**
		 * 使用当前客户端调用 REST API。
		 *
		 * @param connection 当前有效客户端
		 * @return 界面展示结果
		 * @throws Exception HTTP、认证或 TLS 失败
		 */
		T run(WorkbenchElasticsearchConnection connection) throws Exception;
	}
}
