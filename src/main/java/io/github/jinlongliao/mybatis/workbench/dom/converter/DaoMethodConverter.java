package io.github.jinlongliao.mybatis.workbench.dom.converter;

import com.intellij.psi.PsiMethod;
import com.intellij.util.xml.ConvertContext;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;

import io.github.jinlongliao.mybatis.workbench.dom.model.Mapper;
import io.github.jinlongliao.mybatis.workbench.util.JavaUtils;
import io.github.jinlongliao.mybatis.workbench.util.MapperUtils;

/**
 * @author yanglin
 */
public class DaoMethodConverter extends ConverterAdaptor<PsiMethod> {

	@Nullable
	@Override
	public PsiMethod fromString(@Nullable @NonNls String id, ConvertContext context) {
		Mapper mapper = MapperUtils.getMapper(context.getInvocationElement());
		return JavaUtils.findMethod(context.getProject(), MapperUtils.getNamespace(mapper), id).orElse(null);
	}

}
