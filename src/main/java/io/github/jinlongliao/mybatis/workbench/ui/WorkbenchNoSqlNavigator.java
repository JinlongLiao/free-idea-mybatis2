package io.github.jinlongliao.mybatis.workbench.ui;

import static io.github.jinlongliao.mybatis.workbench.ui.WorkbenchBundle.message;

import com.intellij.DynamicBundle;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.mongodb.MongoSecurityException;
import com.mongodb.MongoTimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import io.github.jinlongliao.mybatis.workbench.database.*;
import io.github.jinlongliao.mybatis.workbench.setting.WorkbenchConnectionProfiles;

/**
 * 在数据源树中按需加载 Redis 节点、MongoDB 集合和 Elasticsearch 索引。
 *
 * @author liaojinlong
 * @since 2026/11/18 16:55
 */
final class WorkbenchNoSqlNavigator implements TreeWillExpandListener, Disposable {
	/**
	 * 记录加载对象的阶段、配置标识和异常。
	 */
	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	/**
	 * 尚未加载子节点的标记，触发展开行为。
	 */
	private static final Object UNLOADED = new Object();

	/**
	 * 当前窗口的数据源树模型，只在 EDT 访问。
	 */
	private final DefaultTreeModel objectModel;

	/** 当前项目，用于将 Mongo/Elasticsearch 树对象打开为编辑器标签。 */
	private final Project project;

	/**
	 * 当前窗口的对象树，用于恢复异步加载前的展开路径。
	 */
	private final JTree sourceTree;

	/**
	 * 窗口已启动的后台任务，由 EDT 更新。
	 */
	private final Set<SwingWorker<?, ?>> tasks = new HashSet<>();

	/**
	 * 连接已有对象树。
	 *
	 * @param objectModel 当前树模型
	 * @param sourceTree  当前树组件
	 */
	WorkbenchNoSqlNavigator(Project project, DefaultTreeModel objectModel, JTree sourceTree) {
		this.project = project;
		this.objectModel = objectModel;
		this.sourceTree = sourceTree;
		sourceTree.addTreeWillExpandListener(this);
	}

	/**
	 * 将选中的 MongoDB 集合或 Elasticsearch 索引/别名打开为独立数据编辑器。
	 * 只有满足当前树层级的对象节点才会打开，避免把数据库或分组节点误当作目标。
	 *
	 * @return 当前选择是受支持的可浏览对象时为 true
	 */
	boolean openSelectedNoSqlObjectEditor() {
		if (!(sourceTree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode targetNode)
			|| !(targetNode.getUserObject() instanceof NoSqlObjectName objectName)
			|| !(targetNode.getParent() instanceof DefaultMutableTreeNode parentNode)) {
			return false;
		}
		String target = objectName.name();
		NoSqlConnectionProfile profile = findSourceProfile(targetNode);
		if (Objects.isNull(profile)) {
			return false;
		}
		if (profile.type() == NoSqlSourceType.MONGODB && parentNode.getUserObject() instanceof String database
			&& parentNode.getParent() instanceof DefaultMutableTreeNode sourceNode
			&& sourceNode.getUserObject() instanceof NoSqlConnectionProfile) {
			WorkbenchNoSqlObjectEditorProvider.openMongoCollectionEditor(project, profile, database, target);
			return true;
		}
		if (profile.type() == NoSqlSourceType.ELASTICSEARCH
			&& (Objects.equals(parentNode.getUserObject(), message("search.indices"))
			|| Objects.equals(parentNode.getUserObject(), message("search.aliases")))) {
			WorkbenchNoSqlObjectEditorProvider.openElasticsearchTargetEditor(project, profile, target);
			return true;
		}
		return false;
	}

	/**
	 * 创建带展开占位节点的非 JDBC 数据源。
	 *
	 * @param profile 连接配置
	 * @return 供对象树插入的节点
	 */
	DefaultMutableTreeNode createSourceNode(NoSqlConnectionProfile profile) {
		return expandableNoSqlNode(profile);
	}

