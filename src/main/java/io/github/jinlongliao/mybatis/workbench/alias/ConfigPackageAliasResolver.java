package io.github.jinlongliao.mybatis.workbench.alias;

import com.google.common.collect.Lists;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;

import io.github.jinlongliao.mybatis.workbench.util.MapperUtils;

/**
 * @author yanglin
 */
public class ConfigPackageAliasResolver extends PackageAliasResolver {

	public ConfigPackageAliasResolver(Project project) {
		super(project);
	}

	@NotNull
	@Override
	public Collection<String> getPackages(@Nullable PsiElement element) {
		final ArrayList<String> result = Lists.newArrayList();
		MapperUtils.processConfiguredPackage(project, pkg -> {
			result.add(pkg.getName().getStringValue());
			return true;
		});
		return result;
	}

}
