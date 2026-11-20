package io.github.jinlongliao.mybatis.workbench.model;

public enum DbType {

	MySQL,
	Oracle,
	PostgreSQL,
	SqlServer,
	MariaDB,
	/**
	 * SQLite 文件或内存数据库，生成器沿用标准 JDBC 元数据。
	 */
	SQLite,
	/**
	 * H2 文件、内存或远程数据库，生成器沿用标准 JDBC 元数据。
	 */
	H2

}
