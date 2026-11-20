package io.github.jinlongliao.mybatis.workbench.dom.description;

import com.intellij.psi.xml.XmlFile;
import com.intellij.util.xml.DomFileDescription;

import org.jetbrains.annotations.NotNull;

import io.github.jinlongliao.mybatis.workbench.dom.model.Mapper;
import io.github.jinlongliao.mybatis.workbench.util.DomUtils;

/**
 * @author yanglin
 */
public class MapperDescription extends DomFileDescription<Mapper> {

	public MapperDescription() {
		super(Mapper.class, "mapper");
	}

	/**
	 * {@inheritDoc} 始终按 MyBatis 文件声明识别 XML。
	 */
	@Override
	public boolean isMyFile(@NotNull XmlFile file) {
		return DomUtils.isMybatisFile(file);
	}

}
