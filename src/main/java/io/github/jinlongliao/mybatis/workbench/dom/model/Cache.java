package io.github.jinlongliao.mybatis.workbench.dom.model;

import com.intellij.util.xml.DomElement;
import com.intellij.util.xml.SubTagList;

import java.util.List;

/**
 * @author yanglin
 */
public interface Cache extends DomElement {

	@SubTagList("property")
	public List<Property> getProperties();

}
