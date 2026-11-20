package io.github.jinlongliao.mybatis.workbench.action;

import static io.github.jinlongliao.mybatis.workbench.ui.WorkbenchBundle.message;

import com.intellij.database.psi.DbTable;
import com.intellij.database.util.DasUtil;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiElement;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import io.github.jinlongliao.mybatis.workbench.model.DbType;
import io.github.jinlongliao.mybatis.workbench.model.GenerationTable;
import io.github.jinlongliao.mybatis.workbench.ui.MybatisGeneratorMainUI;
import io.github.jinlongliao.mybatis.workbench.ui.WorkbenchDatabaseToolWindow;
import io.github.jinlongliao.mybatis.workbench.util.DbToolsUtils;
import io.github.jinlongliao.mybatis.workbench.util.StringUtils;

public class MybatisGeneratorMainAction extends AnAction {


	/**
	 * {@inheritDoc} 菜单可用性只读取宿主已加载模块，无需访问 Swing。
	 */
	@Override
	public @NotNull ActionUpdateThread getActionUpdateThread() {
		return ActionUpdateThread.BGT;
	}

	/**
	 * 仅在宿主同时启用 Java 和原生专业数据库能力时公开原生右键入口。
	 *
	 * @param event 当前菜单更新事件
	 */
	@Override
	public void update(@NotNull AnActionEvent event) {
		event.getPresentation().setEnabledAndVisible(WorkbenchDatabaseToolWindow.isJavaAvailable()
			&& WorkbenchDatabaseToolWindow.isNativeDatabaseAvailable());
	}

	/**
	 * 从原生数据库树收集表结构，再打开通用生成窗口。
	 *
	 * @param e 原生数据库树产生的操作事件
	 */
	@Override
	public void actionPerformed(AnActionEvent e) {
		if (!WorkbenchDatabaseToolWindow.isJavaAvailable() || !WorkbenchDatabaseToolWindow.isNativeDatabaseAvailable()) {
			return;
		}
		PsiElement[] psiElements = e.getData(LangDataKeys.PSI_ELEMENT_ARRAY);
		if (Objects.isNull(psiElements) || psiElements.length == 0) {
			Messages.showMessageDialog(message("native.generator.select.tables"), message("generate.mybatis"),
				Messages.getInformationIcon());
			return;
		}
		List<GenerationTable> generationTables = new ArrayList<>(psiElements.length);
		for (PsiElement psiElement : psiElements) {
			if (!(psiElement instanceof DbTable selectedTable)) {
				Messages.showMessageDialog(message("native.generator.select.tables"), message("generate.mybatis"),
					Messages.getInformationIcon());
				return;
			}
			String driverClass = selectedTable.getDataSource().getConnectionConfig().getDriverClass();
			DbType dbType = resolveNativeDatabaseType(driverClass);
			if (Objects.isNull(dbType)) {
				Messages.showMessageDialog(message("native.generator.unsupported.driver", String.valueOf(driverClass)),
					message("generate.mybatis"),
					Messages.getInformationIcon());
				return;
			}
			String qualifier = DasUtil.getSchema(selectedTable.getDasObject());
			if (StringUtils.isEmpty(qualifier)) {
				qualifier = DasUtil.getCatalog(selectedTable.getDasObject());
			}
			generationTables.add(new GenerationTable(DbToolsUtils.buildIntellijTableInfo(selectedTable), dbType, qualifier));
		}
		new MybatisGeneratorMainUI(e.getProject(), generationTables);
	}

	/**
	 * 根据原生连接配置识别生成器支持的数据库。
	 *
	 * @param driverClass 原生连接的 JDBC 驱动类名
	 * @return 识别结果，驱动未知时为空
	 */
	private static DbType resolveNativeDatabaseType(String driverClass) {
		if (Objects.isNull(driverClass)) {
			return null;
		}
		String normalized = driverClass.toLowerCase(Locale.ROOT);
		for (DbType dbType : DbType.values()) {
			if (normalized.contains(dbType.name().toLowerCase(Locale.ROOT))) {
				return dbType;
			}
		}
		return null;
	}

}
