package io.github.jinlongliao.mybatis.workbench.database;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

import io.github.jinlongliao.mybatis.workbench.model.DbType;

/**
 * 使用用户选定的驱动文件建立 JDBC 连接，连接关闭后由调用方释放驱动加载器。
 *
 * @author liaojinlong
 * @since 2026/11/18 14:44
 */
public final class JdbcDriverConnection implements AutoCloseable {
	/**
	 * 执行计划入口接受的可解释 SQL 起始关键字，跳过脚本和管理命令。
	 */
	private static final Pattern EXPLAINABLE_SQL = Pattern.compile("(?i)^(SELECT|WITH|INSERT|UPDATE|DELETE)\\b");

	/**
	 * 驱动及其依赖的加载器，在连接结束前保持可用。
	 */
	private final URLClassLoader driverClassLoader;

	/**
	 * 当前打开的物理连接，不在线程间并发执行 JDBC 操作。
	 */
	private final Connection connection;

	/**
	 * 按驱动依赖顺序加载 JAR 并连接数据库。密码由调用方从凭据存储读取。
	 *
	 * @param driverFiles     驱动 JAR 及依赖文件，按类加载优先级排列
	 * @param driverClassName JDBC 驱动实现类
	 * @param jdbcUrl         JDBC 连接地址
	 * @param loginProperties 用于本次连接的登录属性
	 * @throws Exception 包含驱动加载、连接及相关资源关闭失败
	 */
	public JdbcDriverConnection(List<Path> driverFiles, String driverClassName, String jdbcUrl,
								Properties loginProperties) throws Exception {
		URL[] urls = new URL[driverFiles.size()];
		for (int index = 0; index < urls.length; index++) {
			urls[index] = driverFiles.get(index).toUri().toURL();
		}
		driverClassLoader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
		try {
			Class<? extends Driver> driverType = Class.forName(driverClassName, true, driverClassLoader)
				.asSubclass(Driver.class);
			Driver driver = driverType.getDeclaredConstructor().newInstance();
			connection = driver.connect(jdbcUrl, loginProperties);
			if (Objects.isNull(connection)) {
				throw new SQLException("The selected JDBC driver does not accept this connection URL");
			}
		} catch (Exception | LinkageError failure) {
			try {
				driverClassLoader.close();
			} catch (Exception closeFailure) {
				failure.addSuppressed(closeFailure);
			}
			throw failure;
		}
	}

	/**
	 * 返回当前连接。调用方在此对象的作用域内使用它，最后统一关闭。
	 *
	 * @return 本次打开的 JDBC 连接
	 */
	public Connection connection() {
		return connection;
	}

	/**
	 * 在当前连接执行一条 SQL，读取首个结果并关闭语句与游标。
	 * 事务和提交状态由调用方控制。调用方应在后台线程执行并串行使用本连接。
	 *
	 * @param sql            用户提交的一条 SQL
	 * @param rowLimit       显示行数上限，至少为 1，最多为 10000
	 * @param timeoutSeconds JDBC 执行超时，单位为秒，0 表示使用驱动的无超时行为
	 * @return 截断标记及结果行，或驱动报告的更新行数
	 * @throws SQLException             语句执行、结果读取或资源关闭失败
	 * @throws IllegalArgumentException 行数或超时超出允许范围
	 */
	public JdbcQueryResult executeSql(String sql, int rowLimit, int timeoutSeconds) throws SQLException {
		if (rowLimit < 1 || rowLimit > 10_000 || timeoutSeconds < 0) {
			throw new IllegalArgumentException("Invalid SQL row limit or timeout");
		}
		try (Statement statement = connection.createStatement()) {
			statement.setMaxRows(rowLimit + 1);
			if (timeoutSeconds > 0) {
				statement.setQueryTimeout(timeoutSeconds);
			}
			if (!statement.execute(sql)) {
				long count = statement.getUpdateCount();
				return new JdbcQueryResult(List.of(), List.of(), false,
					count < 0 ? OptionalLong.empty() : OptionalLong.of(count));
			}
			try (ResultSet result = statement.getResultSet()) {
				return readSqlResult(result, rowLimit);
			}
		}
	}

