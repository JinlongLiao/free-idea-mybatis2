package io.github.jinlongliao.mybatis.workbench.ui;

import static io.github.jinlongliao.mybatis.workbench.ui.WorkbenchBundle.message;

import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;

import org.jetbrains.annotations.NotNull;

import java.beans.PropertyChangeListener;
import java.util.Objects;
import java.util.UUID;

import javax.swing.JComponent;

import io.github.jinlongliao.mybatis.workbench.database.NoSqlConnectionProfile;
import io.github.jinlongliao.mybatis.workbench.database.NoSqlSourceType;
import io.github.jinlongliao.mybatis.workbench.setting.WorkbenchConnectionProfiles;
import io.github.jinlongliao.mybatis.workbench.setting.WorkbenchConnectionProfilesListener;

/**
 * 为 MongoDB 集合与 Elasticsearch 索引/别名打开独立的工作台数据标签。
 *
 * @author liaojinlong
 * @since 2026/11/22 09:27
 */
public final class WorkbenchNoSqlObjectEditorProvider implements FileEditorProvider, DumbAware {
	/** 临时文件绑定的数据源快照，不包含密码或 API Key。 */
	private static final Key<NoSqlConnectionProfile> PROFILE = Key.create("mybatis.workbench.nosql.editor.profile");
	/** 临时文件所代表的 NoSQL 协议。 */
	private static final Key<NoSqlSourceType> TYPE = Key.create("mybatis.workbench.nosql.editor.type");
	/** MongoDB 集合所在数据库；Elasticsearch 编辑器为空。 */
	private static final Key<String> DATABASE = Key.create("mybatis.workbench.nosql.editor.database");
	/** MongoDB 集合名或 Elasticsearch 索引/别名。 */
	private static final Key<String> TARGET = Key.create("mybatis.workbench.nosql.editor.target");
	/** 标签标题，不含连接认证信息。 */
	private static final Key<String> TITLE = Key.create("mybatis.workbench.nosql.editor.title");

	/**
	 * 创建只绑定 MongoDB 数据库、集合和连接配置快照的虚拟文件。
	 *
	 * @param profile MongoDB 连接
	 * @param database 目标数据库
	 * @param collection 目标集合
	 * @return 可打开为 MongoDB 数据编辑器的内存文件
	 */
	static VirtualFile createMongoCollectionFile(NoSqlConnectionProfile profile, String database, String collection) {
		String title = profile.name() + " — " + database + "." + collection;
		LightVirtualFile file = new LightVirtualFile(fileName("mongo", database + "_" + collection, profile));
		file.setWritable(false);
		file.putUserData(PROFILE, profile);
		file.putUserData(TYPE, NoSqlSourceType.MONGODB);
		file.putUserData(DATABASE, database);
		file.putUserData(TARGET, collection);
		file.putUserData(TITLE, title);
		return file;
	}

	/**
	 * 创建只绑定 Elasticsearch 索引或别名和连接配置快照的虚拟文件。
	 *
	 * @param profile Elasticsearch 连接
	 * @param target 目标索引或别名
	 * @return 可打开为文档编辑器的内存文件
	 */
	static VirtualFile createElasticsearchTargetFile(NoSqlConnectionProfile profile, String target) {
		String title = profile.name() + " — " + target;
		LightVirtualFile file = new LightVirtualFile(fileName("elasticsearch", target, profile));
		file.setWritable(false);
		file.putUserData(PROFILE, profile);
		file.putUserData(TYPE, NoSqlSourceType.ELASTICSEARCH);
		file.putUserData(DATABASE, "");
		file.putUserData(TARGET, target);
		file.putUserData(TITLE, title);
		return file;
	}

	/**
	 * 打开 MongoDB 集合编辑器；已存在相同连接、数据库和集合的标签时聚焦复用。
	 *
	 * @param project 当前项目
	 * @param profile 连接配置
	 * @param database 集合所在数据库
	 * @param collection 集合名称
	 */
	static void openMongoCollectionEditor(Project project, NoSqlConnectionProfile profile, String database,
		String collection) {
		VirtualFile file = findOpenEditorFile(project, NoSqlSourceType.MONGODB, profile, database, collection);
		if (Objects.isNull(file)) {
			file = createMongoCollectionFile(profile, database, collection);
		}
		new OpenFileDescriptor(project, file).navigate(true);
	}

