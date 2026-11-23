package io.github.jinlongliao.mybatis.workbench.ui;

import static io.github.jinlongliao.mybatis.workbench.ui.WorkbenchBundle.message;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBScrollPane;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;

/**
 * 在对象树旁选择要浏览的数据库或模式。勾选状态只保存在当前 IDE。
 *
 * @author liaojinlong
 * @since 2026/11/20 14:09
 */
final class WorkbenchDatabaseCatalogs {
	/** 工具类无需实例。 */
	private WorkbenchDatabaseCatalogs() {
	}

	/**
	 * @param sourceId 连接配置的稳定标识
	 * @param defaultSelection 尚未设置时的数据库范围
	 * @return 当前设备选择的数据库名
	 */
	static List<String> selectedDatabaseNames(UUID sourceId, List<String> defaultSelection) {
		List<String> saved = PropertiesComponent.getInstance().getList(namespaceSelectionKey(sourceId));
		return Objects.requireNonNullElse(saved, defaultSelection);
	}

	/**
	 * 仅在连接已确认删除后移除本机浏览范围。
	 *
	 * @param sourceId 已删除的配置 ID
	 */
	static void clearNamespaceSelection(UUID sourceId) {
		PropertiesComponent.getInstance().setList(namespaceSelectionKey(sourceId), null);
	}

	/**
	 * @param sourceId 数据源配置 ID
	 * @return 保存勾选项的本机属性键
	 */
	private static String namespaceSelectionKey(UUID sourceId) {
		return "mybatis.workbench.catalogs." + sourceId;
	}

	/**
	 * 在对象树附近打开可搜索的非模态数据库选择器，只有按应用后才保存设置。
	 *
	 * @param parent 树或当前入口组件
	 * @param sourceId 连接配置的稳定标识
	 * @param names 服务端当前返回的候选数据库名称
	 * @param initial 尚未保存时的预选数据库
	 * @param defaultName 服务端或 URI 指定的默认数据库名，可为空
	 * @param stillCurrent 应用时检查当前连接是否仍有效
	 * @param applied 保存成功后的刷新回调
	 */
	static void chooseDatabaseNames(Component parent, UUID sourceId, List<String> names, List<String> initial,
								 String defaultName, BooleanSupplier stillCurrent, Runnable applied) {
		chooseNamespaces(parent, sourceId, names, initial, defaultName, stillCurrent, false, applied);
	}

	/**
	 * 在对象树附近打开可搜索的非模态命名空间选择器；关闭或取消不会修改原选择。
	 *
	 * @param parent 树或当前入口组件
	 * @param sourceId 数据源连接标识
	 * @param names 当前连接返回的候选命名空间
	 * @param initial 尚未保存时的预选范围
	 * @param defaultName 默认数据库或模式，可为空
	 * @param stillCurrent 应用时检查连接和树节点仍有效
	 * @param schemas 是否选择模式而非目录
	 * @param applied 保存成功后的刷新回调
	 */
	static void chooseNamespaces(Component parent, UUID sourceId, List<String> names, List<String> initial,
							  String defaultName, BooleanSupplier stillCurrent, boolean schemas, Runnable applied) {
		String keyPrefix = schemas ? "database.schema." : "database.catalog.";
		List<String> candidates = names.stream().distinct().sorted().toList();
		List<String> selected = selectedDatabaseNames(sourceId, initial);
		JPanel content = new JPanel(new BorderLayout(6, 6));
		JTextField search = new JTextField();
		search.setToolTipText(message("database.selection.search"));
		search.getAccessibleContext().setAccessibleName(message("database.selection.search"));
		JCheckBox all = new JCheckBox(message(keyPrefix + "all"),
			!candidates.isEmpty() && selected.containsAll(candidates));
		all.setEnabled(!candidates.isEmpty());
		JPanel header = new JPanel(new BorderLayout(6, 0));
		header.add(search, BorderLayout.CENTER);
		header.add(all, BorderLayout.EAST);
		content.add(header, BorderLayout.NORTH);

		JPanel options = new JPanel();
		options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
		List<JCheckBox> choices = candidates.stream().map(name -> {
			String label = Objects.equals(name, defaultName)
				? name + "  (" + message(keyPrefix + "default") + ")" : name;
			JCheckBox option = new JCheckBox(label, selected.contains(name));
			option.putClientProperty("catalog.name", name);
			options.add(option);
			return option;
		}).toList();
		JBScrollPane scroll = new JBScrollPane(options);
		scroll.setPreferredSize(new Dimension(390, 300));
		content.add(scroll, BorderLayout.CENTER);
		JLabel count = new JLabel(message(keyPrefix + "candidate.count", candidates.size()));
		JPanel footer = new JPanel(new BorderLayout(6, 0));
		footer.add(count, BorderLayout.WEST);
		JPanel buttons = new JPanel();
		JButton cancel = new JButton(message("database.selection.cancel"));
		JButton apply = new JButton(message("database.selection.apply"));
		apply.setEnabled(!candidates.isEmpty());
		buttons.add(cancel);
		buttons.add(apply);
		footer.add(buttons, BorderLayout.EAST);
		content.add(footer, BorderLayout.SOUTH);
		if (candidates.isEmpty()) {
			options.add(new JLabel(message(keyPrefix + "empty")));
		}

		all.addActionListener(event -> choices.forEach(option -> option.setSelected(all.isSelected())));
		choices.forEach(option -> option.addActionListener(event ->
			all.setSelected(choices.stream().allMatch(JCheckBox::isSelected))));
		search.getDocument().addDocumentListener(new DocumentAdapter() {
			/** {@inheritDoc} 搜索只隐藏不匹配项，不改变候选数据库的勾选状态。 */
			@Override
			protected void textChanged(DocumentEvent event) {
				String query = search.getText().strip().toLowerCase(Locale.ROOT);
				choices.forEach(option -> {
					String name = (String) option.getClientProperty("catalog.name");
					option.setVisible(query.isEmpty() || name.toLowerCase(Locale.ROOT).contains(query));
				});
				options.revalidate();
				options.repaint();
			}
		});

		JBPopup popup = JBPopupFactory.getInstance().createComponentPopupBuilder(content, search)
			.setTitle(message(keyPrefix + "choose"))
			.setResizable(true)
			.setMovable(true)
			.setRequestFocus(true)
			.createPopup();
		cancel.addActionListener(event -> popup.cancel());
		apply.addActionListener(event -> {
			List<String> checked = choices.stream().filter(JCheckBox::isSelected)
				.map(option -> (String) option.getClientProperty("catalog.name")).toList();
			if (checked.isEmpty()) {
				count.setText(message(keyPrefix + "select.one"));
				return;
			}
			if (!stillCurrent.getAsBoolean()) {
				popup.cancel();
				return;
			}
			PropertiesComponent.getInstance().setList(namespaceSelectionKey(sourceId), checked);
			popup.cancel();
			applied.run();
		});
		popup.showUnderneathOf(parent);
	}
}
