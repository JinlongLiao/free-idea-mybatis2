package io.github.jinlongliao.mybatis.workbench.dom.model;

import com.intellij.psi.PsiClass;
import com.intellij.util.xml.Attribute;
import com.intellij.util.xml.Convert;
import com.intellij.util.xml.GenericAttributeValue;

import org.jetbrains.annotations.NotNull;

import io.github.jinlongliao.mybatis.workbench.dom.converter.AliasConverter;

/**
 * @author yanglin
 */
public interface Association extends GroupFour, ResultMapGroup, PropertyGroup {

	@NotNull
	@Attribute("javaType")
	@Convert(AliasConverter.class)
	public GenericAttributeValue<PsiClass> getJavaType();
}
