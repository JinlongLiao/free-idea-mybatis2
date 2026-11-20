package io.github.jinlongliao.mybatis.workbench.ui;

import static io.github.jinlongliao.mybatis.workbench.ui.WorkbenchBundle.message;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.icons.AllIcons;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.SystemInfoRt;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.FormBuilder;

import org.mybatis.generator.api.intellij.IntellijColumnInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import javax.swing.tree.*;

import io.github.jinlongliao.mybatis.workbench.database.*;
import io.github.jinlongliao.mybatis.workbench.model.DbType;
import io.github.jinlongliao.mybatis.workbench.model.GenerationTable;
import io.github.jinlongliao.mybatis.workbench.setting.WorkbenchConnectionProfiles;
import io.github.jinlongliao.mybatis.workbench.setting.WorkbenchConnectionProfilesListener;

/**
 * 独立 JDBC 窗口。每次浏览或执行 SQL 使用独立连接，后台完成数据库操作。
 *
 * @author liaojinlong
 * @since 2026/11/18 15:11
 */
final class WorkbenchDatabasePanel extends JPanel implements Disposable {
	/**
	 * 在操作边界记录失败阶段及堆栈。
	 */
	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	/**
	 * 用于创建 MyBatis 生成窗口的项目。
	 */
	private final Project project;

	/**
	 * 与 SQL 共用同一控制台区域，只有通过统一数据源树才能打开。
	 */
	private final CardLayout consoleLayout = new CardLayout();

	/**
	 * 数据源树下方的可切换控制台，初始收起。
	 */
	private final JPanel consoleWorkspace = new JPanel(consoleLayout);

	/** 当前非 JDBC 控制台使用的配置快照，由 EDT 管理。 */
	private NoSqlConnectionProfile openNoSqlProfile;

	/**
	 * Redis 节点、键和命令操作。
	 */
	private final WorkbenchRedisPanel redisPanel = new WorkbenchRedisPanel();

	/**
	 * MongoDB 集合及文档查询。
	 */
	private final WorkbenchMongoPanel mongoPanel = new WorkbenchMongoPanel();

	/**
	 * Elasticsearch 索引、映射和检索。
	 */
	private final WorkbenchElasticsearchPanel elasticsearchPanel = new WorkbenchElasticsearchPanel();

	/**
	 * 应用级配置在各项目间共享，由 IDE 持久化。
	 */
	private final WorkbenchConnectionProfiles workbenchConnectionProfiles =
		ApplicationManager.getApplication().getService(WorkbenchConnectionProfiles.class);

	/**
	 * 当前表单对应的稳定配置标识。导入和加载会替换它。
	 */
	private UUID profileId = UUID.randomUUID();

	/**
	 * 用于保存、导出及选择连接的显示名称。
	 */
	private final JTextField profileName = new JTextField();

	/**
	 * 关系数据库方言，决定生成配置及连接预设。
	 */
	private final JComboBox<DbType> databaseType = new JComboBox<>(DbType.values());

	/**
	 * Maven 坐标，默认使用选定数据库的官方驱动，可替换为兼容驱动及固定版本。
	 */
	private final JTextField driverCoordinates = new JTextField();

	/**
	 * 自定义 Maven 仓库地址，留空从 Maven Central 获取驱动。
	 */
	private final JBTextField repositoryUrl = new JBTextField();

	/**
	 * JDBC 驱动实现类。
	 */
	private final JTextField driverClass = new JTextField();

	/**
	 * 连接地址，例如 jdbc:postgresql://localhost:5432/postgres。
	 */
	private final JTextField jdbcUrl = new JTextField(32);

	/**
	 * 本次手动连接的用户名，可为空。
	 */
	private final JTextField username = new JTextField();

	/**
	 * 本次手动连接的密码，面板关闭时清空。
	 */
	private final JPasswordField password = new JPasswordField();

	/**
	 * 当前连接对象树的虚拟根，子节点为模式和数据库表。
	 */
	private final DefaultMutableTreeNode objectRoot = new DefaultMutableTreeNode(message("database.objects"));

	/**
	 * 由 EDT 更新的对象树模型。
	 */
	private final DefaultTreeModel objectModel = new DefaultTreeModel(objectRoot);

	/**
	 * 按已保存数据源缓存已读取的对象，只在 EDT 读写。
	 */
	private final Map<UUID, List<JdbcTable>> tablesBySource = new HashMap<>();

	/**
	 * 当前连接的对象清单，用于补全和选择同一连接中的参考表。
	 */
	private List<JdbcTable> browsedTables = List.of();

	/**
	 * 上次查过列的表，连接变化或重新浏览后失效。
	 */
	private JdbcTable sqlCompletionTable;

	/**
	 * 最近一次为已选表读取的列。
	 */
	private List<IntellijColumnInfo> sqlCompletionColumns = List.of();

	/**
	 * 当前连接的模式及表结构导航。
	 */
	private final Tree objectTree = new Tree(objectModel);

	/**
	 * 按需读取非 JDBC 数据源对象。
	 */
	private final WorkbenchNoSqlNavigator workbenchNoSqlNavigator = new WorkbenchNoSqlNavigator(objectModel, objectTree);

	/**
	 * 用户编辑的 SQL，一次执行一条。
	 */
	private final JTextArea sqlEditor = new JTextArea(5, 28);

	/**
	 * 按连接保存本窗口的未执行 SQL 草稿，只在 EDT 访问。
	 */
	private final Map<UUID, String> sqlDraftsBySource = new HashMap<>();

	/**
	 * 上一次 SQL 查询或表结构查看的只读结果。
	 */
	private final JBTable resultTable = new JBTable();

	/**
	 * 正显示的原始查询结果，在 EDT 中同步更新。
	 */
	private JdbcQueryResult currentQueryResult;

	/**
	 * 当前表数据和用于写入前校验的原值，仅在 EDT 访问。
	 */
	private JdbcTableRows currentTableRows;

	/**
	 * 显示当前有界表页的后续数据，普通查询结果不启用。
	 */
	private final JButton nextTablePage = databaseButton(message("next.page"), () -> browseTablePage(false));

	/**
	 * 重新读取当前表页之前的数据，普通查询结果不启用。
	 */
	private final JButton previousTablePage = databaseButton(message("previous.page"), () -> browseTablePage(true));

	/**
	 * 对有主键的表数据开放单元格编辑。
	 */
	private final JButton editTableCell = databaseButton(message("edit.selected.cell"), this::editVisibleTableCell);

	/**
	 * 只允许导出当前显示的查询结果。
	 */
	private final JButton exportCsv = databaseButton(message("export.csv"), this::exportVisibleQueryResult);

	/**
	 * 只有进入连接设置对话框时显示。
	 */
	private final JPanel connectionForm;

	/**
	 * 在连接设置中按需显示仓库和驱动覆盖项。
	 */
	private final JPanel driverForm;

	/**
	 * 查询区按需显示，初始以对象树为主。
	 */
	private final JPanel queryWorkspace = new JPanel(new BorderLayout(4, 4));

	/**
	 * 当前操作的状态或影响行数。
	 */
	private final JLabel status = new JLabel(message("set.up.a.connection.to.browse.tables.or.run.sql"));

	/**
	 * 面板一次执行一个任务，只在 EDT 访问。
	 */
	private SwingWorker<?, ?> databaseTask;

	/**
	 * 连接参数变化时递增，用于丢弃旧连接的后台结果，只在 EDT 访问。
	 */
	private long connectionRevision;

	/**
	 * 构造工具窗口。连接参数和输出均保留在当前窗口。
	 *
	 * @param project 当前项目
	 */
	WorkbenchDatabasePanel(Project project) {
		super(new BorderLayout(6, 6));
		this.project = project;
		JPanel jdbcAddress = new JPanel(new BorderLayout(4, 0));
		jdbcAddress.add(jdbcUrl, BorderLayout.CENTER);
		JButton browseDatabaseFile = databaseButton(message("browse.database.file"), this::chooseJdbcDatabaseFile);
		jdbcAddress.add(browseDatabaseFile, BorderLayout.EAST);
		JLabel jdbcUrlLabel = new JLabel("JDBC URL");
		connectionForm = FormBuilder.createFormBuilder()
			.addLabeledComponent(message("connection.name"), profileName)
			.addLabeledComponent(message("database"), databaseType)
			.addLabeledComponent(jdbcUrlLabel, jdbcAddress)
			.addLabeledComponent(message("username"), username)
			.addLabeledComponent(message("password"), password)
			.getPanel();
		jdbcUrlLabel.setLabelFor(jdbcUrl);
		repositoryUrl.getEmptyText().setText("Maven Central");
		repositoryUrl.setToolTipText(message("driver.repository.empty.uses.maven.central"));
		driverForm = FormBuilder.createFormBuilder()
			.addLabeledComponent(message("driver.maven.coordinates"), driverCoordinates)
			.addLabeledComponent(message("driver.repository"), repositoryUrl)
			.addLabeledComponent(message("driver.class"), driverClass)
			.getPanel();
		Runnable updateDriverForm = () -> {
			setDriverPreset();
			Object type = databaseType.getSelectedItem();
			browseDatabaseFile.setVisible(type == DbType.H2 || type == DbType.SQLite);
		};
		databaseType.addActionListener(event -> updateDriverForm.run());
		updateDriverForm.run();
		DocumentAdapter connectionListener = new DocumentAdapter() {
			/** {@inheritDoc} 连接输入变化后，旧连接的对象清单不再适用。 */
			@Override
			protected void textChanged(DocumentEvent event) {
				connectionRevision++;
				browsedTables = List.of();
				sqlCompletionTable = null;
				sqlCompletionColumns = List.of();
				currentQueryResult = null;
				currentTableRows = null;
				nextTablePage.setEnabled(false);
				previousTablePage.setEnabled(false);
				editTableCell.setEnabled(false);
				exportCsv.setEnabled(false);
				resultTable.setModel(resultModel(new Object[0]));
				status.setText(message("connection.changed.browse.tables.again"));
			}
		};
		for (JTextComponent field : List.of(driverCoordinates, repositoryUrl, driverClass, jdbcUrl, username, password)) {
			field.getDocument().addDocumentListener(connectionListener);
		}

		DocumentAdapter identityListener = new DocumentAdapter() {
			/** {@inheritDoc} 地址或用户变化后，测试和保存都需要重新输入凭据。 */
			@Override
			protected void textChanged(DocumentEvent event) {
				if (password.getDocument().getLength() > 0) {
					password.setText("");
				}
			}
		};
		jdbcUrl.getDocument().addDocumentListener(identityListener);
		username.getDocument().addDocumentListener(identityListener);

		objectTree.setRootVisible(false);
		// 双击表用于数据预览，列元数据由展开箭头和键盘展开触发。
		objectTree.setToggleClickCount(0);
		objectTree.setShowsRootHandles(true);
		objectTree.getEmptyText().setText(message("sources.empty"));
		objectTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
		objectTree.addTreeSelectionListener(event -> {
			if (Objects.nonNull(selectedNoSqlProfile())) {
				connectionRevision++;
				consoleWorkspace.setVisible(false);
			} else if (!queryWorkspace.isVisible()) {
				consoleWorkspace.setVisible(false);
			}
			JdbcConnectionProfile selected = selectedConnectionProfile();
			if (Objects.nonNull(selected) && !selected.equals(currentConnectionProfile())) {
				fillConnectionProfile(selected, true);
			}
			if (Objects.nonNull(currentTableRows) && !Objects.equals(selectedJdbcTable(), currentTableRows.table())) {
				currentTableRows = null;
				nextTablePage.setEnabled(false);
				previousTablePage.setEnabled(false);
				editTableCell.setEnabled(false);
			}
		});
		objectTree.addTreeWillExpandListener(new TreeWillExpandListener() {
			/** {@inheritDoc} 首次展开先读取元数据，成功后再显示真实子节点。 */
			@Override
			public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
				TreePath path = event.getPath();
				if (path.getLastPathComponent() instanceof DefaultMutableTreeNode node
					&& node.getUserObject() instanceof JdbcConnectionProfile source
					&& !tablesBySource.containsKey(source.id())) {
					if (Objects.isNull(databaseTask)) {
						objectTree.setSelectionPath(path);
						refreshSelectedSource();
					}
					// 加载失败时保持折叠，下次展开可以重试。
					throw new ExpandVetoException(event);
				}
				if (path.getLastPathComponent() instanceof DefaultMutableTreeNode node
					&& node.getUserObject() instanceof JdbcTable && node.getChildCount() == 1
					&& message("loading.source.objects").equals(((DefaultMutableTreeNode) node.getFirstChild()).getUserObject())) {
					if (Objects.isNull(databaseTask)) {
						objectTree.setSelectionPath(path);
						readSelectedTable(table -> showJdbcColumnNodes(node, table));
					}
					throw new ExpandVetoException(event);
				}
			}

			/** {@inheritDoc} 折叠后保留已读取的对象。 */
			@Override
			public void treeWillCollapse(TreeExpansionEvent event) {
			}
		});
		objectTree.setCellRenderer(new ColoredTreeCellRenderer() {
			/** {@inheritDoc} 源名称优先显示，类型补充说明，表与目录使用不同图标。 */
			@Override
			public void customizeCellRenderer(JTree tree, Object value, boolean selected,
											  boolean expanded, boolean leaf, int row, boolean focus) {
				Object object = value instanceof DefaultMutableTreeNode node ? node.getUserObject() : value;
				if (object instanceof JdbcConnectionProfile profile) {
					setIcon(AllIcons.Nodes.DataSchema);
					append(profile.name());
					String type = profile.dbType() == DbType.SqlServer ? "SQL Server" : profile.dbType().name();
					append("  " + type, SimpleTextAttributes.GRAYED_ATTRIBUTES);
				} else if (object instanceof NoSqlConnectionProfile profile) {
					setIcon(AllIcons.Nodes.DataSchema);
					append(profile.name());
					append("  " + noSqlTitle(profile.type()), SimpleTextAttributes.GRAYED_ATTRIBUTES);
				} else if (object instanceof JdbcTable table) {
					setIcon(AllIcons.Nodes.DataTables);
					append(table.name());
					String objectKind = switch (Objects.toString(table.type(), "")) {
						case "VIEW" -> message("table.kind.view");
						case "MATERIALIZED VIEW" -> message("table.kind.materialized.view");
						case "PARTITIONED TABLE" -> message("table.kind.partitioned");
						default -> "";
					};
					if (!objectKind.isEmpty()) {
						append("  " + objectKind, SimpleTextAttributes.GRAYED_ATTRIBUTES);
					}
				} else {
					if (!leaf) {
						setIcon(AllIcons.Nodes.Folder);
					}
					append(Objects.toString(object, ""));
				}
			}
		});

