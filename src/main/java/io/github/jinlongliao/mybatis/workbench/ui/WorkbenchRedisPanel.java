package io.github.jinlongliao.mybatis.workbench.ui;

import static io.github.jinlongliao.mybatis.workbench.ui.WorkbenchBundle.message;

import com.intellij.DynamicBundle;
import com.intellij.openapi.Disposable;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBScrollPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.table.DefaultTableModel;

import io.github.jinlongliao.mybatis.workbench.database.NoSqlConnectionProfile;
import io.github.jinlongliao.mybatis.workbench.database.RedisKeyPage;
import io.github.jinlongliao.mybatis.workbench.database.RedisStreamMessage;
import io.github.jinlongliao.mybatis.workbench.database.WorkbenchRedisConnection;

/**
 * Redis 操作标签页，按节点和游标浏览键，并提供带显式路由键的命令执行。
 *
 * @author liaojinlong
 * @since 2026/11/18 15:35
 */
final class WorkbenchRedisPanel extends JPanel implements Disposable {
	/** 最多保留的成员前页快照数，限制用户深翻时的本地状态占用。 */
	private static final int MAX_REDIS_MEMBER_HISTORY_PAGES = 20;
	/**
	 * 记录连接、扫描或命令执行失败的阶段与原因。
	 */
	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	/**
	 * 逗号分隔的 Redis host:port 种子节点。
	 */
	private final JTextField endpoints = new JTextField("localhost:6379");
	/**
	 * 集群或单机。
	 */
	private final JCheckBox cluster = new JCheckBox(message("redis.cluster.mode"));
	/**
	 * 使用系统信任库验证服务器证书。
	 */
	private final JCheckBox tls = new JCheckBox("TLS");
	/** 当前已选数据源的本机证书校验策略，未选择配置时默认校验。 */
	private boolean verifyCertificate = true;
	/**
	 * 单机逻辑库编号。
	 */
	private final JSpinner database = new JSpinner(new SpinnerNumberModel(0, 0, Integer.MAX_VALUE, 1));
	/**
	 * Redis ACL 用户名。
	 */
	private final JTextField username = new JTextField();
	/**
	 * 当前标签页的连接密码。
	 */
	private final JPasswordField password = new JPasswordField();
	/**
	 * 用户选定的 SCAN 节点。
	 */
	private final JComboBox<String> nodes = new JComboBox<>();
	/** 当前 SCAN 响应分页内可见的键；限制为 100 项，选择后在右侧读取详情。 */
	private final DefaultListModel<String> scannedKeys = new DefaultListModel<>();
	/** 允许用户从扫描结果选择精确键名，避免复制文本或手工拼写命令参数。 */
	private final JList<String> keyList = new JList<>(scannedKeys);
	/**
	 * Redis glob 筛选条件。
	 */
	private final JTextField pattern = new JTextField("*");
	/**
	 * 用户可以重置或继续的游标。
	 */
	private final JTextField cursor = new JTextField("0");
	/**
	 * 集群按键路由的键。
	 */
	private final JTextField routingKey = new JTextField();
	/**
	 * Redis 命令名，参数在独立输入框。
	 */
	private final JTextField command = new JTextField("GET");
	/**
	 * 每行一个参数，保留参数内空格。
	 */
	private final JTextArea arguments = new JTextArea(3, 20);
	/**
	 * 对象树带入的原始键，未选中时为空引用。
	 */
	private String selectedTreeKey;
	/**
	 * 只在选中实际键时可用的详情入口。
	 */
	private final JButton viewKeyDetails = new JButton(message("redis.view.key.details"));
	/** 仅对当前详情中的字符串键开放有限长度文本编辑。 */
	private final JButton editStringValue = new JButton(message("redis.edit.string"));
	/** 为当前详情中的键设置秒级过期时间。 */
	private final JButton setKeyTtl = new JButton(message("redis.set.ttl"));
	/** 清除当前详情中已有的键过期时间。 */
	private final JButton clearKeyTtl = new JButton(message("redis.clear.ttl"));
	/** 二次确认后删除当前详情中的单个键。 */
	private final JButton deleteKey = new JButton(message("redis.delete.key"));
	/** 当前选中键详情快照，用于按原类型确认后执行变更。 */
	private WorkbenchRedisConnection.RedisKeyDetails selectedKeyDetails;
	/** 当前所选键的类型专用成员游标；切换键或写入成功后回到首屏。 */
	private String memberPageCursor = "";
	/** 续页游标的 Redis 类型；外部改型后强制从第一页重新读取。 */
	private String memberPageType = "";
	/** 有界保存当前成员页之前的详情快照，用于上一页导航。 */
	private final List<WorkbenchRedisConnection.RedisKeyDetails> memberPageHistory = new java.util.ArrayList<>();
	/**
	 * 当前扫描或命令的文本结果。
	 */
	private final JTextArea output = new JTextArea();
	/** 命令控制台的独立结果区，避免执行命令覆盖浏览器中的键详情。 */
	private final JTextArea commandOutput = new JTextArea();
	/** 当前复合值预览行；列只读，修改必须走带预览快照条件的类型专用操作。 */
	private final DefaultTableModel redisMemberModel = new DefaultTableModel();
	/** Hash/Set/ZSet/List/Stream 的有界成员预览表。 */
	private final JTable redisMemberTable = new JTable(redisMemberModel);
	/** 对当前复合类型添加成员或条目。 */
	private final JButton addRedisMember = new JButton(message("redis.member.add"));
	/** 编辑具有明确成员定位语义的 Hash/ZSet/List 项。 */
	private final JButton editRedisMember = new JButton(message("redis.member.edit"));
	/** 删除选中的复合值成员或 Stream 消息。 */
	private final JButton deleteRedisMember = new JButton(message("redis.member.delete"));
	/** 读取当前类型的下一有界成员页。 */
	private final JButton nextRedisMemberPage = new JButton(message("next.page"));
	/** 返回当前键上一成员页。 */
	private final JButton previousRedisMemberPage = new JButton(message("previous.page"));
	/** 仅对 SCAN 和键详情读取开放；按下后保留任务槽直到当前 socket 调用返回。 */
	private final JButton cancelRedisReadWait = new JButton(message("redis.cancel.read.wait"));
	/**
	 * 当前操作结果和页状态。
	 */
	private final JLabel status = new JLabel(message("connect.to.redis.then.select.a.node"));
	/**
	 * 当前后台任务，在 EDT 管理。
	 */
	private SwingWorker<?, ?> redisTask;
	/** 当前操作是否为允许取消 UI 等待的只读请求；写命令始终不可由此按钮中断。 */
	private boolean redisReadWaitCancellable;
	/** EDT 请求丢弃当前只读响应；工作线程完成后才释放任务槽。 */
	private volatile boolean redisReadWaitCancellationRequested;
	/** EDT 上递增，节点或过滤条件变更时使旧扫描结果失效。 */
	private long scanRevision;
	/** Redis COUNT 可能超量返回；未显示部分保留到用户请求后续页面。 */
	private RedisKeyPage bufferedScanPage;
	/** 当前超量响应中已显示键的下标。 */
	private int bufferedScanOffset;
	/** 标记代码写回游标期间的文档事件，避免把程序状态写回当作新的用户查询。 */
	private boolean suppressCursorReset;
	/**
	 * 连接配置修改次数，用于丢弃旧连接的异步结果。
	 */
	private long connectionRevision;
	/** 正在执行操作时延后读取的新选中键，仅保留最近一次请求。 */
	private boolean pendingRedisKeyDetails;
	/** pattern 输入框回车触发的首页扫描；当前任务结束后才启动，避免释放单任务槽。 */
	private boolean pendingRedisPatternScan;

