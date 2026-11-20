package io.github.jinlongliao.mybatis.workbench.provider;

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.PsiClassReferenceType;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.uast.UElement;
import org.jetbrains.uast.UastUtils;

import java.util.Collection;
import java.util.Optional;

import io.github.jinlongliao.mybatis.workbench.annotation.Annotation;
import io.github.jinlongliao.mybatis.workbench.dom.model.Mapper;
import io.github.jinlongliao.mybatis.workbench.util.Icons;
import io.github.jinlongliao.mybatis.workbench.util.JavaUtils;
import io.github.jinlongliao.mybatis.workbench.util.MapperUtils;

/**
 * @author yanglin
 */
public class InjectionLineMarkerProvider extends RelatedItemLineMarkerProvider {

	@Override
	protected void collectNavigationMarkers(@NotNull PsiElement element, @NotNull Collection<? super RelatedItemLineMarkerInfo<?>> result) {
		UElement uElement = UastUtils.getUParentForIdentifier(element);
		if (uElement == null) {
			return;
		}

		PsiElement identifier = uElement.getJavaPsi();
		if (identifier == null) {
			return;
		}
		if (!(identifier instanceof PsiField)) return;
		PsiField field = (PsiField) identifier;
		if (!isTargetField(field)) return;

		PsiType type = field.getType();
		if (!(type instanceof PsiClassReferenceType)) return;

		Optional<PsiClass> clazz = JavaUtils.findClazz(identifier.getProject(), type.getCanonicalText());
		if (!clazz.isPresent()) return;

		PsiClass psiClass = clazz.get();
		Optional<Mapper> mapper = MapperUtils.findFirstMapper(identifier.getProject(), psiClass);
		if (!mapper.isPresent()) return;

		NavigationGutterIconBuilder<PsiElement> builder =
			NavigationGutterIconBuilder.create(Icons.SPRING_INJECTION_ICON)
				.setAlignment(GutterIconRenderer.Alignment.CENTER)
				.setTarget(psiClass)
				.setTooltipTitle("Data access object found - " + psiClass.getQualifiedName());
		result.add(builder.createLineMarkerInfo(field.getNameIdentifier()));
	}

	private boolean isTargetField(PsiField field) {
		if (JavaUtils.isAnnotationPresent(field, Annotation.AUTOWIRED)) {
			return true;
		}
		Optional<PsiAnnotation> resourceAnno = JavaUtils.getPsiAnnotation(field, Annotation.RESOURCE);
		if (resourceAnno.isPresent()) {
			PsiAnnotationMemberValue nameValue = resourceAnno.get().findAttributeValue("name");
			String name = nameValue.getText().replaceAll("\"", "");
			return StringUtils.isBlank(name) || name.equals(field.getName());
		}
		return false;
	}

}
