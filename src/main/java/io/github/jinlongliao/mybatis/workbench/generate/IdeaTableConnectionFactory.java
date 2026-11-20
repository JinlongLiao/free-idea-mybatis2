package io.github.jinlongliao.mybatis.workbench.generate;

import org.mybatis.generator.api.ConnectionFactory;
import org.mybatis.generator.api.MyBatisGenerator;
import org.mybatis.generator.api.intellij.IntellijColumnInfo;
import org.mybatis.generator.api.intellij.IntellijTableInfo;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.List;
import java.util.Properties;

import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;

/**
 * 将 IDEA 已读取的表结构提供给生成器，生成期间不另建数据库连接。
 *
 * @author liaojinlong
 * @see ConnectionFactory
 * @since 2026/11/18 13:25
 */
public final class IdeaTableConnectionFactory implements ConnectionFactory {
	/**
	 * 当前生成调用绑定的表结构，作用域结束后自动释放。
	 */
	private static final ScopedValue<IntellijTableInfo> TABLE = ScopedValue.newInstance();

	/**
	 * 在当前线程的表结构作用域内生成并写入文件。
	 *
	 * @param myBatisGenerator 已按项目配置构建的生成器
	 * @param table            IDEA 数据库工具读取的表结构
	 * @return 生成器报告的警告
	 * @throws Exception 表结构读取、生成或写入失败
	 */
	public static List<String> generateMapperFiles(MyBatisGenerator myBatisGenerator, IntellijTableInfo table)
		throws Exception {
		return ScopedValue.where(TABLE, table).call(myBatisGenerator::generateAndWrite);
	}

	/**
	 * {@inheritDoc} 此连接只支持生成器需要的元数据访问，关闭时无需释放网络资源。
	 */
	@Override
	public Connection getConnection() {
		IntellijTableInfo table = TABLE.get();
		return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
			(proxy, method, args) -> switch (method.getName()) {
				case "getMetaData" -> buildTableMetadata(table);
				case "close" -> null;
				default -> throw new SQLFeatureNotSupportedException(method.getName());
			});
	}

	/**
	 * {@inheritDoc} 表结构从调用作用域读取，无连接属性。
	 */
	@Override
	public void addConfigurationProperties(Properties properties) {
	}

	/**
	 * 提供生成器查询列、主键与表备注所需的 JDBC 元数据。
	 *
	 * @param table 当前生成的表结构
	 * @return 仅实现生成器元数据访问范围的 JDBC 对象
	 */
	private DatabaseMetaData buildTableMetadata(IntellijTableInfo table) {
		return (DatabaseMetaData) Proxy.newProxyInstance(getClass().getClassLoader(),
			new Class<?>[]{DatabaseMetaData.class}, (proxy, method, args) -> switch (method.getName()) {
				case "storesLowerCaseIdentifiers", "storesUpperCaseIdentifiers" -> false;
				case "getSearchStringEscape" -> "\\";
				case "getColumns" -> buildColumnRows(table, (String) args[0], (String) args[1]);
				case "getPrimaryKeys" -> buildMetadataRows(new String[]{"COLUMN_NAME", "KEY_SEQ"},
					table.getPrimaryKeyColumns().stream()
						.map(column -> new Object[]{column.getName(), column.getKeySeq()}).toList());
				case "getTables" -> buildMetadataRows(new String[]{"REMARKS", "TABLE_TYPE"},
					List.<Object[]>of(new Object[]{table.getTableRemark(), table.getTableType()}));
				default -> throw new SQLFeatureNotSupportedException(method.getName());
			});
	}

	/**
	 * 按 IDEA 的列顺序创建生成器所需的列元数据。
	 *
	 * @param table   当前表结构
	 * @param catalog 表配置使用的目录
	 * @param schema  表配置使用的模式
	 * @return 可独立关闭的列结果集
	 * @throws SQLException 构造 JDBC 元数据失败
	 */
	private CachedRowSet buildColumnRows(IntellijTableInfo table, String catalog, String schema) throws SQLException {
		String[] names = {"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME",
			"COLUMN_SIZE", "NULLABLE", "DECIMAL_DIGITS", "REMARKS", "COLUMN_DEF", "IS_AUTOINCREMENT",
			"IS_GENERATEDCOLUMN"};
		List<Object[]> rows = table.getColumnInfos().stream().map(column -> new Object[]{
			catalog, schema, table.getTableName(), column.getName(), column.getDataType(), resolveJdbcTypeName(column),
			column.getSize(), Boolean.TRUE.equals(column.getNullable()) ? DatabaseMetaData.columnNullable
			: DatabaseMetaData.columnNoNulls,
			column.getDecimalDigits(), column.getRemarks(), column.getColumnDefaultValue(),
			column.isAutoIncrement() ? "YES" : "NO", column.isGeneratedColumn() ? "YES" : "NO"
		}).toList();
		return buildMetadataRows(names, rows);
	}

	/**
	 * 将 JDBC 类型编号转换为生成器读取的类型名，保留厂商自定义类型的编号供后续解析。
	 *
	 * @param column 当前 IDE 列
	 * @return 标准 JDBC 类型名或其他类型标记
	 */
	private String resolveJdbcTypeName(IntellijColumnInfo column) {
		try {
			return JDBCType.valueOf(column.getDataType()).getName();
		} catch (IllegalArgumentException ignored) {
			return JDBCType.OTHER.getName();
		}
	}

	/**
	 * 创建断开式 JDBC 结果集。倒序插入使读取顺序与输入一致。
	 *
	 * @param names  JDBC 元数据列名
	 * @param values 每行按列名顺序提供的值
	 * @return 游标位于首行前的结果集
	 * @throws SQLException 行或列的定义无效
	 */
	private CachedRowSet buildMetadataRows(String[] names, List<Object[]> values) throws SQLException {
		RowSetMetaDataImpl metadata = new RowSetMetaDataImpl();
		metadata.setColumnCount(names.length);
		for (int column = 0; column < names.length; column++) {
			metadata.setColumnName(column + 1, names[column]);
			metadata.setColumnType(column + 1, Types.VARCHAR);
		}
		CachedRowSet rows = RowSetProvider.newFactory().createCachedRowSet();
		rows.setMetaData(metadata);
		for (Object[] value : values.reversed()) {
			rows.moveToInsertRow();
			for (int column = 0; column < names.length; column++) {
				rows.updateObject(column + 1, value[column]);
			}
			rows.insertRow();
			rows.moveToCurrentRow();
		}
		rows.beforeFirst();
		return rows;
	}
}
