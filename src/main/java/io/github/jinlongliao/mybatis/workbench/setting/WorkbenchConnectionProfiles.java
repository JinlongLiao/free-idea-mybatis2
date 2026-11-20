package io.github.jinlongliao.mybatis.workbench.setting;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;

import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.invoke.MethodHandles;
import java.util.*;

import io.github.jinlongliao.mybatis.workbench.database.JdbcConnectionProfile;
import io.github.jinlongliao.mybatis.workbench.database.NoSqlConnectionProfile;
import io.github.jinlongliao.mybatis.workbench.database.NoSqlSourceType;

/**
 * 应用级连接设置。IDE 的 Backup and Sync 可同步该设置文件。
 *
 * @author liaojinlong
 * @since 2026/11/18 15:22
 */
@State(name = "MyBatisWorkbenchConnections", category = SettingsCategory.TOOLS,
	storages = @Storage(value = "mybatis-workbench-connections.xml", roamingType = RoamingType.DEFAULT))
public final class WorkbenchConnectionProfiles implements PersistentStateComponent<Element> {
	/**
	 * 记录无法读取的配置项，其他配置继续加载。
	 */
	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	/**
	 * 以稳定 ID 替换已保存连接，保持 UI 顺序，由实例锁保护。
	 */
	private final Map<UUID, JdbcConnectionProfile> profiles = new LinkedHashMap<>();

	/**
	 * @return 当前保存的连接配置快照
	 */
	public synchronized List<JdbcConnectionProfile> listProfiles() {
		return List.copyOf(profiles.values());
	}

	/**
	 * 以稳定 ID 保存一个配置，支持本机编辑及同步后的读取。
	 *
	 * @param profile 要保存的无独立凭据连接配置
	 */
	public synchronized void saveProfile(JdbcConnectionProfile profile) {
		profile.validateShareableProfile();
		profiles.put(profile.id(), profile);
	}

	/**
	 * 非 JDBC 连接设置，按保存顺序展示，由实例锁保护。
	 */
	private final Map<UUID, NoSqlConnectionProfile> noSqlProfiles = new LinkedHashMap<>();

	/**
	 * @return 非 JDBC 连接配置的快照
	 */
	public synchronized List<NoSqlConnectionProfile> listNoSqlProfiles() {
		return List.copyOf(noSqlProfiles.values());
	}

	/**
	 * 保存非 JDBC 连接的可漫游设置。
	 *
	 * @param profile 不含密码、API Key 和设备路径的配置
	 */
	public synchronized void saveNoSqlProfile(NoSqlConnectionProfile profile) {
		profile.validateShareableProfile();
		noSqlProfiles.put(profile.id(), profile);
	}

	/**
	 * 移除用户确认的 JDBC 配置。若同步已替换该配置，则保留新设置。
	 *
	 * @param profile 确认删除时显示的配置快照
	 * @return 是否成功移除
	 */
	public synchronized boolean removeProfile(JdbcConnectionProfile profile) {
		return profiles.remove(profile.id(), profile);
	}

	/**
	 * 移除用户确认的非 JDBC 配置。若同步已替换该配置，则保留新设置。
	 *
	 * @param profile 确认删除时显示的配置快照
	 * @return 是否成功移除
	 */
	public synchronized boolean removeNoSqlProfile(NoSqlConnectionProfile profile) {
		return noSqlProfiles.remove(profile.id(), profile);
	}

	/**
	 * 按确认时的快照导入 JDBC 配置，保护确认期间重载的设置。
	 * @param expected 对话框展示的本机版本，首次导入时为空
	 * @param incoming 文件中的新版本
	 * @return 是否成功写入
	 */
	public synchronized boolean replaceImportedProfile(JdbcConnectionProfile expected, JdbcConnectionProfile incoming) {
		incoming.validateShareableProfile();
		if (Objects.isNull(expected)) {
			return Objects.isNull(profiles.putIfAbsent(incoming.id(), incoming));
		}
		return expected.id().equals(incoming.id()) && profiles.replace(expected.id(), expected, incoming);
	}

