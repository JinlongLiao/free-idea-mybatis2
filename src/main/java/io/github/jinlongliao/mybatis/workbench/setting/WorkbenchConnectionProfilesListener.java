package io.github.jinlongliao.mybatis.workbench.setting;

import com.intellij.util.messages.Topic;

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

	/**
	 * 配置完整载入后在 EDT 通知订阅者。
	 */
	void connectionProfilesReloaded();
}
