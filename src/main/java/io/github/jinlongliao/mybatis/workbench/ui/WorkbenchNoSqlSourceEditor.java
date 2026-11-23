package io.github.jinlongliao.mybatis.workbench.ui;

import static io.github.jinlongliao.mybatis.workbench.ui.WorkbenchBundle.message;

import com.intellij.DynamicBundle;
import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.DocumentAdapter;
import com.intellij.util.ui.FormBuilder;
import com.mongodb.ConnectionString;
import com.mongodb.MongoConfigurationException;
import com.mongodb.MongoSecurityException;
import com.mongodb.MongoTimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.net.URLDecoder;

import java.awt.Component;
import java.awt.BorderLayout;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.UUID;
import java.util.function.Predicate;

import javax.swing.*;
import javax.swing.event.DocumentEvent;

import io.github.jinlongliao.mybatis.workbench.database.NoSqlConnectionProfile;
import io.github.jinlongliao.mybatis.workbench.database.NoSqlSourceType;
import io.github.jinlongliao.mybatis.workbench.setting.WorkbenchConnectionProfilesListener;
import io.github.jinlongliao.mybatis.workbench.database.WorkbenchMongoConnection;

/**
 * 在统一连接配置对话框中编辑和保存非 JDBC 的连接信息。
 *
 * @author liaojinlong
 * @since 2026/11/18 16:51
 */
final class WorkbenchNoSqlSourceEditor {
	/** 记录连接测试失败的异常链。 */
	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

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
		JTextField auth = new JTextField(Objects.isNull(existing) ? "" : existing.authenticationDatabase(), 25);
		JPasswordField secret = new JPasswordField(Objects.isNull(existing) ? "" : readNoSqlSecret(existing), 25);
		JLabel secretLabel = new JLabel(message(type == NoSqlSourceType.ELASTICSEARCH
			&& Objects.nonNull(existing) && !existing.username().isBlank() ? "password" :
			type == NoSqlSourceType.ELASTICSEARCH ? "search.api.key" : "password"));
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
		username.getDocument().addDocumentListener(new DocumentAdapter() {
			/** {@inheritDoc} Elasticsearch 用户名为空时输入 API Key，否则输入 Basic 密码。 */
			@Override
			protected void textChanged(DocumentEvent event) {
				if (type == NoSqlSourceType.ELASTICSEARCH) {
					secretLabel.setText(message(username.getText().isBlank() ? "search.api.key" : "password"));
				}
			}
		});
		JCheckBox cluster = new JCheckBox(message("redis.cluster.mode"), Objects.nonNull(existing) && existing.cluster());
		JCheckBox tls = new JCheckBox("TLS", Objects.nonNull(existing) && existing.tls());
		JCheckBox verifyCertificate = new JCheckBox("",
			Objects.isNull(existing) || readNoSqlCertificateVerification(existing));
		verifyCertificate.setToolTipText(message("elasticsearch.verify.certificate.tooltip"));
		// 与编辑地址或账号一样，传输模式改变后需要重新输入密码。
		tls.addItemListener(event -> secret.setText(""));
		JSpinner db = new JSpinner(new SpinnerNumberModel(Objects.isNull(existing) ? 0 : existing.logicalDatabase(),
			0, Integer.MAX_VALUE, 1));
		db.setEnabled(!cluster.isSelected());
		cluster.addActionListener(event -> db.setEnabled(!cluster.isSelected()));

