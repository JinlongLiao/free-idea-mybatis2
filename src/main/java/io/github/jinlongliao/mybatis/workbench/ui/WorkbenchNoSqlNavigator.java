package io.github.jinlongliao.mybatis.workbench.ui;

import static io.github.jinlongliao.mybatis.workbench.ui.WorkbenchBundle.message;

import com.intellij.DynamicBundle;
import com.intellij.openapi.Disposable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutionException;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import io.github.jinlongliao.mybatis.workbench.database.*;

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
	WorkbenchNoSqlNavigator(DefaultTreeModel objectModel, JTree sourceTree) {
		this.objectModel = objectModel;
		this.sourceTree = sourceTree;
		sourceTree.addTreeWillExpandListener(this);
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
			loadNoSqlPage(node, pageNode, next.cursor());
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
		placeholder.setUserObject(message("loading.source.objects"));
		objectModel.nodeChanged(placeholder);

		SwingWorker<List<DefaultMutableTreeNode>, Void> task = new SwingWorker<>() {
			/** {@inheritDoc} 凭据和客户端均限定在这次后台读取。 */
			@Override
			protected List<DefaultMutableTreeNode> doInBackground() throws Exception {
				return readNoSqlChildren(profile, value, cursor);
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
						Set<Object> visibleKeys = new HashSet<>();
						for (int index = 0; index < node.getChildCount(); index++) {
							visibleKeys.add(((DefaultMutableTreeNode) node.getChildAt(index)).getUserObject());
						}
						children.stream().filter(child -> visibleKeys.add(child.getUserObject())).forEach(node::add);
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
						retry instanceof RedisScanContinuation ? retry : message("source.objects.failed"), false));
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
	 * @return 待挂接的子节点列表
	 * @throws Exception 网络、认证或数据读取失败
	 */
	private List<DefaultMutableTreeNode> readNoSqlChildren(NoSqlConnectionProfile profile, Object value, String cursor) throws Exception {
		String secret = WorkbenchNoSqlSourceEditor.readNoSqlSecret(profile);
		if (profile.type() == NoSqlSourceType.REDIS) {
			List<String> seeds = Arrays.stream(profile.endpoint().split(",", -1)).map(String::strip).toList();
			try (var connection = new WorkbenchRedisConnection(seeds, profile.cluster(), profile.tls(),
				profile.logicalDatabase(), profile.username(), secret, DynamicBundle.getLocale())) {
				if (value instanceof String node) {
					var page = connection.scanRedisKeys(node, cursor, "*", 100);
					List<DefaultMutableTreeNode> children = leafNames(page.keys());
					if (!"0".equals(page.cursor())) {
						children.add(new DefaultMutableTreeNode(new RedisScanContinuation(page.cursor()), false));
					}
					return children;
				}
				return connection.listPrimaryNodes().stream().map(this::expandableNoSqlNode).toList();
			}
		}
		if (profile.type() == NoSqlSourceType.MONGODB) {
			char[] chars = secret.toCharArray();
			try (var connection = new WorkbenchMongoConnection(profile.endpoint(), profile.username(),
				profile.authenticationDatabase(), chars, DynamicBundle.getLocale())) {
				if (value instanceof String database) {
					return leafNames(connection.listMongoCollections(database));
				}
				return connection.listMongoDatabases().stream().map(this::expandableNoSqlNode).toList();
			} finally {
				Arrays.fill(chars, '\0');
			}
		}
		char[] chars = secret.toCharArray();
		String ca = WorkbenchNoSqlSourceEditor.readLocalCaPath(profile);
		try (var connection = new WorkbenchElasticsearchConnection(profile.endpoint(), chars,
			ca.isEmpty() ? null : Path.of(ca), DynamicBundle.getLocale())) {
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

	/**
	 * 在主节点下保存服务器返回的后续扫描位置。
	 *
	 * @param cursor 非零 Redis SCAN 游标
	 * @author liaojinlong
	 * @since 2026/11/18 18:28
	 */
	record RedisScanContinuation(String cursor) {
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
