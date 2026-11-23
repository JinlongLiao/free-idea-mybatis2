package io.github.jinlongliao.mybatis.workbench.setting;

import com.intellij.util.messages.Topic;

import java.util.UUID;

/**
 * 把 IDE 重新加载的全局数据源配置通知到已打开的数据库窗口。
 *
 * @author liaojinlong
 * @since 2026/11/19 08:44
 */
@FunctionalInterface
public interface WorkbenchConnectionProfilesListener {
	/**
	 * 应用总线主题，窗口在自身生命周期内订阅。
	 */
	Topic<WorkbenchConnectionProfilesListener> PROFILES_RELOADED =
		Topic.create("MyBatis Workbench connection profiles reloaded", WorkbenchConnectionProfilesListener.class);
	/** 配置保存后通知当前打开的 NoSQL 面板重新读取本机凭据和传输设置。 */
	Topic<WorkbenchConnectionProfilesListener> NO_SQL_CONNECTION_SETTINGS_CHANGED =
		Topic.create("MyBatis Workbench NoSQL connection settings changed", WorkbenchConnectionProfilesListener.class);

	/**
	 * 配置完整载入后在 EDT 通知订阅者。
	 */
	void connectionProfilesReloaded();

	/**
	 * 指定 NoSQL 配置及其本机凭据、证书设置保存完成后发送；事件不携带秘密值。
	 *
	 * @param profileId 已保存配置的稳定标识
	 */
	default void noSqlConnectionSettingsChanged(UUID profileId) {
	}
}
