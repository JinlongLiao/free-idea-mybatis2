package com.wuzhizhan.mybatis2.dom.description;

import com.intellij.psi.xml.XmlFile;
import com.intellij.util.xml.DomFileDescription;
import com.wuzhizhan.mybatis2.dom.model.Mapper;
import com.wuzhizhan.mybatis2.util.DomUtils;
import org.jetbrains.annotations.NotNull;

/**
 * @author yanglin
 */
public class MapperDescription extends DomFileDescription<Mapper> {

    public MapperDescription() {
        super(Mapper.class, "mapper");
    }

    /** {@inheritDoc} 始终按 MyBatis 文件声明识别 XML。 */
    @Override
    public boolean isMyFile(@NotNull XmlFile file) {
        return DomUtils.isMybatisFile(file);
    }

}
