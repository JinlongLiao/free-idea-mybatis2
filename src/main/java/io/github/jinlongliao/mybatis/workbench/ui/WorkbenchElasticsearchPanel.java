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
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
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
	/**
	 * encoded API Key，界面关闭时清空。
	 */
	private final JPasswordField apiKey = new JPasswordField();
	/**
	 * 私有 CA PEM 文件，可留空使用默认信任库。
	 */
	private final JTextField caPath = new JTextField();
	/**
	 * 支持直接作为 Mapping 和 Search 目标的索引及别名。
	 */
	private final JComboBox<String> targets = new JComboBox<>();
	/**
	 * 用户编写的 Query DSL 对象。
	 */
	private final JTextArea query = new JTextArea("{\"query\":{\"match_all\":{}},\"size\":25}", 4, 20);
	/**
	 * Elasticsearch 响应 JSON。
	 */
	private final JTextArea output = new JTextArea();
	/**
	 * 保存当前显示的 Mapping 或 Search 响应，未收到响应时禁用。
	 */
	private final JButton exportResponse = new JButton(message("elasticsearch.export.json"));
	/**
	 * 操作执行状态和失败提示。
	 */
	private final JLabel status = new JLabel(message("enter.https.settings.and.an.api.key.then.load.indices"));
	/**
	 * 状态变化时使旧请求的 UI 结果失效。
	 */
	private long connectionRevision;
	/**
	 * 在 EDT 中跟踪当前后台操作。
	 */
	private SwingWorker<?, ?> elasticsearchTask;

	/**
	 * 布置索引和 Query DSL 操作界面，连接参数从数据源载入。
	 */
	WorkbenchElasticsearchPanel() {
		super(new BorderLayout(4, 4));
		JPanel form = new JPanel(new GridLayout(0, 2, 4, 4));
		addElasticsearchField(form, message("index.or.alias"), targets);
		addElasticsearchField(form, "Query DSL", new JBScrollPane(query));
		DocumentAdapter connections = new DocumentAdapter() {
			/** {@inheritDoc} 修改连接时清除旧目标，防止后续请求使用上一个集群的索引。 */
			@Override
			protected void textChanged(DocumentEvent event) {
				connectionRevision++;
				targets.removeAllItems();
				output.setText("");
				exportResponse.setEnabled(false);
				status.setText(message("connection.changed.reload.indices"));
			}
		};
		for (JTextField field : List.of(endpoint, apiKey, caPath)) {
			field.getDocument().addDocumentListener(connections);
		}
		targets.addActionListener(event -> {
			connectionRevision++;
			output.setText("");
			exportResponse.setEnabled(false);
		});

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
		JButton connect = new JButton(message("connect.and.load.indices"));
		connect.addActionListener(event -> runElasticsearchOperation(message("load.indices"),
			WorkbenchElasticsearchConnection::listElasticsearchObjects, objects -> {
				var names = new DefaultComboBoxModel<String>();
				objects.indices().forEach(names::addElement);
				objects.aliases().forEach(names::addElement);
				targets.setModel(names);
				status.setText(message("connected") + objects.indices().size() + message("indices") + objects.aliases().size() + message("aliases"));
			}));
		buttons.add(connect);
		JButton mapping = new JButton(message("show.mapping"));
		mapping.addActionListener(event -> showSelectedElasticsearchIndex(false));
		buttons.add(mapping);
		JButton search = new JButton(message("run.search"));
		search.addActionListener(event -> showSelectedElasticsearchIndex(true));
		buttons.add(search);
		exportResponse.setEnabled(false);
		exportResponse.addActionListener(event -> exportElasticsearchResponse());
		buttons.add(exportResponse);
		JPanel header = new JPanel(new BorderLayout());
		header.add(buttons, BorderLayout.NORTH);
		header.add(form, BorderLayout.CENTER);
		add(header, BorderLayout.NORTH);
		output.setEditable(false);
		add(new JBScrollPane(output), BorderLayout.CENTER);
		add(status, BorderLayout.SOUTH);
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
		apiKey.setText(secret);
		targets.removeAllItems();
		if (!target.isEmpty()) {
			targets.addItem(target);
		}
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
		runElasticsearchOperation(search ? message("run.search") : message("load.mapping"), connection -> search
			? connection.searchElasticsearchDocuments(index, dsl) : connection.readElasticsearchMapping(index), result -> {
			output.setText(result);
			output.setCaretPosition(0);
			exportResponse.setEnabled(!result.isBlank());
		});
	}

	/**
	 * 保存打开选择器时显示的 JSON 响应。后台以 UTF-8 创建新文件，拒绝覆盖已有文件。
	 * 切换目标或开始新请求后，旧导出的状态不替换当前请求状态。
	 */
	private void exportElasticsearchResponse() {
		String response = output.getText();
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
		if (Objects.nonNull(elasticsearchTask)) {
			return;
		}
		String address = endpoint.getText().strip();
		String ca = caPath.getText().strip();
		char[] secret = apiKey.getPassword();
		long revision = connectionRevision;
		status.setText(stage + message("in.progress"));
		elasticsearchTask = new SwingWorker<T, Void>() {
			/** {@inheritDoc} 请求结束后释放 HTTP 客户端和密码数组。 */
			@Override
			protected T doInBackground() throws Exception {
				try (var connection = new WorkbenchElasticsearchConnection(address, secret,
					ca.isEmpty() ? null : Path.of(ca), DynamicBundle.getLocale())) {
					return operation.run(connection);
				} finally {
					Arrays.fill(secret, '\0');
				}
			}

			/** {@inheritDoc} 忽略已取消或配置修改前发起的响应。 */
			@Override
			protected void done() {
				elasticsearchTask = null;
				if (isCancelled() || revision != connectionRevision) {
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
					log.warn("Elasticsearch 操作失败，阶段={}，服务地址={}", stage, address, cause);
					status.setText(stage + message("failed") + cause.getMessage());
				}
			}
		};
		elasticsearchTask.execute();
	}

	/**
	 * 关闭标签页时请求取消后台任务并清空凭据字段。
	 */
	@Override
	public void dispose() {
		if (Objects.nonNull(elasticsearchTask)) {
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
