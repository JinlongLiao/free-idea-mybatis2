package io.github.jinlongliao.mybatis.workbench.ui;

import static io.github.jinlongliao.mybatis.workbench.ui.WorkbenchBundle.message;

import com.intellij.DynamicBundle;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBScrollPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
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
	/**
	 * Extended JSON 查询条件。
	 */
	private final JTextArea filter = new JTextArea("{}", 3, 20);
	/**
	 * 当前页的 Extended JSON 结果。
	 */
	private final JTextArea output = new JTextArea();
	/**
	 * 仅在结果还有下一页时启用。
	 */
	private final JButton nextPage = new JButton(message("next.page"));
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
	/** 仅当前结果页有文档时允许将该页导出为 JSON Lines。 */
	private final JButton exportDocuments = new JButton(message("mongo.export.json.lines"));

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

	/**
	 * 布置集合选择和查询控制区，连接参数从数据源载入。
	 */
	WorkbenchMongoPanel() {
		super(new BorderLayout(4, 4));
		JPanel form = new JPanel(new GridLayout(0, 2, 4, 4));
		addMongoField(form, message("database"), databases);
		addMongoField(form, message("collection"), collections);
		addMongoField(form, message("filter.extended.json"), new JBScrollPane(filter));

		DocumentAdapter connectionListener = new DocumentAdapter() {
			/** {@inheritDoc} 改变连接时清空旧数据库及集合。 */
			@Override
			protected void textChanged(DocumentEvent event) {
				databases.removeAllItems();
				collections.removeAllItems();
				resetMongoQuery();
			}
		};
		for (JTextField field : List.of(uri, username, authDatabase, password)) {
			field.getDocument().addDocumentListener(connectionListener);
		}
		databases.addActionListener(event -> {
			collections.removeAllItems();
			resetMongoQuery();
		});
		collections.addActionListener(event -> resetMongoQuery());
		filter.getDocument().addDocumentListener(new DocumentAdapter() {
			/** {@inheritDoc} 改变查询条件后重新从第一页查询。 */
			@Override
			protected void textChanged(DocumentEvent event) {
				resetMongoQuery();
			}
		});

		JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
		JButton connect = new JButton(message("connect.and.list.databases"));
		connect.addActionListener(event -> runMongoOperation(message("connect"), connection -> {
			connection.pingMongoServer();
			return connection.listMongoDatabases();
		}, names -> databases.setModel(new DefaultComboBoxModel<>(names.toArray(String[]::new)))));
		actions.add(connect);
		JButton browse = new JButton(message("list.collections"));
		browse.addActionListener(event -> loadMongoCollections());
		actions.add(browse);
		JButton query = new JButton(message("find.first.page"));
		query.addActionListener(event -> queryMongoPage(0));
		actions.add(query);
		nextPage.setEnabled(false);
		nextPage.addActionListener(event -> queryMongoPage(nextOffset));
		actions.add(nextPage);
		editDocument.setEnabled(false);
		editDocument.addActionListener(event -> editMongoDocumentAtCaret());
		actions.add(editDocument);
		exportDocuments.setEnabled(false);
		exportDocuments.addActionListener(event -> exportMongoCurrentPage());
		actions.add(exportDocuments);
		JButton importDocuments = new JButton(message("mongo.import.json.lines"));
		importDocuments.addActionListener(event -> importMongoDocuments());
		actions.add(importDocuments);

		JPanel header = new JPanel(new BorderLayout());
		header.add(actions, BorderLayout.NORTH);
		header.add(form, BorderLayout.CENTER);
		add(header, BorderLayout.NORTH);
		output.setEditable(false);
		add(new JBScrollPane(output), BorderLayout.CENTER);
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
		exportDocuments.setEnabled(false);
		editDocument.setEnabled(false);
		nextPage.setEnabled(false);
		output.setText("");
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
			names -> collections.setModel(new DefaultComboBoxModel<>(names.toArray(String[]::new))));
	}

	/**
	 * 使用当前选中的集合及条件查询指定页面。
	 *
	 * @param offset 查询页从零开始的偏移
	 */
	private void queryMongoPage(int offset) {
		if (!(databases.getSelectedItem() instanceof String database)
			|| !(collections.getSelectedItem() instanceof String collection)) {
			status.setText(message("select.a.database.and.collection.first"));
			return;
		}
		String query = filter.getText();
		runMongoOperation(message("find.documents"), connection ->
			connection.queryMongoDocuments(database, collection, query, offset, 50), page -> {
			currentPage = page;
			currentOffset = offset;
			output.setText(String.join("\n\n", page.documents()));
			editDocument.setEnabled(!page.documents().isEmpty());
			exportDocuments.setEnabled(!page.documents().isEmpty());
			output.setCaretPosition(0);
			nextOffset = offset + page.documents().size();
			nextPage.setEnabled(page.hasNext() && nextOffset <= 10_000);
			status.setText(message("this.page") + page.documents().size() + message("documents"));
		});
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
	 * 编辑光标所在文档。确认后依据原始文档进行有条件的整条替换，成功则刷新原结果页。
	 */
	private void editMongoDocumentAtCaret() {
		if (Objects.nonNull(mongoTask) || !(databases.getSelectedItem() instanceof String database)
			|| !(collections.getSelectedItem() instanceof String collection)) {
			return;
		}
		int caret = output.getCaretPosition();
		int offset = 0;
		for (String original : currentPage.documents()) {
			int end = offset + original.length();
			if (caret >= offset && caret <= end) {
				long editRevision = revision;
				int pageOffset = currentOffset;
				JTextArea json = new JTextArea(original, 16, 62);
				JPanel editForm = new JPanel(new BorderLayout(4, 4));
				editForm.add(new JLabel(message("mongo.edit.replace.notice")), BorderLayout.NORTH);
				editForm.add(new JBScrollPane(json), BorderLayout.CENTER);
				int confirmed = JOptionPane.showConfirmDialog(this, editForm, message("mongo.edit.document"),
					JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
				if (confirmed != JOptionPane.OK_OPTION || editRevision != revision || original.equals(json.getText())) {
					return;
				}
				String replacement = json.getText();
				runMongoOperation(message("mongo.edit.document"), connection ->
					connection.replaceMongoDocument(database, collection, original, replacement), saved -> {
					resetMongoQuery();
					if (saved) {
						queryMongoPage(pageOffset);
					} else {
						status.setText(message("mongo.edit.conflict"));
					}
				});
				return;
			}
			offset = end + 2;
		}
		status.setText(message("mongo.edit.select.document"));
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
		if (Objects.nonNull(mongoTask)) {
			return;
		}
		String address = uri.getText().strip();
		String user = username.getText();
		String auth = authDatabase.getText().strip();
		char[] secret = password.getPassword();
		long currentRevision = revision;
		status.setText(stage + message("in.progress"));
		mongoTask = new SwingWorker<T, Void>() {
			/** {@inheritDoc} 客户端用于本次请求，完成后关闭。 */
			@Override
			protected T doInBackground() {
				try (var connection = new WorkbenchMongoConnection(address, user, auth, secret, DynamicBundle.getLocale())) {
					return operation.apply(connection);
				} finally {
					Arrays.fill(secret, '\0');
				}
			}

			/** {@inheritDoc} 忽略旧连接或已取消的请求。 */
			@Override
			protected void done() {
				mongoTask = null;
				if (isCancelled() || currentRevision != revision) {
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
					log.warn("MongoDB 操作失败，阶段={}，认证库={}", stage, auth, cause);
					status.setText(stage + message("failed") + cause.getMessage());
				}
			}
		};
		mongoTask.execute();
	}

	/**
	 * 关闭窗口时请求取消任务，并释放密码字段。
	 */
	@Override
	public void dispose() {
		revision++;
		if (Objects.nonNull(mongoTask)) {
			mongoTask.cancel(true);
		}
		password.setText("");
	}
}
