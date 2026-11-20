package io.github.jinlongliao.mybatis.workbench.generate;

import com.intellij.psi.PsiMethod;

import org.jetbrains.annotations.NotNull;

import io.github.jinlongliao.mybatis.workbench.dom.model.GroupTwo;
import io.github.jinlongliao.mybatis.workbench.dom.model.Mapper;

/**
 * @author yanglin
 */
public class InsertGenerator extends StatementGenerator {

	public InsertGenerator(@NotNull String... patterns) {
		super(patterns);
	}

	@NotNull
	@Override
	protected GroupTwo getTarget(@NotNull Mapper mapper, @NotNull PsiMethod method) {
		return mapper.addInsert();
	}

	@NotNull
	@Override
	public String getId() {
		return "InsertGenerator";
	}

	@NotNull
	@Override
	public String getDisplayText() {
		return "Insert Statement";
	}
}