	/**
	 * 读取优化器估算计划。只接受单条 SQL，并使用各数据库的非 ANALYZE 语法。
	 * 为避免把可能含多条语句的内容交给驱动，内部出现分号的输入须先在编辑器中缩小选区。
	 *
	 * @param dbType         当前连接对应的数据库方言
	 * @param sql            以 SELECT、WITH、INSERT、UPDATE 或 DELETE 开始的语句
	 * @param rowLimit       展示的最大计划行数
	 * @param timeoutSeconds JDBC 执行超时，单位为秒
	 * @return 数据库驱动返回的估算计划
	 * @throws SQLException             JDBC 解析或执行计划查询失败
	 * @throws IllegalArgumentException 方言尚未实现或输入不符合单条语句要求
	 * @see <a href="https://www.postgresql.org/docs/current/sql-explain.html">PostgreSQL EXPLAIN</a>
	 * @see <a href="https://dev.mysql.com/doc/refman/8.4/en/explain.html">MySQL EXPLAIN</a>
	 * @see <a href="https://sqlite.org/eqp.html">SQLite EXPLAIN QUERY PLAN</a>
	 */
	public JdbcQueryResult explainSql(DbType dbType, String sql, int rowLimit, int timeoutSeconds)
		throws SQLException {
		ResourceBundle bundle = ResourceBundle.getBundle("messages.WorkbenchBundle");
		return explainSql(dbType, sql, rowLimit, timeoutSeconds, bundle::getString);
	}

	/**
	 * 按方言读取估算计划，使用宿主语言报告工作台的输入和会话条件错误。
	 *
	 * @param dbType 当前数据库方言
	 * @param sql 待分析的一条 SQL
	 * @param rowLimit 展示的最大计划行数，范围为 1 到 10000
	 * @param timeoutSeconds JDBC 执行超时，单位为秒，0 表示不设超时
	 * @param messages 宿主界面语言的文案解析器
	 * @return 驱动返回的估算计划
	 * @throws SQLException 计划读取或连接清理失败
	 * @throws IllegalArgumentException SQL、行数或超时参数无效
	 */
	public JdbcQueryResult explainSql(DbType dbType, String sql, int rowLimit, int timeoutSeconds,
									 Function<String, String> messages) throws SQLException {
		String statement = sql.strip();
		if (statement.endsWith(";")) {
			statement = statement.substring(0, statement.length() - 1).stripTrailing();
		}
		if (statement.indexOf(';') >= 0 || !EXPLAINABLE_SQL.matcher(statement).find()) {
			throw new IllegalArgumentException(messages.apply("plan.invalid.statement"));
		}
		if (rowLimit < 1 || rowLimit > 10_000 || timeoutSeconds < 0) {
			throw new IllegalArgumentException(messages.apply("plan.invalid.limits"));
		}
		return switch (dbType) {
			case PostgreSQL ->
				executeSql("EXPLAIN (ANALYZE FALSE, FORMAT TEXT) " + statement, rowLimit, timeoutSeconds);
			case MySQL, MariaDB, H2 -> executeSql("EXPLAIN " + statement, rowLimit, timeoutSeconds);
			case SQLite -> executeSql("EXPLAIN QUERY PLAN " + statement, rowLimit, timeoutSeconds);
			case SqlServer -> readSqlServerEstimatedPlan(statement, rowLimit, timeoutSeconds);
			case Oracle -> readOracleEstimatedPlan(statement, rowLimit, timeoutSeconds, messages);
		};
	}

	/**
	 * 使用 SQL Server 会话的 SHOWPLAN_XML 模式读取估算计划。先确认模式启用，
	 * 再提交待分析的语句，最后以独立批次关闭模式。清理失败会关闭连接，且保留原始计划错误。连接由调用方串行独占。
	 *
	 * @param sql            待分析的 SQL
	 * @param rowLimit       展示的最大计划行数，范围为 1 到 10000
	 * @param timeoutSeconds JDBC 执行超时，单位为秒，0 表示不设超时
	 * @return SQL Server 返回的 XML 计划
	 * @throws SQLException 启用模式、读取计划或恢复会话失败
	 * @see <a href="https://learn.microsoft.com/en-us/sql/t-sql/statements/set-showplan-xml-transact-sql">SQL Server SHOWPLAN_XML</a>
	 */
	private JdbcQueryResult readSqlServerEstimatedPlan(String sql, int rowLimit, int timeoutSeconds) throws SQLException {
		try (Statement batch = connection.createStatement()) {
			batch.setQueryTimeout(timeoutSeconds);
			Exception planFailure = null;
			try {
				batch.execute("SET SHOWPLAN_XML ON");
				try (ResultSet result = batch.executeQuery(sql)) {
					return readSqlResult(result, rowLimit);
				}
			} catch (SQLException | RuntimeException failure) {
				planFailure = failure;
				throw failure;
			} finally {
				try {
					batch.execute("SET SHOWPLAN_XML OFF");
				} catch (SQLException resetFailure) {
					// 无法确认会话状态时关闭连接，防止后续 SQL 误用计划模式。
					try {
						connection.close();
					} catch (SQLException closeFailure) {
						resetFailure.addSuppressed(closeFailure);
					}
					if (Objects.isNull(planFailure)) {
						throw resetFailure;
					}
					planFailure.addSuppressed(resetFailure);
				}
			}
		}
	}

