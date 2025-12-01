package org.mybatis.generator.internal.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.mybatis.generator.api.FullyQualifiedTable;
import org.mybatis.generator.api.IntrospectedColumn;
import org.mybatis.generator.api.IntrospectedTable;
import org.mybatis.generator.api.JavaTypeResolver;
import org.mybatis.generator.api.dom.java.FullyQualifiedJavaType;
import org.mybatis.generator.api.dom.java.JavaReservedWords;
import org.mybatis.generator.api.intellij.IntellijColumnInfo;
import org.mybatis.generator.api.intellij.IntellijTableInfo;
import org.mybatis.generator.config.ColumnOverride;
import org.mybatis.generator.config.Context;
import org.mybatis.generator.config.GeneratedKey;
import org.mybatis.generator.config.PropertyRegistry;
import org.mybatis.generator.config.TableConfiguration;
import org.mybatis.generator.internal.ObjectFactory;
import org.mybatis.generator.internal.util.JavaBeansUtil;
import org.mybatis.generator.internal.util.StringUtility;
import org.mybatis.generator.internal.util.messages.Messages;
import org.mybatis.generator.logging.Log;
import org.mybatis.generator.logging.LogFactory;

/* loaded from: mybatis-generator-core-1.4.0.jar:org/mybatis/generator/internal/db/IntellijIntrospector.class */
public class IntellijIntrospector {
    private IntellijTableInfo intellijTableInfo;
    private JavaTypeResolver javaTypeResolver;
    private List<String> warnings;
    private Context context;
    private Log logger = LogFactory.getLog(getClass());

    public IntellijIntrospector(Context context, JavaTypeResolver javaTypeResolver, List<String> warnings, IntellijTableInfo tableInfo) {
        this.context = context;
        this.intellijTableInfo = tableInfo;
        this.javaTypeResolver = javaTypeResolver;
        this.warnings = warnings;
    }

    private void calculatePrimaryKey(FullyQualifiedTable table, IntrospectedTable introspectedTable) {
        Map<Short, String> keyColumns = new TreeMap<>();
        List<IntellijColumnInfo> primaryKeyColumns = this.intellijTableInfo.getPrimaryKeyColumns();
        for (IntellijColumnInfo primaryKeyColumn : primaryKeyColumns) {
            String columnName = primaryKeyColumn.getName();
            short keySeq = primaryKeyColumn.getKeySeq();
            keyColumns.put(Short.valueOf(keySeq), columnName);
        }
        for (String columnName2 : keyColumns.values()) {
            introspectedTable.addPrimaryKeyColumn(columnName2);
        }
    }

    private void closeResultSet(ResultSet rs) {
        if (rs != null) {
            try {
                rs.close();
            } catch (SQLException e) {
            }
        }
    }

    private void reportIntrospectionWarnings(IntrospectedTable introspectedTable, TableConfiguration tableConfiguration, FullyQualifiedTable table) {
        for (ColumnOverride columnOverride : tableConfiguration.getColumnOverrides()) {
            if (!introspectedTable.getColumn(columnOverride.getColumnName()).isPresent()) {
                this.warnings.add(Messages.getString("Warning.3", columnOverride.getColumnName(), table.toString()));
            }
        }
        for (String string : tableConfiguration.getIgnoredColumnsInError()) {
            this.warnings.add(Messages.getString("Warning.4", string, table.toString()));
        }
		GeneratedKey generatedKey = tableConfiguration.getGeneratedKey().orElse(null);
        if (generatedKey != null && introspectedTable.getColumn(generatedKey.getColumn()).isEmpty()) {
            if (generatedKey.isIdentity()) {
                this.warnings.add(Messages.getString("Warning.5", generatedKey.getColumn(), table.toString()));
            } else {
                this.warnings.add(Messages.getString("Warning.6", generatedKey.getColumn(), table.toString()));
            }
        }
        for (IntrospectedColumn ic : introspectedTable.getAllColumns()) {
            if (JavaReservedWords.containsWord(ic.getJavaProperty())) {
                this.warnings.add(Messages.getString("Warning.26", ic.getActualColumnName(), table.toString()));
            }
        }
    }

