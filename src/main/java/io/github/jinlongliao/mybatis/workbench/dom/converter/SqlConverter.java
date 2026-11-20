package io.github.jinlongliao.mybatis.workbench.dom.converter;

import com.intellij.util.xml.ConvertContext;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

import io.github.jinlongliao.mybatis.workbench.dom.model.IdDomElement;
import io.github.jinlongliao.mybatis.workbench.dom.model.Mapper;

/**
 * @author yanglin
 */
public class SqlConverter extends IdBasedTagConverter {

	@NotNull
	@Override
	public Collection<? extends IdDomElement> getComparisons(@Nullable Mapper mapper, ConvertContext context) {
		return mapper.getSqls();
	}

}