	/**
	 * {@inheritDoc} 只在第一次展开数据源或可展开对象时发起请求。
	 */
	@Override
	public void treeWillExpand(TreeExpansionEvent event) {
		if (event.getPath().getLastPathComponent() instanceof DefaultMutableTreeNode node) {
			loadNoSqlChildren(node);
		}
	}

	/**
	 * {@inheritDoc} 折叠后保留已读取的对象。
	 */
	@Override
	public void treeWillCollapse(TreeExpansionEvent event) {
	}

	/**
	 * 刷新所选节点或最近的可加载上级。Elasticsearch 的索引与别名
	 * 来自同一次读取，因此其任意子节点均刷新所属数据源。
	 *
	 * @param selected 用户选中的非 JDBC 节点
	 * @param profile  该节点所属的数据源配置
	 */
	void refreshNoSqlNode(DefaultMutableTreeNode selected, NoSqlConnectionProfile profile) {
		for (var node = selected; Objects.nonNull(node); node = (DefaultMutableTreeNode) node.getParent()) {
			boolean dataSource = node.getUserObject() instanceof NoSqlConnectionProfile;
			if (dataSource || (profile.type() != NoSqlSourceType.ELASTICSEARCH && node.getAllowsChildren())) {
				node.removeAllChildren();
				node.add(new DefaultMutableTreeNode(UNLOADED, false));
				objectModel.nodeStructureChanged(node);
				loadNoSqlChildren(node);
				sourceTree.expandPath(new TreePath(node.getPath()));
				return;
			}
		}
	}

	/**
	 * 设置当前 IDE 的 Redis 树过滤条件，并从数据源根节点重新开始扫描。
	 *
	 * @param selected 数据源节点或其下的对象
	 * @param profile 当前 Redis 连接快照
	 */
	void configureRedisTreeFilter(DefaultMutableTreeNode selected, NoSqlConnectionProfile profile) {
		var source = selected;
		while (!(source.getUserObject() instanceof NoSqlConnectionProfile)
			&& source.getParent() instanceof DefaultMutableTreeNode parent) {
			source = parent;
		}
		var properties = PropertiesComponent.getInstance();
		var key = redisTreeFilterKey(profile);
		String input = JOptionPane.showInputDialog(sourceTree, message("redis.tree.key.filter.prompt"),
			readRedisTreeFilter(profile));
		var profiles = ApplicationManager.getApplication().getService(WorkbenchConnectionProfiles.class);
		if (Objects.isNull(input) || source.getRoot() != objectModel.getRoot() || !profiles.listNoSqlProfiles().contains(profile)) {
			return;
		}
		properties.setValue(key, input.isEmpty() ? "*" : input, "*");
		refreshNoSqlNode(source, profile);
	}

	/**
	 * @param profile Redis 数据源
	 * @return IDE 保存的 Redis 键匹配表达式，未设置时匹配全部键
	 */
	static String readRedisTreeFilter(NoSqlConnectionProfile profile) {
		return PropertiesComponent.getInstance().getValue(redisTreeFilterKey(profile), "*");
	}

	/**
	 * 清理已删除 Redis 数据源的本机树过滤设置。
	 *
	 * @param profile 已从共享配置移除的 Redis 数据源
	 */
	static void clearRedisTreeFilter(NoSqlConnectionProfile profile) {
		PropertiesComponent.getInstance().unsetValue(redisTreeFilterKey(profile));
	}

	/**
	 * @param profile 当前 Redis 连接
	 * @return 当前 IDE 的树过滤属性键
	 */
	private static String redisTreeFilterKey(NoSqlConnectionProfile profile) {
		return "mybatis.workbench.redis.tree.filter." + profile.id();
	}

