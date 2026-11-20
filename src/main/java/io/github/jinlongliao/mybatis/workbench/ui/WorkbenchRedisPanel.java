package io.github.jinlongliao.mybatis.workbench.ui;

import static io.github.jinlongliao.mybatis.workbench.ui.WorkbenchBundle.message;

import com.intellij.DynamicBundle;
import com.intellij.openapi.Disposable;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBScrollPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.swing.*;
import javax.swing.event.DocumentEvent;

import io.github.jinlongliao.mybatis.workbench.database.NoSqlConnectionProfile;
import io.github.jinlongliao.mybatis.workbench.database.WorkbenchRedisConnection;

/**
 * Redis 操作标签页，按节点和游标浏览键，并提供带显式路由键的命令执行。
 *
 * @author liaojinlong
 * @since 2026/11/18 15:35
 */
final class WorkbenchRedisPanel extends JPanel implements Disposable {
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
	/**
	 * 当前扫描或命令的文本结果。
	 */
	private final JTextArea output = new JTextArea();
	/**
	 * 当前操作结果和页状态。
	 */
	private final JLabel status = new JLabel(message("connect.to.redis.then.select.a.node"));
	/**
	 * 当前后台任务，在 EDT 管理。
	 */
	private SwingWorker<?, ?> redisTask;
	/**
	 * 连接配置修改次数，用于丢弃旧连接的异步结果。
	 */
	private long connectionRevision;

