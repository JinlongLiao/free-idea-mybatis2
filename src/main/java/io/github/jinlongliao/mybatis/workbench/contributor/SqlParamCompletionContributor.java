package io.github.jinlongliao.mybatis.workbench.contributor;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;

import java.util.Optional;

import io.github.jinlongliao.mybatis.workbench.dom.model.IdDomElement;
import io.github.jinlongliao.mybatis.workbench.util.DomUtils;
import io.github.jinlongliao.mybatis.workbench.util.MapperUtils;


/**
 * @author yanglin
 */
public class SqlParamCompletionContributor extends CompletionContributor {

	@Override
	public void fillCompletionVariants(CompletionParameters parameters, final CompletionResultSet result) {
		if (parameters.getCompletionType() != CompletionType.BASIC) {
			return;
		}

		PsiElement position = parameters.getPosition();
		Project project = position.getProject();
		PsiFile topLevelFile = InjectedLanguageManager.getInstance(project).getTopLevelFile(position);
		if (DomUtils.isMybatisFile(topLevelFile)) {
			if (shouldAddElement(position.getContainingFile(), parameters.getOffset())) {
				process(topLevelFile, result, position);
			}
		}
	}

	private void process(PsiFile xmlFile, CompletionResultSet result, PsiElement position) {
		Project project = position.getProject();
		int offset = InjectedLanguageManager.getInstance(project).injectedToHost(position, position.getTextOffset());
		Optional<IdDomElement> idDomElement = MapperUtils.findParentIdDomElement(xmlFile.findElementAt(offset));
		if (idDomElement.isPresent()) {
			TestParamContributor.addElementForPsiParameter(project, result, idDomElement.get());
			result.stopHere();
		}
	}

	private boolean shouldAddElement(PsiFile file, int offset) {
		String text = file.getText();
		for (int i = offset - 1; i > 0; i--) {
			char c = text.charAt(i);
			if (c == '{' && text.charAt(i - 1) == '#') return true;
		}
		return false;
	}
}