	/**
	 * 枚举账号可见的 MongoDB 数据库，应用本机多选后仅刷新仍在树上的源节点。
	 *
	 * @param selected 当前所选节点或其子节点
	 * @param profile 对话框所属的数据源
	 */
	void chooseMongoDatabases(DefaultMutableTreeNode selected, NoSqlConnectionProfile profile) {
		DefaultMutableTreeNode source = selected;
		while (!(source.getUserObject() instanceof NoSqlConnectionProfile)
			&& source.getParent() instanceof DefaultMutableTreeNode parent) {
			source = parent;
		}
		DefaultMutableTreeNode sourceNode = source;
		var profiles = ApplicationManager.getApplication().getService(WorkbenchConnectionProfiles.class);
		// 同步先更新配置服务，再刷新树，确认时必须同时检查两处状态。
		BooleanSupplier stillCurrent = () -> sourceNode.getRoot() == objectModel.getRoot()
			&& profiles.listNoSqlProfiles().contains(profile);
		if (!stillCurrent.getAsBoolean()) {
			return;
		}
		SwingWorker<MongoDatabaseChoices, Void> task = new SwingWorker<>() {
			/** {@inheritDoc} 在后台单独连接并读取数据库名称。 */
			@Override
			protected MongoDatabaseChoices doInBackground() {
				char[] password = WorkbenchNoSqlSourceEditor.readNoSqlSecret(profile).toCharArray();
				try (var connection = new WorkbenchMongoConnection(profile.endpoint(), profile.username(),
					profile.authenticationDatabase(), password, DynamicBundle.getLocale())) {
					var names = connection.listMongoDatabases();
					return new MongoDatabaseChoices(names, connection.initialMongoDatabaseSelection(names),
						connection.defaultMongoDatabaseName());
				} finally {
					Arrays.fill(password, '\0');
				}
			}

			/** {@inheritDoc} 查询失败时保留原勾选项和现有树。 */
			@Override
			protected void done() {
				tasks.remove(this);
				if (isCancelled() || !stillCurrent.getAsBoolean()) {
					return;
				}
				try {
					MongoDatabaseChoices choices = get();
					WorkbenchDatabaseCatalogs.chooseDatabaseNames(sourceTree, profile.id(), choices.names(),
						choices.initialSelection(), choices.defaultName(), stillCurrent,
						() -> refreshNoSqlNode(sourceNode, profile));
				} catch (InterruptedException failure) {
					Thread.currentThread().interrupt();
				} catch (ExecutionException failure) {
					Throwable cause = failure.getCause();
					log.warn("选择 MongoDB 数据库失败，阶段=读取名称，配置 ID={}", profile.id(), cause);
					JOptionPane.showMessageDialog(sourceTree, describeNoSqlBrowseFailure(cause));
				}
			}
		};
		tasks.add(task);
		task.execute();
	}

	/**
	 * @param failure 后台读取失败的根因
	 * @return 不附带 URI 或认证信息的界面提示
	 */
	private String describeNoSqlBrowseFailure(Throwable failure) {
		if (failure instanceof MongoSecurityException) {
			return message("mongo.error.authentication");
		}
		if (failure instanceof MongoTimeoutException) {
			return message("mongo.error.network");
		}
		return message("source.objects.failed");
	}

	/**
	 * 后台读取当前节点；节点被刷新或移除时丢弃较早的返回结果。
	 *
	 * @param node 树中要展开或刷新的当前节点
	 */
	private void loadNoSqlChildren(DefaultMutableTreeNode node) {
		if (node.getChildCount() != 1 || ((DefaultMutableTreeNode) node.getFirstChild()).getUserObject() != UNLOADED) {
			return;
		}
		loadNoSqlPage(node, (DefaultMutableTreeNode) node.getFirstChild(), "0");
	}

	/**
	 * 使用所选分页节点保存的游标继续扫描，键仍挂在同一主节点下。
	 *
	 * @param pageNode 用户双击或通过菜单触发的分页节点
	 */
	void loadNextRedisPage(DefaultMutableTreeNode pageNode) {
		if (pageNode.getUserObject() instanceof RedisScanContinuation next
			&& pageNode.getParent() instanceof DefaultMutableTreeNode node) {
			if (next.bufferedOffset() < next.bufferedKeys().size()) {
				loadBufferedRedisPage(node, pageNode, next);
				return;
			}
			loadNoSqlPage(node, pageNode, next.cursor());
		}
	}

