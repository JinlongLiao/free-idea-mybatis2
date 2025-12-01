package org.mybatis.generator.api;

import org.mybatis.generator.api.intellij.IntellijTableInfo;
import org.mybatis.generator.config.Context;
import org.mybatis.generator.config.TableConfiguration;
import org.mybatis.generator.internal.ObjectFactory;
import org.mybatis.generator.internal.db.IntellijIntrospector;
import org.mybatis.generator.internal.util.StringUtility;
import org.mybatis.generator.internal.util.messages.Messages;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

public class ContextIdeaAdder {
	private static final Field FIELD;

	static {
		try {
			FIELD = Context.class.getDeclaredField("tableConfigurations");
			FIELD.setAccessible(true);
		} catch (NoSuchFieldException e) {
			throw new RuntimeException(e);
		}
	}

	public static void introspectIntellijTables(Context context,
										 ProgressCallback callback,
										 List<String> warnings, Set<String> fullyQualifiedTableNames,
										 IntellijTableInfo tableInfo) throws InterruptedException, SQLException {
		List<IntrospectedTable> introspectedTables = context.getIntrospectedTables();
		introspectedTables.clear();
		JavaTypeResolver javaTypeResolver = ObjectFactory.createJavaTypeResolver(context, warnings);
		callback.startTask(Messages.getString("Progress.0"));
		IntellijIntrospector databaseIntrospector = new IntellijIntrospector(context, javaTypeResolver, warnings, tableInfo);
		List<TableConfiguration> tableConfigurations = null;
		try {
			tableConfigurations = (List<TableConfiguration>) FIELD.get(context);
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
		for (TableConfiguration tc : tableConfigurations) {
			String tableName = StringUtility.composeFullyQualifiedTableName(tc.getCatalog(), tc.getSchema(), tc.getTableName(), '.');
			if (fullyQualifiedTableNames == null || fullyQualifiedTableNames.isEmpty() || fullyQualifiedTableNames.contains(tableName)) {
				if (!tc.areAnyStatementsEnabled()) {
					warnings.add(Messages.getString("Warning.0", tableName));
				} else {
					callback.startTask(Messages.getString("Progress.1", tableName));
					List<IntrospectedTable> tables = databaseIntrospector.introspectTables(tc);
					if (tables != null) {
						introspectedTables.addAll(tables);
					}
					callback.checkCancel();
				}
			}
		}
	}


}
