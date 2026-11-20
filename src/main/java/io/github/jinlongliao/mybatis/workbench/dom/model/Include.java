package io.github.jinlongliao.mybatis.workbench.dom.model;

import com.intellij.psi.xml.XmlTag;
import com.intellij.util.xml.Attribute;
import com.intellij.util.xml.Convert;
import com.intellij.util.xml.DomElement;
import com.intellij.util.xml.GenericAttributeValue;

import io.github.jinlongliao.mybatis.workbench.dom.converter.SqlConverter;

/**
 * @author yanglin
 */
public interface Include extends DomElement {

	@Attribute("refid")
	@Convert(SqlConverter.class)
	public GenericAttributeValue<XmlTag> getRefId();

}