	/** 将 Redis 超量响应的下一批键直接挂接到树中，再保存剩余键和服务端游标。 */
	private void loadBufferedRedisPage(DefaultMutableTreeNode node, DefaultMutableTreeNode placeholder,
									   RedisScanContinuation continuation) {
		int end = continuation.bufferedOffset()
			+ Math.min(100, continuation.bufferedKeys().size() - continuation.bufferedOffset());
		List<DefaultMutableTreeNode> page = leafNames(continuation.bufferedKeys()
			.subList(continuation.bufferedOffset(), end));
		appendRedisPageContinuation(page, continuation.cursor(), continuation.match(), continuation.bufferedKeys(), end);
		node.remove(placeholder);
		appendUniqueChildren(node, page);
		TreePath path = new TreePath(node.getPath());
		boolean wasExpanded = sourceTree.isExpanded(path);
		objectModel.nodeStructureChanged(node);
		if (wasExpanded) {
			sourceTree.expandPath(path);
		}
	}

	/**
	 * 在后台读取一页，只有占位节点仍属于当前对象时才应用结果。
	 *
	 * @param node        承载结果的数据源或对象
	 * @param placeholder 首次加载或增量加载的占位节点
	 * @param cursor      Redis 本节点游标，其他协议固定为 0
	 */
	private void loadNoSqlPage(DefaultMutableTreeNode node, DefaultMutableTreeNode placeholder, String cursor) {
		NoSqlConnectionProfile profile = findSourceProfile(node);
		if (Objects.isNull(profile)) {
			return;
		}
		Object value = node.getUserObject();
		Object retry = placeholder.getUserObject();
		String match = retry instanceof RedisScanContinuation next ? next.match()
			: profile.type() == NoSqlSourceType.REDIS
				? readRedisTreeFilter(profile) : "*";
		placeholder.setUserObject(message("loading.source.objects"));
		objectModel.nodeChanged(placeholder);

		SwingWorker<List<DefaultMutableTreeNode>, Void> task = new SwingWorker<>() {
			/** {@inheritDoc} 凭据和客户端均限定在这次后台读取。 */
			@Override
			protected List<DefaultMutableTreeNode> doInBackground() throws Exception {
				return readNoSqlChildren(profile, value, cursor, match);
			}

			/** {@inheritDoc} 仅替换本次加载安放的占位节点。 */
			@Override
			protected void done() {
				tasks.remove(this);
				if (isCancelled() || node.getRoot() != objectModel.getRoot() || placeholder.getParent() != node) {
					return;
				}
				node.remove(placeholder);
				try {
					List<DefaultMutableTreeNode> children = get();
					if (retry instanceof RedisScanContinuation) {
						appendUniqueChildren(node, children);
					} else {
						children.forEach(node::add);
					}
					if (node.getChildCount() == 0) {
						node.add(new DefaultMutableTreeNode(message("no.source.objects"), false));
					}
				} catch (InterruptedException failure) {
					Thread.currentThread().interrupt();
				} catch (ExecutionException failure) {
					log.warn("加载非 JDBC 数据源失败，类型={}，配置 ID={}", profile.type(), profile.id(), failure.getCause());
					// 增量失败时保留原游标，可再次请求同一页。
					node.add(new DefaultMutableTreeNode(
						retry instanceof RedisScanContinuation ? retry : describeNoSqlBrowseFailure(failure.getCause()), false));
				}
				TreePath path = new TreePath(node.getPath());
				boolean wasExpanded = sourceTree.isExpanded(path);
				objectModel.nodeStructureChanged(node);
				if (wasExpanded) {
					sourceTree.expandPath(path);
				}
			}
		};
		tasks.add(task);
		task.execute();
	}

