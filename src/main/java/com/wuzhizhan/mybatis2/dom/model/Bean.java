package com.wuzhizhan.mybatis2.dom.model;

import com.intellij.util.xml.DomElement;
import com.intellij.util.xml.SubTagList;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * @author yanglin
 */
public interface Bean extends DomElement {

    @NotNull
    @SubTagList("property")
    public List<BeanProperty> getBeanProperties();

}