	/**
	 * 打开 Elasticsearch 目标编辑器；已存在相同连接和目标的标签时聚焦复用。
	 *
	 * @param project 当前项目
	 * @param profile 连接配置
	 * @param target 索引或别名
	 */
	static void openElasticsearchTargetEditor(Project project, NoSqlConnectionProfile profile, String target) {
		VirtualFile file = findOpenEditorFile(project, NoSqlSourceType.ELASTICSEARCH, profile, "", target);
		if (Objects.isNull(file)) {
			file = createElasticsearchTargetFile(profile, target);
		} else {
			NoSqlConnectionProfile openedProfile = file.getUserData(PROFILE);
			if (!Objects.equals(openedProfile, profile)) {
				boolean connectionChanged = Objects.isNull(openedProfile)
					|| !Objects.equals(openedProfile.endpoint(), profile.endpoint())
					|| !Objects.equals(openedProfile.username(), profile.username())
					|| openedProfile.tls() != profile.tls();
				// 元数据快照总是跟随已保存配置；仅连接字段变化时重载，保留重复打开时的查询状态。
				file.putUserData(PROFILE, profile);
				if (connectionChanged) {
					for (FileEditor editor : FileEditorManager.getInstance(project).getEditors(file)) {
						if (editor instanceof NoSqlObjectEditor noSqlEditor) {
							noSqlEditor.refreshElasticsearchProfile(profile, target);
						}
					}
				}
			}
		}
		new OpenFileDescriptor(project, file).navigate(true);
	}

	/**
	 * 查找已打开的同一连接对象页，身份使用稳定配置 ID 和完整目标名而非可能有截断或本地化的标题。
	 *
	 * @param project 当前项目
	 * @param type NoSQL 协议
	 * @param profile 连接配置快照
	 * @param database 数据库名；Elasticsearch 目标使用空字符串
	 * @param target 集合、索引或别名名
	 * @return 已打开的匹配标签文件；不存在时返回 null
	 */
	private static VirtualFile findOpenEditorFile(Project project, NoSqlSourceType type,
		NoSqlConnectionProfile profile, String database, String target) {
		for (VirtualFile file : FileEditorManager.getInstance(project).getOpenFiles()) {
			NoSqlConnectionProfile openedProfile = file.getUserData(PROFILE);
			if (Objects.nonNull(openedProfile) && Objects.equals(openedProfile.id(), profile.id())
				&& type == file.getUserData(TYPE) && Objects.equals(database, file.getUserData(DATABASE))
				&& Objects.equals(target, file.getUserData(TARGET))) {
				return file;
			}
		}
		return null;
	}

	/** 标签文件名只用于编辑器内部身份，不用于数据源请求。 */
	private static String fileName(String engine, String target, NoSqlConnectionProfile profile) {
		String safeTarget = target.replaceAll("[^A-Za-z0-9._-]", "_");
		String safeProfile = profile.id().toString().substring(0, 8);
		return engine + "_" + safeTarget + "_" + safeProfile + ".json";
	}

