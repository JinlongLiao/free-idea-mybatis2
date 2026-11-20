package io.github.jinlongliao.mybatis.workbench.generate.plugin;

import org.mybatis.generator.api.IntrospectedColumn;
import org.mybatis.generator.api.IntrospectedTable;
import org.mybatis.generator.api.Plugin;
import org.mybatis.generator.api.PluginAdapter;
import org.mybatis.generator.api.dom.java.Field;
import org.mybatis.generator.api.dom.java.TopLevelClass;

import java.util.List;

public class GeneratorSwagger2DocPlugin extends PluginAdapter {
	public boolean validate(List<String> list) {
		return true;
	}

	public boolean modelFieldGenerated(Field field, TopLevelClass topLevelClass, IntrospectedColumn introspectedColumn, IntrospectedTable introspectedTable, Plugin.ModelClassType modelClassType) {
		String modelRemarks = introspectedTable.getRemarks().orElse("");
		String classAnnotation = "@ApiModel(value=\"" + topLevelClass.getType() + modelRemarks + "\")";
		if (!topLevelClass.getAnnotations().contains(classAnnotation))
			topLevelClass.addAnnotation(classAnnotation);
		String apiModelAnnotationPackage = this.properties.getProperty("apiModelAnnotationPackage");
		String apiModelPropertyAnnotationPackage = this.properties.getProperty("apiModelPropertyAnnotationPackage");
		if (null == apiModelAnnotationPackage)
			apiModelAnnotationPackage = "io.swagger.annotations.ApiModel";
		if (null == apiModelPropertyAnnotationPackage)
			apiModelPropertyAnnotationPackage = "io.swagger.annotations.ApiModelProperty";
		topLevelClass.addImportedType(apiModelAnnotationPackage);
		topLevelClass.addImportedType(apiModelPropertyAnnotationPackage);
		introspectedColumn.getRemarks().ifPresent(remarks ->
			field.addAnnotation("@ApiModelProperty(value=\"" + remarks + "\")"));
		return super.modelFieldGenerated(field, topLevelClass, introspectedColumn, introspectedTable, modelClassType);
	}
}
