package com.wuzhizhan.mybatis2.generate.plugin;

import java.util.Properties;
import java.util.Set;

import org.mybatis.generator.api.CommentGenerator;
import org.mybatis.generator.api.IntrospectedColumn;
import org.mybatis.generator.api.IntrospectedTable;
import org.mybatis.generator.api.dom.java.CompilationUnit;
import org.mybatis.generator.api.dom.java.Field;
import org.mybatis.generator.api.dom.java.FullyQualifiedJavaType;
import org.mybatis.generator.api.dom.java.TopLevelClass;
import org.mybatis.generator.api.dom.xml.TextElement;
import org.mybatis.generator.api.dom.xml.XmlElement;
import org.mybatis.generator.config.MergeConstants;
import org.mybatis.generator.internal.util.StringUtility;

public class DbRemarksCommentGenerator implements CommentGenerator {
	private boolean isAnnotations;

	public void addJavaFileComment(CompilationUnit compilationUnit) {
		if (this.isAnnotations) {
			compilationUnit.addImportedType(new FullyQualifiedJavaType("javax.persistence.Table"));
			compilationUnit.addImportedType(new FullyQualifiedJavaType("javax.persistence.Id"));
			compilationUnit.addImportedType(new FullyQualifiedJavaType("javax.persistence.Column"));
			compilationUnit.addImportedType(new FullyQualifiedJavaType("javax.persistence.GeneratedValue"));
			compilationUnit.addImportedType(new FullyQualifiedJavaType("org.hibernate.validator.constraints.NotEmpty"));
		}
	}

	/**
	 * {@inheritDoc} 标识生成节点，使后续 XML 合并能替换这些节点并保留手写 SQL。
	 */
	@Override
	public void addComment(XmlElement xmlElement) {
		xmlElement.addElement(new TextElement("<!-- " + MergeConstants.NEW_ELEMENT_TAG + " -->"));
	}

	/**
	 * {@inheritDoc} 读取模型注解配置。
	 */
	@Override
	public void addConfigurationProperties(Properties properties) {
		this.isAnnotations = StringUtility.isTrue(properties.getProperty("annotations"));
	}

	public void addModelClassComment(TopLevelClass topLevelClass, IntrospectedTable introspectedTable) {
		topLevelClass.addJavaDocLine("/**");
		topLevelClass.addJavaDocLine(" * " + introspectedTable.getFullyQualifiedTable().getIntrospectedTableName());
		topLevelClass.addJavaDocLine(" * @author ");
		topLevelClass.addJavaDocLine(" */");
		if (this.isAnnotations)
			topLevelClass.addAnnotation("@Table(name=\"" + introspectedTable.getFullyQualifiedTableNameAtRuntime() + "\")");
	}

	/**
	 * {@inheritDoc} 保留旧版列备注和可选的持久化注解。
	 */
	@Override
	public void addFieldAnnotation(Field field, IntrospectedTable introspectedTable,
								   IntrospectedColumn introspectedColumn, Set<FullyQualifiedJavaType> imports) {
		String remarks = introspectedColumn.getRemarks().orElse("");
		if (StringUtility.stringHasValue(remarks)) {
			field.addJavaDocLine("/**");
			StringBuilder sb = new StringBuilder();
			sb.append(" * ");
			sb.append(remarks);
			field.addJavaDocLine(sb.toString());
			field.addJavaDocLine(" */");
		}
		if (!this.isAnnotations) {
			return;
		}
		boolean primaryKey = introspectedTable.getPrimaryKeyColumns().contains(introspectedColumn);
		if (primaryKey) {
			field.addAnnotation("@Id");
		} else if (!introspectedColumn.isNullable()) {
			field.addAnnotation("@NotEmpty");
		}

		introspectedTable.getTableConfiguration().getGeneratedKey()
			.filter(key -> key.getColumn().equalsIgnoreCase(introspectedColumn.getActualColumnName()))
			.ifPresent(key -> {
				if (introspectedColumn.isIdentity()) {
					if (key.isJdbcStandard()) {
						field.addAnnotation("@GeneratedValue(generator = \"JDBC\")");
					} else {
						imports.add(new FullyQualifiedJavaType("javax.persistence.GenerationType"));
						field.addAnnotation("@GeneratedValue(strategy = GenerationType.IDENTITY)");
					}
				} else if (introspectedColumn.isSequenceColumn()) {
					imports.add(new FullyQualifiedJavaType("javax.persistence.SequenceGenerator"));
					field.addAnnotation("@SequenceGenerator(name=\"\",sequenceName=\""
						+ key.getRuntimeSqlStatement() + "\")");
				}
			});
	}
}