    public List<IntrospectedTable> introspectTables(TableConfiguration tc) throws SQLException {
        Map<ActualTableName, List<IntrospectedColumn>> columns = getColumns(tc);
        if (columns.isEmpty()) {
            this.warnings.add(Messages.getString("Warning.19", tc.getCatalog(), tc.getSchema(), tc.getTableName()));
            return Collections.emptyList();
        }
        removeIgnoredColumns(tc, columns);
        calculateExtraColumnInformation(tc, columns);
        applyColumnOverrides(tc, columns);
        calculateIdentityColumns(tc, columns);
        List<IntrospectedTable> introspectedTables = calculateIntrospectedTables(tc, columns);
        Iterator<IntrospectedTable> iter = introspectedTables.iterator();
        while (iter.hasNext()) {
            IntrospectedTable introspectedTable = iter.next();
            if (!introspectedTable.hasAnyColumns()) {
                String warning = Messages.getString("Warning.1", introspectedTable.getFullyQualifiedTable().toString());
                this.warnings.add(warning);
                iter.remove();
            } else if (!introspectedTable.hasPrimaryKeyColumns() && !introspectedTable.hasBaseColumns()) {
                String warning2 = Messages.getString("Warning.18", introspectedTable.getFullyQualifiedTable().toString());
                this.warnings.add(warning2);
                iter.remove();
            } else {
                reportIntrospectionWarnings(introspectedTable, tc, introspectedTable.getFullyQualifiedTable());
            }
        }
        return introspectedTables;
    }

    private void removeIgnoredColumns(TableConfiguration tc, Map<ActualTableName, List<IntrospectedColumn>> columns) {
        for (Map.Entry<ActualTableName, List<IntrospectedColumn>> entry : columns.entrySet()) {
            Iterator<IntrospectedColumn> tableColumns = entry.getValue().iterator();
            while (tableColumns.hasNext()) {
                IntrospectedColumn introspectedColumn = tableColumns.next();
                if (tc.isColumnIgnored(introspectedColumn.getActualColumnName())) {
                    tableColumns.remove();
                    if (this.logger.isDebugEnabled()) {
                        this.logger.debug(Messages.getString("Tracing.3", introspectedColumn.getActualColumnName(), entry.getKey().toString()));
                    }
                }
            }
        }
    }

    private void calculateExtraColumnInformation(TableConfiguration tc, Map<ActualTableName, List<IntrospectedColumn>> columns) {
        String calculatedColumnName;
        StringBuilder sb = new StringBuilder();
        Pattern pattern = null;
        String replaceString = null;
        if (tc.getColumnRenamingRule() != null) {
            pattern = Pattern.compile(tc.getColumnRenamingRule().getSearchString());
            String replaceString2 = tc.getColumnRenamingRule().getReplaceString();
            replaceString = replaceString2 == null ? "" : replaceString2;
        }
        for (Map.Entry<ActualTableName, List<IntrospectedColumn>> entry : columns.entrySet()) {
            for (IntrospectedColumn introspectedColumn : entry.getValue()) {
                if (pattern == null) {
                    calculatedColumnName = introspectedColumn.getActualColumnName();
                } else {
                    Matcher matcher = pattern.matcher(introspectedColumn.getActualColumnName());
                    calculatedColumnName = matcher.replaceAll(replaceString);
                }
                if (StringUtility.isTrue(tc.getProperty(PropertyRegistry.TABLE_USE_ACTUAL_COLUMN_NAMES))) {
                    introspectedColumn.setJavaProperty(JavaBeansUtil.getValidPropertyName(calculatedColumnName));
                } else if (StringUtility.isTrue(tc.getProperty(PropertyRegistry.TABLE_USE_COMPOUND_PROPERTY_NAMES))) {
                    sb.setLength(0);
                    sb.append(calculatedColumnName);
                    sb.append('_');
                    sb.append(JavaBeansUtil.getCamelCaseString(introspectedColumn.getRemarks(), true));
                    introspectedColumn.setJavaProperty(JavaBeansUtil.getValidPropertyName(sb.toString()));
                } else {
                    introspectedColumn.setJavaProperty(JavaBeansUtil.getCamelCaseString(calculatedColumnName, false));
                }
                FullyQualifiedJavaType fullyQualifiedJavaType = this.javaTypeResolver.calculateJavaType(introspectedColumn);
                if (fullyQualifiedJavaType != null) {
                    introspectedColumn.setFullyQualifiedJavaType(fullyQualifiedJavaType);
                    introspectedColumn.setJdbcTypeName(this.javaTypeResolver.calculateJdbcTypeName(introspectedColumn));
                } else {
                    boolean warn = true;
                    if (tc.isColumnIgnored(introspectedColumn.getActualColumnName())) {
                        warn = false;
                    }
                    ColumnOverride co = tc.getColumnOverride(introspectedColumn.getActualColumnName());
                    if (co != null && StringUtility.stringHasValue(co.getJavaType())) {
                        warn = false;
                    }
                    if (warn) {
                        introspectedColumn.setFullyQualifiedJavaType(FullyQualifiedJavaType.getObjectInstance());
                        introspectedColumn.setJdbcTypeName("OTHER");
                        String warning = Messages.getString("Warning.14", Integer.toString(introspectedColumn.getJdbcType()), entry.getKey().toString(), introspectedColumn.getActualColumnName());
                        this.warnings.add(warning);
                    }
                }
                if (this.context.autoDelimitKeywords() && SqlReservedWords.containsWord(introspectedColumn.getActualColumnName())) {
                    introspectedColumn.setColumnNameDelimited(true);
                }
                if (tc.isAllColumnDelimitingEnabled()) {
                    introspectedColumn.setColumnNameDelimited(true);
                }
            }
        }
    }

