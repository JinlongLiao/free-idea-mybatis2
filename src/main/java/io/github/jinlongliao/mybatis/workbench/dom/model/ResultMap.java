package io.github.jinlongliao.mybatis.workbench.dom.model;

import com.intellij.psi.PsiClass;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.util.xml.Attribute;
import com.intellij.util.xml.Convert;
import com.intellij.util.xml.GenericAttributeValue;

import org.jetbrains.annotations.NotNull;

import io.github.jinlongliao.mybatis.workbench.dom.converter.AliasConverter;
import io.github.jinlongliao.mybatis.workbench.dom.converter.ResultMapConverter;

/**
 * @author yanglin
 */
public interface ResultMap extends GroupFour, IdDomElement {

	@NotNull
	@Attribute("extends")
	@Convert(ResultMapConverter.class)
	public GenericAttributeValue<XmlAttributeValue> getExtends();

	@NotNull
	@Attribute("type")
	@Convert(AliasConverter.class)
	public GenericAttributeValue<PsiClass> getType();

}
