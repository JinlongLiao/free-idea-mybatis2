package io.github.jinlongliao.mybatis.workbench.ui;

import static io.github.jinlongliao.mybatis.workbench.ui.WorkbenchBundle.message;

import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.icons.AllIcons;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.lang.Language;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.SystemInfoRt;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.CheckBoxList;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.ListSpeedSearch;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.SimpleListCellRenderer;
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

import java.awt.GridLayout;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.regex.Pattern;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.swing.JComboBox;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
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
	 * 从库、模式或表节点打开控制台时选定的范围，空文本表示沿用连接默认范围。
	 */
	private final JLabel sqlConsoleNamespace = new JLabel();

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

	/** 本机连接偏好；关闭校验时驱动仍须使用 TLS 加密。 */
	private final JCheckBox verifyJdbcCertificate = new JCheckBox(message("elasticsearch.verify.certificate"), true);

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
	private final WorkbenchNoSqlNavigator workbenchNoSqlNavigator;

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

	/** 仅筛选当前已返回到 IDE 的结果行，不会对数据库发起新查询。 */
	private final JTextField resultFilter = new JTextField(18);

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
	/** 按显示的表结构白名单新增一行，省略的列使用数据库默认值。 */
	private final JButton insertTableRowButton = databaseButton(message("insert.table.row"), this::addVisibleTableRow);
	/** 按完整行快照和主键删除一行。 */
	private final JButton deleteTableRowButton = databaseButton(message("delete.selected.table.row"), this::deleteSelectedTableRow);

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

	/** SQL 输入和结果共享控制台，结果表可以占满控制台可用高度。 */
	private final OnePixelSplitter queryResultSplitter = new OnePixelSplitter(false, 0.48f, 0.2f, 0.8f);

	/**
	 * 当前操作的状态或影响行数。
	 */
	private final JLabel status = new JLabel(message("set.up.a.connection.to.browse.tables.or.run.sql"));

	/**
	 * 面板一次执行一个任务，只在 EDT 访问。
	 */
	private SwingWorker<?, ?> databaseTask;

	/** SQL 控制台当前执行语句所属连接，供 EDT 将取消请求转给 JDBC Statement。 */
	private volatile JdbcDriverConnection activeSqlConnection;

	/** 每次 SQL 控制台操作的身份令牌，异步取消回调只允许更新它所属的操作。 */
	private volatile Object activeSqlOperation;

	/** 用户请求取消的 SQL 操作令牌；连接建立前的请求也由该令牌传递给后台。 */
	private volatile Object cancelledSqlOperation;

	/** SQL 正在运行时向 JDBC Statement 发出协作式取消请求。 */
	private final JButton cancelSqlButton = new JButton(message("cancel.sql.query"));

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
		this.workbenchNoSqlNavigator = new WorkbenchNoSqlNavigator(project, objectModel, objectTree);
		databaseType.setRenderer(new SimpleListCellRenderer<DbType>() {
			/** {@inheritDoc} 已选项和下拉项沿用统一数据源的引擎标识。 */
			@Override
			public void customize(JList<? extends DbType> list, DbType type, int index, boolean selected, boolean hasFocus) {
				if (Objects.nonNull(type)) {
					setText(type == DbType.SqlServer ? "SQL Server" : type.name());
					setIcon(databaseSourceIcon(type));
				}
			}
		});
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
				.addComponent(verifyJdbcCertificate)
			.getPanel();
		verifyJdbcCertificate.setToolTipText(message("elasticsearch.verify.certificate.tooltip"));
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
				verifyJdbcCertificate.setVisible(type != DbType.H2 && type != DbType.SQLite);
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
				insertTableRowButton.setEnabled(false);
				deleteTableRowButton.setEnabled(false);
				exportCsv.setEnabled(false);
				resultTable.setModel(resultModel(new Object[0]));
				applyQueryResultFilter();
				resultFilter.setText("");
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
				insertTableRowButton.setEnabled(false);
				deleteTableRowButton.setEnabled(false);
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
			/** {@inheritDoc} 数据源按引擎显示图标，表与目录保留各自的对象图标。 */
			@Override
			public void customizeCellRenderer(JTree tree, Object value, boolean selected,
											  boolean expanded, boolean leaf, int row, boolean focus) {
				Object object = value instanceof DefaultMutableTreeNode node ? node.getUserObject() : value;
				if (object instanceof JdbcConnectionProfile profile) {
					setIcon(databaseSourceIcon(profile.dbType()));
					append(profile.name());
					String type = profile.dbType() == DbType.SqlServer ? "SQL Server" : profile.dbType().name();
					append("  " + type, SimpleTextAttributes.GRAYED_ATTRIBUTES);
					if (usesJdbcSchemas(profile.dbType()) || usesJdbcDatabaseCatalogs(profile.dbType())) {
						appendNamespaceSelection(profile.id(), usesJdbcSchemas(profile.dbType()));
					}
				} else if (object instanceof NoSqlConnectionProfile profile) {
					setIcon(databaseSourceIcon(profile.type()));
					append(profile.name());
					append("  " + noSqlTitle(profile.type()), SimpleTextAttributes.GRAYED_ATTRIBUTES);
					if (profile.type() == NoSqlSourceType.MONGODB) {
						appendNamespaceSelection(profile.id(), false);
					} else if (profile.type() == NoSqlSourceType.REDIS) {
						String match = WorkbenchNoSqlNavigator.readRedisTreeFilter(profile);
						if (!"*".equals(match)) {
							append("  " + message("redis.tree.active.filter", match), SimpleTextAttributes.GRAYED_ATTRIBUTES);
						}
					}
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
			/**
			 * 仅显示用户明确保存的选择数量，绘制节点时无需连接数据库。
			 *
			 * @param sourceId 数据源配置标识
			 * @param schemas 为 true 时使用模式计数文案
			 */
			private void appendNamespaceSelection(UUID sourceId, boolean schemas) {
				int count = WorkbenchDatabaseCatalogs.selectedDatabaseNames(sourceId, List.of()).size();
				if (count > 0) {
					append("  " + message(schemas ? "database.schema.selected.count" : "database.catalog.selected.count", count),
						SimpleTextAttributes.GRAYED_ATTRIBUTES);
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

			/** {@inheritDoc} 双击根节点刷新、容器节点折叠/展开、数据对象打开对应的预览或控制台。 */
			@Override
			public void mouseClicked(MouseEvent event) {
				TreePath clickedPath = objectTree.getPathForLocation(event.getX(), event.getY());
				if (event.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(event)
					|| Objects.isNull(clickedPath)) {
					return;
				}
				// 双击路由必须以鼠标命中的行作为准绳，避免搜索或快速连击后沿用旧选中项。
				objectTree.setSelectionPath(clickedPath);
				if (clickedPath.getLastPathComponent() instanceof DefaultMutableTreeNode node
					&& node.getUserObject() instanceof WorkbenchNoSqlNavigator.RedisScanContinuation) {
					workbenchNoSqlNavigator.loadNextRedisPage(node);
				} else if (Objects.nonNull(selectedJdbcTable())) {
					previewSelectedTableRows();
				} else if (workbenchNoSqlNavigator.openSelectedNoSqlObjectEditor()) {
					return;
				} else if (clickedPath.getLastPathComponent() instanceof DefaultMutableTreeNode node) {
					if (node.getUserObject() instanceof JdbcConnectionProfile
						|| node.getUserObject() instanceof NoSqlConnectionProfile) {
						// 根节点双击刷新所属连接，并由 refreshSelectedSource 保留当前节点路径。
						refreshSelectedSource();
					} else if (node.getAllowsChildren()) {
						// Mongo 数据库、ES 分组和 Redis 数据库/节点是容器，只展开或折叠，不切换控制台。
						TreePath path = new TreePath(node.getPath());
						if (objectTree.isExpanded(path)) {
							objectTree.collapsePath(path);
						} else {
							objectTree.expandPath(path);
						}
					} else {
						var profile = selectedNoSqlProfile();
						if (Objects.nonNull(profile) && profile.type() == NoSqlSourceType.REDIS
							&& node.getUserObject() instanceof WorkbenchNoSqlNavigator.NoSqlObjectName) {
							// 仅 Redis 键叶子打开连接控制台；加载占位和空状态不触发切换。
							openSelectedNoSqlConsole(profile);
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
		sqlToolbar.add(sqlConsoleNamespace);
		sqlConsoleNamespace.setVisible(false);
		Runnable runSql = () -> executeEditorSql(false);
		sqlToolbar.add(databaseButton(message("run.sql"), runSql));
		cancelSqlButton.setEnabled(false);
		cancelSqlButton.addActionListener(event -> cancelCurrentSqlQuery());
		sqlToolbar.add(cancelSqlButton);
		// 与工具栏使用同一执行入口，保留选区及数据源检查。
		sqlEditor.registerKeyboardAction(event -> runSql.run(),
			KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, menuModifier), WHEN_FOCUSED);
		sqlToolbar.add(databaseButton(message("show.estimated.plan"), () -> executeEditorSql(true)));
		sqlToolbar.add(databaseButton(message("complete.sql"), this::requestSqlCompletion));
		editTableCell.setEnabled(false);
		sqlToolbar.add(editTableCell);
		insertTableRowButton.setEnabled(false);
		deleteTableRowButton.setEnabled(false);
		sqlToolbar.add(insertTableRowButton);
		sqlToolbar.add(deleteTableRowButton);
		nextTablePage.setEnabled(false);
		previousTablePage.setEnabled(false);
		sqlToolbar.add(previousTablePage);
		sqlToolbar.add(nextTablePage);
		exportCsv.setEnabled(false);
		sqlToolbar.add(exportCsv);
		JLabel resultFilterLabel = new JLabel(message("workbench.filter.current.result"));
		resultFilterLabel.setLabelFor(resultFilter);
		resultFilter.setToolTipText(message("workbench.filter.current.result.tooltip"));
		resultFilter.getDocument().addDocumentListener(new DocumentAdapter() {
			/** {@inheritDoc} 只更新内存中当前结果页的本地视图。 */
			@Override
			protected void textChanged(DocumentEvent event) {
				applyQueryResultFilter();
			}
		});
		resultTable.setCellSelectionEnabled(true);
		resultTable.setAutoCreateRowSorter(true);
		// 窄工具窗口允许横向滚动，不把所有列一起压缩到看不清字段。
		resultTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		sqlEditor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK), "workbench.sql.complete");
		sqlEditor.getActionMap().put("workbench.sql.complete", new AbstractAction() {
			/** {@inheritDoc} 在 SQL 编辑器中以快捷键请求候选。 */
			@Override
			public void actionPerformed(ActionEvent event) {
				requestSqlCompletion();
			}
		});
		queryResultSplitter.setFirstComponent(new JBScrollPane(sqlEditor));
		JPanel resultWorkspace = new JPanel(new BorderLayout(4, 4));
		JPanel resultFilterBar = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 2));
		resultFilterBar.add(resultFilterLabel);
		resultFilterBar.add(resultFilter);
		resultWorkspace.add(resultFilterBar, BorderLayout.NORTH);
		resultWorkspace.add(new JBScrollPane(resultTable), BorderLayout.CENTER);
		queryResultSplitter.setSecondComponent(resultWorkspace);
		queryWorkspace.add(sqlToolbar, BorderLayout.NORTH);
		queryWorkspace.add(queryResultSplitter, BorderLayout.CENTER);
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
		var connectionProfilesBusConnection = ApplicationManager.getApplication().getMessageBus().connect(this);
		connectionProfilesBusConnection.subscribe(WorkbenchConnectionProfilesListener.PROFILES_RELOADED,
			this::refreshDataSourceNodes);
		connectionProfilesBusConnection.subscribe(WorkbenchConnectionProfilesListener.NO_SQL_CONNECTION_SETTINGS_CHANGED,
			new WorkbenchConnectionProfilesListener() {
				/** {@inheritDoc} 此主题仅发送 NoSQL 本机设置变化。 */
				@Override
				public void connectionProfilesReloaded() {
				}

				/** {@inheritDoc} 若右侧当前显示该 Elasticsearch 连接，则更新为刚保存的本机设置。 */
				@Override
				public void noSqlConnectionSettingsChanged(UUID profileId) {
					refreshOpenElasticsearchConnectionSettings(profileId);
				}
			});
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
				verifyJdbcCertificate.setSelected(true);
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
	 * 打开所选数据源的控制台，保留草稿并固定打开时选中的库或模式。
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
		setSqlConsoleNamespace(resolveSelectedConsoleNamespace(jdbc));
		consoleLayout.show(consoleWorkspace, "SQL");
		consoleWorkspace.setVisible(true);
		sqlEditor.requestFocusInWindow();
	}

	/**
	 * 从对象树读取当前库、模式或表所属的范围。
	 *
	 * @param source 当前选中对象所属的数据源
	 * @return 目标库或模式，空字符串表示连接默认范围
	 */
	private String resolveSelectedConsoleNamespace(JdbcConnectionProfile source) {
		boolean schema = usesJdbcSchemas(source.dbType());
		if (!schema && !usesJdbcDatabaseCatalogs(source.dbType())) {
			return "";
		}
		if (objectTree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode node) {
			if (node.getUserObject() instanceof JdbcTable table) {
				return Objects.toString(schema ? table.schema() : table.catalog(), "");
			}
			if (node.getAllowsChildren() && node.getUserObject() instanceof String catalog
				&& node.getParent() instanceof DefaultMutableTreeNode parent && source.equals(parent.getUserObject())) {
				return catalog;
			}
		}
		return "";
	}

	/**
	 * 更新控制台可见的库或模式，并阻止切换前的查询结果回写。
	 *
	 * @param namespace 要使用的库或模式，空字符串表示连接默认范围
	 */
	private void setSqlConsoleNamespace(String namespace) {
		if (!namespace.equals(sqlConsoleNamespace.getText())) {
			connectionRevision++;
			sqlConsoleNamespace.setText(namespace);
			sqlConsoleNamespace.setVisible(!namespace.isEmpty());
			currentQueryResult = null;
			currentTableRows = null;
			nextTablePage.setEnabled(false);
			previousTablePage.setEnabled(false);
			editTableCell.setEnabled(false);
			insertTableRowButton.setEnabled(false);
			deleteTableRowButton.setEnabled(false);
			resultTable.setModel(resultModel(new Object[0]));
			applyQueryResultFilter();
			resultFilter.setText("");
			exportCsv.setEnabled(false);
		}
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
		JdbcConnectionProfile source = selectedConnectionProfile();
		runDatabaseOperation(message("browse.tables"), connection -> listVisibleJdbcTables(connection.connection(), source),
			this::showDatabaseObjects);
	}

	/**
	 * @param connection 已打开的 JDBC 连接
	 * @param source 当前连接配置，未保存时可为空
	 * @return 有显式选择时只读取已选目录或模式中的表
	 * @throws SQLException 无法读取数据库元数据
	 */
	private List<JdbcTable> listVisibleJdbcTables(Connection connection, JdbcConnectionProfile source) throws SQLException {
		if (Objects.nonNull(source)) {
			List<String> selected = WorkbenchDatabaseCatalogs.selectedDatabaseNames(source.id(), List.of());
			if (!selected.isEmpty() && usesJdbcDatabaseCatalogs(source.dbType())) {
				return JdbcTableMetadata.listTablesInCatalogs(connection.getMetaData(), selected);
			}
			if (!selected.isEmpty() && usesJdbcSchemas(source.dbType())) {
				List<JdbcTable> tables = new ArrayList<>();
				for (String schema : selected) {
					tables.addAll(JdbcTableMetadata.listTables(connection.getMetaData(), connection.getCatalog(), schema));
				}
				return List.copyOf(tables);
			}
		}
		String catalog = connection.getCatalog();
		if (Objects.nonNull(source) && usesJdbcDatabaseCatalogs(source.dbType())
			&& (Objects.isNull(catalog) || catalog.isBlank())) {
			// 服务器连接未指定默认库时，由用户勾选要浏览的库，避免枚举所有库的表。
			return List.of();
		}
		return JdbcTableMetadata.listTables(connection.getMetaData(), catalog, null);
	}

	/**
	 * @param type 当前连接的数据库类型
	 * @return 该类型通过 JDBC 目录切换同一服务器上的数据库时为 true
	 */
	private static boolean usesJdbcDatabaseCatalogs(DbType type) {
		return type == DbType.MySQL || type == DbType.MariaDB;
	}

	/**
	 * @param type 当前连接的数据库类型
	 * @return 该类型支持在当前 JDBC 目录内选择模式时为 true
	 */
	private static boolean usesJdbcSchemas(DbType type) {
		return type == DbType.PostgreSQL || type == DbType.H2;
	}

	/**
	 * 读取当前账号可见的 MySQL、MariaDB 目录或 PostgreSQL、H2 模式，保存多选项后刷新树。
	 */
	private void chooseJdbcNamespaces() {
		JdbcConnectionProfile source = selectedConnectionProfile();
		if (Objects.isNull(source) || (!usesJdbcDatabaseCatalogs(source.dbType()) && !usesJdbcSchemas(source.dbType()))) {
			return;
		}
		if (!source.equals(currentConnectionProfile())) {
			fillConnectionProfile(source, true);
		}
		boolean schemas = usesJdbcSchemas(source.dbType());
		runDatabaseOperation(message(schemas ? "database.schema.choose" : "database.catalog.choose"), database -> {
			var connection = database.connection();
			if (schemas) {
				List<String> names = new ArrayList<>();
				try (var rows = connection.getMetaData().getSchemas(connection.getCatalog(), null)) {
					while (rows.next()) {
						names.add(rows.getString("TABLE_SCHEM"));
					}
				}
				return List.of(names, names);
			}
			List<String> names = JdbcTableMetadata.listCatalogNames(connection.getMetaData());
			String catalog = connection.getCatalog();
			return List.of(names, Objects.isNull(catalog) ? names : List.of(catalog));
		}, catalogs -> {
			if (isCurrentJdbcSource(source)) {
				WorkbenchDatabaseCatalogs.chooseNamespaces(this, source.id(), catalogs.getFirst(), catalogs.getLast(),
					"", () -> isCurrentJdbcSource(source), schemas, this::refreshCurrentJdbcSource);
			}
		});
	}

	/**
	 * @param menu 当前数据源的操作菜单
	 */
	private void addSourceBrowseScopeAction(JPopupMenu menu) {
		var jdbc = selectedConnectionProfile();
		var noSql = selectedNoSqlProfile();
		if (Objects.nonNull(jdbc) && (usesJdbcDatabaseCatalogs(jdbc.dbType()) || usesJdbcSchemas(jdbc.dbType()))) {
			addDatabaseMenuItem(menu, message(usesJdbcSchemas(jdbc.dbType()) ? "database.schema.choose"
				: "database.catalog.choose"), this::chooseJdbcNamespaces);
		} else if (Objects.nonNull(noSql) && noSql.type() == NoSqlSourceType.MONGODB
			&& objectTree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode node) {
			addDatabaseMenuItem(menu, message("database.catalog.choose"),
				() -> workbenchNoSqlNavigator.chooseMongoDatabases(node, noSql));
		} else if (Objects.nonNull(noSql) && noSql.type() == NoSqlSourceType.REDIS
			&& objectTree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode node) {
			addDatabaseMenuItem(menu, message("redis.tree.key.filter"),
				() -> workbenchNoSqlNavigator.configureRedisTreeFilter(node, noSql));
		}
	}

	/**
	 * 列出统一数据源入口可创建的关系库或非关系库。
	 *
	 * @param anchor 菜单所在的新增按钮
	 */
	private void showAddSourceMenu(JButton anchor) {
		List<String> choices = new ArrayList<>(10);
		Map<String, Enum<?>> typesByTitle = new HashMap<>(16);
		for (DbType type : DbType.values()) {
			String title = type == DbType.SqlServer ? "SQL Server" : type.name();
			choices.add(title);
			typesByTitle.put(title, type);
		}
		for (NoSqlSourceType type : NoSqlSourceType.values()) {
			String title = noSqlTitle(type);
			choices.add(title);
			typesByTitle.put(title, type);
		}
		JPanel content = new JPanel(new BorderLayout(6, 6));
		JBTextField search = new JBTextField();
		search.setToolTipText(message("new.connection.type.search"));
		search.getAccessibleContext().setAccessibleName(message("new.connection.type.search"));
		JLabel resultCount = new JLabel();
		JPanel searchBar = new JPanel(new BorderLayout(6, 0));
		searchBar.add(new JLabel(message("new.connection.type.search")), BorderLayout.WEST);
		searchBar.add(search, BorderLayout.CENTER);
		searchBar.add(resultCount, BorderLayout.EAST);
		content.add(searchBar, BorderLayout.NORTH);
		DefaultListModel<String> visibleChoices = new DefaultListModel<>();
		JList<String> list = new JList<>(visibleChoices);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setCellRenderer(new SimpleListCellRenderer<String>() {
			/** {@inheritDoc} 展示统一数据源可用协议的名称与对应图标。 */
			@Override
			public void customize(JList<? extends String> source, String title, int index, boolean selected,
				boolean hasFocus) {
				setText(title);
				setIcon(databaseSourceIcon(typesByTitle.get(title)));
			}
		});
		JBScrollPane scroll = new JBScrollPane(list);
		scroll.setPreferredSize(new Dimension(310, 300));
		content.add(scroll, BorderLayout.CENTER);
		Runnable filterChoices = () -> {
			String query = search.getText().strip().toLowerCase(Locale.ROOT);
			visibleChoices.clear();
			choices.stream().filter(title -> title.toLowerCase(Locale.ROOT).contains(query))
				.forEach(visibleChoices::addElement);
			if (!visibleChoices.isEmpty()) {
				list.setSelectedIndex(0);
			}
			resultCount.setText(message("new.connection.type.count", visibleChoices.size(), choices.size()));
		};
		search.getDocument().addDocumentListener(new DocumentAdapter() {
			/** {@inheritDoc} 搜索时只显示名称匹配的协议。 */
			@Override
			protected void textChanged(DocumentEvent event) {
				filterChoices.run();
			}
		});
		var popup = JBPopupFactory.getInstance().createComponentPopupBuilder(content, search)
			.setTitle(message("new.connection.type.choose"))
			.setResizable(true)
			.setRequestFocus(true)
			.createPopup();
		Runnable chooseSelection = () -> {
			String title = list.getSelectedValue();
			if (Objects.isNull(title)) {
				return;
			}
			popup.cancel();
			Enum<?> type = typesByTitle.get(title);
			if (type instanceof DbType jdbcType) {
				editConnectionSettings(jdbcType, null);
			} else if (type instanceof NoSqlSourceType noSqlType) {
				editNoSqlSource(noSqlType, null, null);
			}
		};
		var chooseAction = new AbstractAction() {
			/** {@inheritDoc} 使用当前列表选择项打开对应连接表单。 */
			@Override
			public void actionPerformed(ActionEvent event) {
				chooseSelection.run();
			}
		};
		var cancelAction = new AbstractAction() {
			/** {@inheritDoc} 取消类型选择且不打开连接表单。 */
			@Override
			public void actionPerformed(ActionEvent event) {
				popup.cancel();
			}
		};
		var focusListAction = new AbstractAction() {
			/** {@inheritDoc} 将搜索框方向键交给结果列表继续选择。 */
			@Override
			public void actionPerformed(ActionEvent event) {
				list.requestFocusInWindow();
			}
		};
		for (JComponent component : List.of(search, list)) {
			component.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
				"choose.database.type");
			component.getActionMap().put("choose.database.type", chooseAction);
			component.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
				"cancel.database.type.selection");
			component.getActionMap().put("cancel.database.type.selection", cancelAction);
		}
		search.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0),
			"focus.database.type.list");
		search.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0),
			"focus.database.type.list");
		search.getActionMap().put("focus.database.type.list", focusListAction);
		list.addMouseListener(new MouseAdapter() {
			/** {@inheritDoc} 双击候选协议时直接进入对应设置表单。 */
			@Override
			public void mouseClicked(MouseEvent event) {
				if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
					chooseSelection.run();
				}
			}
		});
		filterChoices.run();
		popup.showUnderneathOf(anchor);
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
	 * 保存连接后刷新统一数据源列表，并定位到此次保存的节点。
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
			for (int index = 0; index < objectRoot.getChildCount(); index++) {
				var source = (DefaultMutableTreeNode) objectRoot.getChildAt(index);
				if (profile.equals(source.getUserObject())) {
					var path = new TreePath(source.getPath());
					objectTree.setSelectionPath(path);
					objectTree.scrollPathToVisible(path);
					break;
				}
			}
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
				case 3 -> Objects.isNull(objectName) ? null : ((DefaultMutableTreeNode) node.getParent()).getUserObject();
				default -> null;
			};
			String groupName = group instanceof String name ? name : "";
			switch (profile.type()) {
				case REDIS -> redisPanel.openSourceProfile(profile, secret, groupName, objectName,
					group instanceof Integer index ? index : profile.logicalDatabase());
				case MONGODB -> mongoPanel.openSourceProfile(profile, secret, groupName, selectedName);
				case ELASTICSEARCH -> elasticsearchPanel.openSourceProfile(profile, secret, selectedName);
			}
			openNoSqlProfile = profile;
			consoleLayout.show(consoleWorkspace, profile.type().name());
			consoleWorkspace.setVisible(true);
		}
	}

	/**
	 * 配置对话框保存本机凭据或证书设置后刷新当前右侧 ES 控制台；未变化时保留当前页面状态。
	 *
	 * @param profileId 已保存配置的稳定标识
	 */
	private void refreshOpenElasticsearchConnectionSettings(UUID profileId) {
		if (Objects.isNull(openNoSqlProfile) || openNoSqlProfile.type() != NoSqlSourceType.ELASTICSEARCH
			|| !openNoSqlProfile.id().equals(profileId)) {
			return;
		}
		NoSqlConnectionProfile latestProfile = workbenchConnectionProfiles.listNoSqlProfiles().stream()
			.filter(profile -> profile.id().equals(profileId) && profile.type() == NoSqlSourceType.ELASTICSEARCH)
			.findFirst().orElse(null);
		if (Objects.isNull(latestProfile)) {
			return;
		}
		String secret = WorkbenchNoSqlSourceEditor.readNoSqlSecret(latestProfile);
		elasticsearchPanel.refreshSavedConnectionIfChanged(latestProfile, secret);
		openNoSqlProfile = latestProfile;
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
		addDatabaseMenuItem(menu, message("collapse.data.sources"), () -> collapseDatabaseSources(objectTree));
		JdbcConnectionProfile jdbc = selectedConnectionProfile();
		if (Objects.nonNull(jdbc) || Objects.nonNull(selectedNoSqlProfile())) {
			menu.addSeparator();
			addDatabaseMenuItem(menu, message("connection.settings"), this::editSelectedDataSource);
			addDatabaseMenuItem(menu, message("refresh.database.objects"), this::refreshSelectedSource);
			addSourceBrowseScopeAction(menu);
			addDatabaseMenuItem(menu, message("query.console"), this::openSelectedQueryConsole);
			if (Objects.nonNull(jdbc)) {
				addDatabaseMenuItem(menu, message("test.connection"), this::testCurrentJdbcConnection);
				if (Objects.isNull(selectedJdbcTable())) {
					addBatchMyBatisMenuItem(menu);
				}
			}
		}
		if (Objects.nonNull(selectedJdbcTable())) {
			menu.addSeparator();
			addTableMenuItems(menu);
		}
		menu.show(anchor, 0, anchor.getHeight());
	}

	/**
	 * 收起所有数据源分支，保留已加载的对象及当前数据源选择。
	 * @param objectTree 隐藏根节点的数据源树，由 EDT 操作
	 */
	private static void collapseDatabaseSources(JTree objectTree) {
		TreeModel model = objectTree.getModel();
		Object root = model.getRoot();
		TreePath selected = objectTree.getSelectionPath();
		TreePath selectedSource = Objects.nonNull(selected) && selected.getPathCount() > 1
			? new TreePath(new Object[]{root, selected.getPathComponent(1)}) : null;
		for (int index = 0; index < model.getChildCount(root); index++) {
			objectTree.collapsePath(new TreePath(new Object[]{root, model.getChild(root, index)}));
		}
		if (Objects.nonNull(selectedSource)) {
			objectTree.setSelectionPath(selectedSource);
			objectTree.scrollPathToVisible(selectedSource);
		} else {
			objectTree.scrollRowToVisible(0);
		}
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
			addSourceBrowseScopeAction(menu);
			addDatabaseMenuItem(menu, message("query.console"), this::openSelectedQueryConsole);
			if (node.getUserObject() instanceof JdbcConnectionProfile) {
				addBatchMyBatisMenuItem(menu);
			}
			addDatabaseMenuItem(menu, message("remove.data.source"), () -> removeDataSource(node.getUserObject()));
		} else if (objectTree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode node
			&& node.getUserObject() instanceof WorkbenchNoSqlNavigator.RedisScanContinuation) {
			addDatabaseMenuItem(menu, message("next.page"), () -> workbenchNoSqlNavigator.loadNextRedisPage(node));
		} else if (Objects.nonNull(selectedJdbcTable())) {
			addSourceBrowseScopeAction(menu);
			if (!resolveSelectedConsoleNamespace(selectedConnectionProfile()).isEmpty()) {
				addDatabaseMenuItem(menu, message("query.console"), this::openSelectedQueryConsole);
			}
			addTableMenuItems(menu);
		} else if (Objects.nonNull(selectedConnectionProfile())
			&& !resolveSelectedConsoleNamespace(selectedConnectionProfile()).isEmpty()) {
			addDatabaseMenuItem(menu, message("query.console"), this::openSelectedQueryConsole);
		} else if (Objects.nonNull(selectedNoSqlProfile())) {
			addSourceBrowseScopeAction(menu);
			addDatabaseMenuItem(menu, message("refresh.database.objects"), this::refreshSelectedSource);
			addDatabaseMenuItem(menu, message("query.console"), this::openSelectedQueryConsole);
		}
		if (menu.getComponentCount() > 0) {
			menu.show(objectTree, x, y);
		}
	}

	/**
	 * 确认后移除保存配置、本机凭据和浏览范围。
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
				WorkbenchDatabaseCatalogs.clearNamespaceSelection(jdbc.id());
				PasswordSafe.getInstance().set(credentialAttributes(jdbc), null);
				PropertiesComponent.getInstance().unsetValue(jdbcCertificateVerificationKey(jdbc.id()));
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
				WorkbenchDatabaseCatalogs.clearNamespaceSelection(noSql.id());
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
		addBatchMyBatisMenuItem(menu);
		addDatabaseMenuItem(menu, message("import.csv"), this::importCsvToSelectedTable);
	}

	/**
	 * 在有多个已浏览对象且宿主启用 Java 时提供批量生成入口。
	 *
	 * @param menu 数据源或表的菜单
	 */
	private void addBatchMyBatisMenuItem(JPopupMenu menu) {
		if (browsedTables.size() > 1 && WorkbenchDatabaseToolWindow.isJavaAvailable()) {
			addDatabaseMenuItem(menu, message("generate.mybatis.for.tables"), this::chooseTablesForMyBatisGeneration);
		}
	}

	/**
	 * 从当前树已浏览的对象中选择多张表，在同一 JDBC 连接读取生成快照。
	 * 数据源在选择期间变化时取消，读取任一表失败时不打开生成窗口。
	 */
	private void chooseTablesForMyBatisGeneration() {
		JdbcConnectionProfile source = selectedConnectionProfile();
		if (!isCurrentJdbcSource(source)) {
			return;
		}
		var choices = new CheckBoxList<JdbcTable>();
		choices.setItems(browsedTables, WorkbenchDatabasePanel::qualifiedImportTable);
		// 键盘搜索只移动当前行，已勾选的表不受影响。
		ListSpeedSearch.installOn(choices, JCheckBox::getText);
		choices.setVisibleRowCount(12);
		JdbcTable initial = selectedJdbcTable();
		if (Objects.nonNull(initial)) {
			choices.setItemSelected(initial, true);
		}
		int response = JOptionPane.showConfirmDialog(this, new JBScrollPane(choices),
			message("generate.mybatis.for.tables"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (response != JOptionPane.OK_OPTION || !isCurrentJdbcSource(source)) {
			return;
		}
		List<JdbcTable> selected = choices.getCheckedItems();
		if (selected.isEmpty()) {
			status.setText(message("native.generator.select.tables"));
			return;
		}
		if (!browsedTables.containsAll(selected)) {
			status.setText(message("data.source.changed"));
			return;
		}
		runDatabaseOperation(message("read.table.structure"), database -> {
			var metadata = database.connection().getMetaData();
			var generationTables = new ArrayList<GenerationTable>(selected.size());
			for (JdbcTable table : selected) {
				generationTables.add(JdbcTableMetadata.readGenerationTable(metadata, source.dbType(), table));
			}
			return generationTables;
		}, tables -> new MybatisGeneratorMainUI(project, tables));
	}

	/**
	 * @param type 数据源的数据库类型
	 * @return 新建菜单、数据源树与连接设置共用的 16 像素引擎标识
	 * @see <a href="https://github.com/devicons/devicon/tree/v2.17.0/icons">Devicon 数据库图标</a>
	 */
	static Icon databaseSourceIcon(Enum<?> type) {
		return IconLoader.getIcon("/icons/database/" + type.name() + ".svg", WorkbenchDatabasePanel.class);
	}

	/**
	 * 向当前菜单添加可点击的工作台操作。
	 *
	 * @param menu 目标菜单
	 * @param label 当前 IDE 语言下显示的标题
	 * @param operation 点击时执行的操作
	 * @return 已加入菜单的可配置菜单项
	 */
	private JMenuItem addDatabaseMenuItem(JPopupMenu menu, String label, Runnable operation) {
		JMenuItem item = new JMenuItem(label);
		item.addActionListener(event -> operation.run());
		menu.add(item);
		return item;
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
			PropertiesComponent.getInstance().setValue(jdbcCertificateVerificationKey(profile.id()),
				verifyJdbcCertificate.isSelected(), true);
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
		setSqlConsoleNamespace("");
		profileName.setText(profile.name());
		databaseType.setSelectedItem(profile.dbType());
		jdbcUrl.setText(profile.url());
		username.setText(profile.username());
		driverCoordinates.setText(profile.coordinates());
		driverClass.setText(profile.driverClass());
		repositoryUrl.setText(profile.repository());
		Credentials credentials = useStoredPassword ? PasswordSafe.getInstance().get(credentialAttributes(profile)) : null;
		password.setText(Objects.isNull(credentials) ? "" : credentials.getPasswordAsString());
		verifyJdbcCertificate.setSelected(PropertiesComponent.getInstance()
			.getBoolean(jdbcCertificateVerificationKey(profile.id()), true));
		browsedTables = tablesBySource.getOrDefault(profile.id(), List.of());
		status.setText(message("opened") + profile.name());
	}

	/**
	 * 按配置及连接身份绑定密码，替换地址或账号后不会自动沿用旧密码。
	 *
	 * @param profile 当前连接配置
	 * @return 仅用于本机 PasswordSafe 的凭据键
	 */
	private static CredentialAttributes credentialAttributes(JdbcConnectionProfile profile) {
		String identity = profile.id() + "\n" + profile.url() + "\n" + profile.username();
		UUID key = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
		return new CredentialAttributes("MyBatis Workbench JDBC " + key, profile.username());
	}

	/**
	 * 返回只保存在当前 IDE 本机的 JDBC 证书校验设置键。
	 *
	 * @param profileId JDBC 连接标识
	 * @return 本机属性键
	 */
	private static String jdbcCertificateVerificationKey(UUID profileId) {
		return "mybatis.workbench.jdbc.verify-certificate." + profileId;
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
		if (objectRoot.getChildCount() > 0) {
			// 空树的隐藏根默认折叠。只展开根，数据源子节点依然按需加载。
			objectTree.expandPath(new TreePath(objectRoot.getPath()));
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
		boolean groupBySchema = targetSource.getUserObject() instanceof JdbcConnectionProfile profile
			&& usesJdbcSchemas(profile.dbType());
		if (targetSource.getUserObject() instanceof JdbcConnectionProfile source
			&& (groupBySchema || usesJdbcDatabaseCatalogs(source.dbType()))) {
			// PostgreSQL 和 H2 的单个连接限定在一个数据库内，显示和分组均使用模式名。
			for (String namespace : WorkbenchDatabaseCatalogs.selectedDatabaseNames(source.id(), List.of())) {
				DefaultMutableTreeNode group = new DefaultMutableTreeNode(namespace);
				schemas.put(groupBySchema ? List.of("", namespace) : List.of(namespace, ""), group);
				targetSource.add(group);
			}
		}
		for (JdbcTable table : tables) {
			String catalog = groupBySchema ? "" : Objects.toString(table.catalog(), "");
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
		Path file = chooseConfirmedCsvImport(this, target);
		if (Objects.isNull(file) || !isCurrentJdbcSource(source) || !target.equals(selectedJdbcTable())) {
			return;
		}
		runDatabaseOperation(message("import.csv"), connection ->
				JdbcCsvImport.importCsvRows(connection.connection(), source.dbType(), target, file, WorkbenchBundle::message),
			count -> status.setText(message("imported") + count + message("rows.into") + qualifiedImportTable(target)));
	}

	/**
	 * 在 EDT 选择 CSV 文件并确认追加目标。调用方在返回后校验连接状态。
	 *
	 * @param parent 对话框归属组件
	 * @param target 固定的目标表
	 * @return 已确认的输入文件，取消时为空
	 */
	static Path chooseConfirmedCsvImport(JComponent parent, JdbcTable target) {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle(message("select.a.utf.8.csv.with.a.header"));
		if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) {
			return null;
		}
		Path file = chooser.getSelectedFile().toPath();
		int choice = JOptionPane.showConfirmDialog(parent,
			message("append.data.from") + file.getFileName() + message("to") + qualifiedImportTable(target) + "？",
			message("confirm.write.to.target.table"), JOptionPane.OK_CANCEL_OPTION);
		return choice == JOptionPane.OK_OPTION ? file : null;
	}

	/**
	 * @param target 追加目标
	 * @return 用于确认和结果反馈的限定表名
	 */
	static String qualifiedImportTable(JdbcTable target) {
		return String.join(".", Stream.of(target.catalog(), target.schema(), target.name())
			.filter(Objects::nonNull).filter(name -> !name.isEmpty()).toList());
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
		JdbcConnectionProfile targetSource = selectedConnectionProfile();
		if (!isCurrentJdbcSource(targetSource)) {
			return;
		}
		DbType type = targetSource.dbType();
		List<JdbcConnectionProfile> others = workbenchConnectionProfiles.listProfiles().stream()
			.filter(profile -> profile.dbType() == type && !profile.id().equals(profileId)).toList();
		if (others.isEmpty()) {
			compareWithReferenceTable(targetSource, target, null, browsedTables);
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
		if (index < 0 || !isCurrentComparisonTarget(targetSource, target)) {
			return;
		}
		if (index == 0) {
			compareWithReferenceTable(targetSource, target, null, browsedTables);
		} else {
			JdbcConnectionProfile reference = others.get(index - 1);
			if (!workbenchConnectionProfiles.listProfiles().contains(reference)) {
				status.setText(message("data.source.changed"));
				return;
			}
			runDatabaseOperation(message("comparison.loading.reference"), database -> {
				try (JdbcDriverConnection source = openSavedJdbcSource(reference)) {
					var connection = source.connection();
					return listVisibleJdbcTables(connection, reference);
				}
			}, tables -> compareWithReferenceTable(targetSource, target, reference, tables));
		}
	}

	/**
	 * 列出可用参考表并比较选定对象。同一连接下排除目标表本身。
	 *
	 * @param targetSource 发起比较时的目标连接配置
	 * @param target    对象树所选目标表
	 * @param reference 另一数据源配置，使用当前连接时为空
	 * @param tables    参考连接中可见的表
	 */
	private void compareWithReferenceTable(JdbcConnectionProfile targetSource, JdbcTable target, JdbcConnectionProfile reference,
										   List<JdbcTable> tables) {
		if (!isCurrentComparisonTarget(targetSource, target)) {
			return;
		}
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
		if (index < 0 || !isCurrentComparisonTarget(targetSource, target)) {
			return;
		}
		if (Objects.nonNull(reference) && !workbenchConnectionProfiles.listProfiles().contains(reference)) {
			status.setText(message("data.source.changed"));
			return;
		}
		JdbcTable source = candidates.get(index);
		DbType type = targetSource.dbType();
		runDatabaseOperation(message("compare.table.structure"), database -> {
			if (Objects.isNull(reference)) {
				return JdbcTableComparer.compareTableStructure(database.connection(), source,
					database.connection(), target, type, WorkbenchBundle::message);
			}
			try (JdbcDriverConnection referenceConnection = openSavedJdbcSource(reference)) {
				return JdbcTableComparer.compareTableStructure(referenceConnection.connection(), source,
					database.connection(), target, type, WorkbenchBundle::message);
			}
		}, differences -> {
			if (!isCurrentComparisonTarget(targetSource, target)) {
				return;
			}
			if (Objects.nonNull(reference) && !workbenchConnectionProfiles.listProfiles().contains(reference)) {
				status.setText(message("data.source.changed"));
				return;
			}
			showComparisonResult(source, target, reference, differences);
		});
	}

	/**
	 * 在比较对话框及异步查询返回时确认目标连接和选中表仍是最初的目标。
	 *
	 * @param source 发起比较时的目标连接
	 * @param target 发起比较时的目标表
	 * @return 仍可使用该目标时为 true
	 */
	private boolean isCurrentComparisonTarget(JdbcConnectionProfile source, JdbcTable target) {
		if (!isCurrentJdbcSource(source)) {
			return false;
		}
		if (!target.equals(selectedJdbcTable())) {
			status.setText(message("select.a.table.to.receive.the.schema.changes"));
			return false;
		}
		return true;
	}

	/**
	 * 在后台打开独立 JDBC 连接，账号密码从该配置对应的 PasswordSafe 读取。
	 *
	 * @param profile 已保存的 JDBC 连接
	 * @return 当前任务需要关闭的连接
	 * @throws Exception 驱动解析或数据库连接失败
	 */
	static JdbcDriverConnection openSavedJdbcSource(JdbcConnectionProfile profile) throws Exception {
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
			boolean verifyCertificate = PropertiesComponent.getInstance()
				.getBoolean(jdbcCertificateVerificationKey(profile.id()), true);
			return new JdbcDriverConnection(jars, profile.driverClass(), profile.url(), login,
				profile.dbType(), verifyCertificate);
		} finally {
			login.clear();
		}
	}

	/**
	 * 生成当前本机 JDBC 会话配置的短期比较标识，覆盖共享配置、PasswordSafe 凭据和证书校验开关。
	 * 密码只参与本地摘要运算，不写入文件、属性或日志；调用方仅用结果判断只读连接能否复用。
	 *
	 * @param profile 已保存的数据源配置
	 * @return SHA-256 配置指纹，不含可逆凭据
	 * @throws NoSuchAlgorithmException 当前 JRE 不提供必需的 SHA-256
	 */
	static String savedJdbcConnectionFingerprint(JdbcConnectionProfile profile) throws NoSuchAlgorithmException {
		Credentials credentials = PasswordSafe.getInstance().get(credentialAttributes(profile));
		var oneTimePassword = Objects.isNull(credentials) ? null : credentials.getPassword();
		char[] secret = Objects.isNull(oneTimePassword) ? new char[0] : oneTimePassword.toCharArray(false);
		if (Objects.isNull(secret)) {
			secret = new char[0];
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(profile.toString().getBytes(StandardCharsets.UTF_8));
			digest.update((byte) (PropertiesComponent.getInstance()
				.getBoolean(jdbcCertificateVerificationKey(profile.id()), true) ? 1 : 0));
			for (char value : secret) {
				digest.update((byte) (value >>> 8));
				digest.update((byte) value);
			}
			return HexFormat.of().formatHex(digest.digest());
		} finally {
			Arrays.fill(secret, '\0');
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
			text += message("compare.migration.draft.for.review") + draft;
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
			actions.add(databaseButton(message("compare.open.migration.sql"), () -> {
				Window dialog = SwingUtilities.getWindowAncestor(result);
				if (Objects.nonNull(dialog)) {
					dialog.dispose();
				}
				openReadOnlySqlPreview(message("compare.migration.sql.tab", target.name()), draft);
			}));
			actions.add(databaseButton(message("compare.save.migration.sql"), () -> saveMigrationSqlDraft(result, draft)));
			result.add(actions, BorderLayout.SOUTH);
		}
		String sourceName = Objects.isNull(reference) ? source.name() : reference.name() + " / " + source.name();
		JOptionPane.showMessageDialog(this, result, message("reference") + sourceName + message("target") + target.name(),
			JOptionPane.PLAIN_MESSAGE);
	}

	/**
	 * 在后台将已展示的草案写为 UTF-8 文件，并在比较对话框中反馈保存结果。
	 * 通过独占创建保护已有文件，选择器取消时不写文件。
	 *
	 * @param parent 比较结果对话框的内容组件
	 * @param draft 已生成的迁移 SQL 草案
	 */
	private void saveMigrationSqlDraft(JComponent parent, String draft) {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle(message("compare.save.migration.sql"));
		chooser.setSelectedFile(chooser.getCurrentDirectory().toPath().resolve("migration.sql").toFile());
		if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		Path file = chooser.getSelectedFile().toPath();
		ApplicationManager.getApplication().executeOnPooledThread(() -> {
			String outcome;
			int messageType;
			try {
				Files.writeString(file, draft, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
				outcome = message("compare.migration.sql.saved", file);
				messageType = JOptionPane.INFORMATION_MESSAGE;
			} catch (IOException failure) {
				log.warn("保存迁移 SQL 草案失败，阶段=写入，目标文件={}", file, failure);
				outcome = failure instanceof FileAlreadyExistsException
					? message("compare.migration.sql.file.exists", file)
					: message("compare.migration.sql.save.failed", failure.getMessage());
				messageType = JOptionPane.ERROR_MESSAGE;
			}
			String feedback = outcome;
			int feedbackType = messageType;
			SwingUtilities.invokeLater(() -> {
				if (!project.isDisposed()) {
					JOptionPane.showMessageDialog(parent.isShowing() ? parent : this, feedback,
						message("compare.save.migration.sql"), feedbackType);
				}
			});
		});
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
			ddl -> openReadOnlySqlPreview(table.name() + " (DDL).sql", ddl));
	}

	/**
	 * 在主编辑区打开只读 SQL 预览，宿主缺少 SQL 语言支持时使用纯文本。
	 *
	 * @param name 编辑器标签页名称
	 * @param content 要审阅的 DDL 或迁移草案
	 */
	private void openReadOnlySqlPreview(String name, String content) {
		Language sql = Language.findLanguageByID("SQL");
		LightVirtualFile definition = Objects.isNull(sql)
			? new LightVirtualFile(name, PlainTextFileType.INSTANCE, content) : new LightVirtualFile(name, sql, content);
		definition.setWritable(false);
		new OpenFileDescriptor(project, definition).navigate(true);
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
		JdbcConnectionProfile source = selectedConnectionProfile();
		runDatabaseOperation(message("browse.table.data"),
			connection -> JdbcTableEditor.previewTableRows(connection, type, table, WorkbenchBundle::message),
			page -> {
				String title = table.name() + " [" + source.name() + "]";
				var file = WorkbenchTablePreviewEditorProvider.createTablePreviewFile(title, source, page);
				new OpenFileDescriptor(project, file).navigate(true);
			});
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
		insertTableRowButton.setEnabled(!page.insertableColumns().isEmpty());
		deleteTableRowButton.setEnabled(!page.primaryKeys().isEmpty());
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
		confirmTableCellValue(this, page.table().name(), columnName, previous, value ->
			saveSelectedTableCell(source, page, modelRow, modelColumn, columnName, previous, value));
	}

	/**
	 * 提示用户确认新值后通知调用方。SQL NULL 以空引用交给保存回调。
	 *
	 * @param parent 对话框所在组件
	 * @param table 当前表名
	 * @param column 当前列名
	 * @param previous 当前快照保存的旧值
	 * @param confirmed 接收确认值的回调
	 */
	static void confirmTableCellValue(Component parent, String table, String column, String previous,
									  Consumer<String> confirmed) {
		JTextArea input = new JTextArea(Objects.toString(previous, ""), 4, 28);
		JCheckBox writeNull = new JCheckBox(message("set.sql.null"), Objects.isNull(previous));
		input.setEnabled(!writeNull.isSelected());
		writeNull.addActionListener(event -> input.setEnabled(!writeNull.isSelected()));
		JPanel form = new JPanel(new GridLayout(0, 1, 4, 4));
		form.add(new JLabel(message("update") + table + "." + column));
		form.add(new JBScrollPane(input));
		form.add(writeNull);
		int choice = JOptionPane.showConfirmDialog(parent, form, message("save.cell"), JOptionPane.OK_CANCEL_OPTION);
		if (choice != JOptionPane.OK_OPTION) {
			return;
		}
		String value = writeNull.isSelected() ? null : input.getText();
		confirmed.accept(value);
	}

	/**
	 * 确认源和页仍是用户正在编辑的对象后保存，成功才更新本地网格。
	 *
	 * @param source 打开确认框时的 JDBC 源
	 * @param page 打开确认框时的数据页
	 * @param modelRow 数据模型行号
	 * @param modelColumn 数据模型列号
	 * @param columnName 原始数据库列名
	 * @param previous 数据库旧值
	 * @param value 用户确认的新值
	 */
	private void saveSelectedTableCell(JdbcConnectionProfile source, JdbcTableRows page, int modelRow, int modelColumn,
									   String columnName, String previous, String value) {
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
			currentTableRows = page.withStoredCell(modelRow, modelColumn, storedValue);
			currentQueryResult = currentTableRows.result();
			resultTable.getModel().setValueAt(storedValue, modelRow, modelColumn);
			status.setText(message("table.cell.saved"));
		});
	}

	/**
	 * 按列录入后新增当前表的一行，并在保存前重新核对当前连接与表。
	 */
	private void addVisibleTableRow() {
		JdbcTableRows page = currentTableRows;
		JdbcConnectionProfile source = selectedConnectionProfile();
		if (Objects.isNull(page) || Objects.isNull(source) || page.insertableColumns().isEmpty()) {
			status.setText(message("table.row.insert.unavailable"));
			return;
		}
		var columns = page.result().columnLabels().stream().filter(page.insertableColumns()::contains).toList();
		String json = promptTableRowValues(this, columns, status::setText);
		if (Objects.isNull(json) || !isCurrentJdbcSource(source) || currentTableRows != page
			|| !page.table().equals(selectedJdbcTable())) {
			return;
		}
		DbType type = source.dbType();
		runDatabaseOperation(message("insert.table.row"), database -> {
			JdbcTableEditor.insertTableRow(database.connection(), type, page, json, WorkbenchBundle::message);
			return JdbcTableEditor.previewTableRows(database, type, page.table(), page.queryOptions(), WorkbenchBundle::message);
		}, refreshed -> {
			if (isCurrentJdbcSource(source) && Objects.equals(selectedJdbcTable(), page.table())) {
				showTableRows(refreshed);
				status.setText(message("table.row.inserted"));
			}
		});
	}

	/**
	 * 展示两个表格入口共用的逐列录入对话框，保留默认值、空文本和 SQL NULL 的区别。
	 *
	 * @param parent 对话框所属组件
	 * @param columns 当前表允许新增的列，按表格顺序排列
	 * @param updateStatus 提交时未选择任何列的提示回调
	 * @return 供 JDBC 写入校验的 JSON，取消或没有提交任何列时返回 null
	 */
	static String promptTableRowValues(JComponent parent, List<String> columns, Consumer<String> updateStatus) {
		JPanel rows = new JPanel(new GridLayout(0, 3, 6, 4));
		List<JComboBox<String>> modes = new ArrayList<>(columns.size());
		List<JTextField> values = new ArrayList<>(columns.size());
		for (String column : columns) {
			var mode = new JComboBox<>(new String[]{message("table.row.insert.default"),
				message("table.row.insert.value"), message("set.sql.null")});
			var value = new JTextField(24);
			value.setEnabled(false);
			mode.addActionListener(event -> value.setEnabled(mode.getSelectedIndex() == 1));
			var label = new JLabel(column);
			label.setLabelFor(value);
			mode.getAccessibleContext().setAccessibleName(column);
			rows.add(label);
			rows.add(mode);
			rows.add(value);
			modes.add(mode);
			values.add(value);
		}
		var scroll = new JBScrollPane();
		var fields = new JPanel(new BorderLayout());
		fields.add(rows, BorderLayout.NORTH);
		scroll.setViewportView(fields);
		var preferred = rows.getPreferredSize();
		preferred.height = Math.min(preferred.height + 8, 320);
		preferred.width += 24;
		scroll.setPreferredSize(preferred);
		JPanel form = new JPanel(new BorderLayout(4, 4));
		form.add(new JLabel(message("table.row.insert.fields.help")), BorderLayout.NORTH);
		form.add(scroll, BorderLayout.CENTER);
		if (JOptionPane.showConfirmDialog(parent, form, message("insert.table.row"), JOptionPane.OK_CANCEL_OPTION,
			JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
			return null;
		}
		JsonObject row = new JsonObject();
		for (int i = 0; i < columns.size(); i++) {
			int mode = modes.get(i).getSelectedIndex();
			if (mode == 1) {
				row.addProperty(columns.get(i), values.get(i).getText());
			} else if (mode == 2) {
				row.add(columns.get(i), JsonNull.INSTANCE);
			}
		}
		if (row.size() == 0) {
			updateStatus.accept(message("table.row.insert.fields.help"));
			return null;
		}
		return row.toString();
	}

	/**
	 * 删除结果表中选择的单行。写入前再次核对完整标量行快照和原主键。
	 */
	private void deleteSelectedTableRow() {
		JdbcTableRows page = currentTableRows;
		JdbcConnectionProfile source = selectedConnectionProfile();
		int viewRow = resultTable.getSelectedRow();
		if (Objects.isNull(page) || Objects.isNull(source) || viewRow < 0) {
			status.setText(message("select.a.row.to.delete"));
			return;
		}
		int row = resultTable.convertRowIndexToModel(viewRow);
		List<String> values = page.result().rows().get(row);
		String keys = page.primaryKeys().stream().map(key -> key + "="
			+ Objects.toString(values.get(page.result().columnLabels().indexOf(key)), "NULL")).toList().toString();
		if (JOptionPane.showConfirmDialog(this, message("table.row.delete.confirm", page.table().name(), keys),
			message("delete.selected.table.row"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
			!= JOptionPane.YES_OPTION) {
			return;
		}
		if (!isCurrentJdbcSource(source) || currentTableRows != page || !page.table().equals(selectedJdbcTable())) {
			return;
		}
		DbType type = source.dbType();
		runDatabaseOperation(message("delete.selected.table.row"), database -> {
			JdbcTableEditor.deleteTableRow(database.connection(), type, page, row, WorkbenchBundle::message);
			return JdbcTableEditor.previewTableRows(database, type, page.table(), page.queryOptions(), WorkbenchBundle::message);
		}, refreshed -> {
			if (isCurrentJdbcSource(source) && Objects.equals(selectedJdbcTable(), page.table())) {
				showTableRows(refreshed);
				status.setText(message("table.row.deleted"));
			}
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
		insertTableRowButton.setEnabled(false);
		deleteTableRowButton.setEnabled(false);
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
		applyQueryResultFilter();
	}

	/**
	 * 按控制台的库或模式解析别名或已选表并按需读取列。
	 */
	private void requestSqlCompletion() {
		String sql = sqlEditor.getText();
		int caret = sqlEditor.getCaretPosition();
		DbType type = databaseType.getItemAt(databaseType.getSelectedIndex());
		JdbcTable selectedTable = selectedJdbcTable();
		JdbcTable table = JdbcSqlCompletion.resolveSqlCompletionTable(type, sql, caret, browsedTables, selectedTable,
			sqlConsoleNamespace.getText());
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
		DbType type = databaseType.getItemAt(databaseType.getSelectedIndex());
		String selectedSql = sqlEditor.getSelectedText();
		String sql;
		if (Objects.nonNull(selectedSql)) {
			sql = selectedSql;
		} else {
			try {
				sql = JdbcSqlStatementLocator.locateAtCaret(type, sqlEditor.getText(), sqlEditor.getCaretPosition());
			} catch (IllegalArgumentException failure) {
				status.setText(message("sql.statement.cursor.ambiguous"));
				return;
			}
		}
		if (sql.isBlank()) {
			status.setText(message("enter.sql"));
			return;
		}
		String namespace = sqlConsoleNamespace.getText();
		if (Objects.nonNull(databaseTask)) {
			return;
		}
		Object sqlOperation = explain ? null : new Object();
		if (Objects.nonNull(sqlOperation)) {
			activeSqlOperation = sqlOperation;
			cancelledSqlOperation = null;
			cancelSqlButton.setEnabled(true);
		}
		runDatabaseOperation(explain ? message("read.estimated.plan") : message("run.sql"), connection -> {
			if (Objects.nonNull(sqlOperation)) {
				activeSqlConnection = connection;
			}
			try {
				if (Objects.nonNull(sqlOperation) && cancelledSqlOperation == sqlOperation) {
					connection.cancelCurrentStatement();
				}
				if (!namespace.isEmpty()) {
					if (usesJdbcSchemas(type)) {
						connection.connection().setSchema(namespace);
					} else {
						connection.connection().setCatalog(namespace);
					}
				}
				return explain ? connection.explainSql(type, sql, 1_000, 30, WorkbenchBundle::message)
					: connection.executeSql(sql, 1_000, 30);
			} finally {
				if (Objects.nonNull(sqlOperation) && activeSqlOperation == sqlOperation) {
					activeSqlConnection = null;
				}
			}
		}, this::showSqlResult);
		if (Objects.nonNull(sqlOperation) && Objects.isNull(databaseTask)) {
			cancelSqlButton.setEnabled(false);
			activeSqlOperation = null;
		}
	}

	/** 将用户取消动作转发给当前 JDBC Statement，不以线程中断代替数据库端取消。 */
	private void cancelCurrentSqlQuery() {
		Object operation = activeSqlOperation;
		if (Objects.isNull(operation)) {
			status.setText(message("sql.query.not.running"));
			return;
		}
		cancelledSqlOperation = operation;
		cancelSqlButton.setEnabled(false);
		JdbcDriverConnection connection = activeSqlConnection;
		if (Objects.isNull(connection)) {
			status.setText(message("sql.query.cancel.requested"));
			return;
		}
		status.setText(message("sql.query.cancel.requested"));
		JdbcConnectionProfile source = selectedConnectionProfile();
		ApplicationManager.getApplication().executeOnPooledThread(() -> {
			try {
				connection.cancelCurrentStatement();
			} catch (SQLException failure) {
				log.warn("取消 SQL 查询失败，当前数据源={}", source, failure);
				ApplicationManager.getApplication().invokeLater(() -> {
					if (activeSqlOperation == operation) {
						if (cancelledSqlOperation == operation) {
							cancelledSqlOperation = null;
						}
						status.setText(message("sql.query.cancel.failed") + " " + failure.getMessage());
						cancelSqlButton.setEnabled(true);
					}
				});
			}
		});
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
		insertTableRowButton.setEnabled(false);
		deleteTableRowButton.setEnabled(false);
		currentQueryResult = result;
		exportCsv.setEnabled(!result.columnLabels().isEmpty());
		DefaultTableModel model = resultModel(result.columnLabels().toArray());
		result.rows().forEach(row -> model.addRow(row.toArray()));
		resultTable.setModel(model);
		applyQueryResultFilter();
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
		exportQueryResult(this, project, captureVisibleQueryResult(resultTable, currentQueryResult), status::setText);
	}

	/**
	 * 将结果网格当前可见的排序和筛选视图复制为独立导出快照。
	 *
	 * @param table 当前显示结果的网格
	 * @param result 原始 JDBC 结果及截断状态
	 * @return 按网格当前列与行顺序构建的结果；输入为空时返回空
	 */
	static JdbcQueryResult captureVisibleQueryResult(JTable table, JdbcQueryResult result) {
		if (Objects.isNull(result) || result.columnLabels().isEmpty()) {
			return result;
		}
		List<String> columns = new ArrayList<>(table.getColumnCount());
		for (int column = 0; column < table.getColumnCount(); column++) {
			columns.add(table.getColumnName(column));
		}
		List<List<String>> rows = new ArrayList<>(table.getRowCount());
		for (int row = 0; row < table.getRowCount(); row++) {
			List<String> values = new ArrayList<>(columns.size());
			for (int column = 0; column < columns.size(); column++) {
				values.add((String) table.getValueAt(row, column));
			}
			rows.add(Collections.unmodifiableList(values));
		}
		return new JdbcQueryResult(List.copyOf(columns), List.copyOf(rows), result.truncated(), result.updateCount());
	}

	/**
	 * 把文本条件应用到当前已经加载的结果行。此筛选在客户端执行，保持数据库行数上限不变。
	 */
	private void applyQueryResultFilter() {
		if (!(resultTable.getRowSorter() instanceof TableRowSorter<?> sorter)) {
			return;
		}
		String filter = resultFilter.getText().strip();
		sorter.setRowFilter(filter.isEmpty() ? null : RowFilter.regexFilter("(?iu)" + Pattern.quote(filter)));
	}

	/**
	 * 将调用方在 EDT 捕获的结果快照异步写入新 CSV 文件。
	 *
	 * @param parent 保存对话框的父组件
	 * @param project 状态反馈所属的项目
	 * @param result 需要导出的结果快照
	 * @param showStatus 在 EDT 接收导出结果
	 */
	static void exportQueryResult(JComponent parent, Project project, JdbcQueryResult result, Consumer<String> showStatus) {
		if (Objects.isNull(result) || result.columnLabels().isEmpty()) {
			return;
		}
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle(result.truncated() ? message("export.current.page.results.truncated") : message("export.query.results"));
		if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		Path target = chooser.getSelectedFile().toPath();
		ApplicationManager.getApplication().executeOnPooledThread(() -> {
			try {
				JdbcResultCsv.exportQueryResult(target, result);
				ApplicationManager.getApplication().invokeLater(() -> {
					if (!project.isDisposed()) {
						showStatus.accept(message("exported") + result.rows().size() + message("rows") + (result.truncated() ? message("current.page") : ""));
					}
				});
			} catch (IOException failure) {
				log.warn("导出 JDBC 查询结果失败，阶段=写入，目标文件={}，结果行数={}", target, result.rows().size(), failure);
				ApplicationManager.getApplication().invokeLater(() -> {
					if (!project.isDisposed()) {
						showStatus.accept(message("csv.export.failed") + failure.getMessage());
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
		DbType dbType = databaseType.getItemAt(databaseType.getSelectedIndex());
		boolean verifyCertificate = verifyJdbcCertificate.isSelected();
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
					try (JdbcDriverConnection connection = new JdbcDriverConnection(paths, driver, url, properties,
						dbType, verifyCertificate)) {
						return operation.apply(connection);
					}
				} finally {
					properties.clear();
				}
			}

			/** {@inheritDoc} 项目关闭或任务取消后跳过 UI 更新。 */
			@Override
			protected void done() {
				Object completedSqlOperation = activeSqlOperation;
				boolean sqlWasCancelled = Objects.nonNull(completedSqlOperation)
					&& cancelledSqlOperation == completedSqlOperation;
				activeSqlOperation = null;
				activeSqlConnection = null;
				databaseTask = null;
				cancelSqlButton.setEnabled(false);
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
					if (sqlWasCancelled) {
						status.setText(message("sql.query.cancelled"));
						return;
					}
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
			Object operation = activeSqlOperation;
			if (Objects.nonNull(operation)) {
				cancelledSqlOperation = operation;
			}
			JdbcDriverConnection connection = activeSqlConnection;
			if (Objects.nonNull(connection)) {
				ApplicationManager.getApplication().executeOnPooledThread(() -> {
					try {
						connection.cancelCurrentStatement();
					} catch (SQLException failure) {
						log.warn("关闭工作台时取消 SQL 查询失败", failure);
					}
				});
			}
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
	interface JdbcOperation<T> {
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