	/**
	 * 按确认时的快照导入非 JDBC 配置，保护确认期间重载的设置。
	 * @param expected 对话框展示的本机版本，首次导入时为空
	 * @param incoming 文件中的新版本
	 * @return 是否成功写入
	 */
	public synchronized boolean replaceImportedNoSqlProfile(NoSqlConnectionProfile expected, NoSqlConnectionProfile incoming) {
		incoming.validateShareableProfile();
		if (Objects.isNull(expected)) {
			return Objects.isNull(noSqlProfiles.putIfAbsent(incoming.id(), incoming));
		}
		return expected.id().equals(incoming.id()) && noSqlProfiles.replace(expected.id(), expected, incoming);
	}

	/**
	 * {@inheritDoc} 使用与跨平台导出一致的内容，每个配置单独存放。
	 */
	@Override
	public synchronized @NotNull Element getState() {
		Element root = new Element("connections");
		for (JdbcConnectionProfile profile : profiles.values()) {
			try {
				StringWriter writer = new StringWriter();
				profile.writeProfile(writer);
				root.addContent(new Element("connection").setText(writer.toString()));
			} catch (IOException failure) {
				throw new IllegalStateException("无法序列化连接 " + profile.id(), failure);
			}
		}
		for (NoSqlConnectionProfile profile : noSqlProfiles.values()) {
			root.addContent(new Element("nosql")
				.setAttribute("id", profile.id().toString()).setAttribute("name", profile.name())
				.setAttribute("type", profile.type().name()).setAttribute("endpoint", profile.endpoint())
				.setAttribute("username", profile.username()).setAttribute("authDatabase", profile.authenticationDatabase())
				.setAttribute("cluster", Boolean.toString(profile.cluster())).setAttribute("tls", Boolean.toString(profile.tls()))
				.setAttribute("logicalDatabase", Integer.toString(profile.logicalDatabase())));
		}
		return root;
	}

	/**
	 * {@inheritDoc} IDE 启动及设置同步后替换现有配置，并在 EDT 通知已打开的窗口。
	 */
	@Override
	public synchronized void loadState(@NotNull Element state) {
		profiles.clear();
		for (Element item : state.getChildren("connection")) {
			try {
				saveProfile(JdbcConnectionProfile.readProfile(new StringReader(item.getText())));
			} catch (IOException failure) {
				log.warn("无法读取 MyBatis Workbench 连接配置，阶段=设置加载", failure);
			}
		}
		noSqlProfiles.clear();
		for (Element item : state.getChildren("nosql")) {
			try {
				saveNoSqlProfile(new NoSqlConnectionProfile(UUID.fromString(item.getAttributeValue("id", "")),
					item.getAttributeValue("name", ""), NoSqlSourceType.valueOf(item.getAttributeValue("type", "")),
					item.getAttributeValue("endpoint", ""), item.getAttributeValue("username", ""),
					item.getAttributeValue("authDatabase", ""), Boolean.parseBoolean(item.getAttributeValue("cluster")),
					Boolean.parseBoolean(item.getAttributeValue("tls")),
					Integer.parseInt(item.getAttributeValue("logicalDatabase", "0"))));
			} catch (IllegalArgumentException failure) {
				log.warn("无法读取非 JDBC 连接配置，阶段=设置加载", failure);
			}
		}
		var application = ApplicationManager.getApplication();
		if (Objects.nonNull(application) && !application.isDisposed()) {
			// 设置可能在后台重载，推迟派发以避免持有配置锁时回调窗口。
			application.invokeLater(() -> {
				if (!application.isDisposed()) {
					application.getMessageBus().syncPublisher(WorkbenchConnectionProfilesListener.PROFILES_RELOADED)
						.connectionProfilesReloaded();
				}
			});
		}
	}
}
