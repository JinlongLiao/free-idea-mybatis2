# Free Enhanced MyBatis2

Free Enhanced MyBatis2 是一款 IntelliJ IDEA 插件，用于在 Mapper 接口和 XML 之间导航，并通过 IDEA 的 Database 工具生成 MyBatis 代码。

## 功能

- 在 Mapper 接口的方法与 XML 中对应的 SQL 语句之间双向跳转。
- 提供 Mapper XML 模板、参数补全、检查和快速修复。
- 通过 MyBatis Generator 界面按数据库表生成模型、Mapper 接口和 XML，支持按配置生成 Swagger 模型注解。
- 识别 Spring XML 和 `@MapperScan` 中注册的 Mapper。

## 兼容性

| 项目 | 当前配置 |
| --- | --- |
| 插件版本 | `0.0.3` |
| 目标 IntelliJ IDEA | `2026.3`，构建号 `263.7307.200` |
| 最低平台分支 | `263`（IDEA 2026.3） |
| 构建环境 | JDK 25、Gradle Wrapper 9.9.0 |

运行插件需要启用 IDEA 内置的 Java、XML、Spring 和 Database Tools 支持。请在具备相应功能的 IntelliJ IDEA 环境中安装。

## 构建与安装

使用 JDK 25，将 `JAVA_HOME` 指向对应 JDK 后，在仓库根目录执行：

```bash
./gradlew test buildPlugin verifyPluginStructure
```

Windows PowerShell 使用 `./gradlew.bat test buildPlugin verifyPluginStructure`。首次构建需要下载 Gradle、IDEA SDK 和 Maven 依赖。构建完成后，在 IDEA 的 **Settings / Preferences → Plugins → 齿轮菜单 → Install Plugin from Disk…** 选择 `build/distributions/free-idea-mybatis2-0.0.3.zip`，并按 IDE 提示重启。

开发时可用 `./gradlew runIde` 启动隔离的开发实例。插件结构检查验证打包产物，实际功能请在 IDE 中用 MyBatis 项目验证。

## 使用

1. 打开同时包含 Mapper 接口和对应 XML 的项目。确认 XML 的 `namespace` 对应接口全限定名，SQL 语句的 `id` 对应接口方法名。
2. 点击 Mapper 方法或 XML 语句旁的行标记，在接口和语句之间跳转。
3. 生成代码时，在 **Database** 工具窗口连接数据源，选中一张或多张表，右键选择 **Mybatis-Generator**。确认生成路径、包名及覆盖选项后再执行生成。生成器设置位于 **Settings / Preferences → Tools → Mybatis Generator Setting**。

生成器使用 MyBatis Generator 2.0.0。关闭 XML 覆盖时，旧版没有生成标记的 Mapper 若与新生成的语句存在同名标识，插件会保留原 XML 并提示冲突。开启 XML 覆盖会替换整个 Mapper XML，请先保存其中的手写 SQL。

## 许可证

本仓库采用 [MIT License](LICENSE)。