		FormBuilder fields = FormBuilder.createFormBuilder();
		fields.addLabeledComponent(message("connection.name"), name);
		JLabel addressLabel = new JLabel(switch (type) {
			case REDIS -> message("redis.address.or.uri");
			case MONGODB -> "MongoDB URI";
			case ELASTICSEARCH -> message("https.url");
		});
		addressLabel.setIcon(WorkbenchDatabasePanel.databaseSourceIcon(type));
		addressLabel.setLabelFor(address);
		fields.addLabeledComponent(addressLabel, address);
		fields.addLabeledComponent(message("username"), username);
		if (type == NoSqlSourceType.MONGODB) {
			fields.addLabeledComponent(message("authentication.database"), auth);
			JButton pasteUri = new JButton(message("mongo.paste.uri"));
			pasteUri.addActionListener(event -> {
				JPasswordField uri = new JPasswordField(35);
				try {
					if (JOptionPane.showConfirmDialog(parent, uri, message("mongo.paste.uri"),
						JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
						char[] pasted = uri.getPassword();
						try {
							applyMongoUri(new String(pasted), name, address, username, auth, secret);
						} finally {
							Arrays.fill(pasted, '\0');
						}
					}
				} catch (IllegalArgumentException failure) {
					JOptionPane.showMessageDialog(parent, failure.getMessage(), message("connection.settings"),
						JOptionPane.ERROR_MESSAGE);
				} finally {
					uri.setText("");
				}
			});
			fields.addComponent(pasteUri);
			JButton test = new JButton(message("test.connection"));
			JLabel result = new JLabel();
			test.addActionListener(event -> {
				try {
					applyMongoUri(address.getText(), name, address, username, auth, secret);
					testMongoConnection(address.getText(), username.getText(), auth.getText(), secret.getPassword(),
						verifyCertificate.isSelected(), test, result);
				} catch (IllegalArgumentException failure) {
					result.setText(failure.getMessage());
				}
			});
			fields.addLabeledComponent(test, result);
		}
		if (type == NoSqlSourceType.REDIS) {
			address.setToolTipText(message("redis.address.example"));
			fields.addLabeledComponent(message("database.number"), db);
			fields.addLabeledComponent(message("connection.mode"), cluster);
			fields.addLabeledComponent("TLS", tls);
		}
		fields.addLabeledComponent(message("elasticsearch.verify.certificate"), verifyCertificate);
		fields.addLabeledComponent(secretLabel, secret);
		if (type == NoSqlSourceType.ELASTICSEARCH) {
			JPanel caField = new JPanel(new BorderLayout(4, 0));
			caField.add(ca, BorderLayout.CENTER);
			JButton chooseCa = new JButton(message("browse.ca.pem"));
			chooseCa.addActionListener(event -> {
				JFileChooser chooser = new JFileChooser();
				if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
					ca.setText(chooser.getSelectedFile().toPath().toString());
				}
			});
			caField.add(chooseCa, BorderLayout.EAST);
			fields.addLabeledComponent(message("private.ca.pem.optional"), caField);
		}
		JPanel connectionForm = fields.getPanel();
		while (JOptionPane.showConfirmDialog(parent, connectionForm,
			message(Objects.isNull(existing) ? "new.connection" : "connection.settings"),
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
			try {
				if (type == NoSqlSourceType.MONGODB) {
					applyMongoUri(address.getText(), name, address, username, auth, secret);
				} else if (type == NoSqlSourceType.REDIS) {
					applyRedisUri(address, username, secret, db, cluster.isSelected(), tls);
				}
				var profile = new NoSqlConnectionProfile(Objects.isNull(existing) ? UUID.randomUUID() : existing.id(),
					name.getText().strip(), type, address.getText().strip(),
					username.getText(), auth.getText().strip(),
					cluster.isSelected(), tls.isSelected(), cluster.isSelected() ? 0 : (Integer) db.getValue());
				profile.validateShareableProfile(WorkbenchBundle::message);
				if (!saveProfile.test(profile)) {
					secret.setText("");
					JOptionPane.showMessageDialog(parent, message("data.source.changed"), message("connection.settings"),
						JOptionPane.WARNING_MESSAGE);
					return null;
				}
				char[] value = secret.getPassword();
				char[] previousSecret = type == NoSqlSourceType.ELASTICSEARCH && Objects.nonNull(existing)
					? readNoSqlSecret(existing).toCharArray() : new char[0];
				boolean connectionSettingsChanged = false;
				try {
					if (type == NoSqlSourceType.ELASTICSEARCH && Objects.nonNull(existing)) {
						connectionSettingsChanged = !Objects.equals(existing.endpoint(), profile.endpoint())
							|| !Objects.equals(existing.username(), profile.username())
							|| !Objects.equals(readLocalCaPath(existing), ca.getText().strip())
							|| readElasticsearchCertificateVerification(existing) != verifyCertificate.isSelected()
							|| !Arrays.equals(previousSecret, value);
					}
					PasswordSafe.getInstance().set(noSqlCredentialKey(profile),
						value.length == 0 ? null : new Credentials(profile.username(), new String(value)));
				} finally {
					Arrays.fill(value, '\0');
					Arrays.fill(previousSecret, '\0');
					secret.setText("");
				}
				if (type == NoSqlSourceType.ELASTICSEARCH) {
					PropertiesComponent.getInstance().setValue(localCaProperty(profile), ca.getText().strip(), "");
				}
				PropertiesComponent.getInstance().setValue(localCertificateVerificationProperty(profile),
					verifyCertificate.isSelected(), defaultNoSqlCertificateVerification(profile));
				if (type == NoSqlSourceType.ELASTICSEARCH && connectionSettingsChanged) {
					ApplicationManager.getApplication().getMessageBus()
						.syncPublisher(WorkbenchConnectionProfilesListener.NO_SQL_CONNECTION_SETTINGS_CHANGED)
						.noSqlConnectionSettingsChanged(profile.id());
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
	 * 从完整 URI 拆出 MongoDB 凭据。地址、账号和认证库先回填，密码最后写入，
	 * 避免字段监听器清掉新密码。URI 中没有凭据时保留手动填写的账号与认证库。
	 *
	 * @param input 用户输入的 URI
	 * @param name 连接名称，留空时使用 URI 中的目标数据库或第一台服务器
	 * @param address 继续保存到连接配置的无凭据地址
	 * @param username URI 内认证用户名的目标字段
	 * @param authDatabase URI 认证库的目标字段
	 * @param secret 最终由本机 PasswordSafe 保存的密码字段
	 * @throws IllegalArgumentException URI 无效或认证信息不完整
	 */
	static void applyMongoUri(String input, JTextField name, JTextField address, JTextField username, JTextField authDatabase,
							 JPasswordField secret) {
		String uri = input.strip();
		if (!uri.startsWith("mongodb://") && !uri.startsWith("mongodb+srv://")) {
			throw new IllegalArgumentException(message("mongo.invalid.full.uri"));
		}
		int authority = uri.indexOf("://") + 3;
		int end = uri.length();
		for (char delimiter : new char[]{'/', '?', '#'}) {
			int position = uri.indexOf(delimiter, authority);
			if (position >= 0) {
				end = Math.min(end, position);
			}
		}
		int separator = uri.indexOf('@', authority);
		ConnectionString parsed;
		try {
			// 只在本地解析。SRV/TXT 由驱动在后台真正连接时解析。
			parsed = new ConnectionString(uri, (host, recordType) -> List.of());
		} catch (IllegalArgumentException | MongoConfigurationException failure) {
			// 解析异常可能包含完整 URI。界面只显示固定错误说明。
			throw new IllegalArgumentException(message("mongo.invalid.full.uri"));
		}
		if (separator < 0 || separator >= end) {
			if (!uri.equals(address.getText())) {
				address.setText(uri);
			}
			fillMongoConnectionName(name, parsed);
			return;
		}
		var credential = parsed.getCredential();
		if (Objects.isNull(credential)) {
			throw new IllegalArgumentException(message("mongo.invalid.full.uri"));
		}
		char[] password = credential.getPassword();
		if (Objects.isNull(password)) {
			throw new IllegalArgumentException(message("mongo.invalid.full.uri"));
		}
		try {
			address.setText(uri.substring(0, authority) + uri.substring(separator + 1));
			username.setText(credential.getUserName());
			authDatabase.setText(uri.startsWith("mongodb+srv://")
				&& !uri.matches("(?is).*?[?&;]authSource=.*") ? "" : credential.getSource());
			secret.setText(new String(password));
			fillMongoConnectionName(name, parsed);
		} finally {
			Arrays.fill(password, '\0');
		}
	}

	/**
	 * 为仅粘贴 URI 的新连接填写名称，保留用户已输入的名称。
	 *
	 * @param name 连接名称字段
	 * @param parsed 已在本地解析的地址
	 */
	private static void fillMongoConnectionName(JTextField name, ConnectionString parsed) {
		if (name.getText().isBlank()) {
			name.setText(Objects.toString(parsed.getDatabase(), parsed.getHosts().getFirst()));
		}
	}

	/**
	 * 从 Redis URL 填写种子地址、TLS、逻辑库与 URI 中的认证信息。
	 *
	 * @param address 编辑中的地址或 URL
	 * @param username 认证账号字段
	 * @param secret 当前设备密码字段
	 * @param database 所选 Redis 逻辑库
	 * @param cluster 当前是否以 Cluster 模式连接
	 * @param tls 当前 TLS 设置
	 * @throws IllegalArgumentException Redis URL 不可解析或 Cluster 指定了非零库
	 */
	static void applyRedisUri(JTextField address, JTextField username, JPasswordField secret, JSpinner database,
							  boolean cluster, JCheckBox tls) {
		String input = address.getText().strip();
		String url = input.startsWith("jdbc:") ? input.substring(5) : input;
		if (!url.contains("://")) {
			return;
		}
		URI uri;
		int index;
		String user = null;
		char[] password = null;
		try {
			uri = URI.create(url);
			if ((!"redis".equals(uri.getScheme()) && !"rediss".equals(uri.getScheme())) || Objects.isNull(uri.getHost())
				|| Objects.nonNull(uri.getRawQuery()) || Objects.nonNull(uri.getRawFragment()) || uri.getPort() > 65535) {
				throw new IllegalArgumentException();
			}
			String path = uri.getPath();
			index = path.isEmpty() || "/".equals(path) ? 0 : Integer.parseInt(path.substring(1));
			if (index < 0 || (cluster && index != 0)) {
				throw new IllegalArgumentException();
			}
			if (Objects.nonNull(uri.getRawUserInfo())) {
				String[] credentials = uri.getRawUserInfo().split(":", 2);
				if (credentials.length != 2) {
					throw new IllegalArgumentException();
				}
				user = URLDecoder.decode(credentials[0].replace("+", "%2B"), StandardCharsets.UTF_8);
				password = URLDecoder.decode(credentials[1].replace("+", "%2B"), StandardCharsets.UTF_8).toCharArray();
			}
		} catch (IllegalArgumentException failure) {
			throw new IllegalArgumentException(message("redis.invalid.full.uri"));
		}
		try {
			// 规范化地址会触发监听器，URL 无内嵌密码时保留本次表单输入，身份变化时监听器已清理旧凭据。
			if (Objects.isNull(password)) {
				password = secret.getPassword();
			}
			address.setText(uri.getHost() + ":" + (uri.getPort() < 0 ? 6379 : uri.getPort()));
			database.setValue(index);
			tls.setSelected("rediss".equals(uri.getScheme()));
			if (Objects.nonNull(user)) {
				username.setText(user);
			}
			secret.setText(new String(password));
		} finally {
			Arrays.fill(password, '\0');
		}
	}

	/**
	 * 测试当前 MongoDB 输入，无需先保存连接或列出所有数据库。
	 *
	 * @param uri 不含明文凭据的服务器 URI
	 * @param username 当前输入的账号
	 * @param authDatabase 当前输入的认证库
	 * @param password 本次后台操作拥有的密码副本
	 * @param test 当前测试按钮
	 * @param result 当前对话框的测试结果
	 */
	private static void testMongoConnection(String uri, String username, String authDatabase, char[] password,
													boolean verifyCertificate, JButton test, JLabel result) {
		test.setEnabled(false);
		result.setText(message("test.connection") + message("in.progress"));
		new SwingWorker<Void, Void>() {
			/** {@inheritDoc} 后台执行 ping，包含服务器选择和认证。 */
			@Override
			protected Void doInBackground() {
				try (var connection = new WorkbenchMongoConnection(uri.strip(), username, authDatabase.strip(),
					password, verifyCertificate, DynamicBundle.getLocale())) {
					connection.pingMongoServer();
					return null;
				} finally {
					Arrays.fill(password, '\0');
				}
			}

			/** {@inheritDoc} 对话框中显示安全摘要，根因留在日志。 */
			@Override
			protected void done() {
				test.setEnabled(true);
				try {
					get();
					result.setText(message("mongo.test.succeeded"));
				} catch (InterruptedException failure) {
					Thread.currentThread().interrupt();
				} catch (ExecutionException failure) {
					Throwable cause = failure.getCause();
					log.warn("MongoDB 连接测试失败，阶段=ping，地址={}，用户={}，认证库={}", uri, username, authDatabase, cause);
					String key = cause instanceof MongoSecurityException ? "mongo.error.authentication"
						: cause instanceof MongoTimeoutException ? "mongo.error.network" : "mongo.test.failed";
					result.setText(message(key));
				}
			}
		}.execute();
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
	 * 清理已移除连接在本机保存的认证及协议专用设置。
	 *
	 * @param profile 已移除的数据源
	 */
	static void removeLocalNoSqlSettings(NoSqlConnectionProfile profile) {
		PasswordSafe.getInstance().set(noSqlCredentialKey(profile), null);
		if (profile.type() == NoSqlSourceType.ELASTICSEARCH) {
			PropertiesComponent.getInstance().unsetValue(localCaProperty(profile));
		} else if (profile.type() == NoSqlSourceType.REDIS) {
			WorkbenchNoSqlNavigator.clearRedisTreeFilter(profile);
		}
		PropertiesComponent.getInstance().unsetValue(localCertificateVerificationProperty(profile));
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
	 * 读取当前 Elasticsearch 配置是否需要校验服务器证书链与主机名；未保存的旧配置默认开启校验。
	 *
	 * @param profile Elasticsearch 数据源
	 * @return true 表示校验证书，false 表示仅跳过证书身份校验并继续使用 HTTPS
	 */
	static boolean readElasticsearchCertificateVerification(NoSqlConnectionProfile profile) {
		return readNoSqlCertificateVerification(profile);
	}

	/**
	 * 读取按连接保存在本机的 NoSQL 证书校验偏好；旧配置默认校验证书。
	 *
	 * @param profile NoSQL 数据源
	 * @return true 表示验证服务器证书链和主机名
	 */
	static boolean readNoSqlCertificateVerification(NoSqlConnectionProfile profile) {
		return PropertiesComponent.getInstance().getBoolean(localCertificateVerificationProperty(profile),
			defaultNoSqlCertificateVerification(profile));
	}

	/**
	 * 保留旧 Elasticsearch 数据源的本机校验偏好，其他数据源默认验证证书。
	 *
	 * @param profile NoSQL 数据源
	 * @return 未设置当前属性时采用的证书校验值
	 */
	private static boolean defaultNoSqlCertificateVerification(NoSqlConnectionProfile profile) {
		return profile.type() == NoSqlSourceType.ELASTICSEARCH
			? PropertiesComponent.getInstance().getBoolean("mybatis.workbench.elasticsearch.verify-certificate."
				+ profile.id(), true) : true;
	}

	/**
	 * 证书校验开关按设备保存，避免本机信任策略同步到其他 IDE。
	 *
	 * @param profile Elasticsearch 数据源
	 * @return 本机属性名
	 */
	private static String localCertificateVerificationProperty(NoSqlConnectionProfile profile) {
		return "mybatis.workbench.nosql.verify-certificate." + profile.id();
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