	/**
	 * 布置 Redis 扫描和命令区，连接参数从数据源载入。
	 */
	WorkbenchRedisPanel() {
		super(new BorderLayout(4, 4));
		JPanel form = new JPanel(new GridLayout(0, 2, 4, 4));
		addRedisField(form, message("browse.node"), nodes);
		addRedisField(form, message("key.pattern"), pattern);
		pattern.setToolTipText(message("redis.pattern.help"));
		addRedisField(form, message("scan.cursor"), cursor);
		JButton createString = new JButton(message("redis.create.string"));
		createString.addActionListener(event -> createRedisStringKey());
		JButton createAggregate = new JButton(message("redis.create.aggregate"));
		createAggregate.addActionListener(event -> createRedisAggregateKey());
		nodes.addActionListener(event -> resetRedisScanCursor());
		DocumentAdapter connectionListener = new DocumentAdapter() {
			/** {@inheritDoc} 当前节点和游标属于旧连接，修改连接后清空。 */
			@Override
			protected void textChanged(DocumentEvent event) {
				resetRedisBrowse();
			}
		};
		for (JTextField input : List.of(endpoints, username, password)) {
			input.getDocument().addDocumentListener(connectionListener);
		}
		cluster.addActionListener(event -> {
			database.setEnabled(!cluster.isSelected());
			resetRedisBrowse();
		});
		tls.addActionListener(event -> resetRedisBrowse());
		database.addChangeListener(event -> resetRedisBrowse());
		pattern.getDocument().addDocumentListener(new DocumentAdapter() {
			/** {@inheritDoc} 修改过滤条件会使在途范围失效，并要求用户重新提交后才扫描。 */
			@Override
			protected void textChanged(DocumentEvent event) {
				pendingRedisPatternScan = false;
				resetRedisScanCursor();
			}
		});
		pattern.addActionListener(event -> {
			resetRedisScanCursor();
			if (Objects.nonNull(redisTask)) {
				pendingRedisPatternScan = true;
				status.setText(message("scan") + message("in.progress"));
				return;
			}
			scanSelectedRedisNode();
		});
		cursor.getDocument().addDocumentListener(new DocumentAdapter() {
			/** {@inheritDoc} 手工改游标时放弃旧页和已选键；代码更新游标通过 suppressCursorReset 避免清理新结果。 */
			@Override
			protected void textChanged(DocumentEvent event) {
				if (!suppressCursorReset) {
					scanRevision++;
					clearRedisScanResults();
				}
			}
		});
		keyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		keyList.addListSelectionListener(event -> {
			if (!event.getValueIsAdjusting()) {
				selectScannedRedisKey(keyList.getSelectedValue());
			}
		});
		redisMemberTable.setDefaultEditor(Object.class, null);
		redisMemberTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		redisMemberTable.getSelectionModel().addListSelectionListener(event -> {
			if (!event.getValueIsAdjusting()) {
				updateRedisMutationButtons();
			}
		});
		redisMemberTable.addMouseListener(new MouseAdapter() {
			/** 双击可编辑成员时复用带快照校验的既有编辑流程。 */
			@Override
			public void mouseClicked(MouseEvent event) {
				if (event.getButton() != MouseEvent.BUTTON1 || event.getClickCount() != 2) {
					return;
				}
				int row = redisMemberTable.rowAtPoint(event.getPoint());
				if (row >= 0) {
					redisMemberTable.setRowSelectionInterval(row, row);
					if (editRedisMember.isEnabled()) {
						editSelectedRedisMember();
					}
				}
			}
		});
		redisMemberTable.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "editRedisMember");
		redisMemberTable.getActionMap().put("editRedisMember", new AbstractAction() {
			/** Enter 在选中可编辑成员时打开现有条件写入表单。 */
			@Override
			public void actionPerformed(ActionEvent event) {
				if (editRedisMember.isEnabled()) {
					editSelectedRedisMember();
				}
			}
		});

		JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
		actions.add(createString);
		actions.add(createAggregate);
		JButton connect = new JButton(message("test.connection.and.load.nodes"));
		connect.addActionListener(event -> runRedisOperation(message("connect"), connection -> {
			connection.pingRedis();
			return connection.listPrimaryNodes();
		}, primaries -> {
			Object selectedNode = nodes.getSelectedItem();
			var model = new DefaultComboBoxModel<>(primaries.toArray(String[]::new));
			if (Objects.nonNull(selectedNode)) {
				// 刷新时保留原主节点。节点已消失时等待用户重新选择，避免扫描其他节点。
				model.setSelectedItem(primaries.contains(selectedNode) ? selectedNode : null);
			}
			nodes.setModel(model);
			resetRedisScanCursor();
			if (!Objects.equals(selectedNode, model.getSelectedItem())) {
				output.setText("");
			}
		}));
		actions.add(connect);
		JButton scan = new JButton(message("scan.one.page"));
		scan.addActionListener(event -> scanSelectedRedisNode());
		actions.add(scan);
		cancelRedisReadWait.setToolTipText(message("redis.cancel.read.wait.help"));
		cancelRedisReadWait.setEnabled(false);
		cancelRedisReadWait.addActionListener(event -> cancelRedisReadResultWait());
		actions.add(cancelRedisReadWait);
		viewKeyDetails.setEnabled(false);
		viewKeyDetails.addActionListener(event -> showSelectedRedisKeyDetails());
		actions.add(viewKeyDetails);
		editStringValue.setEnabled(false);
		editStringValue.addActionListener(event -> editSelectedRedisString());
		actions.add(editStringValue);
		setKeyTtl.setEnabled(false);
		setKeyTtl.addActionListener(event -> setSelectedRedisKeyTtl());
		actions.add(setKeyTtl);
		clearKeyTtl.setEnabled(false);
		clearKeyTtl.addActionListener(event -> clearSelectedRedisKeyTtl());
		actions.add(clearKeyTtl);
		deleteKey.setEnabled(false);
		deleteKey.addActionListener(event -> deleteSelectedRedisKey());
		actions.add(deleteKey);
		JPanel memberActions = new JPanel(new FlowLayout(FlowLayout.LEFT));
		addRedisMember.setEnabled(false);
		addRedisMember.addActionListener(event -> addSelectedRedisMember());
		memberActions.add(addRedisMember);
		editRedisMember.setEnabled(false);
		editRedisMember.addActionListener(event -> editSelectedRedisMember());
		memberActions.add(editRedisMember);
		deleteRedisMember.setEnabled(false);
		deleteRedisMember.addActionListener(event -> deleteSelectedRedisMember());
		memberActions.add(deleteRedisMember);
		previousRedisMemberPage.setEnabled(false);
		previousRedisMemberPage.addActionListener(event -> loadPreviousRedisMemberPage());
		memberActions.add(previousRedisMemberPage);
		nextRedisMemberPage.setEnabled(false);
		nextRedisMemberPage.addActionListener(event -> loadNextRedisMemberPage());
		memberActions.add(nextRedisMemberPage);
		output.setEditable(false);
		commandOutput.setEditable(false);
		JPanel browserHeader = new JPanel(new BorderLayout());
		browserHeader.add(new JBScrollPane(actions, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED), BorderLayout.NORTH);
		browserHeader.add(form, BorderLayout.CENTER);
		JPanel keyPanel = new JPanel(new BorderLayout(4, 4));
		keyPanel.add(new JLabel(message("redis.scan.results")), BorderLayout.NORTH);
		keyPanel.add(new JBScrollPane(keyList), BorderLayout.CENTER);
		JPanel details = new JPanel(new BorderLayout(4, 4));
		JSplitPane valueDetails = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
			new JBScrollPane(output), new JBScrollPane(redisMemberTable));
		valueDetails.setResizeWeight(0.35);
		details.add(valueDetails, BorderLayout.CENTER);
		details.add(new JBScrollPane(memberActions, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED), BorderLayout.SOUTH);
		JSplitPane browser = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, keyPanel, details);
		browser.setResizeWeight(0.32);
		JPanel browseTab = new JPanel(new BorderLayout(4, 4));
		browseTab.add(browserHeader, BorderLayout.NORTH);
		browseTab.add(browser, BorderLayout.CENTER);
		JButton execute = new JButton(message("run.command"));
		execute.addActionListener(event -> executeEditorRedisCommand());
		JPanel consoleForm = new JPanel(new GridLayout(0, 2, 4, 4));
		addRedisField(consoleForm, message("cluster.routing.key"), routingKey);
		addRedisField(consoleForm, message("command"), command);
		addRedisField(consoleForm, message("command.arguments.one.per.line"), new JBScrollPane(arguments));
		JPanel consoleHeader = new JPanel(new BorderLayout(4, 4));
		consoleHeader.add(consoleForm, BorderLayout.CENTER);
		consoleHeader.add(execute, BorderLayout.SOUTH);
		JPanel consoleTab = new JPanel(new BorderLayout(4, 4));
		consoleTab.add(consoleHeader, BorderLayout.NORTH);
		consoleTab.add(new JBScrollPane(commandOutput), BorderLayout.CENTER);
		JTabbedPane workspace = new JTabbedPane();
		workspace.addTab(message("redis.browser.tab"), browseTab);
		workspace.addTab(message("redis.console.tab"), consoleTab);
		add(workspace, BorderLayout.CENTER);
		add(status, BorderLayout.SOUTH);
	}

	/**
	 * 开始新的扫描范围，避免旧请求将其游标写回已修改的表单。
	 */
	private void resetRedisScanCursor() {
		scanRevision++;
		setRedisCursor("0");
		clearRedisScanResults();
	}

	/** 清空与旧 MATCH/cursor 范围关联的键列表和详情快照。 */
	private void clearRedisScanResults() {
		bufferedScanPage = null;
		bufferedScanOffset = 0;
		resetRedisMemberPage();
		scannedKeys.clear();
		keyList.clearSelection();
		selectedTreeKey = null;
		selectedKeyDetails = null;
		clearRedisMemberTable();
		viewKeyDetails.setEnabled(false);
		updateRedisMutationButtons();
		output.setText("");
	}

	/** 更新服务端游标而不把程序写回误判成用户输入。 */
	private void setRedisCursor(String value) {
		suppressCursorReset = true;
		try {
			cursor.setText(value);
		} finally {
			suppressCursorReset = false;
		}
	}

	/** 清除旧键的成员行，避免新详情读取期间对上一键的预览产生误操作。 */
	private void clearRedisMemberTable() {
		redisMemberModel.setDataVector(new Object[0][0], new Object[0]);
	}

	/**
	 * 使已有节点和游标失效，避免换连接后沿用之前的游标。
	 */
	private void resetRedisBrowse() {
		connectionRevision++;
		pendingRedisKeyDetails = false;
		pendingRedisPatternScan = false;
		resetRedisMemberPage();
		bufferedScanPage = null;
		bufferedScanOffset = 0;
		selectedTreeKey = null;
		selectedKeyDetails = null;
		clearRedisMemberTable();
		viewKeyDetails.setEnabled(false);
		updateRedisMutationButtons();
		nodes.removeAllItems();
		setRedisCursor("0");
		output.setText("");
		commandOutput.setText("");
		scannedKeys.clear();
		keyList.clearSelection();
		status.setText(message("connection.changed.reload.redis.nodes"));
	}

	/**
	 * 仅在选中键仍存在时开放对应管理操作；字符串编辑会在读取阶段再次核对类型和值。
	 */
	private void updateRedisMutationButtons() {
		var details = selectedKeyDetails;
		boolean editableString = Objects.nonNull(selectedKeyDetails)
			&& "string".equals(selectedKeyDetails.type())
			&& Objects.nonNull(selectedKeyDetails.editableStringSnapshot());
		editStringValue.setEnabled(editableString);
		setKeyTtl.setEnabled(editableString);
		clearKeyTtl.setEnabled(editableString && details.ttlSeconds() >= 0);
		String type = Objects.isNull(details) ? "" : details.type();
		boolean aggregate = List.of("hash", "set", "zset", "list", "stream").contains(type);
		deleteKey.setEnabled(editableString || aggregate);
		int selectedRow = redisMemberTable.getSelectedRow();
		boolean selectedMember = aggregate && selectedRow >= 0;
		addRedisMember.setEnabled(aggregate);
		editRedisMember.setEnabled(selectedMember && List.of("hash", "set", "zset", "list").contains(type));
		long selectedListIndex = selectedMember && "list".equals(type)
			? ((Number) redisMemberModel.getValueAt(selectedRow, 0)).longValue() : -1;
		boolean deletableListPosition = !"list".equals(type) || selectedMember
			&& (selectedListIndex == 0 || selectedListIndex == details.listLength() - 1);
		boolean completeStreamSnapshot = !"stream".equals(type) || selectedMember
			&& details.streamPreview().get(selectedRow).completeSnapshot();
		deleteRedisMember.setEnabled(selectedMember && deletableListPosition && completeStreamSnapshot);
		previousRedisMemberPage.setEnabled(Objects.nonNull(details) && !memberPageHistory.isEmpty());
		boolean hasBufferedMember = Objects.nonNull(details)
			&& (details.hashBufferedPreview().size() > details.memberBufferOffset()
				|| details.setBufferedPreview().size() > details.memberBufferOffset());
		nextRedisMemberPage.setEnabled(Objects.nonNull(details)
			&& (hasBufferedMember || !details.nextMemberPageCursor().isEmpty()));
	}

	/**
	 * 从统一数据源树打开已保存连接。只有选中实际键时自动读取详情。
	 *
	 * @param profile 选择的连接设置
	 * @param secret  当前设备保存的认证凭据
	 * @param node    对象树上选中的主节点，空字符串表示未选中
	 * @param key     对象树上选中的原始键，空引用表示未选中
	 * @param logicalDatabase 对象树中选中的单机逻辑库，集群固定为 0
	 */
	void openSourceProfile(NoSqlConnectionProfile profile, String secret, String node, String key, int logicalDatabase) {
		resetRedisBrowse();
		verifyCertificate = WorkbenchNoSqlSourceEditor.readNoSqlCertificateVerification(profile);
		endpoints.setText(profile.endpoint());
		pattern.setText(WorkbenchNoSqlNavigator.readRedisTreeFilter(profile));
		cluster.setSelected(profile.cluster());
		tls.setSelected(profile.tls());
		database.setValue(logicalDatabase);
		database.setEnabled(!profile.cluster());
		username.setText(profile.username());
		password.setText(secret);
		nodes.removeAllItems();
		if (!node.isEmpty()) {
			nodes.addItem(node);
		}
		selectedTreeKey = key;
		selectedKeyDetails = null;
		viewKeyDetails.setEnabled(Objects.nonNull(key));
		updateRedisMutationButtons();
		// 只有能无歧义表示为单行参数的键才预填通用命令。
		boolean singleLineKey = Objects.nonNull(key) && !key.isEmpty() && key.lines().count() == 1
			&& !key.endsWith("\n") && !key.endsWith("\r");
		routingKey.setText(singleLineKey ? key : "");
		command.setText(singleLineKey ? "TYPE" : "GET");
		arguments.setText(singleLineKey ? key : "");
		showSelectedRedisKeyDetails();
	}

	/**
	 * 向连接表单添加一行并建立标签关联。
	 *
	 * @param form  Redis 表单
	 * @param label 字段显示名
	 * @param field 对应输入组件
	 */
	private void addRedisField(JPanel form, String label, JComponent field) {
		JLabel name = new JLabel(label);
		name.setLabelFor(field);
		form.add(name);
		form.add(field);
	}

	/**
	 * 每次最多显示 100 个键。Redis COUNT 只是提示，超出部分暂存在页面状态中，下一次点击先读取缓存，避免跳过键。
	 * 单机直接扫描当前逻辑库，集群必须先选定主节点。
	 */
	private void scanSelectedRedisNode() {
		String node = (String) nodes.getSelectedItem();
		if (Objects.isNull(node) && cluster.isSelected()) {
			status.setText(message("load.redis.nodes.first"));
			return;
		}
		String next = cursor.getText().strip();
		String match = pattern.getText();
		if (Objects.nonNull(bufferedScanPage) && bufferedScanPage.cursor().equals(next)
			&& bufferedScanOffset < bufferedScanPage.keys().size()) {
			showNextRedisScanPage();
			return;
		}
		List<String> previousVisibleKeys = List.copyOf(Collections.list(scannedKeys.elements()));
		RedisKeyPage previousBufferedPage = bufferedScanPage;
		int previousBufferedOffset = bufferedScanOffset;
		String previousCursor = cursor.getText();
		bufferedScanPage = null;
		bufferedScanOffset = 0;
		long revision = scanRevision;
		runCancellableRedisRead(message("scan"), connection -> connection.scanRedisKeys(
			Objects.nonNull(node) ? node : connection.listPrimaryNodes().getFirst(), next, match, 100),
			() -> revision == scanRevision, page -> {
			setRedisCursor(page.cursor());
			bufferedScanPage = page;
			bufferedScanOffset = 0;
			showNextRedisScanPage();
		}, () -> restoreRedisScanAfterCancelledRead(previousVisibleKeys, previousBufferedPage,
			previousBufferedOffset, previousCursor));
	}

	/** SCAN 结果取消时恢复之前显示的页、未显示批次和输入游标。 */
	private void restoreRedisScanAfterCancelledRead(List<String> visibleKeys, RedisKeyPage bufferedPage,
											int bufferedOffset, String previousCursor) {
		scannedKeys.clear();
		visibleKeys.forEach(scannedKeys::addElement);
		bufferedScanPage = bufferedPage;
		bufferedScanOffset = bufferedOffset;
		suppressCursorReset = true;
		cursor.setText(previousCursor);
		suppressCursorReset = false;
	}

	/** 显示当前 Redis SCAN 响应中下一批至多 100 个键；全部缓冲键显示完后才标记扫描完成。 */
	private void showNextRedisScanPage() {
		RedisKeyPage page = bufferedScanPage;
		if (Objects.isNull(page)) {
			return;
		}
		int start = bufferedScanOffset;
		int end = page.nextPageEnd(start, 100);
		scannedKeys.clear();
		for (String key : page.keys().subList(start, end)) {
			scannedKeys.addElement(key);
		}
		keyList.clearSelection();
		bufferedScanOffset = end;
		boolean bufferedKeysRemain = bufferedScanOffset < page.keys().size();
		String serverHasMore = "0".equals(page.cursor()) && !bufferedKeysRemain ? message("scan.complete") : "";
		status.setText(message("this.page") + (end - start) + message("keys") + serverHasMore);
		if (!bufferedKeysRemain) {
			bufferedScanPage = null;
			bufferedScanOffset = 0;
		}
	}

	/**
	 * 使用对象树保存的原始键读取详情，连接变化后该操作不可用。
	 */
	private void showSelectedRedisKeyDetails() {
		String key = selectedTreeKey;
		if (Objects.isNull(key)) {
			return;
		}
		WorkbenchRedisConnection.RedisKeyDetails previousDetails = selectedKeyDetails;
		String previousOutput = output.getText();
		output.setText(message("redis.key.details.loading"));
		selectedKeyDetails = null;
		clearRedisMemberTable();
		updateRedisMutationButtons();
		String requestedCursor = memberPageCursor;
		if (Objects.nonNull(redisTask)) {
			pendingRedisKeyDetails = true;
			return;
		}
		runCancellableRedisRead(message("redis.view.key.details"), connection ->
			connection.readRedisKeyDetails(key, requestedCursor),
			() -> Objects.equals(key, selectedTreeKey) && Objects.equals(requestedCursor, memberPageCursor), details -> {
			if (!memberPageType.isEmpty() && !memberPageType.equals(details.type())) {
				resetRedisMemberPage();
				showSelectedRedisKeyDetails();
				return;
			}
			memberPageType = details.type();
			selectedKeyDetails = details;
			updateRedisMutationButtons();
			populateRedisMemberTable(details);
			boolean listSnapshotChanged = "list".equals(details.type())
				&& requestedCursor.startsWith("list:") && details.memberPageOffset() == 0;
			if (listSnapshotChanged) {
				memberPageHistory.clear();
				memberPageCursor = "";
				status.setText(message("redis.list.page.reset"));
			}
			String text = message("redis.key.type") + " " + details.type() + "\n"
				+ message("redis.ttl") + " " + details.ttlSeconds();
			if ("string".equals(details.type())) {
				text += "\n" + message("redis.string.preview") + "\n" + details.stringPreview();
			} else if (List.of("hash", "set", "zset", "list", "stream").contains(details.type())) {
				text += "\n" + message("redis.member.preview.help");
			}
			if ("string".equals(details.type()) && Objects.isNull(details.editableStringSnapshot())) {
				text += "\n" + message("redis.string.edit.too.large");
			}
			output.setText(text);
		}, () -> restoreRedisKeyDetailsAfterCancelledRead(key, requestedCursor, previousDetails, previousOutput));
	}

	/** 键详情读取取消时恢复同键上一页；初次读取被取消时用明确文案替换加载状态。 */
	private void restoreRedisKeyDetailsAfterCancelledRead(String key, String requestedCursor,
													 WorkbenchRedisConnection.RedisKeyDetails previousDetails,
													 String previousOutput) {
		if (pendingRedisKeyDetails) {
			return;
		}
		if (Objects.nonNull(previousDetails) && Objects.equals(key, selectedTreeKey)) {
			if (!memberPageHistory.isEmpty() && memberPageHistory.getLast() == previousDetails
				&& Objects.equals(previousDetails.nextMemberPageCursor(), requestedCursor)) {
				memberPageHistory.removeLast();
			}
			selectedKeyDetails = previousDetails;
			memberPageCursor = previousDetails.memberPageCursor();
			output.setText(previousOutput);
			populateRedisMemberTable(previousDetails);
			updateRedisMutationButtons();
			return;
		}
		selectedKeyDetails = null;
		clearRedisMemberTable();
		updateRedisMutationButtons();
		output.setText(message("redis.read.cancelled.no.details"));
	}

	/** 按 Redis 原生类型把有限预览映射为只读成员表，不把复杂结构压成不可选中的文本。 */
	private void populateRedisMemberTable(WorkbenchRedisConnection.RedisKeyDetails details) {
		List<Object[]> rows = new java.util.ArrayList<>();
		String[] columns;
		switch (details.type()) {
			case "hash" -> {
				columns = new String[] {message("redis.hash.field"), message("redis.string.value")};
				for (var field : details.hashPreview()) {
					rows.add(new Object[] {field.getKey(), field.getValue()});
				}
			}
			case "set" -> {
				columns = new String[] {message("redis.set.member")};
				for (String member : details.setPreview()) {
					rows.add(new Object[] {member});
				}
			}
			case "zset" -> {
				columns = new String[] {message("redis.set.member"), message("redis.zset.score")};
				for (var member : details.sortedSetPreview()) {
					rows.add(new Object[] {member.member(), Double.toString(member.score())});
				}
			}
			case "list" -> {
				columns = new String[] {message("redis.list.index"), message("redis.list.value")};
				for (int index = 0; index < details.listPreview().size(); index++) {
					rows.add(new Object[] {details.memberPageOffset() + index, details.listPreview().get(index)});
				}
			}
			case "stream" -> {
				columns = new String[] {message("redis.stream.id"), message("redis.stream.fields")};
				for (var entry : details.streamPreview()) {
					String fields = entry.fields().stream().map(field -> field.getKey() + "=" + field.getValue())
						.collect(Collectors.joining(", "));
					if (!entry.completeSnapshot()) {
						fields += " " + message("redis.stream.entry.preview.truncated");
					}
					rows.add(new Object[] {entry.id(), fields});
				}
			}
			default -> columns = new String[0];
		}
		redisMemberModel.setDataVector(rows.toArray(Object[][]::new), columns);
	}

	/** 按当前键类型追加一条结构数据；每种类型使用对应 Redis 命令，不把用户值拼成命令文本。 */
	private void addSelectedRedisMember() {
		String key = selectedTreeKey;
		var details = selectedKeyDetails;
		if (Objects.isNull(key) || Objects.isNull(details)) {
			return;
		}
		List<String> values;
		switch (details.type()) {
			case "hash" -> {
				values = promptRedisFields(message("redis.member.add"),
					List.of(message("redis.hash.field"), message("redis.string.value")), List.of("", ""));
				if (Objects.isNull(values)) {
					return;
				}
				runRedisOperation(message("redis.member.add"), connection ->
					connection.addRedisHashField(key, values.get(0), values.get(1)), this::showRedisMemberMutationResult);
			}
			case "set" -> {
				values = promptRedisFields(message("redis.member.add"), List.of(message("redis.set.member")), List.of(""));
				if (Objects.isNull(values)) {
					return;
				}
				runRedisOperation(message("redis.member.add"), connection ->
					connection.addRedisSetMember(key, values.getFirst()), this::showRedisMemberMutationResult);
			}
			case "zset" -> {
				values = promptRedisFields(message("redis.member.add"),
					List.of(message("redis.set.member"), message("redis.zset.score")), List.of("", "0"));
				if (Objects.isNull(values)) {
					return;
				}
				Double score = parseRedisScore(values.get(1));
				if (Objects.isNull(score)) {
					return;
				}
				runRedisOperation(message("redis.member.add"), connection ->
					connection.addRedisSortedSetMember(key, values.get(0), score), this::showRedisMemberMutationResult);
			}
			case "list" -> {
				values = promptRedisFields(message("redis.member.add"), List.of(message("redis.list.value")), List.of(""));
				if (Objects.isNull(values)) {
					return;
				}
				Object[] directions = {message("redis.list.push.left"), message("redis.list.push.right")};
				int direction = JOptionPane.showOptionDialog(this, message("redis.list.push.direction"),
					message("redis.member.add"), JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null,
					directions, directions[1]);
				if (direction < 0) {
					return;
				}
				runRedisOperation(message("redis.member.add"), connection ->
					connection.pushRedisListValue(key, values.getFirst(), direction == 0) > 0,
					this::showRedisMemberMutationResult);
			}
			case "stream" -> {
				values = promptRedisFields(message("redis.member.add"),
					List.of(message("redis.stream.field"), message("redis.stream.value")), List.of("", ""));
				if (Objects.isNull(values)) {
					return;
				}
				runRedisOperation(message("redis.member.add"), connection -> {
					return Objects.nonNull(connection.appendRedisStreamEntry(key, values.get(0), values.get(1)));
				}, this::showRedisMemberMutationResult);
			}
			default -> {
				return;
			}
		}
	}

	/** 修改 Hash 字段值、Set 成员名、ZSet 分数或 List 当前绝对位置。 */
	private void editSelectedRedisMember() {
		String key = selectedTreeKey;
		var details = selectedKeyDetails;
		int row = redisMemberTable.getSelectedRow();
		if (Objects.isNull(key) || Objects.isNull(details) || row < 0) {
			return;
		}
		List<String> values;
		switch (details.type()) {
			case "hash" -> {
				String field = Objects.toString(redisMemberModel.getValueAt(row, 0), "");
				String original = Objects.toString(redisMemberModel.getValueAt(row, 1), "");
				values = promptRedisFields(message("redis.member.edit"),
					List.of(message("redis.string.value")), List.of(original));
				if (Objects.isNull(values)) {
					return;
				}
				runRedisOperation(message("redis.member.edit"), connection ->
					connection.replaceRedisHashField(key, field, original, values.getFirst()), this::showRedisMemberMutationResult);
			}
			case "set" -> {
				String original = Objects.toString(redisMemberModel.getValueAt(row, 0), "");
				values = promptRedisFields(message("redis.member.edit"),
					List.of(message("redis.set.member")), List.of(original));
				if (Objects.isNull(values)) {
					return;
				}
				runRedisOperation(message("redis.member.edit"), connection ->
					connection.replaceRedisSetMember(key, original, values.getFirst()), this::showRedisMemberMutationResult);
			}
			case "zset" -> {
				String member = Objects.toString(redisMemberModel.getValueAt(row, 0), "");
				double original = Double.parseDouble(Objects.toString(redisMemberModel.getValueAt(row, 1), ""));
				values = promptRedisFields(message("redis.member.edit"),
					List.of(message("redis.zset.score")), List.of(Double.toString(original)));
				if (Objects.isNull(values)) {
					return;
				}
				Double replacement = parseRedisScore(values.getFirst());
				if (Objects.isNull(replacement)) {
					return;
				}
				runRedisOperation(message("redis.member.edit"), connection ->
					connection.replaceRedisSortedSetScore(key, member, original, replacement), this::showRedisMemberMutationResult);
			}
			case "list" -> {
				long index = ((Number) redisMemberModel.getValueAt(row, 0)).longValue();
				String original = Objects.toString(redisMemberModel.getValueAt(row, 1), "");
				values = promptRedisFields(message("redis.member.edit"),
					List.of(message("redis.list.value")), List.of(original));
				if (Objects.isNull(values)) {
					return;
				}
				runRedisOperation(message("redis.member.edit"), connection -> connection.replaceRedisListValue(key,
					index, details.listLength(), original, values.getFirst()), this::showRedisMemberMutationResult);
			}
			default -> {
				return;
			}
		}
	}

	/** 确认后按类型快照删除当前成员；List 只开放首/末项以避免按重复值误删。 */
	private void deleteSelectedRedisMember() {
		String key = selectedTreeKey;
		var details = selectedKeyDetails;
		int row = redisMemberTable.getSelectedRow();
		if (Objects.isNull(key) || Objects.isNull(details) || row < 0) {
			return;
		}
		String description = Objects.toString(redisMemberModel.getValueAt(row, 0), "");
		int choice = JOptionPane.showConfirmDialog(this, message("redis.member.delete.confirm", description),
			message("redis.member.delete"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		if (choice != JOptionPane.OK_OPTION) {
			return;
		}
		switch (details.type()) {
			case "hash" -> {
				String field = Objects.toString(redisMemberModel.getValueAt(row, 0), "");
				String value = Objects.toString(redisMemberModel.getValueAt(row, 1), "");
				runRedisOperation(message("redis.member.delete"), connection ->
					connection.deleteRedisHashField(key, field, value) ? 1L : 0L, this::showRedisMemberDeleteResult);
			}
			case "set" -> {
				String member = Objects.toString(redisMemberModel.getValueAt(row, 0), "");
				runRedisOperation(message("redis.member.delete"), connection ->
					connection.deleteRedisSetMember(key, member) ? 1L : 0L, this::showRedisMemberDeleteResult);
			}
			case "zset" -> {
				String member = Objects.toString(redisMemberModel.getValueAt(row, 0), "");
				double score = Double.parseDouble(Objects.toString(redisMemberModel.getValueAt(row, 1), ""));
				runRedisOperation(message("redis.member.delete"), connection ->
					connection.deleteRedisSortedSetMember(key, member, score) ? 1L : 0L,
					this::showRedisMemberDeleteResult);
			}
			case "list" -> {
				long index = ((Number) redisMemberModel.getValueAt(row, 0)).longValue();
				String value = Objects.toString(redisMemberModel.getValueAt(row, 1), "");
				runRedisOperation(message("redis.member.delete"), connection -> connection.deleteRedisListValue(key,
					index, details.listLength(), value), this::showRedisMemberDeleteResult);
			}
			case "stream" -> {
				RedisStreamMessage streamMessage = details.streamPreview().get(row);
				runRedisOperation(message("redis.member.delete"), connection ->
					connection.deleteRedisStreamMessage(key, streamMessage) ? 1L : 0L,
					this::showRedisMemberDeleteResult);
			}
			default -> {
				return;
			}
		}
	}

	/** 为任意 Redis 结构字段提供多行输入，取消时不启动写请求。 */
	private List<String> promptRedisFields(String title, List<String> labels, List<String> initialValues) {
		JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
		List<JTextArea> inputs = new java.util.ArrayList<>(labels.size());
		for (int index = 0; index < labels.size(); index++) {
			JTextArea input = new JTextArea(initialValues.get(index), 4, 32);
			inputs.add(input);
			form.add(new JLabel(labels.get(index)));
			form.add(new JBScrollPane(input));
		}
		int choice = JOptionPane.showConfirmDialog(this, form, title,
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (choice != JOptionPane.OK_OPTION) {
			return null;
		}
		return inputs.stream().map(JTextArea::getText).toList();
	}

	/** 解析 ZSet 分数并将非数值及非有限数值留在表单内提示。 */
	private Double parseRedisScore(String value) {
		try {
			double score = Double.parseDouble(value.strip());
			if (Double.isFinite(score)) {
				return score;
			}
		} catch (NumberFormatException ignored) {
			// 用统一字段提示处理格式错误，不将无效文本发送到 Redis。
		}
		status.setText(message("redis.zset.score.invalid"));
		return null;
	}

	/** 成员变更成功后刷新当前键详情；快照不匹配时提示用户重读。 */
	private void showRedisMemberMutationResult(boolean changed) {
		status.setText(message(changed ? "redis.member.saved" : "redis.member.changed"));
		resetRedisMemberPage();
		showSelectedRedisKeyDetails();
	}

	/** 处理成员删除结果，并将 List 中间位置明确提示为不支持的安全边界。 */
	private void showRedisMemberDeleteResult(long outcome) {
		if (outcome == -2) {
			status.setText(message("redis.list.delete.edge.only"));
		} else if (outcome == 0) {
			status.setText(message("redis.member.changed"));
		} else {
			status.setText(message("redis.member.deleted"));
			resetRedisMemberPage();
			showSelectedRedisKeyDetails();
		}
	}

	/** 将列表中的单个键设为当前对象，并在后台读取详情；不把键名解释为 Redis 命令文本。 */
	private void selectScannedRedisKey(String key) {
		if (!Objects.equals(key, selectedTreeKey)) {
			resetRedisMemberPage();
		}
		selectedTreeKey = key;
		selectedKeyDetails = null;
		clearRedisMemberTable();
		viewKeyDetails.setEnabled(Objects.nonNull(key));
		updateRedisMutationButtons();
		if (Objects.nonNull(key)) {
			showSelectedRedisKeyDetails();
		} else {
			output.setText("");
		}
	}

	/** 读取当前类型的下一页；续页 token 由连接层生成，不从用户文本解释为 Redis 命令。 */
	private void loadNextRedisMemberPage() {
		var details = selectedKeyDetails;
		if (Objects.isNull(details)) {
			return;
		}
		boolean hasBufferedMember = details.hashBufferedPreview().size() > details.memberBufferOffset()
			|| details.setBufferedPreview().size() > details.memberBufferOffset();
		if (!hasBufferedMember && details.nextMemberPageCursor().isEmpty()) {
			return;
		}
		if (memberPageHistory.size() == MAX_REDIS_MEMBER_HISTORY_PAGES) {
			memberPageHistory.removeFirst();
		}
		memberPageHistory.add(details);
		if (hasBufferedMember) {
			showNextBufferedRedisMemberPage(details);
		} else {
			memberPageCursor = details.nextMemberPageCursor();
			showSelectedRedisKeyDetails();
		}
	}

	/** 返回有界历史中保存的上一页快照；游标状态随详情一并恢复。 */
	private void loadPreviousRedisMemberPage() {
		if (memberPageHistory.isEmpty()) {
			return;
		}
		selectedKeyDetails = memberPageHistory.remove(memberPageHistory.size() - 1);
		memberPageCursor = selectedKeyDetails.memberPageCursor();
		populateRedisMemberTable(selectedKeyDetails);
		updateRedisMutationButtons();
	}

	/** 先耗尽 HSCAN/SSCAN 单次响应超过 20 项的本地缓冲，再请求服务端返回的下一游标。 */
	private void showNextBufferedRedisMemberPage(WorkbenchRedisConnection.RedisKeyDetails details) {
		String pageCursor = details.nextMemberPageCursor();
		int pageNumber = parseBufferedRedisPageNumber(pageCursor);
		List<Map.Entry<String, String>> hashBuffer = details.hashBufferedPreview();
		List<String> setBuffer = details.setBufferedPreview();
		List<Map.Entry<String, String>> hashPage = List.of();
		List<String> setPage = List.of();
		int bufferEnd = details.memberBufferOffset();
		if (!hashBuffer.isEmpty()) {
			bufferEnd = Math.min(hashBuffer.size(), bufferEnd + 20);
			hashPage = List.copyOf(hashBuffer.subList(details.memberBufferOffset(), bufferEnd));
		}
		if (!setBuffer.isEmpty()) {
			bufferEnd = Math.min(setBuffer.size(), bufferEnd + 20);
			setPage = List.copyOf(setBuffer.subList(details.memberBufferOffset(), bufferEnd));
		}
		String pageToken = pageCursor;
		String nextCursor;
		if (hashBuffer.size() > bufferEnd || setBuffer.size() > bufferEnd) {
			nextCursor = details.type() + "-buffer:" + (pageNumber + 1);
		} else if ("hash".equals(details.type()) && !"0".equals(details.hashNextScanCursor())) {
			nextCursor = "hash:" + details.hashNextScanCursor();
		} else if ("set".equals(details.type()) && !"0".equals(details.setNextScanCursor())) {
			nextCursor = "set:" + details.setNextScanCursor();
		} else {
			nextCursor = "";
		}
		memberPageCursor = pageToken;
		selectedKeyDetails = new WorkbenchRedisConnection.RedisKeyDetails(details.type(), details.ttlSeconds(),
			details.stringPreview(), details.listPreview(), details.sortedSetPreview(), hashPage, setPage,
			details.streamPreview(), details.listLength(), details.editableStringSnapshot(), pageToken, nextCursor,
			details.memberPageOffset(), hashBuffer, setBuffer, details.hashNextScanCursor(),
			details.setNextScanCursor(), bufferEnd);
		populateRedisMemberTable(selectedKeyDetails);
		updateRedisMutationButtons();
	}

	/** 缓冲页 token 只用作本地页面身份，不会发送到 Redis。 */
	private int parseBufferedRedisPageNumber(String pageCursor) {
		int separator = pageCursor.indexOf(':');
		if (separator < 0) {
			return 1;
		}
		try {
			return Math.max(1, Integer.parseInt(pageCursor.substring(separator + 1)));
		} catch (NumberFormatException ignored) {
			return 1;
		}
	}

	/** 清空成员页游标历史，避免切换键或写入成功后沿用旧位置。 */
	private void resetRedisMemberPage() {
		memberPageCursor = "";
		memberPageType = "";
		memberPageHistory.clear();
	}

	/** 新建一个字符串键，使用 SET NX 防止覆盖现有键；TTL 为空表示不过期。 */
	private void createRedisStringKey() {
		JTextField keyInput = new JTextField(32);
		JTextArea valueInput = new JTextArea(8, 32);
		JTextField ttlInput = new JTextField(12);
		JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
		form.add(new JLabel(message("redis.key.name")));
		form.add(keyInput);
		form.add(new JLabel(message("redis.string.value")));
		form.add(new JBScrollPane(valueInput));
		form.add(new JLabel(message("redis.ttl.optional.seconds")));
		form.add(ttlInput);
		int choice = JOptionPane.showConfirmDialog(this, form, message("redis.create.string"),
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (choice != JOptionPane.OK_OPTION) {
			return;
		}
		String key = keyInput.getText();
		String value = valueInput.getText();
		String ttlText = ttlInput.getText().strip();
		if (key.isEmpty()) {
			status.setText(message("redis.key.name.required"));
			return;
		}
		long ttlSeconds = 0;
		if (!ttlText.isEmpty()) {
			try {
				ttlSeconds = Long.parseLong(ttlText);
			} catch (NumberFormatException failure) {
				status.setText(message("redis.ttl.positive"));
				return;
			}
			if (ttlSeconds < 1) {
				status.setText(message("redis.ttl.positive"));
				return;
			}
		}
		String targetKey = key;
		long targetTtl = ttlSeconds;
		long targetScanRevision = scanRevision;
		runRedisOperation(message("redis.create.string"), connection ->
			connection.createRedisStringKey(targetKey, value, targetTtl), created -> {
			if (created) {
				if (targetScanRevision == scanRevision) {
					bufferedScanPage = null;
					bufferedScanOffset = 0;
					scannedKeys.clear();
					scannedKeys.addElement(targetKey);
					keyList.setSelectedIndex(0);
					keyList.ensureIndexIsVisible(0);
				}
				status.setText(message("redis.string.created"));
			} else {
				status.setText(message("redis.key.already.exists"));
			}
		});
	}

	/**
	 * 通过独立入口新建非 String 结构键；Lua 在同一键槽内确认键不存在并写入首个成员。
	 */
	private void createRedisAggregateKey() {
		String[] types = {"hash", "set", "zset", "list-left", "list-right", "stream"};
		String[] typeLabels = {message("redis.type.hash"), message("redis.type.set"), message("redis.type.zset"),
			message("redis.type.list.left"), message("redis.type.list.right"), message("redis.type.stream")};
		JTextField keyInput = new JTextField(30);
		JComboBox<String> typeInput = new JComboBox<>(typeLabels);
		JLabel firstLabel = new JLabel();
		JTextField firstValue = new JTextField(30);
		JLabel secondLabel = new JLabel();
		JTextField secondValue = new JTextField(30);
		JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
		form.add(new JLabel(message("redis.key.name")));
		form.add(keyInput);
		form.add(new JLabel(message("redis.create.key.type")));
		form.add(typeInput);
		form.add(new JLabel(message("redis.create.aggregate.ttl.note")));
		form.add(new JLabel(""));
		form.add(firstLabel);
		form.add(firstValue);
		form.add(secondLabel);
		form.add(secondValue);
		Runnable refreshFields = () -> {
			int index = typeInput.getSelectedIndex();
			firstLabel.setText(message(index == 0 ? "redis.hash.field" : index == 5 ? "redis.stream.field"
				: index == 1 || index == 2 ? "redis.set.member" : "redis.list.value"));
			if (index == 0 || index == 5) {
				secondLabel.setText(message("redis.string.value"));
				secondValue.setEnabled(true);
			} else if (index == 2) {
				secondLabel.setText(message("redis.zset.score"));
				secondValue.setEnabled(true);
			} else {
				secondLabel.setText("");
				secondValue.setText("");
				secondValue.setEnabled(false);
			}
		};
		typeInput.addActionListener(event -> refreshFields.run());
		refreshFields.run();
		int choice = JOptionPane.showConfirmDialog(this, form, message("redis.create.aggregate"),
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (choice != JOptionPane.OK_OPTION) {
			return;
		}
		String key = keyInput.getText();
		if (key.isEmpty()) {
			status.setText(message("redis.key.name.required"));
			return;
		}
		int typeIndex = typeInput.getSelectedIndex();
		String second = secondValue.isEnabled() ? secondValue.getText() : "";
		if (typeIndex == 2) {
			Double score = parseRedisScore(second);
			if (Objects.isNull(score)) {
				return;
			}
			second = Double.toString(score);
		}
		String targetKey = key;
		String targetType = types[typeIndex];
		String targetFirst = firstValue.getText();
		String targetSecond = second;
		long targetScanRevision = scanRevision;
		runRedisOperation(message("redis.create.aggregate"), connection -> connection.createRedisAggregateKey(
			targetKey, targetType, targetFirst, targetSecond), created -> {
			if (created) {
				if (targetScanRevision == scanRevision) {
					bufferedScanPage = null;
					bufferedScanOffset = 0;
					scannedKeys.clear();
					scannedKeys.addElement(targetKey);
					keyList.setSelectedIndex(0);
					keyList.ensureIndexIsVisible(0);
				}
				status.setText(message("redis.aggregate.created"));
			} else {
				status.setText(message("redis.key.already.exists"));
			}
		});
	}


	/**
	 * 读取当前字符串原值后打开有限长度编辑器，提交时使用 Redis Lua 比较原值并原子保留 TTL。
	 */
	private void editSelectedRedisString() {
		String key = selectedTreeKey;
		var details = selectedKeyDetails;
		if (Objects.isNull(key) || Objects.isNull(details) || Objects.isNull(details.editableStringSnapshot())) {
			return;
		}
		long revision = connectionRevision;
		String original = details.editableStringSnapshot();
		JTextArea editor = new JTextArea(original, 12, 56);
		int choice = JOptionPane.showConfirmDialog(this, new JBScrollPane(editor), message("redis.edit.string"),
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (choice != JOptionPane.OK_OPTION || revision != connectionRevision
			|| !Objects.equals(key, selectedTreeKey) || details != selectedKeyDetails || original.equals(editor.getText())) {
			return;
		}
		String replacement = editor.getText();
		runRedisOperation(message("redis.edit.string"), connection ->
			connection.replaceRedisStringValue(key, original, replacement),
			() -> revision == connectionRevision && Objects.equals(key, selectedTreeKey), saved -> {
			status.setText(message(saved ? "redis.string.saved" : "redis.key.changed"));
			showSelectedRedisKeyDetails();
		});
	}

	/**
	 * 为已读取详情的原始键设置正数秒 TTL，后端会原子检查键类型并通过原键计算集群槽位。
	 */
	private void setSelectedRedisKeyTtl() {
		String key = selectedTreeKey;
		var details = selectedKeyDetails;
		if (Objects.isNull(key) || Objects.isNull(details) || Objects.isNull(details.editableStringSnapshot())) {
			return;
		}
		String initial = details.ttlSeconds() > 0 ? Long.toString(details.ttlSeconds()) : "60";
		String input = JOptionPane.showInputDialog(this, message("redis.ttl.seconds.prompt"), initial);
		if (Objects.isNull(input)) {
			return;
		}
		long seconds;
		try {
			seconds = Long.parseLong(input.strip());
		} catch (NumberFormatException failure) {
			status.setText(message("redis.ttl.positive"));
			return;
		}
		if (seconds < 1) {
			status.setText(message("redis.ttl.positive"));
			return;
		}
		long revision = connectionRevision;
		runRedisOperation(message("redis.set.ttl"), connection ->
			connection.setRedisKeyTtl(key, details.editableStringSnapshot(), seconds),
			() -> revision == connectionRevision && Objects.equals(key, selectedTreeKey), updated -> {
			status.setText(message(updated ? "redis.ttl.updated" : "redis.key.changed"));
			showSelectedRedisKeyDetails();
		});
	}

	/**
	 * 清除当前键 TTL；设置为永久存活前再次校验键类型。
	 */
	private void clearSelectedRedisKeyTtl() {
		String key = selectedTreeKey;
		var details = selectedKeyDetails;
		if (Objects.isNull(key) || Objects.isNull(details) || details.ttlSeconds() < 0
			|| Objects.isNull(details.editableStringSnapshot())) {
			return;
		}
		long revision = connectionRevision;
		runRedisOperation(message("redis.clear.ttl"), connection ->
			connection.clearRedisKeyTtl(key, details.editableStringSnapshot()),
			() -> revision == connectionRevision && Objects.equals(key, selectedTreeKey), cleared -> {
			status.setText(message(cleared ? "redis.ttl.cleared" : "redis.key.changed"));
			showSelectedRedisKeyDetails();
		});
	}

	/**
	 * 二次确认后删除当前选中的单个键，不解析或拆分原始键名。
	 */
	private void deleteSelectedRedisKey() {
		String key = selectedTreeKey;
		var details = selectedKeyDetails;
		if (Objects.isNull(key) || Objects.isNull(details)) {
			return;
		}
		long revision = connectionRevision;
		if (Objects.nonNull(details.editableStringSnapshot())) {
			int choice = JOptionPane.showConfirmDialog(this,
				message("redis.delete.key.confirm", key, details.type()), message("redis.delete.key"),
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
			if (choice != JOptionPane.OK_OPTION || !isSelectedRedisKeyCurrent(key, details, revision)) {
				return;
			}
			runRedisOperation(message("redis.delete.key"), connection ->
				connection.deleteRedisKey(key, details.editableStringSnapshot()),
				() -> isSelectedRedisKeyCurrent(key, details, revision), this::showRedisKeyDeletionResult);
			return;
		}
		if (!List.of("hash", "set", "zset", "list", "stream").contains(details.type())) {
			return;
		}
		runCancellableRedisRead(message("redis.delete.key.snapshot.reading"),
			connection -> connection.readRedisKeyDeletionSnapshot(key),
			() -> isSelectedRedisKeyCurrent(key, details, revision), snapshot -> {
				if (snapshot.status() != WorkbenchRedisConnection.RedisKeyDeletionSnapshotStatus.READY) {
					status.setText(message(redisKeyDeletionStatusMessage(snapshot.status())));
					return;
				}
				int choice = JOptionPane.showConfirmDialog(this,
					message("redis.delete.key.composite.confirm", key, snapshot.type(), snapshot.memberCount()),
					message("redis.delete.key"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
				if (choice != JOptionPane.OK_OPTION || !isSelectedRedisKeyCurrent(key, details, revision)) {
					return;
				}
				runRedisOperation(message("redis.delete.key"),
					connection -> connection.deleteRedisKey(key, snapshot),
					() -> isSelectedRedisKeyCurrent(key, details, revision), this::showRedisKeyDeletionResult);
			});
	}

	/** 在写入前重新验证用户仍选中原连接、键和详情对象。 */
	private boolean isSelectedRedisKeyCurrent(String key, WorkbenchRedisConnection.RedisKeyDetails details,
										  long revision) {
		return revision == connectionRevision && Objects.equals(key, selectedTreeKey) && details == selectedKeyDetails;
	}

	/** 统一处理字符串及复合键的原子删除结果。 */
	private void showRedisKeyDeletionResult(boolean deleted) {
		if (deleted) {
			String deletedKey = selectedTreeKey;
			selectedTreeKey = null;
			selectedKeyDetails = null;
			keyList.clearSelection();
			removeDeletedRedisKeyFromScanPages(deletedKey);
			viewKeyDetails.setEnabled(false);
			updateRedisMutationButtons();
			output.setText("");
			clearRedisMemberTable();
			status.setText(message("redis.key.deleted"));
		} else {
			status.setText(message("redis.key.changed"));
			showSelectedRedisKeyDetails();
		}
	}

	/** 从当前可见页和剩余 SCAN 缓冲中移除刚删除的键，避免本地缓存再次展示已删除项。 */
	private void removeDeletedRedisKeyFromScanPages(String deletedKey) {
		for (int index = scannedKeys.size() - 1; index >= 0; index--) {
			if (Objects.equals(deletedKey, scannedKeys.get(index))) {
				scannedKeys.remove(index);
			}
		}
		if (Objects.nonNull(bufferedScanPage)) {
			List<String> bufferedKeys = new ArrayList<>(bufferedScanPage.keys());
			for (int index = bufferedKeys.size() - 1; index >= 0; index--) {
				if (!Objects.equals(deletedKey, bufferedKeys.get(index))) {
					continue;
				}
				bufferedKeys.remove(index);
				if (index < bufferedScanOffset) {
					bufferedScanOffset--;
				}
			}
			bufferedScanPage = new RedisKeyPage(bufferedScanPage.cursor(), List.copyOf(bufferedKeys));
		}
	}

	/** 将连接层快照拒绝原因映射为不含数据值的本地化状态。 */
	private String redisKeyDeletionStatusMessage(
		WorkbenchRedisConnection.RedisKeyDeletionSnapshotStatus snapshotStatus) {
		return switch (snapshotStatus) {
			case TOO_LARGE -> "redis.delete.key.snapshot.too.large";
			case SCAN_LIMIT -> "redis.delete.key.snapshot.scan.limit";
			case UNSUPPORTED_ENCODING -> "redis.delete.key.snapshot.encoding";
			case EXPIRATION_UNSUPPORTED -> "redis.delete.key.snapshot.expiration.unsupported";
			case STREAM_INFO_UNSUPPORTED -> "redis.delete.key.snapshot.stream.info.unsupported";
			case STREAM_HAS_GROUPS -> "redis.delete.key.snapshot.stream.groups";
			default -> "redis.key.changed";
		};
	}

	/**
	 * 捕获 Redis 命令输入，再由后台连接执行。
	 */
	private void executeEditorRedisCommand() {
		String commandName = command.getText().strip();
		String key = routingKey.getText();
		String input = arguments.getText();
		List<String> params = input.isEmpty() ? List.of() : input.lines().toList();
		runRedisOperation(message("run.command"), connection -> displayRedisReply(
			connection.executeRedisCommand(key, commandName, params)), commandOutput::setText);
	}

	/**
	 * 以 UTF-8 展示文本回复，并展开 Redis 的多值回复。
	 *
	 * @param reply Redis 原生回复
	 * @return 供用户阅读的文本
	 */
	private static String displayRedisReply(Object reply) {
		if (reply instanceof byte[] bytes) {
			return new String(bytes, StandardCharsets.UTF_8);
		}
		if (reply instanceof List<?> list) {
			return list.stream().map(WorkbenchRedisPanel::displayRedisReply).collect(Collectors.joining(", ", "[", "]"));
		}
		return Objects.toString(reply, "(nil)");
	}

	/**
	 * 捕获当前连接状态，并在后台为本次操作连接及自动关闭。
	 *
	 * @param stage     操作阶段
	 * @param operation 后台 Redis 操作
	 * @param callback  成功后更新界面的回调
	 * @param <T>       操作结果类型
	 */
	private <T> void runRedisOperation(String stage, Function<WorkbenchRedisConnection, T> operation,
									   Consumer<T> callback) {
		runRedisOperation(stage, operation, () -> true, callback);
	}

	/**
	 * 在应用结果前额外核对扫描条件，防止旧游标或错误覆盖新范围的状态。
	 *
	 * @param <T> Redis 操作结果类型
	 * @param stage 操作阶段
	 * @param operation 后台命令
	 * @param resultStillCurrent EDT 上检查请求是否仍属于当前范围
	 * @param callback 结果有效时更新界面
	 */
	private <T> void runRedisOperation(String stage, Function<WorkbenchRedisConnection, T> operation,
									   BooleanSupplier resultStillCurrent, Consumer<T> callback) {
		runRedisOperation(stage, operation, resultStillCurrent, callback, false);
	}

	/** 为只读键扫描与详情读取开放“取消等待”；不调用 SwingWorker.cancel 以保留任务槽直到 I/O 返回。 */
	private <T> void runCancellableRedisRead(String stage, Function<WorkbenchRedisConnection, T> operation,
											 BooleanSupplier resultStillCurrent, Consumer<T> callback) {
		runCancellableRedisRead(stage, operation, resultStillCurrent, callback, () -> {
		});
	}

	/** 为只读操作提供取消结果后的状态恢复，不会中断后台 Redis socket。 */
	private <T> void runCancellableRedisRead(String stage, Function<WorkbenchRedisConnection, T> operation,
											 BooleanSupplier resultStillCurrent, Consumer<T> callback,
											 Runnable cancelledCallback) {
		runRedisOperation(stage, operation, resultStillCurrent, callback, true, cancelledCallback);
	}

	/**
	 * 执行一个 Redis 后台操作；只读扫描可取消 UI 结果，写操作不开放取消入口。
	 *
	 * @param stage 操作阶段
	 * @param operation 后台 Redis 操作
	 * @param resultStillCurrent EDT 上检查请求是否仍属于当前范围
	 * @param callback 结果有效时更新界面
	 * @param cancellableRead 是否为可取消结果的只读操作
	 * @param <T> Redis 操作结果类型
	 */
	private <T> void runRedisOperation(String stage, Function<WorkbenchRedisConnection, T> operation,
									   BooleanSupplier resultStillCurrent, Consumer<T> callback,
									   boolean cancellableRead) {
		runRedisOperation(stage, operation, resultStillCurrent, callback, cancellableRead, () -> {
		});
	}

	/**
	 * 执行一个 Redis 后台操作，并在只读结果被取消时恢复该操作开始前的界面状态。
	 *
	 * @param stage 操作阶段
	 * @param operation 后台 Redis 操作
	 * @param resultStillCurrent EDT 上检查请求是否仍属于当前范围
	 * @param callback 结果有效时更新界面
	 * @param cancellableRead 是否为可取消结果的只读操作
	 * @param cancelledCallback 取消结果后恢复原界面的动作
	 * @param <T> Redis 操作结果类型
	 */
	private <T> void runRedisOperation(String stage, Function<WorkbenchRedisConnection, T> operation,
									   BooleanSupplier resultStillCurrent, Consumer<T> callback,
									   boolean cancellableRead, Runnable cancelledCallback) {
		if (Objects.nonNull(redisTask)) {
			return;
		}
		List<String> seeds = Arrays.stream(endpoints.getText().split(",", -1)).map(String::strip).toList();
		boolean clustered = cluster.isSelected();
		boolean encrypted = tls.isSelected();
		int dbIndex = clustered ? 0 : (Integer) database.getValue();
		String user = username.getText();
		char[] secret = password.getPassword();
		boolean verifyServerCertificate = verifyCertificate;
		long revision = connectionRevision;
		redisReadWaitCancellable = cancellableRead;
		redisReadWaitCancellationRequested = false;
		cancelRedisReadWait.setEnabled(cancellableRead);
		status.setText(stage + message("in.progress"));
		redisTask = new SwingWorker<T, Void>() {
			/** {@inheritDoc} 密码字符数组在后台连接初始化后释放。 */
			@Override
			protected T doInBackground() {
				try (var connection = new WorkbenchRedisConnection(seeds, clustered, encrypted, dbIndex, user,
					new String(secret), verifyServerCertificate, DynamicBundle.getLocale())) {
					if (cancellableRead && redisReadWaitCancellationRequested) {
						return null;
					}
					return operation.apply(connection);
				} finally {
					Arrays.fill(secret, '\0');
				}
			}

			/** {@inheritDoc} 任务被取消时跳过 UI 更新。 */
			@Override
			protected void done() {
				try {
					finishRedisOperation();
				} finally {
					if (pendingRedisPatternScan && !isCancelled() && Objects.isNull(redisTask)) {
						pendingRedisPatternScan = false;
						scanSelectedRedisNode();
					} else if (pendingRedisKeyDetails && !isCancelled() && Objects.isNull(redisTask)) {
						pendingRedisKeyDetails = false;
						showSelectedRedisKeyDetails();
					}
				}
			}

			/** 先释放工作槽，再根据当前连接版本决定是否应用结果。 */
			private void finishRedisOperation() {
				boolean resultCancelled = redisReadWaitCancellationRequested;
				redisTask = null;
				redisReadWaitCancellable = false;
				redisReadWaitCancellationRequested = false;
				cancelRedisReadWait.setEnabled(false);
				if (isCancelled() || revision != connectionRevision) {
					return;
				}
				if (!resultStillCurrent.getAsBoolean()) {
					status.setText(message("redis.scan.scope.changed"));
					return;
				}
				try {
					T result = get();
					if (resultCancelled) {
						cancelledCallback.run();
						status.setText(message("redis.read.cancelled"));
						return;
					}
					status.setText(stage + message("completed"));
					callback.accept(result);
				} catch (InterruptedException failure) {
					Thread.currentThread().interrupt();
				} catch (ExecutionException failure) {
					Throwable cause = failure.getCause();
					log.warn("Redis 操作失败，阶段={}，节点={}，集群={}", stage, seeds, clustered, cause);
					if (resultCancelled) {
						cancelledCallback.run();
					}
					status.setText(resultCancelled ? message("redis.read.cancelled")
						: stage + message("failed") + cause.getMessage());
				}
			}
		};
		redisTask.execute();
	}

	/** 丢弃当前 SCAN/键详情结果，但不提前释放任务槽或声称底层 Redis I/O 已中断。 */
	private void cancelRedisReadResultWait() {
		if (Objects.isNull(redisTask) || !redisReadWaitCancellable || redisReadWaitCancellationRequested) {
			return;
		}
		redisReadWaitCancellationRequested = true;
		cancelRedisReadWait.setEnabled(false);
		status.setText(message("redis.read.cancel.requested"));
	}

	/**
	 * 关闭标签页时取消任务请求，并清空界面密码。
	 */
	@Override
	public void dispose() {
		pendingRedisKeyDetails = false;
		redisReadWaitCancellable = false;
		cancelRedisReadWait.setEnabled(false);
		if (Objects.nonNull(redisTask)) {
			redisTask.cancel(true);
		}
		password.setText("");
	}
}
