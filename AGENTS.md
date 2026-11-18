# 项目协作指南

## 项目结构

- 单模块 IntelliJ IDEA 插件，使用 Java 和 Gradle Kotlin DSL。SDK、第三方依赖和 Java 工具链集中在 `build.gradle.kts`，Wrapper 版本由 `gradle/wrapper/gradle-wrapper.properties` 管理。
- `src/main/java/com/wuzhizhan/mybatis2/` 下的 `dom`、`provider`、`reference`、`inspection` 等包负责 XML 解析、导航、引用和检查；`action`、`ui`、`setting`、`generate` 负责生成器入口、配置和生成。
- `generate/IdeaTableConnectionFactory.java` 将 IDEA 表结构提供给 MyBatis Generator。`src/main/java/org/mybatis/generator/api/intellij/` 保存表及列快照。升级生成器时核对 JDBC 元数据契约、生成回调、XML 合并和覆盖写入。
- `src/main/resources/META-INF/plugin.xml` 注册扩展、服务、动作和插件运行时依赖。图标、模板和其他打包资源位于 `src/main/resources/`。

## 修改原则

- 默认使用简体中文沟通和编写文档，保留代码标识、路径及固定文件名。开始前检查工作区和相邻实现。仓库存在 `.codegraph/` 时，先用 `codegraph explore` 定位符号及调用链。
- 保留用户已有改动，控制修改范围，优先复用相邻代码和平台 API。开源构建使用 Gradle 声明公开依赖，避免为本机 JAR、私有仓库或机器绝对路径引入构建配置。
- 遵循 `.editorconfig`、`.gitattributes` 和同模块相邻代码。新增 Java 类型的类级 Javadoc 按顺序写 `@author liaojinlong`、`@since`，创建时间取执行机器本地时间，格式为 `yyyy/MM/dd HH:mm`。维护已有类型时保留原有署名和时间。修改业务逻辑时同步维护注释，说明关键边界与失败处理。
- 修改 PSI、索引或 UI 逻辑时，沿用平台读写动作及线程约束，核对索引未就绪时的行为。生成器改动同时检查项目已有文件、覆盖选项以及生成失败时已写入的文件。

## 依赖和版本

- 使用 JDK 25 和仓库 Wrapper。升级 IDEA SDK 时，核对 JetBrains 的平台构建号、Java 基线和 API 变更。涉及内置插件或模块迁移，同时更新 `build.gradle.kts` 的编译依赖和 `plugin.xml` 的运行时依赖。IDEA 2026.3 的 XML PSI/DOM 依赖 `com.intellij.xml`，Spring MyBatis Provider 位于 `intellij.spring.core.analysis.jvm`。
- 修改插件版本时同步维护 `build.gradle.kts`、`plugin.xml` 和 README 中的版本示例。变更最低兼容版本时同步核对两处 `sinceBuild` 限制。发布说明集中维护在 `plugin.xml` 的 `<change-notes>`，修改后检查最终 ZIP 内的描述文件。
- 设置搜索索引由 `intellijPlatform.buildSearchableOptions` 统一控制。只禁用生成任务会让后续打包任务找不到输入目录。

## 验证与交付

- 文档修改检查相对链接、命令、配置值及 `git diff --check`。依赖、插件描述符和 Java 改动执行 `./gradlew buildPlugin verifyPluginStructure`，核对 ZIP 中实际的版本、兼容范围和依赖。
- 缺陷先复现再修复，按风险选择现有验证。只为重要行为或明确的回归缺口增加测试，优先补充相邻用例，临时验证产物用完清理。生成器改动执行 `./gradlew test`，覆盖生成、合并、冲突保护和覆盖写入。
- 平台或运行时依赖升级时，用 `./gradlew runIde` 或安装构建产物验证插件加载、Mapper 双向跳转、Spring Mapper 识别和数据库表生成。分别报告编译、结构检查与 IDE 实测的结果，明确未验证项。
- 未经用户明确授权，只执行 Git 只读检查。禁止暂存、提交、推送、拉取、切换分支、合并、变基或清理工作区。交付只包含需求涉及的改动，构建产物、IDE 本地状态和临时文件留在工作区外或忽略目录。