	/**
	 * 在没有已有事务的 Oracle 连接上生成并读取估算计划。计划表中的记录在
	 * 返回前回滚，只有回滚成功才恢复自动提交，避免提交临时记录。清理失败会关闭连接，且保留原始计划错误。
	 *
	 * @param sql            待分析的 SQL
	 * @param rowLimit       展示的最大计划行数，范围为 1 到 10000
	 * @param timeoutSeconds JDBC 执行超时，单位为秒，0 表示不设超时
	 * @param messages       宿主界面语言的文案解析器
	 * @return Oracle 返回的文本计划
	 * @throws SQLException 连接已有事务、计划读取失败或无法清理计划记录
	 * @see <a href="https://docs.oracle.com/en/database/oracle/oracle-database/21/sqlrf/EXPLAIN-PLAN.html">Oracle EXPLAIN PLAN</a>
	 */
	private JdbcQueryResult readOracleEstimatedPlan(String sql, int rowLimit, int timeoutSeconds,
													 Function<String, String> messages) throws SQLException {
		if (!connection.getAutoCommit()) {
			throw new SQLException(messages.apply("plan.oracle.requires.autocommit"));
		}
		// STATEMENT_ID 最多 30 个字符，固定十六进制字符可安全用于 SQL 文法中的字符串常量。
		String planId = UUID.randomUUID().toString().replace("-", "").substring(0, 30);
		connection.setAutoCommit(false);
		Exception planFailure = null;
		try (Statement explain = connection.createStatement();
			 var display = connection.prepareStatement("SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY('PLAN_TABLE', ?, 'TYPICAL'))")) {
			explain.setQueryTimeout(timeoutSeconds);
			display.setQueryTimeout(timeoutSeconds);
			display.setString(1, planId);
			explain.execute("EXPLAIN PLAN SET STATEMENT_ID = '" + planId + "' INTO PLAN_TABLE FOR " + sql);
			try (ResultSet rows = display.executeQuery()) {
				return readSqlResult(rows, rowLimit);
			}
		} catch (SQLException | RuntimeException failure) {
			planFailure = failure;
			throw failure;
		} finally {
			try {
				connection.rollback();
				connection.setAutoCommit(true);
			} catch (SQLException cleanupFailure) {
				try {
					connection.close();
				} catch (SQLException closeFailure) {
					cleanupFailure.addSuppressed(closeFailure);
				}
				if (Objects.isNull(planFailure)) {
					throw cleanupFailure;
				}
				planFailure.addSuppressed(cleanupFailure);
			}
		}
	}

	/**
	 * 读取可独立展示的结果，额外探测一行用于区分恰好达到上限和实际截断。
	 *
	 * @param result   当前 SQL 语句的结果集
	 * @param rowLimit 返回给界面的最大行数
	 * @return 列标题、结果行和截断标记
	 * @throws SQLException 驱动无法读取列或行
	 */
	static JdbcQueryResult readSqlResult(ResultSet result, int rowLimit) throws SQLException {
		ResultSetMetaData metadata = result.getMetaData();
		int columnCount = metadata.getColumnCount();
		List<String> labels = new ArrayList<>(columnCount);
		for (int index = 1; index <= columnCount; index++) {
			labels.add(metadata.getColumnLabel(index));
		}
		List<List<String>> rows = new ArrayList<>();
		while (result.next()) {
			if (rows.size() == rowLimit) {
				return new JdbcQueryResult(List.copyOf(labels), List.copyOf(rows), true, OptionalLong.empty());
			}
			List<String> row = new ArrayList<>(columnCount);
			for (int index = 1; index <= columnCount; index++) {
				row.add(result.getString(index));
			}
			rows.add(Collections.unmodifiableList(row));
		}
		return new JdbcQueryResult(List.copyOf(labels), List.copyOf(rows), false, OptionalLong.empty());
	}

	/**
	 * 先关闭数据库连接，再释放驱动文件。连接关闭失败时仍关闭加载器。
	 *
	 * @throws Exception JDBC 或驱动文件关闭失败
	 */
	@Override
	public void close() throws Exception {
		try (URLClassLoader loaderToClose = driverClassLoader; Connection connectionToClose = connection) {
			// 按声明的反向顺序关闭，保留同时发生的关闭异常。
		}
	}
}