		JPanel toolbar = new JPanel(new BorderLayout(4, 0));
		JPanel sourceActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
		JButton add = new JButton(AllIcons.General.Add);
		add.setToolTipText(message("new.connection"));
		add.getAccessibleContext().setAccessibleName(message("new.connection"));
		add.addActionListener(event -> showAddSourceMenu(add));
		toolbar.add(add, BorderLayout.WEST);
		JButton settings = databaseIconButton(AllIcons.General.Settings, message("connection.settings"), this::editSelectedDataSource);
		JButton refresh = databaseIconButton(AllIcons.Actions.Refresh, message("refresh.database.objects"), this::refreshSelectedSource);
		JButton console = databaseIconButton(AllIcons.Actions.Execute, message("query.console"), this::openSelectedQueryConsole);
		JButton browse = databaseIconButton(AllIcons.Nodes.DataTables, message("browse.table.data"), this::previewSelectedTableRows);
		JButton ddl = databaseButton(message("show.ddl"), this::showSelectedTableDdl);
		sourceActions.add(settings);
		sourceActions.add(refresh);
		sourceActions.add(console);
		sourceActions.add(browse);
		sourceActions.add(ddl);
		// 只为当前树节点提供可执行的操作，避免误用上次选中的连接。
		Runnable updateDatabaseActions = () -> {
			boolean sourceSelected = Objects.nonNull(selectedConnectionProfile()) || Objects.nonNull(selectedNoSqlProfile());
			boolean tableSelected = objectTree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode node
				&& node.getUserObject() instanceof JdbcTable;
			settings.setEnabled(sourceSelected);
			refresh.setEnabled(sourceSelected);
			console.setEnabled(sourceSelected);
			browse.setEnabled(tableSelected);
			ddl.setEnabled(tableSelected);
		};
		objectTree.addTreeSelectionListener(event -> updateDatabaseActions.run());
		updateDatabaseActions.run();
		JButton more = new JButton("⋯");
		more.setToolTipText(message("connection.and.table.actions"));
		more.getAccessibleContext().setAccessibleName(message("connection.and.table.actions"));
		more.addActionListener(event -> showDatabaseActions(more));
		// 窄窗中保留新增及更多入口，选中项操作也可从更多菜单访问。
		toolbar.add(sourceActions, BorderLayout.CENTER);
		toolbar.add(more, BorderLayout.EAST);