	/** {@inheritDoc} 仅接受带完整 NoSQL 编辑器元数据的内存文件。 */
	@Override
	public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
		return Objects.nonNull(file.getUserData(PROFILE)) && Objects.nonNull(file.getUserData(TYPE))
			&& Objects.nonNull(file.getUserData(TARGET)) && Objects.nonNull(file.getUserData(TITLE));
	}

	/** {@inheritDoc} 根据文件目标创建专用 NoSQL 数据编辑器。 */
	@Override
	public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
		return new NoSqlObjectEditor(file);
	}

	/** {@inheritDoc} 返回稳定的插件编辑器标识。 */
	@Override
	public @NotNull String getEditorTypeId() {
		return "mybatis-workbench-nosql-object-editor";
	}

	/** {@inheritDoc} 内存文件只显示本插件编辑器。 */
	@Override
	public @NotNull FileEditorPolicy getPolicy() {
		return FileEditorPolicy.HIDE_DEFAULT_EDITOR;
	}

	/**
	 * 通过现有 Mongo/Elasticsearch 工作台复用有界读取和冲突保护写入。
	 *
	 * @author liaojinlong
	 * @since 2026/11/22 09:27
	 */
	private static final class NoSqlObjectEditor extends UserDataHolderBase implements FileEditor {
		/** 打开该编辑器的虚拟文件。 */
		private final VirtualFile file;
		/** 文件标签所显示的面板。 */
		private final JComponent component;
		/** FileEditor 状态名称，不暴露连接地址或认证字段。 */
		private final String title;

		/**
		 * 从 PasswordSafe 读取连接凭据，交给限定在此编辑器生命周期内的面板。
		 *
		 * @param file 带 NoSQL 目标元数据的内存文件
		 */
		private NoSqlObjectEditor(VirtualFile file) {
			this.file = file;
			NoSqlConnectionProfile profile = Objects.requireNonNull(file.getUserData(PROFILE));
			NoSqlSourceType type = Objects.requireNonNull(file.getUserData(TYPE));
			String target = Objects.requireNonNull(file.getUserData(TARGET));
			title = Objects.requireNonNull(file.getUserData(TITLE));
			String secret = WorkbenchNoSqlSourceEditor.readNoSqlSecret(profile);
			if (type == NoSqlSourceType.MONGODB) {
				WorkbenchMongoPanel panel = new WorkbenchMongoPanel();
				panel.openMongoCollectionEditor(profile, secret, Objects.requireNonNull(file.getUserData(DATABASE)), target);
				component = panel;
			} else if (type == NoSqlSourceType.ELASTICSEARCH) {
				WorkbenchElasticsearchPanel panel = new WorkbenchElasticsearchPanel();
				panel.openElasticsearchTargetEditor(profile, secret, target);
				component = panel;
			} else {
				throw new IllegalArgumentException(message("nosql.editor.unsupported.type"));
			}
			if (type == NoSqlSourceType.ELASTICSEARCH) {
				ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(
					WorkbenchConnectionProfilesListener.NO_SQL_CONNECTION_SETTINGS_CHANGED,
					new WorkbenchConnectionProfilesListener() {
						/** {@inheritDoc} 此主题仅发送 NoSQL 本机设置变化。 */
						@Override
						public void connectionProfilesReloaded() {
						}

						/** {@inheritDoc} 仅更新本编辑器所绑定的数据源。 */
						@Override
						public void noSqlConnectionSettingsChanged(UUID profileId) {
							refreshElasticsearchConnectionSettings(profileId);
						}
					});
			}
		}

		/**
		 * 将已打开 Elasticsearch 标签切换到当前保存的认证配置，并重查原目标。
		 *
		 * @param profile 最新保存的数据源配置
		 * @param target 当前标签目标索引或别名
		 */
		private void refreshElasticsearchProfile(NoSqlConnectionProfile profile, String target) {
			if (component instanceof WorkbenchElasticsearchPanel panel) {
				String secret = WorkbenchNoSqlSourceEditor.readNoSqlSecret(profile);
				panel.openElasticsearchTargetEditor(profile, secret, target);
			}
		}

		/**
		 * 配置对话框保存后读取最新 profile 和本机秘密；连接字段相同则不重置用户查询状态。
		 *
		 * @param profileId 已保存配置的稳定标识
		 */
		private void refreshElasticsearchConnectionSettings(UUID profileId) {
			NoSqlConnectionProfile openedProfile = file.getUserData(PROFILE);
			if (Objects.isNull(openedProfile) || !openedProfile.id().equals(profileId)
				|| !(component instanceof WorkbenchElasticsearchPanel panel)) {
				return;
			}
			NoSqlConnectionProfile latestProfile = ApplicationManager.getApplication()
				.getService(WorkbenchConnectionProfiles.class).listNoSqlProfiles().stream()
				.filter(profile -> profile.id().equals(profileId) && profile.type() == NoSqlSourceType.ELASTICSEARCH)
				.findFirst().orElse(null);
			if (Objects.isNull(latestProfile)) {
				return;
			}
			file.putUserData(PROFILE, latestProfile);
			String secret = WorkbenchNoSqlSourceEditor.readNoSqlSecret(latestProfile);
			panel.refreshSavedConnectionIfChanged(latestProfile, secret);
		}

		/** {@inheritDoc} 返回数据库查询面板。 */
		@Override
		public @NotNull JComponent getComponent() {
			return component;
		}

		/** {@inheritDoc} 将焦点交给查询结果控件。 */
		@Override
		public @NotNull JComponent getPreferredFocusedComponent() {
			return component;
		}

		/** {@inheritDoc} 返回数据源与集合/索引目标。 */
		@Override
		public @NotNull String getName() {
			return title;
		}

		/** {@inheritDoc} 结果页状态由服务端重新查询，不恢复旧连接快照。 */
		@Override
		public void setState(@NotNull FileEditorState state) {
		}

		/** {@inheritDoc} 当前查询面板没有待提交的本地改动。 */
		@Override
		public boolean isModified() {
			return false;
		}

		/** {@inheritDoc} 内存文件关闭后不再有效。 */
		@Override
		public boolean isValid() {
			return file.isValid();
		}

		/** {@inheritDoc} 编辑器只显示远端操作状态。 */
		@Override
		public void addPropertyChangeListener(PropertyChangeListener listener) {
		}

		/** {@inheritDoc} 编辑器不注册文件属性监听。 */
		@Override
		public void removePropertyChangeListener(PropertyChangeListener listener) {
		}

		/** {@inheritDoc} 返回当前内存文件。 */
		@Override
		public @NotNull VirtualFile getFile() {
			return file;
		}

		/** {@inheritDoc} 关闭面板并清空连接密码字段。 */
		@Override
		public void dispose() {
			if (component instanceof WorkbenchMongoPanel panel) {
				panel.dispose();
			} else if (component instanceof WorkbenchElasticsearchPanel panel) {
				panel.dispose();
			}
			file.putUserData(PROFILE, null);
			file.putUserData(TYPE, null);
			file.putUserData(DATABASE, null);
			file.putUserData(TARGET, null);
			file.putUserData(TITLE, null);
		}
	}
}
