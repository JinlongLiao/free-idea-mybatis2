package io.github.jinlongliao.mybatis.workbench.locator;

import com.google.common.collect.Sets;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import io.github.jinlongliao.mybatis.workbench.dom.model.Mapper;
import io.github.jinlongliao.mybatis.workbench.util.MapperUtils;

/**
 * @author yanglin
 */
public class MapperXmlPackageProvider extends PackageProvider {

	@NotNull
	@Override
	public Set<PsiPackage> getPackages(@NotNull Project project) {
		HashSet<PsiPackage> res = Sets.newHashSet();
		Collection<Mapper> mappers = MapperUtils.findMappers(project);
		JavaPsiFacade javaPsiFacade = JavaPsiFacade.getInstance(project);
		for (Mapper mapper : mappers) {
			String namespace = MapperUtils.getNamespace(mapper);
			PsiClass clazz = javaPsiFacade.findClass(namespace, GlobalSearchScope.allScope(project));
			if (null != clazz) {
				PsiFile file = clazz.getContainingFile();
				if (file instanceof PsiJavaFile) {
					String packageName = ((PsiJavaFile) file).getPackageName();
					PsiPackage pkg = javaPsiFacade.findPackage(packageName);
					if (null != pkg) {
						res.add(pkg);
					}
				}
			}
		}
		return res;
	}

}