	/**
	 * 根据非 JDBC 协议和当前节点返回各类数据库对象。
	 *
	 * @param profile 当前数据源配置
	 * @param value   数据源或待展开的对象
	 * @param cursor  Redis 主节点的扫描游标，首次使用 0
	 * @param match 与 Redis 游标绑定的键匹配表达式
	 * @return 待挂接的子节点列表
	 * @throws Exception 网络、认证或数据读取失败
	 */
	private List<DefaultMutableTreeNode> readNoSqlChildren(NoSqlConnectionProfile profile, Object value, String cursor, String match) throws Exception {
		String secret = WorkbenchNoSqlSourceEditor.readNoSqlSecret(profile);
		if (profile.type() == NoSqlSourceType.REDIS) {
			List<String> seeds = Arrays.stream(profile.endpoint().split(",", -1)).map(String::strip).toList();
			int logicalDatabase = value instanceof Integer index ? index : profile.logicalDatabase();
				try (var connection = new WorkbenchRedisConnection(seeds, profile.cluster(), profile.tls(),
					logicalDatabase, profile.username(), secret,
					WorkbenchNoSqlSourceEditor.readNoSqlCertificateVerification(profile), DynamicBundle.getLocale())) {
				if (value instanceof String || value instanceof Integer) {
					String node = value instanceof String address ? address : connection.listPrimaryNodes().getFirst();
					var page = connection.scanRedisKeys(node, cursor, match, 100);
					int end = page.nextPageEnd(0, 100);
					List<DefaultMutableTreeNode> children = leafNames(page.keys().subList(0, end));
					appendRedisPageContinuation(children, page.cursor(), match, page.keys(), end);
					return children;
				}
				if (!profile.cluster()) {
					return connection.listRedisLogicalDatabases(logicalDatabase).stream().map(this::expandableNoSqlNode).toList();
				}
				return connection.listPrimaryNodes().stream().map(this::expandableNoSqlNode).toList();
			}
		}
		if (profile.type() == NoSqlSourceType.MONGODB) {
			char[] chars = secret.toCharArray();
				try (var connection = new WorkbenchMongoConnection(profile.endpoint(), profile.username(),
					profile.authenticationDatabase(), chars,
					WorkbenchNoSqlSourceEditor.readNoSqlCertificateVerification(profile), DynamicBundle.getLocale())) {
				if (value instanceof String database) {
					return leafNames(connection.listMongoCollections(database));
				}
				List<String> names = connection.listMongoDatabases();
				List<String> selected = WorkbenchDatabaseCatalogs.selectedDatabaseNames(profile.id(), connection.initialMongoDatabaseSelection(names));
				return names.stream().filter(selected::contains).map(this::expandableNoSqlNode).toList();
			} finally {
				Arrays.fill(chars, '\0');
			}
		}
		// API Key 模式缺少凭据时无需发起网络请求，在当前树节点直接说明配置缺项。
		if (profile.username().isEmpty() && secret.isEmpty()) {
			return List.of(new DefaultMutableTreeNode(message("search.encoded.key"), false));
		}
		char[] chars = secret.toCharArray();
		String ca = WorkbenchNoSqlSourceEditor.readLocalCaPath(profile);
		boolean verifyCertificate = WorkbenchNoSqlSourceEditor.readElasticsearchCertificateVerification(profile);
		try (var connection = new WorkbenchElasticsearchConnection(profile.endpoint(), profile.username(), chars,
			ca.isEmpty() ? null : Path.of(ca), verifyCertificate, DynamicBundle.getLocale())) {
			var objects = connection.listElasticsearchObjects();
			DefaultMutableTreeNode indices = new DefaultMutableTreeNode(message("search.indices"));
			leafNames(objects.indices()).forEach(indices::add);
			DefaultMutableTreeNode aliases = new DefaultMutableTreeNode(message("search.aliases"));
			leafNames(objects.aliases()).forEach(aliases::add);
			return List.of(indices, aliases);
		} finally {
			Arrays.fill(chars, '\0');
		}
	}

