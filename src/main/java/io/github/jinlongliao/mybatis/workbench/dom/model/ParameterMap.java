package io.github.jinlongliao.mybatis.workbench.dom.model;

import com.intellij.psi.PsiClass;
import com.intellij.util.xml.Attribute;
import com.intellij.util.xml.Convert;
import com.intellij.util.xml.GenericAttributeValue;
import com.intellij.util.xml.SubTagList;

import org.jetbrains.annotations.NotNull;

import java.util.List;

import io.github.jinlongliao.mybatis.workbench.dom.converter.AliasConverter;

/**
 * @author yanglin
 */
public interface ParameterMap extends IdDomElement {

	@NotNull
	@Attribute("type")
	@Convert(AliasConverter.class)
	public GenericAttributeValue<PsiClass> getType();

	@SubTagList("parameter")
	public List<Parameter> getParameters();

}