    private void calculateIdentityColumns(TableConfiguration tc, Map<ActualTableName, List<IntrospectedColumn>> columns) {
        GeneratedKey gk = tc.getGeneratedKey().orElse(null);
        if (gk == null) {
            return;
        }
        for (Map.Entry<ActualTableName, List<IntrospectedColumn>> entry : columns.entrySet()) {
            for (IntrospectedColumn introspectedColumn : entry.getValue()) {
                if (isMatchedColumn(introspectedColumn, gk)) {
                    if (gk.isIdentity() || gk.isJdbcStandard()) {
                        introspectedColumn.setIdentity(true);
                        introspectedColumn.setSequenceColumn(false);
                    } else {
                        introspectedColumn.setIdentity(false);
                        introspectedColumn.setSequenceColumn(true);
                    }
                }
            }
        }
    }

    private boolean isMatchedColumn(IntrospectedColumn introspectedColumn, GeneratedKey gk) {
        if (introspectedColumn.isColumnNameDelimited()) {
            return introspectedColumn.getActualColumnName().equals(gk.getColumn());
        }
        return introspectedColumn.getActualColumnName().equalsIgnoreCase(gk.getColumn());
    }

    private void applyColumnOverrides(TableConfiguration tc, Map<ActualTableName, List<IntrospectedColumn>> columns) {
        for (Map.Entry<ActualTableName, List<IntrospectedColumn>> entry : columns.entrySet()) {
            for (IntrospectedColumn introspectedColumn : entry.getValue()) {
                ColumnOverride columnOverride = tc.getColumnOverride(introspectedColumn.getActualColumnName());
                if (columnOverride != null) {
                    if (this.logger.isDebugEnabled()) {
                        this.logger.debug(Messages.getString("Tracing.4", introspectedColumn.getActualColumnName(), entry.getKey().toString()));
                    }
                    if (StringUtility.stringHasValue(columnOverride.getJavaProperty())) {
                        introspectedColumn.setJavaProperty(columnOverride.getJavaProperty());
                    }
                    if (StringUtility.stringHasValue(columnOverride.getJavaType())) {
                        introspectedColumn.setFullyQualifiedJavaType(new FullyQualifiedJavaType(columnOverride.getJavaType()));
                    }
                    if (StringUtility.stringHasValue(columnOverride.getJdbcType())) {
                        introspectedColumn.setJdbcTypeName(columnOverride.getJdbcType());
                    }
                    if (StringUtility.stringHasValue(columnOverride.getTypeHandler())) {
                        introspectedColumn.setTypeHandler(columnOverride.getTypeHandler());
                    }
                    if (columnOverride.isColumnNameDelimited()) {
                        introspectedColumn.setColumnNameDelimited(true);
                    }
                    introspectedColumn.setGeneratedAlways(columnOverride.isGeneratedAlways());
                    introspectedColumn.setProperties(columnOverride.getProperties());
                }
            }
        }
    }

