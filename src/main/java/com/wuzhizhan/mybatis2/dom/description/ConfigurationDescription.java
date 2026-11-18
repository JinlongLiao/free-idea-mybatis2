package com.wuzhizhan.mybatis2.dom.description;

import com.intellij.psi.xml.XmlFile;
import com.intellij.util.xml.DomFileDescription;
import com.wuzhizhan.mybatis2.dom.model.Configuration;
import com.wuzhizhan.mybatis2.util.DomUtils;
import org.jetbrains.annotations.NotNull;

/**
 * @author yanglin
 */
public class ConfigurationDescription extends DomFileDescription<Configuration> {

    public ConfigurationDescription() {
        super(Configuration.class, "configuration");
    }

    /** {@inheritDoc} 始终按 MyBatis 文件声明识别 XML。 */
    @Override
    public boolean isMyFile(@NotNull XmlFile file) {
        return DomUtils.isMybatisConfigurationFile(file);
    }

}
