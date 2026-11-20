package io.github.jinlongliao.mybatis.workbench.dom.description;

import com.intellij.psi.xml.XmlFile;
import com.intellij.util.xml.DomFileDescription;

import org.jetbrains.annotations.NotNull;

import io.github.jinlongliao.mybatis.workbench.dom.model.Configuration;
import io.github.jinlongliao.mybatis.workbench.util.DomUtils;

/**
 * @author yanglin
 */
public class ConfigurationDescription extends DomFileDescription<Configuration> {

	public ConfigurationDescription() {
		super(Configuration.class, "configuration");
	}

	/**
	 * {@inheritDoc} 始终按 MyBatis 文件声明识别 XML。
	 */
	@Override
	public boolean isMyFile(@NotNull XmlFile file) {
		return DomUtils.isMybatisConfigurationFile(file);
	}

}