	/**
	 * 创建能够按需浏览的对象节点。
	 *
	 * @param value 数据源配置或对象名称
	 * @return 带占位子节点的树节点
	 */
	private DefaultMutableTreeNode expandableNoSqlNode(Object value) {
		DefaultMutableTreeNode node = new DefaultMutableTreeNode(value);
		node.add(new DefaultMutableTreeNode(UNLOADED, false));
		return node;
	}

	/**
	 * 构造只读的数据库对象叶节点。
	 *
	 * @param names 数据库对象名称
	 * @return 不提供展开动作的树节点
	 */
	private List<DefaultMutableTreeNode> leafNames(List<String> names) {
		List<DefaultMutableTreeNode> nodes = new ArrayList<>(names.size());
		for (String name : names) {
			nodes.add(new DefaultMutableTreeNode(new NoSqlObjectName(name), false));
		}
		return nodes;
	}

	/** 在当前子节点中去重追加一批 Redis 键及可能的下一页占位项。 */
	private void appendUniqueChildren(DefaultMutableTreeNode node, List<DefaultMutableTreeNode> children) {
		Set<Object> visibleKeys = new HashSet<>();
		for (int index = 0; index < node.getChildCount(); index++) {
			visibleKeys.add(((DefaultMutableTreeNode) node.getChildAt(index)).getUserObject());
		}
		children.stream().filter(child -> visibleKeys.add(child.getUserObject())).forEach(node::add);
	}

	/** 保存尚未展示的超量键；缓存耗尽后继续使用该 SCAN 响应提供的服务端游标。 */
	private void appendRedisPageContinuation(List<DefaultMutableTreeNode> children, String cursor, String match,
										 List<String> keys, int nextOffset) {
		if (nextOffset < keys.size() || !"0".equals(cursor)) {
			children.add(new DefaultMutableTreeNode(new RedisScanContinuation(cursor, match, keys, nextOffset), false));
		}
	}

	/**
	 * 在主节点下保存服务器游标，以及 Redis COUNT 超量返回但尚未展示的键。
	 *
	 * @param cursor Redis SCAN 游标；若缓冲键尚未展示完，可为 0
	 * @param match 生成游标时使用的匹配表达式
	 * @param bufferedKeys 同一 SCAN 响应中尚未全部展示的键
	 * @param bufferedOffset 缓冲列表中下一批键的起始下标
	 * @author liaojinlong
	 * @since 2026/11/18 18:28
	 */
	record RedisScanContinuation(String cursor, String match, List<String> bufferedKeys, int bufferedOffset) {
		/**
		 * 创建没有待展示键的服务器游标占位节点。
		 *
		 * @param cursor Redis SCAN 下一游标
		 * @param match Redis glob 条件
		 */
		RedisScanContinuation(String cursor, String match) {
			this(cursor, match, List.of(), 0);
		}

		/**
		 * @return 当前 IDE 语言的分页操作标题
		 */
		@Override
		public String toString() {
			return message("next.page");
		}
	}

	/**
	 * 保存真实对象名，使查询入口能够和临时状态提示区分。
	 *
	 * @param name 数据库返回的原始对象名称
	 * @author liaojinlong
	 * @since 2026/11/18 18:25
	 */
	record NoSqlObjectName(String name) {
		/**
		 * @return 树渲染器显示的原始对象名称
		 */
		@Override
		public String toString() {
			return name;
		}
	}

	/**
	 * 从节点向上搜索所属数据源。
	 *
	 * @param node 当前树节点
	 * @return 所属非 JDBC 连接，其他节点为空
	 */
	static NoSqlConnectionProfile findSourceProfile(DefaultMutableTreeNode node) {
		for (var candidate = node; Objects.nonNull(candidate); candidate = (DefaultMutableTreeNode) candidate.getParent()) {
			if (candidate.getUserObject() instanceof NoSqlConnectionProfile profile) {
				return profile;
			}
		}
		return null;
	}

	/**
	 * 取消窗口关闭时尚在等待的读取。
	 */
	@Override
	public void dispose() {
		for (var task : List.copyOf(tasks)) {
			task.cancel(true);
		}
		tasks.clear();
	}
}