    private Map<ActualTableName, List<IntrospectedColumn>> getColumns(TableConfiguration tc) throws SQLException {
        String localCatalog;
        String localSchema;
        String localTableName;
        boolean delimitIdentifiers = tc.isDelimitIdentifiers() || StringUtility.stringContainsSpace(tc.getCatalog()) || StringUtility.stringContainsSpace(tc.getSchema()) || StringUtility.stringContainsSpace(tc.getTableName());
        if (delimitIdentifiers) {
            localCatalog = tc.getCatalog();
            localSchema = tc.getSchema();
            localTableName = tc.getTableName();
        } else {
            localCatalog = tc.getCatalog();
            localSchema = tc.getSchema();
            localTableName = tc.getTableName();
        }
        Map<ActualTableName, List<IntrospectedColumn>> answer = new HashMap<>();
        if (this.logger.isDebugEnabled()) {
            String fullTableName = StringUtility.composeFullyQualifiedTableName(localCatalog, localSchema, localTableName, '.');
            this.logger.debug(Messages.getString("Tracing.1", fullTableName));
        }
        boolean supportsIsAutoIncrement = false;
        boolean supportsIsGeneratedColumn = false;
        for (IntellijColumnInfo intellijColumnInfo : this.intellijTableInfo.getColumnInfos()) {
            if (intellijColumnInfo.isAutoIncrement()) {
                supportsIsAutoIncrement = true;
            }
            if (intellijColumnInfo.isGeneratedColumn()) {
                supportsIsGeneratedColumn = true;
            }
        }
        for (IntellijColumnInfo intellijColumnInfo2 : this.intellijTableInfo.getColumnInfos()) {
            IntrospectedColumn introspectedColumn = ObjectFactory.createIntrospectedColumn(this.context);
            introspectedColumn.setTableAlias(tc.getAlias());
            introspectedColumn.setJdbcType(intellijColumnInfo2.getDataType());
            introspectedColumn.setLength(intellijColumnInfo2.getSize());
            introspectedColumn.setActualColumnName(intellijColumnInfo2.getName());
            introspectedColumn.setNullable(intellijColumnInfo2.getNullable().booleanValue());
            introspectedColumn.setScale(intellijColumnInfo2.getDecimalDigits());
            introspectedColumn.setRemarks(intellijColumnInfo2.getRemarks());
            introspectedColumn.setDefaultValue(intellijColumnInfo2.getColumnDefaultValue());
            if (supportsIsAutoIncrement) {
                introspectedColumn.setAutoIncrement(intellijColumnInfo2.isAutoIncrement());
            }
            if (supportsIsGeneratedColumn) {
                introspectedColumn.setGeneratedColumn(intellijColumnInfo2.isGeneratedColumn());
            }
            ActualTableName atn = new ActualTableName(null, null, this.intellijTableInfo.getTableName());
            List<IntrospectedColumn> columns = answer.get(atn);
            if (columns == null) {
                columns = new ArrayList<>();
                answer.put(atn, columns);
            }
            columns.add(introspectedColumn);
            if (this.logger.isDebugEnabled()) {
                this.logger.debug(Messages.getString("Tracing.2", introspectedColumn.getActualColumnName(), Integer.toString(introspectedColumn.getJdbcType()), atn.toString()));
            }
        }
        if (answer.size() > 1 && !StringUtility.stringContainsSQLWildcard(localSchema) && !StringUtility.stringContainsSQLWildcard(localTableName)) {
            ActualTableName inputAtn = new ActualTableName(tc.getCatalog(), tc.getSchema(), tc.getTableName());
            StringBuilder sb = new StringBuilder();
            boolean comma = false;
            for (ActualTableName atn2 : answer.keySet()) {
                if (comma) {
                    sb.append(',');
                } else {
                    comma = true;
                }
                sb.append(atn2.toString());
            }
            this.warnings.add(Messages.getString("Warning.25", inputAtn.toString(), sb.toString()));
        }
        return answer;
    }

    private List<IntrospectedTable> calculateIntrospectedTables(TableConfiguration tc, Map<ActualTableName, List<IntrospectedColumn>> columns) {
        boolean delimitIdentifiers = tc.isDelimitIdentifiers() || StringUtility.stringContainsSpace(tc.getCatalog()) || StringUtility.stringContainsSpace(tc.getSchema()) || StringUtility.stringContainsSpace(tc.getTableName());
        List<IntrospectedTable> answer = new ArrayList<>();
        for (Map.Entry<ActualTableName, List<IntrospectedColumn>> entry : columns.entrySet()) {
            ActualTableName atn = entry.getKey();
            FullyQualifiedTable table = new FullyQualifiedTable(StringUtility.stringHasValue(tc.getCatalog()) ? atn.getCatalog() : null, StringUtility.stringHasValue(tc.getSchema()) ? atn.getSchema() : null, atn.getTableName(), tc.getDomainObjectName(), tc.getAlias(), StringUtility.isTrue(tc.getProperty(PropertyRegistry.TABLE_IGNORE_QUALIFIERS_AT_RUNTIME)), tc.getProperty(PropertyRegistry.TABLE_RUNTIME_CATALOG), tc.getProperty(PropertyRegistry.TABLE_RUNTIME_SCHEMA), tc.getProperty(PropertyRegistry.TABLE_RUNTIME_TABLE_NAME), delimitIdentifiers, tc.getDomainObjectRenamingRule(), this.context);
            IntrospectedTable introspectedTable = ObjectFactory.createIntrospectedTable(tc, table, this.context);
            for (IntrospectedColumn introspectedColumn : entry.getValue()) {
                introspectedTable.addColumn(introspectedColumn);
            }
            calculatePrimaryKey(table, introspectedTable);
            enhanceIntrospectedTable(introspectedTable);
            answer.add(introspectedTable);
        }
        return answer;
    }

    private void enhanceIntrospectedTable(IntrospectedTable introspectedTable) {
        String remarks = this.intellijTableInfo.getTableRemark();
        String tableType = this.intellijTableInfo.getTableType();
        introspectedTable.setRemarks(remarks);
        introspectedTable.setTableType(tableType);
    }
}
