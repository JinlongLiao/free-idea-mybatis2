package io.github.jinlongliao.mybatis.workbench.ui;

import static io.github.jinlongliao.mybatis.workbench.ui.WorkbenchBundle.message;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.ui.DocumentAdapter;
import com.intellij.util.ui.FormBuilder;

import java.awt.Component;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

import javax.swing.*;
import javax.swing.event.DocumentEvent;

import io.github.jinlongliao.mybatis.workbench.database.NoSqlConnectionProfile;
import io.github.jinlongliao.mybatis.workbench.database.NoSqlSourceType;

/**
 * 在统一连接配置对话框中编辑和保存非 JDBC 的连接信息。
 *
 * @author liaojinlong
 * @since 2026/11/18 16:51
 */
final class WorkbenchNoSqlSourceEditor {
	/**
	 * 对话框使用静态方法打开。
	 */
	private WorkbenchNoSqlSourceEditor() {
	}

	/**
	 * 创建或编辑非 JDBC 配置。共享配置保存成功后才更新本机凭据，清空并确认时删除该连接的凭据。
	 *
	 * @param parent   对话框归属组件
	 * @param type     数据库协议
	 * @param existing 预填表单的配置，空白新增时为空
	 * @param saveProfile 以编辑开始时的快照尝试保存共享配置，已变化时返回 false
	 * @return 已保存的配置，取消或配置冲突时为空
	 */
	static NoSqlConnectionProfile editNoSqlSource(Component parent, NoSqlSourceType type, NoSqlConnectionProfile existing,
		Predicate<NoSqlConnectionProfile> saveProfile) {
		String defaultAddress = switch (type) {
			case REDIS -> "localhost:6379";
			case MONGODB -> "mongodb://localhost:27017/";
			case ELASTICSEARCH -> "https://localhost:9200";
		};
		JTextField name = new JTextField(Objects.isNull(existing) ? "" : existing.name(), 25);
		JTextField address = new JTextField(Objects.isNull(existing) ? defaultAddress : existing.endpoint(), 25);
		JTextField username = new JTextField(Objects.isNull(existing) ? "" : existing.username(), 25);
		JTextField ca = new JTextField(Objects.isNull(existing) ? "" : readLocalCaPath(existing), 25);
		JTextField auth = new JTextField(Objects.isNull(existing) ? "admin" : existing.authenticationDatabase(), 25);
		JPasswordField secret = new JPasswordField(Objects.isNull(existing) ? "" : readNoSqlSecret(existing), 25);
		DocumentAdapter identityListener = new DocumentAdapter() {
			/** {@inheritDoc} 不把旧服务器或账号的凭据带到修改后的连接。 */
			@Override
			protected void textChanged(DocumentEvent event) {
				if (secret.getDocument().getLength() > 0) {
					secret.setText("");
				}
			}
		};
		address.getDocument().addDocumentListener(identityListener);
		username.getDocument().addDocumentListener(identityListener);
		auth.getDocument().addDocumentListener(identityListener);
		JCheckBox cluster = new JCheckBox(message("redis.cluster.mode"), Objects.nonNull(existing) && existing.cluster());
		JCheckBox tls = new JCheckBox("TLS", Objects.nonNull(existing) && existing.tls());
		// 与编辑地址或账号一样，传输模式改变后需要重新输入密码。
		tls.addItemListener(event -> secret.setText(""));
		JSpinner db = new JSpinner(new SpinnerNumberModel(Objects.isNull(existing) ? 0 : existing.logicalDatabase(),
			0, Integer.MAX_VALUE, 1));
		db.setEnabled(!cluster.isSelected());
		cluster.addActionListener(event -> db.setEnabled(!cluster.isSelected()));

		FormBuilder fields = FormBuilder.createFormBuilder();
		fields.addLabeledComponent(message("connection.name"), name);
		fields.addLabeledComponent(switch (type) {
			case REDIS -> message("seed.nodes");
			case MONGODB -> "MongoDB URI";
			case ELASTICSEARCH -> message("https.url");
		}, address);
		if (type != NoSqlSourceType.ELASTICSEARCH) {
			fields.addLabeledComponent(message("username"), username);
		}
		if (type == NoSqlSourceType.MONGODB) {
			fields.addLabeledComponent(message("authentication.database"), auth);
		}
		if (type == NoSqlSourceType.REDIS) {
			fields.addLabeledComponent(message("database.number"), db);
			fields.addLabeledComponent(message("connection.mode"), cluster);
			fields.addLabeledComponent("TLS", tls);
		}
		fields.addLabeledComponent(type == NoSqlSourceType.ELASTICSEARCH ? "API Key" : message("password"), secret);
		if (type == NoSqlSourceType.ELASTICSEARCH) {
			fields.addLabeledComponent(message("private.ca.pem.optional"), ca);
		}
		JPanel connectionForm = fields.getPanel();
		while (JOptionPane.showConfirmDialog(parent, connectionForm,
			message(Objects.isNull(existing) ? "new.connection" : "connection.settings"),
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
			try {
				var profile = new NoSqlConnectionProfile(Objects.isNull(existing) ? UUID.randomUUID() : existing.id(),
					name.getText().strip(), type, address.getText().strip(), username.getText(), auth.getText().strip(),
					cluster.isSelected(), tls.isSelected(), cluster.isSelected() ? 0 : (Integer) db.getValue());
				profile.validateShareableProfile(WorkbenchBundle::message);
				if (!saveProfile.test(profile)) {
					secret.setText("");
					JOptionPane.showMessageDialog(parent, message("data.source.changed"), message("connection.settings"),
						JOptionPane.WARNING_MESSAGE);
					return null;
				}
				char[] value = secret.getPassword();
				try {
					PasswordSafe.getInstance().set(noSqlCredentialKey(profile),
						value.length == 0 ? null : new Credentials(profile.username(), new String(value)));
				} finally {
					Arrays.fill(value, '\0');
					secret.setText("");
				}
				if (type == NoSqlSourceType.ELASTICSEARCH) {
					PropertiesComponent.getInstance().setValue(localCaProperty(profile), ca.getText().strip(), "");
				}
				return profile;
			} catch (IllegalArgumentException failure) {
				JOptionPane.showMessageDialog(parent, failure.getMessage(), message("connection.settings"),
					JOptionPane.ERROR_MESSAGE);
			}
		}
		secret.setText("");
		return null;
	}

	/**
	 * 取回关联到本机的密码或 API Key。
	 *
	 * @param profile 当前保存的连接
	 * @return 凭据文本，未绑定时为空字符串
	 */
	static String readNoSqlSecret(NoSqlConnectionProfile profile) {
		Credentials credentials = PasswordSafe.getInstance().get(noSqlCredentialKey(profile));
		return Objects.isNull(credentials) ? "" : Objects.toString(credentials.getPasswordAsString(), "");
	}

	/**
	 * 清理已移除连接在本机保存的认证和证书路径。
	 *
	 * @param profile 已移除的数据源
	 */
	static void removeLocalNoSqlSettings(NoSqlConnectionProfile profile) {
		PasswordSafe.getInstance().set(noSqlCredentialKey(profile), null);
		if (profile.type() == NoSqlSourceType.ELASTICSEARCH) {
			PropertiesComponent.getInstance().unsetValue(localCaProperty(profile));
		}
	}

	/**
	 * 读取本机 Elasticsearch 私有 CA 文件路径。
	 *
	 * @param profile 已选数据源
	 * @return 当前设备的 PEM 路径，未配置时为空字符串
	 */
	static String readLocalCaPath(NoSqlConnectionProfile profile) {
		return PropertiesComponent.getInstance().getValue(localCaProperty(profile), "");
	}

	/**
	 * 将证书路径作为本机属性保留，避免路径通过连接设置横跨设备。
	 *
	 * @param profile Elasticsearch 数据源
	 * @return 本机属性名
	 */
	private static String localCaProperty(NoSqlConnectionProfile profile) {
		return "mybatis.workbench.elasticsearch.ca." + profile.id();
	}

	/**
	 * 根据连接协议、地址和用户分配本机凭据键。Redis 的 TLS 与非 TLS 使用独立键，旧键无法判断传输模式，因此不参与读取。
	 *
	 * @param profile 连接资料
	 * @return PasswordSafe 关联键
	 */
	static CredentialAttributes noSqlCredentialKey(NoSqlConnectionProfile profile) {
		String identity = profile.id() + "\n" + profile.type() + "\n" + profile.endpoint()
			+ "\n" + profile.username() + "\n" + profile.authenticationDatabase();
		if (profile.type() == NoSqlSourceType.REDIS) {
			identity += "\nredis-tls=" + profile.tls();
		}
		UUID key = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
		return new CredentialAttributes("MyBatis Workbench NoSQL " + key, profile.username());
	}

}
