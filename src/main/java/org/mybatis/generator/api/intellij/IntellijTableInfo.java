package org.mybatis.generator.api.intellij;

import java.util.List;

/* loaded from: mybatis-generator-core-1.4.0.jar:org/mybatis/generator/api/intellij/IntellijTableInfo.class */
public class IntellijTableInfo {
    private String tableName;
    private String databaseType;
    private String tableRemark;
    private String tableType;
    private List<IntellijColumnInfo> columnInfos;
    private List<IntellijColumnInfo> primaryKeyColumns;

    public String getTableName() {
        return this.tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getDatabaseType() {
        return this.databaseType;
    }

    public void setDatabaseType(String databaseType) {
        this.databaseType = databaseType;
    }

    public List<IntellijColumnInfo> getColumnInfos() {
        return this.columnInfos;
    }

    public void setColumnInfos(List<IntellijColumnInfo> columnInfos) {
        this.columnInfos = columnInfos;
    }

    public List<IntellijColumnInfo> getPrimaryKeyColumns() {
        return this.primaryKeyColumns;
    }

    public void setPrimaryKeyColumns(List<IntellijColumnInfo> primaryKeyColumns) {
        this.primaryKeyColumns = primaryKeyColumns;
    }

    public String getTableRemark() {
        return this.tableRemark;
    }

    public void setTableRemark(String tableRemark) {
        this.tableRemark = tableRemark;
    }

    public String getTableType() {
        return this.tableType;
    }

    public void setTableType(String tableType) {
        this.tableType = tableType;
    }
}
