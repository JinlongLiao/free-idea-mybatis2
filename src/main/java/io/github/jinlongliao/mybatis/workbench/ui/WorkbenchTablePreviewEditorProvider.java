package io.github.jinlongliao.mybatis.workbench.ui;

import static io.github.jinlongliao.mybatis.workbench.ui.WorkbenchBundle.message;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeListener;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import javax.swing.JButton;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import javax.swing.SwingUtilities;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.RowFilter;
import javax.swing.event.DocumentEvent;
import javax.swing.ScrollPaneConstants;
import javax.swing.RowSorter;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;

import io.github.jinlongliao.mybatis.workbench.database.JdbcConnectionProfile;
import io.github.jinlongliao.mybatis.workbench.database.JdbcDriverConnection;
import io.github.jinlongliao.mybatis.workbench.database.JdbcQueryResult;
import io.github.jinlongliao.mybatis.workbench.database.JdbcCsvImport;
import io.github.jinlongliao.mybatis.workbench.database.JdbcTableEditor;
import io.github.jinlongliao.mybatis.workbench.database.JdbcTableFilterOperator;
import io.github.jinlongliao.mybatis.workbench.database.JdbcTableQueryExpressionParser;
import io.github.jinlongliao.mybatis.workbench.database.JdbcTableQueryOptions;
import io.github.jinlongliao.mybatis.workbench.database.JdbcTableRows;
import io.github.jinlongliao.mybatis.workbench.model.DbType;
import io.github.jinlongliao.mybatis.workbench.setting.WorkbenchConnectionProfiles;

/**
 * 在主编辑区独立浏览表数据。MySQL 只读页查询复用本标签页的短期连接，写操作使用独立连接。
 *
 * @author liaojinlong
 * @since 2026/11/20 14:21
 */
public final class WorkbenchTablePreviewEditorProvider implements FileEditorProvider, DumbAware {
	/** 仅挂载到本插件创建的内存文件，数据不写入项目。 */
	private static final Key<JdbcTableRows> PREVIEW = Key.create("mybatis.workbench.table.preview");
	/** 标签页绑定的配置快照，翻页时校验其仍然有效。 */
	private static final Key<JdbcConnectionProfile> SOURCE = Key.create("mybatis.workbench.table.preview.source");
	/** 记录翻页失败的根因与连接标识。 */
	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	/**
	 * @param name 数据源与表限定名组成的标题
	 * @param source 加载数据时使用的配置快照
	 * @param page 已加载的有界表数据
	 * @return 可由 IDE 打开为表格编辑器的临时文件
	 */
	static VirtualFile createTablePreviewFile(String name, JdbcConnectionProfile source, JdbcTableRows page) {
		var file = new LightVirtualFile(name);
		file.setWritable(false);
		file.putUserData(PREVIEW, page);
		file.putUserData(SOURCE, source);
		return file;
	}