	/**
	 * 布置 Redis 扫描和命令区，连接参数从数据源载入。
	 */
	WorkbenchRedisPanel() {
		super(new BorderLayout(4, 4));
		JPanel form = new JPanel(new GridLayout(0, 2, 4, 4));
		addRedisField(form, message("browse.node"), nodes);
		addRedisField(form, message("key.pattern"), pattern);
		addRedisField(form, message("scan.cursor"), cursor);
		addRedisField(form, message("cluster.routing.key"), routingKey);
		addRedisField(form, message("command"), command);
		addRedisField(form, message("command.arguments.one.per.line"), new JBScrollPane(arguments));
		nodes.addActionListener(event -> cursor.setText("0"));
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
			/** {@inheritDoc} 更新过滤条件时开始新一轮扫描。 */
			@Override
			protected void textChanged(DocumentEvent event) {
				cursor.setText("0");
			}
		});

		JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
		JButton connect = new JButton(message("test.connection.and.load.nodes"));
		connect.addActionListener(event -> runRedisOperation(message("connect"), connection -> {
			connection.pingRedis();
			return connection.listPrimaryNodes();
		}, primaries -> {
			nodes.setModel(new DefaultComboBoxModel<>(primaries.toArray(String[]::new)));
			cursor.setText("0");
		}));
		actions.add(connect);
		JButton scan = new JButton(message("scan.one.page"));
		scan.addActionListener(event -> scanSelectedRedisNode());
		actions.add(scan);
		JButton execute = new JButton(message("run.command"));
		execute.addActionListener(event -> executeEditorRedisCommand());
		actions.add(execute);
		viewKeyDetails.setEnabled(false);
		viewKeyDetails.addActionListener(event -> showSelectedRedisKeyDetails());
		actions.add(viewKeyDetails);
		JPanel header = new JPanel(new BorderLayout());
		header.add(actions, BorderLayout.NORTH);
		header.add(form, BorderLayout.CENTER);
		add(header, BorderLayout.NORTH);
		output.setEditable(false);
		add(new JBScrollPane(output), BorderLayout.CENTER);
		add(status, BorderLayout.SOUTH);
	}

	/**
	 * 使已有节点和游标失效，避免换连接后沿用之前的游标。
	 */
	private void resetRedisBrowse() {
		connectionRevision++;
		selectedTreeKey = null;
		viewKeyDetails.setEnabled(false);
		nodes.removeAllItems();
		cursor.setText("0");
		output.setText("");
		status.setText(message("connection.changed.reload.redis.nodes"));
	}

	/**
	 * 从统一数据源树打开已保存连接，在请求前填充配置。
	 *
	 * @param profile 选择的连接设置
	 * @param secret  当前设备保存的认证凭据
	 * @param node    对象树上选中的主节点，空字符串表示未选中
	 * @param key     对象树上选中的原始键，空引用表示未选中
	 */
	void openSourceProfile(NoSqlConnectionProfile profile, String secret, String node, String key) {
		endpoints.setText(profile.endpoint());
		cluster.setSelected(profile.cluster());
		tls.setSelected(profile.tls());
		database.setValue(profile.logicalDatabase());
		database.setEnabled(!profile.cluster());
		username.setText(profile.username());
		password.setText(secret);
		nodes.removeAllItems();
		if (!node.isEmpty()) {
			nodes.addItem(node);
		}
		selectedTreeKey = key;
		viewKeyDetails.setEnabled(Objects.nonNull(key));
		// 只有能无歧义表示为单行参数的键才预填通用命令。
		boolean singleLineKey = Objects.nonNull(key) && !key.isEmpty() && key.lines().count() == 1
			&& !key.endsWith("\n") && !key.endsWith("\r");
		routingKey.setText(singleLineKey ? key : "");
		command.setText(singleLineKey ? "TYPE" : "GET");
		arguments.setText(singleLineKey ? key : "");
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
	 * 使用选定节点与该节点的游标请求一页。
	 */
	private void scanSelectedRedisNode() {
		if (!(nodes.getSelectedItem() instanceof String node)) {
			status.setText(message("load.redis.nodes.first"));
			return;
		}
		String next = cursor.getText().strip();
		String match = pattern.getText();
		runRedisOperation(message("scan"), connection -> connection.scanRedisKeys(node, next, match, 100), page -> {
			cursor.setText(page.cursor());
			output.setText(String.join("\n", page.keys()));
			status.setText(message("this.page") + page.keys().size() + message("keys") + ("0".equals(page.cursor()) ? message("scan.complete") : ""));
		});
	}

	/**
	 * 使用对象树保存的原始键读取详情，连接变化后该操作不可用。
	 */
	private void showSelectedRedisKeyDetails() {
		String key = selectedTreeKey;
		if (Objects.isNull(key)) {
			return;
		}
		runRedisOperation(message("redis.view.key.details"), connection -> connection.readRedisKeyDetails(key), details -> {
			String text = message("redis.key.type") + " " + details.type() + "\nTTL: " + details.ttlSeconds();
			if ("string".equals(details.type())) {
				text += "\n" + message("redis.string.preview") + "\n" + details.stringPreview();
			} else if ("list".equals(details.type())) {
				text += "\n" + message("redis.list.preview") + "\n" + displayRedisReply(details.listPreview());
			} else if ("zset".equals(details.type())) {
				text += "\n" + message("redis.sorted.set.preview") + "\n" + details.sortedSetPreview().stream()
					.map(member -> member.score() + "\t" + member.member()).collect(Collectors.joining("\n"));
			}
			output.setText(text);
		});
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
			connection.executeRedisCommand(key, commandName, params)), output::setText);
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
		if (Objects.nonNull(redisTask)) {
			return;
		}
		List<String> seeds = Arrays.stream(endpoints.getText().split(",", -1)).map(String::strip).toList();
		boolean clustered = cluster.isSelected();
		boolean encrypted = tls.isSelected();
		int dbIndex = clustered ? 0 : (Integer) database.getValue();
		String user = username.getText();
		char[] secret = password.getPassword();
		long revision = connectionRevision;
		status.setText(stage + message("in.progress"));
		redisTask = new SwingWorker<T, Void>() {
			/** {@inheritDoc} 密码字符数组在后台连接初始化后释放。 */
			@Override
			protected T doInBackground() {
				try (var connection = new WorkbenchRedisConnection(seeds, clustered, encrypted, dbIndex, user,
					new String(secret), DynamicBundle.getLocale())) {
					return operation.apply(connection);
				} finally {
					Arrays.fill(secret, '\0');
				}
			}

			/** {@inheritDoc} 任务被取消时跳过 UI 更新。 */
			@Override
			protected void done() {
				redisTask = null;
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
					log.warn("Redis 操作失败，阶段={}，节点={}，集群={}", stage, seeds, clustered, cause);
					status.setText(stage + message("failed") + cause.getMessage());
				}
			}
		};
		redisTask.execute();
	}

	/**
	 * 关闭标签页时取消任务请求，并清空界面密码。
	 */
	@Override
	public void dispose() {
		if (Objects.nonNull(redisTask)) {
			redisTask.cancel(true);
		}
		password.setText("");
	}
}