		objectTree.addMouseListener(new MouseAdapter() {
			/** {@inheritDoc} 在按下时处理由系统触发的上下文菜单。 */
			@Override
			public void mousePressed(MouseEvent event) {
				openDatabaseObjectPopup(event);
			}

			/** {@inheritDoc} 兼容在释放鼠标时触发上下文菜单的系统。 */
			@Override
			public void mouseReleased(MouseEvent event) {
				openDatabaseObjectPopup(event);
			}

			/** {@inheritDoc} 双击数据源刷新，双击表预览数据。 */
			@Override
			public void mouseClicked(MouseEvent event) {
				if (event.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(event)
					|| Objects.isNull(objectTree.getPathForLocation(event.getX(), event.getY()))) {
					return;
				}
				if (objectTree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode node
					&& node.getUserObject() instanceof WorkbenchNoSqlNavigator.RedisScanContinuation) {
					workbenchNoSqlNavigator.loadNextRedisPage(node);
				} else if (Objects.nonNull(selectedJdbcTable())) {
					previewSelectedTableRows();
				} else {
					var profile = selectedNoSqlProfile();
					if (Objects.nonNull(profile)) {
						openSelectedNoSqlConsole(profile);
					} else if (objectTree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode node) {
						if (node.getUserObject() instanceof JdbcConnectionProfile) {
							refreshSelectedSource();
						} else if (node.getAllowsChildren()) {
							TreePath path = new TreePath(node.getPath());
							if (objectTree.isExpanded(path)) {
								objectTree.collapsePath(path);
							} else {
								objectTree.expandPath(path);
							}
						}
					}
				}
			}
		});
		objectTree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK), "workbench.object.menu");
		objectTree.getActionMap().put("workbench.object.menu", new AbstractAction() {
			/** {@inheritDoc} 将键盘触发的菜单定位到当前选中行。 */
			@Override
			public void actionPerformed(ActionEvent event) {
				var bounds = objectTree.getPathBounds(objectTree.getSelectionPath());
				if (Objects.nonNull(bounds)) {
					showDatabaseObjectMenu(bounds.x, bounds.y + bounds.height);
				}
			}
		});

		// 只在对象树拥有焦点时响应，避免覆盖 SQL 编辑器及 IDE 的常规快捷键。
		objectTree.registerKeyboardAction(event -> {
			if (Objects.nonNull(selectedJdbcTable())) {
				previewSelectedTableRows();
			} else if (objectTree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode node
				&& (node.getUserObject() instanceof JdbcConnectionProfile || node.getUserObject() instanceof NoSqlConnectionProfile)) {
				openSelectedQueryConsole();
			}
		}, KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0), WHEN_FOCUSED);
		int menuModifier = SystemInfoRt.isMac ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
		objectTree.registerKeyboardAction(event -> showSelectedTableDdl(),
			KeyStroke.getKeyStroke(KeyEvent.VK_B, menuModifier), WHEN_FOCUSED);

		JPanel sqlToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
		Runnable runSql = () -> executeEditorSql(false);
		sqlToolbar.add(databaseButton(message("run.sql"), runSql));
		// 与工具栏使用同一执行入口，保留选区及数据源检查。
		sqlEditor.registerKeyboardAction(event -> runSql.run(),
			KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, menuModifier), WHEN_FOCUSED);
		sqlToolbar.add(databaseButton(message("show.estimated.plan"), () -> executeEditorSql(true)));
		sqlToolbar.add(databaseButton(message("complete.sql"), this::requestSqlCompletion));
		editTableCell.setEnabled(false);
		sqlToolbar.add(editTableCell);
		nextTablePage.setEnabled(false);
		previousTablePage.setEnabled(false);
		sqlToolbar.add(previousTablePage);
		sqlToolbar.add(nextTablePage);
		exportCsv.setEnabled(false);
		sqlToolbar.add(exportCsv);
		resultTable.setCellSelectionEnabled(true);
		sqlEditor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK), "workbench.sql.complete");
		sqlEditor.getActionMap().put("workbench.sql.complete", new AbstractAction() {
			/** {@inheritDoc} 在 SQL 编辑器中以快捷键请求候选。 */
			@Override
			public void actionPerformed(ActionEvent event) {
				requestSqlCompletion();
			}
		});
		OnePixelSplitter query = new OnePixelSplitter(true, 0.4f);
		query.setFirstComponent(new JBScrollPane(sqlEditor));
		query.setSecondComponent(new JBScrollPane(resultTable));
		queryWorkspace.add(sqlToolbar, BorderLayout.NORTH);
		queryWorkspace.add(query, BorderLayout.CENTER);
		consoleWorkspace.add(queryWorkspace, "SQL");
		consoleWorkspace.add(new JBScrollPane(redisPanel), NoSqlSourceType.REDIS.name());
		consoleWorkspace.add(new JBScrollPane(mongoPanel), NoSqlSourceType.MONGODB.name());
		consoleWorkspace.add(new JBScrollPane(elasticsearchPanel), NoSqlSourceType.ELASTICSEARCH.name());
		consoleWorkspace.setVisible(false);

		// 窗口较矮时两区按比例缩放，控制台收起后对象树使用全部空间。
		OnePixelSplitter content = new OnePixelSplitter(true, 0.55f, 0.2f, 0.8f);
		content.setFirstComponent(new JBScrollPane(objectTree));
		content.setSecondComponent(consoleWorkspace);
		add(toolbar, BorderLayout.NORTH);
		add(content, BorderLayout.CENTER);
		add(status, BorderLayout.SOUTH);
		ApplicationManager.getApplication().getMessageBus().connect(this)
			.subscribe(WorkbenchConnectionProfilesListener.PROFILES_RELOADED, this::refreshDataSourceNodes);
		refreshDataSourceNodes();
	}

	/**
	 * 用独立对话框创建或编辑连接。校验失败时保留输入，取消时仅恢复仍然有效的先前连接。
	 * 保存配置后由用户展开数据源或主动刷新时加载对象。
	 *
	 * @param newDatabaseType 从新增菜单选定的方言，编辑或导入时为空
	 * @param incomingProfile 导入或迁移待确认的配置，其他入口为空
	 */
	private void editConnectionSettings(DbType newDatabaseType, JdbcConnectionProfile incomingProfile) {
		boolean create = Objects.nonNull(newDatabaseType) || Objects.nonNull(incomingProfile);
		JdbcConnectionProfile previous = currentConnectionProfile();
		JdbcConnectionProfile previousSaved = selectedConnectionProfile();
		JdbcConnectionProfile expected = create ? null : previousSaved;
		char[] previousPassword = password.getPassword();
		try {
			if (Objects.nonNull(incomingProfile)) {
				fillConnectionProfile(incomingProfile, false);
			} else if (create) {
				switchSqlDraft(UUID.randomUUID());
				profileName.setText("");
				databaseType.setSelectedItem(newDatabaseType);
				setDriverPreset();
				repositoryUrl.setText("");
				jdbcUrl.setText("");
				username.setText("");
				password.setText("");
			}
			JPanel settings = new JPanel(new BorderLayout(0, 6));
			JPanel connectionSettings = new JPanel(new BorderLayout(0, 6));
			connectionSettings.add(connectionForm, BorderLayout.NORTH);

			JCheckBox showDriverSettings = new JCheckBox(message("driver.settings"));
			driverForm.setVisible(false);
			showDriverSettings.addActionListener(event -> {
				driverForm.setVisible(showDriverSettings.isSelected());
				// 对话框打开后重新计算尺寸，窄窗口内也能完整显示仓库输入项。
				Window dialog = SwingUtilities.getWindowAncestor(showDriverSettings);
				if (Objects.nonNull(dialog)) {
					dialog.pack();
				}
			});
			JPanel driverSettings = new JPanel(new BorderLayout(0, 4));
			driverSettings.add(showDriverSettings, BorderLayout.NORTH);
			driverSettings.add(driverForm, BorderLayout.CENTER);
			connectionSettings.add(driverSettings, BorderLayout.CENTER);
			settings.add(connectionSettings, BorderLayout.CENTER);
			JPanel diagnostics = new JPanel(new BorderLayout(6, 0));
			JTextArea testResult = new JTextArea(2, 24);
			testResult.setEditable(false);
			testResult.setLineWrap(true);
			testResult.setWrapStyleWord(true);
			diagnostics.add(databaseButton(message("test.connection"), this::testCurrentJdbcConnection), BorderLayout.WEST);
			diagnostics.add(new JBScrollPane(testResult), BorderLayout.CENTER);
			settings.add(diagnostics, BorderLayout.SOUTH);

			PropertyChangeListener updateTestResult = event -> testResult.setText(status.getText());
			status.addPropertyChangeListener("text", updateTestResult);
			String title = message(create ? "new.connection" : "connection.settings");
			try {
				while (JOptionPane.showConfirmDialog(this, settings, title,
					JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
					try {
						saveConnectionProfile(expected);
					} catch (IllegalArgumentException failure) {
						testResult.setText(failure.getMessage());
						continue;
					}
					// 对话框关闭后，尚未返回的测试结果不能覆盖已保存状态。
					connectionRevision++;
					return;
				}
			} finally {
				status.removePropertyChangeListener("text", updateTestResult);
			}
			if (Objects.isNull(previousSaved) || workbenchConnectionProfiles.listProfiles().contains(previousSaved)) {
				fillConnectionProfile(previous, false);
				password.setText(new String(previousPassword));
			} else {
				// 新建表单使用不同 ID，重载通知可能未清理原连接的控制台。
				password.setText("");
				if (queryWorkspace.isVisible()) {
					consoleWorkspace.setVisible(false);
				}
				refreshDataSourceNodes();
				status.setText(message("data.source.changed"));
			}
		} finally {
			Arrays.fill(previousPassword, '\0');
		}
	}

	/**
	 * 取得选中节点所属的已保存连接。
	 */
	private JdbcConnectionProfile selectedConnectionProfile() {
		Object selection = objectTree.getLastSelectedPathComponent();
		if (selection instanceof DefaultMutableTreeNode node) {
			for (var current = node; Objects.nonNull(current); current = (DefaultMutableTreeNode) current.getParent()) {
				if (current.getUserObject() instanceof JdbcConnectionProfile profile) {
					return profile;
				}
			}
		}
		return null;
	}

	/**
	 * 打开所选数据源的控制台，JDBC 控制台保留该源的草稿。
	 */
	private void openSelectedQueryConsole() {
		NoSqlConnectionProfile noSql = selectedNoSqlProfile();
		if (Objects.nonNull(noSql)) {
			openSelectedNoSqlConsole(noSql);
			return;
		}
		JdbcConnectionProfile jdbc = selectedConnectionProfile();
		if (Objects.isNull(jdbc)) {
			status.setText(message("select.query.source"));
			return;
		}
		if (!jdbc.equals(currentConnectionProfile())) {
			fillConnectionProfile(jdbc, true);
		}
		consoleLayout.show(consoleWorkspace, "SQL");
		consoleWorkspace.setVisible(true);
		sqlEditor.requestFocusInWindow();
	}

	/**
	 * 切换编辑器绑定的数据源，保留原连接的草稿并恢复目标连接的草稿。
	 *
	 * @param sourceId 即将使用的数据源标识
	 */
	private void switchSqlDraft(UUID sourceId) {
		if (profileId.equals(sourceId)) {
			return;
		}
		connectionRevision++;
		String draft = sqlEditor.getText();
		if (draft.isEmpty()) {
			sqlDraftsBySource.remove(profileId);
		} else {
			sqlDraftsBySource.put(profileId, draft);
		}
		profileId = sourceId;
		sqlEditor.setText(sqlDraftsBySource.getOrDefault(sourceId, ""));
	}

	/**
	 * 先合并已保存的数据源，再刷新仍然有效的选中节点。
	 */
	private void refreshSelectedSource() {
		TreePath selection = objectTree.getSelectionPath();
		refreshDataSourceNodes();
		if (Objects.isNull(selection)) {
			return;
		}
		var selectedNode = (DefaultMutableTreeNode) selection.getLastPathComponent();
		if (selectedNode.getRoot() != objectRoot) {
			status.setText(message("data.source.changed"));
			return;
		}
		NoSqlConnectionProfile noSql = WorkbenchNoSqlNavigator.findSourceProfile(selectedNode);
		if (Objects.nonNull(noSql)) {
			workbenchNoSqlNavigator.refreshNoSqlNode(selectedNode, noSql);
			return;
		}
		JdbcConnectionProfile selected = selectedConnectionProfile();
		if (Objects.nonNull(selected)) {
			if (!selected.equals(currentConnectionProfile())) {
				fillConnectionProfile(selected, true);
			}
			refreshCurrentJdbcSource();
		}
	}

	/**
	 * 加载当前选中数据源的表及视图。
	 */
	private void refreshCurrentJdbcSource() {
		runDatabaseOperation(message("browse.tables"), connection -> JdbcTableMetadata.listTables(connection.connection().getMetaData(),
			connection.connection().getCatalog(), null), this::showDatabaseObjects);
	}

	/**
	 * 列出统一数据源入口可创建的关系库或非关系库。
	 *
	 * @param anchor 菜单所在的新增按钮
	 */
	private void showAddSourceMenu(JButton anchor) {
		JPopupMenu menu = new JPopupMenu();
		for (DbType type : DbType.values()) {
			String title = type == DbType.SqlServer ? "SQL Server" : type.name();
			addDatabaseMenuItem(menu, title, () -> editConnectionSettings(type, null));
		}
		for (NoSqlSourceType type : NoSqlSourceType.values()) {
			addDatabaseMenuItem(menu, noSqlTitle(type), () -> editNoSqlSource(type, null, null));
		}
		menu.show(anchor, 0, anchor.getHeight());
	}

	/**
	 * 按选中的数据源类型打开连接设置。
	 */
	private void editSelectedDataSource() {
		var profile = selectedNoSqlProfile();
		if (Objects.nonNull(profile)) {
			editNoSqlSource(profile.type(), profile, profile);
		} else {
			JdbcConnectionProfile selected = selectedConnectionProfile();
			if (Objects.nonNull(selected) && !selected.equals(currentConnectionProfile())) {
				fillConnectionProfile(selected, true);
			}
			editConnectionSettings(null, null);
		}
	}

	/**
	 * 保存连接后刷新统一数据源列表。
	 *
	 * @param type     数据库协议
	 * @param initial 用于预填表单的配置，空白新增时为空
	 * @param expected 已保存的配置快照，新增和迁移时为空
	 */
	private void editNoSqlSource(NoSqlSourceType type, NoSqlConnectionProfile initial, NoSqlConnectionProfile expected) {
		var profile = WorkbenchNoSqlSourceEditor.editNoSqlSource(this, type, initial,
			incoming -> workbenchConnectionProfiles.replaceImportedNoSqlProfile(expected, incoming));
		refreshDataSourceNodes();
		if (Objects.nonNull(profile)) {
			status.setText(message("saved") + profile.name());
		}
	}

	/**
	 * 校验所选数据源仍是最新保存的版本，再把选中的对象交给非 JDBC 控制台。
	 *
	 * @param profile 选中对象所属的数据源
	 */
	private void openSelectedNoSqlConsole(NoSqlConnectionProfile profile) {
		// 配置可能已在后台重载，树节点仍在等待 EDT 刷新。
		if (!workbenchConnectionProfiles.listNoSqlProfiles().contains(profile)) {
			refreshDataSourceNodes();
			status.setText(message("data.source.changed"));
			return;
		}
		if (objectTree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode node) {
			String secret = WorkbenchNoSqlSourceEditor.readNoSqlSecret(profile);
			String objectName = node.getUserObject() instanceof WorkbenchNoSqlNavigator.NoSqlObjectName object ? object.name() : null;
			String selectedName = Objects.requireNonNullElse(objectName, "");
			// 主节点或数据库位于数据源下一层，叶对象从父节点取得归属。
			Object group = switch (node.getLevel()) {
				case 2 -> node.getAllowsChildren() ? node.getUserObject() : null;
				case 3 -> Objects.isNull(objectName) ? null : node.getParent().toString();
				default -> null;
			};
			String groupName = group instanceof String name ? name : "";
			switch (profile.type()) {
				case REDIS -> redisPanel.openSourceProfile(profile, secret, groupName, objectName);
				case MONGODB -> mongoPanel.openSourceProfile(profile, secret, groupName, selectedName);
				case ELASTICSEARCH -> elasticsearchPanel.openSourceProfile(profile, secret, selectedName);
			}
			openNoSqlProfile = profile;
			consoleLayout.show(consoleWorkspace, profile.type().name());
			consoleWorkspace.setVisible(true);
		}
	}

	/**
	 * @return 选中的非 JDBC 数据源，其他节点返回空
	 */
	private NoSqlConnectionProfile selectedNoSqlProfile() {
		if (objectTree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode node) {
			return WorkbenchNoSqlNavigator.findSourceProfile(node);
		}
		return null;
	}

	/**
	 * 使用各数据库的正式名称显示协议。
	 *
	 * @param type 数据库类型
	 * @return 可展示名称
	 */
	private String noSqlTitle(NoSqlSourceType type) {
		return switch (type) {
			case REDIS -> "Redis";
			case MONGODB -> "MongoDB";
			case ELASTICSEARCH -> "Elasticsearch";
		};
	}

	/**
	 * 在更多菜单提供当前数据源和表适用的操作，窄窗也能访问。
	 */
	private void showDatabaseActions(JButton anchor) {
		JPopupMenu menu = new JPopupMenu();
		addDatabaseMenuItem(menu, message("connection.profiles"), this::showProfileActions);
		JdbcConnectionProfile jdbc = selectedConnectionProfile();
		if (Objects.nonNull(jdbc) || Objects.nonNull(selectedNoSqlProfile())) {
			menu.addSeparator();
			addDatabaseMenuItem(menu, message("connection.settings"), this::editSelectedDataSource);
			addDatabaseMenuItem(menu, message("refresh.database.objects"), this::refreshSelectedSource);
			addDatabaseMenuItem(menu, message("query.console"), this::openSelectedQueryConsole);
			if (Objects.nonNull(jdbc)) {
				addDatabaseMenuItem(menu, message("test.connection"), this::testCurrentJdbcConnection);
			}
		}
		if (Objects.nonNull(selectedJdbcTable())) {
			menu.addSeparator();
			addTableMenuItems(menu);
		}
		menu.show(anchor, 0, anchor.getHeight());
	}

	/**
	 * 测试当前表单连接，只读取数据库产品名，无须保存表单。
	 */
	private void testCurrentJdbcConnection() {
		runDatabaseOperation(message("test.connection"), connection ->
				connection.connection().getMetaData().getDatabaseProductName(),
			product -> status.setText(message("connected.to") + product));
	}

	/**
	 * 鼠标触发菜单时先定位实际点击的节点，使后续动作使用正确的数据源。
	 *
	 * @param event 鼠标按下或释放事件
	 */
	private void openDatabaseObjectPopup(MouseEvent event) {
		if (!event.isPopupTrigger()) {
			return;
		}
		TreePath path = objectTree.getPathForLocation(event.getX(), event.getY());
		if (Objects.nonNull(path)) {
			objectTree.setSelectionPath(path);
			showDatabaseObjectMenu(event.getX(), event.getY());
		}
	}

	/**
	 * 按选中对象提供适用的操作，并将菜单定位在树内。
	 *
	 * @param x 树内横坐标
	 * @param y 树内纵坐标
	 */
	private void showDatabaseObjectMenu(int x, int y) {
		var menu = new JPopupMenu();
		if (objectTree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode node
			&& (node.getUserObject() instanceof JdbcConnectionProfile || node.getUserObject() instanceof NoSqlConnectionProfile)) {
			addDatabaseMenuItem(menu, message("connection.settings"), this::editSelectedDataSource);
			addDatabaseMenuItem(menu, message("refresh.database.objects"), this::refreshSelectedSource);
			addDatabaseMenuItem(menu, message("query.console"), this::openSelectedQueryConsole);
			addDatabaseMenuItem(menu, message("remove.data.source"), () -> removeDataSource(node.getUserObject()));
		} else if (objectTree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode node
			&& node.getUserObject() instanceof WorkbenchNoSqlNavigator.RedisScanContinuation) {
			addDatabaseMenuItem(menu, message("next.page"), () -> workbenchNoSqlNavigator.loadNextRedisPage(node));
		} else if (Objects.nonNull(selectedJdbcTable())) {
			addTableMenuItems(menu);
		} else if (Objects.nonNull(selectedNoSqlProfile())) {
			addDatabaseMenuItem(menu, message("refresh.database.objects"), this::refreshSelectedSource);
			addDatabaseMenuItem(menu, message("query.console"), this::openSelectedQueryConsole);
		}
		if (menu.getComponentCount() > 0) {
			menu.show(objectTree, x, y);
		}
	}

	/**
	 * 确认后移除树中的保存配置和该配置在本机的凭据。
	 *
	 * @param snapshot 用户打开菜单时选中的数据源配置
	 */
	private void removeDataSource(Object snapshot) {
		String name = snapshot instanceof JdbcConnectionProfile jdbc ? jdbc.name() : ((NoSqlConnectionProfile) snapshot).name();
		int choice = JOptionPane.showConfirmDialog(this, message("confirm.remove.data.source") + "\n" + name,
			message("remove.data.source"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		if (choice != JOptionPane.OK_OPTION) {
			return;
		}
		boolean removed;
		if (snapshot instanceof JdbcConnectionProfile jdbc) {
			removed = workbenchConnectionProfiles.removeProfile(jdbc);
			if (removed) {
				PasswordSafe.getInstance().set(credentialAttributes(jdbc), null);
				if (jdbc.id().equals(profileId)) {
					switchSqlDraft(UUID.randomUUID());
					jdbcUrl.setText("");
					username.setText("");
					password.setText("");
					profileName.setText("");
					consoleWorkspace.setVisible(false);
				}
				sqlDraftsBySource.remove(jdbc.id());
			}
		} else {
			var noSql = (NoSqlConnectionProfile) snapshot;
			removed = workbenchConnectionProfiles.removeNoSqlProfile(noSql);
			if (removed) {
				WorkbenchNoSqlSourceEditor.removeLocalNoSqlSettings(noSql);
			}
		}
		refreshDataSourceNodes();
		status.setText(message(removed ? "data.source.removed" : "data.source.changed"));
	}

	/**
	 * 提供表节点的完整操作菜单。
	 */
	private void addTableMenuItems(JPopupMenu menu) {
		addDatabaseMenuItem(menu, message("show.columns.and.primary.key"), () -> readSelectedTable(this::showTableStructure));
		addDatabaseMenuItem(menu, message("browse.table.data"), this::previewSelectedTableRows);
		addDatabaseMenuItem(menu, message("show.ddl"), this::showSelectedTableDdl);
		addDatabaseMenuItem(menu, message("compare.table.structure"), this::compareSelectedTableStructure);
		if (WorkbenchDatabaseToolWindow.isJavaAvailable()) {
			addDatabaseMenuItem(menu, message("generate.mybatis"), () -> readSelectedTable(table ->
				new MybatisGeneratorMainUI(project, List.of(table))));
		}
		addDatabaseMenuItem(menu, message("import.csv"), this::importCsvToSelectedTable);
	}

	/**
	 * 向当前菜单添加可点击的工作台操作。
	 */
	private void addDatabaseMenuItem(JPopupMenu menu, String label, Runnable operation) {
		JMenuItem item = new JMenuItem(label);
		item.addActionListener(event -> operation.run());
		menu.add(item);
	}

	/**
	 * 创建使用 IDEA 主题图标、带工具提示和可访问名称的工具栏按钮。
	 */
	private JButton databaseIconButton(Icon icon, String label, Runnable operation) {
		JButton button = new JButton(icon);
		button.setToolTipText(label);
		button.getAccessibleContext().setAccessibleName(label);
		button.addActionListener(event -> operation.run());
		return button;
	}

	/**
	 * 打开连接配置菜单，加载和导入只填充表单，实际连接由用户操作发起。
	 */
	private void showProfileActions() {
		JdbcConnectionProfile expected = selectedConnectionProfile();
		String[] actions = {message("new"), message("save"), message("load"), message("import"), message("export"), message("migrate.jetbrains.data.sources")};
		int choice = JOptionPane.showOptionDialog(this, message("select.a.connection.profile.action"), message("connection.profiles"),
			JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, actions, actions[1]);
		try {
			switch (choice) {
				case 0 -> {
					switchSqlDraft(UUID.randomUUID());
					profileName.setText("");
					jdbcUrl.setText("");
					username.setText("");
					password.setText("");
					status.setText(message("new.connection.profile"));
				}
				case 1 -> saveConnectionProfile(expected);
				case 2 -> loadSavedConnectionProfile();
				case 3 -> transferConnectionProfile(true);
				case 4 -> transferConnectionProfile(false);
				case 5 -> importJetBrainsConnectionProfiles();
				default -> {
					// 对话框已取消，无需处理。
				}
			}
		} catch (IOException | IllegalArgumentException failure) {
			log.warn("连接配置操作失败，阶段={}，配置 ID={}", actions[choice], profileId, failure);
			status.setText(failure.getMessage());
		}
	}

	/**
	 * 收集当前表单中的可交换设置，密码留在专用字段。
	 *
	 * @return 连接配置快照
	 */
	private JdbcConnectionProfile currentConnectionProfile() {
		DbType type = databaseType.getItemAt(databaseType.getSelectedIndex());
		return new JdbcConnectionProfile(profileId, profileName.getText().strip(), type, jdbcUrl.getText().strip(),
			username.getText(), driverCoordinates.getText().strip(), driverClass.getText().strip(),
			repositoryUrl.getText().strip());
	}

	/**
	 * 先验证并按打开对话框时的快照保存共享配置，再更新本机 PasswordSafe。
	 * 同步已替换或删除该配置时刷新列表，避免覆盖新设置或凭据。
	 *
	 * @param expected 打开设置时的配置快照，新建或尚未保存时为空
	 */
	private void saveConnectionProfile(JdbcConnectionProfile expected) {
		JdbcConnectionProfile profile = currentConnectionProfile();
		profile.validateShareableProfile(WorkbenchBundle::message);
		if (!workbenchConnectionProfiles.replaceImportedProfile(expected, profile)) {
			refreshDataSourceNodes();
			status.setText(message("data.source.changed"));
			return;
		}
		char[] secret = password.getPassword();
		try {
			PasswordSafe.getInstance().set(credentialAttributes(profile),
				secret.length == 0 ? null : new Credentials(profile.username(), new String(secret)));
		} finally {
			Arrays.fill(secret, '\0');
		}
		refreshDataSourceNodes();
		var source = findJdbcSourceNode(profile.id());
		if (Objects.nonNull(source)) {
			objectTree.setSelectionPath(new TreePath(source.getPath()));
		}
		status.setText(message("saved") + profile.name());
	}

	/**
	 * 展示当前已保存的连接，状态同步后的数据会在下次打开选择器时生效。
	 */
	private void loadSavedConnectionProfile() {
		List<JdbcConnectionProfile> profiles = workbenchConnectionProfiles.listProfiles();
		if (profiles.isEmpty()) {
			status.setText(message("no.saved.connections"));
			return;
		}
		int index = chooseConnectionProfileIndex(profiles.stream().map(JdbcConnectionProfile::name).toList());
		if (index >= 0) {
			fillConnectionProfile(profiles.get(index), true);
		}
	}

	/**
	 * 从用户选中的 JetBrains 数据源文件迁移支持的连接。选择一条配置后进入对应的设置表单。
	 *
	 * @throws IOException 文件不可读、过大或不符合数据源结构
	 */
	private void importJetBrainsConnectionProfiles() throws IOException {
		JFileChooser chooser = new JFileChooser();
		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		try (var input = Files.newInputStream(chooser.getSelectedFile().toPath())) {
			byte[] fileContent = input.readNBytes(1_048_577);
			if (fileContent.length > 1_048_576) {
				throw new IOException(message("jetbrains.data.source.file.exceeds.1.mib"));
			}
			var migration = WorkbenchJetBrainsDataSources.readDataSources(fileContent);
			if (migration.rejected() > 0) {
				JOptionPane.showMessageDialog(this, message("jetbrains.migration.skipped.invalid") + " " + migration.rejected(),
					message("migrate.jetbrains.data.sources"), JOptionPane.WARNING_MESSAGE);
			}
			List<String> names = new ArrayList<>(migration.profiles().size() + migration.noSqlProfiles().size());
			migration.profiles().forEach(profile -> names.add(profile.name()));
			migration.noSqlProfiles().forEach(profile -> names.add(profile.name()));
			if (names.isEmpty()) {
				status.setText(message("the.file.has.no.supported.connections"));
				return;
			}
			int index = chooseConnectionProfileIndex(names);
			if (index >= migration.profiles().size()) {
				importNoSqlConnection(migration.noSqlProfiles().get(index - migration.profiles().size()));
			} else if (index >= 0) {
				editConnectionSettings(null, migration.profiles().get(index));
			}
		}
	}

	/**
	 * 显示连接名称列表，并使用序号区分同名数据源。
	 *
	 * @param names 非空的候选连接名称
	 * @return 用户选择的索引，取消时返回 -1
	 */
	private int chooseConnectionProfileIndex(List<String> names) {
		String[] labels = new String[names.size()];
		for (int index = 0; index < names.size(); index++) {
			labels[index] = (index + 1) + ". " + names.get(index);
		}
		Object selection = JOptionPane.showInputDialog(this, message("select.a.connection.to.load"), message("load.connection"),
			JOptionPane.PLAIN_MESSAGE, null, labels, labels[0]);
		return Arrays.asList(labels).indexOf(selection);
	}

	/**
	 * 导入或导出版本化的 UTF-8 配置文件。导入展示可共享配置并确认，导出可经确认更新同 ID 的配置文件。
	 *
	 * @param importing 为 true 时导入文件，否则导出当前表单
	 * @throws IOException 无法读取或写入配置文件
	 */
	private void transferConnectionProfile(boolean importing) throws IOException {
		JFileChooser chooser = new JFileChooser();
		int choice = importing ? chooser.showOpenDialog(this) : chooser.showSaveDialog(this);
		if (choice != JFileChooser.APPROVE_OPTION) {
			return;
		}
		Path file = chooser.getSelectedFile().toPath();
		if (importing) {
			String text = readPortableProfileText(file);
			Properties properties = ConnectionProfileProperties.readProfileProperties(new StringReader(text));
			if (properties.getProperty("format", "").startsWith("mybatis-workbench-nosql/")) {
				var imported = NoSqlConnectionProfile.readProfile(new StringReader(text), WorkbenchBundle::message);
				var previous = workbenchConnectionProfiles.listNoSqlProfiles().stream()
					.filter(profile -> profile.id().equals(imported.id())).findFirst().orElse(null);
				if (!imported.equals(previous) && confirmPortableProfileUpdate(previous, imported)) {
					boolean saved = workbenchConnectionProfiles.replaceImportedNoSqlProfile(previous, imported);
					if (saved && Objects.nonNull(previous) && previous.type() == NoSqlSourceType.REDIS
						&& previous.tls() != imported.tls()) {
						WorkbenchNoSqlSourceEditor.removeLocalNoSqlSettings(previous);
					}
					refreshDataSourceNodes();
					status.setText(saved ? message("saved") + imported.name() : message("data.source.changed"));
				}
			} else {
				var imported = JdbcConnectionProfile.readProfile(new StringReader(text), WorkbenchBundle::message);
				var previous = workbenchConnectionProfiles.listProfiles().stream()
					.filter(profile -> profile.id().equals(imported.id())).findFirst().orElse(null);
				if (!imported.equals(previous) && confirmPortableProfileUpdate(previous, imported)) {
					boolean saved = workbenchConnectionProfiles.replaceImportedProfile(previous, imported);
					if (saved && Objects.nonNull(previous) && (!previous.coordinates().equals(imported.coordinates())
						|| !previous.driverClass().equals(imported.driverClass()) || !previous.repository().equals(imported.repository()))) {
						// JDBC 驱动来源改变后，不把旧驱动使用的凭据交给新驱动。
						PasswordSafe.getInstance().set(credentialAttributes(previous), null);
					}
					refreshDataSourceNodes();
					status.setText(saved ? message("saved") + imported.name() : message("data.source.changed"));
				}
			}
		} else {
			StringWriter content = new StringWriter();
			NoSqlConnectionProfile noSql = selectedNoSqlProfile();
			if (Objects.nonNull(noSql)) {
				noSql.writeProfile(content, WorkbenchBundle::message);
			} else {
				JdbcConnectionProfile jdbc = selectedConnectionProfile();
				(Objects.nonNull(jdbc) ? jdbc : currentConnectionProfile()).writeProfile(content, WorkbenchBundle::message);
			}
			if (writePortableProfileFile(file, content.toString(), () ->
				JOptionPane.showConfirmDialog(this, message("connection.export.confirm.replace"), message("connection.exported"),
					JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION)) {
				status.setText(message("connection.exported"));
			}
		}
	}

	/** 可移植连接配置的 UTF-8 字节上限，导入和导出共用。 */
	private static final int PORTABLE_PROFILE_MAX_BYTES = 64 * 1024;

	/**
	 * 读取限定大小的 UTF-8 配置，非法编码按读取失败处理。
	 *
	 * @param file 要读取的文件
	 * @return UTF-8 配置正文
	 * @throws IOException 文件不可读或超过大小限制
	 */
	private static String readPortableProfileText(Path file) throws IOException {
		try (var input = Files.newInputStream(file)) {
			byte[] data = input.readNBytes(PORTABLE_PROFILE_MAX_BYTES + 1);
			if (data.length > PORTABLE_PROFILE_MAX_BYTES) {
				throw new IOException(message("connection.profile.exceeds.64.kib"));
			}
			return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data)).toString();
		}
	}

	/**
	 * 写入可移植配置。目标已存在时，仅在用户确认更新同一连接后原子替换。
	 *
	 * @param file 用户选中的导出路径
	 * @param content 已验证的可共享配置正文
	 * @param confirmReplacement 确认更新已有文件的回调，仅在同 ID 文件有变化时调用
	 * @return 是否完成写入，取消确认时为 false
	 * @throws IOException 文件不可写、身份不一致或发现文件已被修改
	 */
	static boolean writePortableProfileFile(Path file, String content, BooleanSupplier confirmReplacement) throws IOException {
		byte[] encoded = content.getBytes(StandardCharsets.UTF_8);
		if (encoded.length > PORTABLE_PROFILE_MAX_BYTES) {
			throw new IOException(message("connection.profile.exceeds.64.kib"));
		}
		try {
			Files.write(file, encoded, StandardOpenOption.CREATE_NEW);
			return true;
		} catch (FileAlreadyExistsException existing) {
			if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException(message("connection.export.file.different"), existing);
			}
		}
		String previous = readPortableProfileText(file);
		Properties oldFields = ConnectionProfileProperties.readProfileProperties(new StringReader(previous));
		Properties newFields = ConnectionProfileProperties.readProfileProperties(new StringReader(content));
		if (!Objects.equals(newFields.getProperty("format"), oldFields.getProperty("format"))
			|| !Objects.equals(newFields.getProperty("id"), oldFields.getProperty("id"))) {
			throw new IOException(message("connection.export.file.different"));
		}
		if (previous.equals(content)) {
			return true;
		}
		if (!confirmReplacement.getAsBoolean()) {
			return false;
		}
		Path staged = Files.createTempFile(file.toAbsolutePath().getParent(), ".workbench-", ".tmp");
		try {
			Files.write(staged, encoded);
			// 预览确认与落盘之间，Git 操作或编辑器可能已更新文件。
			if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !previous.equals(readPortableProfileText(file))) {
				throw new IOException(message("connection.export.file.changed"));
			}
			Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			return true;
		} finally {
			Files.deleteIfExists(staged);
		}
	}

	/**
	 * 确认从文件新增或更新本机数据源，并在更新时展示两个版本的可共享字段。
	 *
	 * @param saved 本机已保存的配置快照，首次导入时为空
	 * @param incoming 从文件解析的配置
	 * @return 用户是否确认保存配置
	 * @throws IOException 无法序列化预览
	 */
	private boolean confirmPortableProfileUpdate(Object saved, Object incoming) throws IOException {
		StringWriter content = new StringWriter();
		for (Object profile : Objects.isNull(saved) ? List.of(incoming) : List.of(saved, incoming)) {
			if (profile instanceof JdbcConnectionProfile jdbc) {
				jdbc.writeProfile(content, WorkbenchBundle::message);
			} else if (profile instanceof NoSqlConnectionProfile noSql) {
				noSql.writeProfile(content, WorkbenchBundle::message);
			}
			content.write("\n");
		}
		JTextArea preview = new JTextArea(content.toString(), 20, 62);
		preview.setEditable(false);
		preview.setCaretPosition(0);
		return JOptionPane.showConfirmDialog(this, new JBScrollPane(preview), message(Objects.isNull(saved) ? "new.connection" : "connection.confirm.file.update"),
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION;
	}

	/**
	 * 将可移植文件导入新数据源，避免覆盖本机配置或自动绑定原有密码。
	 *
	 * @param profile 文件中读到的可共享配置
	 */
	private void importNoSqlConnection(NoSqlConnectionProfile profile) {
		var imported = new NoSqlConnectionProfile(UUID.randomUUID(), profile.name(), profile.type(), profile.endpoint(),
			profile.username(), profile.authenticationDatabase(), profile.cluster(), profile.tls(), profile.logicalDatabase());
		editNoSqlSource(imported.type(), imported, null);
	}

	/**
	 * 将配置填入表单。外部文件导入始终清空密码字段。
	 *
	 * @param profile           要打开的连接配置
	 * @param useStoredPassword 是否取回本机 PasswordSafe 的密码
	 */
	private void fillConnectionProfile(JdbcConnectionProfile profile, boolean useStoredPassword) {
		switchSqlDraft(profile.id());
		profileName.setText(profile.name());
		databaseType.setSelectedItem(profile.dbType());
		jdbcUrl.setText(profile.url());
		username.setText(profile.username());
		driverCoordinates.setText(profile.coordinates());
		driverClass.setText(profile.driverClass());
		repositoryUrl.setText(profile.repository());
		Credentials credentials = useStoredPassword ? PasswordSafe.getInstance().get(credentialAttributes(profile)) : null;
		password.setText(Objects.isNull(credentials) ? "" : credentials.getPasswordAsString());
		browsedTables = tablesBySource.getOrDefault(profile.id(), List.of());
		status.setText(message("opened") + profile.name());
	}

	/**
	 * 按配置及连接身份绑定密码，替换地址或账号后不会自动沿用旧密码。
	 *
	 * @param profile 当前连接配置
	 * @return 仅用于本机 PasswordSafe 的凭据键
	 */
	private CredentialAttributes credentialAttributes(JdbcConnectionProfile profile) {
		String identity = profile.id() + "\n" + profile.url() + "\n" + profile.username();
		UUID key = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
		return new CredentialAttributes("MyBatis Workbench JDBC " + key, profile.username());
	}

	/**
	 * 从配置同步变动的数据源节点，保留其他连接的树节点和展开状态。
	 * 当前 JDBC 数据源被替换或移除时，让已经提交的后台结果失效。
	 */
	private void refreshDataSourceNodes() {
		var jdbcProfiles = workbenchConnectionProfiles.listProfiles();
		var noSqlProfiles = workbenchConnectionProfiles.listNoSqlProfiles();
		if (Objects.nonNull(openNoSqlProfile) && !noSqlProfiles.contains(openNoSqlProfile)) {
			openNoSqlProfile = null;
			// 当前显示 SQL 时，非 JDBC 配置的变更不会收起 SQL 工作区。
			if (!queryWorkspace.isVisible()) {
				consoleWorkspace.setVisible(false);
				status.setText(message("data.source.changed"));
			}
		}
		for (int index = objectRoot.getChildCount() - 1; index >= 0; index--) {
			var node = (DefaultMutableTreeNode) objectRoot.getChildAt(index);
			Object profile = node.getUserObject();
			if (profile instanceof JdbcConnectionProfile jdbc && !jdbcProfiles.contains(jdbc)) {
				tablesBySource.remove(jdbc.id());
				if (jdbc.id().equals(profileId)) {
					// 远端可保留连接 ID 并修改地址，单靠草稿切换无法识别旧任务。
					connectionRevision++;
					browsedTables = List.of();
					sqlCompletionTable = null;
					sqlCompletionColumns = List.of();
					// 新配置需要通过数据源树重新打开并按当前连接身份读取本机凭据。
					password.setText("");
					if (queryWorkspace.isVisible()) {
						consoleWorkspace.setVisible(false);
					}
					status.setText(message("data.source.changed"));
				}
				objectModel.removeNodeFromParent(node);
			} else if (profile instanceof NoSqlConnectionProfile noSql && !noSqlProfiles.contains(noSql)) {
				objectModel.removeNodeFromParent(node);
			}
		}
		for (JdbcConnectionProfile profile : jdbcProfiles) {
			if (Objects.isNull(findJdbcSourceNode(profile.id()))) {
				var source = new DefaultMutableTreeNode(profile);
				// 未加载的源需要一个占位子节点，才能显示展开入口。
				source.add(new DefaultMutableTreeNode(message("loading.source.objects"), false));
				objectModel.insertNodeInto(source, objectRoot, objectRoot.getChildCount());
			}
		}
		for (NoSqlConnectionProfile profile : noSqlProfiles) {
			boolean exists = Collections.list(objectRoot.children()).stream()
				.anyMatch(node -> profile.equals(((DefaultMutableTreeNode) node).getUserObject()));
			if (!exists) {
				objectModel.insertNodeInto(workbenchNoSqlNavigator.createSourceNode(profile), objectRoot, objectRoot.getChildCount());
			}
		}
	}

	/**
	 * 按配置标识查找 JDBC 数据源节点。
	 *
	 * @param sourceId 数据源配置标识
	 * @return 当前树中的对应节点，未保存时为空
	 */
	private DefaultMutableTreeNode findJdbcSourceNode(UUID sourceId) {
		for (int index = 0; index < objectRoot.getChildCount(); index++) {
			var node = (DefaultMutableTreeNode) objectRoot.getChildAt(index);
			if (node.getUserObject() instanceof JdbcConnectionProfile profile && profile.id().equals(sourceId)) {
				return node;
			}
		}
		return null;
	}

	/**
	 * 按目录和模式更新当前已保存连接的对象，保留其他数据源的内容。
	 *
	 * @param tables 当前连接的表和视图清单
	 */
	private void showDatabaseObjects(List<JdbcTable> tables) {
		browsedTables = tables;
		sqlCompletionTable = null;
		sqlCompletionColumns = List.of();
		DefaultMutableTreeNode targetSource = findJdbcSourceNode(profileId);
		if (Objects.isNull(targetSource)) {
			return;
		}
		tablesBySource.put(profileId, tables);
		targetSource.removeAllChildren();
		Map<List<String>, DefaultMutableTreeNode> schemas = new LinkedHashMap<>();
		for (JdbcTable table : tables) {
			String catalog = Objects.toString(table.catalog(), "");
			String schema = Objects.toString(table.schema(), "");
			DefaultMutableTreeNode group = schemas.computeIfAbsent(List.of(catalog, schema), key -> {
				String label = String.join(".", key.stream().filter(name -> !name.isEmpty()).toList());
				DefaultMutableTreeNode node = new DefaultMutableTreeNode(label.isEmpty() ? message("default.schema") : label);
				targetSource.add(node);
				return node;
			});
			var tableNode = new DefaultMutableTreeNode(table);
			tableNode.add(new DefaultMutableTreeNode(message("loading.source.objects"), false));
			group.add(tableNode);
		}
		objectModel.nodeStructureChanged(targetSource);
		TreePath sourcePath = new TreePath(targetSource.getPath());
		objectTree.expandPath(sourcePath);
	}

	/**
	 * 将后台读取的列添加到原表节点，并在主键列旁展示其约束顺序。
	 *
	 * @param node  发起加载的表节点
	 * @param table 从同一连接读取的表元数据
	 */
	private void showJdbcColumnNodes(DefaultMutableTreeNode node, GenerationTable table) {
		if (node.getRoot() != objectRoot) {
			return;
		}
		node.removeAllChildren();
		for (var column : table.tableInfo().getColumnInfos()) {
			String label = column.getName() + "  " + Objects.toString(column.getTypeName(), "");
			if (column.getKeySeq() > 0) {
				label += "  (" + message("primary.key.position") + " " + column.getKeySeq() + ")";
			}
			node.add(new DefaultMutableTreeNode(label, false));
		}
		objectModel.nodeStructureChanged(node);
		objectTree.expandPath(new TreePath(node.getPath()));
	}

	/**
	 * 从现有数据库文件生成地址。H2 的驱动需要文件基名。SQLite 遇到含问号的文件路径时使用编码后的文件 URI。
	 * 打开时预选地址对应的现有本地文件。取消选择或文件不符合当前类型时保留表单中的地址。
	 *
	 * @see <a href="https://h2database.com/html/features.html#database_file_layout">H2 数据库文件布局</a>
	 */
	private void chooseJdbcDatabaseFile() {
		DbType type = databaseType.getItemAt(databaseType.getSelectedIndex());
		FileChooserDescriptor descriptor = new FileChooserDescriptor(true, false, false, false, false, false)
			.withTitle(message("choose.database.file"));
		if (type == DbType.H2) {
			descriptor.withFileFilter(file -> file.getName().endsWith(".mv.db"));
		}
		String current = jdbcUrl.getText().strip();
		Path currentFile = findCurrentJdbcDatabasePath(type, current);
		VirtualFile initialFile = Objects.nonNull(currentFile) && Files.isRegularFile(currentFile)
			? LocalFileSystem.getInstance().findFileByNioFile(currentFile) : null;
		VirtualFile databaseFile = FileChooser.chooseFile(descriptor, jdbcUrl, project, initialFile);
		if (Objects.isNull(databaseFile)) {
			return;
		}
		Path selected = databaseFile.toNioPath().toAbsolutePath();
		String path = selected.toString();
		if (!Files.isRegularFile(selected) || (type == DbType.H2
			&& (!path.endsWith(".mv.db") || path.contains(";")))) {
			JOptionPane.showMessageDialog(jdbcUrl,
				message(type == DbType.H2 ? "choose.h2.database.file" : "choose.existing.database.file"),
				message("choose.database.file"), JOptionPane.ERROR_MESSAGE);
			return;
		}
		if (type == DbType.H2) {
			int optionsStart = current.startsWith("jdbc:h2:file:") ? current.indexOf(';') : -1;
			// H2 将反斜杠与紧随的字符成对读取。只在未被转义的分号后识别设置名。
			String options = optionsStart < 0 ? "" : current.substring(optionsStart);
			if (!options.matches("(?is)(?:[^;\\\\]++|\\\\.|;)*;IFEXISTS=.*")) {
				options = ";IFEXISTS=TRUE" + options;
			}
			if (SystemInfoRt.isWindows) {
				path = path.replace('\\', '/');
			}
			jdbcUrl.setText("jdbc:h2:file:" + path.substring(0, path.length() - ".mv.db".length()) + options);
		} else if (type == DbType.SQLite) {
			int optionsStart = Objects.nonNull(currentFile) ? current.indexOf('?') : -1;
			// 保留显式打开模式。仅配置超时等参数时，仍禁止在原文件移走后创建空库。
			String options = optionsStart >= 0 ? current.substring(optionsStart + 1) : "";
			if (Arrays.stream(options.split("&")).noneMatch(option -> option.startsWith("mode="))) {
				options = options.isEmpty() ? "mode=rw" : options + "&mode=rw";
			}
			jdbcUrl.setText("jdbc:sqlite:" + selected.toUri().toASCIIString() + "?" + options);
		}
	}

	/**
	 * 解析文件选择器支持的绝对本地 JDBC 路径，忽略连接选项。
	 * 地址为空、手工输入尚未完成或使用内存及远程数据库时，交给 IDE 使用默认位置。
	 *
	 * @param type 当前表单选择的方言
	 * @param url 当前表单中的 JDBC 地址
	 * @return 本地数据库文件路径，无法解析时返回空
	 */
	private Path findCurrentJdbcDatabasePath(DbType type, String url) {
		try {
			Path file;
			if (type == DbType.H2 && url.startsWith("jdbc:h2:file:")) {
				String name = url.substring("jdbc:h2:file:".length()).split(";", 2)[0];
				file = Path.of(name + ".mv.db");
			} else if (type == DbType.SQLite && url.startsWith("jdbc:sqlite:")) {
				String location = url.substring("jdbc:sqlite:".length()).split("\\?", 2)[0];
				file = location.startsWith("file:/") ? Path.of(URI.create(location)) : Path.of(location);
			} else {
				return null;
			}
			return file.isAbsolute() ? file : null;
		} catch (IllegalArgumentException ignored) {
			// 地址仍可编辑，无法解析的本地路径不会阻断文件选择。
		}
		return null;
	}

	/**
	 * 切换方言时填入标准驱动类名，用户仍可指定其他兼容实现。
	 */
	private void setDriverPreset() {
		DbType selected = databaseType.getItemAt(databaseType.getSelectedIndex());
		driverCoordinates.setText(WorkbenchJdbcArtifacts.defaultDriverCoordinates(selected));
		driverClass.setText(WorkbenchJdbcArtifacts.defaultDriverClass(selected));
	}

	/**
	 * 创建数据库操作按钮，动作发生在工具窗口的 EDT。
	 *
	 * @param title     操作名称
	 * @param operation 按钮动作
	 * @return 绑定动作的按钮
	 */
	private JButton databaseButton(String title, Runnable operation) {
		JButton button = new JButton(title);
		button.addActionListener(event -> operation.run());
		return button;
	}

	/**
	 * 载入已选表快照，在 EDT 中交给调用者展示或生成文件。
	 *
	 * @param callback 表结构加载成功的回调
	 */
	private void readSelectedTable(Consumer<GenerationTable> callback) {
		JdbcTable selected = selectedJdbcTable();
		if (Objects.isNull(selected)) {
			status.setText(message("browse.and.select.a.table.first"));
			return;
		}
		DbType type = databaseType.getItemAt(databaseType.getSelectedIndex());
		runDatabaseOperation(message("read.table.structure"), connection ->
			JdbcTableMetadata.readGenerationTable(connection.connection().getMetaData(), type, selected), callback);
	}

	/**
	 * 获取当前连接对象树里的表节点。
	 *
	 * @return 已选表，未选中表时为空
	 */
	private JdbcTable selectedJdbcTable() {
		JdbcConnectionProfile source = selectedConnectionProfile();
		if (Objects.nonNull(source) && source.id().equals(profileId)
			&& objectTree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode node
			&& node.getUserObject() instanceof JdbcTable table) {
			return table;
		}
		return null;
	}

	/**
	 * 选择文件，并在确认后通过独占 JDBC 连接向选定表追加。
	 */
	private void importCsvToSelectedTable() {
		JdbcConnectionProfile source = selectedConnectionProfile();
		JdbcTable target = selectedJdbcTable();
		if (Objects.isNull(target)) {
			status.setText(message("browse.and.select.a.target.table.first"));
			return;
		}
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle(message("select.a.utf.8.csv.with.a.header"));
		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		Path file = chooser.getSelectedFile().toPath();
		String qualified = String.join(".", Stream.of(target.catalog(), target.schema(), target.name())
			.filter(Objects::nonNull).filter(name -> !name.isEmpty()).toList());
		int choice = JOptionPane.showConfirmDialog(this,
			message("append.data.from") + file.getFileName() + message("to") + qualified + "？",
			message("confirm.write.to.target.table"), JOptionPane.OK_CANCEL_OPTION);
		if (choice != JOptionPane.OK_OPTION) {
			return;
		}
		if (!isCurrentJdbcSource(source) || !target.equals(selectedJdbcTable())) {
			return;
		}
		DbType type = source.dbType();
		runDatabaseOperation(message("import.csv"), connection ->
				JdbcCsvImport.importCsvRows(connection.connection(), type, target, file, WorkbenchBundle::message),
			count -> status.setText(message("imported") + count + message("rows.into") + qualified));
	}

	/**
	 * 选择参考数据源和参考表，然后与树中当前的目标表比较。
	 */
	private void compareSelectedTableStructure() {
		JdbcTable target = selectedJdbcTable();
		if (Objects.isNull(target)) {
			status.setText(message("select.a.table.to.receive.the.schema.changes"));
			return;
		}
		DbType type = databaseType.getItemAt(databaseType.getSelectedIndex());
		List<JdbcConnectionProfile> others = workbenchConnectionProfiles.listProfiles().stream()
			.filter(profile -> profile.dbType() == type && !profile.id().equals(profileId)).toList();
		if (others.isEmpty()) {
			compareWithReferenceTable(target, type, null, browsedTables);
			return;
		}
		String[] options = new String[others.size() + 1];
		options[0] = message("comparison.current.connection");
		for (int index = 0; index < others.size(); index++) {
			options[index + 1] = (index + 1) + ". " + others.get(index).name();
		}
		Object selected = JOptionPane.showInputDialog(this, message("comparison.choose.source"),
			message("compare.table.structure"), JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
		int index = Arrays.asList(options).indexOf(selected);
		if (index == 0) {
			compareWithReferenceTable(target, type, null, browsedTables);
		} else if (index > 0) {
			JdbcConnectionProfile reference = others.get(index - 1);
			runDatabaseOperation(message("comparison.loading.reference"), database -> {
				try (JdbcDriverConnection source = openComparisonSource(reference)) {
					var connection = source.connection();
					return JdbcTableMetadata.listTables(connection.getMetaData(), connection.getCatalog(), null);
				}
			}, tables -> compareWithReferenceTable(target, type, reference, tables));
		}
	}

	/**
	 * 列出可用参考表并比较选定对象。同一连接下排除目标表本身。
	 *
	 * @param target    对象树所选目标表
	 * @param type      两边共用的方言
	 * @param reference 另一数据源配置，使用当前连接时为空
	 * @param tables    参考连接中可见的表
	 */
	private void compareWithReferenceTable(JdbcTable target, DbType type, JdbcConnectionProfile reference,
										   List<JdbcTable> tables) {
		List<JdbcTable> candidates = tables.stream()
			.filter(table -> Objects.nonNull(reference) || !table.equals(target)).toList();
		if (candidates.isEmpty()) {
			status.setText(message("select.another.table.from.this.connection.as.the.reference"));
			return;
		}
		String[] labels = new String[candidates.size()];
		for (int index = 0; index < candidates.size(); index++) {
			JdbcTable table = candidates.get(index);
			String name = String.join(".", Stream.of(table.catalog(), table.schema(), table.name())
				.filter(Objects::nonNull).filter(part -> !part.isEmpty()).toList());
			labels[index] = (index + 1) + ". " + name;
		}
		Object selected = JOptionPane.showInputDialog(this,
			message("target.table") + target.name() + message("select.a.reference.table"),
			message("compare.table.structure"), JOptionPane.PLAIN_MESSAGE, null, labels, labels[0]);
		int index = Arrays.asList(labels).indexOf(selected);
		if (index < 0) {
			return;
		}
		JdbcTable source = candidates.get(index);
		runDatabaseOperation(message("compare.table.structure"), database -> {
			if (Objects.isNull(reference)) {
				return JdbcTableComparer.compareTableStructure(database.connection(), source,
					database.connection(), target, type, WorkbenchBundle::message);
			}
			try (JdbcDriverConnection referenceConnection = openComparisonSource(reference)) {
				return JdbcTableComparer.compareTableStructure(referenceConnection.connection(), source,
					database.connection(), target, type, WorkbenchBundle::message);
			}
		}, differences -> showComparisonResult(source, target, reference, differences));
	}

	/**
	 * 在后台打开独立的参考连接，账号密码从该配置对应的 PasswordSafe 读取。
	 *
	 * @param profile 已保存的参考连接
	 * @return 当前任务需要关闭的连接
	 * @throws Exception 驱动解析或数据库连接失败
	 */
	private JdbcDriverConnection openComparisonSource(JdbcConnectionProfile profile) throws Exception {
		Properties login = new Properties();
		if (!profile.username().isBlank()) {
			login.setProperty("user", profile.username());
		}
		Credentials credentials = PasswordSafe.getInstance().get(credentialAttributes(profile));
		if (Objects.nonNull(credentials) && Objects.nonNull(credentials.getPasswordAsString())) {
			login.setProperty("password", credentials.getPasswordAsString());
		}
		try {
			List<Path> jars = WorkbenchJdbcArtifacts.resolveDriverFiles(profile.coordinates(), profile.repository());
			return new JdbcDriverConnection(jars, profile.driverClass(), profile.url(), login);
		} finally {
			login.clear();
		}
	}

	/**
	 * 展示两张表的目录差异及需要用户审核的迁移草案。
	 *
	 * @param source      参考表
	 * @param target      目标表
	 * @param reference   跨连接比较的参考数据源，可以为空
	 * @param differences JDBC 结构差异
	 */
	private void showComparisonResult(JdbcTable source, JdbcTable target, JdbcConnectionProfile reference,
									  JdbcTableDifferences differences) {
		String text = message("comparison.scope.columns.and.primary.keys.n.n")
			+ (differences.changes().isEmpty() ? message("no.differences.in.this.scope") : String.join("\n", differences.changes()));
		String draft = String.join("\n", differences.migrationDraft());
		if (!draft.isEmpty()) {
			text += message("n.nmigration.draft.for.review.add.nullable.columns.n") + draft;
		}
		JTextArea comparison = new JTextArea(text, 18, 72);
		comparison.setEditable(false);
		comparison.setCaretPosition(0);
		JPanel result = new JPanel(new BorderLayout(0, 4));
		result.add(new JBScrollPane(comparison), BorderLayout.CENTER);
		if (!draft.isEmpty()) {
			JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
			actions.add(databaseButton(message("compare.copy.migration.sql"),
				() -> CopyPasteManager.getInstance().setContents(new StringSelection(draft))));
			result.add(actions, BorderLayout.SOUTH);
		}
		String sourceName = Objects.isNull(reference) ? source.name() : reference.name() + " / " + source.name();
		JOptionPane.showMessageDialog(this, result, message("reference") + sourceName + message("target") + target.name(),
			JOptionPane.PLAIN_MESSAGE);
	}

	/**
	 * 从数据库读取并展示选中对象的 DDL，可复制到 SQL 控制台。
	 */
	private void showSelectedTableDdl() {
		JdbcTable table = selectedJdbcTable();
		if (Objects.isNull(table)) {
			status.setText(message("select.a.table.or.view.to.inspect"));
			return;
		}
		DbType type = databaseType.getItemAt(databaseType.getSelectedIndex());
		runDatabaseOperation(message("load.ddl"),
			connection -> JdbcTableDdl.readTableDdl(connection.connection(), type, table, WorkbenchBundle::message),
			ddl -> {
				JTextArea definition = new JTextArea(ddl, 18, 72);
				definition.setEditable(false);
				definition.setCaretPosition(0);
				JOptionPane.showMessageDialog(this, new JBScrollPane(definition), table.name() + " DDL",
					JOptionPane.PLAIN_MESSAGE);
			});
	}

	/**
	 * 用当前连接配置预览选定表的前 100 行。
	 */
	private void previewSelectedTableRows() {
		JdbcTable table = selectedJdbcTable();
		if (Objects.isNull(table)) {
			status.setText(message("select.a.table.to.browse"));
			return;
		}
		DbType type = databaseType.getItemAt(databaseType.getSelectedIndex());
		runDatabaseOperation(message("browse.table.data"),
			connection -> JdbcTableEditor.previewTableRows(connection, type, table, WorkbenchBundle::message),
			this::showTableRows);
	}

	/**
	 * 显示预览页，只有表数据来源且有可写列时开放编辑按钮。
	 *
	 * @param page 当前连接的表数据
	 */
	private void showTableRows(JdbcTableRows page) {
		if (!Objects.equals(selectedJdbcTable(), page.table())) {
			return;
		}
		showSqlResult(page.result());
		currentTableRows = page;
		nextTablePage.setEnabled(page.hasNextPage());
		previousTablePage.setEnabled(page.hasPreviousPage());
		editTableCell.setEnabled(!page.editableColumns().isEmpty());
	}

	/**
	 * 在当前数据源重新读取相邻表页。
	 *
	 * @param backward 为 true 时按当前页首行查找前一页，否则从末行继续
	 */
	private void browseTablePage(boolean backward) {
		JdbcTableRows page = currentTableRows;
		if (Objects.isNull(page) || !page.table().equals(selectedJdbcTable())
			|| !(backward ? page.hasPreviousPage() : page.hasNextPage())) {
			return;
		}
		DbType type = databaseType.getItemAt(databaseType.getSelectedIndex());
		runDatabaseOperation(message(backward ? "previous.page" : "next.page"),
			connection -> backward ? JdbcTableEditor.readPreviousTableRows(connection, type, page, WorkbenchBundle::message)
				: JdbcTableEditor.readNextTableRows(connection, type, page, WorkbenchBundle::message),
			adjacent -> {
				if (currentTableRows != page || !page.table().equals(selectedJdbcTable())) {
					return;
				}
				if (adjacent.result().rows().isEmpty()) {
					// 边界外的行可能在两次查询之间删除，保留原页供用户继续浏览或刷新。
					(backward ? previousTablePage : nextTablePage).setEnabled(false);
					status.setText(message(backward ? "table.page.no.previous.rows" : "table.page.no.next.rows"));
					return;
				}
				showTableRows(adjacent);
			});
	}

	/**
	 * 显示原值与新值输入项，确认后按原表及行执行一次更新。
	 */
	private void editVisibleTableCell() {
		JdbcConnectionProfile source = selectedConnectionProfile();
		JdbcTableRows page = currentTableRows;
		int row = resultTable.getSelectedRow();
		int column = resultTable.getSelectedColumn();
		if (Objects.isNull(page) || row < 0 || column < 0) {
			status.setText(message("select.a.cell.to.edit"));
			return;
		}
		int modelRow = resultTable.convertRowIndexToModel(row);
		int modelColumn = resultTable.convertColumnIndexToModel(column);
		String columnName = page.result().columnLabels().get(modelColumn);
		if (!page.editableColumns().contains(columnName)) {
			status.setText(message("primary.keys.generated.columns.and.unsupported.types.are.read.only"));
			return;
		}
		String previous = page.result().rows().get(modelRow).get(modelColumn);
		JTextArea input = new JTextArea(Objects.toString(previous, ""), 4, 28);
		JCheckBox writeNull = new JCheckBox(message("set.sql.null"), Objects.isNull(previous));
		input.setEnabled(!writeNull.isSelected());
		writeNull.addActionListener(event -> input.setEnabled(!writeNull.isSelected()));
		JPanel form = new JPanel(new GridLayout(0, 1, 4, 4));
		form.add(new JLabel(message("update") + page.table().name() + "." + columnName));
		form.add(new JBScrollPane(input));
		form.add(writeNull);
		int choice = JOptionPane.showConfirmDialog(this, form, message("save.cell"), JOptionPane.OK_CANCEL_OPTION);
		if (choice != JOptionPane.OK_OPTION) {
			return;
		}
		String value = writeNull.isSelected() ? null : input.getText();
		if (Objects.equals(previous, value)) {
			status.setText(message("no.changes"));
			return;
		}
		if (!isCurrentJdbcSource(source) || currentTableRows != page || !page.table().equals(selectedJdbcTable())) {
			return;
		}
		DbType type = source.dbType();
		runDatabaseOperation(message("update.table.data"), database ->
			JdbcTableEditor.updateTableCell(database.connection(), type, page, modelRow, columnName, value, WorkbenchBundle::message), storedValue -> {
			if (currentTableRows != page || !page.table().equals(selectedJdbcTable())) {
				return;
			}
			var result = page.result();
			List<List<String>> rows = new ArrayList<>(result.rows());
			List<String> updated = new ArrayList<>(rows.get(modelRow));
			updated.set(modelColumn, storedValue);
			rows.set(modelRow, Collections.unmodifiableList(updated));
			var refreshed = new JdbcQueryResult(result.columnLabels(), List.copyOf(rows), result.truncated(), result.updateCount());
			currentQueryResult = refreshed;
			currentTableRows = new JdbcTableRows(page.table(), refreshed, page.primaryKeys(), page.editableColumns(),
				page.hasNextPage(), page.hasPreviousPage());
			resultTable.getModel().setValueAt(storedValue, modelRow, modelColumn);
			status.setText(message("table.cell.saved"));
		});
	}

	/**
	 * 在结果区显示当前表的列、JDBC 类型、主键位置和备注。
	 *
	 * @param table 已读取得到的表快照
	 */
	private void showTableStructure(GenerationTable table) {
		consoleLayout.show(consoleWorkspace, "SQL");
		consoleWorkspace.setVisible(true);
		currentTableRows = null;
		nextTablePage.setEnabled(false);
		previousTablePage.setEnabled(false);
		editTableCell.setEnabled(false);
		currentQueryResult = null;
		exportCsv.setEnabled(false);
		DefaultTableModel model = resultModel(new Object[]{message("column"), message("jdbc.type"), message("primary.key.position"), message("nullable"), message("comment")});
		var keys = table.tableInfo().getPrimaryKeyColumns();
		for (var column : table.tableInfo().getColumnInfos()) {
			Object keyPosition = keys.stream().filter(key -> key.getName().equals(column.getName()))
				.findFirst().map(key -> key.getKeySeq()).orElse(null);
			model.addRow(new Object[]{column.getName(), column.getDataType(), keyPosition,
				column.getNullable(), column.getRemarks()});
		}
		resultTable.setModel(model);
	}

	/**
	 * 按需读取别名所指表或已选表的列，再为当前编辑器位置提供候选。
	 */
	private void requestSqlCompletion() {
		String sql = sqlEditor.getText();
		int caret = sqlEditor.getCaretPosition();
		DbType type = databaseType.getItemAt(databaseType.getSelectedIndex());
		JdbcTable selectedTable = selectedJdbcTable();
		JdbcTable table = JdbcSqlCompletion.resolveSqlCompletionTable(type, sql, caret, browsedTables, selectedTable);
		long revision = connectionRevision;
		// 后台返回及弹窗确认都必须对应发起时的连接和编辑位置。
		BooleanSupplier requestStillCurrent = () -> revision == connectionRevision
			&& caret == sqlEditor.getCaretPosition() && sql.equals(sqlEditor.getText())
			&& Objects.equals(selectedTable, selectedJdbcTable());
		Consumer<List<IntellijColumnInfo>> show = columns -> {
			if (requestStillCurrent.getAsBoolean()) {
				showSqlCompletion(type, sql, caret, table, columns, requestStillCurrent);
			}
		};
		if (Objects.isNull(table)) {
			show.accept(List.of());
		} else if (Objects.equals(sqlCompletionTable, table)) {
			show.accept(sqlCompletionColumns);
		} else {
			runDatabaseOperation(message("load.columns.for.sql.completion"), connection -> JdbcTableMetadata.readGenerationTable(
				connection.connection().getMetaData(), type, table).tableInfo().getColumnInfos(), columns -> {
				sqlCompletionTable = table;
				sqlCompletionColumns = columns;
				show.accept(columns);
			});
		}
	}

	/**
	 * 在 SQL 光标旁展示补全候选。选择后仅替换原来的标识符。
	 *
	 * @param type    当前数据库方言
	 * @param sql     发起补全时的编辑器内容
	 * @param caret   发起补全时的光标位置
	 * @param table   别名所指表或当前对象树所选表，可以为空
	 * @param columns 已取得的列元数据
	 * @param requestStillCurrent 确认候选时重新检查连接和编辑位置
	 */
	private void showSqlCompletion(DbType type, String sql, int caret, JdbcTable table,
								   List<IntellijColumnInfo> columns, BooleanSupplier requestStillCurrent) {
		List<String> choices = JdbcSqlCompletion.suggestSqlIdentifiers(type, sql, caret, browsedTables, table, columns);
		if (choices.isEmpty()) {
			status.setText(message("no.matching.sql.completions"));
			return;
		}
		int start = JdbcSqlCompletion.findIdentifierBoundary(sql, caret, -1);
		int end = JdbcSqlCompletion.findIdentifierBoundary(sql, caret, 1);
		var popup = JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
			.setTitle(message("sql.completion"))
			.setItemChosenCallback(choice -> {
				if (requestStillCurrent.getAsBoolean()) {
					sqlEditor.replaceRange(choice, start, end);
					sqlEditor.setCaretPosition(start + choice.length());
					sqlEditor.requestFocusInWindow();
				}
			}).createPopup();
		try {
			var bounds = sqlEditor.modelToView2D(caret);
			if (Objects.nonNull(bounds)) {
				popup.show(new RelativePoint(sqlEditor, new Point((int) bounds.getX(), (int) bounds.getMaxY())));
			} else {
				popup.showUnderneathOf(sqlEditor);
			}
		} catch (BadLocationException failure) {
			log.warn("SQL 补全定位失败，光标={}", caret, failure);
			popup.showUnderneathOf(sqlEditor);
		}
	}

	/**
	 * 使用选中的 SQL，选区为空时使用编辑器全文。
	 *
	 * @param explain 是否读取估算计划
	 */
	private void executeEditorSql(boolean explain) {
		JdbcConnectionProfile source = selectedConnectionProfile();
		if (!isCurrentJdbcSource(source)) {
			return;
		}
		String sql = Objects.requireNonNullElse(sqlEditor.getSelectedText(), sqlEditor.getText());
		if (sql.isBlank()) {
			status.setText(message("enter.sql"));
			return;
		}
		DbType type = databaseType.getItemAt(databaseType.getSelectedIndex());
		runDatabaseOperation(explain ? message("read.estimated.plan") : message("run.sql"), connection ->
				explain ? connection.explainSql(type, sql, 1_000, 30, WorkbenchBundle::message)
					: connection.executeSql(sql, 1_000, 30),
			this::showSqlResult);
	}

	/**
	 * 在提交数据库操作前检查选择、表单与最新已保存快照，阻止确认窗口打开期间
	 * 已替换或删除的数据源继续使用旧地址。
	 *
	 * @param source 打开操作时捕获的连接配置
	 * @return 当前选中的连接与该快照一致且仍然有效
	 */
	private boolean isCurrentJdbcSource(JdbcConnectionProfile source) {
		if (Objects.isNull(source)) {
			status.setText(message("select.query.source"));
			return false;
		}
		if (!source.equals(currentConnectionProfile()) || !source.equals(selectedConnectionProfile())
			|| !workbenchConnectionProfiles.listProfiles().contains(source)) {
			refreshDataSourceNodes();
			status.setText(message("data.source.changed"));
			return false;
		}
		return true;
	}

	/**
	 * 将查询结果替换到结果表格并报告截断或影响行数。
	 *
	 * @param result 首个 JDBC 结果
	 */
	private void showSqlResult(JdbcQueryResult result) {
		consoleLayout.show(consoleWorkspace, "SQL");
		consoleWorkspace.setVisible(true);
		currentTableRows = null;
		nextTablePage.setEnabled(false);
		previousTablePage.setEnabled(false);
		editTableCell.setEnabled(false);
		currentQueryResult = result;
		exportCsv.setEnabled(!result.columnLabels().isEmpty());
		DefaultTableModel model = resultModel(result.columnLabels().toArray());
		result.rows().forEach(row -> model.addRow(row.toArray()));
		resultTable.setModel(model);
		if (result.updateCount().isPresent()) {
			status.setText(message("affected.rows") + result.updateCount().getAsLong());
		} else {
			status.setText(message("returned") + result.rows().size() + message("rows") + (result.truncated() ? message("truncated") : ""));
		}
	}

	/**
	 * 导出当前结果页，截断状态会在保存提示及完成状态中保留。
	 */
	private void exportVisibleQueryResult() {
		JdbcQueryResult result = currentQueryResult;
		if (Objects.isNull(result) || result.columnLabels().isEmpty()) {
			return;
		}
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle(result.truncated() ? message("export.current.page.results.truncated") : message("export.query.results"));
		if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		Path target = chooser.getSelectedFile().toPath();
		ApplicationManager.getApplication().executeOnPooledThread(() -> {
			try {
				JdbcResultCsv.exportQueryResult(target, result);
				ApplicationManager.getApplication().invokeLater(() -> {
					if (!project.isDisposed()) {
						status.setText(message("exported") + result.rows().size() + message("rows") + (result.truncated() ? message("current.page") : ""));
					}
				});
			} catch (IOException failure) {
				log.warn("导出 JDBC 查询结果失败，阶段=写入，目标文件={}，结果行数={}", target, result.rows().size(), failure);
				ApplicationManager.getApplication().invokeLater(() -> {
					if (!project.isDisposed()) {
						status.setText(message("csv.export.failed") + failure.getMessage());
					}
				});
			}
		});
	}

	/**
	 * 创建只读展示模型，数据编辑由独立写入流程实现。
	 *
	 * @param columns 表头
	 * @return 空的只读表格模型
	 */
	private DefaultTableModel resultModel(Object[] columns) {
		return new DefaultTableModel(columns, 0) {
			/** {@inheritDoc} 工作台结果区仅展示。 */
			@Override
			public boolean isCellEditable(int row, int column) {
				return false;
			}
		};
	}

	/**
	 * 在 EDT 捕获连接输入，后台连接并运行操作，结束后回到 EDT 更新组件。
	 *
	 * @param stage     用于状态和异常定位的阶段
	 * @param operation 在后台执行的 JDBC 操作
	 * @param callback  成功后在 EDT 执行的回调
	 * @param <T>       操作的返回类型
	 */
	private <T> void runDatabaseOperation(String stage, JdbcOperation<T> operation, Consumer<T> callback) {
		if (Objects.nonNull(databaseTask)) {
			return;
		}
		String url = jdbcUrl.getText().strip();
		String driver = driverClass.getText().strip();
		String coordinates = driverCoordinates.getText().strip();
		String repository = repositoryUrl.getText().strip();
		if (url.isEmpty() || driver.isEmpty() || coordinates.isEmpty()) {
			status.setText(message("enter.driver.coordinates.driver.class.and.jdbc.url"));
			return;
		}
		Properties properties = new Properties();
		if (!username.getText().isBlank()) {
			properties.setProperty("user", username.getText());
		}
		char[] secret = password.getPassword();
		if (secret.length > 0) {
			properties.setProperty("password", new String(secret));
			Arrays.fill(secret, '\0');
		}

		long revision = connectionRevision;
		status.setText(stage + message("in.progress"));
		databaseTask = new SwingWorker<T, Void>() {
			/** {@inheritDoc} 各项操作在后台打开及关闭自己的连接。 */
			@Override
			protected T doInBackground() throws Exception {
				try {
					var paths = WorkbenchJdbcArtifacts.resolveDriverFiles(coordinates, repository);
					try (JdbcDriverConnection connection = new JdbcDriverConnection(paths, driver, url, properties)) {
						return operation.apply(connection);
					}
				} finally {
					properties.clear();
				}
			}

			/** {@inheritDoc} 项目关闭或任务取消后跳过 UI 更新。 */
			@Override
			protected void done() {
				databaseTask = null;
				if (isCancelled() || project.isDisposed() || revision != connectionRevision) {
					return;
				}
				try {
					T result = get();
					status.setText(stage + message("completed"));
					callback.accept(result);
				} catch (InterruptedException failure) {
					Thread.currentThread().interrupt();
				} catch (ExecutionException failure) {
					Throwable cause = failure.getCause();
					log.warn("数据库操作失败，阶段={}，驱动={}", stage, driver, cause);
					status.setText(stage + message("failed") + cause.getMessage());
				}
			}
		};
		databaseTask.execute();
	}

	/**
	 * 请求取消后台任务并清空界面持有的密码。
	 */
	@Override
	public void dispose() {
		workbenchNoSqlNavigator.dispose();
		redisPanel.dispose();
		mongoPanel.dispose();
		elasticsearchPanel.dispose();
		if (Objects.nonNull(databaseTask)) {
			databaseTask.cancel(true);
		}
		password.setText("");
	}

	/**
	 * 后台任务接收一个有效的 JDBC 连接，并返回脱离连接生命周期的值。
	 *
	 * @param <T> 返回值类型
	 * @author liaojinlong
	 * @since 2026/11/18 15:11
	 */
	@FunctionalInterface
	private interface JdbcOperation<T> {
		/**
		 * 在工作线程执行本次数据库操作。
		 *
		 * @param connection 当前连接
		 * @return 操作结果
		 * @throws Exception 驱动或数据库操作失败
		 */
		T apply(JdbcDriverConnection connection) throws Exception;
	}
}
