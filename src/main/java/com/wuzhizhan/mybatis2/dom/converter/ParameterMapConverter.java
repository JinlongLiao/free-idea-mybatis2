package com.wuzhizhan.mybatis2.dom.converter;

import com.intellij.util.xml.ConvertContext;
import com.wuzhizhan.mybatis2.dom.model.IdDomElement;
import com.wuzhizhan.mybatis2.dom.model.Mapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * @author yanglin
 */
public class ParameterMapConverter extends IdBasedTagConverter {

    @NotNull
    @Override
    public Collection<? extends IdDomElement> getComparisons(@Nullable Mapper mapper,
                                                             ConvertContext context) {
        return mapper.getParameterMaps();
    }

}