	/** {@inheritDoc} 仅接受由 Workbench 创建的表数据文件。 */
	@Override
	public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
		return Objects.nonNull(file.getUserData(PREVIEW));
	}

	/** {@inheritDoc} 使用快照创建独立结果表，无需再次请求数据库。 */
	@Override
	public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
		return new TablePreviewEditor(project, file, Objects.requireNonNull(file.getUserData(SOURCE)),
			Objects.requireNonNull(file.getUserData(PREVIEW)));
	}

	/** {@inheritDoc} 返回独立于 IDE 语言的编辑器类型标识。 */
	@Override
	public @NotNull String getEditorTypeId() {
		return "mybatis-workbench-table-preview";
	}

	/** {@inheritDoc} 临时文件仅显示数据表格。 */
	@Override
	public @NotNull FileEditorPolicy getPolicy() {
		return FileEditorPolicy.HIDE_DEFAULT_EDITOR;
	}

	/**
	 * 支持分页和确认编辑的数据页，关闭后随内存文件一并回收。
	 *
	 * @author liaojinlong
	 * @since 2026/11/20 14:21
	 */
	private static final class TablePreviewEditor extends UserDataHolderBase implements FileEditor {
		/** 文件编辑器归属的临时快照文件。 */
		private final VirtualFile file;
		/** 带横向滚动和页范围提示的编辑器容器。 */
		private final JPanel content = new JPanel(new BorderLayout());
		/** 网格接收焦点，修改经单独确认后提交。 */
		private final JBTable table = new JBTable();

		/** 在已加载的单页中按字面文本筛选，无需额外查询。 */
		private final JTextField pageFilter = new JTextField(15);
		/** 有界 AND 数据库 WHERE 条件输入；解析后的列名白名单和参数值由 JDBC 查询层分别处理。 */
		private final JTextField databaseWhereExpression = new JTextField(24);
		/** 单列数据库端 ORDER BY 输入，只接受列名和可选 ASC/DESC。 */
		private final JTextField databaseOrderByExpression = new JTextField(18);
		/** 应用数据库端筛选和排序，并从首页重新读取。 */
		private final JButton applyDatabaseQuery = new JButton(message("apply.database.filter.sort"));
		/** 移除数据库端筛选和排序，恢复有界主键首页。 */
		private final JButton clearDatabaseQuery = new JButton(message("clear.database.filter.sort"));
		/** 显示本页实际生效的数据库筛选和排序条件。 */
		private final JLabel databaseQuerySummary = new JLabel();
		/** 打开标签页时使用的连接配置。 */
		private final JdbcConnectionProfile source;
		/** 当前显示的原始数据页，翻页时使用其主键边界。 */
		private JdbcTableRows page;
		/** 重新查询绑定表，并回到第一页。 */
		private final JButton refresh = new JButton(message("refresh.table.data"));
		/** 仅放弃刷新、筛选排序和翻页的返回值，不中断 JDBC 调用。 */
		private final JButton cancelReadWait = new JButton(message("table.query.cancel.request"));
		/** 向前浏览相邻主键范围。 */
		private final JButton previous = new JButton(message("previous.page"));
		/** 向后浏览相邻主键范围。 */
		private final JButton next = new JButton(message("next.page"));
		/** 对当前页选中的可写单元格发起修改。 */
		private final JButton edit = new JButton(message("edit.selected.cell"));
		/** 按 JSON 列值新增当前表数据，遗漏的列由数据库应用默认值。 */
		private final JButton insertRow = new JButton(message("insert.table.row"));
		/** 按完整行快照与主键删除当前选择行。 */
		private final JButton deleteRow = new JButton(message("delete.selected.table.row"));
		/** 仅在标签页可接收新数据库任务时允许选择导入文件。 */
		private final JButton importCsv = new JButton(message("import.csv"));
		/** 显示当前页的行数或请求结果。 */
		private final JLabel status = new JLabel();
		/** 当前标签页唯一的数据库任务，由 EDT 管理。 */
		private SwingWorker<?, Void> pageTask;
		/** MySQL 只读连接空闲超过 30 秒后释放；Timer 只在 EDT 操作。 */
		private final Timer mysqlReadConnectionIdleTimer = new Timer(30_000, event -> expireIdleMySqlReadConnection());
		/** 保护本标签页可复用 MySQL 读连接及其非凭据指纹。 */
		private final Object mysqlReadConnectionLock = new Object();
		/** 仅 MySQL 刷新、筛选和分页读取复用；写入继续创建独立连接。 */
		private JdbcDriverConnection mysqlReadConnection;
		/** 对比 profile、PasswordSafe 凭据和本机 TLS 校验设置，不保存原始密码。 */
		private String mysqlReadConnectionFingerprint;
		/** 当前任务是否为允许丢弃结果的只读表页查询。 */
		private boolean pageReadWaitCancellable;
		/** 取消等待请求跨工作线程可见；任务槽仍占用到 JDBC 操作真正返回。 */
		private volatile boolean pageReadWaitCancellationRequested;
		/** 当前可取消表页查询的连接快照，仅用于向该任务的活动 Statement 发起取消。 */
		private volatile JdbcDriverConnection activeReadConnection;
		/** 当前可取消查询的身份，阻止旧取消回调更新新任务状态。 */
		private volatile Object activeReadOperation;
		/** 标签页关闭标记，阻止后台任务保留连接或向已关闭 UI 回写。 */
		private volatile boolean disposed;


		/**
		 * @param project 导出操作归属的项目
		 * @param file 内存文件
		 * @param source 打开预览时使用的配置
		 * @param page 首次查询结果
		 */
		private TablePreviewEditor(Project project, VirtualFile file, JdbcConnectionProfile source, JdbcTableRows page) {
			this.file = file;
			this.source = source;
			mysqlReadConnectionIdleTimer.setRepeats(false);
			table.setCellSelectionEnabled(true);
			table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
			table.setAutoCreateRowSorter(true);
			table.getSelectionModel().addListSelectionListener(event -> updateSelectedTableActions());
			table.getColumnModel().getSelectionModel().addListSelectionListener(event -> updateSelectedTableActions());
			refresh.addActionListener(event -> refreshTableData());
			previous.addActionListener(event -> loadAdjacentTablePage(true));
			next.addActionListener(event -> loadAdjacentTablePage(false));
			edit.addActionListener(event -> editSelectedTableCell());
			table.addMouseListener(new MouseAdapter() {
				/** 双击数据格复用带主键校验的编辑流程，正在读写时保持禁用。 */
				@Override
				public void mouseClicked(MouseEvent event) {
					if (!SwingUtilities.isLeftMouseButton(event) || event.getClickCount() != 2) {
						return;
					}
					int row = table.rowAtPoint(event.getPoint());
					int column = table.columnAtPoint(event.getPoint());
					if (row >= 0 && column >= 0) {
						table.changeSelection(row, column, false, false);
						if (edit.isEnabled()) {
							editSelectedTableCell();
						}
					}
				}
			});
			insertRow.addActionListener(event -> insertTablePreviewRow());
			deleteRow.addActionListener(event -> deleteSelectedTablePreviewRow());
			var export = new JButton(message("export.csv"));
			export.addActionListener(event -> WorkbenchDatabasePanel.exportQueryResult(content, project,
				captureVisibleTableResult(), status::setText));
			importCsv.addActionListener(event -> importCsvToPreviewTable());
			applyDatabaseQuery.addActionListener(event -> applyDatabaseTableQuery());
			clearDatabaseQuery.addActionListener(event -> clearDatabaseTableQuery());
			databaseWhereExpression.setToolTipText(message("database.where.input.help"));
			databaseOrderByExpression.setToolTipText(message("database.order.by.input.help"));
			databaseWhereExpression.addActionListener(event -> applyDatabaseTableQuery());
			databaseOrderByExpression.addActionListener(event -> applyDatabaseTableQuery());
			JPanel whereControls = new JPanel(new BorderLayout(4, 0));
			whereControls.add(databaseWhereExpression, BorderLayout.CENTER);
			whereControls.setBorder(BorderFactory.createTitledBorder(message("database.where.title")));
			JPanel orderByControls = new JPanel(new BorderLayout(4, 0));
			orderByControls.add(databaseOrderByExpression, BorderLayout.CENTER);
			orderByControls.setBorder(BorderFactory.createTitledBorder(message("database.order.by.title")));
			JPanel queryControls = new JPanel(new GridLayout(1, 2, 6, 0));
			queryControls.add(whereControls);
			queryControls.add(orderByControls);
			JPanel databaseQueryActions = new JPanel(new BorderLayout(6, 0));
			JPanel queryButtons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
			queryButtons.add(clearDatabaseQuery);
			queryButtons.add(applyDatabaseQuery);
			databaseQueryActions.add(databaseQuerySummary, BorderLayout.CENTER);
			databaseQueryActions.add(queryButtons, BorderLayout.EAST);
			JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING));
			actions.add(refresh);
			cancelReadWait.setToolTipText(message("table.query.cancel.request.help"));
			cancelReadWait.setEnabled(false);
			cancelReadWait.addActionListener(event -> cancelTableReadResultWait());
			actions.add(cancelReadWait);
			actions.add(previous);
			actions.add(next);
			actions.add(edit);
			actions.add(insertRow);
			actions.add(deleteRow);
			actions.add(export);
			actions.add(importCsv);
			var filterLabel = new JLabel(message("workbench.filter.current.page"));
			filterLabel.setLabelFor(pageFilter);
			JPanel currentPageFilter = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
			currentPageFilter.add(filterLabel);
			currentPageFilter.add(pageFilter);
			pageFilter.setToolTipText(message("workbench.filter.current.page.help"));
			JPanel toolbar = new JPanel(new BorderLayout(0, 3));
			toolbar.add(new JBScrollPane(actions, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
				ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS), BorderLayout.NORTH);
			JPanel queryArea = new JPanel(new BorderLayout(0, 3));
			queryArea.add(queryControls, BorderLayout.CENTER);
			queryArea.add(databaseQueryActions, BorderLayout.SOUTH);
			toolbar.add(queryArea, BorderLayout.CENTER);
			toolbar.add(currentPageFilter, BorderLayout.SOUTH);
			pageFilter.getDocument().addDocumentListener(new DocumentAdapter() {
				/** {@inheritDoc} 只重新计算本地视图行。 */
				@Override
				protected void textChanged(DocumentEvent event) {
					applyCurrentPageFilter();
				}
			});
			content.add(toolbar, BorderLayout.NORTH);
			content.add(new JBScrollPane(table), BorderLayout.CENTER);
			content.add(status, BorderLayout.SOUTH);
			showTablePage(page);
		}

		/**
		 * 将筛选应用到当前页排序器，保留模型行索引供翻页和编辑使用。
		 */
		private void applyCurrentPageFilter() {
			String text = pageFilter.getText().strip();
			var sorter = (TableRowSorter<?>) table.getRowSorter();
			sorter.setRowFilter(text.isEmpty() ? null : RowFilter.regexFilter("(?iu)" + Pattern.quote(text)));
		}

		/**
		 * 在 EDT 按网格显示顺序复制当前页，供后台导出。
		 *
		 * @return 保留列顺序和 SQL NULL 的可见结果
		 */
		private JdbcQueryResult captureVisibleTableResult() {
			var columns = new ArrayList<String>(table.getColumnCount());
			for (int column = 0; column < table.getColumnCount(); column++) {
				columns.add(table.getColumnName(column));
			}
			var rows = new ArrayList<List<String>>(table.getRowCount());
			for (int row = 0; row < table.getRowCount(); row++) {
				var values = new ArrayList<String>(columns.size());
				for (int column = 0; column < columns.size(); column++) {
					values.add((String) table.getValueAt(row, column));
				}
				rows.add(values);
			}
			var result = page.result();
			return new JdbcQueryResult(columns, rows, result.truncated(), result.updateCount());
		}

		/**
		 * @param newPage 当前连接返回的新页，只在 EDT 更新网格和操作状态
		 */
		private void showTablePage(JdbcTableRows newPage) {
			var result = newPage.result();
			// 刷新可能读到变更后的表结构，只有列名与类型一致时才按列序号复用视图状态。
			boolean sameColumns = Objects.nonNull(page) && page.result().columnLabels().equals(result.columnLabels())
				&& page.columnTypes().equals(newPage.columnTypes());
			boolean sameQuery = sameColumns && page.queryOptions().equals(newPage.queryOptions());
			List<? extends RowSorter.SortKey> sortKeys = sameQuery ? List.copyOf(table.getRowSorter().getSortKeys()) : List.of();
			var columnOrder = new ArrayList<Integer>(table.getColumnCount());
			var columnWidths = new ArrayList<Integer>(table.getColumnCount());
			if (sameColumns) {
				for (int viewIndex = 0; viewIndex < table.getColumnCount(); viewIndex++) {
					var column = table.getColumnModel().getColumn(viewIndex);
					columnOrder.add(column.getModelIndex());
					columnWidths.add(column.getWidth());
				}
			}
			page = newPage;
			var rows = result.rows().stream().map(row -> row.toArray()).toArray(Object[][]::new);
			table.setModel(new DefaultTableModel(rows, result.columnLabels().toArray()) {
				/** {@inheritDoc} 预览数据不可直接修改。 */
				@Override
				public boolean isCellEditable(int row, int column) {
					return false;
				}
			});
			var sorter = (TableRowSorter<?>) table.getRowSorter();
			for (int index = 0; index < table.getColumnCount(); index++) {
				switch (page.columnTypes().get(index)) {
					case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.NUMERIC, Types.DECIMAL,
						Types.REAL, Types.FLOAT, Types.DOUBLE ->
						sorter.setComparator(index, (String left, String right) -> compareNumericCells(left, right));
					default -> {
						// 非数值列保留 IDE 表格的文本排序。
					}
				}
				table.getColumnModel().getColumn(index)
					.setPreferredWidth(Math.clamp(result.columnLabels().get(index).length() * 9 + 26, 120, 300));
			}
			for (int viewIndex = 0; viewIndex < columnOrder.size(); viewIndex++) {
				int currentViewIndex = table.convertColumnIndexToView(columnOrder.get(viewIndex));
				table.moveColumn(currentViewIndex, viewIndex);
				var column = table.getColumnModel().getColumn(viewIndex);
				column.setPreferredWidth(columnWidths.get(viewIndex));
				column.setWidth(columnWidths.get(viewIndex));
			}
			sorter.setSortsOnUpdates(true);
			sorter.setSortKeys(sortKeys);
			updateDatabaseQueryControls(newPage);
			applyCurrentPageFilter();
			previous.setEnabled(page.hasPreviousPage());
			next.setEnabled(page.hasNextPage());
			updateSelectedTableActions();
			insertRow.setEnabled(!page.insertableColumns().isEmpty());
			status.setText(message("returned") + rows.length + message("rows")
				+ (result.truncated() ? message("truncated") : ""));
		}

		/**
		 * 按当前表元数据应用数据库端 WHERE 和 ORDER BY，并重置 keyset 分页至首页。
		 */
		private void applyDatabaseTableQuery() {
			JdbcTableQueryOptions options;
			try {
				options = JdbcTableQueryExpressionParser.parse(databaseWhereExpression.getText(),
					databaseOrderByExpression.getText(), page.result().columnLabels(), WorkbenchBundle::message);
			} catch (IllegalArgumentException exception) {
				status.setText(exception.getMessage());
				return;
			}
			var target = page.table();
			runCancellableTableRead(message("apply.database.filter.sort"), connection ->
				JdbcTableEditor.previewTableRows(connection, source.dbType(), target, options, WorkbenchBundle::message),
			loaded -> {
				pageFilter.setText("");
				showTablePage(loaded);
			}, this::restoreDatabaseQueryControlsFromPage);
		}

		/**
		 * 清除服务端筛选与排序，恢复按主键读取的有界首页。
		 */
		private void clearDatabaseTableQuery() {
			databaseWhereExpression.setText("");
			databaseOrderByExpression.setText("");
			applyDatabaseTableQuery();
		}

		/**
		 * 显示数据库实际生效条件，切页后保留 WHERE 与 ORDER BY 输入。
		 *
		 * @param current 当前数据库查询页
		 */
		private void updateDatabaseQueryControls(JdbcTableRows current) {
			var options = current.queryOptions();
			databaseWhereExpression.setText(formatDatabaseWhereExpression(options));
			databaseOrderByExpression.setText(formatDatabaseOrderByExpression(options));
			updateDatabaseQuerySummary(options);
		}

		/**
		 * 将结构化条件还原为可编辑的安全 WHERE 文本，字符串中的引号按 SQL 规则双写。
		 *
		 * @param options 当前生效的结构化查询条件
		 * @return 可再次提交的 AND 条件文本；无筛选时为空字符串
		 */
		private String formatDatabaseWhereExpression(JdbcTableQueryOptions options) {
			return JdbcTableQueryExpressionParser.formatWhereExpression(options);
		}

		/**
		 * 将当前单列排序恢复为可编辑文本；排序列仍由表元数据白名单校验。
		 *
		 * @param options 当前生效的结构化查询条件
		 * @return 可再次提交的排序文本；未指定列时为空字符串
		 */
		private String formatDatabaseOrderByExpression(JdbcTableQueryOptions options) {
			if (!options.hasSort()) {
				return "";
			}
			return quoteQueryColumn(options.sortColumn()) + (options.sortDescending() ? " DESC" : " ASC");
		}

		/**
		 * 双引号转义列标识符，避免列名中的空格或保留字改变输入含义。
		 *
		 * @param column 数据库元数据返回的列名
		 * @return 可由表达式解析器识别的双引号列标识符
		 */
		private String quoteQueryColumn(String column) {
			return "\"" + column.replace("\"", "\"\"") + "\"";
		}

		/**
		 * 摘要本页实际生效的数据库条件；当前页内文本过滤由独立输入框完成。
		 *
		 * @param options 本页实际生效的数据库查询条件
		 */
		private void updateDatabaseQuerySummary(JdbcTableQueryOptions options) {
			String filterSummary = message("database.filter.none");
			if (options.hasFilter()) {
				List<String> conditions = options.filters().stream().map(condition -> {
					String operator = message("table.filter.operator." + condition.operator().name().toLowerCase(Locale.ROOT));
					String filterValue = condition.operator() == JdbcTableFilterOperator.IN
						? String.join(", ", condition.values()) : condition.value();
					return condition.operator().requiresValue()
						? message("database.filter.summary.condition", condition.column(), operator, filterValue)
						: message("database.filter.summary.null", condition.column(), operator);
				}).toList();
				filterSummary = String.join(" AND ", conditions);
			}
			String sortSummary = options.hasSort()
				? message("database.sort.summary.column", options.sortColumn(),
					message(options.sortDescending() ? "sort.descending" : "sort.ascending"))
				: message("database.sort.primary.key");
			String summary = message("database.filter.sort.summary", filterSummary, sortSummary);
			databaseQuerySummary.setText(summary);
			databaseQuerySummary.setToolTipText(summary);
		}

		/**
		 * 比较数值列中的显示文本，使用十进制精度以保留大整数的顺序。动态类型或非有限值排在普通数值之后。
		 *
		 * @param left 左侧非空显示值
		 * @param right 右侧非空显示值
		 * @return 供当前页排序使用的比较结果
		 */
		private static int compareNumericCells(String left, String right) {
			BigDecimal leftNumber = parseNumericCell(left);
			BigDecimal rightNumber = parseNumericCell(right);
			if (Objects.nonNull(leftNumber) && Objects.nonNull(rightNumber)) {
				return leftNumber.compareTo(rightNumber);
			}
			if (Objects.nonNull(leftNumber) != Objects.nonNull(rightNumber)) {
				return Objects.nonNull(leftNumber) ? -1 : 1;
			}
			return left.compareTo(right);
		}

		/**
		 * @param text 驱动返回的非空显示值
		 * @return 可精确解析的数值，非有限值或动态类型文本返回空
		 */
		private static BigDecimal parseNumericCell(String text) {
			try {
				return new BigDecimal(text);
			} catch (NumberFormatException ignored) {
				return null;
			}
		}

		/**
		 * 在后台重新读取绑定表的首页。成功后替换浏览快照，失败时保留旧页。
		 */
		private void refreshTableData() {
			var target = page.table();
			runCancellableTableRead(message("refresh.table.data"), connection ->
				JdbcTableEditor.previewTableRows(connection, source.dbType(), target, page.queryOptions(), WorkbenchBundle::message),
				this::showTablePage, this::restoreDatabaseQueryControlsFromPage);
		}

		/**
		 * 按标签页绑定的连接查下一页。同步更新或删除原连接后停止翻页，保留当前数据。
		 *
		 * @param backward 为 true 时从页首向前续读
		 */
		private void loadAdjacentTablePage(boolean backward) {
			var current = page;
			runCancellableTableRead(message(backward ? "previous.page" : "next.page"), connection ->
				backward ? JdbcTableEditor.readPreviousTableRows(connection, source.dbType(), current, WorkbenchBundle::message)
					: JdbcTableEditor.readNextTableRows(connection, source.dbType(), current, WorkbenchBundle::message), loaded -> {
				if (loaded.result().rows().isEmpty()) {
					previous.setEnabled(!backward && current.hasPreviousPage());
					next.setEnabled(backward && current.hasNextPage());
					status.setText(message(backward ? "table.page.no.previous.rows" : "table.page.no.next.rows"));
				} else {
					showTablePage(loaded);
				}
			}, this::restoreDatabaseQueryControlsFromPage);
		}

		/** 取消或读取失败时把编辑框恢复到仍显示的已完成页条件。 */
		private void restoreDatabaseQueryControlsFromPage() {
			updateDatabaseQueryControls(page);
		}

		/** 在行、列或结果页变化时按实际选区启用写入，后台操作期间保持禁用。 */
		private void updateSelectedTableActions() {
			boolean rowSelected = !disposed && Objects.isNull(pageTask) && importCsv.isEnabled()
				&& Objects.nonNull(page) && table.getSelectedRow() >= 0;
			int column = table.getSelectedColumn();
			deleteRow.setEnabled(rowSelected && !page.primaryKeys().isEmpty());
			edit.setEnabled(rowSelected && column >= 0 && page.editableColumns().contains(
				table.getModel().getColumnName(table.convertColumnIndexToModel(column))));
		}

		/**
		 * 按数据模型行号保存已确认的单元格，确保表头排序后仍以原始主键定位。
		 */
		private void editSelectedTableCell() {
			int row = table.getSelectedRow();
			int column = table.getSelectedColumn();
			if (row < 0 || column < 0) {
				status.setText(message("select.a.cell.to.edit"));
				return;
			}
			var current = page;
			int modelRow = table.convertRowIndexToModel(row);
			int modelColumn = table.convertColumnIndexToModel(column);
			String name = current.result().columnLabels().get(modelColumn);
			if (!current.editableColumns().contains(name)) {
				status.setText(message("primary.keys.generated.columns.and.unsupported.types.are.read.only"));
				return;
			}
			String original = current.result().rows().get(modelRow).get(modelColumn);
			WorkbenchDatabasePanel.confirmTableCellValue(content, current.table().name(), name, original, value -> {
				if (page != current || Objects.equals(original, value)) {
					return;
				}
				runTableOperation(message("update.table.data"), connection -> {
					String stored = JdbcTableEditor.updateTableCell(connection.connection(), source.dbType(), current,
						modelRow, name, value, WorkbenchBundle::message);
					if (current.queryOptions().filters().stream().anyMatch(filter -> name.equals(filter.column()))
						|| name.equals(current.queryOptions().sortColumn())) {
						return JdbcTableEditor.previewTableRows(connection, source.dbType(), current.table(),
							current.queryOptions(), WorkbenchBundle::message);
					}
					return current.withStoredCell(modelRow, modelColumn, stored);
				}, updated -> {
					showTablePage(updated);
					status.setText(message("table.cell.saved"));
				});
			});
		}

		/** 按列录入新行，保留省略列、空字符串和 SQL NULL 的区别。 */
		private void insertTablePreviewRow() {
			JdbcTableRows current = page;
			var columns = current.result().columnLabels().stream().filter(current.insertableColumns()::contains).toList();
			if (columns.isEmpty()) {
				status.setText(message("table.row.insert.unavailable"));
				return;
			}
			String json = WorkbenchDatabasePanel.promptTableRowValues(content, columns, status::setText);
			if (Objects.isNull(json) || page != current) {
				return;
			}
			var target = current.table();
			runTableOperation(message("insert.table.row"), connection -> {
				JdbcTableEditor.insertTableRow(connection.connection(), source.dbType(), current, json, WorkbenchBundle::message);
				return JdbcTableEditor.previewTableRows(connection, source.dbType(), target, current.queryOptions(),
					WorkbenchBundle::message);
			}, refreshed -> {
				showTablePage(refreshed);
				status.setText(message("table.row.inserted"));
			});
		}

		/** 确认后仅按显示时的整行值和主键删除单行。 */
		private void deleteSelectedTablePreviewRow() {
			JdbcTableRows current = page;
			int viewRow = table.getSelectedRow();
			if (viewRow < 0) {
				status.setText(message("select.a.row.to.delete"));
				return;
			}
			int row = table.convertRowIndexToModel(viewRow);
			List<String> values = current.result().rows().get(row);
			String keys = current.primaryKeys().stream().map(key -> key + "="
				+ Objects.toString(values.get(current.result().columnLabels().indexOf(key)), "NULL")).toList().toString();
			if (JOptionPane.showConfirmDialog(content, message("table.row.delete.confirm", current.table().name(), keys),
				message("delete.selected.table.row"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
				!= JOptionPane.YES_OPTION || page != current) {
				return;
			}
			var target = current.table();
			runTableOperation(message("delete.selected.table.row"), connection -> {
				JdbcTableEditor.deleteTableRow(connection.connection(), source.dbType(), current, row,
					WorkbenchBundle::message);
				return JdbcTableEditor.previewTableRows(connection, source.dbType(), target, current.queryOptions(),
					WorkbenchBundle::message);
			}, refreshed -> {
				showTablePage(refreshed);
				status.setText(message("table.row.deleted"));
			});
		}

		/**
		 * 将 CSV 原子追加到标签页绑定的表，并保留当前浏览位置。
		 */
		private void importCsvToPreviewTable() {
			if (disposed || Objects.nonNull(pageTask)) {
				return;
			}
			var target = page.table();
			var file = WorkbenchDatabasePanel.chooseConfirmedCsvImport(content, target);
			if (Objects.isNull(file)) {
				return;
			}
			runTableOperation(message("import.csv"), connection ->
				JdbcCsvImport.importCsvRows(connection.connection(), source.dbType(), target, file, WorkbenchBundle::message),
				count -> status.setText(message("imported") + count + message("rows.into")
					+ WorkbenchDatabasePanel.qualifiedImportTable(target)));
		}

		/**
		 * 串行执行标签页的刷新、分页与写入，配置变化后拒绝发起任务或应用旧结果。
		 *
		 * @param <T> 数据库操作的结果类型
		 * @param stage 当前操作标题
		 * @param operation 使用已绑定连接的数据库操作
		 * @param completed 对仍然有效的标签页应用结果
		 */
		private <T> void runTableOperation(String stage, WorkbenchDatabasePanel.JdbcOperation<T> operation,
									   Consumer<T> completed) {
			runTableOperation(stage, operation, completed, false, () -> {
			});
		}

		/** 将只读页面查询标记为可请求取消；实际中断行为由 JDBC 驱动决定。 */
		private <T> void runCancellableTableRead(String stage, WorkbenchDatabasePanel.JdbcOperation<T> operation,
										 Consumer<T> completed, Runnable queryStateRestoreCallback) {
			runTableOperation(stage, operation, completed, true, queryStateRestoreCallback);
		}

		/**
		 * 串行执行标签页数据库任务。MySQL 只读页查询使用本标签页连接；写入和其他方言使用本次独占连接。
		 * 只读页查询允许后台请求 Statement.cancel 并丢弃迟到结果，任务槽占用至 JDBC 调用返回。
		 *
		 * @param <T> 数据库操作的结果类型
		 * @param stage 当前操作标题
		 * @param operation 使用已绑定连接的数据库操作
		 * @param completed 对仍然有效的标签页应用结果
		 * @param cancellableRead 是否为只读页面查询
		 * @param queryStateRestoreCallback 读取失败或结果被丢弃后的查询控件恢复动作
		 */
		private <T> void runTableOperation(String stage, WorkbenchDatabasePanel.JdbcOperation<T> operation,
									   Consumer<T> completed, boolean cancellableRead,
									   Runnable queryStateRestoreCallback) {
			if (disposed || Objects.nonNull(pageTask)) {
				return;
			}
			var profiles = ApplicationManager.getApplication().getService(WorkbenchConnectionProfiles.class);
			boolean reusableMySqlRead = cancellableRead && source.dbType() == DbType.MySQL;
			if (reusableMySqlRead) {
				mysqlReadConnectionIdleTimer.stop();
			}
			refresh.setEnabled(false);
			previous.setEnabled(false);
			next.setEnabled(false);
			edit.setEnabled(false);
			insertRow.setEnabled(false);
			deleteRow.setEnabled(false);
			importCsv.setEnabled(false);
			applyDatabaseQuery.setEnabled(false);
			clearDatabaseQuery.setEnabled(false);
			databaseWhereExpression.setEnabled(false);
			databaseOrderByExpression.setEnabled(false);
			if (!profiles.listProfiles().contains(source)) {
				mysqlReadConnectionIdleTimer.stop();
				invalidateMySqlReadConnectionAsync("profile changed");
				status.setText(message("data.source.changed"));
				refresh.setEnabled(true);
				applyDatabaseQuery.setEnabled(true);
				clearDatabaseQuery.setEnabled(true);
				databaseWhereExpression.setEnabled(true);
				databaseOrderByExpression.setEnabled(true);
				return;
			}
			pageReadWaitCancellable = cancellableRead;
			pageReadWaitCancellationRequested = false;
			cancelReadWait.setEnabled(cancellableRead);
			Object readOperation = cancellableRead ? new Object() : null;
			activeReadOperation = readOperation;
			activeReadConnection = null;
			status.setText(stage + message("in.progress"));
			pageTask = new SwingWorker<T, Void>() {
				/** {@inheritDoc} 非 MySQL 只读页及所有写操作使用本次独占连接。 */
				@Override
				protected T doInBackground() throws Exception {
					if (!profiles.listProfiles().contains(source)) {
						throw new IllegalStateException(message("data.source.changed"));
					}
					if (cancellableRead && pageReadWaitCancellationRequested) {
						return null;
					}
					if (reusableMySqlRead) {
						return runWithReusableMySqlReadConnection(profiles, operation);
					}
					JdbcDriverConnection connection = WorkbenchDatabasePanel.openSavedJdbcSource(source);
					try (connection) {
						if (cancellableRead) {
							activeReadConnection = connection;
						}
						if (!profiles.listProfiles().contains(source)) {
							throw new IllegalStateException(message("data.source.changed"));
						}
						if (cancellableRead && pageReadWaitCancellationRequested) {
							return null;
						}
						return operation.apply(connection);
					} finally {
						if (activeReadConnection == connection) {
							activeReadConnection = null;
						}
					}
				}

				/** {@inheritDoc} 失败保留旧页，关闭或更新配置时丢弃结果。 */
				@Override
				protected void done() {
					pageTask = null;
					boolean resultCancelled = pageReadWaitCancellationRequested;
					if (resultCancelled) {
						mysqlReadConnectionIdleTimer.stop();
						invalidateMySqlReadConnectionAsync("read wait cancelled");
					} else {
						scheduleMySqlReadConnectionExpiry();
					}
					pageReadWaitCancellable = false;
					pageReadWaitCancellationRequested = false;
					cancelReadWait.setEnabled(false);
					if (activeReadOperation == readOperation) {
						activeReadOperation = null;
						activeReadConnection = null;
					}
					databaseWhereExpression.setEnabled(true);
					databaseOrderByExpression.setEnabled(true);
					if (disposed || isCancelled()) {
						return;
					}
					if (!profiles.listProfiles().contains(source)) {
						invalidateMySqlReadConnectionAsync("profile changed");
						if (cancellableRead) {
							queryStateRestoreCallback.run();
						}
						status.setText(message("data.source.changed"));
						return;
					}
					refresh.setEnabled(true);
					importCsv.setEnabled(true);
					applyDatabaseQuery.setEnabled(true);
					clearDatabaseQuery.setEnabled(true);
					previous.setEnabled(page.hasPreviousPage());
					next.setEnabled(page.hasNextPage());
					updateSelectedTableActions();
					insertRow.setEnabled(!page.insertableColumns().isEmpty());
					try {
						T result = get();
						if (resultCancelled) {
							queryStateRestoreCallback.run();
							status.setText(message("table.query.cancelled"));
							return;
						}
						completed.accept(result);
					} catch (InterruptedException failure) {
						if (cancellableRead) {
							queryStateRestoreCallback.run();
						}
						Thread.currentThread().interrupt();
					} catch (ExecutionException failure) {
						log.warn("表标签页数据库操作失败，阶段={}，配置 ID={}，目标表={}", stage, source.id(), page.table(),
							failure.getCause());
						if (resultCancelled || cancellableRead) {
							queryStateRestoreCallback.run();
						}
						status.setText(resultCancelled ? message("table.query.cancelled")
							: stage + message("failed") + failure.getCause().getMessage());
					}
				}
			};
			pageTask.execute();
		}

		/**
		 * 只在本标签页的 MySQL 只读刷新、筛选或翻页中复用连接。操作前后比较配置指纹；任何异常、取消或变更
		 * 都在工作线程释放连接，避免下一页复用不确定的 JDBC 会话。
		 *
		 * @param profiles 当前保存的连接配置服务
		 * @param operation 本次有界页面查询
		 * @param <T> 查询结果类型
		 * @return 查询结果；取消请求时结果由 EDT 丢弃
		 * @throws Exception 配置变化、凭据变化或 JDBC 查询失败
		 */
		private <T> T runWithReusableMySqlReadConnection(WorkbenchConnectionProfiles profiles,
			WorkbenchDatabasePanel.JdbcOperation<T> operation) throws Exception {
			String fingerprint = WorkbenchDatabasePanel.savedJdbcConnectionFingerprint(source);
			JdbcDriverConnection connection = acquireMySqlReadConnection(profiles, fingerprint);
			boolean reusable = false;
			try {
				if (!profiles.listProfiles().contains(source)) {
					throw new IllegalStateException(message("data.source.changed"));
				}
				if (pageReadWaitCancellationRequested) {
					return null;
				}
				T result = operation.apply(connection);
				if (pageReadWaitCancellationRequested) {
					return result;
				}
				if (!profiles.listProfiles().contains(source)
					|| !fingerprint.equals(WorkbenchDatabasePanel.savedJdbcConnectionFingerprint(source))) {
					throw new IllegalStateException(message("data.source.changed"));
				}
				reusable = !disposed;
				return result;
			} finally {
				if (activeReadConnection == connection) {
					activeReadConnection = null;
				}
				if (!reusable) {
					invalidateMySqlReadConnection(connection, "read cancelled, failed, or configuration changed");
				}
			}
		}

		/**
		 * 获取本标签页唯一 MySQL 读连接。凭据指纹变化时先关闭旧会话，再用 PasswordSafe 中的当前配置重连；
		 * 不执行额外网络探活，连接失效由本次页面查询发现。
		 *
		 * @param profiles 当前配置服务
		 * @param fingerprint 当前 profile、凭据和 TLS 设置摘要
		 * @return 已占用本任务连接槽的 MySQL 会话
		 * @throws Exception 连接打开期间配置变化或 JDBC 建连失败
		 */
		private JdbcDriverConnection acquireMySqlReadConnection(WorkbenchConnectionProfiles profiles,
			String fingerprint) throws Exception {
			JdbcDriverConnection stale = null;
			synchronized (mysqlReadConnectionLock) {
				if (disposed) {
					throw new IllegalStateException(message("data.source.changed"));
				}
				if (Objects.nonNull(mysqlReadConnection)
					&& fingerprint.equals(mysqlReadConnectionFingerprint)) {
					activeReadConnection = mysqlReadConnection;
					return mysqlReadConnection;
				}
				stale = mysqlReadConnection;
				mysqlReadConnection = null;
				mysqlReadConnectionFingerprint = null;
			}
			closeMySqlReadConnection(stale, "replacing stale session");
			if (!profiles.listProfiles().contains(source)) {
				throw new IllegalStateException(message("data.source.changed"));
			}
			JdbcDriverConnection opened = WorkbenchDatabasePanel.openSavedJdbcSource(source);
			boolean installed = false;
			try {
				if (!profiles.listProfiles().contains(source)
					|| !fingerprint.equals(WorkbenchDatabasePanel.savedJdbcConnectionFingerprint(source))) {
					throw new IllegalStateException(message("data.source.changed"));
				}
				synchronized (mysqlReadConnectionLock) {
					if (disposed) {
						throw new IllegalStateException(message("data.source.changed"));
					}
					mysqlReadConnection = opened;
					mysqlReadConnectionFingerprint = fingerprint;
					activeReadConnection = opened;
					installed = true;
				}
				return opened;
			} finally {
				if (!installed) {
					closeMySqlReadConnection(opened, "discarding changed session");
				}
			}
		}

		/** 在任务完成后安排 30 秒空闲释放；活动任务或活动查询不会被 idle 回调关闭。 */
		private void scheduleMySqlReadConnectionExpiry() {
			if (disposed || Objects.nonNull(pageTask) || Objects.nonNull(activeReadConnection)) {
				return;
			}
			synchronized (mysqlReadConnectionLock) {
				if (Objects.nonNull(mysqlReadConnection)) {
					mysqlReadConnectionIdleTimer.restart();
				}
			}
		}

		/** 空闲计时到期后从 EDT 原子摘除连接，再在后台关闭以免阻塞界面。 */
		private void expireIdleMySqlReadConnection() {
			if (disposed || Objects.nonNull(pageTask) || Objects.nonNull(activeReadConnection)) {
				return;
			}
			invalidateMySqlReadConnectionAsync("30 second idle timeout");
		}

		/**
		 * 按期望连接身份摘除缓存。传入空连接表示无条件失效；当前活动读取由其后台 finally 负责关闭。
		 *
		 * @param expected 仅当缓存仍是该连接时才关闭；空值表示关闭当前空闲缓存
		 * @param reason 生命周期结束原因，仅用于异常诊断
		 */
		private void invalidateMySqlReadConnection(JdbcDriverConnection expected, String reason) {
			JdbcDriverConnection stale = null;
			synchronized (mysqlReadConnectionLock) {
				if (Objects.nonNull(mysqlReadConnection)
					&& (Objects.isNull(expected) || mysqlReadConnection == expected)) {
					stale = mysqlReadConnection;
					mysqlReadConnection = null;
					mysqlReadConnectionFingerprint = null;
				}
			}
			closeMySqlReadConnection(stale, reason);
		}

		/** 将空闲缓存摘除后提交到 pooled thread 关闭，调用方可位于 EDT。 */
		private void invalidateMySqlReadConnectionAsync(String reason) {
			JdbcDriverConnection stale = null;
			synchronized (mysqlReadConnectionLock) {
				if (Objects.nonNull(mysqlReadConnection)
					&& mysqlReadConnection != activeReadConnection) {
					stale = mysqlReadConnection;
					mysqlReadConnection = null;
					mysqlReadConnectionFingerprint = null;
				}
			}
			if (Objects.nonNull(stale)) {
				JdbcDriverConnection connection = stale;
				ApplicationManager.getApplication().executeOnPooledThread(
					() -> closeMySqlReadConnection(connection, reason));
			}
		}

		/** 关闭已摘除的 MySQL 连接；由调用方保证没有表页语句仍在使用它。 */
		private void closeMySqlReadConnection(JdbcDriverConnection connection, String reason) {
			if (Objects.isNull(connection)) {
				return;
			}
			try {
				connection.close();
			} catch (Exception failure) {
				log.warn("关闭 MySQL 表预览只读连接失败，原因={}，配置 ID={}，目标表={}", reason, source.id(), page.table(), failure);
			}
		}

		/** 请求中断只读页面语句并丢弃结果；保留当前完成页，任务槽仍占用至 JDBC 操作返回。 */
		private void cancelTableReadResultWait() {
			if (Objects.isNull(pageTask) || !pageReadWaitCancellable || pageReadWaitCancellationRequested) {
				return;
			}
			pageReadWaitCancellationRequested = true;
			cancelReadWait.setEnabled(false);
			status.setText(message("table.query.cancel.requested"));
			Object operation = activeReadOperation;
			JdbcDriverConnection connection = activeReadConnection;
			if (Objects.isNull(connection)) {
				return;
			}
			var profileId = source.id();
			var target = page.table();
			ApplicationManager.getApplication().executeOnPooledThread(() -> {
				try {
					connection.cancelCurrentStatement();
				} catch (SQLException failure) {
					log.warn("请求取消 JDBC 表页查询失败，配置 ID={}，目标表={}", profileId, target, failure);
					ApplicationManager.getApplication().invokeLater(() -> {
						if (activeReadOperation == operation && pageReadWaitCancellationRequested) {
							status.setText(message("table.query.cancel.request.failed"));
						}
					});
				}
			});
		}

		/** {@inheritDoc} 返回滚动表格容器。 */
		@Override
		public @NotNull JComponent getComponent() { return content; }

		/** {@inheritDoc} 键盘焦点直接进入网格。 */
		@Override
		public @NotNull JComponent getPreferredFocusedComponent() { return table; }

		/** {@inheritDoc} 此名称用于编辑器提供器的显示。 */
		@Override
		public @NotNull String getName() { return message("browse.table.data"); }

		/** {@inheritDoc} 不恢复临时预览的持久状态。 */
		@Override
		public void setState(@NotNull FileEditorState state) { }

		/** {@inheritDoc} 没有待提交的本地更改。 */
		@Override
		public boolean isModified() { return false; }

		/** {@inheritDoc} 随临时文件的生命周期有效。 */
		@Override
		public boolean isValid() { return file.isValid(); }

		/** {@inheritDoc} 预览不产生属性变更。 */
		@Override
		public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) { }

		/** {@inheritDoc} 预览不注册属性监听器。 */
		@Override
		public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) { }

		/** {@inheritDoc} 返回当前临时文件。 */
		@Override
		public @NotNull VirtualFile getFile() { return file; }

		/** {@inheritDoc} 结束当前数据库任务，禁止关闭后刷新标签页。 */
		@Override
		public void dispose() {
			disposed = true;
			mysqlReadConnectionIdleTimer.stop();
			JdbcDriverConnection active = activeReadConnection;
			if (Objects.nonNull(pageTask)) {
				pageTask.cancel(true);
			}
			if (Objects.nonNull(active)) {
				var profileId = source.id();
				var target = page.table();
				ApplicationManager.getApplication().executeOnPooledThread(() -> {
					try {
						active.cancelCurrentStatement();
					} catch (SQLException failure) {
						log.warn("关闭 MySQL 表预览时请求取消活动查询失败，配置 ID={}，目标表={}", profileId, target, failure);
					}
				});
			} else {
				invalidateMySqlReadConnectionAsync("editor disposed");
			}
		}
	}
}
