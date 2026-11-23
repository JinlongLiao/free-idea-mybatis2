package io.github.jinlongliao.mybatis.workbench.ui;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.ide.plugins.PluginModuleId;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;

import org.jetbrains.annotations.NotNull;

import kotlin.coroutines.Continuation;

import java.util.Objects;

import io.github.jinlongliao.mybatis.workbench.setting.MybatisSetting;

/**
 * 宿主的原生专业数据库管理未启用时，注册独立数据库窗口。
 *
 * @author liaojinlong
 * @since 2026/11/18 15:11
 */
public final class WorkbenchDatabaseToolWindow implements ToolWindowFactory, DumbAware {
	/**
	 * 原生数据库的专业能力模块，仅加载基础数据库插件时不会启用。
	 */
	private static final PluginModuleId NATIVE_DATABASE_PRO =
		PluginModuleId.getId("intellij.database.pro", PluginModuleId.JETBRAINS_NAMESPACE);

	/**
	 * 返回宿主的原生专业数据库模块是否已实际启用。
	 *
	 * @return 已启用原生专业数据库模块时为 true
	 */
	public static boolean isNativeDatabaseAvailable() {
		return PluginManagerCore.getPluginSet().isModuleEnabled(NATIVE_DATABASE_PRO);
	}

	/**
	 * 可选 Java 描述符启用时才注册 MyBatis 设置服务，与 Mapper 扩展使用同一宿主条件。
	 *
	 * @return Java/MyBatis 扩展已在当前宿主注册时为 true
	 */
	public static boolean isJavaAvailable() {
		return Objects.nonNull(MybatisSetting.getInstance());
	}

	/**
	 * 宿主有原生专业数据库管理时沿用原生窗口，独立窗口的可见性与 Java 集成无关。
	 *
	 * @param project      将创建工具窗口的项目
	 * @param continuation 平台的协程上下文，此处立即返回结果
	 * @return 是否适用独立管理窗口
	 */
	@Override
	public Object isApplicableAsync(@NotNull Project project, @NotNull Continuation<? super Boolean> continuation) {
		return !isNativeDatabaseAvailable();
	}

	/**
	 * 创建项目专属窗口，项目关闭或内容移除时释放面板持有的任务和密码。
	 *
	 * @param project    当前项目
	 * @param toolWindow 独立数据库窗口
	 */
	@Override
	public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
		var contentManager = toolWindow.getContentManager();
		var factory = contentManager.getFactory();
		WorkbenchDatabasePanel panel = new WorkbenchDatabasePanel(project);
		Content content = factory.createContent(panel, WorkbenchBundle.message("data.sources"), false);
		content.setDisposer(panel);
		contentManager.addContent(content);
	}
}
