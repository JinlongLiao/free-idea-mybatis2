package io.github.jinlongliao.mybatis.workbench.generate.plugin;

import org.mybatis.generator.api.IntrospectedTable;
import org.mybatis.generator.api.PluginAdapter;
import org.mybatis.generator.api.dom.java.FullyQualifiedJavaType;
import org.mybatis.generator.api.dom.java.Interface;

import java.util.List;

public class RepositoryPlugin extends PluginAdapter {
	private final FullyQualifiedJavaType annotationRepository;

	private String annotation = "@Repository";

	public RepositoryPlugin() {
		this.annotationRepository = new FullyQualifiedJavaType("org.springframework.stereotype.Repository");
	}

	public boolean validate(List<String> list) {
		return true;
	}

	public boolean clientGenerated(Interface interfaze, IntrospectedTable introspectedTable) {
		interfaze.addImportedType(this.annotationRepository);
		interfaze.addAnnotation(this.annotation);
		return true;
	}
}
